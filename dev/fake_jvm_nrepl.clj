(ns fake-jvm-nrepl
  "A fake JVM nREPL server for jonquil.nrepl.jvm's tests and the :clj
  self-test window. It imitates clojure-cli.repl 0.1.0 / nREPL 1.7.0's
  replies as measured (P0/P1 of the clj-dialect arc, the spike's own
  measured reply shapes):

  - describe advertises jvm-ops; clone answers a fresh session (ns
    \"user\").
  - eval evaluates its code form by form, in the request's \"ns\" (falling
    back to the session's own ns); a request whose \"ns\" isn't loaded
    answers only the namespace-not-found sentence. Each form answers
    {\"ns\" current \"value\" v} in one message, and a separate {\"status\"
    [\"done\"]} ends the request.
  - load-file evaluates its \"file\" text the same way, but answers only
    the LAST form's {\"value\" v} (never \"ns\"), and never moves the
    session's own namespace.
  - (in-ns 'x) moves the session's namespace (and the request's current
    one) to x; (ns x ...) moves only the request's current one. Both mark
    x loaded.
  - A form matching :fail-on fails with the measured ExceptionInfo shape,
    and nothing after it runs. A form matching :slow waits, waking early
    on an interrupt for its session unless :ignore-interrupt?. A form
    matching :prints answers {\"out\" text} before its value.
  - (read-line) answers need-input, then waits for its session's stdin
    op (or an interrupt), same as a slow form.
  - lookup and doc resolve \"ns\" (falling back to the session's own ns,
    same as eval) against :loaded the same way eval does. lookup under an
    unloaded ns answers a lookup-error (the real server's own measured
    shape); doc under an unloaded ns sends no reply at all, since the
    real server's own doc op throws there and never answers (measured).

  It never evaluates anything: a form's value comes from :answers (keyed
  by the form's pr-str), else the sum for (+ numbers...), else
  \"#'ns/name\" for def/defn/defn-, else \"nil\" for in-ns/ns, else \":ok\".

  Every message it receives is kept, so a test can assert which ops a
  client sent."
  (:require [clojure.string :as str]
            [jonquil.nrepl.bencode :as bencode]))

(def jvm-ops
  ;; describe's default ops: counted directly off reply-shapes.md's own
  ;; comment text (20 names). That comment labels itself "(21)", but the
  ;; list under it has only 20 -- measured by splitting the raw lines, not
  ;; recalled from the label.
  ["auto-require" "clone" "close" "completions" "describe" "doc" "eval"
   "forward-system-output" "inspect-def" "inspect-in" "inspect-out"
   "inspect-page" "inspect-start" "inspect-toggle-meta" "inspect-toggle-table"
   "interrupt" "load-file" "lookup" "ls-sessions" "stdin"])

(def fail-err
  ;; The measured shape for a form matching :fail-on.
  "Execution error (ExceptionInfo) at user/eval1 (REPL:1).\nboom\n")

(def fail-ex-class
  ;; Both :ex and :root-ex, measured.
  "class clojure.lang.ExceptionInfo")

(defn- live? [stop-latch closed] (not (or (realized? stop-latch) (realized? closed))))

(defn- wait!
  "Waits up to ms, waking at once (well under ms) once stop-latch or closed
  resolves. Jolt's socket read/write don't check a :closed flag on their
  own, so a Thread/sleep here would otherwise sleep out its full ms even
  after stop! or drop-connections! has ended this connection."
  [stop-latch closed ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (when (and (< (System/currentTimeMillis) deadline) (live? stop-latch closed))
        (Thread/sleep (min 10 (max 0 (- deadline (System/currentTimeMillis)))))
        (recur)))))

(defn- read-forms
  "code's top-level forms, in order. Throws on unbalanced code."
  [code]
  (let [r (java.io.PushbackReader. (java.io.StringReader. code))]
    (loop [acc []]
      (let [f (read r false ::eof)]
        (if (= ::eof f) acc (recur (conj acc f)))))))

(defn- head [form] (when (seq? form) (first form)))

(defn- matches? [marker form] (and marker (str/includes? (pr-str form) marker)))

(defn- default-value [current form]
  (cond
    ;; Jonquil's wake-up probe is (+ 1 2), so sums of numbers answer as a
    ;; real REPL would (measured: "3").
    (and (= '+ (head form)) (every? number? (rest form))) (str (apply + (rest form)))
    (#{'def 'defn 'defn-} (head form)) (str "#'" current "/" (second form))
    (#{'in-ns 'ns} (head form)) "nil"
    :else ":ok"))

(defn- wait-for!
  "Waits up to ms, waking early on: the server stopping (:stopped), the
  session's interrupt-p resolving (:interrupted, only when interrupt-p is
  given), or extra-p resolving (:done, only when extra-p is given).
  :elapsed when ms runs out first. Polls every 10ms, the way wait! does."
  [stop-latch closed interrupt-p extra-p ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (cond
        (not (live? stop-latch closed)) :stopped
        (and interrupt-p (realized? interrupt-p)) :interrupted
        (and extra-p (realized? extra-p)) :done
        (>= (System/currentTimeMillis) deadline) :elapsed
        :else (do (Thread/sleep (min 10 (max 0 (- deadline (System/currentTimeMillis)))))
                  (recur))))))

(defn- evaluate!
  "Answers one eval or load-file request, form by form. reply sends one
  message map (string keys) with the request's id. A throw anywhere in
  here (e.g. read-forms on malformed/incomplete code) answers the
  request with the same eval-error shape :fail-on uses, instead of
  escaping this request's own future -- stop!'s cleanup deref would
  otherwise rethrow it and abort the wait for every future after it.

  An interrupt or stdin op can arrive on the connection's own thread
  before this future has reached the point where it registers its
  session's :interrupt/:stdin promise (handle! dispatches the future and
  returns at once). handle! records that early arrival at
  :pending-interrupt/:pending-stdin instead of dropping it; every
  registration point here atomically installs its promise AND consumes
  any such pending flag in the same swap!, so the two can never race
  past each other and lose the signal."
  [{:keys [opts sessions loaded]} stop-latch closed reply m]
  (try
    (let [sid (get m "session")
          op (get m "op")
          eval? (= "eval" op)
          text (or (get m "code") (get m "file") "")
          req-ns (get m "ns")
          sess-ns (get-in @sessions [sid :ns] "user")]
      (if (and eval? req-ns (not (contains? @loaded req-ns)))
        (reply {"ns" req-ns "status" ["namespace-not-found" "done" "error"]})
        (let [forms (read-forms text)
              start-ns (or req-ns sess-ns)]
          (loop [fs (seq forms) current start-ns last-value nil]
            (cond
              (nil? fs)
              (if eval?
                (reply {"status" ["done"]})
                (do (reply {"value" last-value}) (reply {"status" ["done"]})))

              (matches? (:fail-on opts) (first fs))
              (do (reply {"err" fail-err})
                  (reply {"ex" fail-ex-class "root-ex" fail-ex-class "status" ["eval-error"]})
                  (reply {"status" ["done"]}))

              (and eval? (= 'read-line (head (first fs))))
              (let [stdin-p (promise)
                    interrupt-p (promise)]
                (reply {"status" ["need-input"]})
                (let [[old] (swap-vals! sessions update sid
                                        (fn [s] (-> s (assoc :stdin stdin-p :interrupt interrupt-p)
                                                    (dissoc :pending-stdin :pending-interrupt))))
                      prior (get old sid)]
                  (when-let [early (:pending-stdin prior)] (deliver stdin-p early))
                  (when (:pending-interrupt prior) (deliver interrupt-p true)))
                (let [outcome (wait-for! stop-latch closed interrupt-p stdin-p 10000)]
                  (swap! sessions update sid dissoc :stdin :interrupt)
                  (cond
                    (= :stopped outcome) :dropped
                    (= :interrupted outcome) (reply {"status" ["done" "interrupted"]})
                    :else
                    (let [line (str/replace (or @stdin-p "") #"\n$" "")]
                      (reply {"ns" current "value" (pr-str line)})
                      (recur (next fs) current last-value)))))

              :else
              (let [f (first fs)
                    ms (some (fn [[marker ms]] (when (matches? marker f) ms)) (:slow opts))
                    wait-outcome
                    (when ms
                      (if (:ignore-interrupt? opts)
                        (do (wait! stop-latch closed ms) (if (live? stop-latch closed) :elapsed :stopped))
                        (let [ip (promise)
                              [old] (swap-vals! sessions update sid
                                                (fn [s] (-> s (assoc :interrupt ip) (dissoc :pending-interrupt))))]
                          (when (:pending-interrupt (get old sid)) (deliver ip true))
                          (let [o (wait-for! stop-latch closed ip nil ms)]
                            (swap! sessions update sid dissoc :interrupt)
                            o))))]
                (cond
                  (= :stopped wait-outcome) :dropped
                  (= :interrupted wait-outcome) (reply {"status" ["done" "interrupted"]})
                  :else
                  (let [printed (some (fn [[marker text]] (when (matches? marker f) text)) (:prints opts))
                        _ (when printed (reply {"out" printed}))
                        in-ns-form? (= 'in-ns (head f))
                        ns-form? (= 'ns (head f))
                        new-current (cond
                                      in-ns-form?
                                      (let [target (str (second (second f)))]
                                        (swap! loaded conj target)
                                        (when eval? (swap! sessions assoc-in [sid :ns] target))
                                        target)
                                      ns-form?
                                      (let [target (str (second f))]
                                        (swap! loaded conj target)
                                        target)
                                      :else current)
                        v (get (:answers opts) (pr-str f) (default-value new-current f))]
                    (if eval?
                      (do (reply {"ns" new-current "value" v})
                          (recur (next fs) new-current last-value))
                      (recur (next fs) new-current v))))))))))
    (catch Throwable e
      ;; The error replies can throw too, when the client closed the
      ;; socket while a reply was in flight (its Stop detaching at once).
      ;; Nobody is listening then, so there's nothing left to answer.
      (try
        (reply {"err" (str (ex-message e) "\n")})
        (reply {"ex" fail-ex-class "root-ex" fail-ex-class "status" ["eval-error"]})
        (reply {"status" ["done"]})
        (catch Throwable _ nil)))))

(defn- handle!
  "Answers one request, incrementing srv's :busy count for its duration (a
  bounded finally, never left stuck up on a throw). :busy only covers
  this synchronous call, though: eval and load-file dispatch onto their
  own future (tracked in srv's :futures, the same one stop! already
  waits on) and handle! returns almost at once, so a slow eval is NOT
  reflected in :busy -- stop!'s own :futures deref loop is what actually
  waits for one to finish. Dispatching that way is what lets this
  connection's own reading thread go on to see a later interrupt or
  stdin op for the same in-flight eval instead of blocking behind it.

  interrupt/stdin arriving before that future has registered its
  session's own promise are recorded at :pending-interrupt/:pending-stdin
  (atomically: either an existing promise gets the delivery, or the
  pending flag gets set, never both) instead of being silently dropped;
  evaluate!'s own registration points consume them."
  [{:keys [opts sessions log counter busy futures loaded] :as srv} _sock stop-latch closed reply m]
  (swap! log conj (dissoc m "id"))
  (swap! busy inc)
  (try
    (if-not (live? stop-latch closed)
      :dropped
      (case (get m "op")
        "describe" (reply {"ops" (zipmap (:ops opts jvm-ops) (repeat {})) "status" ["done"]})
        "clone" (let [sid (str "fake-session-" (swap! counter inc))]
                  (swap! sessions assoc sid {:ns "user"})
                  (reply {"new-session" sid "status" ["done"]}))
        ("eval" "load-file")
        (let [sid (get m "session")]
          ;; a stray pending flag from an EARLIER, already-finished request
          ;; on this session must never bleed into this new one.
          (swap! sessions update sid dissoc :pending-interrupt :pending-stdin)
          (swap! futures conj (future (evaluate! srv stop-latch closed reply m))))
        "interrupt"
        (let [sid (get m "session")
              [old] (swap-vals! sessions update sid
                                (fn [s] (if (:interrupt s) s (assoc s :pending-interrupt true))))]
          (when-let [p (:interrupt (get old sid))] (deliver p true))
          (reply {"status" ["done"]}))
        "stdin"
        (let [sid (get m "session")
              text (get m "stdin")
              [old] (swap-vals! sessions update sid
                                (fn [s] (if (:stdin s) s (assoc s :pending-stdin text))))]
          (when-let [p (:stdin (get old sid))] (deliver p text))
          (reply {"status" ["done"]}))
        "completions"
        (let [rows (get (:completions opts) (get m "prefix"))]
          (reply {"completions" (mapv (fn [[c n]] {"candidate" c "ns" n "type" "function"}) rows)
                  "status" ["done"]}))
        "lookup"
        (let [req-ns (get m "ns")
              sid (get m "session")
              sess-ns (get-in @sessions [sid :ns] "user")
              ns (or req-ns sess-ns)]
          (when-let [ms (:lookup-ms opts)] (wait! stop-latch closed ms))
          (if (contains? @loaded ns)
            (reply {"info" (get (:docs opts) (get m "sym") []) "status" ["done"]})
            (reply {"message" (str "No namespace: " ns " found")
                    "status" ["done" "error" "lookup-error"]})))
        "doc"
        (let [req-ns (get m "ns")
              sid (get m "session")
              sess-ns (get-in @sessions [sid :ns] "user")
              ns (or req-ns sess-ns)]
          ;; The real server's own doc op calls the-ns and throws when ns
          ;; isn't loaded, and never answers at all (measured, the spike's
          ;; probe output) -- so this sends nothing for that case either,
          ;; instead of the plain "No doc for ..." fallback below.
          (when (contains? @loaded ns)
            (wait! stop-latch closed (:doc-ms opts 0))
            (reply {"doc" (get (:full-docs opts) (get m "sym") (str "No doc for " (get m "sym")))
                    "status" ["done"]})))
        ("close" "ls-sessions" "auto-require") (reply {"status" ["done"]})
        (reply {"status" ["done" "unknown-op"]})))
    (finally (swap! busy dec))))

(defn- serve-connection!
  [{:keys [opts conns stop-latch] :as srv} ^java.net.Socket sock]
  (let [closed (promise)]
    (swap! conns conj {:sock sock :closed closed})
    (let [src (bencode/byte-source (.getInputStream sock))
          out (.getOutputStream sock)
          lock (Object.)]
      (try
        (loop []
          (when (live? stop-latch closed)
            (let [m (bencode/decode src)]
              (when (and (not= ::bencode/eof m) (live? stop-latch closed))
                (let [reply (fn [kv]
                              (when (and (not (:mute? opts)) (live? stop-latch closed))
                                (locking lock
                                  (.write ^java.io.OutputStream out ^bytes (bencode/encode (assoc kv "id" (get m "id"))))
                                  (.flush ^java.io.OutputStream out))))
                      result (handle! srv sock stop-latch closed reply m)]
                  (when (and (not= :dropped result) (live? stop-latch closed))
                    (recur)))))))
        (catch Throwable _ nil)
        (finally
          (try (.close sock) (catch Throwable _ nil)))))))

(defn start!
  "Starts a fake server on a free loopback port and returns it. opts:
  :ops       the ops describe advertises (default jvm-ops)
  :answers   {form-pr-str printed-value}
  :fail-on   marker: that form fails with the measured ExceptionInfo
             shape, and the rest of its request is skipped
  :slow      {marker ms}: a form whose pr-str holds marker waits ms
             before answering, waking early on an interrupt for its
             session
  :ignore-interrupt? true: an interrupt during a :slow wait doesn't wake
             it -- the eval keeps waiting out its ms
  :prints    {marker text}: that form answers {\"out\" text} before its
             value
  :completions {prefix [[candidate ns] ...]}: completions answers these
             (string-keyed maps), or [] for an unknown prefix
  :docs      {sym info}: lookup under a LOADED \"ns\" answers {\"info\"
             info}, or {\"info\" []} for an unknown symbol; an unloaded
             \"ns\" answers a lookup-error instead, whatever :docs holds
  :lookup-ms ms: lookup answers after this delay (as the cljd fake's)
  :full-docs {sym text}: doc under a LOADED \"ns\" answers text, or
             \"No doc for \" + sym; an unloaded \"ns\" gets no reply at all
  :doc-ms    ms: doc answers after this delay (default 0), a loaded ns only
  :loaded    extra namespace name strings considered loaded from the
             start, alongside \"user\"
  :mute?     read requests but never answer any"
  ([] (start! {}))
  ([opts]
   (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
         srv {:opts opts :server ss :port (.getLocalPort ss)
              :sessions (atom {}) :log (atom []) :counter (atom 0)
              :conns (atom []) :futures (atom [])
              :stopped? (atom false) :stop-latch (promise) :busy (atom 0)
              :loaded (atom (into #{"user"} (:loaded opts)))}]
     (future
       (try
         (loop []
           (let [sock (.accept ss)]
             (if @(:stopped? srv)
               (.close sock)
               (do (swap! (:futures srv) conj (future (serve-connection! srv sock)))
                   (recur)))))
         (catch Throwable _ nil)))
     srv)))

(defn ops-seen
  "Every op the server has received, in order."
  [srv]
  (mapv (fn [m] (get m "op")) @(:log srv)))

(defn requests
  "Every request the server has received (string keys, no :id), in order."
  [srv]
  @(:log srv))

(defn drop-connections!
  "Closes every client socket from the server's side, the way an app that
  quits drops its nREPL clients. Delivers each connection's :closed first,
  so a :slow/:slow-ops wait in flight on it wakes at once instead of
  sleeping out its full ms against a socket that's about to disappear."
  [srv]
  (doseq [{:keys [sock closed]} @(:conns srv)]
    (deliver closed true)
    (try (.close ^java.net.Socket sock) (catch Throwable _ nil)))
  nil)

(defn- await-bounded!
  "Waits up to ms-total for (pred) to become true, polling every 10ms."
  [ms-total pred]
  (let [deadline (+ (System/currentTimeMillis) (long ms-total))]
    (loop []
      (when (and (not (pred)) (< (System/currentTimeMillis) deadline))
        (Thread/sleep 10)
        (recur)))))

(defn stop!
  "Stops accepting and drops every client, and doesn't return until every
  connection's own thread has actually ended (bounded), so a stale thread
  from this server can never answer a socket a later server reuses (jolt's
  socket read/write don't check a :closed flag on their own, so a thread
  that outlived stop! could otherwise still read and reply on a reused
  fd -- measured, 2 of 3 runs). Order: wake every sleeper (the latch),
  wait for in-flight replies to finish (busy), only then close sockets, and
  only then wait for the connection threads themselves to end. Measured on
  Jolt v0.8.13: closing a ServerSocket doesn't wake a thread blocked in
  accept, so this connects once first to let the accept loop see the flag
  and end."
  [srv]
  (reset! (:stopped? srv) true)
  (deliver (:stop-latch srv) true)
  (await-bounded! 1000 (fn [] (zero? @(:busy srv))))
  (try (.close (java.net.Socket. "127.0.0.1" (int (:port srv)))) (catch Throwable _ nil))
  (try (.close ^java.net.ServerSocket (:server srv)) (catch Throwable _ nil))
  (drop-connections! srv)
  (let [deadline (+ (System/currentTimeMillis) 1000)]
    (doseq [fut @(:futures srv)]
      (deref fut (max 0 (- deadline (System/currentTimeMillis))) nil)))
  nil)
