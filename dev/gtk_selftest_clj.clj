(ns gtk-selftest-clj
  "clj plan P2.T4 self-test: opens the REAL Jonquil window via
  jonquil.main/start! on a throwaway JVM project directory whose
  .nrepl-port names a fake JVM nREPL server in this process, and runs the
  rows in gtk-selftest-clj.rows against its ui. Mirrors gtk-selftest-
  cljd's driver (run-row/finish/-main): the fake server needs nothing
  outside the repo.

  The last row fires the app's own quit action, so finish is a
  close-forced fallback only, and after-close-checks! runs once
  main/start! returns, before SUMMARY."
  (:require [clojure.core.async :as a :refer [<! go timeout]]
            [gtk-async :as ga :refer [await-until go-row on-gtk]]
            [gtk-selftest-clj.rows :as rows]
            [gtk-selftest-clj.source :as source]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]
            [temp-dirs]))

(def ^:private row-fns
  [rows/row-clj-starts-not-running
   rows/row-clj-inspect-before-run
   rows/row-clj-run-moves-prompt
   rows/row-clj-selection-not-loaded
   rows/row-clj-stop-interrupts
   rows/row-clj-stdin
   rows/row-clj-reconnects-after-restart
   rows/row-clj-reconnect-resets-prompt
   rows/row-clj-quick-doc
   rows/row-clj-full-doc
   rows/row-clj-full-doc-escape
   rows/row-clj-inspect-after-run
   rows/row-clj-inspect-in-out
   rows/row-clj-inspect-lost-connection
   rows/row-clj-inspect-lost-idle
   source/row-clj-source-jar
   source/row-clj-source-own-file
   source/row-clj-source-jump-to-own-file
   source/row-clj-source-follow-and-back
   source/row-clj-source-open-in-window
   source/row-clj-source-outside-read-only
   source/row-clj-source-escape
   source/row-clj-source-missing-entry
   source/row-clj-source-line-moved
   source/row-clj-source-read-only
   source/row-clj-source-shares-slot
   rows/row-clj-reset-tick-round-trip
   rows/row-clj-two-tabs-run
   rows/row-clj-close])

(def ^:private PER-ROW-MS
  ;; estimate: the longest failing path is row-clj-reconnects-after-
  ;; restart's 5000 ms run-and-await plus two 2000 ms label waits, plus
  ;; run-row's own 10000 ms idle wait -- the same basis gtk-selftest-
  ;; cljd's own PER-ROW-MS uses.
  30000)

(defn- run-row
  "Resets the editor and the console's input, waits (up to 10000 ms) for
  the evaluator to go idle, then runs row and parks until its channel
  delivers."
  [ui row]
  (go-row :row-reset support/check
    (on-gtk (editor/set-text! (:editor ui) "")
            (cview/clear-input! (:console ui)))
    (await-until 10000 20 #(rows/idle? ui))
    (<! (row ui))))

(defonce ^:private finished? (atom false))

(defonce ^:private window-destroyed? (atom false))

(def ^:private CLOSE-FALLBACK-DEADLINE-MS
  ;; As gtk-selftest-nrepl's/gtk-selftest-cljd's: a working quit closes
  ;; within one main-loop iteration, so this only waits out its deadline
  ;; when quit is broken.
  2000)

(defn- print-summary! []
  (let [passed (count (filter second @support/results)) total (count @support/results)]
    (reset! finished? true)
    (println (str "SUMMARY " passed " of " total " passed"))))

(defn- finish
  "After the last row, which fired the real quit action. Forces the close
  through gtk-async/close-window-bounded! only when that didn't work, as
  gtk-selftest-cljd's finish does."
  [ui]
  (go (let [t0 (System/currentTimeMillis)]
        (loop []
          (when (and (not @window-destroyed?)
                     (< (- (System/currentTimeMillis) t0) CLOSE-FALLBACK-DEADLINE-MS))
            (<! (timeout 50))
            (recur))))
      (when-not @window-destroyed?
        (support/check :clj-close-forced false
                       {:reason "window still open after the quit action; forced closed to avoid hanging the run"})
        (ga/close-window-bounded!
         (:window ui) window-destroyed? CLOSE-FALLBACK-DEADLINE-MS
         (fn []
           (support/check :clj-close-stuck false
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
      (println "[gtk-selftest-clj] run ended before the last row; no SUMMARY, exiting 1")))
  (temp-dirs/delete-made!)
  (System/exit (if (and @finished? (seq @support/results) (every? second @support/results)) 0 1)))
