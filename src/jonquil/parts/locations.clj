(ns jonquil.parts.locations
  "The Locations list and Compare as a window part's data half: the panel
  command, the six list commands and the seven compare commands (their chords
  stay in keymap.edn) and the panel it contributes. The install half is
  jonquil.gtk.parts.locations.")

(def spec
  "The Locations part: its commands, run through the window context's
  :panel-focus-fn and the fns the install half answers, and its panel's
  palette label."
  {:id :locations
   :commands
   {:panel/locations {:doc "Focus the Locations panel, or open it." :scope :window
                      :call [:panel-focus-fn :locations]}
    :compare/disk {:doc "Compare with the file on disk: list the differences." :scope :window
                   :call [:compare-disk-fn]}
    :compare/head {:doc "Compare with git HEAD: list the differences." :scope :window
                   :call [:compare-head-fn]}
    :compare/last-run {:doc "Compare with the last Run: list the differences from what the tab's last whole Run sent."
                       :scope :window :call [:compare-last-run-fn]}
    ;; "This form: ..." sorts after "Compare with ...", so typing "compare
    ;; with git head" still picks the whole tab first
    :compare-form/disk {:doc "This form: compare with the file on disk, listing the differences in the top-level form at the caret only."
                        :scope :window :call [:compare-form-disk-fn]}
    :compare-form/head {:doc "This form: compare with git HEAD, listing the differences in the top-level form at the caret only."
                        :scope :window :call [:compare-form-head-fn]}
    :compare-form/last-run {:doc "This form: compare with the last Run, listing the differences in the top-level form at the caret only."
                            :scope :window :call [:compare-form-last-run-fn]}
    :compare/take {:doc "Take the other version here: the compared file's lines for the difference at the caret."
                   :scope :window :call [:compare-take-fn]}
    ;; the locations list: three ways to fill it, F8 and Shift+F8 to step
    :locations/uses {:doc "List the uses of the symbol at the caret." :scope :window
                     :call [:locations-uses-fn]}
    :locations/definitions {:doc "List the definitions of the symbol at the caret." :scope :window
                            :call [:locations-definitions-fn]}
    :locations/matches {:doc "List every match of the find query, or of the selection." :scope :window
                        :call [:locations-matches-fn]}
    :locations/usages-in-project {:doc "Find Usages in Project: list every use of the symbol at the caret in the project's files."
                                  :scope :window :call [:locations-project-usages-fn]}
    :locations/next {:doc "Go to the next place in the list." :scope :window
                     :call [:locations-step-fn 1]}
    :locations/previous {:doc "Go to the previous place in the list." :scope :window
                         :call [:locations-step-fn -1]}}
   :panels [{:id :locations :label "Locations"}]})
