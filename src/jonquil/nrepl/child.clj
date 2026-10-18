(ns jonquil.nrepl.child
  "The out-of-process Jolt child: pick a port, launch `jolt nrepl-server`
  in the project directory with nREPL middleware injected through -Sdeps
  (the command the dialects follow-up spike measured), wait until it
  answers, and end it again, killing only the pid it spawned.

  Port rules (~/.claude/rules/dynamic-port-selection.md): a candidate must
  bind on both 127.0.0.1 and the wildcard address (a loopback bind
  succeeds on macOS while another process holds *:p), and the child's own
  bind is the real attempt: a child that exits because its bind failed
  moves the scan to the next port. There is no port override to honour.

  jolt nrepl-server writes .nrepl-port into its working directory, the
  user's project, and removes it on SIGTERM. A .nrepl-port that was there
  before the first child started belongs to someone else, so it is read
  first and written back once the last child is gone.

  Children for one directory can overlap (project switch A, B, A), and each
  one's SIGTERM deletes the file whatever it holds. So a process-wide owner
  table per directory (claim-dir!, owner-ready!, release-dir!) keeps the
  file pointing at the most recently started child that still runs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]
            [jolt.socket]
            [jonquil.login-path :as login-path]
            [jonquil.nrepl.middleware-files :as middleware-files]
            [jonquil.tool-path :as tool-path]))

(def port-base
  ;; estimate: any unprivileged range works; this one avoids 7888, the
  ;; nREPL default hard-coded in many projects (dynamic-port rule 8).
  7950)

(def port-span 50)

(defn- binds? [port addr]
  (try (with-open [_ (java.net.ServerSocket. (int port) 1 (java.net.InetAddress/getByName addr))] true)
       (catch Exception _ false)))

(defn port-free?
  "True when port binds on both the wildcard and the loopback address."
  [port]
  (and (binds? port "0.0.0.0") (binds? port "127.0.0.1")))

(defn middleware-root
  "The jolt-lang/nrepl checkout whose middleware is injected: env
  JONQUIL_JOLT_NREPL, else ~/dev/jolt-lang/nrepl; nil when neither holds a
  deps.edn (the child then runs a vanilla server: no interrupt, no
  lookup)."
  []
  (let [cands [(System/getenv "JONQUIL_JOLT_NREPL")
               (str (System/getProperty "user.home") "/dev/jolt-lang/nrepl")]]
    (some (fn [d] (when (and d (.exists (io/file d "deps.edn")))
                    (.getCanonicalPath (io/file d))))
          cands)))

(def nrepl-git-coord
  "jolt-lang/nrepl as a git dependency, for a machine with no checkout.
  v0.4.0 is the first release that streams an evaluation's output. Measured
  2026-10-18: 2.8 s to ready on the first start (the clone into
  ~/.gitlibs), 347 ms after that, the same as a checkout."
  {:git/url "https://github.com/jolt-lang/nrepl.git" :git/tag "v0.4.0" :git/sha "7efdd6c"})

(defn middleware-source
  "Where the injected middleware comes from: {:local checkout} when
  middleware-root finds one, else {:git nrepl-git-coord}."
  []
  (if-let [root (middleware-root)]
    {:local root}
    {:git nrepl-git-coord}))

(defn launch-args
  "The child's argv. With a middleware source, the follow-up spike's
  command: jolt -Sdeps '{:deps {nrepl/nrepl COORD}
  :nrepl/middleware [nrepl.middleware/default-middleware]}' nrepl-server P,
  where COORD is {:local/root R} for {:local R} or the git coordinate for
  {:git coord}. With a nil source, a plain jolt nrepl-server P.
  source's :jonquil (or jonquil-root, in the three-argument form), when set,
  is jonquil.nrepl.middleware-files' folder: it joins the deps as
  jonquil/nrepl-middleware. The inspector's middleware goes last, so even a
  plain server answers its ops; stdin's goes first, around
  jolt-lang/nrepl's session middleware, and only with it: a plain server
  runs evals on the reader thread, where a waiting read would starve the
  stdin op. A source of just {:jonquil root} is a plain server with the
  inspector.
  The program is jolt's absolute path found on login-path's PATH (jolt.process
  resolves a bare name against Jonquil's own PATH, which a Finder launch has
  too bare), or the bare \"jolt\" when none is found."
  ([port source] (launch-args port (dissoc source :jonquil) (:jonquil source)))
  ([port {:keys [local git]} jonquil-root]
   (let [program (or (tool-path/resolve-program "jolt" (login-path/path)
                                                login-path/executable-file?)
                     "jolt")
         coord (cond local (str "{:local/root " (pr-str local) "}")
                     git (binding [*print-namespace-maps* false] (pr-str git)))
         deps (cond-> []
                coord (conj (str "nrepl/nrepl " coord))
                jonquil-root (conj (str middleware-files/lib " {:local/root " (pr-str jonquil-root) "}")))
         middleware (cond-> []
                      (and coord jonquil-root) (conj (str middleware-files/stdin-middleware))
                      coord (conj "nrepl.middleware/default-middleware")
                      jonquil-root (conj (str middleware-files/inspect-middleware)))]
     (if (seq deps)
       [program "-Sdeps"
        (str "{:deps {" (str/join " " deps) "}"
             " :nrepl/middleware [" (str/join " " middleware) "]}")
        "nrepl-server" (str port)]
       [program "nrepl-server" (str port)]))))

(defn jonquil-root
  "The inspector's folder for a child about to start (the evaluator puts it
  on the source as :jonquil), or nil for none; throws when it can't be
  written. A var of its own so tests can stand in for the cache folder."
  []
  (middleware-files/ensure-root!))

(defn process-options
  "The jolt.process options for the child: {:dir :log}, plus login-path's
  PATH in the child's environment so jolt's own tool lookups see it
  (no :extra-env when there is no PATH at all)."
  [{:keys [dir log]}]
  (let [path (login-path/path)]
    (cond-> {:dir dir :out (io/file log) :err :out :in (io/file "/dev/null")}
      path (assoc :extra-env {"PATH" path}))))

(defn- port-file [dir] (io/file dir ".nrepl-port"))

(defn- read-port-file [dir]
  (let [f (port-file dir)]
    (when (.exists f) (try (slurp f) (catch Exception _ nil)))))

(defn- tail [path n]
  (try (let [lines (str/split-lines (slurp path))]
         (str/join "\n" (take-last n lines)))
       (catch Exception _ "")))

(defn- started? [log port]
  ;; jolt.nrepl/start prints "... nREPL server started on port P (127.0.0.1)"
  ;; after its bind and listen succeed. A TCP connect alone can be answered
  ;; by another listener on P (dynamic-port rule 7: verify what answered).
  (try (str/includes? (slurp log) (str "nREPL server started on port " port " "))
       (catch Exception _ false)))

(defn- wait-ready
  "Polls every 50 ms until the child logs that it listens on port
  (:ready), exits (:exited) or deadline-ms passes (:timeout)."
  [proc log port deadline-ms]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond
        (not (p/alive? proc)) :exited
        (started? log port) :ready
        (> (- (System/currentTimeMillis) t0) deadline-ms) :timeout
        :else (do (Thread/sleep 50) (recur))))))

(def ready-deadline-ms
  ;; estimate: the follow-up spike measured spawn-to-describe at 163-221 ms
  ;; (median 214.8 ms, middleware); 15 s leaves room for a first run that
  ;; resolves the injected dep's jars.
  15000)

(def term-grace-ms
  ;; estimate: SIGTERM ran jolt's shutdown hook and exited promptly in the
  ;; dialects spike; a stopped or wedged child gets SIGKILL after this.
  1000)

(defonce ^:private dir-owners
  ;; canonical dir -> {:saved <.nrepl-port text found by the first claim, or nil>
  ;;                   :owners [[token port-or-nil] ...] in claim order}
  (atom {}))

(defonce ^:private owners-lock (Object.))

(defonce ^:private token-counter (atom 0))

(defn claim-dir!
  "Registers a child about to start in dir and returns its token. The first
  claim for a directory reads the .nrepl-port that is there into :saved."
  [dir]
  (let [dir (.getCanonicalPath (io/file dir))
        token (swap! token-counter inc)]
    (locking owners-lock
      (let [entry (or (get @dir-owners dir) {:saved (read-port-file dir) :owners []})]
        (swap! dir-owners assoc dir (update entry :owners conj [token nil]))))
    token))

(defn owner-ready!
  "Records the port token's child listens on and writes .nrepl-port with it,
  since an earlier owner's exit may have deleted the file."
  [dir token port]
  (let [dir (.getCanonicalPath (io/file dir))]
    (locking owners-lock
      (when (get @dir-owners dir)
        (swap! dir-owners update-in [dir :owners]
               (fn [owners] (mapv (fn [[t _ :as o]] (if (= t token) [t port] o)) owners)))
        (try (spit (port-file dir) (str port))
             (catch Exception _ nil))))))

(defn release-dir!
  "Drops token's claim. If other owners remain, .nrepl-port is rewritten
  with the port of the latest-claimed owner that has one (untouched when
  none has). The last owner out restores the saved .nrepl-port, or deletes
  the file when it still holds the releasing child's port."
  [dir token]
  (let [dir (.getCanonicalPath (io/file dir))]
    (locking owners-lock
      (when-let [{:keys [saved owners]} (get @dir-owners dir)]
        (when-let [[_ port] (first (filter (fn [[t _]] (= t token)) owners))]
          (let [remaining (vec (remove (fn [[t _]] (= t token)) owners))
                f (port-file dir)]
            (try
              (if (seq remaining)
                (swap! dir-owners assoc-in [dir :owners] remaining)
                (swap! dir-owners dissoc dir))
              (if (seq remaining)
                (when-let [p (some second (rseq remaining))]
                  (spit f (str p)))
                (cond
                  saved (spit f saved)
                  (and port (.exists f) (= (str port) (str/trim (slurp f)))) (.delete f)))
              (catch Exception _ nil))))))))

(defn- terminate!
  "SIGTERM, then SIGKILL after term-grace-ms if proc is still alive (a
  SIGSTOPped child ignores SIGTERM until continued, SIGKILL never), and
  confirms it exited either way. Returns the exit code, or nil when it
  was already gone. Doesn't touch the port file or the log: callers do
  that themselves, since a failed start! keeps its log and kill! doesn't."
  [proc]
  (when (p/alive? proc)
    (p/destroy proc)
    (let [r (deref proc term-grace-ms ::alive)]
      (if (= ::alive r)
        (do (.destroyForcibly ^java.lang.Process (:proc proc))
            (:exit (deref proc term-grace-ms nil)))
        (:exit r)))))

(defn- start-loop!
  "start!'s port scan. Each spawned proc goes into spawned (an atom) so
  start! can end it if anything throws."
  [dir source token spawned]
  (loop [ports (filter port-free? (range port-base (+ port-base port-span)))]
    (if-let [port (first ports)]
      (let [log (str (System/getProperty "java.io.tmpdir") "/jonquil-nrepl-" (System/nanoTime) ".log")
            proc (apply p/process (process-options {:dir dir :log log})
                        (launch-args port source))
            _ (reset! spawned proc)
            state (wait-ready proc log port ready-deadline-ms)]
        (case state
          :ready (do (owner-ready! dir token port)
                     {:proc proc :pid (.pid ^java.lang.Process (:proc proc)) :port port
                      :dir dir :log log :source source :token token})
          :exited (if (str/includes? (tail log 20) "bind() failed")
                    (do (io/delete-file log true)
                        (recur (rest ports)))
                    ;; The log is left on disk on purpose here, unlike kill!:
                    ;; it's the only post-mortem for a start that never ran.
                    (throw (ex-info (str "jolt nrepl-server exited: " (tail log 5)) {:log log})))
          :timeout (throw (ex-info (str "jolt nrepl-server didn't answer within "
                                        ready-deadline-ms " ms: " (tail log 5))
                                   {:log log}))))
      (throw (ex-info (str "no free port in " port-base "-" (+ port-base port-span -1)) {})))))

(defn start!
  "Launches the child in dir (made absolute here: a relative :dir makes
  the spawn exit 1 silently, measured) with source's middleware injected
  (middleware-source's shape), or a vanilla server when source is nil.
  Returns {:proc :pid :port :dir :log :source :token}. Throws ex-info with the log's tail when no port
  in the range works or the child won't answer. On any throw the spawned
  child is ended and the directory claim released."
  [dir source]
  (let [dir (.getCanonicalPath (io/file dir))
        token (claim-dir! dir)
        spawned (atom nil)]
    (try
      (start-loop! dir source token spawned)
      (catch Throwable t
        (try (when @spawned (terminate! @spawned))
             (finally (release-dir! dir token)))
        (throw t)))))

(defn alive? [child] (boolean (and child (p/alive? (:proc child)))))

(defn kill!
  "Ends the child: SIGTERM (jolt removes .nrepl-port on it), then SIGKILL
  after term-grace-ms if it's still alive. Only the pid this namespace
  spawned is signalled. Then releases its claim on the directory's
  .nrepl-port (see release-dir!) and deletes the log. Returns the exit code, or nil when it was already gone."
  [{:keys [proc log dir token] :as child}]
  (when child
    (let [code (terminate! proc)]
      (release-dir! dir token)
      (try (io/delete-file log true) (catch Exception _ nil))
      code)))
