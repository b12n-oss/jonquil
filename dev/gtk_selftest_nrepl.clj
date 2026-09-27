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

  D2.T5 adds the real close row (row-nrepl-close-kills-child) and
  after-close-checks! at the end of the run, with its own close-forced
  fallback (the same shape as the window group's WINDOW_CLOSE_FALLBACK).
  D2.T4 has no dedicated close row yet -- every row here leaves the editor
  clean (set-text! never itself marks dirty), so once the last row has
  recorded its own check, this simply closes the window directly; nothing
  here needs the alert-dialog path close-request-fn also has to guard,
  since a clean editor's close always takes the immediate real-quit
  branch."
  (:require [gtk-selftest-nrepl.rows :as rows]
            [gtk-selftest-window.support :as support]
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
   rows/row-nrepl-quick-doc-from-child])

;; --- driver ----------------------------------------------------------------

(defn- run-row! [ui row-fn next]
  (editor/set-text! (:editor ui) "")
  (cview/clear-input! (:console ui))
  (support/poll-until 20000 20 (fn [] (rows/idle? ui))
                      (fn [_idle?] (row-fn ui next))))

;; Same reasoning as gtk-selftest-window's own finished? -- set only where
;; SUMMARY prints, so an early quit (nothing here does that yet, but D2.T5
;; will) can never report every result so far as a false PASS via a stale
;; exit 0.
(defonce ^:private finished? (atom false))

(defn- run-rows! [ui fns]
  (if (empty? fns)
    (do
      (let [passed (count (filter second @support/results)) total (count @support/results)]
        (reset! finished? true)
        (println (str "SUMMARY " passed " of " total " passed")))
      ;; D2.T4 has no close row of its own; the editor is clean at this
      ;; point (every row above uses editor/set-text!, which never marks
      ;; dirty), so this real close-request always takes the immediate
      ;; quit branch. D2.T5 replaces this call with its own last row.
      (g/gtk-window-close (:window ui)))
    (run-row! ui (first fns) (fn [] (run-rows! ui (rest fns))))))

(defn -main [& _]
  (if (child/middleware-root)
    (let [fixture (rows/fixture!)]
      (main/start!
       {:file (:file fixture)
        :settings-path (support/fresh-settings-path)
        :on-ready (fn [ui] (support/later 300 (fn [] (run-rows! ui row-fns))))}))
    (support/check :middleware-root-present false
                   {:reason "no jolt-lang/nrepl checkout: set JONQUIL_JOLT_NREPL or clone it to ~/dev/jolt-lang/nrepl"}))
  (when-not @finished?
    (binding [*out* *err*]
      (println "[gtk-selftest-nrepl] run ended before the last row; no SUMMARY, exiting 1")))
  (System/exit (if (and @finished? (seq @support/results) (every? second @support/results)) 0 1)))
