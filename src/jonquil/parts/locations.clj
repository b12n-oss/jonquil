(ns jonquil.parts.locations
  "The Locations list and Compare as a window part's data half: the panel
  command, the six list commands and the three compare commands (their chords
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
