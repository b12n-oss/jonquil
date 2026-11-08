(ns jonquil.parts.time-form
  "Time Form (time the form at the caret) as a window part's data half: its
  palette command. The install half is jonquil.gtk.parts.time-form.")

(def spec
  "The time-form part's command, run through the window context's
  :time-form-fn."
  {:id :time-form
   :commands
   {:run/time-form {:doc "Time the form at the caret (about a second)." :scope :window
                    :call [:time-form-fn]}}})
