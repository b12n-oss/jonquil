(ns jonquil.gtk.window.ui
  "The window's ui map, the one activate! hands its on-ready callback: the window's widgets and the fns its buttons, keys and menus call, for the self-test rows, the docs shots and the MCP server (jonquil.gtk.window/start!'s docstring names the keys). ui-map builds it from activate!'s bindings, one map per topic, merged with the parts' :ui keys. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window's activate! unchanged."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.file-tree-pane :as file-tree-pane]
            [jonquil.gtk.window.alerts :as win-alerts]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.quit :as win-quit]
            [jonquil.gtk.window.reopen :as win-reopen]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.gtk.window.theme :as win-theme]
            [jonquil.gtk.window.tree-ops :as win-tree-ops]
            [jonquil.parts :as parts]))

(defn- window-keys
  "The ui map's window keys: the first tab's editor, the console, the session, the window and its notebook and tabs, the app, the status bar's labels, the evaluator and dialect selection, Reset on Run, the find bar, the palette and its context keys, the window key handler and the toolbar's buttons."
  [{:keys [ed cv session status-label win notebook tabs app active-tab parinfer-label parinfer-fn line-col-label ev* sel* dialect-label reset-chk fb window-key-args palette hint-label unread-label window-key-fn run-btn stop-btn open-btn save-btn]}]
  {:editor ed
   :console cv
   :session session
   :status-label status-label
   :window win
   ;; The notebook, the tab list and the active tab, read live.
   :notebook notebook
   :tabs* (fn [] @tabs)
   :app app
   :active-tab* active-tab
   :parinfer-label parinfer-label
   :parinfer!* parinfer-fn
   :line-col-label line-col-label
   ;; The window's evaluator and dialect selection, read live
   ;; at call time (ev* is swapped on a runtime switch), and
   ;; the dialect label itself.
   :evaluator* (fn [] @ev*)
   :selection* (fn [] @sel*)
   :dialect-label dialect-label
   ;; The Reset on Run checkbox, for rows that read its
   ;; sensitivity and tooltip.
   :reset-check reset-chk
   :find-bar fb
   ;; the palette's window context keys, the parts' :fns merged in
   :palette-ctx-keys (set (keys window-key-args))
   ;; The first tab's command palette, and what
   ;; Cmd/Ctrl+Shift+P calls to open it
   :palette palette
   :hint-label hint-label
   :unread-label unread-label
   ;; Self-test hook: the exact fn the "key-pressed" signal
   ;; calls, so a row can invoke it directly with a synthetic
   ;; keyval/state -- an ordinary function call, not a
   ;; synthesized OS keystroke.
   :handle-window-key! window-key-fn
   ;; The toolbar's own buttons, so a row can fire their real
   ;; "clicked" handlers (g/g-signal-emit-by-name).
   :toolbar {:run run-btn :stop stop-btn :open open-btn :save save-btn}})

(defn- tree-keys
  "The ui map's file tree keys: the tree, its toggle and outer paned, Open Folder's result, the tree's menu and the trash alert's decision, and the window's open-paths fn."
  [{:keys [tree-pane files-btn tree-paned h-paned current-open-paths] *folder-result :folder-result* *tab-ctx :tab-ctx*}]
   ;; The tree, its toggle, Open Folder's result handler (for
   ;; rows), and the outer paned.
  {:file-tree tree-pane
   :files-btn files-btn
   :tree-paned tree-paned
   :h-paned h-paned
   :toggle-file-tree!* (fn [] (g/gtk-toggle-button-set-active
                               files-btn (if (zero? (g/gtk-toggle-button-get-active files-btn)) 1 0)))
   :file-tree-visible?* (fn [] (pos? (g/gtk-widget-get-visible (:root tree-pane))))
   :simulate-folder-result! (fn [r] (@*folder-result r))
   ;; The tree's menu, as a right click, a
   ;; button and Return in the name entry drive it, and the
   ;; trash alert's decision with a synthetic {:index n}, as
   ;; :close-choice! below.
   :tree-menu!* (fn [path] (file-tree-pane/open-menu! tree-pane path))
   :tree-press!* (fn [label] (file-tree-pane/press-item! tree-pane label))
   :tree-submit!* (fn [text] (file-tree-pane/submit-name! tree-pane text))
   :tree-menu-state* (fn [] (file-tree-pane/menu-state tree-pane))
   :trash-choice! (fn [path result] (win-tree-ops/trash-choice! @*tab-ctx path result))
   :open-paths! (fn [paths] (@current-open-paths paths))})

(defn- tab-keys
  "The ui map's tab and file keys: new, open, select and close a tab, open and save the active tab's file, and the Open and Save dialogs' results."
  [{:keys [active-tab cv win] *tab-ctx :tab-ctx*}]
   ;; The tab operations, exactly what the keys, the toolbar
   ;; and the tabs' close buttons call.
  {:new-tab!* (fn [] (win-tabs/new-tab! @*tab-ctx {}))
   :open-file! (fn [p] (win-tabs/open-file! @*tab-ctx p))
   :select-tab! (fn [id] (win-tabs/select-tab! @*tab-ctx id))
   :close-tab! (fn [id] (win-tabs/close-tab! @*tab-ctx id))
   :close-tab-choice! (fn [id result] (win-tabs/close-tab-choice! @*tab-ctx id result))
   :open-path! (fn [p]
                 (let [{ed :ed path :path :keys [discard-warned?]} (active-tab)]
                   (win-files/guarded-open! ed cv discard-warned?
                                            (fn [] (win-files/open-path! ed cv path p) (win-files/update-title! ed win path)))))
   :save-to! (fn [p] (let [{ed :ed path :path} (active-tab)]
                       (win-files/save-to! ed cv path p) (win-files/update-title! ed win path)))
   ;; Self-test hook: the exact dialog-result decisions,
   ;; driven with a synthetic result map shaped like
   ;; g/finish-file's -- file-dialog! itself can't be driven
   ;; without a live dialog. A simulated Open loads into the
   ;; active tab, as :open-path! does.
   :simulate-open-result! (fn [result]
                            (win-files/contained-open-result!
                             {:cv cv
                              :open! (fn [p] (let [t (active-tab)]
                                               (win-files/open-path! (:ed t) cv (:path t) p)
                                               (win-files/update-title! (:ed t) win (:path t))))}
                             result))
   :simulate-save-result! (fn [result] (let [t (active-tab)]
                                         (win-files/contained-save-result! {:ed (:ed t) :cv cv :win win :path-atom (:path t)} result)))})

(defn- run-keys
  "The ui map's run keys: Run and Stop, the MCP server's notifying runs, Run Selection, Run Form, the focus toggle and the console's two clears."
  [{:keys [run-fn stop-fn active-tab cv ev* sel* status-label run-selection-fn actions toggle-focus-fn clear-console-fn clear-last-fn]}]
  {:run!* run-fn
   :stop!* stop-fn
   ;; Notifying hooks for the MCP server, each calls
   ;; notify once and returns an output reader.
   :mcp-run!* (fn [notify]
                (win-run/do-run! {:tab (active-tab) :cv cv :ev* ev* :sel* sel*
                                  :status-label status-label}
                                 notify))
   :mcp-eval!* (fn [text notify]
                 (let [t (active-tab)]
                   (win-run/mcp-eval! {:tab t :cv cv :ev* ev* :status-label status-label}
                                      text {:hook-source :repl :hook-tab (:id t)} notify)))
   :mcp-run-selection!* (fn [start end notify]
                          (win-run/mcp-run-selection! {:tab (active-tab) :cv cv :ev* ev* :sel* sel*
                                                       :status-label status-label}
                                                      start end notify))
   ;; Self-test hooks: the exact fns the F6/Cmd+E/Cmd+L/
   ;; zoom key chords call, so rows can drive them directly.
   :run-selection!* run-selection-fn
   ;; Run Form's own fn, the one Cmd/Ctrl+Enter and the menu
   ;; item call.
   :run-form!* (:run-form! actions)
   :toggle-focus!* toggle-focus-fn
   :clear-console!* clear-console-fn
   ;; Cmd/Ctrl+Shift+L's own fn, the one that clears only the
   ;; last entry's block -- same self-test-hook shape as
   ;; :clear-console!* just above.
   :clear-last-output!* clear-last-fn})

(defn- theme-keys
  "The ui map's theme and settings keys: zoom, the theme and its auto appearance poll, the code CSS, the theme chord, and the window's settings path."
  [{:keys [zoom-fn set-theme-fn theme-name theme-choice system-dark-fn* theme-ctx arm-count poll-armed? provider cycle-theme-fn settings-path*]}]
  {:zoom!* zoom-fn
   ;; Self-test hooks: applies a theme (never saves), reads
   ;; back the active theme name, and
   ;; reads back the shared CSS provider's OWN serialisation
   ;; (g/css-provider-string asks the provider itself, so a
   ;; row can't read a copy that drifted from it).
   :set-theme!* set-theme-fn
   :theme-name* (fn [] @theme-name)
   ;; Auto theme: the choice (:auto or a theme key), repoints
   ;; the system-appearance fn, runs one poll tick by hand
   ;; (returns its int) and reads how many poll timers were armed.
   :theme-choice* (fn [] @theme-choice)
   :system-dark-fn!* (fn [f] (reset! system-dark-fn* f))
   :poll-appearance!* (fn [] (win-theme/poll-tick! theme-ctx))
   :poll-arm-count* (fn [] @arm-count)
   :poll-armed?* (fn [] @poll-armed?)
   :code-css* (fn [] (g/css-provider-string provider))
   ;; The chord's own action, the settings path this
   ;; window reads/writes, and the exact startup-load fn
   ;; activate! itself just ran (so a row can drive the same
   ;; path against a synthetic settings file).
   :cycle-theme!* cycle-theme-fn
   :settings-path* (fn [] @settings-path*)
   :load-settings!* (fn [p] (win-theme/load-and-apply-theme! theme-ctx p))
   ;; Self-test hook only (row-theme-save-failure-reported):
   ;; repoints the window's own settings path so a
   ;; save can be forced to fail without touching the real
   ;; settings file or racing the filesystem.
   :settings-path!* (fn [p] (reset! settings-path* p))})

(defn- session-keys
  "The ui map's session keys: save and restore the session, and live saving on or off."
  [{*tab-ctx :tab-ctx*}]
   ;; What the real quit saves and
   ;; what a launch with no file restores (without re-rooting).
  {:save-session!* (fn [] (win-session/save-session! @*tab-ctx))
   :restore-session!* (fn [] (win-reopen/restore-session! @*tab-ctx {:reroot? false}))
   ;; Live saving on or off, so a row can stand in for a quit
   ;; and a new launch (off until that launch's restore).
   :session-live!* (fn [on?] (reset! (:session-live? @*tab-ctx) on?))})

(defn- help-keys
  "The ui map's help keys: the popover registry, quick doc, jump, rename, the two context menus' item firing, and completion in the editor and the console."
  [{:keys [tabs close-popovers-fn register-popover-fn actions help-pop full-doc-pop rename-pop repl-activate-menu-action-fn completion repl-complete-fn repl-completion]}]
  (let [;; The editor's and the console's own context menus -- each item
    ;; calls the EXACT SAME fn its key does, through its command in
    ;; jonquil.command-registry. install-context-menu! returns the GSimpleActionGroup, so :activate-
    ;; menu-action!*/:repl-activate-menu-action!* (below) can fire an
    ;; item directly, by name, for rows and the smoke.
    ;; The ui's editor-menu hook fires the FIRST tab's menu
    ;; (tabs are built after activate!'s let), as the other per-tab ui keys do.
        activate-menu-action-fn (fn [name] (g/g-action-group-activate-action (:menu-group (first @tabs)) name ffi/null))]
   ;; The window's own popover registry -- close-popovers-fn
   ;; is exactly what the keymap's Escape binding calls;
   ;; register-popover-fn is how a popover (a self-test row's
   ;; own test popover, completion/quick-doc/rename) joins
   ;; that registry.
    {:close-popovers!* close-popovers-fn
     :register-popover!* register-popover-fn
     ;; The ui's own quick-doc/jump actions -- the exact
     ;; fns editor.clj's own Ctrl+Q/Shift+F1/F12/Ctrl+B clauses
     ;; call (set-quick-doc-fn!/set-jump-fn!, above) -- and the
     ;; quick-doc popover itself, for rows to read back via
     ;; jonquil.gtk.popover/open? and help-popover/quick-doc-text.
     :quick-doc!* (:quick-doc! actions)
     :jump!* (:jump! actions)
     :help-popover help-pop
     ;; The full-doc popover a second quick-doc press
     ;; over the same target shows in -- rows read
     ;; it back via jonquil.gtk.popover/open? and help-popover/
     ;; full-doc-text, same as :help-popover above.
     :full-doc-popover full-doc-pop
     ;; The ui's own rename actions -- rename!* is the
     ;; exact fn editor.clj's own Shift+F6/F2 clauses call
     ;; (set-rename-fn!, above); rename-to!* is the exact fn
     ;; the popover entry's own "activate" signal calls, for
     ;; rows to drive without a synthesized keystroke; and the
     ;; rename popover itself, for rows to read back via
     ;; jonquil.gtk.popover/open? and help-popover/rename-
     ;; entry-text.
     :rename!* (:rename! actions)
     :rename-to!* (:rename-to! actions)
     :rename-popover rename-pop
     ;; Fires a context-menu item by its own unprefixed
     ;; action name (\"jump\", \"rename\", \"toggle-comment\",
     ;; ...) through g_action_group_activate_action on the
     ;; editor's/the console's own GSimpleActionGroup --
     ;; install-context-menu!'s own docstring on why that's
     ;; equivalent to activating the prefixed \"p4.<name>\".
     :activate-menu-action!* activate-menu-action-fn
     :repl-activate-menu-action!* repl-activate-menu-action-fn
     ;; The ui's own completion action -- the exact fn
     ;; editor.clj's own Ctrl+Space clause calls -- and the
     ;; completion popover itself, for rows to read back via
     ;; jonquil.gtk.popover/open? and jonquil.gtk.completion/
     ;; state.
     :complete!* (:complete! actions)
     :completion completion
     ;; The ui's own console-facing completion action --
     ;; the exact fn console-view's own Ctrl+Space clause calls
     ;; -- and the console's completion popover itself, same
     ;; reasoning as :complete!*/:completion above.
     :repl-complete!* repl-complete-fn
     :repl-completion repl-completion}))

(defn- panel-keys
  "The ui map's right-hand slot keys: the inspector and its actions, the source panel, the slot, the review and plugin panels, the parts' owners, the recent panels and panel focus, and the source panel's actions."
  [{:keys [pane inspect-last-fn inspect-form-fn inspector-state inspector-last-args sp slot rp pending-label lp part-contribution right panel-focus-fn on-inspector-close hp source-jump-fn source-open-fn source-back-fn jump-back-fn source-close-fn]}]
   ;; The inspector panel map (make-pane's return, including
   ;; its own :on-def -- rows drive the def prompt through it
   ;; directly), the window's own Cmd/Ctrl+I and
   ;; Cmd/Ctrl+Shift+I actions (also what the two editor-menu
   ;; items call), a reader for the window's own
   ;; inspector-state atom, and a self-test-only hook naming
   ;; the args of the last inspect! request this window
   ;; actually sent (row-inspect-cols-match compares a
   ;; :start's own :cols against the pane's own measure).
  {:inspector pane
   :inspect-last!* inspect-last-fn
   :inspect-form!* inspect-form-fn
   :inspector-state* (fn [] @inspector-state)
   :inspector-last-args* (fn [] @inspector-last-args)
   ;; The jonquil.gtk.source-pane map, the
   ;; GtkBox holding it and the inspector in the right-hand
   ;; slot, and the window's own F12-inside/Open-in-window/
   ;; back/close actions -- exactly what the panel's own keys
   ;; and buttons call.
   :source-pane sp
   :right-slot slot
   ;; The review panel and the status bar's pending count
   :review-pane rp
   :pending-label pending-label
   ;; The plugin list
   :plugin-list-pane lp
   :part-owners* (fn [] (:owners part-contribution))
   ;; the recent panels atom, most recent first, and the
   ;; panel keys' and Switch to panel's fn
   :recent-panels* (fn [] (:recent-panels right))
   :panel-focus!* panel-focus-fn
   ;; the inspector's own Close
   :close-inspector!* on-inspector-close
   ;; The plugin history
   :plugin-history-pane hp
   :source-jump!* source-jump-fn
   :source-open!* source-open-fn
   :source-back!* source-back-fn
   :jump-back!* jump-back-fn
   :source-close!* source-close-fn})

(defn- close-keys
  "The ui map's close keys: the close alert's choice, the quit action, the close request and its self-test flags, and the alert dialogs' self-test hooks."
  [{:keys [tabs cv win confirmed-close? app closing? suppress-close?] *tab-ctx :tab-ctx*}]
   ;; close-choice! is the exact fn the real alert
   ;; dialog's own completion callback calls; request-close!*
   ;; runs gtk_window_close, the same path the window's close
   ;; button/OS chrome uses. closing?/suppress-close? are
   ;; self-test-only hooks (close-request-fn's docstring).
  {:close-choice! (fn [result]
                    (win-quit/close-choice! {:dirty-tabs (filterv (fn [t] (editor/dirty? (:ed t))) @tabs)
                                             :cv cv :win win :confirmed-close? confirmed-close?
                                             :select! (fn [t] (win-tabs/select-tab! @*tab-ctx (:id t)))}
                                            result))
   ;; Activates the application's "quit"
   ;; action exactly as GTK's macOS delegate does on a Quit
   ;; AppleEvent.
   :activate-quit-action!* (fn [] (g/g-action-group-activate-action app "quit" ffi/null))
   :request-close!* (fn [] (g/gtk-window-close win))
   :closing? closing?
   :suppress-close? suppress-close?
   ;; Self-test hooks only: drive complete-alert!'s
   ;; per-dialog routing directly, with synthetic, non-GTK
   ;; "dialog" identities.
   :alert-dialog-count win-alerts/alert-dialog-count
   :alert-pending? win-alerts/alert-pending?
   ;; the self-test closes the alerts it answered in code
   :dismiss-alerts!* win-alerts/dismiss-pending-alerts!
   :pending-alerts* (fn [] (count @win-alerts/alert-cancellables))
   :alert-release! win-alerts/alert-release!
   :register-alert-choice-for-test! win-alerts/register-alert-choice!
   :complete-alert-for-test! win-alerts/complete-alert!})

(defn ui-map
  "The ui map activate! hands on-ready: the topic maps above, merged (their keys don't overlap, which ui-test pins), then the parts' :ui keys (jonquil.parts/merge-with-core). w holds activate!'s bindings the topics read, under activate!'s names, with :tab-ctx* and :folder-result* (the forward atoms) and :current-open-paths (jonquil.gtk.window's atom); the first tab's eight objects are read from :tabs here."
  [{:keys [tabs part-contribution] :as w}]
  ;; The per-tab keys in the maps above are the FIRST tab's objects; rows that
  ;; need another tab use :tabs* and :active-tab*. Actions that act on
  ;; "the window's file" (:open-path!, :save-to!, the simulated dialog
  ;; results, :close-choice!) use the active tab.
  (let [{:keys [ed fb help-pop full-doc-pop rename-pop completion palette actions]} (first @tabs)
        u (assoc w :ed ed :fb fb :help-pop help-pop :full-doc-pop full-doc-pop :rename-pop rename-pop
                 :completion completion :palette palette :actions actions)]
    (parts/merge-with-core :ui (merge (window-keys u) (tree-keys u) (tab-keys u) (run-keys u) (theme-keys u)
                                      (session-keys u) (help-keys u) (panel-keys u) (close-keys u))
                           part-contribution)))
