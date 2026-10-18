(ns jonquil.mcp.listen-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.evaluator :as ev]
            [jonquil.mcp :as mcp]
            [jonquil.mcp.json :as json]
            [jonquil.mcp.listen :as listen]
            [jonquil.mcp.window]
            [temp-dirs])
  (:import (java.io IOException)
           (java.lang ProcessHandle)
           (java.net ConnectException InetAddress ServerSocket Socket)))

(use-fixtures :once temp-dirs/fixture)

(defn- settings-path []
  (str (temp-dirs/temp-dir! "jonquil-mcp-listen-test-") "/settings.edn"))

(defn- own-pid [] (.pid (ProcessHandle/current)))

(defn- evaluator []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ev/Evaluator
    (evaluating? [_] false)
    (waiting-for-input? [_] false)))

(defn- fake-ui []
  {:mcp-eval!* (fn [_ notify]
                 (notify {:done {:result {:status :ok :printed "3"} :output ""}})
                 (fn [] ""))
   :evaluator* (fn [] (evaluator))})

(defn- start! [] (listen/start-listener! {:ui (fake-ui) :dir "/tmp" :gui (fn [f] (f)) :settings-path (settings-path)}))

(defn- connect [port]
  (let [s (Socket. (InetAddress/getByName "127.0.0.1") (int port))]
    (.setSoTimeout s 10000)
    {:socket s
     :in (io/reader (.getInputStream s) :encoding "UTF-8")
     :out (io/writer (.getOutputStream s) :encoding "UTF-8")}))

(defn- send! [c m]
  (.write ^java.io.Writer (:out c) (str (json/encode m) "\n"))
  (.flush ^java.io.Writer (:out c)))

(defn- recv! [c] (json/decode (.readLine ^java.io.BufferedReader (:in c))))

(def ^:private init-msg
  {"jsonrpc" "2.0" "id" 1 "method" "initialize"
   "params" {"protocolVersion" "2024-11-05" "capabilities" {} "clientInfo" {"name" "t" "version" "0"}}})

(deftest registry-dir-under-settings
  (is (= "/x/jonquil/mcp" (listen/registry-dir "/x/jonquil/settings.edn"))))

(deftest registry-round-trip
  (let [sp (settings-path)
        pid (own-pid)]
    (listen/write-entry! sp {:dir "/d" :port 4242 :pid pid})
    (let [es (listen/live-entries sp)]
      (is (= 1 (count es)))
      (is (= {:dir "/d" :port 4242 :pid pid} (select-keys (first es) [:dir :port :pid])))
      (is (number? (:file-mtime (first es)))))
    (listen/delete-entry! sp pid)
    (is (empty? (listen/live-entries sp)))))

(deftest live-entries-filters-dead
  (let [sp (settings-path)]
    (listen/write-entry! sp {:dir "/d" :port 1 :pid 999999})
    (listen/write-entry! sp {:dir "/e" :port 2 :pid (own-pid)})
    (is (= [(own-pid)] (map :pid (listen/live-entries sp))))))

(deftest housekeep-removes-dead
  (let [sp (settings-path)
        dead (io/file (listen/registry-dir sp) "999999.edn")
        live (io/file (listen/registry-dir sp) (str (own-pid) ".edn"))]
    (listen/write-entry! sp {:dir "/d" :port 1 :pid 999999})
    (listen/write-entry! sp {:dir "/e" :port 2 :pid (own-pid)})
    (listen/housekeep! sp)
    (is (not (.exists dead)))
    (is (.exists live))))

(deftest listener-serves-a-real-socket
  (let [l (start!)
        c (connect (:port l))]
    (try
      (send! c init-msg)
      (is (= "2024-11-05" (get-in (recv! c) ["result" "protocolVersion"])))
      (send! c {"jsonrpc" "2.0" "id" 2 "method" "tools/call"
                "params" {"name" "jonquil_eval" "arguments" {"code" "(+ 1 2)"}}})
      (let [text (get-in (recv! c) ["result" "content" 0 "text"])]
        (is (str/ends-with? text "=> 3") text))
      (finally (.close (:socket c)) ((:stop! l))))))

(deftest detach-then-second-client
  (let [l (start!)
        c1 (connect (:port l))]
    (try
      (send! c1 init-msg)
      (recv! c1)
      (.close (:socket c1))
      (let [c2 (connect (:port l))]
        (try
          (send! c2 init-msg)
          (is (= "2024-11-05" (get-in (recv! c2) ["result" "protocolVersion"])))
          (finally (.close (:socket c2)))))
      (finally ((:stop! l))))))

(deftest listener-survives-serve-throw
  (let [err (java.io.StringWriter.)
        calls (atom 0)
        real mcp/serve!]
    (binding [*err* err]
      (with-redefs [mcp/serve! (fn [m]
                                 (when (= 1 (swap! calls inc))
                                   (throw (ex-info "boom from serve" {})))
                                 (real m))]
        (let [l (start!)]
          (try
            (let [c1 (connect (:port l))]
              ;; the throw closes the connection: the client reads EOF
              (is (nil? (.readLine ^java.io.BufferedReader (:in c1))))
              (.close (:socket c1)))
            (let [c2 (connect (:port l))]
              (try
                (send! c2 init-msg)
                (is (= "2024-11-05" (get-in (recv! c2) ["result" "protocolVersion"])))
                (finally (.close (:socket c2))))
              (is (str/includes? (str err) "boom from serve")))
            (finally ((:stop! l)))))))))

(defn- entry [sp] (read-string (slurp (io/file (listen/registry-dir sp) (str (own-pid) ".edn")))))

(deftest set-dir-rewrites-the-entry-and-the-next-client
  (let [sp (settings-path)
        dirs (atom [])
        real jonquil.mcp.window/make-window-tools
        l (listen/start-listener! {:ui (fake-ui) :dir "/tmp" :gui (fn [f] (f)) :settings-path sp})]
    (with-redefs [jonquil.mcp.window/make-window-tools (fn [opts] (swap! dirs conj (:dir opts)) (real opts))]
      (try
        (is (= "/tmp" (:dir (entry sp))))
        ((:set-dir! l) "/elsewhere")
        (is (= {:dir "/elsewhere" :port (:port l) :pid (own-pid)} (entry sp)) "same port and pid, new folder")
        (let [c (connect (:port l))]
          (send! c init-msg)
          (recv! c)
          (.close (:socket c)))
        (is (= ["/elsewhere"] @dirs) "a client after the move is served the new folder")
        (finally ((:stop! l)))))))

(deftest set-dir-after-stop-writes-nothing
  (let [sp (settings-path)
        l (listen/start-listener! {:ui (fake-ui) :dir "/tmp" :gui (fn [f] (f)) :settings-path sp})
        f (io/file (listen/registry-dir sp) (str (own-pid) ".edn"))]
    ((:stop! l))
    ((:set-dir! l) "/elsewhere")
    (is (not (.exists f)))))

(deftest follow-root-calls-set-dir-on-a-re-root
  (let [tree (atom {:root "/a"})
        calls (atom [])
        d (temp-dirs/temp-dir! "jonquil-mcp-listen-test-")]
    (listen/follow-root! tree {:set-dir! (fn [x] (swap! calls conj x))})
    (swap! tree assoc :expanded #{})
    (is (= [] @calls) "no re-root, no call")
    (swap! tree assoc :root d)
    (is (= [(.getCanonicalPath (io/file d))] @calls))))

(deftest follow-root-can-be-undone
  (let [tree (atom {:root "/a"})
        calls (atom [])
        unfollow! (listen/follow-root! tree {:set-dir! (fn [x] (swap! calls conj x))})]
    (unfollow!)
    (swap! tree assoc :root "/b")
    (is (= [] @calls))))

(deftest a-rewritten-entry-is-whole-and-leaves-no-temp
  (let [sp (settings-path)
        l (listen/start-listener! {:ui (fake-ui) :dir "/tmp" :gui (fn [f] (f)) :settings-path sp})]
    (try
      (dotimes [i 20] ((:set-dir! l) (str "/d" i)))
      (is (= "/d19" (:dir (entry sp))))
      (is (= [(str (own-pid) ".edn")] (vec (.list (io/file (listen/registry-dir sp))))) "no temp file left")
      (finally ((:stop! l))))))

(deftest stop-deletes-entry-and-refuses
  (let [sp (settings-path)
        l (listen/start-listener! {:ui (fake-ui) :dir "/tmp" :gui (fn [f] (f)) :settings-path sp})
        entry (io/file (listen/registry-dir sp) (str (own-pid) ".edn"))]
    (is (.exists entry))
    ((:stop! l))
    ((:stop! l))
    (is (not (.exists entry)))
    (is (thrown? ConnectException (connect (:port l))))))

(deftest start-failure-releases-the-port
  (let [seen (atom nil)]
    (with-redefs [listen/write-entry! (fn [_ e]
                                        (reset! seen (:port e))
                                        (throw (ex-info "no registry" {})))]
      (is (thrown? Exception (start!))))
    (is (some? @seen))
    (.close (ServerSocket. (int @seen) 50 (InetAddress/getByName "127.0.0.1")))))

(deftest accept-failure-gives-up-and-unregisters
  (let [sp (settings-path)
        entry (io/file (listen/registry-dir sp) (str (own-pid) ".edn"))
        err (java.io.StringWriter.)
        t0 (System/nanoTime)]
    (binding [*err* err]
      (with-redefs [listen/accept-conn (fn [_] (throw (IOException. "accept boom")))]
        (let [l (listen/start-listener! {:ui (fake-ui) :dir "/tmp" :gui (fn [f] (f)) :settings-path sp})]
          (loop []
            (when (and (.exists entry) (< (/ (- (System/nanoTime) t0) 1e6) 5000))
              (Thread/sleep 20)
              (recur)))
          (is (not (.exists entry)))
          (is (< (/ (- (System/nanoTime) t0) 1e6) 5000))
          (is (str/includes? (str err) "accept boom"))
          (is (str/includes? (str err) "giving up"))
          (is (thrown? ConnectException (connect (:port l)))))))))

;; --- discovery and the --mcp-attach bridge -----------------------------------

(defn- canon [p] (.getCanonicalPath (io/file p)))

(deftest pick-entry-matches-canonical-dir
  (let [e {:dir (canon "/tmp") :port 1 :pid 1 :file-mtime 5}
        r (listen/pick-entry [e {:dir "/nowhere/else" :port 2 :pid 2 :file-mtime 9}] "/tmp/../tmp")]
    (is (= e (:entry r)))))

(deftest pick-entry-newest-of-two-matches
  (let [old {:dir "/d" :port 1 :pid 1 :file-mtime 5}
        new {:dir "/d" :port 2 :pid 2 :file-mtime 9}]
    (is (= new (:entry (listen/pick-entry [old new] "/d"))))
    (is (= new (:entry (listen/pick-entry [new old] "/d"))))))

(deftest pick-entry-no-dir-single-wins
  (let [e {:dir "/d" :port 1 :pid 1 :file-mtime 5}]
    (is (= e (:entry (listen/pick-entry [e] nil))))))

(deftest pick-entry-no-dir-zero-entries-errors
  (let [r (listen/pick-entry [] nil)]
    (is (string? (:error r)))
    (is (nil? (:entry r)))))

(deftest discover-ambiguous-lists
  (let [r (listen/pick-entry [{:dir "/proj/a" :port 4111 :pid 1 :file-mtime 1}
                              {:dir "/proj/b" :port 4222 :pid 2 :file-mtime 2}] nil)
        s (:error r)]
    (is (str/includes? s "/proj/a"))
    (is (str/includes? s "4111"))
    (is (str/includes? s "/proj/b"))
    (is (str/includes? s "4222"))))

(deftest pick-entry-dir-without-match-lists-windows
  (let [s (:error (listen/pick-entry [{:dir "/proj/a" :port 4111 :pid 1 :file-mtime 1}] "/other"))]
    (is (str/includes? s "/proj/a"))
    (is (str/includes? s "4111"))))

(deftest discover-reads-the-registry
  (let [sp (settings-path)]
    (listen/write-entry! sp {:dir (canon "/tmp") :port 77 :pid (own-pid)})
    (is (= 77 (get-in (listen/discover sp "/tmp") [:entry :port])))
    (is (= 77 (get-in (listen/discover sp nil) [:entry :port])))))

(defn- lines-fn
  "A :read-line fn over a fixed seq of lines; nil at the end."
  [lines]
  (let [q (atom (seq lines))]
    (fn [] (let [l (first @q)] (swap! q next) l))))

(deftest bridge-discovery-error-returns-1
  (let [err (java.io.StringWriter.)
        out (atom [])
        code (binding [*err* err]
               (listen/bridge! {:read-line (lines-fn []) :write-line #(swap! out conj %)
                                :settings-path (settings-path) :dir nil}))]
    (is (= 1 code))
    (is (str/includes? (str err) "no Jonquil window"))
    (is (empty? @out))))

(deftest bridge-relays-both-ways-and-stdin-eof-returns-0
  (let [sp (settings-path)
        ss (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        got (promise)
        out (atom [])
        ;; a one-shot echo server: replies "echo:<line>" per line
        srv (future
              (with-open [s (.accept ss)]
                (let [in (io/reader (.getInputStream s))
                      w (io/writer (.getOutputStream s))]
                  (loop [acc []]
                    (if-let [l (.readLine ^java.io.BufferedReader in)]
                      (do (.write ^java.io.Writer w (str "echo:" l "\n"))
                          (.flush ^java.io.Writer w)
                          (recur (conj acc l)))
                      (deliver got acc))))))]
    (try
      (listen/write-entry! sp {:dir (canon "/tmp") :port (.getLocalPort ss) :pid (own-pid)})
      (let [fin (promise)
            ;; stdin gives two lines, then waits for both echoes before EOF
            n (atom 0)
            read-line (let [rl (lines-fn ["a" "b"])]
                        (fn [] (or (rl) (do (deref fin 5000 nil) nil))))
            code (listen/bridge! {:read-line read-line
                                  :write-line (fn [s] (swap! out conj s)
                                                (when (= 2 (swap! n inc)) (deliver fin true)))
                                  :settings-path sp :dir "/tmp"})]
        (is (= 0 code))
        (is (= ["echo:a" "echo:b"] @out))
        (is (= ["a" "b"] (deref got 5000 :timeout))))
      (finally (.close ss) (future-cancel srv)))))

(deftest bridge-socket-eof-returns-0
  (let [sp (settings-path)
        ss (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        srv (future (.close (.accept ss)))]
    (try
      (listen/write-entry! sp {:dir (canon "/tmp") :port (.getLocalPort ss) :pid (own-pid)})
      (let [block (promise)
            code (listen/bridge! {:read-line (fn [] (deref block 20000 nil))
                                  :write-line (fn [_] nil)
                                  :settings-path sp :dir "/tmp"})]
        (is (= 0 code))
        (deliver block nil))
      (finally (.close ss) (future-cancel srv)))))

(deftest pick-entry-nil-dir-entry-never-matches-a-dir-query
  (let [good {:dir (canon "/tmp") :port 1 :pid 1 :file-mtime 5}
        bad {:dir nil :port 2 :pid 2 :file-mtime 9}]
    (is (= good (:entry (listen/pick-entry [bad good] "/tmp"))))
    (is (= bad (:entry (listen/pick-entry [bad] nil))))
    (is (str/includes? (:error (listen/pick-entry [bad] "/other")) "(no folder)"))))

(deftest unusable-entries-are-skipped
  (let [sp (settings-path)
        d (io/file (listen/registry-dir sp))]
    (.mkdirs d)
    (spit (io/file d "junk.edn") "{:pid")
    (spit (io/file d "neg.edn") (pr-str {:dir "/d" :port 1 :pid -1}))
    (spit (io/file d "zero.edn") (pr-str {:dir "/d" :port 1 :pid 0}))
    (listen/write-entry! sp {:dir "/e" :port 2 :pid (own-pid)})
    (is (= [(own-pid)] (map :pid (listen/live-entries sp))))
    (listen/housekeep! sp)))

(defn- one-shot-server
  "Accepts one client and runs (f socket); returns {:ss ss :srv future}."
  [f]
  (let [ss (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
    {:ss ss :srv (future (with-open [s (.accept ss)] (f s)))}))

(deftest bridge-relay-failure-logs-but-eof-detach-does-not
  (let [sp (settings-path)
        err (java.io.StringWriter.)
        ;; failure: the server sends a line, the write-line throws
        {:keys [ss srv]} (one-shot-server
                          (fn [s]
                            (let [w (io/writer (.getOutputStream s))]
                              (.write ^java.io.Writer w "hello\n")
                              (.flush ^java.io.Writer w)
                              (Thread/sleep 300))))]
    (try
      (listen/write-entry! sp {:dir (canon "/tmp") :port (.getLocalPort ss) :pid (own-pid)})
      (let [block (promise)
            code (binding [*err* err]
                   (listen/bridge! {:read-line (fn [] (deref block 20000 nil))
                                    :write-line (fn [_] (throw (ex-info "pipe broke" {})))
                                    :settings-path sp :dir "/tmp"}))]
        (deliver block nil)
        (is (= 0 code))
        (is (str/includes? (str err) "jonquil: the attach relay failed: pipe broke")))
      (finally (.close ss) (future-cancel srv))))
  (let [sp (settings-path)
        err (java.io.StringWriter.)
        {:keys [ss srv]} (one-shot-server (fn [_] nil))]
    (try
      (listen/write-entry! sp {:dir (canon "/tmp") :port (.getLocalPort ss) :pid (own-pid)})
      (let [block (promise)
            code (binding [*err* err]
                   (listen/bridge! {:read-line (fn [] (deref block 20000 nil))
                                    :write-line (fn [_] nil)
                                    :settings-path sp :dir "/tmp"}))]
        (deliver block nil)
        (is (= 0 code))
        (is (not (str/includes? (str err) "relay failed")) (str err)))
      (finally (.close ss) (future-cancel srv)))))
