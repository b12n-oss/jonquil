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

(defmulti make
  "A new, not yet started evaluator for a jonquil.dialect/select result,
  dispatched on its :runtime. Each implementation's namespace adds its
  method; requiring that namespace is what registers it."
  (fn [selection] (:runtime selection)))
