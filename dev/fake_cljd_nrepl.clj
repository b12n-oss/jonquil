(ns fake-cljd-nrepl
  "A fake ClojureDart nREPL server for jonquil.nrepl.attach's tests and the
  cljd self-test window. It imitates the ClojureDart nREPL fork's replies as they
  were measured against a running app on 2026-09-28:

  - describe advertises the fork's eight ops; clone answers :new-session.
  - A new session's first request gets the banner as :out, then
    {:ns \"cljd.user\"}, before any value.
  - eval and load-file evaluate their text form by form, in the session's
    namespace. An (ns x ...) form switches the session and answers only
    {:ns x}; any other form answers {:value v} then {:ns current}. The
    request's own :ns field is ignored, as the fork ignores it.
  - The first failing form answers {:err \"Error:  nil\\n\"}, {:ns current}
    and {:status [\"done\" \"eval-error\"]}, and nothing after it runs.
  - (in-ns ...) is refused with the fork's own sentence.
  - A form that prints (:prints) answers {:out text} before its value, as
    fork 8aaa4628 and later do.
  - lookup answers from :docs the way fork 685e56de and later do ({:info
    ...}, or no-info for an unknown symbol), and lookup-error, as the
    older fork 573781a2 did, when there's no :docs. An unknown op answers
    unknown-op.

  It never evaluates anything: a form's value comes from :answers (keyed by
  the form's pr-str), else the sum for (+ numbers...), a closure string for
  defn/defn-, \"nil\" for comment, and \":ok\" for anything else.

  Every message it receives is kept, so a test can assert which ops a
  client sent (never interrupt, never close)."
  (:require [fake-nrepl-core :as core :refer [head live? matches? read-forms
                                              wait!]]))

(def fork-ops
  ;; The describe reply's ops, measured.
  ["clone" "close" "describe" "eval" "interrupt" "load-file" "lookup" "ls-sessions"])

(def banner
  ;; The start of the fork's per-session banner, measured.
  "ClojureDart\n\nUse `(pick!)` to toggle the widget picker. The picked widget becomes `*1`.\n")

(def fail-err
  ;; What the fork answered for format.cljd's tenth form, measured.
  "Error:  nil\n")

(def in-ns-err
  ;; The fork's own refusal, measured.
  "`in-ns` is not available as a standalone REPL form in ClojureDart. Declare namespace dependencies in an `(ns ...)` form.")

(def defn-in-cljd-user-err
  ;; The fork's own refusal, measured in probe A.
  "`defn` in cljd.user is not supported — cljd.user has no source file, so top-level defs cannot be hot-reloaded. Put the form in a real namespace under src/ and save the file to let the watcher compile it. `swap!`, `reset!`, and plain expressions are fine in cljd.user.")

(defn- default-value [form]
  (cond
    ;; Jonquil's wake-up probe is (+ 1 2), so sums of numbers answer as the
    ;; app would (measured: "3").
    (and (= '+ (head form)) (every? number? (rest form))) (str (apply + (rest form)))
    (#{'defn 'defn-} (head form)) (str "Closure: () => dynamic from Function '" (second form) "': static.")
    (= 'comment (head form)) "nil"
    :else ":ok"))

(defn- session-ns
  "The session's current namespace."
  [{:keys [sessions sid]}]
  (get-in @sessions [sid :ns] "cljd.user"))

(defn- announce-fresh-session!
  "A new session's first request gets the banner, then its ns."
  [{:keys [sessions sid reply]}]
  (when (get-in @sessions [sid :fresh?])
    (swap! sessions assoc-in [sid :fresh?] false)
    (reply {"out" banner})
    (reply {"ns" "cljd.user"})))

(defn- still-asleep?
  "True while the session's first :asleep-for requests are being served."
  [{:keys [opts sessions sid]}]
  (boolean (when-let [served (get-in @sessions [sid :served])]
             (<= (swap! served inc) (:asleep-for opts 0)))))

(defn- answer-asleep!
  "Answers nil without evaluating, as a REPL that isn't awake yet does."
  [{:keys [reply] :as ctx}]
  (reply {"value" "nil"})
  (reply {"ns" (session-ns ctx)})
  (reply {"status" ["done"]}))

(defn- drop-connection!
  "Closes the connection without an answer."
  [{:keys [sock closed]}]
  (deliver closed true)
  (.close ^java.net.Socket sock)
  :dropped)

(defn- switch-ns!
  "An (ns x ...) form switches the session and answers only its ns."
  [{:keys [sessions sid reply] :as ctx} f]
  (swap! sessions assoc-in [sid :ns] (str (second f)))
  (reply {"ns" (session-ns ctx)})
  ::continue)

(defn- fail-form!
  "Answers a :fail-on form as the fork's did."
  [{:keys [reply] :as ctx}]
  (reply {"err" fail-err})
  (reply {"ns" (session-ns ctx)})
  (reply {"status" ["done" "eval-error"]}))

(defn- run-form!
  "Waits out the form's :slow delay, then answers its :prints text, value
  and ns. Returns :dropped when the server stopped meanwhile."
  [{:keys [opts stop-latch closed reply] :as ctx} f]
  (when-let [ms (some (fn [[marker ms]] (when (matches? marker f) ms)) (:slow opts))]
    (wait! stop-latch closed ms))
  (if-not (live? stop-latch closed)
    :dropped
    (do (when-let [printed (some (fn [[marker text]] (when (matches? marker f) text)) (:prints opts))]
          (reply {"out" printed}))
        (reply {"value" (get (:answers opts) (pr-str f) (default-value f))})
        (reply {"ns" (session-ns ctx)})
        ::continue)))

(defn- form-step!
  "Answers one form. Returns ::continue to go on with the next one,
  anything else ends the request."
  [{:keys [opts reply] :as ctx} f]
  (cond
    (matches? (:drop-on opts) f) (drop-connection! ctx)
    (= 'ns (head f)) (switch-ns! ctx f)
    (= 'in-ns (head f)) (reply {"err" in-ns-err "status" ["done" "eval-error"]})
    (and (#{'defn 'defn-} (head f)) (= "cljd.user" (session-ns ctx)))
    (reply {"err" defn-in-cljd-user-err "status" ["done" "eval-error"]})
    (matches? (:fail-on opts) f) (fail-form! ctx)
    :else (run-form! ctx f)))

(defn- eval-forms!
  "Answers each of forms in order, then done."
  [{:keys [reply] :as ctx} forms]
  (loop [fs (seq forms)]
    (if (nil? fs)
      (reply {"status" ["done"]})
      (let [r (form-step! ctx (first fs))]
        (if (= ::continue r)
          (recur (next fs))
          r)))))

(defn- evaluate!
  "Answers one eval or load-file request, form by form. reply sends one
  message map (string keys) with the request's id. Returns :dropped when a
  :drop-on form closed the connection, or when stop-latch or closed
  resolved while a :slow form was waiting."
  [{:keys [opts sessions]} sock stop-latch closed reply m]
  (let [ctx {:opts opts :sessions sessions :sock sock :stop-latch stop-latch
             :closed closed :reply reply :sid (get m "session")}
        text (or (get m "file") (get m "code") "")]
    (announce-fresh-session! ctx)
    (if (still-asleep? ctx)
      (answer-asleep! ctx)
      (let [forms (try (read-forms text) (catch Exception e e))]
        (if (instance? Exception forms)
          (do (reply {"err" (str (ex-message forms) "\n")}) (reply {"status" ["done" "eval-error"]}))
          (eval-forms! ctx forms))))))

(defn- handle!
  "Answers one request, incrementing srv's :busy count for its duration (a
  bounded finally, never left stuck up on a throw) so stop! can wait for
  every in-flight reply to finish before it closes anything."
  [{:keys [opts sessions log counter busy] :as srv} sock stop-latch closed reply m]
  (swap! log conj (dissoc m "id"))
  (swap! busy inc)
  (try
    (when-let [ms (get (:slow-ops opts) (get m "op"))] (wait! stop-latch closed ms))
    (if-not (live? stop-latch closed)
      :dropped
      (case (get m "op")
        "describe" (reply {"ops" (zipmap (:ops opts fork-ops) (repeat {})) "status" ["done"]})
        "clone" (let [sid (str "fake-session-" (swap! counter inc))]
                  (swap! sessions assoc sid {:ns "cljd.user" :fresh? true :served (atom 0)})
                  (reply {"new-session" sid "status" ["done"]}))
        ("eval" "load-file") (evaluate! srv sock stop-latch closed reply m)
        "lookup" (if-let [docs (:docs opts)]
                   (do (when-let [ms (:lookup-ms opts)] (wait! stop-latch closed ms))
                       (if-let [info (get docs (get m "sym"))]
                         (reply {"info" info "status" ["done"]})
                         (reply {"status" ["done" "no-info"]})))
                   (reply {"status" ["done" "lookup-error"]}))
        ("interrupt" "close" "ls-sessions") (reply {"status" ["done"]})
        (reply {"err" (str "Unknown op: " (get m "op")) "status" ["done" "unknown-op"]})))
    (finally (swap! busy dec))))

(defn start!
  "Starts a fake server on a free loopback port and returns it. opts:
  :ops      the ops describe advertises (default fork-ops)
  :answers  {form-pr-str printed-value}
  :slow     {marker ms}: a form whose pr-str holds marker answers after ms
  :fail-on  marker: that form fails as the fork's did, and the rest is skipped
  :prints   {marker text}: that form answers {:out text} before its value
  :docs     {sym info}: lookup answers info (string keys), or no-info; with
            no :docs, lookup answers lookup-error
  :lookup-ms ms: lookup (with :docs) answers after ms
  :drop-on  marker: that form closes the connection without an answer
  :asleep-for n: a new session's first n eval or load-file requests answer
            nil without evaluating, as a REPL that isn't awake yet does
  :mute?    read requests but never answer any
  :slow-ops {op-name ms}: that op (describe, clone, ...) answers after ms,
            to test code that waits on describe or clone (a Stop pressed
            while connect! is still waiting)"
  ([] (start! {}))
  ([opts] (core/start-server! opts handle! {})))

(def ops-seen "Every op the server has received, in order." core/ops-seen)

(def requests "Every request the server has received (string keys, no :id), in order." core/requests)

(def drop-connections!
  "Closes every client socket from the server's side (fake-nrepl-core)."
  core/drop-connections!)

(def stop!
  "Stops the server and waits for its connection threads (fake-nrepl-core)."
  core/stop!)
