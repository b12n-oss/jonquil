(ns jonquil.nrepl.jank-child
  "The out-of-process jank child: find the jank binary, start `jank repl`
  in a private temp directory, wait for the .nrepl-port file jank writes
  there, and kill the whole process tree again.

  jank picks its own port and writes .nrepl-port into its working
  directory, so there is no port scan here (unlike nrepl.child). The run
  directory is the only place jank writes: nothing reads or writes the
  project directory.

  `jank repl` always runs a terminal REPL next to the nREPL server, and EOF
  on its stdin ends the process. jank is spawned directly with the stdin
  pipe jolt.process opens by default, and the child map keeps that stream
  open until kill! (jank re-check spike, 2026-10-12)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]
            [jonquil.login-path :as login-path]
            [jonquil.nrepl.connection :as connection]))

(defn- absolute [path]
  (str (.normalize (.toAbsolutePath (.toPath (io/file path))))))

(defn find-binary
  "The jank executable as an absolute path, or nil. Order: env
  JONQUIL_JANK, <dir>/jank for each of path-dirs, /opt/homebrew/bin/jank,
  ~/.local/bin/jank; the first that exists? accepts. A relative candidate
  is made absolute against the current directory first, because the spawn
  runs in the run dir. With no argument it reads the real environment
  and login-path's PATH (the shell's, for a Finder launch)."
  ([]
   (find-binary
    {:env (into {} (System/getenv))
     :path-dirs (remove str/blank? (str/split (or (login-path/path) "") #":"))
     :exists? (fn [path] (let [f (io/file path)] (and (.isFile f) (.canExecute f))))}))
  ([{:keys [env path-dirs exists?]}]
   (let [cands (concat (when-let [e (get env "JONQUIL_JANK")] [e])
                       (map #(str % "/jank") path-dirs)
                       ["/opt/homebrew/bin/jank"
                        (str (System/getProperty "user.home") "/.local/bin/jank")])]
     (first (filter exists? (map absolute cands))))))

(defn- ns-root
  "The directory above ns-name's own folders when file-path is where that
  namespace lives (talk.core at /p/src/talk/core.jank gives /p/src; dashes
  in the ns are underscores in the path), else nil."
  [file-path ns-name]
  (when (and file-path ns-name)
    (let [f (io/file file-path)
          n (.getName f)
          i (str/last-index-of n ".")
          ext (if i (subs n i) "")
          rel (str (-> ns-name (str/replace "-" "_") (str/replace "." "/")) ext)
          path (.getPath f)]
      (when (str/ends-with? path (str "/" rel))
        (subs path 0 (- (count path) (count rel) 1))))))

(defn module-path
  "The directory jank loads namespaces from: <project-dir>/src when that
  is a directory, else project-dir. With no project, the root the file's
  own ns implies (so a sibling namespace resolves), else file-dir. nil when
  nothing is known."
  [{:keys [project-dir file-dir file-path ns-name]}]
  (if project-dir
    (let [src (io/file project-dir "src")]
      (if (.isDirectory src) (.getPath src) project-dir))
    (or (ns-root file-path ns-name) file-dir)))

(defn launch-command
  "The argv that starts jank. The caller keeps its stdin pipe open."
  [binary module-path]
  (if module-path
    [binary "--module-path" module-path "repl"]
    [binary "repl"]))

(def default-timeout-ms
  ;; estimate: the re-check spike saw the port file within about a second;
  ;; 30 s leaves room for a cold start on a slow disk.
  30000)

(def kill-wait-ms
  ;; estimate: destroy-tree signals every process at once; the probe saw
  ;; alive? false immediately after.
  1000)

(defn alive? [child] (boolean (and child (p/alive? (:proc child)))))

(defn- delete-tree!
  "Deletes dir and everything under it. A symlinked directory is removed as
  a link, never recursed into, so its target outside the run dir survives."
  [dir]
  (let [f (io/file dir)]
    (when (and (.isDirectory f) (not (java.nio.file.Files/isSymbolicLink (.toPath f))))
      (doseq [c (.listFiles f)] (delete-tree! c)))
    (.delete f)))

(defn kill!
  "Kills the child's process tree first, then closes its stdin (closing it
  first lets jank exit on EOF before the tree is signalled, which would
  skip the kill and orphan any descendant). Waits up to 1 s for it to be
  gone, escalates to destroyForcibly if it isn't, and deletes the run
  dir. Idempotent; nil is fine."
  [child]
  (when child
    (let [proc (:proc child)
          wait! (fn []
                  (loop [n 0]
                    (when (and (p/alive? proc) (< (* n 20) kill-wait-ms))
                      (Thread/sleep 20)
                      (recur (inc n)))))]
      (when (p/alive? proc) (p/destroy-tree proc))
      (when-let [in (:stdin child)] (try (.close ^java.io.OutputStream in) (catch Exception _ nil)))
      (wait!)
      (when (p/alive? proc)
        (.destroyForcibly ^java.lang.Process (:proc proc))
        (wait!)))
    (when-let [d (:run-dir child)] (delete-tree! d))
    nil))

(defn- tail [path n]
  (try (str/join "\n" (take-last n (str/split-lines (slurp path))))
       (catch Exception _ "")))

(defn- read-port [run-dir]
  (let [f (io/file run-dir ".nrepl-port")]
    (when (.exists f)
      (try (connection/parse-port (slurp f)) (catch Exception _ nil)))))

(defn process-options
  "The jolt.process options for the jank child: {:dir :log}, plus
  login-path's PATH in its environment when there is one."
  [{:keys [dir log]}]
  (let [path (login-path/path)]
    (cond-> {:dir dir :out (io/file log) :err :out}
      path (assoc :extra-env {"PATH" path}))))

(defn start!
  "Starts jank in a fresh <tmpdir>/jonquil-jank-<nanos> and waits for its
  port file. Returns {:proc :pid :port :run-dir}, or {:error text} when
  jank exits early (text carries the exit code and the log's last 5
  lines) or timeout-ms (default 30000) passes, or {:error :cancelled}
  when (cancelled?) turns true. Every failure kills the tree and deletes
  the run dir first. opts: :binary (required), :module-path, :timeout-ms,
  :cancelled?."
  [{:keys [binary module-path timeout-ms cancelled?]}]
  (let [timeout-ms (or timeout-ms default-timeout-ms)
        ;; :dir must be canonical absolute: a relative or symlinked one
        ;; makes the spawn misbehave (see nrepl.child).
        run-dir (let [d (io/file (System/getProperty "java.io.tmpdir")
                                 (str "jonquil-jank-" (System/nanoTime)))]
                  (.mkdirs d)
                  (.getCanonicalPath d))
        log (str run-dir "/server.log")
        spawned (try (apply p/process (process-options {:dir run-dir :log log})
                            (launch-command binary module-path))
                     (catch Exception e
                       ;; e.g. a stale JONQUIL_JANK: nothing was spawned.
                       (delete-tree! run-dir)
                       {:spawn-error (str "couldn't start jank: " (ex-message e))}))]
    (if-let [e (:spawn-error spawned)]
      {:error e}
      (let [proc spawned
            child {:proc proc :pid (.pid ^java.lang.Process (:proc proc)) :run-dir run-dir
                   :stdin (:in proc)}
            fail (fn [e] (kill! child) {:error e})
            t0 (System/currentTimeMillis)]
        ;; Anything thrown while waiting (cancelled?, reading the port)
        ;; must not leave the spawned tree behind.
        (try
          (loop []
            (let [port (read-port run-dir)]
              (cond
                port (assoc child :port port)
                (and cancelled? (cancelled?)) (fail :cancelled)
                (not (p/alive? proc))
                (let [r (deref proc 500 nil)]
                  (fail (str "jank exited (exit " (:exit r) "): " (tail log 5))))
                (> (- (System/currentTimeMillis) t0) timeout-ms)
                (fail (str "jank didn't write a port file within " timeout-ms " ms: " (tail log 5)))
                :else (do (Thread/sleep 50) (recur)))))
          (catch Throwable e (kill! child) (throw e)))))))
