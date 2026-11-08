(ns jonquil.parts.worlds
  "Save World and Restore World (LM7) as a window part's data half: its
  palette commands. The install half is jonquil.gtk.parts.worlds.")

(def spec
  "The worlds part's commands, run through the window context's
  :world-save-fn, :world-restore-fn and :world-restore-earlier-fn."
  {:id :worlds
   :commands
   {;; Save World and Restore World (LM7): the session's data, one world per project
    :world/save {:doc "Save World: write the session's data to this project's world, naming first what can't be saved."
                 :scope :window :call [:world-save-fn]}
    ;; the doc must not hold "save": the palette sorts by doc, and "save
    ;; world" would otherwise pick Restore World first (measured 2026-11-04)
    :world/restore {:doc "Restore World: bring back the newest world of this project, replacing the current values."
                    :scope :window :call [:world-restore-fn]}
    :world/restore-earlier {:doc "Restore an Earlier World: pick one of this project's earlier worlds to bring back."
                            :scope :window :call [:world-restore-earlier-fn]}}})
