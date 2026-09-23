;; Reset on Run walkthrough -- try this with the "Reset on Run" checkbox in
;; the toolbar, next to the Run button:
;;
;; 1. Reset on Run starts checked. Press Run: this defines `greet` and
;;    calls it once.
;; 2. Delete the (defn greet ...) line below (keep the (greet) call) and
;;    press Run again. It fails -- Reset on Run cleared what the previous
;;    Run defined before this one started, and this file no longer
;;    defines `greet` itself.
;; 3. Put the (defn greet ...) line back, uncheck Reset on Run, then press
;;    Run once more, then delete the defn again and Run a third time. This
;;    time (greet) still works, because unchecking the box keeps what
;;    earlier Runs defined instead of clearing it first.
(defn greet [] (println "Hi from greet!"))
(greet)
