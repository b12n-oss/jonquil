(ns jonquil.parts.live-results
  "LM1's live console results as a window part's data half: the six result
  commands (their chords stay in keymap.edn). The install half is
  jonquil.gtk.parts.live-results.")

(def spec
  "The live-results part: its commands, run through the five fns the install
  half answers."
  {:id :live-results
   :commands
   {:result/inspect {:doc "Inspect the console result at the caret." :scope :window :call [:result-inspect-fn]}
    :result/copy {:doc "Copy the console result at the caret." :scope :window :call [:result-copy-fn]}
    :result/define {:doc "Define a var holding the console result at the caret." :scope :window
                    :call [:result-define-fn]}
    :result/jump {:doc "Jump to the definition of the var the console result at the caret names." :scope :window
                  :call [:result-jump-fn]}
    :result/doc {:doc "Show the quick doc of the var the console result at the caret names, in the console."
                 :scope :window :call [:result-doc-fn]}
    :result/open-location {:doc "Open the file and line the console text at the caret names, such as src/app/core.clj:12."
                           :scope :window :call [:result-open-location-fn]}}})
