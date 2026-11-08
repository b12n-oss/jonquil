(ns jonquil.parts.processes
  "LM4's Processes panel as a window part's data half: its two palette
  commands and the panel it contributes (the panel key stays in keymap.edn).
  The install half is jonquil.gtk.parts.processes.")

(def spec
  "The Processes part: its commands, run through the window context's
  :panel-focus-fn and :processes-show-fn, and its panel's palette label."
  {:id :processes
   :commands
   {:panel/processes {:doc "Focus the Processes panel, or open it." :scope :window
                      :call [:panel-focus-fn :processes]}
    :processes/show {:doc "Show the processes (the fibers your program spawned)." :scope :window
                     :call [:processes-show-fn]}}
   :panels [{:id :processes :label "Processes"}]})
