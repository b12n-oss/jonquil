;; Smoke of `jonquil --mcp` over real stdio. A jolt script (babashka.process
;; is built into jolt, and clojure.data.json comes from deps.edn); it never
;; touches the jolt process from the inside. Run via `jolt mcp:smoke` (a checkout, `jolt -M -m jonquil.main --mcp
;; DIR`) or `jolt mcp:smoke --bin ./jonquil` (the built binary). With --attach it
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
            [clojure.data.json :as json]
            [clojure.edn :as edn]
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
     {:proc proc :q q :in (io/writer (:in proc)) :id (atom 0) :lines (atom []) :notes (atom [])})))

(defn- json-line?
  "Whether l reads as one JSON value."
  [l]
  (not= ::bad (try (json/read-str l) (catch Exception _ ::bad))))

(defn- send! [{:keys [^java.io.Writer in]} msg]
  (.write in (str (json/write-str msg) "\n"))
  (.flush in))

(defn- next-line
  "The next stdout line, or ::timeout / ::eof."
  [{:keys [^LinkedBlockingQueue q lines]} ms]
  (let [l (.poll q ms TimeUnit/MILLISECONDS)]
    (cond (nil? l) ::timeout
          (= l ::eof) ::eof
          :else (do (swap! lines conj l) l))))

(defn- request!
  "Sends a request and returns the parsed response, or {:fail why}. A
  notification read while waiting (a map without \"id\")
  goes onto :notes, and the wait goes on, under the same deadline."
  [srv method params ms]
  (let [id (swap! (:id srv) inc)
        deadline (+ (System/currentTimeMillis) ms)]
    (send! srv {"jsonrpc" "2.0" "id" id "method" method "params" params})
    (loop []
      (let [l (next-line srv (max 1 (- deadline (System/currentTimeMillis))))
            m (when (string? l)
                (try (json/read-str l)
                     (catch Exception _ {:fail (str "not JSON: " l)})))]
        (cond
          (not (string? l)) {:fail l}
          (and (map? m) (not (:fail m)) (not (contains? m "id"))) (do (swap! (:notes srv) conj m) (recur))
          :else m)))))

(defn- await-note
  "Waits up to ms for a notification with method, reading lines as they
  come; true when one is on :notes."
  [srv method ms]
  (let [deadline (+ (System/currentTimeMillis) ms)
        seen? #(some (fn [m] (= method (get m "method"))) @(:notes srv))]
    (loop []
      (cond
        (seen?) true
        (>= (System/currentTimeMillis) deadline) false
        :else (let [l (next-line srv (min 500 (max 1 (- deadline (System/currentTimeMillis)))))]
                (when (string? l)
                  (let [m (try (json/read-str l) (catch Exception _ nil))]
                    (when (and (map? m) (not (contains? m "id"))) (swap! (:notes srv) conj m))))
                (if (= ::eof l) false (recur)))))))

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
    (send! srv {"jsonrpc" "2.0" "method" "notifications/initialized"})
    r))

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
    ;; proj has a deps.edn, where Jolt runs out of process by default; this
    ;; block is the in-process headless path (where a stray println could
    ;; reach the JSON on stdout), so it opts back in. nrepl-proj is the
    ;; out-of-process one.
    (spit (str proj "/.jonquil.edn") "{:runtime :in-process}")
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
                       (every? json-line?
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
    "jonquil_check_brackets" "jonquil_project"
    "jonquil_plugins_list" "jonquil_plugin_read" "jonquil_plugin_propose" "jonquil_plugin_status"})

(defn- ends-with? [r suffix]
  (and (string? (:text r)) (str/ends-with? (str/trim (:text r)) suffix)))

(defn- plugin-source
  "A plugin file whose defplugin id is id, with one :edit command."
  [id]
  (str "(ns jonquil.plugins." id "\n  (:require [jonquil.plugin :as p]))\n\n"
       "(p/defplugin\n  {:id :" id "\n   :doc \"A smoke plugin.\"\n   :effects #{}\n"
       "   :commands [{:id :" id "/wrap :doc \"Wrap it.\" :scope :editor\n"
       "               :edit (fn [s] (p/wrap-form s \"(when \" \")\"))}]})\n"))

(defn- plugin-checks!
  "Plugin tools over real stdio: a propose that's pending, one its check
  refuses, the status, the user's note after a rejection (written into
  the temp history here, since no tool can reject), list and read."
  [srv xdg]
  (let [r (tool! srv "jonquil_plugin_propose" {"id" "smoke-p" "source" (plugin-source "smoke-p")
                                               "reason" "the smoke's plugin"} 90000)]
    (check! "propose smoke-p is pending"
            (and (string? (:text r)) (str/starts-with? (:text r) "status: pending") (not (:error? r))) r))
  (let [r (tool! srv "jonquil_plugin_propose" {"id" "smoke-q" "source" (plugin-source "smoke-other")
                                               "reason" "a mismatched id"} 90000)]
    (check! "propose with a mismatched id is rejected-by-checks with its problems"
            (and (string? (:text r)) (str/starts-with? (:text r) "status: rejected-by-checks")
                 (str/includes? (:text r) "\nproblems:\n- ") (:error? r))
            r))
  (let [r (tool! srv "jonquil_plugin_status" {"id" "smoke-p"} 30000)]
    (check! "status of smoke-p is pending" (and (string? (:text r)) (str/starts-with? (:text r) "smoke-p v1: pending.")) r))
  (let [f (io/file xdg "jonquil" "plugins" ".history" "smoke-p" "history.edn")
        found? (.isFile f)]
    (check! "the proposal is in the temp settings' plugins folder" found? (str f))
    (when found?
      (spit f (pr-str (conj (edn/read-string (slurp f))
                            {:n 1 :at (str (java.time.Instant/now)) :status :rejected :note "smoke note"}))))
    (let [r (tool! srv "jonquil_plugin_status" {"id" "smoke-p"} 30000)]
      (check! "status after a rejection carries the note and the call that continues it"
              (and (string? (:text r))
                   (str/ends-with? (:text r) "The user's note: smoke note Call jonquil_plugin_propose with a new version to try again."))
              r)))
  (let [r (tool! srv "jonquil_plugins_list" {} 30000)]
    (check! "list has a smoke-p line"
            (and (string? (:text r)) (some #(str/starts-with? % "smoke-p: ") (str/split-lines (:text r)))) r))
  (let [r (tool! srv "jonquil_plugin_read" {"id" "smoke-p"} 30000)]
    (check! "read smoke-p shows its history"
            (and (string? (:text r)) (str/includes? (:text r) "History:\nv1 rejected (agent, ")) r)))

(defn- window-main []
  (let [tmp (str (fs/create-temp-dir {:prefix "jonquil-mcp-smoke-"}))
        xdg (str tmp "/xdg")
        proj (str tmp "/proj")
        a (str proj "/a.clj")]
    (fs/create-dirs proj)
    (spit (str proj "/deps.edn") "{}")
    ;; In process, as this window block was written against.
    (spit (str proj "/.jonquil.edn") "{:runtime :in-process}")
    (spit a "(ns demo.a) (def y 7) y")
    ;; a pure plugin with one MCP tool, loaded at startup
    (fs/create-dirs (str xdg "/jonquil/plugins"))
    (spit (str xdg "/jonquil/plugins/smoke-t.clj")
          (str "(ns jonquil.plugins.smoke-t\n  (:require [jonquil.plugin :as p]))\n\n"
               "(p/defplugin\n  {:id :smoke-t :doc \"A smoke tool.\" :effects #{}\n"
               "   :mcp-tools [{:name \"twice\" :doc \"The text twice.\"\n"
               "                :args {\"text\" {:type \"string\" :doc \"The text.\" :required? true}}\n"
               "                :run (fn [_ a] (str (get a \"text\") (get a \"text\")))}]})\n"))
    (let [srv (launch! proj xdg)
          pid (.pid ^java.lang.Process (:proc (:proc srv)))]
      (try
        (let [h (handshake! srv "window")]
          (check! "initialize declares tools.listChanged"
                  (= {"listChanged" true} (get-in h ["result" "capabilities" "tools"])) h))
        ;; the startup plugin load runs on the plugin agent, so the first
        ;; tools/list can come before it; then its swap sends list_changed
        ;; and the next list has the tool
        (let [want (conj window-tool-names "plugin_smoke-t_twice")
              names-of (fn [r] (set (map #(get % "name") (get-in r ["result" "tools"]))))
              r1 (request! srv "tools/list" {} 60000)
              [path r] (if (= want (names-of r1))
                         [:first-list r1]
                         (if (await-note srv "notifications/tools/list_changed" 30000)
                           [:after-list-changed (request! srv "tools/list" {} 60000)]
                           [:no-list-changed r1]))]
          (println "[mcp-smoke] startup plugin tool seen" (name path))
          (check! "plugin_smoke-t_twice is served at startup: in the first list, or after list_changed"
                  (and (not= :no-list-changed path)
                       (= 20 (count (get-in r ["result" "tools"])))
                       (= want (names-of r)))
                  {:path path :r r}))
        (let [r (tool! srv "plugin_smoke-t_twice" {"text" "ab"} 60000)]
          (check! "a plugin's tool answers over real stdio"
                  (and (= "abab" (:text r)) (not (:error? r))) r))
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
        (plugin-checks! srv xdg)
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
                (every? json-line?
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
  (every? json-line?
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
    ;; In process, as this attach block was written against.
    (spit (str proj "/.jonquil.edn") "{:runtime :in-process}")
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
            (let [h (handshake! b1 "bridge 1")]
              (check! "the listener declares tools.listChanged"
                      (= {"listChanged" true} (get-in h ["result" "capabilities" "tools"])) h))
            (let [r (request! b1 "tools/list" {} 60000)
                  names (set (map #(get % "name") (get-in r ["result" "tools"])))]
              (check! "tools/list has exactly the nineteen window tools"
                      (and (= 19 (count (get-in r ["result" "tools"]))) (= window-tool-names names)) r))
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
