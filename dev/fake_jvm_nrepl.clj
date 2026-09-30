(ns fake-jvm-nrepl
  "A fake JVM nREPL server for jonquil.nrepl.jvm's tests and the :clj
  self-test window. It imitates clojure-cli.repl 0.1.0 / nREPL 1.7.0's
  replies as measured (P0/P1 of the clj-dialect arc, see
  .superpowers/sdd/clj/reply-shapes.md):

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
  message map (string keys) with the request's id."
  [{:keys [opts sessions loaded]} stop-latch closed reply m]
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
            (let [stdin-p (promise) interrupt-p (promise)]
              (reply {"status" ["need-input"]})
              (swap! sessions assoc-in [sid :stdin] stdin-p)
              (swap! sessions assoc-in [sid :interrupt] interrupt-p)
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
                      (let [ip (promise)]
                        (swap! sessions assoc-in [sid :interrupt] ip)
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
                    (recur (next fs) new-current v)))))))))))

(defn- handle!
  "Answers one request, incrementing srv's :busy count for its duration (a
  bounded finally, never left stuck up on a throw) so stop! can wait for
  every in-flight reply to finish before it closes anything. eval and
  load-file run on their own future (tracked in srv's :futures, the same
  one stop! already waits on) so a slow one never blocks this connection's
  own reading thread from seeing a later interrupt or stdin op."
  [{:keys [opts sessions log counter busy futures] :as srv} _sock stop-latch closed reply m]
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
        (swap! futures conj (future (evaluate! srv stop-latch closed reply m)))
        "interrupt"
        (let [sid (get m "session")]
          (when-let [p (get-in @sessions [sid :interrupt])] (deliver p true))
          (reply {"status" ["done"]}))
        "stdin"
        (let [sid (get m "session")]
          (when-let [p (get-in @sessions [sid :stdin])] (deliver p (get m "stdin")))
          (reply {"status" ["done"]}))
        "completions"
        (let [rows (get (:completions opts) (get m "prefix"))]
          (reply {"completions" (mapv (fn [[c n]] {"candidate" c "ns" n "type" "function"}) rows)
                  "status" ["done"]}))
        "lookup"
        (reply {"info" (get (:docs opts) (get m "sym") []) "status" ["done"]})
        "doc"
        (do (wait! stop-latch closed (:doc-ms opts 0))
            (reply {"doc" (get (:full-docs opts) (get m "sym") (str "No doc for " (get m "sym")))
                    "status" ["done"]}))
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
  :docs      {sym info}: lookup answers {\"info\" info}, or {\"info\" []}
             for an unknown symbol
  :full-docs {sym text}: doc answers text, or \"No doc for \" + sym
  :doc-ms    ms: doc answers after this delay (default 0)
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
