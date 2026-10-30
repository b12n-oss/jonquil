(ns clj-server
  "A real clojure -M:serve nREPL server (clojure-cli.repl-server 0.1.0 on
  nREPL 1.7.0, or 1.8.0 for the one end-of-input test, via start!'s
  {:nrepl \"1.8.0\"}) in a fresh temp project, for bb test:clj's live
  suite against jonquil.nrepl.jvm. Mirrors jonquil.nrepl.child's shape for a
  Jolt child, but launches the real JVM Clojure CLI instead: Jonquil
  itself never starts, stops or signals this JVM (the global
  constraint) -- this namespace plays the human who already has one
  running, the way jonquil.nrepl.child plays the human for the :nrepl
  dialect's own live suite.

  A second temp dir is passed as CLJ_CONFIG so the real
  ~/.config/clojure tree is never touched. .nrepl-port is the
  server's own file to write and delete; this namespace never reads,
  writes or deletes it directly -- only the readiness log line is
  polled, and the whole temp tree is deleted only after the process has
  been confirmed dead."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]))

(def deps-edn-text
  ;; The :serve alias.
  "{:paths [\"src\"] :aliases {:serve {:extra-deps {io.github.clojure/clojure-cli.repl-server {:mvn/version \"0.1.0\"}} :main-opts [\"-m\" \"clojure-cli.repl.server\"]}}}\n")

(defn- deps-edn-with-nrepl
  "deps-edn-text with nrepl/nrepl pinned to version in the :serve alias's
  :extra-deps, so the server runs that nREPL instead of the 1.7.0 one
  clojure-cli.repl-server brings."
  [version]
  (str "{:paths [\"src\"] :aliases {:serve {:extra-deps {io.github.clojure/clojure-cli.repl-server {:mvn/version \"0.1.0\"} nrepl/nrepl {:mvn/version \"" version "\"}} :main-opts [\"-m\" \"clojure-cli.repl.server\"]}}}\n"))

(def fixture-core-text
  ;; The fixture's exact text: demo.core with greet, a private secret
  ;; returning :private-ok, and nested. Embedded rather than read at run
  ;; time, so tests don't depend on an external file.
  (str "(ns demo.core\n"
       "  \"A fixture namespace for the JVM attach tests.\")\n"
       "\n"
       "(defn greet\n"
       "  \"Says hello to someone.\"\n"
       "  [who]\n"
       "  (str \"hello \" who))\n"
       "\n"
       "(defn- secret [] :private-ok)\n"
       "\n"
       "(def nested {:a [1 2 {:b \"héllo 😀\"}] :c #{:x :y} :d (range 30)})\n"))

(defn- tail [path n]
  (try (let [lines (str/split-lines (slurp path))]
         (str/join "\n" (take-last n lines)))
       (catch Exception _ "")))

(defn- port-from-log
  "The port number off the server's own readiness line (\"nREPL server
  listening on port N\"), or nil while the log doesn't have it yet."
  [log]
  (try
    (when-let [m (re-find #"nREPL server listening on port (\d+)" (slurp log))]
      (parse-long (second m)))
    (catch Exception _ nil)))

(defn- wait-ready
  "Polls every 50 ms until the log carries the readiness line (:ready),
  the process exits first (:exited), or deadline-ms passes (:timeout)."
  [proc log deadline-ms]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond
        (not (p/alive? proc)) :exited
        (port-from-log log) :ready
        (> (- (System/currentTimeMillis) t0) deadline-ms) :timeout
        :else (do (Thread/sleep 50) (recur))))))

(def ready-deadline-ms
  ;; estimate: a first run resolves clojure-cli.repl-server 0.1.0 and its
  ;; deps over the network; cached startup measured about 1.8-2 s on this
  ;; machine once ~/.m2 already has them.
  120000)

(def term-grace-ms
  ;; estimate: SIGTERM runs the server's own shutdown hook (deletes
  ;; .nrepl-port) and exits promptly, measured; a wedged process gets
  ;; SIGKILL after this.
  5000)

(defn- terminate!
  "SIGTERM, then SIGKILL after term-grace-ms if proc is still alive.
  Returns the exit code, or nil when it was already gone."
  [proc]
  (when (p/alive? proc)
    (p/destroy proc)
    (let [r (deref proc term-grace-ms ::alive)]
      (if (= ::alive r)
        (do (.destroyForcibly ^java.lang.Process (:proc proc))
            (:exit (deref proc term-grace-ms nil)))
        (:exit r)))))

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete ^java.io.File f)))

(defn start!
  "Launches clojure -M:serve in a fresh temp project (deps.edn with the
  :serve alias, src/demo/core.clj holding the fixture) with
  CLJ_CONFIG pointing at a second temp dir. Waits for the server's own
  readiness line and reads the port from it. Returns {:proc :pid :port
  :dir :config-dir :log}. Throws ex-info with the log's tail when the
  server exits early or never becomes ready, after ending the process and
  deleting both temp dirs (the log is kept for the tail's full text).
  Optional opts {:nrepl \"1.8.0\"} pins that nREPL version; with no opts
  the deps.edn is deps-edn-text byte for byte."
  ([] (start! {}))
  ([{:keys [nrepl]}]
   (let [uid (str (System/nanoTime) "-" (random-uuid))
         tmp (System/getProperty "java.io.tmpdir")
         dir (io/file tmp (str "jonquil-clj-server-" uid))
         config-dir (io/file tmp (str "jonquil-clj-config-" uid))
         src (io/file dir "src/demo/core.clj")
         log (str tmp "/jonquil-clj-server-" uid ".log")]
     (.mkdirs (.getParentFile src))
     (.mkdirs config-dir)
     (spit (io/file dir "deps.edn") (if nrepl (deps-edn-with-nrepl nrepl) deps-edn-text))
     (spit src fixture-core-text)
     (let [dir-path (.getCanonicalPath dir)
           config-path (.getCanonicalPath config-dir)
           proc (p/process {:dir dir-path :out (io/file log) :err :out
                            :extra-env {"CLJ_CONFIG" config-path}}
                           "clojure" "-M:serve")
           state (wait-ready proc log ready-deadline-ms)]
       (case state
         :ready {:proc proc :pid (.pid ^java.lang.Process (:proc proc)) :port (port-from-log log)
                 :dir dir-path :config-dir config-path :log log}
         (let [msg (if (= :exited state)
                     (str "clojure -M:serve exited: " (tail log 20))
                     (str "clojure -M:serve didn't answer within " ready-deadline-ms " ms: " (tail log 20)))]
            ;; No server to hand back, so nothing else will clean up: end the
            ;; process, then delete both temp dirs. The log stays, since the
            ;; exception names it.
           (terminate! proc)
           (delete-tree! dir-path)
           (delete-tree! config-path)
           (throw (ex-info msg {:log log}))))))))

(defn stop!
  "Ends the server this namespace started (only the pid at (:proc srv),
  never by port or name): SIGTERM, then SIGKILL after term-grace-ms if
  it's still alive. Only once the process is confirmed dead (so its own
  .nrepl-port deletion has already happened) does this delete the temp
  project dir, the temp CLJ_CONFIG dir and the log."
  [{:keys [proc dir config-dir log] :as srv}]
  (when srv
    (terminate! proc)
    (delete-tree! dir)
    (delete-tree! config-dir)
    (try (io/delete-file log true) (catch Exception _ nil)))
  nil)
