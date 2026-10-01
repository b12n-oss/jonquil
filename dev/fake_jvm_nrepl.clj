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
  client sent.

  inspect-start, inspect-in, inspect-out, inspect-page, inspect-toggle-meta
  and inspect-toggle-table run jonquil.inspect.view/run-op against one view
  per server (mirroring the real server's own single global atom).
  inspect-def never interns (the fake never evaluates Clojure): it answers
  the current view plus :defd instead. A session is registered -- so
  inspect-start sees its *1, not nil -- only once an eval op for it has
  been received; load-file alone leaves it unregistered (the spike's own
  measured probe 1 behaviour). *1 itself is the last eval/load-file value
  the fake produced, read back with edn/read-string when it parses cleanly,
  else kept as the printed string."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fake-nrepl-core :as core :refer [head live? matches? read-forms wait!]]
            [jonquil.inspect.view :as view]))

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

(defn- default-value [current form]
  (cond
    ;; Jonquil's wake-up probe is (+ 1 2), so sums of numbers answer as a
    ;; real REPL would (measured: "3").
    (and (= '+ (head form)) (every? number? (rest form))) (str (apply + (rest form)))
    (#{'def 'defn 'defn-} (head form)) (str "#'" current "/" (second form))
    (#{'in-ns 'ns} (head form)) "nil"
    ;; A literal nil answers nil, as a real REPL does (the JVM evaluator's
    ;; connect-time inspect registration evaluates exactly this).
    (nil? form) "nil"
    :else ":ok"))

(defn- read-printed-value
  "s (a session's last eval/load-file printed value, or nil) read back
  with edn/read-string, or s itself when it doesn't parse as edn (a var
  literal like \"#'user/x\", or anything else the edn reader rejects). nil
  reads as nil (edn/read-string's own nil/empty-string case)."
  [s]
  (try (edn/read-string s) (catch Exception _ s)))

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
  past each other and lose the signal.

  last-values keeps this session's own *1 (the last value this request
  produced, eval or load-file alike), read at inspect-start time."
  [{:keys [opts sessions loaded last-values]} stop-latch closed reply m]
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
              (do
                (swap! last-values assoc sid last-value)
                (if eval?
                  (reply {"status" ["done"]})
                  (do (reply {"value" last-value}) (reply {"status" ["done"]}))))

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
                          (recur (next fs) new-current v))
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
  evaluate!'s own registration points consume them.

  registered, view and last-values back the inspect-* ops: registered
  gains a session id only for an \"eval\" op (never \"load-file\"), view is
  the one per-server inspector view state (mirroring the real server's own
  single global atom), and last-values is that session's *1."
  [{:keys [opts sessions log counter busy futures loaded registered view last-values] :as srv} _sock stop-latch closed reply m]
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
          ;; inspect-start only sees a session's *1 once it's registered
          ;; this way (spike probe 1): load-file alone never registers it.
          (when (= "eval" (get m "op")) (swap! registered conj sid))
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
        "inspect-start"
        (let [sid (get m "session")]
          (when-let [ms (:inspect-ms opts)] (wait! stop-latch closed ms))
          (let [value (when (contains? @registered sid) (read-printed-value (get @last-values sid)))]
            (reply {"view" (pr-str (view/run-op view :start {:value value :rows (get m "rows") :cols (get m "cols")}))
                    "status" ["done"]})))
        "inspect-in"
        (do (when-let [ms (:inspect-ms opts)] (wait! stop-latch closed ms))
            (reply {"view" (pr-str (view/run-op view :in {:idx (get m "idx")})) "status" ["done"]}))
        "inspect-out"
        (do (when-let [ms (:inspect-ms opts)] (wait! stop-latch closed ms))
            (reply {"view" (pr-str (view/run-op view :out {})) "status" ["done"]}))
        "inspect-page"
        (do (when-let [ms (:inspect-ms opts)] (wait! stop-latch closed ms))
            (reply {"view" (pr-str (view/run-op view :page {:delta (get m "delta")})) "status" ["done"]}))
        "inspect-toggle-meta"
        (do (when-let [ms (:inspect-ms opts)] (wait! stop-latch closed ms))
            (reply {"view" (pr-str (view/run-op view :meta {:cursor (get m "cursor")})) "status" ["done"]}))
        "inspect-toggle-table"
        (do (when-let [ms (:inspect-ms opts)] (wait! stop-latch closed ms))
            (reply {"view" (pr-str (view/run-op view :table {})) "status" ["done"]}))
        ;; inspect-def never interns (the fake never evaluates Clojure):
        ;; the current view plus :defd, naming the session's own ns (as
        ;; the real server would intern in its *ns*).
        "inspect-def"
        (let [sid (get m "session")
              ns (get-in @sessions [sid :ns] "user")]
          (when-let [ms (:inspect-ms opts)] (wait! stop-latch closed ms))
          (reply {"view" (pr-str (view/respond view {:defd (str "#'" ns "/" (get m "name"))}))
                  "status" ["done"]}))
        ("close" "ls-sessions" "auto-require") (reply {"status" ["done"]})
        (reply {"status" ["done" "unknown-op"]})))
    (finally (swap! busy dec))))

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
  :inspect-ms ms: any inspect-* op answers after this delay (as :doc-ms
             delays doc)
  :mute?     read requests but never answer any"
  ([] (start! {}))
  ([opts] (core/start-server! opts handle! {:loaded (atom (into #{"user"} (:loaded opts))) :view (atom nil) :registered (atom #{}) :last-values (atom {})})))

(def ops-seen "Every op the server has received, in order." core/ops-seen)

(def requests "Every request the server has received (string keys, no :id), in order." core/requests)

(def drop-connections!
  "Closes every client socket from the server's side (fake-nrepl-core)."
  core/drop-connections!)

(def stop!
  "Stops the server and waits for its connection threads (fake-nrepl-core)."
  core/stop!)
