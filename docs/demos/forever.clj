;; This program never finishes on its own -- press Run, watch it count, then
;; press Stop (Esc) or the Stop button in the toolbar to end it early.
(loop [n 0]
  (println n)
  (Thread/sleep 500)
  (recur (inc n)))
