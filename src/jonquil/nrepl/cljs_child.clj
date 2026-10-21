(ns jonquil.nrepl.cljs-child
  "The out-of-process ClojureScript-on-node child: find the clojure CLI,
  start the launcher with it, wait for the `jonquil-cljs-prepl <port>` line
  in the log, and kill the whole process tree again.

  The launcher picks its own port, so there is no port scan here. The run
  directory holds server.log. The process runs in the project directory
  when there is one (so deps.edn and the project's paths apply), else in
  the run dir. A failed start keeps the log and names it, so the user can
  read what clojure said; a cancelled start and a killed child leave
  nothing behind. The child keeps the stdin pipe open until kill!: the
  launcher ends on its EOF."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]
            [jonquil.cljs-node :as cljs-node]
            [jonquil.login-path :as login-path]
            [jonquil.tool-path :as tool-path]))

(def default-timeout-ms
  ;; The first run downloads the Closure Compiler and ClojureScript.
  60000)

(def kill-wait-ms 1000)

(defn find-clojure
  "The clojure CLI as an absolute path, or nil. JONQUIL_CLOJURE first,
  else clojure on login-path's PATH. With no argument it reads the real
  environment and login-path's PATH (the shell's, for a Finder launch)."
  ([]
   (find-clojure {:env (into {} (System/getenv))
                  :path (login-path/path)
                  :exists? login-path/executable-file?}))
  ([{:keys [env path exists?]}]
   (or (get env "JONQUIL_CLOJURE")
       (tool-path/resolve-program "clojure" path exists?))))

(defn alive? [child] (boolean (and child (p/alive? (:proc child)))))

(defn- delete-tree!
  "Deletes dir and everything under it. A symlinked directory is removed as
  a link, never recursed into."
  [dir]
  (let [f (io/file dir)]
    (when (and (.isDirectory f) (not (java.nio.file.Files/isSymbolicLink (.toPath f))))
      (doseq [c (.listFiles f)] (delete-tree! c)))
    (.delete f)))

(defn- kill-process!
  "Destroys the tree, closes stdin, waits up to kill-wait-ms, escalates."
  [{:keys [proc stdin]}]
  (let [wait! (fn []
                (loop [n 0]
                  (when (and (p/alive? proc) (< (* n 20) kill-wait-ms))
                    (Thread/sleep 20)
                    (recur (inc n)))))]
    (when (p/alive? proc) (p/destroy-tree proc))
    (when stdin (try (.close ^java.io.OutputStream stdin) (catch Exception _ nil)))
    (wait!)
    (when (p/alive? proc)
      (.destroyForcibly ^java.lang.Process (:proc proc))
      (wait!))))

(defn kill!
  "Kills the child's process tree, closes its stdin, and deletes the run
  dir. Idempotent; nil is fine."
  [child]
  (when child
    (kill-process! child)
    (when-let [d (:run-dir child)] (delete-tree! d))
    nil))

(defn- tail [path n]
  (try (str/join "\n" (take-last n (str/split-lines (slurp path))))
       (catch Exception _ "")))

(defn- read-log [log]
  (try (slurp log) (catch Exception _ "")))

(defn- port-taken?
  "True when the log has the launcher's own marker line
  `jonquil-cljs-http-port-taken <port>`. Compile output that merely
  mentions a BindException doesn't count."
  [text]
  (boolean (some #(re-matches #"jonquil-cljs-http-port-taken \d+" %)
                 (str/split-lines text))))

(defn process-options
  "The jolt.process options for the child: {:dir :log}, plus login-path's
  PATH in its environment when there is one."
  [{:keys [dir log]}]
  (let [path (login-path/path)]
    (cond-> {:dir dir :out (io/file log) :err :out}
      path (assoc :extra-env {"PATH" path}))))

(defn- canonical [path] (.getCanonicalPath (io/file path)))

(defn start!
  "Starts the launcher with clojure in a fresh <tmpdir>/jonquil-cljs-run-<suffix>
  and waits for its ready line. Returns {:proc :pid :port :url :run-dir :log
  :stdin} (:url is the page URL of a browser launch, nil for node), or
  {:error :http-port-taken} when a :browser launch's log has the
  launcher's `jonquil-cljs-http-port-taken <port>` line (it exits 3) (the tree is killed and the run dir
  removed), or {:error text} when the process exits early or timeout-ms
  (default 60000) passes (the text carries the exit code, the log's last 5
  lines and \"The log: <path>\"; the log is kept), or {:error :cancelled}
  when (cancelled?) turns true. Every failure kills the tree. opts:
  :clojure and :launcher (required), :project-dir, :timeout-ms,
  :cancelled?, :target (:node or :browser), :http-port (browser only),
  :args-fn (default cljs-node/launch-args; a test seam; it receives
  :target and :http-port too)."
  [{:keys [clojure launcher project-dir timeout-ms cancelled? args-fn target http-port]}]
  (let [timeout-ms (or timeout-ms default-timeout-ms)
        args-fn (or args-fn cljs-node/launch-args)
        ;; :dir must be canonical absolute: a relative or symlinked one
        ;; makes the spawn misbehave (see nrepl.child).
        ;; createTempDirectory makes the folder itself (owner-only, and it
        ;; fails rather than reusing a folder or symlink someone else made
        ;; at a guessable name).
        run-dir (.getCanonicalPath
                 (.toFile (java.nio.file.Files/createTempDirectory
                           "jonquil-cljs-run-"
                           (make-array java.nio.file.attribute.FileAttribute 0))))
        log (str run-dir "/server.log")
        dir (if project-dir (canonical project-dir) run-dir)
        deps-file (io/file dir "deps.edn")
        add-dep? (cljs-node/needs-cljs-dep?
                  (when (.isFile deps-file) (try (slurp deps-file) (catch Exception _ nil))))
        argv (args-fn {:clojure clojure :launcher launcher :add-dep? add-dep?
                       :target target :http-port http-port})
        spawned (try (apply p/process (process-options {:dir dir :log log}) argv)
                     (catch Exception e
                       (delete-tree! run-dir)
                       {:spawn-error (str "couldn't start clojure: " (ex-message e))}))]
    (if-let [e (:spawn-error spawned)]
      {:error e}
      (let [proc spawned
            child {:proc proc :pid (.pid ^java.lang.Process (:proc proc)) :run-dir run-dir
                   :log log :stdin (:in proc)}
            ;; A failure keeps the log (the error names it), so only the
            ;; process dies; a cancel leaves nothing.
            fail (fn [text] (kill-process! child)
                   {:error (str text "\n" (tail log 5) "\nThe log: " log)})
            t0 (System/currentTimeMillis)]
        ;; Anything thrown while waiting must not leave the tree behind.
        (try
          (loop []
            (let [text (read-log log)
                  port (cljs-node/ready-port text)]
              (cond
                port (assoc child :port port :url (cljs-node/ready-url text))
                (and (= :browser target) (port-taken? text))
                (do (kill! child) {:error :http-port-taken})
                (and cancelled? (cancelled?)) (do (kill! child) {:error :cancelled})
                (not (p/alive? proc))
                (let [r (deref proc 500 nil)]
                  (fail (str "clojure exited (exit " (:exit r) "):")))
                (> (- (System/currentTimeMillis) t0) timeout-ms)
                (fail (str "clojure didn't start within " timeout-ms " ms:"))
                :else (do (Thread/sleep 50) (recur)))))
          (catch Throwable e (kill! child) (throw e)))))))
