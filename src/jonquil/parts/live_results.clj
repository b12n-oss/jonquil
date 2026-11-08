(ns jonquil.parts.live-results
  "LM1's live console results as a window part's data half: the four result
  commands (their chords stay in keymap.edn). The install half is
  jonquil.gtk.parts.live-results.")

(def spec
  "The live-results part: its commands, run through the four fns the install
  half answers."
  {:id :live-results
   :commands
   {:result/inspect {:doc "Inspect the console result at the caret." :scope :window :call [:result-inspect-fn]}
    :result/copy {:doc "Copy the console result at the caret." :scope :window :call [:result-copy-fn]}
    :result/define {:doc "Define a var holding the console result at the caret." :scope :window
                    :call [:result-define-fn]}
    :result/jump {:doc "Jump to the definition of the var the console result at the caret names." :scope :window
                  :call [:result-jump-fn]}}})
