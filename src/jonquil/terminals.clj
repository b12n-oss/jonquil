(ns jonquil.terminals
  "The terminals in the window's bottom area, as plain data: each has a
  number, shown on its tab. The widgets are jonquil.gtk.parts.terminal's.")

(defn label
  "The tab's label for terminal number n."
  [n]
  (str "Terminal " n))

(defn next-number
  "The number for a new terminal beside the ones numbered numbers: one more
  than the highest, and 1 when there is none, so closing every terminal
  starts the count again."
  [numbers]
  (inc (reduce max 0 numbers)))

(defn target
  "The number of the terminal Show the terminal goes to, or nil when there
  is none and a new one is wanted. numbers are the open terminals in tab
  order, showing the one whose tab is showing (nil on Interactions), and
  last the one used last."
  [{:keys [numbers showing last]}]
  (let [open (set numbers)]
    (cond
      (open showing) showing
      (open last) last
      :else (first numbers))))

(defn after-close
  "The number of the terminal to show once terminal n has closed, or nil
  when it was the only one: the tab after it, or the one before it when it
  was the last tab."
  [numbers n]
  (let [v (vec numbers)
        i (.indexOf v n)]
    (when (<= 0 i)
      (or (get v (inc i)) (get v (dec i))))))

(defn close-alert
  "The alert for closing terminal n's tab while a program is still running
  in it. Cancel is the default, since the other answer ends the program."
  [n]
  {:message (str "A program is still running in " (label n) ".")
   :detail "Closing the tab ends it."
   :buttons ["Close Anyway" "Cancel"]})
