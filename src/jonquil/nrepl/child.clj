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
  before the child started belongs to someone else, so it is read first
  and written back once the child is gone."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]
            [jolt.socket]))

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

(defn launch-args
  "The child's argv. With a middleware root, the follow-up spike's
  command: jolt -Sdeps '{:deps {nrepl/nrepl {:local/root R}}
  :nrepl/middleware [nrepl.middleware/default-middleware]}' nrepl-server P."
  [port root]
  (if root
    ["jolt" "-Sdeps"
     (str "{:deps {nrepl/nrepl {:local/root " (pr-str root) "}}"
          " :nrepl/middleware [nrepl.middleware/default-middleware]}")
     "nrepl-server" (str port)]
    ["jolt" "nrepl-server" (str port)]))

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

(defn start!
  "Launches the child in dir (made absolute here: a relative :dir makes
  the spawn exit 1 silently, measured) with root's middleware injected, or
  a vanilla server when root is nil. Returns {:proc :pid :port :dir :log
  :root :saved-port-file}. Throws ex-info with the log's tail when no port
  in the range works or the child won't answer."
  [dir root]
  (let [dir (.getCanonicalPath (io/file dir))
        saved (read-port-file dir)]
    (loop [ports (filter port-free? (range port-base (+ port-base port-span)))]
      (if-let [port (first ports)]
        (let [log (str (System/getProperty "java.io.tmpdir") "/jonquil-nrepl-" (System/nanoTime) ".log")
              proc (apply p/process {:dir dir :out (io/file log) :err :out :in (io/file "/dev/null")}
                          (launch-args port root))
              state (wait-ready proc log port ready-deadline-ms)]
          (case state
            :ready {:proc proc :pid (.pid ^java.lang.Process (:proc proc)) :port port
                    :dir dir :log log :root root :saved-port-file saved}
            :exited (if (str/includes? (tail log 20) "bind() failed")
                      (recur (rest ports))
                      (throw (ex-info (str "jolt nrepl-server exited: " (tail log 5)) {:log log})))
            :timeout (do (p/destroy proc)
                         (throw (ex-info (str "jolt nrepl-server didn't answer within "
                                              ready-deadline-ms " ms: " (tail log 5)) {:log log})))))
        (throw (ex-info (str "no free port in " port-base "-" (+ port-base port-span -1)) {}))))))

(defn alive? [child] (boolean (and child (p/alive? (:proc child)))))

(def term-grace-ms
  ;; estimate: SIGTERM ran jolt's shutdown hook and exited promptly in the
  ;; dialects spike; a stopped or wedged child gets SIGKILL after this.
  1000)

(defn- restore-port-file! [{:keys [dir port saved-port-file]}]
  (let [f (port-file dir)]
    (try
      (cond
        saved-port-file (spit f saved-port-file)
        (and (.exists f) (= (str port) (str/trim (slurp f)))) (.delete f))
      (catch Exception _ nil))))

(defn kill!
  "Ends the child: SIGTERM (jolt removes .nrepl-port on it), then SIGKILL
  after term-grace-ms if it's still alive (a SIGSTOPped child ignores
  SIGTERM until continued, SIGKILL never). Only the pid this namespace
  spawned is signalled. Then restores the .nrepl-port it found and deletes
  the log. Returns the exit code, or nil when it was already gone."
  [{:keys [proc log] :as child}]
  (when child
    (let [code (when (p/alive? proc)
                 (p/destroy proc)
                 (let [r (deref proc term-grace-ms ::alive)]
                   (if (= ::alive r)
                     (do (.destroyForcibly ^java.lang.Process (:proc proc))
                         (:exit (deref proc term-grace-ms nil)))
                     (:exit r))))]
      (restore-port-file! child)
      (try (io/delete-file log true) (catch Exception _ nil))
      code)))
