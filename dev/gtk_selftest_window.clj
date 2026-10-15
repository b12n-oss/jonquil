(ns gtk-selftest-window
  "P2.T7 self-test: opens the REAL Jonquil window via jonquil.main/start!
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
  dev/gtk_selftest_window/, one per feature area (support, run-form,
  run-repl, files, find-replace, close-prerun, window-keys, themes,
  popovers-quickdoc, completion, rename-menus, dialects). This file keeps
  only row-fns (the ordered list every row runs in -- the last row really
  closes the window, so order matters), the driver, and -main."
  (:require [clojure.core.async :as a :refer [<! go timeout]]
            [gtk-async :as ga :refer [on-gtk await-until go-row]]
            [gtk-selftest-window.app-open :as app-open]
            [gtk-selftest-window.close-prerun :as close-prerun]
            [gtk-selftest-window.completion :as completion]
            [gtk-selftest-window.console-layout :as console-layout]
            [gtk-selftest-window.contained :as contained]
            [gtk-selftest-window.dialects :as dialects]
            [gtk-selftest-window.files :as files]
            [gtk-selftest-window.file-tree :as file-tree]
            [gtk-selftest-window.file-tree-pane :as file-tree-pane]
            [gtk-selftest-window.find-replace :as find-replace]
            [gtk-selftest-window.inspector :as inspector]
            [gtk-selftest-window.popovers-quickdoc :as popovers-quickdoc]
            [gtk-selftest-window.prompt :as prompt]
            [gtk-selftest-window.rename-menus :as rename-menus]
            [gtk-selftest-window.run-form :as run-form]
            [gtk-selftest-window.run-repl :as run-repl]
            [gtk-selftest-window.source-pane :as source-pane]
            [gtk-selftest-window.support :as support]
            [gtk-selftest-window.tab-widget :as tab-widget]
            [gtk-selftest-window.tabs :as tabs]
            [gtk-selftest-window.themes :as themes]
            [gtk-selftest-window.window-keys :as window-keys]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]
            [temp-dirs]))

(def ^:private row-fns
  [prompt/row-prompt-starts-user
   run-repl/row-run-output
   run-repl/row-run-while-running
   run-repl/row-stdin-roundtrip
   run-repl/row-stop-while-reading
   close-prerun/row-run-unbalanced
   run-repl/row-reset-vs-accumulate
   run-repl/row-repl-after-run
   run-repl/row-fast-repl-twice
   run-repl/row-busy-hint-once
   run-repl/row-typed-ahead-not-evaluated
   run-repl/row-console-history-down-empty
   window-keys/row-escape-yields-when-idle
   window-keys/row-command-chord-capslock
   close-prerun/row-repl-eof-no-editor-hint
   files/row-open-refuses-dirty
   files/row-open-invalid-utf8
   files/row-save-atomic
   files/row-save-follows-symlink
   files/row-save-dangling-symlink
   files/row-save-keeps-private-mode
   files/row-save-executable-keeps-mode
   files/row-save-failure-leaves-original-intact
   files/row-dialog-dismissed-silent
   files/row-save-and-open
   run-repl/row-run-selection-line
   run-repl/row-run-selection-sel
   run-repl/row-run-selection-blank
   run-form/row-run-form-top-level
   run-form/row-run-form-in-comment
   run-form/row-run-form-flashes
   run-form/row-run-form-key-cancels-flash
   run-form/row-run-form-flash-overlap
   run-form/row-run-form-key-before-smart-enter
   run-form/row-run-form-menu-item
   run-form/row-run-form-nothing-at-caret
   window-keys/row-toggle-focus
   run-repl/row-clear-console
   run-repl/row-clear-last-two-entries
   run-repl/row-clear-last-while-running
   run-repl/row-clear-last-twice-noop
   run-repl/row-clear-console-key-regression
   run-repl/row-clear-last-menu-item
   run-repl/row-clear-last-run-selection-mid-run-keeps-mark
   run-repl/row-clear-last-mark-lands-after-queued-tail
   console-layout/row-console-wraps-long-lines
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
   completion/row-completion-arrow-keeps-rows
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
   dialects/row-dialect-label-default
   dialects/row-evaluator-is-the-session
   dialects/row-project-config-malformed-warns
   dialects/row-project-config-unknown-runtime-warns
   dialects/row-project-open-keeps-evaluator
   dialects/row-editor-paints-profile-sets
   dialects/row-selection-throw-still-paints-new-profile
   inspector/row-pane-renders-view
   inspector/row-pane-keys-step
   inspector/row-pane-leaf
   inspector/row-pane-measure
   inspector/row-pane-note-clears
   inspector/row-inspect-focus-after-open
   inspector/row-inspect-last-after-repl
   inspector/row-inspect-in-out
   inspector/row-inspect-page-edge
   inspector/row-inspect-reselect
   inspector/row-inspect-table-meta
   inspector/row-inspect-def
   inspector/row-inspect-def-keeps-selection
   inspector/row-inspect-form
   inspector/row-inspect-escape
   inspector/row-inspect-cols-match
   inspector/row-inspect-def-prompt-escape-safe
   contained/row-find-change-throw-contained
   contained/row-find-replace-throw-contained
   contained/row-find-replace-all-throw-contained
   contained/row-run-button-throw-contained
   contained/row-stop-button-throw-contained
   contained/row-open-button-throw-contained
   contained/row-save-button-throw-contained
   contained/row-reset-toggle-throw-contained
   contained/row-open-result-throw-contained
   contained/row-save-result-throw-contained
   contained/row-dirty-timer-survives-throw
   prompt/row-prompt-follows-repl-ns
   prompt/row-prompt-keeps-typed-input
   prompt/row-prompt-follows-run
   prompt/row-prompt-echo-uses-prompt
   source-pane/row-source-pane-shows-line
   source-pane/row-source-pane-user-scroll-wins
   source-pane/row-source-pane-hide-stops-settle
   source-pane/row-source-pane-jar-read-only
   source-pane/row-source-pane-missing-entry
   source-pane/row-source-pane-line-moved
   source-pane/row-source-pane-history
   source-pane/row-source-pane-back-deleted-file
   source-pane/row-source-pane-same-file-no-reload
   tab-widget/row-tab-widget-builds
   tab-widget/row-tab-widget-dispose-unparents
   file-tree-pane/row-tree-pane-shows-rows
   file-tree-pane/row-tree-pane-folder-click
   file-tree-pane/row-tree-pane-file-click
   file-tree-pane/row-tree-pane-select-path
   file-tree-pane/row-tree-pane-refresh-deleted
   file-tree-pane/row-tree-pane-unreadable-root
   file-tree-pane/row-tree-pane-large-folder
   file-tree-pane/row-tree-pane-select-non-canonical
   file-tree-pane/row-tree-pane-deferred-activation-by-path
   window-keys/row-cursor-listener-throw-others-still-run
   window-keys/row-window-key-throw-contained
   tabs/row-tab-new-shows-strip
   tabs/row-tab-open-replaces-untouched
   tabs/row-tab-open-focuses-existing
   tabs/row-tab-switch-keys
   tabs/row-tab-dirty-label
   tabs/row-tab-close-dirty-cancel
   tabs/row-tab-close-dirty-discard
   tabs/row-tab-close-with-open-popover
   tabs/row-tab-switch-closes-popovers
   tabs/row-tab-theme-all-tabs
   tabs/row-tab-source-open-in-window
   tabs/row-tab-run-keeps-other-tab-ns
   tabs/row-tab-run-banner-names-file
   tabs/row-tab-foreign-refuses-run
   tabs/row-tab-close-to-one-adopts-project
   tabs/row-close-all-message
   tabs/row-close-all-save-all
   tabs/row-close-all-save-failure
   tabs/row-tab-close-releases-namespaces
   tabs/row-tab-close-cancels-flash
   tabs/row-tab-close-while-running-eof
   tabs/row-tab-foreign-refuses-inspect-form
   file-tree/row-tree-toggle-chord
   file-tree/row-tree-chord-not-run-selection
   file-tree/row-tree-button-toggles
   file-tree/row-tree-open-and-focus
   file-tree/row-tree-highlight-follows-tab
   file-tree/row-tree-save-shows-file
   file-tree/row-tree-settings-persist
   file-tree/row-tree-follows-theme
   file-tree/row-tree-escape-returns-focus
   app-open/row-app-open-two-files
   app-open/row-app-open-folder
   app-open/row-app-open-missing
   app-open/row-app-open-already-open
   app-open/row-app-open-non-local
   window-keys/row-command-chord-close])

;; --- driver --------------------------------------------------------------

(def ^:private PER-ROW-MS
  ;; A row that FAILs waits out every deadline on its path. The longest path
  ;; is run-repl/row-reset-vs-accumulate's four 3000 ms waits (12000 ms),
  ;; plus run-row's 2000 ms idle wait; the whole run takes about 5 s.
  30000)

(defn- run-row
  "Resets the editor and the console's input, waits (up to 2000 ms) for the
  engine to go idle, then runs row and parks until its channel delivers."
  [ui row]
  (go-row :row-reset support/check
    (on-gtk (editor/set-text! (:editor ui) "")
            (cview/clear-input! (:console ui)))
    (await-until 2000 20 #(support/idle? ui))
    (<! (row ui))))

;; P2.T10 fix round 1, Important #2: removing run-rows!'s old unconditional
;; close made row-command-chord-close the only way out, so a REGRESSION in
;; the close chord (on-window-key stops matching KEY-W, close-fn stops
;; being wired, etc.) would leave the g_application main loop running
;; forever with nothing to stop it -- hanging `bb gtk:selftest` instead of
;; failing it. window-destroyed? is set by a "destroy" signal this file
;; connects on the window itself (-main, below), independent of window.clj's
;; own close-request->quit wiring: a plain Clojure atom flip, safe to read
;; from the driver's go block without ever touching the (possibly
;; already-destroyed) widget pointer directly.
(defonce ^:private window-destroyed? (atom false))

;; P3 fix wave I3: set only where SUMMARY prints, i.e. when the driver
;; reached the end of row-fns. An early g_application_quit (a row that
;; really closes or quits) ends g_application_run with the remaining rows
;; never run and no SUMMARY, and every result recorded so far can still be
;; a PASS -- -main used to exit 0 then, and `bb gtk:selftest` reads only
;; exit codes.
(defonce ^:private finished? (atom false))

(def ^:private WINDOW-CLOSE-FALLBACK-DEADLINE-MS
  ;; Generous: a working chord closes within one main-loop iteration
  ;; (microseconds), so this only ever actually waits out its deadline
  ;; when the chord is broken. finish (below) uses this same constant
  ;; twice: once to wait for the chord's own close to land, and again
  ;; (I1) as close-window-bounded!'s own deadline for the forced close
  ;; that follows when the chord didn't work.
  2000)

(defn- print-summary!
  "SUMMARY, printed exactly once, from wherever the run actually ends."
  []
  (let [passed (count (filter second @support/results)) total (count @support/results)]
    (reset! finished? true)
    (println (str "SUMMARY " passed " of " total " passed"))))

(defn- finish
  "After the last row. row-command-chord-close already tried the product's
  own close chord; this reads window-destroyed? rather than assuming it
  worked. If the chord regressed, the window is still open, and forcing
  gtk_window_close (via gtk-async/close-window-bounded!) keeps the process
  from hanging.

  I1: a suppress-close? flag left stuck true by an earlier row's own throw
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
                  (g/g-signal-connect-data (:window ui) "destroy"
                                           (cb/callable (fn [_win _data] (reset! window-destroyed? true) nil)
                                                        [:pointer :pointer] :void)
                                           ffi/null ffi/null g/CONNECT-DEFAULT)
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
