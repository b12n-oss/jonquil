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
    :reset? false makes a Run skip that reset and add to the session. The
    in-process session and the Jolt child honour it; JVM attach never
    resets, so it changes nothing there; other evaluators may still reset. :quiet, a token, asks an evaluator that
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
  (complete [ev req]
    "Completion candidates, jonquil.docs/candidates' shape. req is
    {:prefix :text :pos :extra-context}. Never throws.")
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
  (offline-candidates [ev req]
    "complete's candidates without asking the server: what complete falls
    back to. req is complete's {:prefix :text :pos :extra-context}. Never
    throws."))

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
  quick-doc press on the same target (jonquil.gtk.window.help's do-quick-doc!).
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
  (toggle-debug-mode! [ev on-done]
    "Flips Jolt's debug mode (code compiled from then on keeps its locals),
    reading the runtime's own mode, off the caller's thread; on-done gets
    {:on? b}, the mode it's in now, or {:error s}.")
  (eval-in-frame! [ev req on-result]
    "req is {:frame-i :code :error-id}. Evaluates code with the last
    error's frame frame-i's locals bound
    (jonquil.debugger/eval-in-frame), off the caller's thread; on-result gets
    {:out :text} or {:error s}, jonquil.debugger/STALE when error-id names
    an older error."))

(defprotocol LineCounts
  "LM4's exact line counts (jonquil.line-counts over jolt.profile), for the
  two Jolt runtimes. Callers check (satisfies? LineCounts ev) first. Each
  call runs off the caller's thread and calls back once."
  (toggle-line-counts! [ev on-done]
    "Flips counting for code compiled from now on, reading the runtime's
    own mode; on-done gets {:on? b} (with :was-debug? true when it ended
    debug mode) or {:error s}.")
  (line-counts! [ev path on-data]
    "on-data gets {:counts {line hits}} for the file at path, or {:error s}.")
  (clear-line-counts! [ev on-done]
    "Zeroes the counters; on-done gets {:ok true} or {:error s}."))

(defprotocol Break
  "Break on error (jonquil.debugger's break): an evaluation that throws
  pauses on its worker before anything unwinds. Jolt in process. Callers
  check (satisfies? Break ev) first."
  (set-break-handlers! [ev handlers]
    "{:on-pause (fn [data]) :on-resume (fn [{:keys [continued?]}])
    :on-eval-start (fn [id]) :on-eval-end (fn [id])}: on-pause and
    on-resume are called on the paused worker, data being
    jonquil.debugger/frames-data's map, and continued? is false when a Stop
    ended the pause. For each evaluation that isn't quiet, Break on error on
    or off, on-eval-start is called before it runs and on-eval-end after its
    on-complete, both with the same id, an object of its own; their throws
    are dropped. The evaluator goes idle before on-complete, so the next
    evaluation can start before on-eval-end.")
  (toggle-break! [ev on-done]
    "Turns Break on error on or off, off the caller's thread; on-done gets
    {:on? b} or {:error s}.")
  (break-eval! [ev frame-i code on-result]
    "Evaluates code in the paused error's frame frame-i, on the paused
    thread; on-result gets jonquil.debugger/eval-in-frame's map, or
    {:error \"Not paused any more.\"}.")
  (continue-break! [ev]
    "Lets the paused error unwind: true when one was paused."))

(defprotocol Breakpoints
  "Breakpoints (LM5, jonquil.breakpoints): the runtime side, for the two
  Jolt runtimes. The window sends breakpoint-instrumented code; this arms
  the runtime's hook (jonquil.debugger/breakpoint-hook) so a call that
  reaches a breakpoint pauses through the Break handlers. Callers check
  (satisfies? Breakpoints ev) first."
  (set-breakpoints! [ev on? on-done]
    "Arms (on? true) or disarms the hook, off the caller's thread; arming
    turns debug mode on when it's off. on-done gets {:on? b}, with
    :debug-turned-on? true when it did, or {:error s}, and then the hook
    stays as it was.")
  (breakpoints-on? [ev]
    "True while the runtime's hook is armed, as the last
    set-breakpoints! answered: only then does a Run send breakpoint code.")
  (macro-heads! [ev ns-name syms on-done]
    "What each symbol text in syms names in namespace ns-name, off the
    caller's thread: on-done gets {:heads m :ns-found? b}
    (jonquil.debugger/macro-heads) or {:error s}.")
  (step-break! [ev]
    "Step (stepping): lets the paused breakpoint run on with stepping on,
    so the next step point or breakpoint it reaches pauses. True when a
    pause was waiting. Continue, Stop and the evaluation's end turn
    stepping off."))

(defprotocol Trace
  "Tracing functions where the code runs (jonquil.trace-rt): the two Jolt
  runtimes. Callers check (satisfies? Trace ev) first."
  (trace-op! [ev op args on-result]
    "Runs a trace op off the caller's thread: :trace and :untrace take
    {:sym :ns} (the name and the namespace to resolve it in), :untrace-all
    and :clear take {}, :log takes {:since id}. on-result gets
    jonquil.trace-rt's answer, or {:error s}."))

(defprotocol Fibers
  "LM4's Processes panel where the code runs (jonquil.fibers-rt): the two
  Jolt runtimes. Callers check (satisfies? Fibers ev) first."
  (fibers-op! [ev op args on-result]
    "Runs a fibers op off the caller's thread: :watch, :list, :forget take
    {}, :interrupt and :kill take {:id n}. on-result gets
    jonquil.fibers-rt/op's answer, or {:error s}."))

(defprotocol World
  "Save World and Restore World where the code runs (jonquil.world-rt):
  the two Jolt runtimes. Callers check (satisfies? World ev) first."
  (world-op! [ev op args on-result]
    "Runs a world op off the caller's thread: :save and :restore take
    {:path}, the world file. on-result gets jonquil.world-rt/op's answer,
    or {:error s}."))

(defprotocol Inspector
  "Inspecting the value an evaluator's last :ok result produced,
  through jonquil.inspect.view's lifted ops. op and args are
  jonquil.inspect.view/run-op's own (:start :in :out :page :meta :table
  :def :earlier :later; a JVM server's inspector has no history, so its
  evaluator answers :earlier and :later with {:error s}), except :start takes {:rows :cols} (the evaluator supplies the
  value), :start-result takes {:id :rows :cols} (a console result's value,
  jonquil.results-rt), :start-trace takes {:id :part :rows :cols} (a traced call's
  :ret or :args, jonquil.trace-rt), :start-local takes {:frame :local :rows :cols :error-id} (a
  local of the last error's frame i, jonquil.debugger; a stale :error-id
  answers {:error jonquil.debugger/STALE}); the three :start-* take an
  optional :label, the view's name for the value in place of *1; and :def takes {:name} (the
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
