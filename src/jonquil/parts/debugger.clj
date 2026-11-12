(ns jonquil.parts.debugger
  "The debugger as a window part's data half: the Frames panel command, the
  line-count, debug-mode, Break and restart commands (their chords stay in
  keymap.edn) and the panel it contributes. The install half is
  jonquil.gtk.parts.debugger.")

(def spec
  "The debugger part: its commands, run through the window context's
  :panel-focus-fn and the fns the install half answers, and its panel's
  palette label."
  {:id :debugger
   :commands
   {:panel/frames {:doc "Focus the Frames panel, or open it." :scope :window
                   :call [:panel-focus-fn :debugger]}
    :debug/show-frames {:doc "Show the last error's frames and locals." :scope :window
                        :call [:show-frames-fn]}
    :debug/locals-mode {:doc "Keep every local for the debugger (debug mode, then reload your code)."
                        :scope :window :call [:locals-mode-fn]}
    :run/line-counts {:doc "Count how often each line runs (Jolt): turn it on, then Run your saved file."
                      :scope :window :call [:line-counts-fn]}
    :run/clear-line-counts {:doc "Clear line counts: zero Jolt's counters and hide them."
                            :scope :window :call [:clear-line-counts-fn]}
    :debug/break-on-error {:doc "Break on error: pause an evaluation where it throws, before anything unwinds."
                           :scope :window :call [:break-fn]}
    :debug/continue {:doc "Continue from the break: let the error unwind." :scope :window
                     :call [:continue-fn]}
    :debug/step {:doc "Step to the next line: run the paused breakpoint on to the next line it reaches (Jolt)."
                 :scope :window :call [:step-fn]}
    :debug/step-over {:doc "Step over the next line: run any call on it through, pausing in this function or a caller (Jolt)."
                      :scope :window :call [:step-over-fn]}
    :debug/step-out {:doc "Step out of this function: run on until it returns, pausing in its caller (Jolt)."
                     :scope :window :call [:step-out-fn]}
    :restart/retry {:doc "Retry the last failed evaluation." :scope :window :call [:retry-fn]}
    :restart/edit {:doc "Edit the last failed evaluation at the prompt." :scope :window
                   :call [:edit-retry-fn]}
    :restart/define {:doc "Define the name the last error couldn't resolve, then retry." :scope :window
                     :call [:define-retry-fn]}}
   :panels [{:id :debugger :label "Frames"}]
   ;; VS Code's F10 Step Over, F11 Step Into, Shift+F11 Step Out, with F7
   ;; for F11: macOS's Show Desktop takes F11 (keycode 103), while F7
   ;; (keycode 98) and F10 (109) have no system shortcut and nothing in
   ;; keymap.edn; Shift+F10 is the context menu. Only while a breakpoint's
   ;; pause shows, else the key passes through
   :bindings {:window [{:key "F10" :exact #{} :when [:breakpoint-paused] :command :debug/step-over}
                       {:key "F7" :exact #{} :when [:breakpoint-paused] :command :debug/step}
                       {:key "F7" :exact #{:shift} :when [:breakpoint-paused] :command :debug/step-out}]}})
