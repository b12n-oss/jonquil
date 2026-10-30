(ns gtk-selftest-jank
  "Self-test: opens the REAL Jonquil window via
  jonquil.main/start! on a throwaway jank project and runs the rows in
  gtk-selftest-jank.rows against its ui. The jank binary is dev/fake-jank
  (JONQUIL_JANK), so the real launcher, its held-open stdin and its run dir
  are exercised against a fake jank nREPL process. Mirrors gtk-selftest-
  clj's driver (run-row/finish/-main).

  The last row fires the app's own quit action, so finish is a
  close-forced fallback only, and after-close-checks! runs once
  main/start! returns, before SUMMARY."
  (:require [clojure.core.async :as a :refer [<! go timeout]]
            [gtk-async :as ga :refer [go-row on-gtk]]
            [gtk-selftest-jank.rows :as rows]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]
            [temp-dirs]))

(def ^:private row-fns
  [rows/row-jank-starts-not-running
   rows/row-jank-run-moves-prompt
   rows/row-jank-repl-line
   rows/row-jank-selection-not-loaded
   rows/row-jank-quick-doc
   rows/row-jank-f12-in-buffer
   rows/row-jank-inspector-unavailable
   rows/row-jank-stop-restarts
   rows/row-jank-reset-restarts
   rows/row-jank-close-kills-child])

(def ^:private PER-ROW-MS
  ;; estimate: the longest row is row-jank-reset-restarts (two Runs, each
  ;; up to 15000 ms of child start plus connect on a loaded machine), plus
  ;; run-row's own idle wait (10000 ms, twice when it has to press Stop).
  55000)

(defn- run-row
  "Resets the editor and the console's input, waits (up to 10000 ms) for
  the evaluator to go idle (else presses Stop, see
  gtk-async/ready-for-row?), then runs row and parks until its channel
  delivers."
  [ui row]
  (go-row :row-reset support/check
    (on-gtk (editor/set-text! (:editor ui) "")
            (cview/clear-input! (:console ui)))
    (when (ga/ready-for-row? {:idle? #(rows/idle? ui) :stop! (:stop!* ui) :deadline-ms 10000}
                             support/check)
      (<! (row ui)))))

(defonce ^:private finished? (atom false))

(defonce ^:private window-destroyed? (atom false))

(def ^:private CLOSE-FALLBACK-DEADLINE-MS
  ;; A working quit closes within one main-loop iteration, so this only
  ;; waits out its deadline when quit is broken.
  2000)

(defn- print-summary! []
  (let [passed (count (filter second @support/results)) total (count @support/results)]
    (reset! finished? true)
    (println (str "SUMMARY " passed " of " total " passed"))))

(defn- finish
  "After the last row, which fired the real quit action. Forces the close
  through gtk-async/close-window-bounded! only when that didn't work."
  [ui]
  (go (let [t0 (System/currentTimeMillis)]
        (loop []
          (when (and (not @window-destroyed?)
                     (< (- (System/currentTimeMillis) t0) CLOSE-FALLBACK-DEADLINE-MS))
            (<! (timeout 50))
            (recur))))
      (when-not @window-destroyed?
        (support/check :jank-close-forced false
                       {:reason "window still open after the quit action; forced closed to avoid hanging the run"})
        (ga/close-window-bounded!
         (:window ui) window-destroyed? CLOSE-FALLBACK-DEADLINE-MS
         (fn []
           (support/check :jank-close-stuck false
                          {:reason "window still open after the forced close too; exiting to avoid hanging the run"})
           (print-summary!)
           (temp-dirs/delete-made!)
           (System/exit 1))))))

(defn -main [& _]
  (let [fixture (rows/fixture!)
        driver-done (a/promise-chan)]
    (main/start!
     {:file (:file fixture)
      :settings-path (support/fresh-settings-path)
      :on-ready (fn [ui]
                  (g/g-signal-connect-data (:window ui) "destroy"
                                           (cb/callable (fn [_win _data] (reset! window-destroyed? true) nil)
                                                        [:pointer :pointer] :void)
                                           ffi/null ffi/null g/CONNECT-DEFAULT)
                  (go (<! (timeout 300))
                      (<! (ga/run-rows (map (fn [row] (fn [] (run-row ui row))) row-fns)
                                       PER-ROW-MS support/check))
                      (<! (finish ui))
                      (a/put! driver-done :done)))})
    (a/alts!! [driver-done (timeout 5000)])
    (rows/after-close-checks!)
    (print-summary!))
  (when-not @finished?
    (binding [*out* *err*]
      (println "[gtk-selftest-jank] run ended before the last row; no SUMMARY, exiting 1")))
  (temp-dirs/delete-made!)
  (System/exit (if (and @finished? (seq @support/results) (every? second @support/results)) 0 1)))
