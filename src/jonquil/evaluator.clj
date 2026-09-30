(ns jonquil.evaluator
  "The seam between a window and whatever runs its programs. The in-process
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
    in :reset mode). nil when started, else the busy warning string.")
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

(defprotocol FullDoc
  "P3.T2: full documentation beyond quick doc's own summary, for a second
  quick-doc press on the same target (jonquil.gtk.window's do-quick-doc!,
  ruling 11). Only jonquil.nrepl.jvm's evaluator implements this today
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

(defprotocol Inspector
  "Inspecting the value an evaluator's last :ok result produced,
  through jonquil.inspect.view's lifted ops. op and args are
  jonquil.inspect.view/run-op's own (:start :in :out :page :meta :table
  :def), except :start takes {:rows :cols} (the evaluator supplies the
  value) and :def takes {:name} (the evaluator supplies the target
  namespace). on-view is called exactly once, from any thread, with a
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
