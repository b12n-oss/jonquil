(ns gtk-selftest-window
  "P2.T7 self-test: opens the REAL Jonquil window via jonquil.main/start!
  and runs the front-end self-test rows against its ui, on the same
  timer-chain pattern as dev/gtk_selftest.clj -- never a fixed sleep,
  every wait polls a condition against a deadline. Rows call ui's exposed
  functions (run!*, stop!*, open-path!, save-to!) directly, or drive the
  console's own submit path (handle-return! over an interactive insert),
  exactly as the buttons/keys and the user's typing do; no keystroke is
  ever synthesized.

  Each row resets the editor and console first and leaves the engine idle
  before returning control to the driver -- a row that starts a program
  stops it and waits for real, rather than trusting the next row's own
  reset to paper over it.

  The rows themselves live in the gtk-selftest-window.* namespaces under
  dev/gtk_selftest_window/, one per feature area (support, run-repl, files,
  find-replace, close-prerun, window-keys, themes, popovers-quickdoc,
  completion, rename-menus). This file keeps only row-fns (the ordered
  list every row runs in -- the last row really closes the window, so
  order matters), the driver, and -main."
  (:require [gtk-selftest-window.close-prerun :as close-prerun]
            [gtk-selftest-window.completion :as completion]
            [gtk-selftest-window.files :as files]
            [gtk-selftest-window.find-replace :as find-replace]
            [gtk-selftest-window.popovers-quickdoc :as popovers-quickdoc]
            [gtk-selftest-window.rename-menus :as rename-menus]
            [gtk-selftest-window.run-repl :as run-repl]
            [gtk-selftest-window.support :as support]
            [gtk-selftest-window.themes :as themes]
            [gtk-selftest-window.window-keys :as window-keys]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]))

(def ^:private row-fns
  [run-repl/row-run-output
   run-repl/row-run-while-running
   run-repl/row-stdin-roundtrip
   run-repl/row-stop-while-reading
   close-prerun/row-run-unbalanced
   run-repl/row-reset-vs-accumulate
   run-repl/row-repl-after-run
   run-repl/row-fast-repl-twice
   run-repl/row-busy-hint-once
   run-repl/row-console-history-down-empty
   window-keys/row-escape-yields-when-idle
   window-keys/row-command-chord-capslock
   close-prerun/row-repl-eof-no-editor-hint
   files/row-open-refuses-dirty
   files/row-open-invalid-utf8
   files/row-save-atomic
   files/row-save-follows-symlink
   files/row-save-executable-keeps-mode
   files/row-save-failure-leaves-original-intact
   files/row-dialog-dismissed-silent
   files/row-save-and-open
   run-repl/row-run-selection-line
   run-repl/row-run-selection-sel
   run-repl/row-run-selection-blank
   window-keys/row-toggle-focus
   run-repl/row-clear-console
   window-keys/row-zoom-in-out
   find-replace/row-find-highlights
   find-replace/row-find-next-wraps
   find-replace/row-find-prev
   find-replace/row-find-no-match
   find-replace/row-replace-one
   find-replace/row-replace-all
   find-replace/row-find-escape
   find-replace/row-find-hangul
   find-replace/row-find-survives-repaint
   find-replace/row-find-preedit-yields
   find-replace/row-find-prefill-selects
   close-prerun/row-line-col
   close-prerun/row-close-clean
   close-prerun/row-close-dirty-cancel
   close-prerun/row-close-dirty-discard
   close-prerun/row-close-dirty-save
   close-prerun/row-pre-run-hidden-by-error
   close-prerun/row-pre-run-stray-closer
   close-prerun/row-close-alert-pending-guard
   close-prerun/row-close-alert-routing
   close-prerun/row-run-while-running-keeps-warning
   find-replace/row-window-escape-closes-bar-while-running
   find-replace/row-window-escape-yields-preedit-while-running
   find-replace/row-window-escape-stops-when-editor-focused
   find-replace/row-find-typing-keeps-caret
   find-replace/row-find-return-in-replacement
   find-replace/row-window-find-keys-editor-focused
   close-prerun/row-line-col-typing
   close-prerun/row-close-dismissed
   close-prerun/row-close-save-failed
   close-prerun/row-close-alert-build-throws
   close-prerun/row-quit-action-prompts
   themes/row-theme-retints-tags
   themes/row-theme-css
   themes/row-theme-prefer-dark
   themes/row-theme-keeps-text-and-undo
   themes/row-theme-keeps-find-highlight
   themes/row-theme-and-zoom-share-css
   themes/row-theme-applies-to-new-text
   themes/row-theme-cycle
   themes/row-theme-chord
   themes/row-theme-chord-needs-shift
   themes/row-settings-isolated
   themes/row-settings-unreadable-warns
   themes/row-theme-save-failure-reported
   themes/row-theme-switch-while-running
   popovers-quickdoc/row-popover-at-caret
   popovers-quickdoc/row-popover-keys-stay-with-editor
   popovers-quickdoc/row-window-shift-f6-reaches-editor
   popovers-quickdoc/row-window-f6-still-toggles
   popovers-quickdoc/row-window-escape-closes-popover
   popovers-quickdoc/row-popover-dark-colours
   popovers-quickdoc/row-quick-doc-core
   popovers-quickdoc/row-quick-doc-run-defined
   popovers-quickdoc/row-quick-doc-after-reset
   popovers-quickdoc/row-quick-doc-closes
   popovers-quickdoc/row-quick-doc-closes-on-focus-loss
   popovers-quickdoc/row-quick-doc-cmd-does-nothing
   popovers-quickdoc/row-quick-doc-cmd-shift-f1-does-nothing
   popovers-quickdoc/row-quick-doc-after-define
   popovers-quickdoc/row-jump-top-level
   popovers-quickdoc/row-jump-local-binding
   popovers-quickdoc/row-jump-external
   popovers-quickdoc/row-jump-none
   popovers-quickdoc/row-jump-shadowed-param
   completion/row-completion-opens
   completion/row-completion-single-inserts
   completion/row-completion-no-prefix
   completion/row-completion-navigate-commit
   completion/row-completion-tab-commits
   completion/row-completion-keys-reach-editor
   completion/row-completion-closes-on-space
   completion/row-completion-escape-while-running
   completion/row-completion-cmd-space-nothing
   completion/row-completion-preedit-yields
   completion/row-completion-closes-on-focus-loss
   completion/row-completion-closes-other-popover
   completion/row-completion-commit-throw-consumes-key
   completion/row-plain-return-apply-op-throw-contained
   completion/row-repl-completion-opens
   completion/row-repl-completion-precedence
   completion/row-repl-history-after-close
   completion/row-repl-completion-history-symbols
   completion/row-repl-completion-outside-input
   completion/row-repl-completion-cmd-space-nothing
   completion/row-repl-completion-preedit-yields
   completion/row-repl-completion-closes-on-focus-loss
   completion/row-repl-completion-keys-reach-console
   completion/row-repl-completion-closes-on-space
   rename-menus/row-rename-applies
   rename-menus/row-rename-skips-comments-strings
   rename-menus/row-rename-one-undo
   rename-menus/row-rename-invalid
   rename-menus/row-rename-keys
   rename-menus/row-rename-cmd-does-nothing
   rename-menus/row-rename-cmd-shift-f6-does-nothing
   rename-menus/row-rename-closed-signal-syncs
   rename-menus/row-menu-actions
   rename-menus/row-menu-actions-console-complete
   rename-menus/row-menu-action-throw-is-contained
   window-keys/row-cursor-listener-throw-others-still-run
   window-keys/row-window-key-throw-contained
   window-keys/row-command-chord-close])

;; --- driver --------------------------------------------------------------

(defn- run-row! [ui row-fn next]
  (editor/set-text! (:editor ui) "")
  (cview/clear-input! (:console ui))
  (support/poll-until 2000 20 (fn [] (support/idle? ui))
                      (fn [_idle?] (row-fn ui next))))

;; P2.T10 fix round 1, Important #2: removing run-rows!'s old unconditional
;; close made row-command-chord-close the only way out, so a REGRESSION in
;; the close chord (on-window-key stops matching KEY-W, close-fn stops
;; being wired, etc.) would leave the g_application main loop running
;; forever with nothing to stop it -- hanging `bb gtk:selftest` instead of
;; failing it. window-destroyed? is set by a "destroy" signal this file
;; connects on the window itself (-main, below), independent of window.clj's
;; own close-request->quit wiring: a plain Clojure atom flip, safe to poll
;; from run-rows! without ever touching the (possibly already-destroyed)
;; widget pointer directly.
(defonce ^:private window-destroyed? (atom false))

;; P3 fix wave I3: set only where SUMMARY prints, i.e. when run-rows! reached
;; the end of row-fns. An early g_application_quit (a row that really closes
;; or quits) ends g_application_run with the remaining rows never run and no
;; SUMMARY, and every result recorded so far can still be a PASS -- -main
;; used to exit 0 then, and `bb gtk:selftest` reads only exit codes.
(defonce ^:private finished? (atom false))

(def ^:private WINDOW-CLOSE-FALLBACK-DEADLINE-MS
  ;; Generous: a working chord closes within one main-loop iteration
  ;; (microseconds), so this only ever actually waits out its deadline
  ;; when the chord is broken.
  2000)

(defn- run-rows! [ui fns]
  (if (empty? fns)
    ;; row-command-chord-close (the last row) already tried the product's
    ;; own close chord. Poll window-destroyed? rather than assuming it
    ;; worked: if the chord regressed (on-window-key stops matching,
    ;; close-fn stops being wired, or gtk_window_close/close-request stops
    ;; actually quitting the app), the window is still open here, and
    ;; without this fallback nothing would ever end the run. Forcing
    ;; gtk_window_close directly guarantees this process still exits
    ;; (non-zero, via the FAIL below) instead of hanging -- the chord row
    ;; itself stays a real check; this is only a backstop for when it
    ;; fails.
    (support/poll-until WINDOW-CLOSE-FALLBACK-DEADLINE-MS 50
                        (fn [] @window-destroyed?)
                        (fn [closed?]
                          (when-not closed?
                            (support/check :command-chord-close-forced false
                                           {:reason "window still open after the close chord; forced closed to avoid hanging the run"})
                            (g/gtk-window-close (:window ui)))
                          (let [passed (count (filter second @support/results)) total (count @support/results)]
                            (reset! finished? true)
                            (println (str "SUMMARY " passed " of " total " passed")))))
    (run-row! ui (first fns) (fn [] (run-rows! ui (rest fns))))))

(defn -main [& _]
  (main/start!
   {:settings-path (support/fresh-settings-path)
    :on-ready (fn [ui]
                (g/g-signal-connect-data (:window ui) "destroy"
                                         (cb/callable (fn [_win _data] (reset! window-destroyed? true) nil)
                                                      [:pointer :pointer] :void)
                                         ffi/null ffi/null g/CONNECT-DEFAULT)
                (support/later 300 (fn [] (run-rows! ui row-fns))))})
  (when-not @finished?
    (binding [*out* *err*]
      (println "[gtk-selftest-window] run ended before the last row; no SUMMARY, exiting 1")))
  (System/exit (if (and @finished? (seq @support/results) (every? second @support/results)) 0 1)))
