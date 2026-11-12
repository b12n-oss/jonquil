(ns jonquil.parts.breakpoints
  "LM5's breakpoints as a window part's data half: Toggle Breakpoint (F9)
  and Clear All Breakpoints. The install half is
  jonquil.gtk.parts.breakpoints; the pause itself shows in the Frames
  panel (jonquil.gtk.parts.debugger).")

(def spec
  "The breakpoints part: its two commands, run through the fns the install
  half answers, and F9."
  {:id :breakpoints
   :commands
   {:debug/toggle-breakpoint {:doc "Toggle a breakpoint on this line: pause here when the code runs (Jolt)."
                              :scope :window :call [:toggle-breakpoint-fn]}
    :debug/clear-breakpoints {:doc "Clear all breakpoints in this tab." :scope :window
                              :call [:clear-breakpoints-fn]}}
   :bindings {:window [{:key "F9" :exact #{} :command :debug/toggle-breakpoint}]}})
