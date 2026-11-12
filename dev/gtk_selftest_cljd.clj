(ns gtk-selftest-cljd
  "Self-test: opens the REAL Jonquil window via
  jonquil.main/start! on a throwaway ClojureDart app directory whose
  NREPL.lock names a fake fork server in this process, and runs the rows
  in gtk-selftest-cljd.rows against its ui. Mirrors gtk-selftest-nrepl's
  driver (run-row/finish/-main), minus the middleware check: the fake
  server needs nothing outside the repo.

  The last row fires the app's own quit action, so finish is a
  close-forced fallback only, and after-close-checks! runs once
  main/start! returns, before SUMMARY."
  (:require [selftest-keys]
            [clojure.core.async :as a :refer [<! go timeout]]
            [gtk-async :as ga :refer [go-row on-gtk]]
            [gtk-selftest-cljd.rows :as rows]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]
            [temp-dirs]))

(def ^:private row-fns
  [rows/row-cljd-starts-not-running
   rows/row-cljd-load-prints-loaded
   rows/row-cljd-run-selection-in-buffer-ns
   rows/row-cljd-run-form-in-comment
   rows/row-cljd-quick-doc-from-app
   rows/row-cljd-completion-offline
   rows/row-cljd-stop-detaches
   rows/row-cljd-quick-doc-detached
   rows/row-cljd-reconnects-after-detach
   rows/row-cljd-idle-stop-detaches
   rows/row-cljd-not-running-message
   rows/row-cljd-inspect-unavailable
   rows/row-cljd-two-tabs-run
   rows/row-cljd-close])

(def ^:private PER-ROW-MS
  ;; estimate: the longest failing path is row-cljd-reconnects-after-detach's
  ;; 10000 ms wait plus its 2000 ms label wait, plus run-row's idle wait
  ;; (10000 ms, twice when it has to press Stop).
  40000)

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
  ;; As gtk-selftest-nrepl's: a working quit closes within one main-loop
  ;; iteration, so this only waits out its deadline when quit is broken.
  2000)

(defn- print-summary! []
  (let [passed (count (filter second @support/results)) total (count @support/results)]
    (reset! finished? true)
    (selftest-keys/print-warning!)
    (println (str "SUMMARY " passed " of " total " passed"))))

(defn- finish
  "After the last row, which fired the real quit action. Forces the close
  through gtk-async/close-window-bounded! only when that didn't work, as
  gtk-selftest-nrepl's finish does."
  [ui]
  (go (let [t0 (System/currentTimeMillis)]
        (loop []
          (when (and (not @window-destroyed?)
                     (< (- (System/currentTimeMillis) t0) CLOSE-FALLBACK-DEADLINE-MS))
            (<! (timeout 50))
            (recur))))
      (when-not @window-destroyed?
        (support/check :cljd-close-forced false
                       {:reason "window still open after the quit action; forced closed to avoid hanging the run"})
        (ga/close-window-bounded!
         (:window ui) window-destroyed? CLOSE-FALLBACK-DEADLINE-MS
         (fn []
           (support/check :cljd-close-stuck false
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
                  (selftest-keys/watch! (:window ui))
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
      (println "[gtk-selftest-cljd] run ended before the last row; no SUMMARY, exiting 1")))
  (temp-dirs/delete-made!)
  (System/exit (if (and @finished? (seq @support/results) (every? second @support/results)) 0 1)))
