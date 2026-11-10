(ns jonquil.gtk.window
  "The Jonquil window: a toolbar (Run/Stop/Open/Save/Reset-on-Run) over a
  vertical GtkPaned holding the Definitions editor above the
  Interactions console, over a status label. Wires the window's
  jonquil.evaluator (jonquil.session's in-process record by default) to the
  two views: Run and the REPL both go through it.

  Lock caller rule (jonquil.eval's docstring): on-output,
  on-wait-input, on-status-change and on-complete all run on the eval worker
  thread, never the GTK thread. Every one of them here only enqueues, via
  jonquil.gtk.console-view/append! (safe from any thread) or
  jonquil.gtk.callbacks/on-gui! (also safe from any thread, used to reach
  the GTK thread for the status label and nothing else) -- never a direct
  GTK call, never a block, never an invoke-and-wait. Everything else in this
  namespace (button/key handlers, the file dialogs' async callbacks, the
  dirty-changed callback, the self-test's direct calls) already runs on the
  GTK thread, so it calls GTK directly."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.commands :as commands]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.file-tree :as ft]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.file-tree-pane :as file-tree-pane]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.plugin-history-pane :as plugin-history-pane]
            [jonquil.gtk.plugin-list-pane :as plugin-list-pane]
            [jonquil.gtk.review-pane :as review-pane]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.gtk.parts :as gtk-parts]
            [jonquil.gtk.window.alerts :as win-alerts]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.help :as win-help]
            [jonquil.gtk.window.inspector :as win-inspector]
            [jonquil.gtk.window.keys :as win-keys]
            [jonquil.gtk.window.quit :as win-quit]
            [jonquil.gtk.window.reopen :as win-reopen]
            [jonquil.gtk.window.plugin-agent :as win-plugin-agent]
            [jonquil.gtk.window.right-slot :as win-right-slot]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.runtime :as win-runtime]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.gtk.window.theme :as win-theme]
            [jonquil.gtk.window.tree-ops :as win-tree-ops]
            [jonquil.parts :as parts]
            [jonquil.login-path :as login-path]
            [jonquil.nrepl.attach]
            [jonquil.nrepl.cljs-node]
            [jonquil.nrepl.jank]
            [jonquil.nrepl.jvm]
            [jonquil.nrepl.shadow]
            [jonquil.runtime-registry :as registry]
            [jonquil.session]
            [jonquil.settings :as settings]
            [jonquil.tabs :as tabs]
            [jonquil.theme :as theme]))

(def ^:private STATUS-MARGIN
  ;; px at each end of the status bar: GNOME's usual 6-12 px gutter, the
  ;; status box's own child spacing (6) plus a little.
  8)

;; --- construction ------------------------------------------------------

;; --- activate ---------------------------------------------------------------

;; The live window's open-paths fn (a vector of paths), published by
;; activate! and read by the application's "open" handler. nil until a
;; window exists. Never reset: the app quits with its window.
(defonce ^:private current-open-paths (atom nil))
;; The live window's status reporter (fn [text]); nil until a window exists.
(defonce ^:private current-report (atom nil))

(defn- report-open-problem!
  "Says text on the window's status label, else on stderr with a prefix."
  [text]
  (if-let [r @current-report]
    (r text)
    (binding [*out* *err*] (println (str "jonquil: " text)))))

(defn- open-paths-from-gfiles
  "The n local paths of a GFile** array, as strings. A file with no local
  path (a remote URI) or one that throws is reported and skipped."
  [files n]
  (into []
        (keep (fn [i]
                (let [gf (ffi/read files :pointer (* 8 i))]
                  (try
                    (let [p (g/g-file-get-path* gf)]
                      (if (ffi/null? p)
                        (let [u (g/g-file-get-uri* gf)
                              uri (if (ffi/null? u) "(unknown)"
                                      (try (ffi/ptr->string u) (finally (g/g-free u))))]
                          (report-open-problem! (str "Couldn't open " uri ": not a local file"))
                          nil)
                        (try (ffi/ptr->string p) (finally (g/g-free p)))))
                    (catch :default e
                      (report-open-problem! (str "Couldn't open file " i ": " (ex-message e)))
                      nil)))))
        (range n)))

(declare activate!)

(defn launch-split
  "When an \"open\" is what launched the app, its first path is the launch
  option activate! always took from the command line: a directory is :dir,
  anything else (a missing file too) is :file. The rest open as tabs.
  dir? is injected, so this stays pure. => {:launch {..} :rest [..]}"
  [paths dir?]
  (if-let [p (first paths)]
    {:launch (if (dir? p) {:dir p} {:file p}) :rest (vec (rest paths))}
    {:launch {} :rest []}))

(defn- on-app-open!
  "The application's \"open\" handler: n GFiles go to the window's
  open-paths fn. With no window yet (the open launched the app), the first
  path is activate!'s launch option and only the rest go to open-paths
  (launch-split). Nothing throws into GTK; a failure is reported."
  [app opts files n]
  (try
    (let [paths (open-paths-from-gfiles files n)]
      (if (nil? @current-open-paths)
        (let [{:keys [launch rest]}
              (launch-split paths (fn [p] (.isDirectory (java.io.File. ^String p))))]
          (activate! app (merge opts launch))
          (when (seq rest)
            (when-let [f @current-open-paths] (f rest))))
        (when-let [f @current-open-paths] (f paths))))
    (catch :default e
      (try (report-open-problem! (str "Couldn't open: " (ex-message e)))
           (catch :default _ nil)))))

(defn- activate! [app {:keys [file dir cwd on-ready settings-path plugins?]}]
  (let [;; An atom, not a plain value: :settings-path!* (self-test hook
        ;; only) repoints it at an impossible path so a save can be forced
        ;; to fail. Defaults to the real settings file; every harness
        ;; (dev/gtk_selftest_window.clj, dev/demo_shots.clj,
        ;; dev/smoke/binary_smoke.clj's XDG_CONFIG_HOME) is responsible for
        ;; passing its own isolated :settings-path instead. Named
        ;; settings-path* so it doesn't shadow the destructured
        ;; :settings-path option.
        settings-path* (atom (or settings-path (settings/settings-path)))
        ;; Whether this window runs plugins (false under --no-plugins); an
        ;; atom only so :plugins-on!* (self-test hook) can turn it off in a
        ;; window that started with them
        plugins-on* (atom (not (false? plugins?)))
        ;; The window's dialect and runtime (jonquil.dialect/select), and
        ;; its one evaluator. ev* is an atom because it is swapped when a
        ;; file from another runtime opens; every reader derefs it at call
        ;; time. session stays the evaluator the window started with, for
        ;; the ui map's :session. Joins jonquil.runtime-registry right away,
        ;; same as every later evaluator apply-selection! makes.
        ;; A directory launch resolves the directory's own project;
        ;; find-project starts at a path's parent, so it's given a child
        ;; of dir (measured 2026-10-09).
        sel* (atom (if (and dir (not file))
                     (ft/project-selection dir (:dialect (settings/load-settings @settings-path*)))
                     (win-runtime/select-for file @settings-path*)))
        ev* (atom (doto (ev/make @sel*) registry/register!))
        session @ev*
        evaluating-fn (fn [] (ev/evaluating? @ev*))
        ;; The window's tabs, in order, and the active
        ;; one's id; every window-level action resolves the active tab when
        ;; it runs (active-tab), never one captured at build time.
        tabs (atom [])
        active-id (atom nil)
        next-id (atom 0)
        active-tab (fn [] (some (fn [t] (when (= @active-id (:id t)) t)) @tabs))
        active? (fn [t] (= @active-id (:id t)))
        notebook (g/gtk-notebook-new)
        ;; The tab operations' context (tab-ctx), set once the tabs' wiring
        ;; context exists in the body below -- the same forward-reference
        ;; idiom *cv and *right use.
        *tab-ctx (atom nil)
        ;; Tab ids, most recently shown first (on-switch! moves one to the
        ;; front, remove-tab! drops it): Genera's Select order
        recent-tabs (atom [])
        win (g/gtk-application-window-new app)
        status-label (g/gtk-label-new "Ready")
        line-col-label (g/gtk-label-new "Ln 1, Col 1")
        ;; LM6: what keys do at the caret, dim, hidden while there's nothing
        hint-label (doto (g/gtk-label-new "")
                     (g/gtk-widget-add-css-class "dim-label")
                     ;; never wider than this, so the status bar doesn't jump
                     (g/gtk-label-set-ellipsize g/PANGO-ELLIPSIZE-END)
                     (g/gtk-label-set-max-width-chars 44))
        hints-on? (atom true)
        parinfer-label (g/gtk-label-new "")
        ;; How many agent proposals are pending, hidden at zero
        pending-label (g/gtk-label-new "")
        ;; The window's dialect (and runtime, when it isn't the
        ;; dialect's default), between the status text and Ln/Col.
        dialect-label (g/gtk-label-new (dialect/label @sel*))
        confirmed-close? (atom false)
        ;; Self-test hooks only -- see close-request-fn's docstring.
        suppress-close? (atom false)
        closing? (atom false)
        *cv (atom nil)
        ;; LM1's ! commands at the prompt, installed once the palette's
        ;; pieces exist (the command-palette part resets it at install)
        bang* (atom (fn [_text]))
        ;; :failure*, the last failed evaluation, for the restarts
        cv (assoc
            (cview/make-console-view {:on-submit (win-run/make-on-submit active-tab *cv ev* status-label bang*)
                                     :history-prev (win-run/history-prev-fn ev*)
                                     :history-next (win-run/history-next-fn ev*)
                                     ;; The console reads busy/waiting state
                                     ;; straight from the engine, never a mirror.
                                     :evaluating? evaluating-fn
                                     :waiting? (fn [] (ev/waiting-for-input? @ev*))})
            :failure* (atom nil)
            ;; a fn the window runs after every evaluation (the Trace panel's refresh)
            :after-eval* (atom nil)
            ;; LM7: the fn (record result) that files a finished evaluation
            ;; in the project's record, and the replay tab as {:id :root},
            ;; the root it was opened for (or nil)
            :recorder* (atom nil)
            ;; a fn returning the tree's root, read as an evaluation starts
            :replay-root* (atom nil)
            :replay-tab* (atom nil))
        _ (reset! *cv cv)
        {:keys [provider theme-name theme-choice system-dark-fn* poll-armed? arm-count
                zoom-fn theme-ctx set-theme-fn cycle-theme-fn]}
        (win-theme/build {:tabs tabs :cv cv :settings-path* settings-path* :status-label status-label
                          :evaluating-fn evaluating-fn :closing? closing?})
        ;; The source panel is built before the tabs, whose
        ;; jump opens it; its root joins the right-hand slot once the
        ;; inspector exists (*right, reset below).
        sp (source-pane/make-pane)
        ;; The review panel shares the slot
        rp (review-pane/make-pane)
        ;; The plugin list shares the slot too; its rows are rebuilt after
        ;; every plugin step, like the reviews
        lp (plugin-list-pane/make-pane)
        ;; The Messages panel's unread count (LM8), shown in the status
        ;; bar while it's above zero; the Messages part fills it
        unread-label (g/gtk-label-new "")
        ;; And the history of the plugin selected in it
        hp (plugin-history-pane/make-pane)
        *right (atom nil)
        {:keys [popovers close-popovers-fn register-popover-fn source-ctx server-ev completion-ns-fn completion-opts]}
        (win-help/build {:ev* ev* :status-label status-label :sp sp :sel* sel* :active? active? :active-tab active-tab
                         :open-file! win-tabs/open-file! :tab-ctx* *tab-ctx :right* *right})
        ;; A second jonquil.gtk.completion instance, on the console's own
        ;; input region -- :position :top (above the input line, vs. the
        ;; editor's own :bottom), :snapshot-fn/:apply-op! overridden to
        ;; cview/input-snapshot and cview/apply-input-op! so refine!/
        ;; commit! read and write the input region instead of :ed's whole
        ;; buffer (see jonquil.gtk.completion's ns docstring).
        ;; close-others!/on-error are the SAME registry-wide fns the
        ;; editor's instance uses, so opening either instance still closes
        ;; any other open popover.
        repl-completion (completion/make-completion
                         (:view cv)
                         ;; No :ed -- :snapshot-fn/:apply-op! below replace
                         ;; every use the default closures would have made
                         ;; of it, so there's no editor for this instance
                         ;; to name.
                         {:ns-fn completion-ns-fn
                          :candidates-fn (fn [prefix text pos extra] (ev/complete @ev* prefix text pos extra))
                          :doc-fn (fn [sym text pos] (ev/lookup @ev* sym text pos))
                          :server-ev server-ev
                          :close-others! close-popovers-fn
                          :on-error (fn [msg] (win-status/set-status! status-label msg))
                          :position :top
                          :snapshot-fn (fn [] (cview/input-snapshot cv))
                          :apply-op! (fn [result] (cview/apply-input-op! cv result))})
        repl-complete-fn (fn [] (win-help/do-repl-complete! cv ev* repl-completion status-label))
        ;; the console's completion is caret-anchored too: scrolling the
        ;; Interactions pane follows or hides it, as a tab's editor does
        ;; for its popovers (wire-tab!)
        _ (let [follow (callable (fn [_adj _data]
                                   (popover/follow-caret! repl-completion)
                                   nil)
                                 [:pointer :pointer] :void)]
            (doseq [adj [(g/gtk-scrollable-get-vadjustment (:view cv))
                         (g/gtk-scrollable-get-hadjustment (:view cv))]]
              (g/g-signal-connect-data adj "value-changed" follow ffi/null ffi/null g/CONNECT-DEFAULT)))
        {:keys [run-fn stop-fn run-selection-fn run-changed-fn]}
        (win-run/build {:active-tab active-tab :cv cv :ev* ev* :sel* sel* :status-label status-label})
        open-fn (fn [] (win-files/do-open! cv win (fn [p] (win-tabs/open-file! @*tab-ctx p))))
        save-fn (fn [] (let [t (active-tab)] (win-files/do-save! (:ed t) cv win (:path t))))
        save-as-fn (fn [] (let [t (active-tab)] (win-files/do-save-as! (:ed t) cv win (:path t))))
        {:keys [close-fn new-tab-fn step-tab-fn digit-tab-fn]}
        (win-tabs/build {:tabs tabs :active-id active-id :active-tab active-tab :tab-ctx* *tab-ctx})
        {:keys [h-paned inspector-state inspecting? inspect-queued inspector-last-args on-view on-inspector-close pane]}
        (win-inspector/build {:active-tab active-tab :cv cv :ev* ev* :status-label status-label :right* *right})
        {:keys [slot part-panels* right panel-focus-fn]}
        (win-right-slot/build {:win win :h-paned h-paned :pane pane :sp sp :rp rp :lp lp :hp hp
                               :settings-path* settings-path* :status-label status-label})
        _ (reset! *right right)
        inspect-last-ctx {:ev* ev* :right right :pane pane :inspector-state inspector-state
                          :inspecting? inspecting? :inspector-last-args inspector-last-args
                          :on-view on-view :status-label status-label :start [:start {}]}
        inspect-last-fn (fn [] (win-inspector/inspect-last! inspect-last-ctx))
        inspect-form-fn (fn [] (win-inspector/inspect-form! {:tab (active-tab) :cv cv :ev* ev* :sel* sel* :status-label status-label
                                               :inspecting? inspecting? :inspect-queued inspect-queued
                                               :inspect-last-fn inspect-last-fn}))
        {:keys [source-jump-fn source-close-fn source-back-fn source-open-fn jump-back-fn source-keys]}
        (win-help/build-source-keys {:source-ctx source-ctx :active-tab active-tab :right right :sp sp :win win :tabs tabs
                                     :open-file! win-tabs/open-file! :select-tab! win-tabs/select-tab! :tab-by-id win-tabs/tab-by-id
                                     :tab-ctx* *tab-ctx})
        close-inspector-fn (fn [] (win-inspector/close-inspector-panel! right (:ed (active-tab))))
        toggle-focus-fn (fn [] (win-keys/do-toggle-focus! win (:ed (active-tab)) cv))
        clear-console-fn (fn [] (win-keys/do-clear-console! cv))
        clear-last-fn (fn [] (win-keys/do-clear-last-output! cv))
        ;; Parinfer: the window's one mode, read by every tab's editor
        ;; hook; :off unless settings.edn set one.
        parinfer-mode* (atom (or (:parinfer (settings/load-settings @settings-path*)) :off))
        ;; The active tab's project may override the global either way
        ;; (.jonquil.edn :parinfer), so the label follows the ACTIVE tab's
        ;; effective mode and says (project) when the override decides.
        active-parinfer-override (fn []
                                   (when-let [t (active-tab)]
                                     (:parinfer (some-> (:project t) deref))))
        update-parinfer-label! (fn []
                                 (let [ov (active-parinfer-override)]
                                   (if-let [t (commands/parinfer-status-text
                                               (commands/parinfer-effective ov @parinfer-mode*)
                                               (some? ov)
                                               @parinfer-mode*)]
                                     (do (g/gtk-label-set-text parinfer-label t)
                                         (g/gtk-widget-set-visible parinfer-label 1))
                                     (g/gtk-widget-set-visible parinfer-label 0))))
        ;; The hook's one status line: a skipped long form names itself,
        ;; and the next adjusted edit clears it back to Ready.
        parinfer-skip-shown* (atom false)
        parinfer-status-fn (fn [msg]
                             (if msg
                               (do (reset! parinfer-skip-shown* true)
                                   (when-not (evaluating-fn)
                                     (win-status/set-status! status-label
                                                  (str (or (commands/parinfer-label-text @parinfer-mode*)
                                                           "Parinfer")
                                                       " (" msg ")"))))
                               (when (and (compare-and-set! parinfer-skip-shown* true false)
                                          (not (evaluating-fn)))
                                 (win-status/set-status! status-label "Ready"))))
        parinfer-fn (fn [choice]
                      (let [k (commands/parinfer-step @parinfer-mode* choice)]
                        (reset! parinfer-mode* k)
                        ;; A cleared skip note mustn't later stomp a newer
                        ;; status.
                        (reset! parinfer-skip-shown* false)
                        (update-parinfer-label!)
                        (when-let [err (settings/save-settings! @settings-path* {:parinfer k})]
                          (cview/append! cv :system (str "; settings: " err "\n")))
                        ;; same restraint as the theme: a live Running…
                        ;; status isn't overwritten. A pinned active tab
                        ;; names its project's choice, since the command
                        ;; changed only the global.
                        (when-not (evaluating-fn)
                          (win-status/set-status! status-label
                                       (str "Parinfer: " (commands/parinfer-mode-word k)
                                            (commands/parinfer-pinned-note
                                             k (active-parinfer-override)))))))
        open-find-fn (fn [] (find-bar/open! (:fb (active-tab)) :find))
        open-replace-fn (fn [] (find-bar/open! (:fb (active-tab)) :replace))
        ;; The editor's and the console's own context menus -- each item
        ;; calls the EXACT SAME fn its key does, through its command in
        ;; jonquil.command-registry. install-context-menu! returns the GSimpleActionGroup, so :activate-
        ;; menu-action!*/:repl-activate-menu-action!* (below) can fire an
        ;; item directly, by name, for rows and the smoke.
        ;; The ui's editor-menu hook fires the FIRST tab's menu
        ;; (tabs are built after this let), as the other per-tab ui keys do.
        activate-menu-action-fn (fn [name] (g/g-action-group-activate-action (:menu-group (first @tabs)) name ffi/null))
        ;; The tree on the left, its toggle first in the toolbar, and the
        ;; outer paned that holds it beside h-paned.
        {:keys [tree-pane] *open-folder :open-folder* *folder-result :folder-result*}
        (win-tree-ops/build {:status-label status-label :tab-ctx* *tab-ctx})
        files-btn (g/gtk-toggle-button-new-with-label "☰ Files")
        tree-paned (g/gtk-paned-new g/ORIENTATION-HORIZONTAL)
        paned (g/gtk-paned-new g/ORIENTATION-VERTICAL)
        ;; Window parts (jonquil.gtk.parts): each install fn reads the core
        ;; bindings it needs from wctx and answers its :fns, :ui, :hooks and
        ;; :exports. A part's error is said on the status line, not thrown.
        part-error! (fn [id ex]
                      (win-status/set-status! status-label (str "; part " id ": " (or (ex-message ex) (str ex)))))
        part-contribution (parts/install gtk-parts/installs
                                         {:win win :status-label status-label :cv cv :ev* ev* :sel* sel*
                                          :tabs tabs :active-tab active-tab :settings-path* settings-path*
                                          :tab-ctx* *tab-ctx
                                          :tab-by-id win-tabs/tab-by-id :select-tab! win-tabs/select-tab! :new-tab! win-tabs/new-tab!
                                          :bang* bang* :recent-tabs recent-tabs
                                          :contain-click (fn [where f] (win-status/contain-click status-label where f))
                                          :set-status! (fn [text] (win-status/set-status! status-label text))
                                          :right right :tree-pane tree-pane :tree-paned tree-paned :paned paned
                                          :files-btn files-btn :panel-focus-fn panel-focus-fn
                                          :showing-panel win-right-slot/showing-panel :show-right-panel! win-right-slot/show-right-panel!
                                          :hide-right-panel! win-right-slot/hide-right-panel!
                                          :alert-pending? win-alerts/alert-pending? :show-alert! win-alerts/show-alert!
                                          :make-eval-opts win-run/make-eval-opts :refuse-foreign! win-run/refuse-foreign!
                                          :unread-label unread-label
                                          :register-status-log! (fn [log!] (win-status/register-status-log! status-label log!))
                                          :plugin-step! win-plugin-agent/plugin-step!
                                          :plugins-on* plugins-on* :review-pane rp
                                          :plugin-list-pane lp :plugin-history-pane hp
                                          :pending-label pending-label
                                          :open-file! (fn [p] (win-tabs/open-file! @*tab-ctx p))
                                          :plugin-history-shown win-plugin-agent/plugin-history-shown
                                          :plugin-reviews-changed win-plugin-agent/plugin-reviews-changed
                                          :plugin-console win-plugin-agent/plugin-console
                                          :on-saved win-files/on-saved
                                          :mcp-eval! win-run/mcp-eval!
                                          :refresh-labels! (fn [] (win-tabs/refresh-labels! @*tab-ctx))
                                          :inspect! (fn [start]
                                                      (win-inspector/inspect-last! (assoc inspect-last-ctx :start start)))
                                          :ask-lookup! win-help/ask-lookup! :jump-to! win-help/jump-to!
                                          :source-ctx source-ctx
                                          :place-caret-and-scroll! win-help/place-caret-and-scroll! :file-name win-files/file-name
                                          :open-in-tab! win-help/open-in-tab! :show-source! win-help/show-source!
                                          :rerun-failure! (fn [f t]
                                                            (win-run/rerun-failure! {:failure f :tab t :cv cv :ev* ev* :sel* sel*
                                                                             :status-label status-label}))}
                                         part-error!
                                         {:specs parts/specs})
        _ (reset! part-panels* (:panels part-contribution))
        ;; The console's context menu is built here, after the parts, because
        ;; the live-results part answers the four result fns its items call;
        ;; built earlier those keys were nil.
        console-menu-group
        (win-keys/install-context-menu! (:view cv)
                               (cmd-registry/menu-items cmd-registry/console-menu
                                                        (merge {:complete! repl-complete-fn :clear-last-fn clear-last-fn}
                                                               (select-keys (:fns part-contribution)
                                                                            [:result-inspect-fn :result-copy-fn
                                                                             :result-define-fn :result-jump-fn])))
                               (fn [msg] (win-status/set-status! status-label msg)))
        repl-activate-menu-action-fn
        (fn [name] (g/g-action-group-activate-action console-menu-group name ffi/null))
        ;; named, so each tab's palette can build the same per-key ctx
        ;; (window-key-ctx) a window chord gets
        window-key-args (parts/merge-with-core
                         :fns
                         {:panel-focus-fn panel-focus-fn
                         :hints-toggle-fn (fn []
                                            (let [on? (swap! hints-on? not)]
                                              (when-not on? (g/gtk-widget-set-visible hint-label 0))
                                              (win-status/set-status! status-label (if on?
                                                                          "; Key hints on: the status bar names the keys for the symbol at the caret."
                                                                          "; Key hints off."))))
                         :run-changed-fn run-changed-fn
                         ;; switching tabs: a palette tab entry, and Go back
                         :switch-tab-fn (fn [id] (when (win-tabs/tab-by-id tabs id) (win-tabs/select-tab! @*tab-ctx id)))
                         :tab-back-fn (fn [] (if-let [id (second @recent-tabs)]
                                               (win-tabs/select-tab! @*tab-ctx id)
                                               (win-status/set-status! status-label "; Only one tab is open.")))
                         :run-fn run-fn :stop-fn stop-fn :open-fn open-fn :save-fn save-fn
                                      :save-as-fn save-as-fn :close-fn close-fn
                                      :run-selection-fn run-selection-fn :toggle-focus-fn toggle-focus-fn
                                      :clear-console-fn clear-console-fn :clear-last-fn clear-last-fn
                                      :zoom-fn zoom-fn :evaluating? evaluating-fn
                                      :win win :active-tab active-tab :open-find-fn open-find-fn
                                      :open-replace-fn open-replace-fn :theme-cycle-fn cycle-theme-fn
                                      :close-popovers-fn close-popovers-fn
                                      :inspect-last-fn inspect-last-fn :inspect-form-fn inspect-form-fn
                                      :pane pane :close-inspector-fn close-inspector-fn
                                      :status-label status-label :source-keys source-keys
                                      :new-tab-fn new-tab-fn :step-tab-fn step-tab-fn
                                      :digit-tab-fn digit-tab-fn
                                      :parinfer-fn parinfer-fn}
                         part-contribution)
        window-key-fn (win-keys/on-window-key window-key-args)
        toolbar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        run-btn (g/gtk-button-new-with-label "Run (F5)")
        stop-btn (g/gtk-button-new-with-label "Stop (Esc)")
        open-btn (g/gtk-button-new-with-label "Open…")
        save-btn (g/gtk-button-new-with-label "Save…")
        reset-chk (g/gtk-check-button-new-with-label "Reset on Run")
        ;; The tick an attach runtime took down (dialect/reset-toggle).
        reset-state (atom {})
        ;; The status bar is a horizontal box: the status label on the
        ;; left (hexpand, so it claims the box's spare width) and the
        ;; Ln/Col label on the right.
        status-box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        vbox (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        keyctl (g/gtk-event-controller-key-new)]
    (g/gtk-box-append toolbar files-btn)
    (g/gtk-box-append toolbar run-btn)
    (g/gtk-box-append toolbar stop-btn)
    (g/gtk-box-append toolbar open-btn)
    (g/gtk-box-append toolbar save-btn)
    (g/gtk-box-append toolbar reset-chk)
    (g/gtk-check-button-set-active reset-chk 1)
    (g/gtk-widget-set-vexpand paned 1)
    ;; A notebook of tabs is the paned's start child; each page is a tab's
    ;; own find bar over its editor (jonquil.gtk.tab). The strip stays
    ;; hidden while there's one tab.
    (g/gtk-notebook-set-show-tabs notebook 0)
    (g/gtk-notebook-set-show-border notebook 0)
    (g/gtk-notebook-set-scrollable notebook 1)
    (g/gtk-widget-set-vexpand notebook 1)
    (g/gtk-paned-set-start-child paned notebook)
    (g/gtk-paned-set-end-child paned (:scroll cv))
    (g/gtk-paned-set-position paned 430)
    ;; The editor/console paned is h-paned's start child; the
    ;; inspector panel is its end child, hidden until the first open
    ;; (show-inspector-panel!), and kept at a fixed width when the window
    ;; resizes (never grows/shrinks with it, never squeezed below its own
    ;; WIDTH-REQUEST).
    (g/gtk-widget-set-vexpand h-paned 1)
    (g/gtk-paned-set-start-child h-paned paned)
    (g/gtk-widget-set-vexpand (:root pane) 1)
    (g/gtk-widget-set-vexpand (:root sp) 1)
    (g/gtk-box-append slot (:root pane))
    (g/gtk-box-append slot (:root sp))
    (g/gtk-widget-set-vexpand (:root rp) 1)
    (g/gtk-box-append slot (:root rp))
    (g/gtk-widget-set-vexpand (:root lp) 1)
    (g/gtk-box-append slot (:root lp))
    (g/gtk-widget-set-vexpand (:root hp) 1)
    (g/gtk-box-append slot (:root hp))
    (doseq [[_ p] @part-panels*]
      (g/gtk-widget-set-vexpand (:root p) 1)
      (g/gtk-box-append slot (:root p)))
    (g/gtk-paned-set-end-child h-paned slot)
    (g/gtk-paned-set-resize-end-child h-paned 0)
    (g/gtk-paned-set-shrink-end-child h-paned 0)
    (g/gtk-widget-set-visible (:root pane) 0)
    (g/gtk-widget-set-visible (:root sp) 0)
    (g/gtk-widget-set-visible (:root rp) 0)
    (g/gtk-widget-set-visible (:root lp) 0)
    (doseq [[_ p] @part-panels*]
      (g/gtk-widget-set-visible (:root p) 0))
    (g/gtk-widget-set-visible (:root hp) 0)
    (g/gtk-widget-set-visible slot 0)
    (g/gtk-box-append vbox toolbar)
    (g/gtk-paned-set-start-child tree-paned (:root tree-pane))
    (g/gtk-paned-set-end-child tree-paned h-paned)
    (g/gtk-paned-set-resize-start-child tree-paned 0)
    (g/gtk-paned-set-shrink-start-child tree-paned 0)
    (g/gtk-widget-set-vexpand tree-paned 1)
    (g/gtk-box-append vbox tree-paned)
    (g/gtk-widget-set-hexpand status-label 1)
    (g/gtk-box-append status-box status-label)
    (g/gtk-box-append status-box hint-label)
    (g/gtk-widget-set-visible hint-label 0)
    (g/gtk-box-append status-box pending-label)
    (g/gtk-widget-set-visible pending-label 0)
    (g/gtk-box-append status-box unread-label)
    (g/gtk-widget-set-visible unread-label 0)
    (g/gtk-box-append status-box dialect-label)
    (g/gtk-box-append status-box parinfer-label)
    (update-parinfer-label!)
    (g/gtk-box-append status-box line-col-label)
    ;; Room at both ends, so "Ln N, Col N" doesn't touch the window's right
    ;; edge and the left matches it.
    (g/gtk-widget-set-margin-start status-box STATUS-MARGIN)
    (g/gtk-widget-set-margin-end status-box STATUS-MARGIN)
    (g/gtk-box-append vbox status-box)
    (g/gtk-window-set-child win vbox)
    (g/gtk-window-set-default-size win 900 700)
    ;; One shared GtkCssProvider for the display, added once; both views
    ;; get the jonquil-code CSS class so a single reload resizes both live.
    ;; Each tab's view gets the class in jonquil.gtk.tab/make-tab!.
    (g/gtk-widget-add-css-class (:view cv) "jonquil-code")
    (g/gtk-style-context-add-provider-for-display
     (g/gdk-display-get-default) provider g/STYLE-PROVIDER-PRIORITY-APPLICATION)
    (source-pane/apply-theme! sp (theme/theme @theme-name))
    (add-watch theme-name ::source-pane (fn [_ _ _ k] (source-pane/apply-theme! sp (theme/theme k))))
    (inspector-pane/apply-theme! pane (theme/theme @theme-name))
    (add-watch theme-name ::inspector-pane (fn [_ _ _ k] (inspector-pane/apply-theme! pane (theme/theme k))))
    (g/g-signal-connect-data run-btn "clicked"
                             (callable (win-status/contain-click status-label "run" (fn [_] (run-fn))) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data stop-btn "clicked"
                             (callable (win-status/contain-click status-label "stop" (fn [_] (stop-fn))) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data open-btn "clicked"
                             (callable (win-status/contain-click status-label "open" (fn [_] (open-fn))) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data save-btn "clicked"
                             (callable (win-status/contain-click status-label "save" (fn [_] (save-fn))) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data reset-chk "toggled"
                             (callable (win-status/contain-click status-label "reset on run"
                                                      (fn [btn]
                                                        (ev/set-mode! @ev*
                                                                      (if (zero? (g/gtk-check-button-get-active btn))
                                                                        :accumulate :reset))))
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
    (g/g-signal-connect-data keyctl "key-pressed"
                             (callable window-key-fn [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller win keyctl)
    (win-quit/connect! {:tabs tabs :select! (fn [t] (win-tabs/select-tab! @*tab-ctx (:id t)))
                        :cv cv :win win :app app
                        :confirmed-close? confirmed-close?
                        :suppress-close? suppress-close? :closing? closing?
                        :popovers popovers :ev* ev*
                        ;; Live saving stops first: nothing after the
                        ;; quit's own save may write a shorter list.
                        ;; A quit before startup's reopen is done
                        ;; stops it and saves nothing, since the
                        ;; tabs open are only part of the list.
                        :save-session! (fn [] (let [c @*tab-ctx
                                                    restoring? (some-> c :session-restoring? deref)]
                                                (parts/run-hooks! part-contribution :on-quit [] part-error!)
                                                (some-> c :session-live? (reset! false))
                                                (some-> c :session-restoring? (reset! false))
                                                (when-not restoring? (win-session/save-session! c))))})
    (cview/set-error-fn! cv (fn [msg] (win-status/set-status! status-label msg)))
    (editor/set-jump-fn! (:editor sp) source-jump-fn)
    (editor/set-error-fn! (:editor sp) (fn [msg] (source-pane/report! sp msg)))
    (source-pane/set-handlers! sp {:on-open source-open-fn :on-close source-close-fn})
    ;; Joins the SAME registry, so Escape closes the def prompt before it
    ;; can fall through to Stop.
    (register-popover-fn (:def-popover pane))
    (win-help/wire-completion! repl-completion repl-complete-fn status-label register-popover-fn
                      (fn [fns] (cview/set-completion-fns! cv fns))
                      (fn [f] (cview/on-input-changed! cv f))
                      (fn [f] (cview/on-focus-lost! cv f)))
    (let [sel-ctx {:cv cv :status-label status-label :dialect-label dialect-label :sel* sel* :ev* ev*
                   :reset-chk reset-chk :reset-state reset-state :right right :inspector-state inspector-state
                   :active-ed (fn [] (:ed (active-tab)))
                   :mode-fn (fn [] (if (zero? (g/gtk-check-button-get-active reset-chk)) :accumulate :reset))}
          ;; A file argument that doesn't exist opens an empty editor with
          ;; the path remembered anyway, so Save creates it -- unlike the
          ;; Open button's dialog result, this is the documented normal
          ;; case, not an error to report.
          text (when file (try (slurp file) (catch :default _ nil)))
          ;; A launch file holding a NUL is refused like any open. Its path must NOT be remembered (Save would write
          ;; "" over the binary), but `file` stays bound so the
          ;; session-reopen gate below (when-not file) is untouched.
          file-nul (when text (tabs/nul-line text))
          ;; A save (or a path change) under the root
          ;; expands to the file and highlights it when its tab is active.
          tree-saved! (fn [t]
                        (let [p @(:path t)
                              m @(:state tree-pane)]
                          (when (and p (ft/under-root? m p))
                            (file-tree-pane/show-model! tree-pane (ft/expand-to m p))
                            (when (active? t) (file-tree-pane/select-path! tree-pane p)))))
          tab-ctx {:win win :cv cv :ev* ev* :sel* sel* :status-label status-label
                   :line-col-label line-col-label :close-popovers-fn close-popovers-fn
                   :register-popover-fn register-popover-fn :active? active? :source-ctx source-ctx
                   :sel-ctx sel-ctx :settings-path* settings-path* :inspect-last-fn inspect-last-fn
                   :inspect-form-fn inspect-form-fn :open-find-fn open-find-fn
                   :open-replace-fn open-replace-fn :tabs tabs :next-id next-id :notebook notebook
                   :theme-name theme-name :completion-opts completion-opts
                   :active-id active-id :active-tab active-tab :popovers popovers
                   :tree-pane tree-pane :tree-saved! tree-saved! :folder-result* *folder-result
                   :session-live? (atom false) :session-last (atom nil) :session-error (atom nil)
                   :session-restoring? (atom false)
                   :window-key-args window-key-args :repl-complete-fn repl-complete-fn
                   :parinfer-mode-read (fn [] @parinfer-mode*)
                   :parinfer-status-fn parinfer-status-fn
                   :parinfer-label-update! update-parinfer-label!
                   :part-contribution part-contribution :part-error! part-error!
                   :recent-tabs recent-tabs
                   :hint-label hint-label :hints-on? hints-on?}
          first-tab (win-tabs/add-tab! tab-ctx {:path (when-not file-nul file)
                                       :text (if file-nul "" (or text ""))})
          tree-settings (:file-tree (settings/load-settings @settings-path*))
          ;; Open Folder...'s result: re-roots the tree; the window's project
          ;; follows only while its one tab is an untouched Untitled one.
          folder-result! (fn [{p :path err :error :as r}]
                           (cond
                             p (do (file-tree-pane/show-model! tree-pane (ft/load-model p))
                                   (let [ts @tabs
                                         t (first ts)]
                                     (when (and (= 1 (count ts))
                                                (tabs/untouched-untitled? {:path @(:path t) :text (editor/editor-text (:ed t))}))
                                       (win-runtime/apply-selection! sel-ctx (ft/project-selection
                                                                  p (:dialect (settings/load-settings @settings-path*))))))
                                   (parts/run-hooks! part-contribution :on-reroot [{:path p}] part-error!))
                             (win-files/dialog-dismissed? r) nil
                             :else (cview/append! cv :error (str "; open folder: " err "\n"))))]
      ;; switch-page fired inside that first append, before the tab was in
      ;; tabs, so the tab is made active here, and starts the recent list.
      (reset! active-id (:id first-tab))
      (reset! recent-tabs [(:id first-tab)])
      ;; A launch on a project file: the first tab never goes through
      ;; on-switch!, so its override reaches the label here.
      (update-parinfer-label!)
      (reset! *tab-ctx tab-ctx)
      (reset! *folder-result folder-result!)
      ;; Open With and Dock drops. A directory re-roots the tree like an
      ;; Open Folder result and shows it; a file opens in a tab, and one
      ;; that can't be read goes to the status line while the rest still
      ;; open.
      (reset! current-report (fn [text] (win-status/set-status! status-label text)))
      (reset! current-open-paths
              (fn [paths]
                (doseq [p paths]
                  (try
                    (if (.isDirectory (java.io.File. ^String p))
                      (do (folder-result! {:path p})
                          ;; The tree now shows this folder, so show the tree
                          ;; too; the toggle handler saves it as a choice.
                          (g/gtk-toggle-button-set-active files-btn 1))
                      (when-let [err (:error (win-tabs/open-file! @*tab-ctx p))]
                        (win-status/set-status! status-label (str "Couldn't open " (.getName (java.io.File. ^String p)) ": " err))))
                    (catch :default e
                      (win-status/set-status! status-label (str "Couldn't open " p ": " (ex-message e))))))))
      ;; The tree's root, Open Folder..., then the file-tree part's
      ;; :tree-start!: the starting width and visibility (saved, else shown
      ;; only for a directory launch) and the toggle's handler, the one
      ;; place that shows, hides and saves.
      (file-tree-pane/show-model! tree-pane (ft/load-model (ft/root-for {:dir dir :file file :cwd cwd})))
      (reset! *open-folder (fn [] (win-files/file-dialog! :folder win
                                                (fn [r] (try (folder-result! r)
                                                             (catch :default e
                                                               (cview/append! cv :error (str "; open folder: " (ex-message e) "\n")))))
                                                (:root @(:state tree-pane)))))
      (if-let [f (:tree-start! (:fns part-contribution))]
        (f {:tree-settings tree-settings :dir dir})
        (g/gtk-widget-set-visible (:root tree-pane) 0))
      (g/g-signal-connect-data notebook "switch-page"
                               (callable (win-tabs/switch-page-fn tab-ctx) [:pointer :pointer :uint :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT)
      (win-theme/load-and-apply-theme! theme-ctx @settings-path*)
      ;; Same refusal as open-path!, for the launch file.
      (when file-nul
        (cview/append! cv :error
                       (str "; open: " file " holds a NUL byte on line " file-nul
                            ", which Jonquil can't edit, so it wasn't opened.\n")))
      ;; Same non-UTF-8 warning as open-path!.
      (when (and text (not file-nul) (str/includes? text "�"))
        (cview/append! cv :system
                       "This file isn't valid UTF-8; unreadable bytes show as � and saving will replace them.\n"))
      (try
        (win-runtime/show-selection! cv dialect-label @sel*)
        ;; The starting evaluator's connection, label and Reset on Run, as
        ;; apply-selection! does for every later one.
        (win-runtime/watch-connection! @ev* ev* sel* dialect-label cv status-label)
        (win-runtime/apply-reset-toggle! reset-chk reset-state @sel*)
        (g/gtk-label-set-text dialect-label (win-runtime/dialect-text @sel* @ev*))
        (win-runtime/refresh-prompt! cv ev*)
        (catch :default e
          (cview/append! cv :error (str "; project: " (ex-message e) "\n"))))
      (win-runtime/start-and-report! cv status-label @ev* @sel*)
      ;; A launch that named no file reopens the
      ;; tabs open at the last quit in this project. The entry, and any
      ;; re-root, come now, so the tree shows its folder from the first
      ;; frame. The files open after the window is up (reopen-later!).
      (let [entry (when-not file
                    (try (win-reopen/session-entry! tab-ctx {:reroot? true})
                         (catch :default e
                           (cview/append! cv :error (str "; reopen tabs: " (ex-message e) "\n"))
                           nil)))
            ;; The launch's tabs are open, so from here on every change to
            ;; them is saved as it happens (session-changed!). The state the
            ;; launch ends in isn't written: a launch on one file would
            ;; otherwise replace the folder's remembered tabs before
            ;; anything changed. A re-root alone doesn't save either, for the
            ;; same reason; the next tab change does.
            live! (fn []
                    (reset! (:session-last tab-ctx) (win-session/session-snapshot tab-ctx))
                    (reset! (:session-live? tab-ctx) true)
                    (parts/run-hooks! part-contribution :on-start [] part-error!))]
        ;; Say where the child tools' PATH came from. The wait for the
        ;; lookup is off the GTK thread; the append goes through on-gui!, and
        ;; a window closed meanwhile must not throw out of the future.
        (future
          (try (when-let [line (login-path/console-line)]
                 (on-gui! (fn [] (cview/append! cv :system (str line "\n")))))
               (catch :default _ nil)))
        (let [t (active-tab)]
          (win-files/update-title! (:ed t) win (:path t))
          (g/gtk-window-present win)
          (editor/focus! (:ed t)))
        (if (seq (:tabs entry))
          (win-reopen/reopen-later! tab-ctx entry
                         (fn []
                           (try
                             (let [t (active-tab)]
                               (win-files/update-title! (:ed t) win (:path t))
                               (editor/focus! (:ed t)))
                             (finally (live!)))))
          (live!))))
    ;; The plugins part's :plugins-start!: the console, the reviews
    ;; callback, the effects, the startup load and the save hook, set once
    ;; the window is up.
    (when-let [f (:plugins-start! (:fns part-contribution))] (f))
    (when on-ready
      ;; The per-tab keys below are the FIRST tab's objects; rows that
      ;; need another tab use :tabs* and :active-tab*. Actions that act on
      ;; "the window's file" (:open-path!, :save-to!, the simulated dialog
      ;; results, :close-choice!) use the active tab.
      (let [{:keys [ed fb help-pop full-doc-pop rename-pop completion palette actions]} (first @tabs)]
        (on-ready (parts/merge-with-core :ui {:editor ed :console cv :session session :status-label status-label :window win
                 ;; The notebook, the tab list and the active tab, read live.
                   :notebook notebook
                   :tabs* (fn [] @tabs)
                 ;; The tree, its toggle, Open Folder's result handler (for
                 ;; rows), and the outer paned.
                   :file-tree tree-pane
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
                   :open-paths! (fn [paths] (@current-open-paths paths))
                   :app app
                   :active-tab* active-tab
                   :parinfer-label parinfer-label
                   :parinfer!* parinfer-fn
                 ;; The tab operations, exactly what the keys, the toolbar
                 ;; and the tabs' close buttons call.
                   :new-tab!* (fn [] (win-tabs/new-tab! @*tab-ctx {}))
                   :open-file! (fn [p] (win-tabs/open-file! @*tab-ctx p))
                   :select-tab! (fn [id] (win-tabs/select-tab! @*tab-ctx id))
                   :close-tab! (fn [id] (win-tabs/close-tab! @*tab-ctx id))
                   :close-tab-choice! (fn [id result] (win-tabs/close-tab-choice! @*tab-ctx id result))
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
                   :run!* run-fn :stop!* stop-fn
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
                   :open-path! (fn [p]
                                 (let [{ed :ed path :path :keys [discard-warned?]} (active-tab)]
                                   (win-files/guarded-open! ed cv discard-warned?
                                                  (fn [] (win-files/open-path! ed cv path p) (win-files/update-title! ed win path)))))
                   :save-to! (fn [p] (let [{ed :ed path :path} (active-tab)]
                                       (win-files/save-to! ed cv path p) (win-files/update-title! ed win path)))
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
                   :clear-last-output!* clear-last-fn
                   :zoom!* zoom-fn
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
                   :settings-path!* (fn [p] (reset! settings-path* p))
                 ;; What the real quit saves and
                 ;; what a launch with no file restores (without re-rooting).
                   :save-session!* (fn [] (win-session/save-session! @*tab-ctx))
                   :restore-session!* (fn [] (win-reopen/restore-session! @*tab-ctx {:reroot? false}))
                 ;; Live saving on or off, so a row can stand in for a quit
                 ;; and a new launch (off until that launch's restore).
                   :session-live!* (fn [on?] (reset! (:session-live? @*tab-ctx) on?))
                 ;; the palette's window context keys, the parts' :fns merged in
                   :palette-ctx-keys (set (keys window-key-args))
                 ;; The window's own popover registry -- close-popovers-fn
                 ;; is exactly what the keymap's Escape binding calls;
                 ;; register-popover-fn is how a popover (a self-test row's
                 ;; own test popover, completion/quick-doc/rename) joins
                 ;; that registry.
                   :close-popovers!* close-popovers-fn
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
                 ;; entry-text/rename-problem-text.
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
                 ;; The first tab's command palette, and what
                 ;; Cmd/Ctrl+Shift+P calls to open it
                   :palette palette
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
                   :repl-completion repl-completion
                 ;; The inspector panel map (make-pane's return, including
                 ;; its own :on-def -- rows drive the def prompt through it
                 ;; directly), the window's own Cmd/Ctrl+I and
                 ;; Cmd/Ctrl+Shift+I actions (also what the two editor-menu
                 ;; items call), a reader for the window's own
                 ;; inspector-state atom, and a self-test-only hook naming
                 ;; the args of the last inspect! request this window
                 ;; actually sent (row-inspect-cols-match compares a
                 ;; :start's own :cols against the pane's own measure).
                   :inspector pane
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
                   :hint-label hint-label
                   :unread-label unread-label
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
                   :source-close!* source-close-fn
                 ;; Self-test hook: the exact fn the "key-pressed" signal
                 ;; calls, so a row can invoke it directly with a synthetic
                 ;; keyval/state -- an ordinary function call, not a
                 ;; synthesized OS keystroke.
                   :handle-window-key! window-key-fn
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
                                                         (win-files/contained-save-result! {:ed (:ed t) :cv cv :win win :path-atom (:path t)} result)))
                 ;; The toolbar's own buttons, so a row can fire their real
                 ;; "clicked" handlers (g/g-signal-emit-by-name).
                   :toolbar {:run run-btn :stop stop-btn :open open-btn :save save-btn}
                 ;; close-choice! is the exact fn the real alert
                 ;; dialog's own completion callback calls; request-close!*
                 ;; runs gtk_window_close, the same path the window's close
                 ;; button/OS chrome uses. closing?/suppress-close? are
                 ;; self-test-only hooks (close-request-fn's docstring).
                   :close-choice! (fn [result]
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
                   :complete-alert-for-test! win-alerts/complete-alert!}
                                  part-contribution))))))

(defn- run-application!
  "Runs the application's main loop. With an argv of more than the program
  name, hands GApplication a NULL-terminated char** so it emits \"open\"
  with the files; otherwise runs with no argv, so \"activate\" fires. The C
  strings and the array are freed after the loop ends."
  [app argv]
  ;; On Linux X11 GTK writes WM_CLASS from the prgname, and g_application_run
  ;; would set it from argv[0] (or the process name). The launcher's
  ;; StartupWMClass is the app id, so set it first. macOS keeps its value.
  (when (= "Linux" (System/getProperty "os.name"))
    (g/g-set-prgname "net.b12n.jonquil"))
  (if (> (count argv) 1)
    (let [n (count argv)
          arr (ffi/alloc (* 8 (inc n)))
          strs (mapv ffi/string->ptr argv)]
      (try
        (doseq [[i p] (map-indexed vector strs)]
          (ffi/write arr :pointer p (* 8 i)))
        (ffi/write arr :pointer ffi/null (* 8 n))
        (g/g-application-run app n arr)
        (finally
          (doseq [p strs] (ffi/free p))
          (ffi/free arr))))
    (g/g-application-run app 0 ffi/null)))

(defn start!
  "Builds the Jonquil window and blocks in the GTK main loop. opts is
  {:file path-or-nil :dir path-or-nil :cwd path-or-nil :on-ready (fn [ui])
  :settings-path path-or-nil :plugins? bool}, :plugins? false skipping the
  user's plugins, where :dir (a directory launch) and :cwd root
  the file tree (jonquil.file-tree/root-for) and
  :settings-path defaults to (jonquil.settings/settings-path) -- every
  self-test/docs-shots/smoke harness must pass its own isolated path so it
  never touches the user's real settings file. ui is {:file-tree
  :files-btn :tree-paned :toggle-file-tree!* :file-tree-visible?*
  :simulate-folder-result! :tree-menu!* :tree-press!* :tree-submit!*
  :tree-menu-state* :trash-choice! :time-ask* :time-confirmed? :notebook
  :tabs* :active-tab* :editor
  :console :session :status-label :line-col-label :evaluator* :selection*
  :dialect-label :reset-check :window :find-bar :run!*
  :stop!* :open-path! :save-to! :run-selection!* :run-form!* :toggle-focus!*
  :clear-console!* :clear-last-output!* :zoom!* :set-theme!* :theme-name* :code-css*
  :theme-choice* :system-dark-fn!* :poll-appearance!* :poll-arm-count* :poll-armed?*
  :cycle-theme!* :settings-path* :load-settings!* :settings-path!*
  :save-session!* :restore-session!* :session-live!*
  :replay-record* :replay-tab-id* :replay-open!* :replay-forget!* :replay-reopen-root!*
  :close-popovers!* :register-popover!* :quick-doc!* :jump!* :help-popover
  :full-doc-popover
  :rename!* :rename-to!* :rename-popover
  :activate-menu-action!* :repl-activate-menu-action!*
  :complete!* :completion
  :inspector :inspect-last!* :inspect-form!* :inspector-state* :inspector-last-args*
  :source-pane :right-slot :source-jump!* :source-open!* :source-back!* :jump-back!* :source-close!*
  :handle-window-key! :simulate-open-result! :simulate-save-result! :toolbar
  :close-choice! :activate-quit-action!* :request-close!* :closing? :suppress-close?
  :alert-dialog-count :alert-pending? :alert-release!
  :register-alert-choice-for-test! :complete-alert-for-test!}; the
  self-test rows call these functions directly, exactly as the buttons and
  keys do. :evaluator* reads back the window's current evaluator
  (ev*'s deref, never a second one); :selection* reads back the current
  jonquil.dialect/select result; :dialect-label is the GtkLabel those two
  keep in sync, shown in the status bar. :find-bar is the jonquil.gtk.find-bar
  map; rows call jonquil.gtk.find-bar's own functions on it.
  :cycle-theme!* is what Cmd/Ctrl+Shift+M runs; :settings-path* reads back
  the window's own settings path; :load-settings!* re-runs the startup load
  against any path; :settings-path!* is self-test-only (row-theme-save-
  failure-reported), repointing the window's own settings path. :close-popovers!*
  is what the window keymap's :popover/close-all Escape binding calls; :register-popover!*
  joins a jonquil.gtk.popover map to this window's registry so
  :close-popovers!* knows about it -- the completion/quick-doc/
  rename popovers register theirs the same way a self-test row's own test
  popover does. :quick-doc!*/:jump!* are exactly what the editor's
  own Ctrl+Q/Shift+F1/F12/Ctrl+B key clauses call; :help-popover is the
  jonquil.gtk.help-popover map those two actions show doc text in --
  jonquil.gtk.popover/open? and help-popover/quick-doc-text read it back.
  :full-doc-popover is the SECOND jonquil.gtk.help-popover map a
  repeated quick-doc press over the same target shows full documentation
  in -- jonquil.gtk.popover/open? and help-popover/full-doc-
  text read it back the same way.
  :rename!*/:rename-to!* are exactly what the editor's own
  Shift+F6/F2 clauses and the rename popover's own entry \"activate\"
  signal call; :rename-popover is the jonquil.gtk.help-popover map those
  two act on -- jonquil.gtk.popover/open? and help-popover/rename-entry-
  text/rename-problem-text read it back. :activate-menu-action!*/:repl-
  activate-menu-action!* fire one of the editor's/the console's own
  context-menu items by its unprefixed action name, exactly as
  install-context-menu!'s own docstring describes. :palette is
  the first tab's jonquil.gtk.palette map and :open-palette!* what
  Cmd/Ctrl+Shift+P calls to open the active tab's. :load-plugins!*
  loads the plugin folder now, off the GTK thread, answering the
  console lines, :reload-plugins!* does what Reload Plugins does and
  waits for it, and :plugins-dir* names that folder. :plugins-on!*
  sets whether the window runs plugins, as --no-plugins
  would have. :plugin-state*
  answers {:plugins? :plugins-dir :registry :disabled} for
  the plugin MCP tools' reads, and :plugin-propose!* takes {:id :source
  :reason}, runs the isolated check on the calling thread and records the
  proposal through the plugin agent, answering propose-step!'s map, or
  :status :unknown when the step didn't finish in time. :plugin-tools*
  answers the live plugins' MCP tools
  (jonquil.plugin-registry/mcp-tools), which the MCP server serves beside
  the built-ins. :watch-plugin-tools* takes a notify! fn and calls
  it when a swap of the live registry changes those tools, answering a fn
  that stops it (jonquil.plugin-registry/watch-tools!). :review-pane
  is the jonquil.gtk.review-pane map, :pending-label the status bar's
  pending count, :review-proposals!* does what Review Plugin Proposals
  does and waits for the window to change, :review-accept!*,
  :review-reject!* and :review-next!* do what the panel's buttons do and
  wait the same way (answering the step's lines, [] for Next), and
  :review-shown* answers
  {:id :n :index :count} for the proposal the panel shows, or nil. :complete!* is
  exactly what the editor's own Ctrl+Space clause calls; :completion is
  the jonquil.gtk.completion map it acts on -- jonquil.gtk.popover/open?
  and jonquil.gtk.completion/state read it back.
  :inspector is the jonquil.gtk.inspector-pane map; rows
  drive its own key/click handling directly, and its :on-def is exactly
  what the def prompt's entry calls. :inspect-last!*/:inspect-form!* are
  exactly what Cmd/Ctrl+I and Cmd/Ctrl+Shift+I (and the editor menu's two
  items) call; :inspector-state* reads back the window's own
  inspector-state atom; :inspector-last-args* is self-test-only, naming
  the args of the last inspect! request this window actually sent.
  :source-pane is the jonquil.gtk.source-pane map; :right-slot the GtkBox
  holding it and the inspector; the :source-*!* fns are what the panel's
  keys and buttons call.

  :argv (a vector of strings, \"jonquil\" first; jonquil.main/gtk-argv) is
  handed to g_application_run when it has more than the program name, so
  GApplication emits \"open\" with those files instead of \"activate\"."
  [{:keys [argv] :as opts}]
  ;; G_APPLICATION_NON_UNIQUE, so a second launch starts its own
  ;; process instead of forwarding "activate" into an already-running one
  ;; over D-Bus (the default GApplication behaviour for a shared app id).
  (let [app (g/gtk-application-new "net.b12n.jonquil"
                                   (bit-or g/APPLICATION-DEFAULT-FLAGS g/G-APPLICATION-NON-UNIQUE
                                           g/G-APPLICATION-HANDLES-OPEN))]
    (g/g-signal-connect-data app "activate"
                             (callable (fn [app _data] (activate! app opts)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; "open" (GApplication::open, void (*)(GApplication*, GFile**, gint,
    ;; const gchar*, gpointer)) carries Finder's Open With and Dock drops.
    (g/g-signal-connect-data app "open"
                             (callable (fn [app files n _hint _data] (on-app-open! app opts files n))
                                       [:pointer :pointer :int :pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (run-application! app argv)
    nil))
