(ns jonquil.nrepl.child
  "The out-of-process Jolt child: pick a port, launch Jolt's nREPL server
  in the project directory with nREPL middleware injected (its deps through
  -Sdeps), wait until it answers, and end it again, killing only the pid it
  spawned.

  Port rules (~/.claude/rules/dynamic-port-selection.md): a candidate must
  bind on both 127.0.0.1 and the wildcard address (a loopback bind
  succeeds on macOS while another process holds *:p), and the child's own
  bind is the real attempt: a child that exits because its bind failed
  moves the scan to the next port. There is no port override to honour.

  The server writes no .nrepl-port. `jolt nrepl-server` would write one
  into the project while the child runs, where it showed in git status
  and where another nREPL client (a JVM Clojure window on the same
  project, say) could take Jonquil's own child for the project's server.
  So the child runs `jolt -e` with jolt.nrepl/start's {:port-file nil}
  (Jolt v0.8.18 and later), doing what nrepl-server does around it: block
  SIGINT, add the shutdown hook, park the main thread. Jonquil picks the
  port itself, so nothing needs the file; a .nrepl-port of the user's own
  is never touched."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]
            [jolt.socket]
            [jonquil.login-path :as login-path]
            [jonquil.nrepl.leftovers :as leftovers]
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

(defn server-form
  "The -e form that runs the server on port with the middleware symbols,
  as `jolt nrepl-server` does (jolt.main) but with no port file, plus a
  watcher: a thread that reads the child's stdin, a pipe Jonquil holds
  open for the child's whole life, and exits the child at its end. When
  Jonquil dies in a way that runs no hook (a crash, SIGKILL), the OS
  closes the pipe, so the child doesn't outlive it (measured: exited
  within 3 s of its parent's SIGKILL). System/exit runs the shutdown hook,
  which stops the server. *in*'s root is an empty reader, so a program's
  read on a plain server (no stdin middleware) ends at once, as it did
  with /dev/null for stdin, instead of waiting on the watcher's pipe."
  [port middleware]
  (str "(do (require 'jolt.nrepl) (jolt.host/block-sigint)"
       " (alter-var-root #'*in* (constantly (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. \"\"))))"
       " (let [stop (jolt.nrepl/start " port " '[" (str/join " " middleware) "] {:port-file nil})]"
       " (jolt.host/add-shutdown-hook stop)"
       " (future (try (let [in System/in] (loop [] (when-not (neg? (.read in)) (recur)))) (catch Throwable _ nil))"
       " (System/exit 0))"
       " (jolt.host/park-until-interrupt) (stop)))"))

(defn launch-args
  "The child's argv. With a middleware source: jolt -Sdeps '{:deps
  {nrepl/nrepl COORD}}' -e (server-form P [nrepl.middleware/default-middleware]),
  where COORD is {:local/root R} for {:local R} or the git coordinate for
  {:git coord}. With a nil source, a plain server: jolt -e (server-form P []).
  source's :jonquil (or jonquil-root, in the three-argument form), when set,
  is jonquil.nrepl.middleware-files' folder: it joins the deps as
  jonquil/nrepl-middleware. The inspector's middleware and jonquil-info's go
  last, so even a plain server answers their ops; stdin's goes first, around
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
                      jonquil-root (conj (str middleware-files/inspect-middleware)
                                         (str middleware-files/info-middleware)))]
     (if (seq deps)
       [program "-Sdeps" (str "{:deps {" (str/join " " deps) "}}")
        "-e" (server-form port middleware)]
       [program "-e" (server-form port middleware)]))))

(defn jonquil-root
  "The inspector's folder for a child about to start (the evaluator puts it
  on the source as :jonquil), or nil for none; throws when it can't be
  written. A var of its own so tests can stand in for the cache folder."
  []
  (middleware-files/ensure-root!))

(defn process-options
  "The jolt.process options for the child: {:dir :log}, plus login-path's
  PATH in the child's environment so jolt's own tool lookups see it
  (no :extra-env when there is no PATH at all). stdin is left as a pipe,
  which the process record holds open until kill!: server-form's watcher
  ends the child when it closes, Jonquil's own death included."
  [{:keys [dir log]}]
  (let [path (login-path/path)]
    (cond-> {:dir dir :out (io/file log) :err :out}
      path (assoc :extra-env {"PATH" path}))))

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

(defn- terminate!
  "SIGTERM, then SIGKILL after term-grace-ms if proc is still alive (a
  SIGSTOPped child ignores SIGTERM until continued, SIGKILL never), and
  confirms it exited either way. Returns the exit code, or nil when it
  was already gone. Doesn't touch the log: a failed start! keeps it and
  kill! doesn't."
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
  [dir source spawned]
  (loop [ports (filter port-free? (range port-base (+ port-base port-span)))]
    (if-let [port (first ports)]
      (let [log (str (System/getProperty "java.io.tmpdir") "/jonquil-nrepl-" (System/nanoTime) ".log")
            proc (apply p/process (process-options {:dir dir :log log})
                        (launch-args port source))
            _ (reset! spawned proc)
            state (wait-ready proc log port ready-deadline-ms)]
        (case state
          :ready {:proc proc :pid (.pid ^java.lang.Process (:proc proc)) :port port
                  :dir dir :log log :source source}
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
  Returns {:proc :pid :port :dir :log :source}. Throws ex-info with the log's tail when no port
  in the range works or the child won't answer. On any throw the spawned
  child is ended."
  [dir source]
  (leftovers/prune!)
  (let [dir (.getCanonicalPath (io/file dir))
        spawned (atom nil)]
    (try
      (start-loop! dir source spawned)
      (catch Throwable t
        (when @spawned (terminate! @spawned))
        (throw t)))))

(defn alive? [child] (boolean (and child (p/alive? (:proc child)))))

(defn kill!
  "Ends the child: SIGTERM, then SIGKILL after term-grace-ms if it's still
  alive. Only the pid this namespace spawned is signalled. Then deletes the
  log. Returns the exit code, or nil when it was already gone."
  [{:keys [proc log] :as child}]
  (when child
    (let [code (terminate! proc)]
      (try (io/delete-file log true) (catch Exception _ nil))
      code)))
