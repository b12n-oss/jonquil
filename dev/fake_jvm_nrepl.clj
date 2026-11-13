(ns fake-jvm-nrepl
  "A fake JVM nREPL server for jonquil.nrepl.jvm's tests and the :clj
  self-test window. It imitates clojure-cli.repl 0.1.0 / nREPL 1.7.0's
  replies as measured:

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
  been received; load-file alone leaves it unregistered (measured
  behaviour). *1 itself is the last eval/load-file value
  the fake produced, read back with edn/read-string when it parses cleanly,
  else kept as the printed string."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fake-nrepl-core :as core :refer [head live? matches? read-forms
                                              wait!]]
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

(defn- finish-eval!
  "Ends a request whose forms are all answered: records last-value as the
  session's *1, then answers done (load-file also answers its last value)."
  [{:keys [last-values reply sid eval?]} last-value]
  (swap! last-values assoc sid last-value)
  (if eval?
    (reply {"status" ["done"]})
    (do (reply {"value" last-value}) (reply {"status" ["done"]}))))

(defn- fail-form!
  "Answers a form matching :fail-on with the measured ExceptionInfo shape."
  [{:keys [reply]}]
  (reply {"err" fail-err})
  (reply {"ex" fail-ex-class "root-ex" fail-ex-class "status" ["eval-error"]})
  (reply {"status" ["done"]}))

(defn- register-stdin-wait!
  "Installs the session's :stdin and :interrupt promises and consumes any
  pending flags in the same swap!."
  [sessions sid stdin-p interrupt-p]
  (let [[old] (swap-vals! sessions update sid
                          (fn [s] (-> s (assoc :stdin stdin-p :interrupt interrupt-p)
                                      (dissoc :pending-stdin :pending-interrupt))))
        prior (get old sid)]
    (when-let [early (:pending-stdin prior)] (deliver stdin-p early))
    (when (:pending-interrupt prior) (deliver interrupt-p true))))

(defn- read-line-form!
  "Answers need-input, then waits for the session's stdin op or an
  interrupt. Returns {:next [current last-value]} once a line arrived."
  [{:keys [sessions stop-latch closed reply sid]} current last-value]
  (let [stdin-p (promise)
        interrupt-p (promise)]
    (reply {"status" ["need-input"]})
    (register-stdin-wait! sessions sid stdin-p interrupt-p)
    (let [outcome (wait-for! stop-latch closed interrupt-p stdin-p 10000)]
      (swap! sessions update sid dissoc :stdin :interrupt)
      (cond
        (= :stopped outcome) :dropped
        (= :interrupted outcome) (reply {"status" ["done" "interrupted"]})
        :else
        (let [line (str/replace (or @stdin-p "") #"\n$" "")]
          ;; an empty stdin is end-of-input: nREPL 1.8.0's
          ;; read-line answers nil, not "".
          (reply {"ns" current "value" (if (= "" @stdin-p) "nil" (pr-str line))})
          {:next [current last-value]})))))

(defn- slow-wait-ms
  "The :slow delay for form f, or nil."
  [opts f]
  (some (fn [[marker ms]] (when (matches? marker f) ms)) (:slow opts)))

(defn- interruptible-wait!
  "Waits ms for the request's session, waking early on an interrupt."
  [{:keys [sessions stop-latch closed sid]} ms]
  (let [ip (promise)
        [old] (swap-vals! sessions update sid
                          (fn [s] (-> s (assoc :interrupt ip) (dissoc :pending-interrupt))))]
    (when (:pending-interrupt (get old sid)) (deliver ip true))
    (let [o (wait-for! stop-latch closed ip nil ms)]
      (swap! sessions update sid dissoc :interrupt)
      o)))

(defn- slow-wait!
  "Waits out form f's :slow delay, if it has one. Returns :stopped,
  :interrupted, :elapsed, or nil when f isn't slow."
  [{:keys [opts stop-latch closed] :as ctx} f]
  (when-let [ms (slow-wait-ms opts f)]
    (if (:ignore-interrupt? opts)
      (do (wait! stop-latch closed ms) (if (live? stop-latch closed) :elapsed :stopped))
      (interruptible-wait! ctx ms))))

(defn- ns-after-form!
  "The request's current namespace after form f, marking a namespace that
  in-ns or ns names as loaded (in-ns also moves an eval session's own ns)."
  [{:keys [loaded sessions sid eval?]} f current]
  (cond
    (= 'in-ns (head f))
    (let [target (str (second (second f)))]
      (swap! loaded conj target)
      (when eval? (swap! sessions assoc-in [sid :ns] target))
      target)
    (= 'ns (head f))
    (let [target (str (second f))]
      (swap! loaded conj target)
      target)
    :else current))

(defn- answer-form!
  "Answers one ordinary form (its :prints text, then its value for eval).
  Returns {:next [current last-value]}."
  [{:keys [opts reply eval?] :as ctx} f current]
  (let [printed (some (fn [[marker text]] (when (matches? marker f) text)) (:prints opts))
        _ (when printed (reply {"out" printed}))
        new-current (ns-after-form! ctx f current)
        v (get (:answers opts) (pr-str f) (default-value new-current f))]
    (when eval? (reply {"ns" new-current "value" v}))
    {:next [new-current v]}))

(defn- plain-form!
  "Waits out f's :slow delay, then answers it unless the wait ended the
  request."
  [{:keys [reply] :as ctx} f current]
  (let [wait-outcome (slow-wait! ctx f)]
    (cond
      (= :stopped wait-outcome) :dropped
      (= :interrupted wait-outcome) (reply {"status" ["done" "interrupted"]})
      :else (answer-form! ctx f current))))

(defn- eval-step!
  "Handles the form at the head of fs. Returns {:next [current last-value]}
  to go on with the rest, anything else ends the request."
  [{:keys [opts eval?] :as ctx} fs current last-value]
  (cond
    (nil? fs) (finish-eval! ctx last-value)
    (matches? (:fail-on opts) (first fs)) (fail-form! ctx)
    (and eval? (= 'read-line (head (first fs)))) (read-line-form! ctx current last-value)
    :else (plain-form! ctx (first fs) current)))

(defn- eval-forms!
  "Answers forms one by one, starting in namespace start-ns."
  [ctx forms start-ns]
  (loop [fs (seq forms) current start-ns last-value nil]
    (let [r (eval-step! ctx fs current last-value)]
      (if-let [[c v] (when (map? r) (:next r))]
        (recur (next fs) c v)
        r))))

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
          eval? (= "eval" (get m "op"))
          text (or (get m "code") (get m "file") "")
          req-ns (get m "ns")
          sess-ns (get-in @sessions [sid :ns] "user")
          ctx {:opts opts :sessions sessions :loaded loaded :last-values last-values
               :stop-latch stop-latch :closed closed :reply reply
               :sid sid :eval? eval?}]
      (if (and eval? req-ns (not (contains? @loaded req-ns)))
        (reply {"ns" req-ns "status" ["namespace-not-found" "done" "error"]})
        (eval-forms! ctx (read-forms text) (or req-ns sess-ns))))
    (catch Throwable e
      ;; The error replies can throw too, when the client closed the
      ;; socket while a reply was in flight (its Stop detaching at once).
      ;; Nobody is listening then, so there's nothing left to answer.
      (try
        (reply {"err" (str (ex-message e) "\n")})
        (reply {"ex" fail-ex-class "root-ex" fail-ex-class "status" ["eval-error"]})
        (reply {"status" ["done"]})
        (catch Throwable _ nil)))))

(defn- describe-op!
  "describe: the advertised ops and, unless :none, the nREPL version."
  [{:keys [srv reply]}]
  (let [opts (:opts srv)
        v (:nrepl-version opts [1 7 0])
        [major minor incr] (when (vector? v) v)]
    (reply (cond-> {"ops" (zipmap (:ops opts jvm-ops) (repeat {})) "status" ["done"]}
             (vector? v)
             (assoc "versions" {"nrepl" {"major" major "minor" minor "incremental" incr
                                         "version-string" (str major "." minor "." incr)}})))))

(defn- clone-op!
  "clone: registers and answers a fresh session in ns \"user\"."
  [{:keys [srv reply]}]
  (let [sid (str "fake-session-" (swap! (:counter srv) inc))]
    (swap! (:sessions srv) assoc sid {:ns "user"})
    (reply {"new-session" sid "status" ["done"]})))

(defn- dispatch-eval-op!
  "eval and load-file: runs the request on its own future."
  [{:keys [srv stop-latch closed reply m]}]
  (let [sid (get m "session")]
    ;; a stray pending flag from an EARLIER, already-finished request
    ;; on this session must never bleed into this new one.
    (swap! (:sessions srv) update sid dissoc :pending-interrupt :pending-stdin)
    ;; inspect-start only sees a session's *1 once it's registered
    ;; this way (measured): load-file alone never registers it.
    (when (= "eval" (get m "op")) (swap! (:registered srv) conj sid))
    (swap! (:futures srv) conj (future (evaluate! srv stop-latch closed reply m)))))

(defn- interrupt-op!
  "interrupt: wakes the session's in-flight wait, or records it as pending."
  [{:keys [srv reply m]}]
  (let [sid (get m "session")
        [old] (swap-vals! (:sessions srv) update sid
                          (fn [s] (if (:interrupt s) s (assoc s :pending-interrupt true))))]
    (when-let [p (:interrupt (get old sid))] (deliver p true))
    (reply {"status" ["done"]})))

(defn- stdin-op!
  "stdin: hands the text to the session's read-line, or records it as pending."
  [{:keys [srv reply m]}]
  (let [sid (get m "session")
        text (get m "stdin")
        [old] (swap-vals! (:sessions srv) update sid
                          (fn [s] (if (:stdin s) s (assoc s :pending-stdin text))))]
    (when-let [p (:stdin (get old sid))] (deliver p text))
    (reply {"status" ["done"]})))

(defn- completions-op!
  "completions: the rows for the request's prefix, after any delay."
  [{:keys [srv stop-latch closed reply m]}]
  (let [opts (:opts srv)
        rows (get (:completions opts) (get m "prefix"))]
    (when-let [ms (or (some-> (:delays opts) deref (get "completions")) (:completions-ms opts))]
      (wait! stop-latch closed ms))
    (reply {"completions" (mapv (fn [[c n]] {"candidate" c "ns" n "type" "function"}) rows)
            "status" ["done"]})))

(defn- request-ns
  "The request's \"ns\", falling back to its session's own ns."
  [srv m]
  (or (get m "ns") (get-in @(:sessions srv) [(get m "session") :ns] "user")))

(defn- lookup-op!
  "lookup: the info for the symbol, or a lookup-error under an unloaded ns."
  [{:keys [srv stop-latch closed reply m]}]
  (let [opts (:opts srv)
        ns (request-ns srv m)]
    (when-let [ms (or (some-> (:delays opts) deref (get "lookup")) (:lookup-ms opts))]
      (wait! stop-latch closed ms))
    (if (contains? @(:loaded srv) ns)
      (reply {"info" (get (:docs opts) (get m "sym") []) "status" ["done"]})
      (reply {"message" (str "No namespace: " ns " found")
              "status" ["done" "error" "lookup-error"]}))))

(defn- info-op!
  "info: cider's info, Basilisp 0.5.1's shape (measured 2026-10-18). The
  fields sit flat in the reply, and an unknown symbol or namespace answers
  only status. Advertised only when :ops names it."
  [{:keys [srv reply m]}]
  (let [ns (request-ns srv m)]
    (reply (assoc (if (contains? @(:loaded srv) ns) (get (:docs (:opts srv)) (get m "sym") {}) {})
                  "status" ["done"]))))

(defn- doc-op!
  "doc: the symbol's doc text. The real server's own doc op calls the-ns and
  throws when ns isn't loaded, and never answers at all (measured), so
  this sends nothing for that case either."
  [{:keys [srv stop-latch closed reply m]}]
  (let [opts (:opts srv)
        ns (request-ns srv m)]
    (when (contains? @(:loaded srv) ns)
      (wait! stop-latch closed (:doc-ms opts 0))
      (reply {"doc" (get (:full-docs opts) (get m "sym") (str "No doc for " (get m "sym")))
              "status" ["done"]}))))

(defn- inspect-wait!
  "Waits out :inspect-ms, if the server has one."
  [{:keys [srv stop-latch closed]}]
  (when-let [ms (:inspect-ms (:opts srv))] (wait! stop-latch closed ms)))

(defn- inspect-start-op!
  "inspect-start: starts the view on the session's *1 (nil until registered)."
  [{:keys [srv reply m] :as req}]
  (let [sid (get m "session")
        {:keys [registered view last-values]} srv]
    (inspect-wait! req)
    (let [value (when (contains? @registered sid) (read-printed-value (get @last-values sid)))]
      (reply {"view" (pr-str (view/run-op view :start {:value value :rows (get m "rows") :cols (get m "cols")}))
              "status" ["done"]}))))

(defn- inspect-def-op!
  "inspect-def never interns (the fake never evaluates Clojure): it answers
  the current view plus :defd, naming the session's own ns (as the real
  server would intern in its *ns*)."
  [{:keys [srv reply m] :as req}]
  (let [sid (get m "session")
        ns (get-in @(:sessions srv) [sid :ns] "user")]
    (inspect-wait! req)
    (reply {"view" (pr-str (view/respond (:view srv) {:defd (str "#'" ns "/" (get m "name"))}))
            "status" ["done"]})))

(defn- inspect-view-op!
  "The inspect-in/out/page/toggle ops: wait, then answer what view-fn
  returns."
  [{:keys [reply] :as req} view-fn]
  (inspect-wait! req)
  (reply {"view" (pr-str (view-fn)) "status" ["done"]}))

(defn- inspect-op!
  "Routes an inspect-in/out/page/toggle op against the server's one view."
  [{:keys [srv m] :as req} op]
  (let [view (:view srv)]
    (inspect-view-op! req
                      (case op
                        "inspect-in" #(view/run-op view :in {:idx (get m "idx")})
                        "inspect-out" #(view/run-op view :out {})
                        "inspect-page" #(view/run-op view :page {:delta (get m "delta")})
                        "inspect-toggle-meta" #(view/run-op view :meta {:cursor (get m "cursor")})
                        "inspect-toggle-table" #(view/run-op view :table {})))))

(defn- dispatch-op!
  "Routes one live request to its op's handler."
  [{:keys [reply m] :as req}]
  (let [op (get m "op")]
    (case op
      "describe" (describe-op! req)
      "clone" (clone-op! req)
      ("eval" "load-file") (dispatch-eval-op! req)
      "interrupt" (interrupt-op! req)
      "stdin" (stdin-op! req)
      "completions" (completions-op! req)
      "lookup" (lookup-op! req)
      "info" (info-op! req)
      "doc" (doc-op! req)
      "inspect-start" (inspect-start-op! req)
      ("inspect-in" "inspect-out" "inspect-page" "inspect-toggle-meta" "inspect-toggle-table")
      (inspect-op! req op)
      "inspect-def" (inspect-def-op! req)
      ("close" "ls-sessions" "auto-require") (reply {"status" ["done"]})
      (reply {"status" ["done" "unknown-op"]}))))

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
  [{:keys [log busy] :as srv} _sock stop-latch closed reply m]
  (swap! log conj (dissoc m "id"))
  (swap! busy inc)
  (try
    (if-not (live? stop-latch closed)
      :dropped
      (dispatch-op! {:srv srv :stop-latch stop-latch :closed closed :reply reply :m m}))
    (finally (swap! busy dec))))

(defn start!
  "Starts a fake server on a free loopback port and returns it. opts:
  :ops       the ops describe advertises (default jvm-ops)
  :nrepl-version [major minor incremental] describe reports under
             \"versions\" (default [1 7 0]), or :none to leave \"versions\" out
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
  :completions-ms ms: completions answers after this delay
  :delays    an atom of {\"completions\" ms \"lookup\" ms}, read on every
             request, so a test can slow the server down and back
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
