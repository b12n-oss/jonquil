(ns jonquil.parts.change-sets
  "LM3's change sets as a window part's data half: the commands that make
  and list sets and the panel they are reviewed in. The install half is
  jonquil.gtk.parts.change-sets.")

(def spec
  "The change sets part: its commands, run through the window context's
  :change-set-make-fn and :change-set-list-fn, and its panel's palette
  label (Switch to panel offers it; it has no command of its own, so the
  words change sets reach Change Sets alone). The docs avoid the words of
  the Run Changed, Save As and Compare commands, since the palette picks by
  words (test/jonquil/palette_test.clj pins the queries)."
  {:id :change-sets
   :commands
   {:change-set/make {:doc "Make a Change Set: bundle the work you touched in the file, to look over together."
                      :scope :window :call [:change-set-make-fn]}
    :change-set/browse {:doc "Change Sets: look over the sets you kept."
                        :scope :window :call [:change-set-list-fn]}}
   :panels [{:id :change-sets :label "Change Sets"}]})
