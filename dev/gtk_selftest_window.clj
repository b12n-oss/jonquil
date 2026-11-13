(ns gtk-selftest-window
  "Self-test: opens the REAL Jonquil window via jonquil.main/start!
  and runs the front-end self-test rows against its ui. Every wait polls a
  condition against a deadline, never a fixed sleep. Rows call ui's exposed
  functions (run!*, stop!*, open-path!, save-to!) directly, or drive the
  console's own submit path (handle-return! over an interactive insert),
  exactly as the buttons/keys and the user's typing do; no keystroke is
  ever synthesized.

  The driver is a go block (dev/gtk_async.clj): run-row resets the editor
  and console, waits for the engine to go idle, then runs one row and parks
  until the row's channel delivers, so rows run strictly in order. A row
  that doesn't finish within PER-ROW-MS is recorded as a :row-timeout FAIL
  and the run goes on.

  The rows themselves live in the gtk-selftest-window.* namespaces under
  dev/gtk_selftest_window/, one per feature area. The ordered list every row
  runs in is cut into five consecutive parts, gtk-selftest-window.order-a to
  order-e, each requiring the row namespaces it lists. This file joins the
  parts into row-fns (the last row really closes the window, so order
  matters), and keeps the driver and -main."
  ;; probe-part stays ahead of every require that loads jonquil.parts,
  ;; jonquil.keymap, jonquil.palette or jonquil.command-registry, which the
  ;; order-* parts do through their rows: those namespaces build their tables
  ;; as they load, and the probe's spec has to be in them. Sorting this list
  ;; by name puts order-a first and the :probe-part row then fails.
  (:require [selftest-keys]
            [clojure.core.async :as a :refer [<! go timeout]]
            [gtk-selftest-window.probe-part]
            [gtk-async :as ga :refer [go-row on-gtk]]
            [gtk-selftest-window.order-a :as order-a]
            [gtk-selftest-window.order-b :as order-b]
            [gtk-selftest-window.order-c :as order-c]
            [gtk-selftest-window.order-d :as order-d]
            [gtk-selftest-window.order-e :as order-e]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]
            [temp-dirs]))

(def ^:private row-fns
  (vec (concat order-a/rows
               order-b/rows
               order-c/rows
               order-d/rows
               order-e/rows)))

;; --- driver --------------------------------------------------------------

(def ^:private PER-ROW-MS
  ;; A row that FAILs waits out every deadline on its path. The longest path
  ;; is run-repl/row-reset-vs-accumulate's four 3000 ms waits (12000 ms),
  ;; plus run-row's idle wait (2000 ms, twice when it has to press Stop);
  ;; the whole run takes about 5 s.
  30000)

(defn- run-row
  "Resets the editor and the console's input, waits (up to 2000 ms) for the
  engine to go idle (else presses Stop, see
  gtk-async/ready-for-row?), then runs row and parks until its channel delivers."
  [ui row]
  (go-row :row-reset support/check
    ;; an alert the last row answered in code is still a dialog on screen:
    ;; dismiss it, and wait for GTK to complete it, so its late completion
    ;; can't clear the next row's alert-pending? guard
    (on-gtk ((:dismiss-alerts!* ui)))
    (ga/await-until 3000 20 #(zero? ((:pending-alerts* ui))))
    (on-gtk (editor/set-text! (:editor ui) "")
            (cview/clear-input! (:console ui))
            ;; recording a demo: keep the window filling the screen, whatever
            ;; the last row did to its size (a no-op when already maximized)
            (when (System/getenv "JONQUIL_SELFTEST_MAXIMIZE") (g/gtk-window-maximize (:window ui)))
            ;; theme rows end on whatever they last checked; every row
            ;; starts on the run's base theme
            (when (not= support/BASE-THEME ((:theme-name* ui)))
              ((:set-theme!* ui) support/BASE-THEME)))
    ;; support/idle? follows the window's live evaluator (the tabs rows
    ;; switch the window to ClojureDart and back, which leaves a new
    ;; in-process evaluator, and Stop acts on the live one), and waits for
    ;; the last run's on-complete and the console's drain too, so a run's
    ;; result line never lands inside the next row.
    (when (ga/ready-for-row? {:idle? #(support/idle? ui)
                              :stop! (:stop!* ui) :deadline-ms 2000}
                             support/check)
      (<! (row ui)))))

;; row-command-chord-close is the only way out of the run, so a REGRESSION in
;; the close chord (on-window-key stops matching KEY-W, close-fn stops
;; being wired, etc.) would leave the g_application main loop running
;; forever with nothing to stop it -- hanging `jolt gtk:selftest` instead of
;; failing it. window-destroyed? is set by a "destroy" signal this file
;; connects on the window itself (-main, below), independent of
;; jonquil.gtk.window.quit's close-request wiring: a plain Clojure atom
;; flip, safe to read from the driver's go block without ever touching the
;; (possibly already-destroyed) widget pointer directly.
(defonce ^:private window-destroyed? (atom false))

;; Set only where SUMMARY prints, i.e. when the driver
;; reached the end of row-fns. An early g_application_quit (a row that
;; really closes or quits) ends g_application_run with the remaining rows
;; never run and no SUMMARY, and every result recorded so far can still be
;; a PASS and -main would exit 0, while `jolt gtk:selftest` reads only
;; exit codes.
(defonce ^:private finished? (atom false))

(def ^:private WINDOW-CLOSE-FALLBACK-DEADLINE-MS
  ;; Generous: a working chord closes within one main-loop iteration
  ;; (microseconds), so this only ever actually waits out its deadline
  ;; when the chord is broken. finish (below) uses this same constant
  ;; twice: once to wait for the chord's own close to land, and again
  ;; as close-window-bounded!'s own deadline for the forced close
  ;; that follows when the chord didn't work.
  2000)

(defn- print-summary!
  "SUMMARY, printed exactly once, from wherever the run actually ends."
  []
  (let [passed (count (filter second @support/results)) total (count @support/results)]
    (reset! finished? true)
    (selftest-keys/print-warning!)
    (println (str "SUMMARY " passed " of " total " passed"))))

(defn- finish
  "After the last row. row-command-chord-close already tried the product's
  own close chord; this reads window-destroyed? rather than assuming it
  worked. If the chord regressed, the window is still open, and forcing
  gtk_window_close (via gtk-async/close-window-bounded!) keeps the process
  from hanging.

  A suppress-close? flag left stuck true by an earlier row's own throw
  makes close-request-fn return its suppressed branch forever, so even
  the forced gtk_window_close never actually closes the window --
  g_application_run then never returns and -main's own thread, parked
  inside it, never reaches its own System/exit. close-window-bounded!'s
  on-stuck is the way out: it records a second FAIL, makes sure SUMMARY
  has printed, and exits from this go thread. Never uses await-until:
  once the window is gone the GTK main loop has ended, and an on-gui!
  thunk would never run."
  [ui]
  (go (let [t0 (System/currentTimeMillis)]
        (loop []
          (when (and (not @window-destroyed?)
                     (< (- (System/currentTimeMillis) t0) WINDOW-CLOSE-FALLBACK-DEADLINE-MS))
            (<! (timeout 50))
            (recur))))
      (when-not @window-destroyed?
        (support/check :command-chord-close-forced false
                       {:reason "window still open after the close chord; forced closed to avoid hanging the run"})
        (ga/close-window-bounded!
         (:window ui) window-destroyed? WINDOW-CLOSE-FALLBACK-DEADLINE-MS
         (fn []
           (support/check :command-chord-close-stuck false
                          {:reason "window still open after the forced close too; exiting to avoid hanging the run"})
           (print-summary!)
           (temp-dirs/delete-made!)
           (System/exit 1))))
      (print-summary!)))

(defn -main [& _]
  (let [driver-done (a/promise-chan)]
    (main/start!
     {:settings-path (support/fresh-settings-path)
      :on-ready (fn [ui]
                  (selftest-keys/watch! (:window ui))
                  (g/g-signal-connect-data (:window ui) "destroy"
                                           (cb/callable (fn [_win _data] (reset! window-destroyed? true) nil)
                                                        [:pointer :pointer] :void)
                                           ffi/null ffi/null g/CONNECT-DEFAULT)
                  ;; recording a demo: JONQUIL_SELFTEST_MAXIMIZE fills the screen
                  (when (System/getenv "JONQUIL_SELFTEST_MAXIMIZE") (g/gtk-window-maximize (:window ui)))
                  (go (<! (timeout 300))
                      (<! (ga/run-rows (map (fn [row] (fn [] (run-row ui row))) row-fns)
                                       PER-ROW-MS support/check))
                      (<! (finish ui))
                      (a/put! driver-done :done)))})
    ;; g_application_run has returned, so this thread may block now. The
    ;; driver prints SUMMARY from its own thread after it sees the destroy
    ;; signal, which can land just after the main loop ends.
    (a/alts!! [driver-done (timeout 5000)])
    (when-not @finished?
      (binding [*out* *err*]
        (println "[gtk-selftest-window] run ended before the last row; no SUMMARY, exiting 1")))
    (temp-dirs/delete-made!)
    (System/exit (if (and @finished? (seq @support/results) (every? second @support/results)) 0 1))))
