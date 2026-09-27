(ns gtk-selftest-nrepl
  "D2.T4 self-test: opens the REAL Jonquil window via jonquil.main/start!
  against a throwaway project whose .jonquil.edn opts into {:runtime
  :nrepl}, so a real Jolt child runs behind it, and runs the rows in
  gtk-selftest-nrepl.rows against its ui. Mirrors gtk-selftest-window's own
  driver (run-row!/run-rows!/-main; dev/gtk_selftest_window.clj), with the
  differences the D2.T4 brief calls out:

  - run-row! polls rows/idle? -- through the window's own evaluator, since
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
  Quit AppleEvent takes -- so run-rows!'s own empty-fns branch is now a
  close-forced FALLBACK only, the same shape as the window group's own
  WINDOW-CLOSE-FALLBACK-DEADLINE-MS (dev/gtk_selftest_window.clj): it polls
  window-destroyed? (a \"destroy\" signal this file connects in -main,
  independent of window.clj's own close-request->quit wiring) and forces
  gtk-window-close only if the real quit action somehow didn't already
  close it, recording a FAIL if so. Nothing here needs the alert-dialog
  path close-request-fn also has to guard, since row-nrepl-close-kills-
  child leaves the editor clean (set-text! never marks it dirty) right
  before quitting, so the real close always takes close-request-fn's
  immediate real-quit branch.

  after-close-checks! (rows.clj) runs after main/start! returns, i.e.
  after the GTK main loop is actually gone -- SUMMARY is printed only once
  it's done, so its three checks are always part of the same summary the
  exit code is computed from, never a separate late FAIL that would need
  its own tally line."
  (:require [gtk-selftest-nrepl.rows :as rows]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]
            [jonquil.nrepl.child :as child]))

(def ^:private row-fns
  [rows/row-nrepl-starts-and-reports
   rows/row-nrepl-run-output
   rows/row-nrepl-repl-follows-run
   rows/row-nrepl-error-is-text
   rows/row-nrepl-reset-clears-child
   rows/row-nrepl-stdin-notice
   rows/row-nrepl-completion-from-child
   rows/row-nrepl-quick-doc-from-child
   rows/row-nrepl-stop-interrupts
   rows/row-nrepl-stop-unresponsive-restarts
   rows/row-nrepl-child-dies-mid-eval
   rows/row-nrepl-open-switches-evaluator
   rows/row-nrepl-close-kills-child])

;; --- driver ----------------------------------------------------------------

(defn- run-row! [ui row-fn next]
  (editor/set-text! (:editor ui) "")
  (cview/clear-input! (:console ui))
  (support/poll-until 20000 20 (fn [] (rows/idle? ui))
                      (fn [_idle?] (row-fn ui next))))

;; Same reasoning as gtk-selftest-window's own finished? -- set only where
;; SUMMARY prints, so an early quit can never report every result so far
;; as a false PASS via a stale exit 0. Now set after after-close-checks!
;; runs too (-main, below), not from run-rows! itself.
(defonce ^:private finished? (atom false))

;; D2.T5: mirrors gtk-selftest-window's own window-destroyed? (a plain
;; atom flip from a "destroy" signal, safe to poll without ever touching
;; the possibly-already-destroyed window pointer directly).
(defonce ^:private window-destroyed? (atom false))

(def ^:private CLOSE-FALLBACK-DEADLINE-MS
  ;; Generous: row-nrepl-close-kills-child's own real quit closes within
  ;; one main-loop iteration, so this only actually waits out its deadline
  ;; when that quit path is broken.
  2000)

(defn- run-rows! [ui fns]
  (if (empty? fns)
    ;; row-nrepl-close-kills-child (the last row) already fired the real
    ;; quit action. Poll window-destroyed? rather than assuming it worked:
    ;; if that regressed, the window is still open here, and without this
    ;; fallback nothing would ever end the run.
    (support/poll-until CLOSE-FALLBACK-DEADLINE-MS 50
                        (fn [] @window-destroyed?)
                        (fn [closed?]
                          (when-not closed?
                            (support/check :nrepl-close-forced false
                                           {:reason "window still open after the quit action; forced closed to avoid hanging the run"})
                            (g/gtk-window-close (:window ui)))))
    (run-row! ui (first fns) (fn [] (run-rows! ui (rest fns))))))

(defn -main [& _]
  (if (child/middleware-root)
    (let [fixture (rows/fixture!)]
      (main/start!
       {:file (:file fixture)
        :settings-path (support/fresh-settings-path)
        :on-ready (fn [ui]
                    (g/g-signal-connect-data (:window ui) "destroy"
                                             (cb/callable (fn [_win _data] (reset! window-destroyed? true) nil)
                                                          [:pointer :pointer] :void)
                                             ffi/null ffi/null g/CONNECT-DEFAULT)
                    (support/later 300 (fn [] (run-rows! ui row-fns))))})
      ;; D2.T5: the GTK loop is gone once main/start! returns -- rows/
      ;; after-close-checks!'s own blocking shell/sh calls are safe here,
      ;; unlike inside a row (which must go through shell-then, since
      ;; g_timeout_add needs a live main loop). SUMMARY (and finished?)
      ;; wait for these three checks so the exit code reflects all of
      ;; them, not just the rows.
      (rows/after-close-checks!)
      (let [passed (count (filter second @support/results)) total (count @support/results)]
        (reset! finished? true)
        (println (str "SUMMARY " passed " of " total " passed"))))
    (support/check :middleware-root-present false
                   {:reason "no jolt-lang/nrepl checkout: set JONQUIL_JOLT_NREPL or clone it to ~/dev/jolt-lang/nrepl"}))
  (when-not @finished?
    (binding [*out* *err*]
      (println "[gtk-selftest-nrepl] run ended before the last row; no SUMMARY, exiting 1")))
  (System/exit (if (and @finished? (seq @support/results) (every? second @support/results)) 0 1)))
