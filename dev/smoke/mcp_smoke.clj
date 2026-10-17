;; Smoke of `jonquil --mcp` over real stdio. A babashka script (cheshire and
;; babashka.process are built in); it never touches the jolt process from the
;; inside. Run via `bb mcp:smoke` (a checkout, `jolt -M -m jonquil.main --mcp
;; DIR`) or `bb mcp:smoke --bin ./jonquil` (the built binary). With --attach it
;; starts a normal window with the listener on plus `--mcp-attach` bridges to
;; it (two processes, both destroyed on every exit path). With --window it
;; drives `--mcp --window` instead: a REAL window opens and takes focus, so
;; nobody should type while it runs.
;;
;; Every response is read with a deadline, so a hang fails a check instead of
;; blocking. stderr is inherited, so a hang or a crash is diagnosable. Every
;; process this script starts is destroyed on every exit path.
(ns mcp-smoke
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private results (atom []))

(defn- check! [step ok detail]
  (swap! results conj (boolean ok))
  (println (str "[mcp-smoke] " step " " (if ok "PASS" "FAIL")
                (when-not ok (str " " (pr-str detail))))))

(defn- bin-arg []
  (let [args *command-line-args*
        i (.indexOf ^java.util.List (vec args) "--bin")]
    (when (>= i 0) (nth args (inc i)))))

(defn- window? []
  (boolean (some #{"--window"} *command-line-args*)))

(defn- attach? []
  (boolean (some #{"--attach"} *command-line-args*)))

(defn- command
  "The argv for a Jonquil process: mode flags (may be empty) then dir."
  [mode dir]
  (if-let [bin (bin-arg)]
    (into [(str (fs/absolutize bin))] (conj mode dir))
    (into ["jolt" "-M" "-m" "jonquil.main"] (conj mode dir))))

(defn- launch!
  ([dir xdg] (launch! dir xdg (if (window?) ["--mcp" "--window"] ["--mcp"])))
  ([dir xdg mode]
   (let [proc (p/process (command mode dir)
                         {:err :inherit
                          :extra-env {"XDG_CONFIG_HOME" xdg}
                          :shutdown p/destroy-tree})
         q (LinkedBlockingQueue.)
         reader (io/reader (:out proc))]
     (future
       (try (loop []
              (when-let [l (.readLine ^java.io.BufferedReader reader)]
                (.put q l) (recur)))
            (catch Exception _ nil)
            (finally (.put q ::eof))))
     {:proc proc :q q :in (io/writer (:in proc)) :id (atom 0) :lines (atom [])})))

(defn- send! [{:keys [^java.io.Writer in]} msg]
  (.write in (str (json/generate-string msg) "\n"))
  (.flush in))

(defn- next-line
  "The next stdout line, or ::timeout / ::eof."
  [{:keys [^LinkedBlockingQueue q lines]} ms]
  (let [l (.poll q ms TimeUnit/MILLISECONDS)]
    (cond (nil? l) ::timeout
          (= l ::eof) ::eof
          :else (do (swap! lines conj l) l))))

(defn- request!
  "Sends a request and returns the parsed response, or {:fail why}."
  [srv method params ms]
  (let [id (swap! (:id srv) inc)]
    (send! srv {"jsonrpc" "2.0" "id" id "method" method "params" params})
    (let [l (next-line srv ms)]
      (if (string? l)
        (try (json/parse-string l)
             (catch Exception _ {:fail (str "not JSON: " l)}))
        {:fail l}))))

(defn- tool! [srv name args ms]
  (let [r (request! srv "tools/call" {"name" name "arguments" args} ms)]
    (if (:fail r)
      r
      {:text (get-in r ["result" "content" 0 "text"]) :error? (get-in r ["result" "isError"])})))

(defn- children [pid]
  (let [{:keys [out]} @(p/process ["pgrep" "-P" (str pid)] {:out :string :err :string})]
    (set (remove str/blank? (str/split-lines out)))))

(defn- handshake! [srv label]
  (let [r (request! srv "initialize" {"protocolVersion" "2025-06-18" "capabilities" {}
                                      "clientInfo" {"name" "mcp-smoke" "version" "0"}}
                    60000)]
    (check! (str label " initialize") (= "jonquil" (get-in r ["result" "serverInfo" "name"])) r)
    (send! srv {"jsonrpc" "2.0" "method" "notifications/initialized"})))

(defn- stop! [srv label]
  (.close ^java.io.Writer (:in srv))
  (let [r (deref (:proc srv) 20000 ::timeout)]
    (check! (str label " exits after stdin closes") (and (not= r ::timeout) (zero? (:exit r)))
            (if (= r ::timeout) "still running after 20 s" (:exit r)))
    (when (= r ::timeout) (p/destroy-tree (:proc srv)))))

(defn- main []
  (let [tmp (str (fs/create-temp-dir {:prefix "jonquil-mcp-smoke-"}))
        xdg (str tmp "/xdg")
        proj (str tmp "/proj")
        nrepl-proj (str tmp "/nrepl-proj")]
    (doseq [d [proj nrepl-proj]]
      (fs/create-dirs d)
      (spit (str d "/deps.edn") "{}")
      (spit (str d "/a.clj") "(ns a)\n(def x 1)\n"))
    (spit (str nrepl-proj "/.jonquil.edn") "{:runtime :nrepl}")
    (let [srv (launch! proj xdg)]
      (try
        (handshake! srv "in-process")
        (let [r (request! srv "tools/list" {} 20000)]
          (check! "tools/list has six tools" (= 6 (count (get-in r ["result" "tools"]))) r))
        (let [r (tool! srv "jonquil_eval" {"code" "(+ 1 2)"} 60000)]
          (check! "eval (+ 1 2) => 3" (and (string? (:text r)) (str/includes? (:text r) "=> 3")) r))
        (let [r1 (tool! srv "jonquil_eval" {"code" "(do (.start (Thread. (fn [] (println \"noise\")))) (Thread/sleep 200) :ok)"} 30000)
              r2 (tool! srv "jonquil_eval" {"code" "(+ 2 2)"} 30000)]
          (check! "background println does not reach stdout"
                  (and (not (:fail r1)) (string? (:text r2)) (str/includes? (:text r2) "=> 4")
                       (every? (fn [l] (try (json/parse-string l) true (catch Exception _ false)))
                               @(:lines srv)))
                  {:r1 r1 :r2 r2 :lines @(:lines srv)}))
        (let [r (tool! srv "jonquil_check_brackets" {"text" "(+ 1"} 20000)]
          (check! "check_brackets reports the unclosed form"
                  (and (string? (:text r)) (not (str/blank? (:text r)))) r))
        (stop! srv "in-process")
        (finally (p/destroy-tree (:proc srv))))
      ;; second project: a real Jolt child over nREPL
      (let [srv (launch! nrepl-proj xdg)
            pid (.pid ^java.lang.Process (:proc (:proc srv)))]
        (try
          (handshake! srv "nrepl")
          (let [r (tool! srv "jonquil_eval" {"code" "(+ 5 5)"} 60000)]
            (check! "nrepl eval (+ 5 5) => 10" (and (string? (:text r)) (str/includes? (:text r) "=> 10")) r))
          (let [kids (children pid)]
            (check! (str "nrepl run started a child (pid " (str/join "," kids) ")") (seq kids) kids)
            (stop! srv "nrepl")
            (Thread/sleep 500)
            (let [alive (filter (fn [k] (zero? (:exit @(p/process ["kill" "-0" k] {:err :string :out :string})))) kids)]
              (check! "no jolt child remains" (empty? alive) alive)))
          (finally (p/destroy-tree (:proc srv))))))
    (fs/delete-tree tmp)
    (let [fails (count (remove true? @results))]
      (println (str "[mcp-smoke] " (- (count @results) fails) "/" (count @results) " passed"))
      (System/exit (if (zero? fails) 0 1)))))

(def ^:private window-tool-names
  #{"jonquil_eval" "jonquil_run" "jonquil_run_selection" "jonquil_stop" "jonquil_open"
    "jonquil_tabs" "jonquil_select_tab" "jonquil_read_tab" "jonquil_replace_text"
    "jonquil_save" "jonquil_console" "jonquil_doc" "jonquil_complete"
    "jonquil_check_brackets" "jonquil_project"})

(defn- ends-with? [r suffix]
  (and (string? (:text r)) (str/ends-with? (str/trim (:text r)) suffix)))

(defn- window-main []
  (let [tmp (str (fs/create-temp-dir {:prefix "jonquil-mcp-smoke-"}))
        xdg (str tmp "/xdg")
        proj (str tmp "/proj")
        a (str proj "/a.clj")]
    (fs/create-dirs proj)
    (spit (str proj "/deps.edn") "{}")
    (spit a "(ns demo.a) (def y 7) y")
    (let [srv (launch! proj xdg)
          pid (.pid ^java.lang.Process (:proc (:proc srv)))]
      (try
        (handshake! srv "window")
        (let [r (request! srv "tools/list" {} 60000)
              names (set (map #(get % "name") (get-in r ["result" "tools"])))]
          (check! "tools/list has exactly the fifteen window tools"
                  (and (= 15 (count (get-in r ["result" "tools"]))) (= window-tool-names names))
                  r))
        (let [r (tool! srv "jonquil_open" {"path" a} 60000)]
          (check! "open a.clj" (and (string? (:text r)) (not (:error? r))) r))
        (let [r (tool! srv "jonquil_run" {} 60000)]
          (check! "run => 7" (ends-with? r "=> 7") r))
        (let [r (tool! srv "jonquil_replace_text" {"text" "(def z 9) z"} 30000)
              t (tool! srv "jonquil_tabs" {} 30000)]
          (check! "replace_text makes the tab dirty"
                  (and (not (:fail r)) (string? (:text t)) (str/includes? (:text t) " dirty ")
                       (str/includes? (:text t) a))
                  {:r r :t t}))
        (let [r (tool! srv "jonquil_save" {} 30000)
              disk (slurp a)]
          (check! "save writes the new bytes to disk"
                  (and (not (:fail r)) (not (:error? r)) (= "(def z 9) z" disk))
                  {:r r :disk disk}))
        (let [r (tool! srv "jonquil_run" {} 60000)]
          (check! "run after save => 9" (ends-with? r "=> 9") r))
        (let [r (tool! srv "jonquil_eval" {"code" "(+ 1 2)"} 60000)]
          (check! "eval (+ 1 2) => 3" (ends-with? r "=> 3") r))
        (let [r (tool! srv "jonquil_console" {"since" 0} 30000)]
          (check! "console since 0 starts with mark and has the banner"
                  (and (string? (:text r))
                       (str/starts-with? (:text r) "mark ")
                       (str/includes? (:text r) "; --- Running a.clj ---"))
                  r))
        (let [t0 (System/currentTimeMillis)
              r (tool! srv "jonquil_eval" {"code" "(loop [] (recur))" "timeout_ms" 500} 10000)
              dt (- (System/currentTimeMillis) t0)]
          (check! "runaway eval times out as isError within 10 s"
                  (and (not (:fail r)) (:error? r) (str/includes? (str (:text r)) "Timed out after") (< dt 10000))
                  {:r r :ms dt}))
        (let [r (tool! srv "jonquil_eval" {"code" "(+ 2 2)"} 60000)]
          (check! "eval after the timeout => 4" (ends-with? r "=> 4") r))
        ;; dirty EOF: an unsaved edit, then stdin closes; the quit discards it
        (let [r (tool! srv "jonquil_replace_text" {"text" "(def w 1) w"} 30000)
              before (slurp a)
              dirty (tool! srv "jonquil_tabs" {} 30000)]
          (check! "unsaved edit is pending before EOF"
                  (and (not (:fail r)) (str/includes? (str (:text dirty)) " dirty "))
                  {:r r :t dirty})
          (.close ^java.io.Writer (:in srv))
          (let [x (deref (:proc srv) 20000 ::timeout)]
            (check! "window exits within 20 s after stdin closes"
                    (and (not= x ::timeout) (zero? (:exit x)))
                    (if (= x ::timeout) "still running after 20 s" (:exit x)))
            (when (= x ::timeout) (p/destroy-tree (:proc srv))))
          (Thread/sleep 500)
          (check! "no jolt child remains" (empty? (children pid)) (children pid))
          (check! "the discard really discarded: bytes on disk unchanged"
                  (= before (slurp a) "(def z 9) z") {:before before :after (slurp a)}))
        (check! "every stdout line is JSON"
                (every? (fn [l] (try (json/parse-string l) true (catch Exception _ false)))
                        @(:lines srv))
                @(:lines srv))
        (finally (p/destroy-tree (:proc srv)))))
    (fs/delete-tree tmp)
    (let [fails (count (remove true? @results))]
      (println (str "[mcp-smoke] " (- (count @results) fails) "/" (count @results) " passed"))
      (System/exit (if (zero? fails) 0 1)))))

;; --attach: a real normal window with the listener on, plus bridges to it.
(defn- alive? [^java.lang.Process jp] (.isAlive jp))

(defn- json-lines? [srv]
  (every? (fn [l] (try (json/parse-string l) true (catch Exception _ false)))
          @(:lines srv)))

(defn- bridge-exit!
  "Closes the bridge's stdin; returns its exit map, or ::timeout (destroyed)."
  [bridge]
  (.close ^java.io.Writer (:in bridge))
  (let [r (deref (:proc bridge) 10000 ::timeout)]
    (when (= r ::timeout) (p/destroy-tree (:proc bridge)))
    r))

(defn- await-registry
  "The first *.edn under dir within ms, or nil."
  [dir ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [f (when (fs/exists? dir) (first (fs/glob dir "*.edn")))]
        (cond f (str f)
              (> (System/currentTimeMillis) end) nil
              :else (do (Thread/sleep 250) (recur)))))))

(defn- attach-main []
  (let [tmp (str (fs/create-temp-dir {:prefix "jonquil-mcp-smoke-"}))
        xdg (str tmp "/xdg")
        proj (str tmp "/proj")
        reg-dir (str xdg "/jonquil/mcp")
        procs (atom [])
        track! (fn [srv] (swap! procs conj (:proc srv)) srv)]
    (fs/create-dirs proj)
    (fs/create-dirs (str xdg "/jonquil"))
    (spit (str proj "/deps.edn") "{}")
    (spit (str xdg "/jonquil/settings.edn") "{:mcp-listen? true}")
    (try
      (let [win (track! (launch! proj xdg []))
            wjp ^java.lang.Process (:proc (:proc win))
            wpid (.pid wjp)
            reg (await-registry reg-dir 30000)]
        (check! "the window registers under the temp XDG within 30 s" reg
                {:dir reg-dir :files (when (fs/exists? reg-dir) (mapv str (fs/list-dir reg-dir)))})
        (when reg
          (let [b1 (track! (launch! proj xdg ["--mcp-attach"]))]
            (handshake! b1 "bridge 1")
            (let [r (request! b1 "tools/list" {} 60000)
                  names (set (map #(get % "name") (get-in r ["result" "tools"])))]
              (check! "tools/list has exactly the fifteen window tools"
                      (and (= 15 (count (get-in r ["result" "tools"]))) (= window-tool-names names)) r))
            (let [r (tool! b1 "jonquil_eval" {"code" "(+ 1 2)"} 60000)]
              (check! "eval (+ 1 2) => 3" (ends-with? r "=> 3") r))
            (let [r (tool! b1 "jonquil_console" {"since" 0} 30000)]
              (check! "console since 0 starts with mark"
                      (and (string? (:text r)) (str/starts-with? (:text r) "mark ")) r))
            (let [x (bridge-exit! b1)]
              (check! "bridge 1 exits 0 within 10 s of stdin closing"
                      (and (not= x ::timeout) (zero? (:exit x)))
                      (if (= x ::timeout) "still running after 10 s" (:exit x))))
            (check! "bridge 1 stdout is all JSON" (json-lines? b1) @(:lines b1))
            (check! "the window process survives the detach" (alive? wjp) {:pid wpid}))
          (let [b2 (track! (launch! proj xdg ["--mcp-attach"]))]
            (handshake! b2 "bridge 2")
            (let [r (tool! b2 "jonquil_eval" {"code" "(* 6 7)"} 60000)]
              (check! "second bridge eval (* 6 7) => 42" (ends-with? r "=> 42") r))
            (let [x (bridge-exit! b2)]
              (check! "bridge 2 exits 0 within 10 s of stdin closing"
                      (and (not= x ::timeout) (zero? (:exit x)))
                      (if (= x ::timeout) "still running after 10 s" (:exit x))))
            (check! "bridge 2 stdout is all JSON" (json-lines? b2) @(:lines b2))
            (check! "the window process survives the second detach" (alive? wjp) {:pid wpid})))
        ;; SIGTERM the window we launched
        (let [kids-before (children wpid)]
          (.destroy wjp)
          (let [x (deref (:proc win) 20000 ::timeout)]
            (check! "window exits within 20 s of SIGTERM" (not= x ::timeout)
                    "still running after 20 s")
            (Thread/sleep 500)
            (check! "no window child remains"
                    (empty? (filter (fn [k] (zero? (:exit @(p/process ["kill" "-0" k] {:err :string :out :string}))))
                                    kids-before))
                    kids-before)
            (check! "the registry file is gone"
                    (not (and reg (fs/exists? reg)))
                    {:reg reg :left (when (fs/exists? reg-dir) (mapv str (fs/list-dir reg-dir)))}))))
      (finally
        (doseq [pr @procs] (try (p/destroy-tree pr) (catch Throwable _ nil)))
        (fs/delete-tree tmp)))
    (let [fails (count (remove true? @results))]
      (println (str "[mcp-smoke] " (- (count @results) fails) "/" (count @results) " passed"))
      (System/exit (if (zero? fails) 0 1)))))

(cond (attach?) (attach-main)
      (window?) (window-main)
      :else (main))
