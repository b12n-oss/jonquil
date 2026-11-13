(ns jonquil.gtk.window.startup
  "The window's start. build makes activate!'s first bindings (the settings path, the dialect selection and the evaluator, the tab list, the notebook and the window), connect-quit! makes activate!'s close-request connect and quit action with the closures that read activate!'s forward atoms, and startup! builds the tabs' context and the first tab, then starts the tree, the notebook, the runtime and the reopen, and runs the plugins part's start. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window's activate! unchanged; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.evaluator :as ev]
            [jonquil.file-tree :as ft]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.file-tree-pane :as file-tree-pane]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.quit :as win-quit]
            [jonquil.gtk.window.reopen :as win-reopen]
            [jonquil.gtk.window.runtime :as win-runtime]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.gtk.window.theme :as win-theme]
            [jonquil.login-path :as login-path]
            [jonquil.parts :as parts]
            [jonquil.runtime-registry :as registry]
            [jonquil.settings :as settings]
            [jonquil.tabs :as tabs]))

(defn- initial-selection
  "The window's starting dialect selection: a directory launch resolves
  the directory's own project, anything else the launch file's."
  [file dir settings-path]
  (if (and dir (not file))
    (ft/project-selection dir (:dialect (settings/load-settings settings-path)))
    (win-runtime/select-for file settings-path)))

(defn build
  "activate!'s first bindings, in activate!'s order: the settings path, whether the window runs plugins, the dialect selection, the evaluator and the session it starts with, evaluating-fn, the tab list with the active id, the next id, active-tab and active?, the notebook, *tab-ctx, the recent tab ids and the window. w holds activate!'s app and the options they read: :app :file :dir :settings-path :plugins?. Returns w with :settings-path* :plugins-on* :sel* :ev* :session :evaluating-fn :tabs :active-id :next-id :active-tab :active? :notebook :tab-ctx* (the *tab-ctx atom) :recent-tabs and :win added."
  [{:keys [app file dir settings-path plugins?] :as w}]
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
        sel* (atom (initial-selection file dir @settings-path*))
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
        win (g/gtk-application-window-new app)]
    (assoc w :settings-path* settings-path* :plugins-on* plugins-on* :sel* sel* :ev* ev* :session session :evaluating-fn evaluating-fn :tabs tabs :active-id active-id :next-id next-id :active-tab active-tab :active? active? :notebook notebook :tab-ctx* *tab-ctx :recent-tabs recent-tabs :win win)))

(defn connect-quit!
  "activate!'s close-request connect and quit action, through jonquil.gtk.window.quit's connect!, with close-request-fn's map built as activate! built it: :select! and :save-session! read *tab-ctx when they run, and :save-session! runs the parts' :on-quit hooks first. Called once per window; activate! reads nothing it returns. m holds :tabs :cv :win :app :confirmed-close? :suppress-close? :closing? :popovers :ev* :part-contribution :part-error! and :tab-ctx* (the *tab-ctx atom)."
  [{:keys [tabs cv win app confirmed-close? suppress-close? closing? popovers ev* part-contribution part-error!]
    *tab-ctx :tab-ctx*}]
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
                                              (when-not restoring? (win-session/save-session! c))))}))

(defn- make-sel-ctx
  "The runtime switch's context (apply-selection!'s): the window's selection
  and evaluator, the Reset on Run box and the panels a switch hides. w is
  build-tab-ctx's."
  [{:keys [cv status-label dialect-label sel* ev* reset-chk reset-state right inspector-state active-tab]}]
  {:cv cv :status-label status-label :dialect-label dialect-label :sel* sel* :ev* ev*
   :reset-chk reset-chk :reset-state reset-state :right right :inspector-state inspector-state
   :active-ed (fn [] (:ed (active-tab)))
   :mode-fn (fn [] (if (zero? (g/gtk-check-button-get-active reset-chk)) :accumulate :reset))})

(defn- make-tree-saved!
  "tree-saved!: the fn that shows a saved tab's file in the tree, expanding
  to it and, when its tab is active, highlighting it."
  [tree-pane active?]
  (fn [t]
    (let [p @(:path t)
          m @(:state tree-pane)]
      (when (and p (ft/under-root? m p))
        (file-tree-pane/show-model! tree-pane (ft/expand-to m p))
        (when (active? t) (file-tree-pane/select-path! tree-pane p))))))

(defn- read-launch-file
  "{:text :file-nul} for the launch file: its text, nil when there is no
  file or it can't be read, and the line of a NUL in it. A file argument
  that doesn't exist opens an empty editor with the path remembered
  anyway, so Save creates it. A launch file holding a NUL is refused like
  any open: its path must NOT be remembered (Save would write \"\" over
  the binary), but `file` stays bound so the session-reopen gate (when-not
  file) is untouched."
  [file]
  (let [text (when file (try (slurp file) (catch :default _ nil)))]
    {:text text :file-nul (when text (tabs/nul-line text))}))

(defn- new-session-state
  "The tab ctx's live session saving state: whether it is live, the last
  snapshot written, the last error said, whether a reopen is under way,
  and the entry that reopen will open. Each an atom."
  []
  {:session-live? (atom false) :session-last (atom nil) :session-error (atom nil)
   :session-restoring? (atom false) :session-pending (atom nil)})

(defn- status-bar-ctx
  "The tab ctx's status bar keys: the Parinfer mode read fn, the hook's
  status fn and the label's update, and the key hints label with its
  toggle state. w is build-tab-ctx's."
  [{:keys [parinfer-mode* parinfer-status-fn update-parinfer-label! hint-label hints-on?]}]
  {:parinfer-mode-read (fn [] @parinfer-mode*)
   :parinfer-status-fn parinfer-status-fn
   :parinfer-label-update! update-parinfer-label!
   :hint-label hint-label :hints-on? hints-on?})

(defn- build-tab-ctx
  "startup!'s first bindings, in activate!'s order: sel-ctx (the runtime switch's context), the launch file's text and the line of a NUL in it, tree-saved!, which shows a saved file in the tree, and tab-ctx, the tab operations' context. w holds activate!'s bindings they read, under activate!'s names, with :folder-result* (tree-ops' *folder-result atom). Returns w with :sel-ctx :text :file-nul and :tab-ctx added."
  [{:keys [cv status-label sel* ev* active-tab file tree-pane active? win line-col-label close-popovers-fn register-popover-fn source-ctx settings-path* inspect-last-fn inspect-form-fn open-find-fn open-replace-fn tabs next-id notebook theme-name completion-opts active-id popovers window-key-args repl-complete-fn part-contribution part-error! recent-tabs] *folder-result :folder-result* :as w}]
  (let [sel-ctx (make-sel-ctx w)
        tab-ctx (merge
                 (new-session-state)
                 (status-bar-ctx w)
                 {:win win :cv cv :ev* ev* :sel* sel* :status-label status-label
                  :line-col-label line-col-label :close-popovers-fn close-popovers-fn
                  :register-popover-fn register-popover-fn :active? active? :source-ctx source-ctx
                  :sel-ctx sel-ctx :settings-path* settings-path* :inspect-last-fn inspect-last-fn
                  :inspect-form-fn inspect-form-fn :open-find-fn open-find-fn
                  :open-replace-fn open-replace-fn :tabs tabs :next-id next-id :notebook notebook
                  :theme-name theme-name :completion-opts completion-opts
                  :active-id active-id :active-tab active-tab :popovers popovers
                  ;; A save (or a path change) under the root
                  ;; expands to the file and highlights it when its tab is active.
                  :tree-pane tree-pane :tree-saved! (make-tree-saved! tree-pane active?)
                  :folder-result* *folder-result
                  :window-key-args window-key-args :repl-complete-fn repl-complete-fn
                  :part-contribution part-contribution :part-error! part-error!
                  :recent-tabs recent-tabs})]
    (assoc (merge w (read-launch-file file)) :sel-ctx sel-ctx :tab-ctx tab-ctx)))

(defn- build-first-tab
  "startup!'s next bindings, in activate!'s order: the first tab, made from the launch file (empty when it holds a NUL), the tree's saved settings, and folder-result!, Open Folder...'s result handler. w holds what they read: :tab-ctx :file-nul :file :text :settings-path* :tree-pane :tabs :sel-ctx :part-contribution :part-error! :cv. Returns w with :first-tab :tree-settings and :folder-result! added."
  [{:keys [tab-ctx file-nul file text settings-path* tree-pane tabs sel-ctx part-contribution part-error! cv] :as w}]
  (let [first-tab (win-tabs/add-tab! tab-ctx {:path (when-not file-nul file)
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
    (assoc w :first-tab first-tab :tree-settings tree-settings :folder-result! folder-result!)))

(defn- show-first-tab!
  "startup!'s first step: the first tab made active and the first in the recent list, the Parinfer label for a launch on a project file, and tab-ctx and folder-result! published to *tab-ctx and *folder-result."
  [{:keys [active-id first-tab recent-tabs update-parinfer-label! tab-ctx folder-result!] *tab-ctx :tab-ctx* *folder-result :folder-result*}]
  ;; switch-page fired inside that first append, before the tab was in
  ;; tabs, so the tab is made active here, and starts the recent list.
  (reset! active-id (:id first-tab))
  (reset! recent-tabs [(:id first-tab)])
  ;; A launch on a project file: the first tab never goes through
  ;; on-switch!, so its override reaches the label here.
  (update-parinfer-label!)
  (reset! *tab-ctx tab-ctx)
  (reset! *folder-result folder-result!))

(defn- accept-opens!
  "startup!'s second step: Open With and Dock drops, through jonquil.gtk.window's current-report and current-open-paths atoms."
  [{:keys [current-report status-label current-open-paths folder-result! files-btn] *tab-ctx :tab-ctx*}]
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
                      (g/gtk-toggle-button-set-active files-btn 1)
                      ;; and its own remembered tabs, in place of the last
                      ;; project's that a launch from Finder was about to open
                      (win-reopen/folder-arrived! @*tab-ctx))
                  (when-let [err (:error (win-tabs/open-file! @*tab-ctx p))]
                    (win-status/set-status! status-label (str "Couldn't open " (.getName (java.io.File. ^String p)) ": " err))))
                (catch :default e
                  (win-status/set-status! status-label (str "Couldn't open " p ": " (ex-message e)))))))))

(defn- start-tree!
  "startup!'s third step: the tree's root, Open Folder..., then the file-tree part's :tree-start! (or the tree hidden without it)."
  [{:keys [tree-pane dir file cwd win folder-result! cv part-contribution tree-settings] *open-folder :open-folder*}]
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
    (g/gtk-widget-set-visible (:root tree-pane) 0)))

(defn- start-notebook!
  "startup!'s fourth step: the notebook's switch-page handler, the saved theme, and the console's refusal of a launch file holding a NUL or its warning about one that isn't UTF-8."
  [{:keys [notebook tab-ctx theme-ctx settings-path* file-nul cv file text]}]
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
                   "This file isn't valid UTF-8; unreadable bytes show as � and saving will replace them.\n")))

(defn- start-runtime!
  "startup!'s fifth step: the starting evaluator's selection, connection, dialect label, Reset on Run and prompt (a throw said in the console), then its start."
  [{:keys [cv dialect-label sel* ev* reset-chk reset-state status-label]}]
  (try
    (win-runtime/show-selection! cv dialect-label @sel*)
    ;; The starting evaluator's connection, label and Reset on Run, as
    ;; apply-selection! does for every later one.
    (win-runtime/watch-connection! @ev* {:ev* ev* :sel* sel* :dialect-label dialect-label :cv cv :status-label status-label})
    (win-runtime/apply-reset-toggle! reset-chk reset-state @sel*)
    (g/gtk-label-set-text dialect-label (win-runtime/dialect-text @sel* @ev*))
    (win-runtime/refresh-prompt! cv ev*)
    (catch :default e
      (cview/append! cv :error (str "; project: " (ex-message e) "\n"))))
  (win-runtime/start-and-report! cv status-label @ev* @sel*))

(defn- launch-session-entry
  "The last quit's session entry for the launch's project, with the tree
  re-rooted to it, or nil when there is none or reading it threw (said on
  the console)."
  [tab-ctx cv]
  (try (win-reopen/session-entry! tab-ctx {:reroot? true})
       (catch :default e
         (cview/append! cv :error (str "; reopen tabs: " (ex-message e) "\n"))
         nil)))

(defn- say-login-path!
  "Says where the child tools' PATH came from on the console. The wait for
  the lookup is off the GTK thread; the append goes through on-gui!, and a
  window closed meanwhile must not throw out of the future. Returns the
  future."
  [cv]
  (future
    (try (when-let [line (login-path/console-line)]
           (on-gui! (fn [] (cview/append! cv :system (str line "\n")))))
         (catch :default _ nil))))

(defn- go-live!
  "The launch's tabs are open, so from here on every change to them is
  saved as it happens (session-changed!). The state the launch ends in
  isn't written: a launch on one file would otherwise replace the folder's
  remembered tabs before anything changed. A re-root alone doesn't save
  either, for the same reason; the next tab change does. Runs the parts'
  :on-start hooks."
  [tab-ctx part-contribution part-error!]
  (reset! (:session-last tab-ctx) (win-session/session-snapshot tab-ctx))
  (reset! (:session-live? tab-ctx) true)
  (parts/run-hooks! part-contribution :on-start [] part-error!))

(defn- reopen!
  "startup!'s last step: a launch that named no file reopens the last quit's tabs; then the child tools' PATH line, the window shown with the active tab focused, and live session saving once the reopen is done."
  [{:keys [file tab-ctx cv part-contribution part-error! active-tab win]}]
  ;; A launch that named no file reopens the
  ;; tabs open at the last quit in this project. The entry, and any
  ;; re-root, come now, so the tree shows its folder from the first
  ;; frame. The files open after the window is up (reopen-later!).
  (let [entry (when-not file (launch-session-entry tab-ctx cv))
        ;; The launch's tabs are open, so from here on every change to
        ;; them is saved as it happens (session-changed!). The state the
        ;; launch ends in isn't written: a launch on one file would
        ;; otherwise replace the folder's remembered tabs before
        ;; anything changed. A re-root alone doesn't save either, for the
        ;; same reason; the next tab change does.
        live! (fn [] (go-live! tab-ctx part-contribution part-error!))]
    ;; Say where the child tools' PATH came from. The wait for the
    ;; lookup is off the GTK thread; the append goes through on-gui!, and
    ;; a window closed meanwhile must not throw out of the future.
    (say-login-path! cv)
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

(defn startup!
  "activate!'s startup, in activate!'s order: startup!'s bindings (tab-ctx and the first tab, with folder-result!), then its steps (the first tab shown, Open With and Dock drops, the tree, the notebook and the theme, the runtime, the reopen), then the plugins part's :plugins-start!. w holds activate!'s bindings the steps read, under activate!'s names, with :tab-ctx* :folder-result* and :open-folder* (the forward atoms) and :current-report and :current-open-paths (jonquil.gtk.window's atoms). Called once per window; activate! reads nothing it returns."
  [{:keys [part-contribution] :as w}]
  (let [s (-> w build-tab-ctx build-first-tab)]
    (show-first-tab! s)
    (accept-opens! s)
    (start-tree! s)
    (start-notebook! s)
    (start-runtime! s)
    (reopen! s))
  ;; The plugins part's :plugins-start!: the console, the reviews
  ;; callback, the effects, the startup load and the save hook, set once
  ;; the window is up.
  (when-let [f (:plugins-start! (:fns part-contribution))] (f)))
