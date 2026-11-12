(ns jonquil.nrepl.cljs-child
  "The out-of-process ClojureScript-on-node child: find the clojure CLI,
  start the launcher with it, wait for the `jonquil-cljs-prepl <port>` line
  in the log, and kill the whole process tree again.

  The launcher picks its own port, so there is no port scan here. The run
  directory holds server.log and the child's pid (for nrepl.leftovers). The
  process runs in the project directory when there is one (so deps.edn and
  the project's paths apply), else in the run dir. A failed start keeps the
  log and names it, so the user can read what clojure said, and the next
  start a day later prunes it; a cancelled start and a killed child leave
  nothing behind. The child keeps the stdin pipe open until kill!: the
  launcher ends on its EOF."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]
            [jonquil.cljs-node :as cljs-node]
            [jonquil.login-path :as login-path]
            [jonquil.nrepl.leftovers :as leftovers]
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

(defn- create-run-dir!
  "A fresh owner-only <tmpdir>/jonquil-cljs-run-<suffix>, as a canonical path.
  createTempDirectory makes the folder itself and fails rather than reusing
  a folder or symlink someone else made at a guessable name."
  []
  (.getCanonicalPath
   (.toFile (java.nio.file.Files/createTempDirectory
             "jonquil-cljs-run-"
             (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn- project-needs-cljs-dep?
  "True when the deps.edn in dir (if any) lacks the ClojureScript dep."
  [dir]
  (let [deps-file (io/file dir "deps.edn")]
    (cljs-node/needs-cljs-dep?
     (when (.isFile deps-file) (try (slurp deps-file) (catch Exception _ nil))))))

(defn- spawn-launcher!
  "Spawns argv in dir with its output in log. Returns the process, or
  {:spawn-error text} after removing run-dir when the spawn throws."
  [{:keys [dir log run-dir argv]}]
  (try (apply p/process (process-options {:dir dir :log log}) argv)
       (catch Exception e
         (delete-tree! run-dir)
         {:spawn-error (str "couldn't start clojure: " (ex-message e))})))

(defn- start-failure
  "A failed start: kills the process (the log stays, the error names it)
  and returns {:error text} with the log's tail."
  [child text]
  (kill-process! child)
  {:error (str text "\n" (tail (:log child) 5) "\nThe log: " (:log child))})

(defn- poll-start
  "One look at the launcher's log. Returns the finished result (the child
  with its :port, or an {:error ...} map), or nil to keep waiting."
  [{:keys [child target cancelled? timeout-ms t0]}]
  (let [text (read-log (:log child))
        port (cljs-node/ready-port text)]
    (cond
      port (assoc child :port port :url (cljs-node/ready-url text)
                  :stdin-path (cljs-node/ready-stdin text))
      (and (= :browser target) (port-taken? text))
      (do (kill! child) {:error :http-port-taken})
      (and cancelled? (cancelled?)) (do (kill! child) {:error :cancelled})
      (not (p/alive? (:proc child)))
      (let [r (deref (:proc child) 500 nil)]
        (start-failure child (str "clojure exited (exit " (:exit r) "):")))
      (> (- (System/currentTimeMillis) t0) timeout-ms)
      (start-failure child (str "clojure didn't start within " timeout-ms " ms:")))))

(defn- await-start
  "Polls the launcher's log until poll-start has a result. Anything thrown
  while waiting must not leave the tree behind."
  [child opts]
  (let [ctx (assoc opts :child child :t0 (System/currentTimeMillis))]
    (try
      (loop []
        (or (poll-start ctx)
            (do (Thread/sleep 50) (recur))))
      (catch Throwable e (kill! child) (throw e)))))

(defn start!
  "Starts the launcher with clojure in a fresh <tmpdir>/jonquil-cljs-run-<suffix>
  and waits for its ready line. Returns {:proc :pid :port :url :run-dir :log
  :stdin :stdin-path} (:url is the bare page address from a browser
  launch's ready line, nil for node; the URL Jonquil opens carries the
  window's ?t= token on top, see cljs-node/page-url), or
  {:error :http-port-taken} when a :browser launch's log has the
  launcher's `jonquil-cljs-http-port-taken <port>` line (it exits 3) (the tree is killed and the run dir
  removed), or {:error text} when the process exits early or timeout-ms
  (default 60000) passes (the text carries the exit code, the log's last 5
  lines and \"The log: <path>\"; the log is kept), or {:error :cancelled}
  when (cancelled?) turns true. Every failure kills the tree. opts:
  :clojure and :launcher (required), :project-dir, :timeout-ms,
  :cancelled?, :target (:node or :browser), :http-port, :console? and
  :page-token (browser only), :args-fn (default cljs-node/launch-args; a
  test seam; it receives :target, :http-port, :console? and :page-token
  too)."
  [{:keys [clojure launcher project-dir timeout-ms cancelled? args-fn target http-port console?
           page-token]}]
  (leftovers/prune!)
  (let [args-fn (or args-fn cljs-node/launch-args)
        ;; :dir must be canonical absolute: a relative or symlinked one
        ;; makes the spawn misbehave (see nrepl.child).
        run-dir (create-run-dir!)
        log (str run-dir "/server.log")
        dir (if project-dir (canonical project-dir) run-dir)
        argv (args-fn {:clojure clojure :launcher launcher :add-dep? (project-needs-cljs-dep? dir)
                       :target target :http-port http-port :console? console?
                       :page-token page-token})
        spawned (spawn-launcher! {:dir dir :log log :run-dir run-dir :argv argv})]
    (if-let [e (:spawn-error spawned)]
      {:error e}
      (let [child {:proc spawned :pid (.pid ^java.lang.Process (:proc spawned)) :run-dir run-dir
                   :log log :stdin (:in spawned)}]
        (leftovers/write-pid! run-dir (:pid child))
        (await-start child {:target target :cancelled? cancelled?
                            :timeout-ms (or timeout-ms default-timeout-ms)})))))
