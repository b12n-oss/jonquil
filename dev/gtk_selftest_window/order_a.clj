(ns gtk-selftest-window.order-a
  "Rows 1 to 111 of the window self-test, in the order they run.
  dev/gtk_selftest_window.clj joins the five parts order-a to order-e
  into its row list."
  (:require [gtk-selftest-window.close-prerun :as close-prerun]
            [gtk-selftest-window.console-layout :as console-layout]
            [gtk-selftest-window.files :as files]
            [gtk-selftest-window.find-replace :as find-replace]
            [gtk-selftest-window.prompt :as prompt]
            [gtk-selftest-window.run-form :as run-form]
            [gtk-selftest-window.run-repl :as run-repl]
            [gtk-selftest-window.tabs :as tabs]
            [gtk-selftest-window.themes :as themes]
            [gtk-selftest-window.window-keys :as window-keys]))

(def rows
  [;; first, before any row switches tabs, which would put the launch tab in
   ;; the recent list by another way
   tabs/row-tab-launch-tab-is-recent
   prompt/row-prompt-starts-user
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
   window-keys/row-f5-runs-the-buffer
   window-keys/row-command-chord-capslock
   window-keys/row-keymap-masks-match-gdk
   close-prerun/row-repl-eof-no-editor-hint
   files/row-open-refuses-dirty
   files/row-open-invalid-utf8
   files/row-open-refuses-nul
   files/row-open-nul-new-tab
   files/row-save-atomic
   files/row-save-follows-symlink
   files/row-save-dangling-symlink
   files/row-save-keeps-private-mode
   files/row-save-symlink-loop
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
   find-replace/row-find-past-an-emoji
   find-replace/row-find-next-wraps
   find-replace/row-find-prev
   find-replace/row-find-no-match
   find-replace/row-replace-one
   find-replace/row-replace-all
   find-replace/row-find-escape
   find-replace/row-find-hangul
   find-replace/row-find-survives-repaint
   find-replace/row-find-preedit-yields
   find-replace/row-find-return-on-a-button-yields
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
   find-replace/row-find-user-caret-at-match-edge
   find-replace/row-find-editor-next-then-replace
   find-replace/row-window-find-keys-editor-focused
   find-replace/row-find-preedit-ends-at-close
   find-replace/row-find-bar-buttons
   close-prerun/row-line-col-typing
   close-prerun/row-close-dismissed
   close-prerun/row-close-save-failed
   close-prerun/row-close-alert-build-throws
   close-prerun/row-quit-action-prompts
   themes/row-theme-retints-tags
   themes/row-theme-retints-inspector-selection
   themes/row-theme-css
   themes/row-theme-prefer-dark
   themes/row-theme-keeps-text-and-undo
   themes/row-theme-keeps-find-highlight
   themes/row-theme-and-zoom-share-css
   themes/row-theme-applies-to-new-text
   themes/row-theme-cycle
   themes/row-auto-follows-system
   themes/row-auto-flip-keeps-text
   themes/row-auto-poll-stops
   themes/row-auto-poll-single-timer
   themes/row-auto-poll-nil-keeps-palette
   themes/row-auto-startup-load
   themes/row-system-dark-real
   themes/row-theme-chord
   themes/row-theme-chord-needs-shift
   themes/row-settings-isolated
   themes/row-settings-unreadable-warns])
