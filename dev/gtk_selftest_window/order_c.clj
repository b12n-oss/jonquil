(ns gtk-selftest-window.order-c
  "Rows 222 to 332 of the window self-test, in the order they run.
  dev/gtk_selftest_window.clj joins the five parts order-a to order-e
  into its row list."
  (:require [gtk-selftest-window.dialects :as dialects]
            [gtk-selftest-window.inspector :as inspector]
            [gtk-selftest-window.no-plugins :as no-plugins-rows]
            [gtk-selftest-window.panels :as panel-rows]
            [gtk-selftest-window.plugin-history :as plugin-history-rows]
            [gtk-selftest-window.plugin-list :as plugin-list-rows]
            [gtk-selftest-window.plugin-mcp :as plugin-mcp-rows]
            [gtk-selftest-window.plugins :as plugin-rows]
            [gtk-selftest-window.results :as results-rows]
            [gtk-selftest-window.review :as review-rows]
            [gtk-selftest-window.trace :as trace-rows]))

(def rows
  [plugin-rows/row-plugin-in-palette
   plugin-rows/row-plugin-broken-file-named
   plugin-rows/row-plugin-edit-throw-contained
   plugin-rows/row-plugin-reload-picks-up-a-change
   plugin-rows/row-plugin-reload-failure-keeps-old
   plugin-rows/row-plugin-save-reloads
   plugin-rows/row-plugin-removed-file
   plugin-rows/row-plugin-run-prints
   plugin-rows/row-plugin-run-replaces-text
   plugin-rows/row-plugin-replace-text-refuses-nul
   plugin-rows/row-plugin-run-closed-tab
   plugin-rows/row-plugin-eval-quiet
   plugin-rows/row-plugin-repl-visible
   plugin-rows/row-plugin-eval-timeout
   plugin-rows/row-plugin-hook-after-save
   plugin-rows/row-plugin-hook-after-run
   plugin-rows/row-plugin-hook-on-error
   plugin-rows/row-plugin-hook-repl-no-loop
   plugin-rows/row-plugin-before-run-abort
   plugin-rows/row-plugin-before-run-form
   plugin-rows/row-plugin-before-run-overrun
   review-rows/row-review-opens-pending
   review-rows/row-review-next-cycles
   review-rows/row-review-based-on-live-moved
   review-rows/row-review-none-to-review
   review-rows/row-review-accept-goes-live
   review-rows/row-review-reject-keeps-note
   review-rows/row-review-refused-stays-pending
   review-rows/row-review-superseded-while-shown
   review-rows/row-review-failed-accept-says-why
   review-rows/row-review-step-keeps-shown-and-note
   plugin-mcp-rows/row-plugin-mcp-propose-pending
   plugin-mcp-rows/row-plugin-mcp-propose-rejected-by-checks
   plugin-mcp-rows/row-plugin-mcp-propose-refused-without-check
   plugin-mcp-rows/row-plugin-mcp-status-after-reject
   plugin-mcp-rows/row-plugin-mcp-supersede-while-shown-needs-a-second-accept
   plugin-mcp-rows/row-plugin-mcp-tool-listed-and-called
   plugin-mcp-rows/row-plugin-mcp-tool-gone-after-reload
   plugin-mcp-rows/row-plugin-mcp-tool-reads-the-buffer
   plugin-mcp-rows/row-plugin-mcp-list-changed-after-reload
   plugin-list-rows/row-plugin-list-shows-each-plugin
   plugin-list-rows/row-plugin-list-disable-takes-the-key
   plugin-list-rows/row-plugin-list-enable-brings-it-back
   plugin-list-rows/row-plugin-list-broken-plugin-can-be-disabled
   plugin-list-rows/row-plugin-list-keeps-trusted-projects
   plugin-list-rows/row-plugin-list-open-opens-its-file
   plugin-list-rows/row-plugin-list-review-opens-the-review
   plugin-list-rows/row-plugin-list-empty
   plugin-list-rows/row-plugin-list-refreshes-after-a-step
   plugin-history-rows/row-plugin-history-shows-each-version
   plugin-history-rows/row-plugin-history-revert-brings-back-v1
   plugin-history-rows/row-plugin-history-revert-keeps-the-reason-at-the-click
   plugin-history-rows/row-plugin-history-revert-of-live-says-already
   plugin-history-rows/row-plugin-history-revert-over-a-broken-edit
   plugin-history-rows/row-plugin-history-revert-restores-a-deleted-file
   plugin-history-rows/row-plugin-history-pending-isnt-reverted
   plugin-history-rows/row-plugin-history-unreadable
   plugin-history-rows/row-plugin-history-back-shows-the-list
   plugin-history-rows/row-plugin-history-refreshes-after-a-step
   plugin-history-rows/row-plugin-tab-follows-a-revert
   plugin-history-rows/row-plugin-tab-keeps-edits-on-a-revert
   plugin-history-rows/row-plugin-tab-follows-an-accept
   plugin-history-rows/row-plugin-tab-untouched-by-a-refused-revert
   plugin-history-rows/row-plugin-tab-at-a-link-target-follows-a-revert
   no-plugins-rows/row-no-plugins-reload-refuses
   no-plugins-rows/row-no-plugins-disable-refuses
   no-plugins-rows/row-no-plugins-revert-refuses
   no-plugins-rows/row-no-plugins-accept-refuses
   no-plugins-rows/row-plugin-list-shows-file-deleted
   dialects/row-dialect-label-default
   dialects/row-evaluator-is-the-session
   dialects/row-project-config-malformed-warns
   dialects/row-project-config-unknown-runtime-warns
   dialects/row-project-open-keeps-evaluator
   dialects/row-editor-paints-profile-sets
   dialects/row-selection-throw-still-paints-new-profile
   inspector/row-pane-renders-view
   inspector/row-pane-keys-step
   inspector/row-pane-d-respects-disabled
   inspector/row-pane-leaf
   inspector/row-pane-measure
   inspector/row-pane-ready-wait-survives-measure-throw
   inspector/row-pane-note-clears
   inspector/row-pane-buttons-reach-their-steps
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
   inspector/row-inspect-reopen-reuses-callback
   inspector/row-inspect-form-queues-behind-request
   inspector/row-inspect-def-prompt-escape-safe
   inspector/row-inspect-history-steps
   ;; after the inspector's rows: on a Jolt with jolt.debug this opens the
   ;; inspector on a local, and row-inspect-focus-after-open needs the
   ;; window's first open
   results-rows/row-result-spans
   results-rows/row-result-click-inspects
   results-rows/row-result-click-waits-double-click-time
   results-rows/row-result-verbs
   results-rows/row-result-menu-verbs
   results-rows/row-result-quick-doc
   results-rows/row-result-open-file-line
   results-rows/row-result-right-click-moves-caret
   trace-rows/row-trace-toggle-and-show
   trace-rows/row-trace-throw
   trace-rows/row-trace-not-a-function
   panel-rows/row-panel-switch-and-keys])
