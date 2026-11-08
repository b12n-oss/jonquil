(ns jonquil.parts.trace
  "LM4's Trace panel as a window part's data half: the panel command, the four
  trace commands and the panel it contributes (the chords stay in keymap.edn).
  The install half is jonquil.gtk.parts.trace.")

(def spec
  "The Trace part: its commands, run through the window context's
  :panel-focus-fn and the trace fns the part answers, and its panel's palette
  label."
  {:id :trace
   :commands
   {:panel/trace {:doc "Focus the Trace panel, or open it." :scope :window
                  :call [:panel-focus-fn :trace]}
    :trace/toggle {:doc "Trace the function at the caret, or stop tracing it." :scope :window
                   :call [:trace-toggle-fn]}
    :trace/show {:doc "Show the calls of the traced functions." :scope :window :call [:trace-show-fn]}
    :trace/clear {:doc "Clear the recorded calls." :scope :window :call [:trace-clear-fn]}
    :trace/untrace-all {:doc "Stop tracing every function." :scope :window :call [:trace-untrace-all-fn]}}
   :panels [{:id :trace :label "Trace"}]})
