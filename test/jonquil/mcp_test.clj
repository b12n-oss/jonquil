(ns jonquil.mcp-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.mcp :as mcp]
            [jonquil.mcp.json :as json]
            [jonquil.version :as version])
  (:import (java.util.concurrent LinkedBlockingQueue)))

(def ^:private stub-defs
  [{"name" "echo" "description" "echo s" "inputSchema" {"type" "object"}}
   {"name" "boom" "description" "throws" "inputSchema" {"type" "object"}}
   {"name" "nope" "description" "error result" "inputSchema" {"type" "object"}}])

(def ^:private tools
  {:defs stub-defs
   :call (fn [tname args]
           (case tname
             "echo" {:text (get args "s") :error? false}
             "boom" (throw (ex-info "bad" {}))
             "nope" {:text "nope" :error? true}))})

(defn- req [id method params]
  {"jsonrpc" "2.0" "id" id "method" method "params" params})

(deftest initialize-echoes-supported-version
  (let [r (mcp/handle-message tools (req 1 "initialize" {"protocolVersion" "2025-03-26"}))]
    (is (= 1 (get r "id")))
    (is (= {"protocolVersion" "2025-03-26"
            "capabilities" {"tools" {}}
            "serverInfo" {"name" "jonquil" "version" (version/current-version)}}
           (get r "result")))))

(deftest initialize-reports-the-running-version
  ;; it used to be a fixed "0.1.0", whatever was running
  (let [v (get-in (mcp/handle-message tools (req 1 "initialize" {"protocolVersion" "2025-06-18"}))
                  ["result" "serverInfo" "version"])]
    (is (= (version/current-version) v))
    ;; a number in a tagged checkout or a build, a bare commit hash in a
    ;; checkout without tags (CI's), "dev" outside git
    (is (and (string? v) (seq v)) (pr-str v))))

(deftest initialize-unknown-version-gets-newest
  (is (= "2025-06-18"
         (get-in (mcp/handle-message tools (req 1 "initialize" {"protocolVersion" "1999-01-01"}))
                 ["result" "protocolVersion"]))))

(deftest initialized-notification-has-no-reply
  (is (nil? (mcp/handle-message tools {"jsonrpc" "2.0" "method" "notifications/initialized"}))))

(deftest ping
  (is (= {} (get (mcp/handle-message tools (req 2 "ping" nil)) "result"))))

(deftest tools-list
  (is (= {"tools" stub-defs}
         (get (mcp/handle-message tools (req 3 "tools/list" nil)) "result"))))

(deftest tools-call-text
  (is (= {"content" [{"type" "text" "text" "hi"}] "isError" false}
         (get (mcp/handle-message tools (req 4 "tools/call" {"name" "echo" "arguments" {"s" "hi"}}))
              "result"))))

(deftest tools-call-error-result
  (let [r (get (mcp/handle-message tools (req 5 "tools/call" {"name" "nope" "arguments" {}})) "result")]
    (is (true? (get r "isError")))
    (is (= "nope" (get-in r ["content" 0 "text"])))))

(deftest tools-call-throw
  (let [r (get (mcp/handle-message tools (req 6 "tools/call" {"name" "boom" "arguments" {}})) "result")]
    (is (true? (get r "isError")))
    (is (= "Error: bad" (get-in r ["content" 0 "text"])))))

(deftest tools-call-unknown-tool
  (let [r (mcp/handle-message tools (req 7 "tools/call" {"name" "zap" "arguments" {}}))]
    (is (= 7 (get r "id")))
    (is (= -32602 (get-in r ["error" "code"])))
    (is (re-find #"zap" (get-in r ["error" "message"])))))

(deftest unknown-method
  (is (= -32601 (get-in (mcp/handle-message tools (req 8 "frob" nil)) ["error" "code"])))
  (is (nil? (mcp/handle-message tools {"jsonrpc" "2.0" "method" "frob"}))))

(deftest batch-refused
  (doseq [m [[(req 1 "ping" nil)] "ping"]]
    (let [r (mcp/handle-message tools m)]
      (is (= -32600 (get-in r ["error" "code"])))
      (is (contains? r "id"))
      (is (nil? (get r "id"))))))

(deftest serve-loop
  (let [lines (atom [(json/encode (req 1 "initialize" {"protocolVersion" "2025-06-18"}))
                     ""
                     "{bad"
                     (json/encode (req 2 "tools/call" {"name" "echo" "arguments" {"s" "yo"}}))])
        out (atom [])]
    (mcp/serve! {:tools tools
                 :read-line (fn [] (let [l (first @lines)] (swap! lines (comp vec rest)) l))
                 :write-line (fn [s] (swap! out conj s))})
    (is (= 3 (count @out)))
    (let [rs (mapv json/decode @out)]
      (is (= 1 (get (rs 0) "id")))
      (is (= -32700 (get-in rs [1 "error" "code"])))
      (is (contains? (rs 1) "id"))
      (is (nil? (get (rs 1) "id")))
      (is (= "yo" (get-in rs [2 "result" "content" 0 "text"]))))))

(defn- serve-lines [tools lines]
  (let [in (atom (vec lines))
        out (atom [])]
    (mcp/serve! {:tools tools
                 :read-line (fn [] (let [l (first @in)] (swap! in (comp vec rest)) l))
                 :write-line (fn [s] (swap! out conj s))})
    (mapv json/decode @out)))

(def ^:private throwing-defs-tools
  {:defs (map (fn [_] (throw (ex-info "defs broke" {}))) [1])
   :call (fn [_ _] nil)})

(deftest handler-throw-answers-internal-error-and-loop-continues
  (let [rs (serve-lines throwing-defs-tools
                        [(json/encode (req 7 "tools/call" {"name" "echo"}))
                         (json/encode (req 8 "ping" nil))])]
    (is (= 2 (count rs)))
    (is (= 7 (get (rs 0) "id")))
    (is (= -32603 (get-in rs [0 "error" "code"])))
    (is (= {} (get-in rs [1 "result"])))))

(deftest handler-throw-on-notification-is-silent
  (is (= [] (serve-lines throwing-defs-tools
                         [(json/encode {"jsonrpc" "2.0" "method" "tools/call"
                                        "params" {"name" "x"}})]))))

(deftest unencodable-response-does-not-end-loop
  (let [bad {:defs [{"name" "bad" "x" (fn [] 1)}] :call (fn [_ _] nil)}
        rs (serve-lines bad [(json/encode (req 3 "tools/list" nil))
                             (json/encode (req 4 "ping" nil))])]
    (is (= 2 (count rs)))
    (is (= 3 (get (rs 0) "id")))
    (is (= -32603 (get-in rs [0 "error" "code"])))
    (is (= 4 (get (rs 1) "id")))))

(deftest notifications-to-known-methods-get-no-reply
  (doseq [m ["ping" "tools/list" "tools/call" "initialize"]]
    (is (nil? (mcp/handle-message tools {"jsonrpc" "2.0" "method" m
                                         "params" {"name" "echo"}})))))

(deftest id-zero-is-a-request
  (is (= 0 (get (mcp/handle-message tools (req 0 "ping" nil)) "id"))))

(deftest nil-returning-tool-is-an-error
  (let [t {:defs stub-defs :call (fn [_ _] nil)}
        r (get (mcp/handle-message t (req 1 "tools/call" {"name" "echo"})) "result")]
    (is (true? (get r "isError")))
    (is (string? (get-in r ["content" 0 "text"])))))

(deftest nil-text-becomes-a-string
  (let [t {:defs stub-defs :call (fn [_ _] {:text nil :error? false})}
        r (get (mcp/handle-message t (req 1 "tools/call" {"name" "echo"})) "result")]
    (is (= "" (get-in r ["content" 0 "text"])))))

(deftest throw-without-message
  (let [t {:defs stub-defs :call (fn [_ _] (throw (ex-info nil {})))}
        r (get (mcp/handle-message t (req 1 "tools/call" {"name" "echo"})) "result")]
    (is (true? (get r "isError")))
    (is (not= "Error: " (get-in r ["content" 0 "text"])))))

(deftest unencodable-id-does-not-end-the-loop
  (let [rs (serve-lines throwing-defs-tools
                        ["{\"jsonrpc\":\"2.0\",\"id\":1e400,\"method\":\"ping\"}"
                         (json/encode (req 9 "ping" nil))])]
    (is (= 2 (count rs)))
    (is (contains? #{-32603 -32600} (get-in rs [0 "error" "code"])))
    (is (nil? (get (rs 0) "id")))
    (is (= 9 (get (rs 1) "id")))
    (is (= {} (get-in rs [1 "result"])))))

(deftest table-fn-is-read-per-message
  ;; a fn table is read on every message
  (let [t (atom tools)
        f (fn [] @t)
        names (fn [] (map #(get % "name") (get-in (mcp/handle-message f (req 1 "tools/list" {})) ["result" "tools"])))]
    (is (= ["echo" "boom" "nope"] (names)))
    (swap! t (fn [m] (-> m
                         (update :defs conj {"name" "late" "description" "added" "inputSchema" {"type" "object"}})
                         (assoc :call (fn [n a] (if (= "late" n) {:text "here" :error? false} ((:call tools) n a)))))))
    (is (= ["echo" "boom" "nope" "late"] (names)))
    (is (= "here" (get-in (mcp/handle-message f (req 2 "tools/call" {"name" "late" "arguments" {}}))
                          ["result" "content" 0 "text"])))
    (reset! t tools)
    (is (= -32602 (get-in (mcp/handle-message f (req 3 "tools/call" {"name" "late" "arguments" {}}))
                          ["error" "code"])))))

;; --- tools/list_changed ---------------------------------------

(deftest initialize-declares-list-changed
  (is (= {"tools" {"listChanged" true}}
         (get-in (mcp/handle-message (assoc tools :list-changed? true)
                                     (req 1 "initialize" {"protocolVersion" "2025-06-18"}))
                 ["result" "capabilities"]))))

(defn- wait-until
  "Polls (f) every 10 ms until it's truthy or ms pass; its last value."
  [f ms]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (let [v (f)]
        (if (or v (> (- (System/currentTimeMillis) t0) ms))
          v
          (do (Thread/sleep 10) (recur)))))))

(defn- live-serve
  "serve! on a future over a queue. :write-line defaults to conj onto :out.
  The fake :subscribe delivers notify! to :notify and counts unsubscribes."
  [& {:keys [write-line err]}]
  (let [q (LinkedBlockingQueue.)
        out (atom [])
        notify (promise)
        unsub (atom 0)
        done (future
               (binding [*err* (or err *err*)]
                 (mcp/serve! {:tools (assoc tools :list-changed? true)
                              :read-line (fn [] (let [l (.take q)] (when-not (= l ::eof) l)))
                              :write-line (or write-line (fn [s] (swap! out conj s)))
                              :subscribe (fn [n] (deliver notify n) (fn [] (swap! unsub inc)))})))]
    {:q q :out out :notify notify :unsub unsub :done done}))

(defn- send! [{:keys [q]} m] (.put ^LinkedBlockingQueue q (json/encode m)))
(defn- eof! [{:keys [q]}] (.put ^LinkedBlockingQueue q ::eof))
(def ^:private initialized {"jsonrpc" "2.0" "method" "notifications/initialized"})
(def ^:private list-changed {"jsonrpc" "2.0" "method" "notifications/tools/list_changed"})

(defn- ready! [s]
  (send! s (req 1 "initialize" {"protocolVersion" "2025-06-18"}))
  (send! s initialized)
  (send! s (req 2 "ping" nil))
  (wait-until #(= 2 (count @(:out s))) 2000))

(defn- notes [s] (filter #(= list-changed %) (map json/decode @(:out s))))

(deftest serve-sends-list-changed-after-initialized
  (let [s (live-serve)]
    (ready! s)
    ((deref (:notify s) 2000 nil))
    (is (wait-until #(= 1 (count (notes s))) 2000))
    (is (= [list-changed] (notes s)))
    (eof! s)
    (deref (:done s) 2000 nil)))

(deftest no-list-changed-before-initialized
  (let [s (live-serve)]
    (send! s (req 1 "initialize" {"protocolVersion" "2025-06-18"}))
    (wait-until #(= 1 (count @(:out s))) 2000)
    ((deref (:notify s) 2000 nil))
    (Thread/sleep 200)
    (send! s (req 2 "ping" nil))
    (wait-until #(= 2 (count @(:out s))) 2000)
    (is (= [1 2] (map #(get (json/decode %) "id") @(:out s))))
    (eof! s)
    (deref (:done s) 2000 nil)))

(deftest notify-does-not-block-the-caller
  (let [gate (promise)
        out (atom [])
        s (live-serve :write-line (fn [l] (when (= list-changed (json/decode l)) @gate) (swap! out conj l)))]
    (send! s (req 1 "initialize" {"protocolVersion" "2025-06-18"}))
    (send! s initialized)
    (send! s (req 2 "ping" nil))
    (wait-until #(= 2 (count @out)) 2000)
    (let [t0 (System/currentTimeMillis)]
      ((deref (:notify s) 2000 nil))
      (is (< (- (System/currentTimeMillis) t0) 500)))
    (deliver gate true)
    (eof! s)
    (deref (:done s) 2000 nil)))

(deftest notifications-coalesce
  (let [gate (promise)
        out (atom [])
        s (live-serve :write-line (fn [l] (when (= list-changed (json/decode l)) @gate) (swap! out conj l)))]
    (send! s (req 1 "initialize" {"protocolVersion" "2025-06-18"}))
    (send! s initialized)
    (send! s (req 2 "ping" nil))
    (wait-until #(= 2 (count @out)) 2000)
    (let [n (deref (:notify s) 2000 nil)]
      (n)
      (Thread/sleep 100)
      ;; spaced out, so each call's send has started if one was made
      (dotimes [_ 5] (n) (Thread/sleep 50)))
    (deliver gate true)
    (Thread/sleep 500)
    (let [k (count (filter #(= list-changed (json/decode %)) @out))]
      (is (<= 1 k 2) k))
    (eof! s)
    (deref (:done s) 2000 nil)))

(deftest writes-never-overlap
  (let [in-flight (atom 0)
        most (atom 0)
        out (atom [])
        s (live-serve :write-line (fn [l]
                                    (swap! most max (swap! in-flight inc))
                                    (Thread/sleep 1)
                                    (swap! out conj l)
                                    (swap! in-flight dec)))]
    (send! s (req 1 "initialize" {"protocolVersion" "2025-06-18"}))
    (send! s initialized)
    (send! s (req 2 "ping" nil))
    (wait-until #(= 2 (count @out)) 2000)
    (let [n (deref (:notify s) 2000 nil)
          f (future (dotimes [_ 50] (n) (Thread/sleep 1)))]
      (dotimes [i 50] (send! s (req (+ 10 i) "ping" nil)))
      @f)
    (wait-until #(<= 52 (count @out)) 5000)
    (Thread/sleep 200)
    (is (pos? (count (filter #(= list-changed (json/decode %)) @out))))
    (is (= 1 @most))
    (is (every? #(map? (json/decode %)) @out))
    (eof! s)
    (deref (:done s) 2000 nil)))

(deftest unsubscribes-at-eof
  (let [s (live-serve)]
    (ready! s)
    (eof! s)
    (deref (:done s) 2000 nil)
    (is (= 1 @(:unsub s))))
  (let [s (live-serve :write-line (fn [_] (throw (ex-info "pipe gone" {}))))]
    (send! s (req 1 "ping" nil))
    (is (thrown? Throwable (deref (:done s) 2000 nil)))
    (is (= 1 @(:unsub s)))))

(deftest failed-notification-is-logged
  (let [err (java.io.StringWriter.)
        out (atom [])
        s (live-serve :err err
                      :write-line (fn [l]
                                    (when (= list-changed (json/decode l)) (throw (ex-info "pipe gone" {})))
                                    (swap! out conj l)))]
    (send! s (req 1 "initialize" {"protocolVersion" "2025-06-18"}))
    (send! s initialized)
    (send! s (req 2 "ping" nil))
    (wait-until #(= 2 (count @out)) 2000)
    ((deref (:notify s) 2000 nil))
    (is (wait-until #(.contains (str err) "jonquil: couldn't send tools/list_changed: pipe gone") 2000) (str err))
    (send! s (req 3 "ping" nil))
    (is (wait-until #(= 3 (count @out)) 2000))
    (eof! s)
    (deref (:done s) 2000 nil)))

(deftest on-initialize-gets-client-info
  (let [seen (atom [])
        in (atom [(json/encode (req 1 "initialize" {"protocolVersion" "2025-06-18"
                                                    "clientInfo" {"name" "Claude Code" "version" "2"}}))])
        out (atom [])]
    (mcp/serve! {:tools tools
                 :on-initialize (fn [ci] (swap! seen conj ci))
                 :read-line (fn [] (let [l (first @in)] (swap! in (comp vec rest)) l))
                 :write-line (fn [s] (swap! out conj s))})
    (is (= [{"name" "Claude Code" "version" "2"}] @seen))
    (is (= 1 (count @out)))))

(deftest on-initialize-throw-is-ignored
  (let [in (atom [(json/encode (req 1 "initialize" {"protocolVersion" "2025-06-18"}))])
        out (atom [])]
    (mcp/serve! {:tools tools
                 :on-initialize (fn [_] (throw (ex-info "hook broke" {})))
                 :read-line (fn [] (let [l (first @in)] (swap! in (comp vec rest)) l))
                 :write-line (fn [s] (swap! out conj s))})
    (is (= 1 (count @out)))
    (is (= 1 (get (json/decode (first @out)) "id")))))
