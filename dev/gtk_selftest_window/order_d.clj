(ns gtk-selftest-window.order-d
  "Rows 333 to 442 of the window self-test, in the order they run.
  dev/gtk_selftest_window.clj joins the five parts order-a to order-e
  into its row list."
  (:require [gtk-selftest-window.change-sets :as change-sets-rows]
            [gtk-selftest-window.contained :as contained]
            [gtk-selftest-window.debugger :as debugger-rows]
            [gtk-selftest-window.docs-panel :as docs-panel-rows]
            [gtk-selftest-window.gutter :as gutter-rows]
            [gtk-selftest-window.messages :as messages-rows]
            [gtk-selftest-window.panels :as panel-rows]
            [gtk-selftest-window.processes :as processes-rows]
            [gtk-selftest-window.prompt :as prompt]
            [gtk-selftest-window.replay :as replay-rows]
            [gtk-selftest-window.restarts :as restarts-rows]
            [gtk-selftest-window.source-pane :as source-pane]
            [gtk-selftest-window.world :as world-rows]))

(def rows
  [panel-rows/row-panel-key-says-what-fills
   panel-rows/row-panel-stale-panels-leave
   processes-rows/row-processes-interrupt-and-forget
   change-sets-rows/row-change-set-make-apply-revert
   change-sets-rows/row-change-set-agent-proposal
   docs-panel-rows/row-docs-panel-show-follow-back-bookmark
   docs-panel-rows/row-docs-panel-guide-page
   docs-panel-rows/row-docs-panel-example-buttons
   panel-rows/row-layout-save-and-use
   panel-rows/row-layout-use-twice
   messages-rows/row-messages-agent-replace-logged
   messages-rows/row-messages-reads-hidden
   messages-rows/row-messages-status-collapses
   messages-rows/row-messages-closed-tab-target
   messages-rows/row-messages-burst
   messages-rows/row-messages-disk-change
   messages-rows/row-messages-disk-change-dirty
   messages-rows/row-messages-disk-change-nul
   messages-rows/row-messages-disk-deleted-then-back
   messages-rows/row-messages-disk-waits-a-tick
   messages-rows/row-messages-disk-follow-failure-said
   messages-rows/row-messages-selection-stays
   messages-rows/row-messages-unread-click-opens
   world-rows/row-world-save-restore
   replay-rows/row-replay-records-console
   replay-rows/row-replay-records-saved-run
   replay-rows/row-replay-skips-errors
   replay-rows/row-replay-offer-at-open
   replay-rows/row-replay-history-restored
   replay-rows/row-replay-off
   replay-rows/row-replay-files-under-start-root
   replay-rows/row-replay-records-run-changed
   replay-rows/row-replay-unreadable-other-root
   replay-rows/row-replay-open-tab
   replay-rows/row-replay-run-no-reset
   replay-rows/row-replay-throw-keeps-record
   replay-rows/row-replay-survives-new-eval
   replay-rows/row-replay-forget
   replay-rows/row-replay-none
   replay-rows/row-replay-open-twice
   replay-rows/row-replay-forget-off
   replay-rows/row-replay-reroot-open-new-tab
   replay-rows/row-replay-run-forgets-own-root
   replay-rows/row-replay-stale-tab
   replay-rows/row-replay-end-to-end
   replay-rows/row-replay-offer-dialect-differs
   replay-rows/row-replay-run-ns
   replay-rows/row-replay-run-carries-skipped
   replay-rows/row-replay-run-drops-deleted-skipped
   replay-rows/row-replay-state-per-window
   replay-rows/row-part-commands-reachable
   gutter-rows/row-gutter-bars-follow-lines
   gutter-rows/row-gutter-counts-widen-and-draw
   gutter-rows/row-changed-not-before-first-run
   gutter-rows/row-changed-marks-after-edit
   gutter-rows/row-run-changed-runs-only-those
   gutter-rows/row-line-counts-after-run
   gutter-rows/row-line-counts-unsaved
   gutter-rows/row-line-counts-second-run
   gutter-rows/row-line-counts-went-off
   gutter-rows/row-key-hints-follow-the-caret
   debugger-rows/row-debugger-pane-shows
   debugger-rows/row-debugger-pane-wiring
   debugger-rows/row-frames-after-error
   debugger-rows/row-break-off-by-default
   debugger-rows/row-break-flag-ends-with-its-eval
   debugger-rows/row-break-flag-survives-next-start
   debugger-rows/row-break-pauses
   debugger-rows/row-break-stop
   debugger-rows/row-break-stop-blocked
   debugger-rows/row-breakpoint-live-at-once
   debugger-rows/row-breakpoint-placement
   debugger-rows/row-breakpoint-waits-for-unrun-edits
   debugger-rows/row-breakpoint-asks-again-after-a-run
   debugger-rows/row-step-follows-the-lines
   debugger-rows/row-step-over-and-out
   debugger-rows/row-step-into-instrumented
   debugger-rows/row-instrument-reads-the-text-at-send
   restarts-rows/row-restart-retry
   restarts-rows/row-restart-edit
   restarts-rows/row-restart-define
   restarts-rows/row-restart-run-edit
   restarts-rows/row-restart-bar
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
   prompt/row-bang-runs-a-command
   prompt/row-bang-lists-choices
   prompt/row-bang-clears-the-last-output
   prompt/row-prompt-echo-uses-prompt
   source-pane/row-source-pane-shows-line
   source-pane/row-source-pane-user-scroll-wins
   source-pane/row-source-pane-hide-stops-settle
   source-pane/row-source-pane-jar-read-only
   source-pane/row-source-pane-missing-entry
   source-pane/row-source-pane-line-moved
   source-pane/row-source-pane-history
   source-pane/row-source-pane-back-deleted-file
   source-pane/row-source-pane-same-file-no-reload])
