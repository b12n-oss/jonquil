(ns jonquil.gtk.window.context
  "The window's command context: the fns its keys, toolbar and menus call (Open, Save, the inspector's, the focus toggle, the console's clears, Find and Replace), the parts' wctx, the console's context menu and window-key-args. build-key-fns makes activate!'s command fns, build-parts installs the parts, build-console-menu makes the console's menu and build-keys the window's key args and key handler. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window's activate! unchanged; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [jolt.ffi :as ffi]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.parts :as gtk-parts]
            [jonquil.gtk.window.alerts :as win-alerts]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.help :as win-help]
            [jonquil.gtk.window.inspector :as win-inspector]
            [jonquil.gtk.window.keys :as win-keys]
            [jonquil.gtk.window.plugin-agent :as win-plugin-agent]
            [jonquil.gtk.window.right-slot :as win-right-slot]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.parts :as parts]))

(defn- inspector-key-fns
  "The inspector's window command fns: the Inspect Last context, Inspect
  Last, Inspect Form and Close. w is build-key-fns's. Returns a map of
  :inspect-last-ctx :inspect-last-fn :inspect-form-fn and
  :close-inspector-fn."
  [{:keys [cv active-tab ev* sel* status-label right pane inspector-state inspecting? inspect-queued
           inspector-last-args on-view]}]
  (let [inspect-last-ctx {:ev* ev* :right right :pane pane :inspector-state inspector-state
                          :inspecting? inspecting? :inspector-last-args inspector-last-args
                          :on-view on-view :status-label status-label :start [:start {}]}
        inspect-last-fn (fn [] (win-inspector/inspect-last! inspect-last-ctx))
        inspect-form-fn (fn [] (win-inspector/inspect-form! {:tab (active-tab) :cv cv :ev* ev* :sel* sel* :status-label status-label
                                                             :inspecting? inspecting? :inspect-queued inspect-queued
                                                             :inspect-last-fn inspect-last-fn}))
        close-inspector-fn (fn [] (win-inspector/close-inspector-panel! right (:ed (active-tab))))]
    {:inspect-last-ctx inspect-last-ctx :inspect-last-fn inspect-last-fn
     :inspect-form-fn inspect-form-fn :close-inspector-fn close-inspector-fn}))

(defn build-key-fns
  "activate!'s window command fns, in activate!'s order: Open, Save and
  Save As for the active tab, the inspector's context with Inspect Last
  and Inspect Form, the inspector's Close, the focus toggle, the
  console's two clears, and Find and Replace. w holds what they read:
  :cv :win :active-tab :ev* :sel* :status-label, the inspector's :right
  :pane :inspector-state :inspecting? :inspect-queued
  :inspector-last-args :on-view, and :tab-ctx* (activate!'s *tab-ctx,
  read when an Open result opens a file). Returns w with :open-fn
  :save-fn :save-as-fn :inspect-last-ctx :inspect-last-fn
  :inspect-form-fn :close-inspector-fn :toggle-focus-fn
  :clear-console-fn :clear-last-fn :open-find-fn and :open-replace-fn
  added."
  [{:keys [cv win active-tab] *tab-ctx :tab-ctx* :as w}]
  (let [open-fn (fn [] (win-files/do-open! cv win (fn [p] (win-tabs/open-file! @*tab-ctx p))))
        save-fn (fn [] (let [t (active-tab)] (win-files/do-save! (:ed t) cv win (:path t))))
        save-as-fn (fn [] (let [t (active-tab)] (win-files/do-save-as! (:ed t) cv win (:path t))))
        toggle-focus-fn (fn [] (win-keys/do-toggle-focus! win (:ed (active-tab)) cv))
        clear-console-fn (fn [] (win-keys/do-clear-console! cv))
        clear-last-fn (fn [] (win-keys/do-clear-last-output! cv))
        open-find-fn (fn [] (find-bar/open! (:fb (active-tab)) :find))
        open-replace-fn (fn [] (find-bar/open! (:fb (active-tab)) :replace))]
    (assoc (merge w (inspector-key-fns w))
           :open-fn open-fn :save-fn save-fn :save-as-fn save-as-fn
           :toggle-focus-fn toggle-focus-fn :clear-console-fn clear-console-fn :clear-last-fn clear-last-fn
           :open-find-fn open-find-fn :open-replace-fn open-replace-fn)))

(defn- window-wctx
  "The part of the parts' wctx about the window itself: its widgets, the
  evaluator and selection, the tabs and their operations, and the status
  line fns. w is build-parts's."
  [{:keys [win status-label cv ev* sel* tabs active-tab settings-path* bang* recent-tabs] *tab-ctx :tab-ctx*}]
  {:win win :status-label status-label :cv cv :ev* ev* :sel* sel*
   :tabs tabs :active-tab active-tab :settings-path* settings-path*
   :tab-ctx* *tab-ctx
   :tab-by-id win-tabs/tab-by-id :select-tab! win-tabs/select-tab! :new-tab! win-tabs/new-tab!
   :bang* bang* :recent-tabs recent-tabs
   :contain-click (fn [where f] (win-status/contain-click status-label where f))
   :set-status! (fn [text] (win-status/set-status! status-label text))
   :register-status-log! (fn [log!] (win-status/register-status-log! status-label log!))
   :open-file! (fn [p] (win-tabs/open-file! @*tab-ctx p))
   :refresh-labels! (fn [] (win-tabs/refresh-labels! @*tab-ctx))})

(defn- panel-wctx
  "The part of the parts' wctx about the panes: the tree, the right-hand
  slot and its panels, alerts, and the run fns. w is build-parts's."
  [{:keys [right tree-pane tree-paned paned files-btn panel-focus-fn unread-label pending-label]}]
  {:right right :tree-pane tree-pane :tree-paned tree-paned :paned paned
   :files-btn files-btn :panel-focus-fn panel-focus-fn
   :showing-panel win-right-slot/showing-panel :show-right-panel! win-right-slot/show-right-panel!
   :hide-right-panel! win-right-slot/hide-right-panel!
   :alert-pending? win-alerts/alert-pending? :show-alert! win-alerts/show-alert!
   :make-eval-opts win-run/make-eval-opts :refuse-foreign! win-run/refuse-foreign!
   :mcp-eval! win-run/mcp-eval!
   :unread-label unread-label :pending-label pending-label})

(defn- tooling-wctx
  "The part of the parts' wctx about plugins, the inspector and the help
  and source fns. w is build-parts's."
  [{:keys [cv ev* sel* status-label plugins-on* rp lp hp inspect-last-ctx source-ctx]}]
  {:plugin-step! win-plugin-agent/plugin-step!
   :plugins-on* plugins-on* :review-pane rp
   :plugin-list-pane lp :plugin-history-pane hp
   :plugin-history-shown win-plugin-agent/plugin-history-shown
   :plugin-reviews-changed win-plugin-agent/plugin-reviews-changed
   :plugin-console win-plugin-agent/plugin-console
   :on-saved win-files/on-saved
   :saved-listeners win-files/saved-listeners
   :inspect! (fn [start]
               (win-inspector/inspect-last! (assoc inspect-last-ctx :start start)))
   :ask-lookup! win-help/ask-lookup! :jump-to! win-help/jump-to!
   :run-example! win-run/do-run-example!
   :open-file-line! win-help/open-file-line!
   :source-ctx source-ctx
   :place-caret-and-scroll! win-help/place-caret-and-scroll! :file-name win-files/file-name
   :open-in-tab! win-help/open-in-tab! :show-source! win-help/show-source!
   ;; LM5's Live at once: a toggle sends its form again
   :run-ranges! (fn [t ranges then]
                  (win-run/do-run-ranges! {:tab t :cv cv :ev* ev* :sel* sel* :status-label status-label}
                                          ranges then))
   :rerun-failure! (fn [f t]
                     (win-run/rerun-failure! {:failure f :tab t :cv cv :ev* ev* :sel* sel*
                                              :status-label status-label}))})

(defn- parts-wctx
  "The map of core bindings every part's install fn reads. w is
  build-parts's. Returns that map."
  [w]
  (merge (window-wctx w) (panel-wctx w) (tooling-wctx w)))

(defn build-parts
  "activate!'s window parts, in activate!'s order: part-error!, which
  says a part's error on the status line, then every part installed with
  wctx, the map of core bindings each install fn reads, and the
  right-hand slot's part panels set from what they answer. w holds what
  wctx names under activate!'s local names: :win :status-label :cv :ev*
  :sel* :tabs :active-tab :settings-path* :bang* :recent-tabs :right
  :tree-pane :tree-paned :paned :files-btn :panel-focus-fn :unread-label
  :plugins-on* :rp :lp :hp :pending-label :inspect-last-ctx :source-ctx,
  :tab-ctx* (activate!'s *tab-ctx) and :part-panels* (right-slot's
  build). Returns w with :part-error! and :part-contribution added."
  [{:keys [status-label part-panels*] :as w}]
  (let [;; Window parts (jonquil.gtk.parts): each install fn reads the core
      ;; bindings it needs from wctx and answers its :fns, :ui, :hooks and
      ;; :exports. A part's error is said on the status line, not thrown.
        part-error! (fn [id ex]
                      (win-status/set-status! status-label (str "; part " id ": " (or (ex-message ex) (str ex)))))
        part-contribution (parts/install gtk-parts/installs (parts-wctx w)
                                         part-error!
                                         {:specs parts/specs})
        _ (reset! part-panels* (:panels part-contribution))]
    (assoc w :part-error! part-error! :part-contribution part-contribution)))

(defn build-console-menu
  "activate!'s console context menu, in activate!'s order: the menu,
  whose items call the same fns their keys do (Complete, Clear Last and
  the live-results part's six result fns), and the fn that fires one of
  its items by name. w holds what they read: :cv :repl-complete-fn
  :clear-last-fn :part-contribution :status-label. Returns w with
  :repl-activate-menu-action-fn added."
  [{:keys [cv repl-complete-fn clear-last-fn part-contribution status-label] :as w}]
  (let [;; The console's context menu is built here, after the parts, because
      ;; the live-results part answers the six result fns its items call;
      ;; built earlier those keys were nil.
        console-menu-group
        (win-keys/install-context-menu! (:view cv)
                                        (cmd-registry/menu-items cmd-registry/console-menu
                                                                 (merge {:complete! repl-complete-fn :clear-last-fn clear-last-fn}
                                                                        (select-keys (:fns part-contribution)
                                                                                     [:result-inspect-fn :result-copy-fn
                                                                                      :result-define-fn :result-jump-fn :result-doc-fn
                                                                                      :result-open-location-fn])))
                                        (fn [msg] (win-status/set-status! status-label msg)))
        repl-activate-menu-action-fn
        (fn [name] (g/g-action-group-activate-action console-menu-group name ffi/null))]
    (assoc w :repl-activate-menu-action-fn repl-activate-menu-action-fn)))

(defn- tab-key-fns
  "The window key fns about tabs: switching to a palette tab entry, Go
  back, a new tab, and stepping or jumping by digit. w is build-keys's."
  [{:keys [tabs recent-tabs status-label new-tab-fn step-tab-fn digit-tab-fn] *tab-ctx :tab-ctx*}]
  {:switch-tab-fn (fn [id] (when (win-tabs/tab-by-id tabs id) (win-tabs/select-tab! @*tab-ctx id)))
   :tab-back-fn (fn [] (if-let [id (second @recent-tabs)]
                         (win-tabs/select-tab! @*tab-ctx id)
                         (win-status/set-status! status-label "; Only one tab is open.")))
   :new-tab-fn new-tab-fn :step-tab-fn step-tab-fn :digit-tab-fn digit-tab-fn})

(defn- run-key-fns
  "The window key fns about running: Run, Stop, Run Selection, Run
  Changed and the evaluating? read. w is build-keys's."
  [{:keys [run-fn stop-fn run-selection-fn run-changed-fn evaluating-fn]}]
  {:run-fn run-fn :stop-fn stop-fn :run-selection-fn run-selection-fn
   :run-changed-fn run-changed-fn :evaluating? evaluating-fn})

(defn- file-key-fns
  "The window key fns about files and search: Open, Save, Save As, Close,
  Find and Replace. w is build-keys's."
  [{:keys [open-fn save-fn save-as-fn close-fn open-find-fn open-replace-fn]}]
  {:open-fn open-fn :save-fn save-fn :save-as-fn save-as-fn :close-fn close-fn
   :open-find-fn open-find-fn :open-replace-fn open-replace-fn})

(defn- core-key-fns
  "The core fns every window chord and each tab's palette read, from
  build-keys's w. Returns that map, before the parts' :fns are merged in."
  [{:keys [panel-focus-fn hints-toggle-fn status-label toggle-focus-fn clear-console-fn clear-last-fn zoom-fn win
           active-tab cycle-theme-fn choose-theme-fn close-popovers-fn inspect-last-fn inspect-form-fn pane
           close-inspector-fn source-keys parinfer-fn] :as w}]
  (merge
   (tab-key-fns w)
   (run-key-fns w)
   (file-key-fns w)
   {:panel-focus-fn panel-focus-fn
    :hints-toggle-fn hints-toggle-fn
    :toggle-focus-fn toggle-focus-fn
    :clear-console-fn clear-console-fn :clear-last-fn clear-last-fn
    :zoom-fn zoom-fn
    :win win :active-tab active-tab :theme-cycle-fn cycle-theme-fn
    :theme-use-fn choose-theme-fn
    :close-popovers-fn close-popovers-fn
    :inspect-last-fn inspect-last-fn :inspect-form-fn inspect-form-fn
    :pane pane :close-inspector-fn close-inspector-fn
    :status-label status-label :source-keys source-keys
    :parinfer-fn parinfer-fn}))

(defn build-keys
  "activate!'s window keys, in activate!'s order: window-key-args, the
  core fns every window chord and each tab's palette read, with the
  parts' :fns merged in, and window-key-fn, the key controller's
  handler. w holds the fns and values it names, under activate!'s local
  names, :part-contribution, and :tab-ctx* (activate!'s *tab-ctx, read
  when a tab switch runs). Returns w with :window-key-args and
  :window-key-fn added."
  [{:keys [part-contribution] :as w}]
  (let [;; named, so each tab's palette can build the same per-key ctx
      ;; (window-key-ctx) a window chord gets
        window-key-args (parts/merge-with-core :fns (core-key-fns w) part-contribution)
        window-key-fn (win-keys/on-window-key window-key-args)]
    (assoc w :window-key-args window-key-args :window-key-fn window-key-fn)))
