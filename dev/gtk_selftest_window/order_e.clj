(ns gtk-selftest-window.order-e
  "Rows 443 to 555 of the window self-test, in the order they run.
  dev/gtk_selftest_window.clj joins the five parts order-a to order-e
  into its row list."
  (:require [gtk-selftest-window.app-open :as app-open]
            [gtk-selftest-window.file-tree :as file-tree]
            [gtk-selftest-window.file-tree-pane :as file-tree-pane]
            [gtk-selftest-window.mcp :as mcp]
            [gtk-selftest-window.parinfer :as parinfer-rows]
            [gtk-selftest-window.prefs :as prefs-rows]
            [gtk-selftest-window.probe :as probe-rows]
            [gtk-selftest-window.session-tabs :as session-tabs]
            [gtk-selftest-window.source-pane :as source-pane]
            [gtk-selftest-window.tab-widget :as tab-widget]
            [gtk-selftest-window.tabs :as tabs]
            [gtk-selftest-window.window-keys :as window-keys]))

(def rows
  [source-pane/row-source-pane-wiring
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
   file-tree-pane/row-tree-pane-menu-items
   file-tree-pane/row-tree-pane-new-file-flow
   file-tree-pane/row-tree-pane-rename-prefill
   file-tree-pane/row-tree-pane-callback-error-shown
   file-tree-pane/row-tree-pane-menu-target-survives-refresh
   file-tree-pane/row-tree-pane-keys
   file-tree-pane/row-tree-pane-stale-menu-actions
   file-tree-pane/row-tree-pane-menu-focus-and-anchor
   file-tree-pane/row-tree-pane-error-row-no-menu
   file-tree-pane/row-tree-pane-header-buttons
   window-keys/row-cursor-listener-throw-others-still-run
   window-keys/row-window-key-throw-contained
   tabs/row-tab-new-shows-strip
   tabs/row-tab-open-replaces-untouched
   tabs/row-tab-open-focuses-existing
   tabs/row-tab-switch-keys
   tabs/row-tab-dirty-label
   tabs/row-tab-close-alert-dismissed
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
   tabs/row-tab-switch-recent
   file-tree/row-tree-toggle-chord
   file-tree/row-tree-chord-not-run-selection
   file-tree/row-tree-button-toggles
   file-tree/row-tree-open-and-focus
   file-tree/row-tree-highlight-follows-tab
   file-tree/row-tree-save-shows-file
   file-tree/row-tree-settings-persist
   file-tree/row-tree-follows-theme
   file-tree/row-tree-escape-returns-focus
   file-tree/row-tree-new-file-opens-tab
   file-tree/row-tree-new-folder-selects
   file-tree/row-tree-rename-open-file
   file-tree/row-tree-rename-folder-retargets
   file-tree/row-tree-rename-taken
   file-tree/row-tree-trash-cancel
   file-tree/row-tree-trash-open-file
   file-tree/row-tree-trash-folder-dirty-tab
   file-tree/row-tree-trash-symlink
   file-tree/row-tree-new-dotfile-hidden
   file-tree/row-tree-window-keys-reach-tree
   file-tree/row-tree-rename-retargets-case-spelling
   file-tree/row-tree-new-file-stale-tab
   file-tree/row-tree-rename-stale-tab
   file-tree/row-tree-rename-keeps-expanded
   app-open/row-app-open-two-files
   app-open/row-app-open-folder
   app-open/row-app-open-missing
   app-open/row-app-open-already-open
   app-open/row-app-open-non-local
   session-tabs/row-session-save-restore
   session-tabs/row-session-untitled-not-saved
   session-tabs/row-session-missing-file-named
   session-tabs/row-session-restore-no-duplicates
   session-tabs/row-session-restore-symlinked-root
   session-tabs/row-session-off
   session-tabs/row-session-bad-file
   session-tabs/row-session-throwing-file-skipped
   session-tabs/row-session-saved-live
   ;; MCP hooks (mcp2)
   mcp/row-mcp-eval-done
   mcp/row-mcp-eval-busy
   mcp/row-mcp-run-done
   mcp/row-mcp-run-problem
   mcp/row-mcp-run-refused
   mcp/row-mcp-run-selection-done
   mcp/row-mcp-tools-open-read-select
   mcp/row-mcp-tools-replace-save
   mcp/row-mcp-tools-replace-nul
   mcp/row-mcp-tools-run-eval-console
   mcp/row-mcp-tools-run-selection
   mcp/row-mcp-tools-timeout-stop
   mcp/row-mcp-tools-waiting-input
   mcp/row-mcp-tools-doc-complete-brackets-project
   ;; MCP listener (mcp3)
   mcp/row-mcp-listen-serves-and-registers
   mcp/row-mcp-listen-detach-leaves-window
   mcp/row-mcp-listen-stop-cleans
   ;; The parinfer rows open files from their own temp projects, which
   ;; re-roots the window's one project, so they run after every row
   ;; that Runs, beside the dialects rows that do the same.
   parinfer-rows/row-parinfer-project-on
   parinfer-rows/row-parinfer-project-pins-off
   parinfer-rows/row-parinfer-label-follows-tab
   parinfer-rows/row-parinfer-label-after-open-into-untitled
   parinfer-rows/row-format-style-project-pin
   parinfer-rows/row-parinfer-mode-change-clears-skip-note
   prefs-rows/row-prefs-records-a-key
   prefs-rows/row-prefs-asks-before-taking-a-key
   probe-rows/row-probe-part
   window-keys/row-command-chord-close])
