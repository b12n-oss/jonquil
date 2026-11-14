(ns gtk-selftest-window.order-b
  "Rows 112 to 221 of the window self-test, in the order they run.
  dev/gtk_selftest_window.clj joins the five parts order-a to order-e
  into its row list."
  (:require [gtk-selftest-window.completion :as completion]
            [gtk-selftest-window.locations :as locations-rows]
            [gtk-selftest-window.palette :as palette-rows]
            [gtk-selftest-window.plugins :as plugin-rows]
            [gtk-selftest-window.popovers-quickdoc :as popovers-quickdoc]
            [gtk-selftest-window.rename-menus :as rename-menus]
            [gtk-selftest-window.themes :as themes]))

(def rows
  [themes/row-theme-save-failure-reported
   themes/row-theme-switch-while-running
   popovers-quickdoc/row-popover-at-caret
   popovers-quickdoc/row-popover-keys-stay-with-editor
   popovers-quickdoc/row-window-shift-f6-reaches-editor
   popovers-quickdoc/row-window-f6-still-toggles
   popovers-quickdoc/row-window-escape-closes-popover
   popovers-quickdoc/row-popover-dark-colours
   popovers-quickdoc/row-quick-doc-core
   popovers-quickdoc/row-quick-doc-fits-its-text
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
   popovers-quickdoc/row-jump-to-a-run-file
   popovers-quickdoc/row-jump-shadowed-param
   popovers-quickdoc/row-quick-doc-ctrl-shift-f1-does-nothing
   rename-menus/row-rename-ctrl-shift-f6-does-nothing
   popovers-quickdoc/row-quick-doc-follows-scroll
   popovers-quickdoc/row-completion-hides-when-caret-scrolls-away
   completion/row-repl-completion-hides-when-input-scrolls-away
   completion/row-completion-opens
   completion/row-completion-sized-to-rows-and-labelled
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
   rename-menus/row-rename-escape-refocuses-editor
   rename-menus/row-rename-entry-keeps-window-keys
   rename-menus/row-menu-actions
   rename-menus/row-menu-actions-console-complete
   rename-menus/row-menu-action-throw-is-contained
   rename-menus/row-menu-items-all-resolve
   palette-rows/row-palette-chord-opens
   palette-rows/row-palette-filters
   palette-rows/row-palette-runs-editor-command
   palette-rows/row-palette-runs-popover-command
   palette-rows/row-palette-runs-window-command
   palette-rows/row-palette-escape-closes
   palette-rows/row-palette-no-match
   palette-rows/row-palette-throw-contained
   palette-rows/row-palette-arrow-keys
   palette-rows/row-palette-closes-its-own-tab
   palette-rows/row-palette-entry-keeps-window-keys
   palette-rows/row-palette-entry-signals
   palette-rows/row-describe-key-consumes
   palette-rows/row-describe-key-skips-modifier
   palette-rows/row-key-bindings-lists
   palette-rows/row-recent-keys-hides-typing
   palette-rows/row-time-form
   palette-rows/row-time-form-cancel
   palette-rows/row-time-form-dont-ask
   palette-rows/row-time-form-alert-pending
   palette-rows/row-time-form-clears-status
   locations-rows/row-locations-pane-shows
   locations-rows/row-locations-pane-wiring
   locations-rows/row-locations-uses-step
   locations-rows/row-locations-survive-edit
   locations-rows/row-locations-gone
   locations-rows/row-locations-ends
   locations-rows/row-locations-next-from-the-palette-keeps-its-selection
   locations-rows/row-locations-definitions
   locations-rows/row-locations-tab-close-clears
   locations-rows/row-locations-buffer-replaced
   locations-rows/row-locations-matches-empty-find
   locations-rows/row-find-usages-in-project
   locations-rows/row-find-usages-stale-file
   locations-rows/row-find-usages-resolved
   locations-rows/row-locations-empty-says-no-places
   locations-rows/row-compare-with-disk-and-take
   locations-rows/row-compare-this-form-and-take
   locations-rows/row-compare-with-head
   locations-rows/row-compare-line-only-on-disk
   locations-rows/row-compare-with-tab
   locations-rows/row-compare-with-last-run
   plugin-rows/row-plugin-key-runs-edit])
