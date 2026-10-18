(ns gtk-selftest-nrepl
  "D2.T4 self-test: opens the REAL Jonquil window via jonquil.main/start!
  against a throwaway project whose .jonquil.edn opts into {:runtime
  :nrepl}, so a real Jolt child runs behind it, and runs the rows in
  gtk-selftest-nrepl.rows against its ui. Mirrors gtk-selftest-window's own
  driver (run-row/finish/-main; dev/gtk_selftest_window.clj), with the
  differences the D2.T4 brief calls out:

  - run-row polls rows/idle? -- through the window's own evaluator, since
    an NRepl evaluator has no :ctx for support/idle? to read -- with a
    20000 ms deadline, longer than the window group's own 2000 ms, because
    the eager start (or a restart) can still be in flight when a row runs.
  - -main requires a jolt-lang/nrepl middleware checkout
    (jonquil.nrepl.child/middleware-root) before it starts a window at
    all; without one, it records a single failing check and never opens
    anything (by design: child-test/middleware-root-test fails the same
    way, D2.T2's ruling).

  D2.T5 adds the real close row (row-nrepl-close-kills-child, deliberately
  last in row-fns) and after-close-checks! at the end of the run. Unlike
  D2.T4 (which had no close row and just called gtk-window-close directly
  once every row had run), the close row itself now fires the app's own
  \"quit\" action -- window.clj's :activate-quit-action!*, the same path a
  Quit AppleEvent takes -- so finish (below) is now a close-forced
  FALLBACK only, the same shape as the window group's own finish
  (dev/gtk_selftest_window.clj): it polls window-destroyed? (a \"destroy\"
  signal this file connects in -main, independent of window.clj's own
  close-request->quit wiring) and forces the close through gtk-async/
  close-window-bounded! only if the real quit action somehow didn't
  already close it, recording a FAIL if so, and (I1) a second FAIL plus a
  process exit if even the forced close is stuck. Nothing here needs the
  alert-dialog path close-request-fn also has to guard, since row-nrepl-
  close-kills-child leaves the editor clean (set-text! never marks it
  dirty) right before quitting, so the real close always takes close-
  request-fn's immediate real-quit branch.

  after-close-checks! (rows.clj) runs after main/start! returns, i.e.
  after the GTK main loop is actually gone -- SUMMARY is printed only once
  it's done, so its three checks are always part of the same summary the
  exit code is computed from, never a separate late FAIL that would need
  its own tally line."
  (:require [clojure.core.async :as a :refer [<! go timeout]]
            [gtk-async :as ga :refer [on-gtk await-until go-row]]
            [gtk-selftest-nrepl.rows :as rows]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]
            [jonquil.nrepl.child :as child]
            [temp-dirs]))

(def ^:private row-fns
  [rows/row-nrepl-starts-and-reports
   rows/row-nrepl-run-output
   rows/row-nrepl-repl-follows-run
   rows/row-nrepl-inspect-last
   rows/row-nrepl-error-is-text
   rows/row-nrepl-reset-clears-child
   rows/row-nrepl-stdin-roundtrip
   rows/row-nrepl-completion-from-child
   rows/row-nrepl-quick-doc-from-child
   rows/row-nrepl-jump-to-another-file
   rows/row-nrepl-stop-interrupts
   rows/row-nrepl-stop-unresponsive-restarts
   rows/row-nrepl-child-dies-mid-eval
   rows/row-nrepl-open-switches-evaluator
   rows/row-nrepl-two-tabs-owner-reset
   rows/row-nrepl-loose-file-runs-in-the-child
   rows/row-nrepl-close-kills-child])

;; --- driver ----------------------------------------------------------------

(def ^:private PER-ROW-MS
  ;; estimate (D2.T5's row tables): row-nrepl-close-kills-child's own
  ;; failing path plus its shell waits and runs is about 35000 ms, plus
  ;; run-row's 20000 ms idle wait.
  60000)

(defn- run-row
  "Resets the editor and the console's input, waits (up to 20000 ms) for
  the nREPL evaluator to go idle, then runs row and parks until its
  channel delivers."
  [ui row]
  (go-row :row-reset support/check
    (on-gtk (editor/set-text! (:editor ui) "")
            (cview/clear-input! (:console ui)))
    (await-until 20000 20 #(rows/idle? ui))
    (<! (row ui))))

;; Same reasoning as gtk-selftest-window's own finished? -- set only where
;; SUMMARY prints, so an early quit can never report every result so far
;; as a false PASS via a stale exit 0. Now set after after-close-checks!
;; runs too (-main, below), not from finish itself.
(defonce ^:private finished? (atom false))

;; D2.T5: mirrors gtk-selftest-window's own window-destroyed? (a plain
;; atom flip from a "destroy" signal, safe to poll without ever touching
;; the possibly-already-destroyed window pointer directly).
(defonce ^:private window-destroyed? (atom false))

(def ^:private CLOSE-FALLBACK-DEADLINE-MS
  ;; Generous: row-nrepl-close-kills-child's own real quit closes within
  ;; one main-loop iteration, so this only actually waits out its deadline
  ;; when that quit path is broken. finish (below) uses this same constant
  ;; twice: once to wait for the quit action's own close to land, and
  ;; again (I1) as close-window-bounded!'s own deadline for the forced
  ;; close that follows when the quit action didn't work. M3: this was
  ;; 2000 before the arc; AH.T1 changed it to 10000 by mistake, restored
  ;; here.
  2000)

(defn- print-summary!
  "SUMMARY, printed exactly once, from wherever the run actually ends."
  []
  (let [passed (count (filter second @support/results)) total (count @support/results)]
    (reset! finished? true)
    (println (str "SUMMARY " passed " of " total " passed"))))

(defn- finish
  "After the last row. row-nrepl-close-kills-child (the last row) already
  fired the real quit action. Poll window-destroyed? rather than assuming
  it worked: if that regressed, the window is still open here, and
  without this fallback nothing would ever end the run.

  I1: a suppress-close?-shaped stuck flag would make even the forced
  gtk_window_close (via gtk-async/close-window-bounded!) never actually
  close the window, so close-window-bounded!'s on-stuck is the real way
  out: it records a second FAIL, makes sure SUMMARY has printed, and
  exits from this go thread, since -main's own thread would otherwise
  stay parked inside main/start! forever. Never uses await-until: once
  the window is gone the GTK main loop has ended, and an on-gui! thunk
  would never run."
  [ui]
  (go (let [t0 (System/currentTimeMillis)]
        (loop []
          (when (and (not @window-destroyed?)
                     (< (- (System/currentTimeMillis) t0) CLOSE-FALLBACK-DEADLINE-MS))
            (<! (timeout 50))
            (recur))))
      (when-not @window-destroyed?
        (support/check :nrepl-close-forced false
                       {:reason "window still open after the quit action; forced closed to avoid hanging the run"})
        (ga/close-window-bounded!
         (:window ui) window-destroyed? CLOSE-FALLBACK-DEADLINE-MS
         (fn []
           (support/check :nrepl-close-stuck false
                          {:reason "window still open after the forced close too; exiting to avoid hanging the run"})
           (print-summary!)
           (temp-dirs/delete-made!)
           (System/exit 1))))))

(defn -main [& _]
  (if (child/middleware-root)
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
      ;; D2.T5: the GTK loop is gone once main/start! returns -- rows/
      ;; after-close-checks!'s own blocking shell/sh calls are safe here,
      ;; unlike inside a row (which must go through sh-within, bounded to
      ;; 5000 ms, so a row never hangs the whole run waiting on a shell
      ;; command). SUMMARY (and finished?) wait for these three checks so
      ;; the exit code reflects all of them, not just the rows.
      (a/alts!! [driver-done (timeout 5000)])
      (rows/after-close-checks!)
      (print-summary!))
    (support/check :middleware-root-present false
                   {:reason "no jolt-lang/nrepl checkout: set JONQUIL_JOLT_NREPL or clone it to ~/dev/jolt-lang/nrepl"}))
  (when-not @finished?
    (binding [*out* *err*]
      (println "[gtk-selftest-nrepl] run ended before the last row; no SUMMARY, exiting 1")))
  (temp-dirs/delete-made!)
  (System/exit (if (and @finished? (seq @support/results) (every? second @support/results)) 0 1)))
