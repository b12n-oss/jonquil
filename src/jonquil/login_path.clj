(ns jonquil.login-path
  "Asks the user's login shell for its PATH once per process, in the
  background, when Jonquil was launched from Finder or the Dock (see
  jonquil.tool-path for why that PATH is too bare to find jolt or jank).

  start! decides and, when a lookup is needed, runs it in a future. answer
  waits for the result, but never longer than timeout-ms plus 500 ms, and
  falls back to well-known folders when the shell fails, times out or prints
  no PATH. Nothing here throws out of start!, answer or path.

  The two exists? defaults, used when start! gets no :exists?, are
  executable-file? (a file the process may execute, for programs) and
  directory? (for dirs). Both are one-argument functions of a path string.
  start! passes them as one function that picks by whether the path is a
  directory, which suits fallback-path (it only asks about directories)."
  (:require [clojure.java.io :as io]
            [jolt.process :as p]
            [jonquil.tool-path :as tp]))

(def timeout-ms
  "How long the shell may take. Measured 2026-10-12: 1.3 s cold, about 0.7 s
  warm for an interactive login zsh."
  5000)

(def ^:private grace-ms 500)

(defonce ^:private state (atom nil))

(defn reset-for-test!
  "Forgets the once-per-process lookup so a test can start another."
  []
  (reset! state nil))

(defn executable-file?
  "Default exists? for programs: path names a regular file the process can execute."
  [path]
  (let [f (io/file path)]
    (boolean (and (.isFile f) (.canExecute f)))))

(defn directory?
  "Default exists? for dirs: path names a directory."
  [path]
  (.isDirectory (io/file path)))

(defn- shell-name [shell]
  (let [s (if (or (nil? shell) (re-find #"^\s*$" shell)) "/bin/zsh" shell)]
    (.getName (io/file s))))

(defn- fallback
  "The well-known-folders answer. Never throws: a failing exists? leaves the
  current PATH as it was."
  [{:keys [env home exists?]} reason]
  (try
    {:path (tp/fallback-path (get env "PATH") home exists?)
     :source :fallback
     :reason reason}
    (catch Throwable _
      {:path (get env "PATH") :source :fallback :reason reason})))

(def ^:private read-cap
  "At most this many bytes of the shell's output are read."
  (* 1024 1024))

(defn- read-capped [^java.io.File f]
  (try
    (with-open [in (java.io.FileInputStream. f)]
      (let [buf (byte-array read-cap)
            n (.read in buf 0 read-cap)]
        (if (pos? n) (String. buf 0 (int n) "UTF-8") "")))
    (catch Throwable _ "")))

(defn- run-shell
  "Spawns the probe with stdout going to a temp file, so the one deadline
  covers the shell's whole run (a pipe would also wait for every process
  holding it open). Returns {:out s} or {:reason r}. On timeout the shell's
  process tree is killed, and whatever it had printed is still parsed."
  [{:keys [shell timeout-ms]}]
  (let [tmp (java.io.File/createTempFile "jonquil-login-path-" ".out")]
    (try
      (let [proc (apply p/process {:in (io/file "/dev/null") :out tmp :err :discard}
                        (tp/shell-command shell))
            r (deref proc timeout-ms ::timeout)
            timed-out? (= ::timeout r)]
        (when timed-out?
          (p/destroy-tree proc)
          (when (= ::timeout (deref proc 1000 ::timeout))
            (.destroyForcibly ^java.lang.Process (:proc proc))))
        (let [out (read-capped tmp)]
          (cond
            (and timed-out? (tp/parse-answer out)) {:out out}
            timed-out? {:reason "timed out"}
            (zero? (:exit r)) {:out out}
            :else {:reason "failed"})))
      (finally (.delete tmp)))))

(defn- lookup [{:keys [shell] :as opts}]
  (try
    (let [{:keys [out reason]} (run-shell opts)]
      (if reason
        (fallback opts reason)
        (if-let [path (tp/parse-answer out)]
          {:path path :source :shell :shell (shell-name shell)}
          (fallback opts "printed no PATH"))))
    (catch Throwable _ (fallback opts "failed"))))

(defn start!
  "Starts the lookup when {:env :home :shell :timeout-ms :exists?} describe a
  launch whose PATH lacks the well-known dirs, and returns :started. Returns
  :not-needed after recording the current PATH (source :process) otherwise.
  A second call in the same process returns nil and changes nothing.
  Defaults: env System/getenv, home user.home, shell the SHELL variable,
  timeout-ms the timeout-ms var, exists? as described in the ns docstring."
  ([] (start! {}))
  ([{:keys [env home shell] :as opts}]
   (try
     (let [env (or env (into {} (System/getenv)))
           home (or home (System/getProperty "user.home"))
           opts (assoc opts
                       :env env :home home
                       :shell (or shell (get env "SHELL"))
                       :timeout-ms (or (:timeout-ms opts) timeout-ms)
                       :exists? (or (:exists? opts)
                                    (fn [p] (if (directory? p) true (executable-file? p)))))
           result (promise)
           entry {:result result :timeout-ms (:timeout-ms opts) :opts opts}]
       (when (compare-and-set! state nil entry)
         (if (tp/needs-lookup? (get env "PATH") home)
           (do (future (deliver result (try (lookup opts)
                                            (catch Throwable _ (fallback opts "failed")))))
               :started)
           (do (deliver result {:path (get env "PATH") :source :process})
               :not-needed))))
     (catch Throwable _
       ;; The once-guard may already be set; nobody must wait on it forever.
       (when-let [{:keys [result opts]} @state]
         (deliver result (fallback (or opts {}) "failed")))
       nil))))

(defn answer
  "The lookup's result map, waiting for it for at most timeout-ms plus 500 ms.
  Before start! has run: the current PATH with source :process."
  []
  (try
    (if-let [{:keys [result timeout-ms opts]} @state]
      (let [r (deref result (+ timeout-ms grace-ms) ::late)]
        (if (= ::late r)
          (do (deliver result (fallback opts "timed out"))
              (deref result 0 (fallback opts "timed out")))
          r))
      {:path (System/getenv "PATH") :source :process})
    (catch Throwable _ {:path (System/getenv "PATH") :source :process})))

(defn path
  "The PATH to give child tools: (:path (answer))."
  []
  (:path (answer)))

(defn console-line
  "The console note saying where the PATH came from, or nil for :process."
  []
  (let [{:keys [source shell reason]} (answer)]
    (case source
      :shell (str "; PATH from your login shell (" shell ").")
      :fallback (str "; PATH from well-known folders (your login shell " reason ").")
      nil)))
