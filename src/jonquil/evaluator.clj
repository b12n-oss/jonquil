(ns jonquil.evaluator
  "What a window calls to run its programs, whatever runs them. The in-process
  Jolt engine (jonquil.session's InProcess record) is one implementation;
  an out-of-process runtime over nREPL is another. A window holds exactly
  one evaluator, chosen by jonquil.dialect/select.

  Callback rule, from jonquil.eval: eval!'s on-output, on-status-change and
  on-complete run on a worker thread, never the caller's, and must only
  enqueue. For an out-of-process runtime on-output may fire zero or more
  times per evaluation; for Jolt over nREPL it fires at most once per
  stream, when the evaluation finishes (jolt-lang/jolt#1153).")

(defprotocol Evaluator
  (start! [ev]
    "Brings the runtime up and blocks until it answers. nil, or a sentence
    saying why it couldn't start.")
  (stop! [ev]
    "Ends the runtime: stops a running evaluation and, for a child process,
    ends that process. Safe to call twice.")
  (eval! [ev code opts]
    "Evaluates code without blocking the caller. opts is
    jonquil.eval/eval-async's (:on-output :on-wait-input :on-status-change
    :on-complete) plus :run?, true for Run Definitions (which first resets,
    in :reset mode), and :owner: who the evaluation's new namespaces belong
    to (a tab id, or :repl). A reset-mode Run removes only its own owner's,
    the REPL's, and `user`. Defaults: :run for a Run, :repl otherwise.
    Attach evaluators ignore it. :quiet, a token, asks an evaluator that
    satisfies QuietEval for an eval that leaves the REPL as it was (no
    history entry, *1 *2 *3 *e and the prompt namespace unchanged), in the
    namespace of :ns-source's ns form when that exists. nil when started,
    else the busy warning string.")
  (interrupt! [ev]
    "Stop: asks the running evaluation to end. nil.")
  (evaluating? [ev])
  (waiting-for-input? [ev])
  (send-input! [ev text]
    "A line for a program blocked reading stdin.")
  (send-eof! [ev])
  (history [ev]
    "The REPL history atom, jonquil.eval/create-history's shape.")
  (prompt-ns [ev]
    "The namespace symbol the REPL prompt evaluates in.")
  (set-mode! [ev mode]
    ":reset or :accumulate.")
  (complete [ev prefix text pos extra-context]
    "Completion candidates, jonquil.docs/candidates' shape. Never throws.")
  (lookup [ev sym text pos]
    "A doc-info map, jonquil.docs/symbol-doc's shape, or nil. Never throws.")
  (limitations [ev]
    "Sentences naming what this runtime can't do; [] when nothing."))

(defprotocol QuietEval
  "Evaluators that honour eval!'s :quiet (the two Jolt runtimes). Callers
  check (satisfies? QuietEval ev) first."
  (quiet-eval? [ev] "true."))

(defprotocol StartNote
  "An evaluator that connects on the first Run says so once, as a plain
  note in the console next to the experimental line, not as a limit."
  (start-note [ev]
    "One sentence for the console when the window opens."))

(def ^:dynamic *help-timeout-ms*
  "When bound, how long a server evaluator's complete and lookup wait for
  the server, in place of its own :help-timeout-ms (300 ms, sized for the
  GTK thread). ask-help! binds it off the GTK thread."
  nil)

(defn help-timeout
  "*help-timeout-ms* when bound, else default."
  [default]
  (or *help-timeout-ms* default))

(defprotocol ServerHelp
  "Evaluators whose complete and lookup ask a server (every attach
  evaluator, out-of-process Jolt and jank). The window asks them through
  ask-help!, so a slow server never holds the GTK thread; in-process Jolt
  doesn't implement this and is asked directly."
  (offline-candidates [ev prefix text pos extra-context]
    "complete's candidates without asking the server: what complete falls
    back to. Never throws."))

(defn server-help?
  "True when ev's help asks a server (ServerHelp)."
  [ev]
  (satisfies? ServerHelp ev))

(def async-help-timeout-ms
  ;; estimate: 3-8x the round trips measured through an ssh tunnel to a
  ;; distant babashka (293-857 ms, 2026-10-18). Off the GTK thread, so a
  ;; long wait costs a stale request at worst, never a stalled key.
  3000)

(def help-grace-ms
  ;; estimate: a local server answered completions in 6.7 ms and lookup in
  ;; 1.2 ms (jonquil.nrepl.evaluator's help-timeout-ms probe), so a local
  ;; answer lands well inside this and the window acts on it at once.
  25)

(defn ask-help!
  "Runs (f) on a worker thread, with *help-timeout-ms* bound to
  async-help-timeout-ms, and waits up to grace-ms for it. [:now r] when it
  answered in time; else [:later], and (on-later r) runs on a worker
  thread once it does. Exactly one of the two gets r. When f throws, r is
  {::error message}, which help-error reads, so a caller can say so
  rather than show nothing."
  [grace-ms f on-later]
  ;; st: ::waiting until one side settles it. The worker stores its answer
  ;; only while the caller still waits; once the caller has given up
  ;; (::later), the worker hands the answer to on-later instead.
  (let [p (promise)
        st (atom ::waiting)]
    (future
      (let [r (try (binding [*help-timeout-ms* async-help-timeout-ms] (f))
                   (catch Throwable e {::error (or (ex-message e) (str (class e)))}))]
        (when-not (compare-and-set! st ::waiting [::done r])
          (try (on-later r) (catch Throwable _ nil)))
        (deliver p true)))
    (deref p grace-ms nil)
    (if (compare-and-set! st ::waiting ::later)
      [:later]
      [:now (second @st)])))

(defn help-error
  "The message of an ask-help! answer whose f threw, else nil."
  [r]
  (when (map? r) (::error r)))

(defprotocol Owners
  "Evaluators that track which owner (tab) created which namespaces
  (jonquil.session's InProcess, jonquil.nrepl.evaluator's NRepl). Attach evaluators never reset, so they don't
  implement it; callers check (satisfies? Owners ev) first."
  (release-owner! [ev owner]
    "Hands owner's namespaces to :repl, so the next reset-mode Run, from
    any tab, removes them. Called when a tab closes: nothing would ever
    Run as that owner again, and a reopened file (a new tab id) would
    otherwise never reset them."))

(defprotocol FullDoc
  "Full documentation beyond quick doc's own summary, for a second
  quick-doc press on the same target (jonquil.gtk.window's do-quick-doc!).
  Only jonquil.nrepl.jvm's evaluator implements this today
  (backed by the server's own \"doc\" op); every caller checks (satisfies?
  FullDoc ev) first, and an evaluator that doesn't implement it never sees
  a second-press request at all."
  (full-doc [ev sym text on-result]
    "Asks for sym's full documentation. text is the buffer's current
    text, never a namespace symbol -- jonquil.nrepl.jvm derives its own
    help-namespace from it (jonquil.clj/help-ns), so this protocol never
    leaks a JVM-specific rule into the window. on-result is called
    exactly once, from any thread (at once on the caller's for
    :not-attached and :no-op), with {:status :ok :text t},
    {:status :no-op}, {:status :timeout}, {:status :not-attached},
    {:status :not-loaded :ns ns-name} (neither the buffer's own namespace
    nor the prompt namespace resolved, so doc was never sent) or
    {:status :not-found} (the namespace answered but doesn't know sym,
    so doc was never sent)."))

(defprotocol Frames
  "The last error's frames and locals (jonquil.debugger), for the two Jolt
  runtimes. Callers check (satisfies? Frames ev) first."
  (error-frames! [ev on-data]
    "Off the caller's thread, calls on-data once with
    jonquil.debugger/frames-data's map, {:note \"No error yet.\"} or
    {:error s}.")
  (debug-mode! [ev on? on-done]
    "Turns Jolt's debug mode (code compiled from then on keeps its locals)
    on or off, off the caller's thread; on-done gets {:on? b} or {:error s}."))

(defprotocol Inspector
  "Inspecting the value an evaluator's last :ok result produced,
  through jonquil.inspect.view's lifted ops. op and args are
  jonquil.inspect.view/run-op's own (:start :in :out :page :meta :table
  :def :earlier :later; a JVM server's inspector has no history, so its
  evaluator answers :earlier and :later with {:error s}), except :start takes {:rows :cols} (the evaluator supplies the
  value), :start-local takes {:frame :local :rows :cols} (a local of the
  last error's frame i, jonquil.debugger) and :def takes {:name} (the
  evaluator supplies the target namespace). on-view is called exactly once, from any thread, with a
  view map (jonquil.inspect.view/run-op's shape) or {:error s} (plus
  :lost? true when the connection that would have answered it is gone),
  and never throws out."
  (inspect! [ev op args on-view]
    "Runs one inspector op off the caller's thread.")
  (inspectable? [ev]
    "true when this evaluator can currently answer inspect! requests."))

(defmulti make
  "A new, not yet started evaluator for a jonquil.dialect/select result,
  dispatched on its :runtime. Each implementation's namespace adds its
  method; requiring that namespace is what registers it."
  (fn [selection] (:runtime selection)))

(defmulti make-launched
  "A new evaluator that launches its own child, for a jonquil.dialect/select
  result, dispatched on (:id (:profile selection)). Each dialect's namespace
  adds its method; requiring that namespace is what registers it."
  (fn [selection] (:id (:profile selection))))

(defmethod make-launched :default [selection]
  (let [id (:id (:profile selection))]
    (throw (ex-info (str "No launched evaluator for dialect " id) {:dialect id}))))

(defmethod make :launch [selection] (make-launched selection))
