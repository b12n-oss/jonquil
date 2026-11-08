(ns jonquil.parts.replay
  "The last session's replay (LM7) as a window part's data half: its palette
  commands. The install half is jonquil.gtk.parts.replay.")

(def spec
  "The replay part's commands, run through the window context's
  :replay-open-fn and :replay-forget-fn."
  {:id :replay
   :commands
   {;; The last session's replay (LM7). "replay" and "forget last" pick their command
    ;; first in the palette (palette_test). The Forget doc and id must not hold "replay", and
    ;; bare "forget" lists Forget every saved layout first (sorted by doc).
    :session/open-replay {:doc "Open last session's replay: review what this project ran last time, in a new tab."
                          :scope :window :call [:replay-open-fn]}
    :session/forget-last {:doc "Forget last session: drop what was recorded of it."
                          :scope :window :call [:replay-forget-fn]}}})
