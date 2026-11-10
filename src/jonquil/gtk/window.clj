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
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.console :as win-console]
            [jonquil.gtk.window.context :as win-context]
            [jonquil.gtk.window.help :as win-help]
            [jonquil.gtk.window.inspector :as win-inspector]
            [jonquil.gtk.window.layout :as win-layout]
            [jonquil.gtk.window.quit :as win-quit]
            [jonquil.gtk.window.right-slot :as win-right-slot]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.startup :as win-startup]
            [jonquil.gtk.window.status-bar :as win-status-bar]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.gtk.window.theme :as win-theme]
            [jonquil.gtk.window.tree-ops :as win-tree-ops]
            [jonquil.gtk.window.ui :as win-ui]
            [jonquil.nrepl.attach]
            [jonquil.nrepl.cljs-node]
            [jonquil.nrepl.jank]
            [jonquil.nrepl.jvm]
            [jonquil.nrepl.shadow]
            [jonquil.session]))

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
  (let [{:keys [settings-path* plugins-on* sel* ev* session evaluating-fn tabs active-id next-id active-tab active? notebook recent-tabs win] *tab-ctx :tab-ctx*}
        (win-startup/build {:app app :file file :dir dir :settings-path settings-path :plugins? plugins?})
        {:keys [status-label line-col-label hint-label hints-on? parinfer-label pending-label dialect-label
                unread-label hints-toggle-fn]}
        (win-status-bar/build {:sel* sel*})
        {:keys [confirmed-close? suppress-close? closing?]}
        (win-quit/build {})
        {:keys [bang* cv]}
        (win-console/build {:active-tab active-tab :ev* ev* :status-label status-label :evaluating-fn evaluating-fn})
        {:keys [provider theme-name theme-choice system-dark-fn* poll-armed? arm-count
                zoom-fn theme-ctx set-theme-fn cycle-theme-fn]}
        (win-theme/build {:tabs tabs :cv cv :settings-path* settings-path* :status-label status-label
                          :evaluating-fn evaluating-fn :closing? closing?})
        {:keys [sp rp lp hp] *right :right*}
        (win-right-slot/build-panels {})
        {:keys [popovers close-popovers-fn register-popover-fn source-ctx server-ev completion-ns-fn completion-opts]}
        (win-help/build {:ev* ev* :status-label status-label :sp sp :sel* sel* :active? active? :active-tab active-tab
                         :open-file! win-tabs/open-file! :tab-ctx* *tab-ctx :right* *right})
        {:keys [repl-completion repl-complete-fn]}
        (win-console/build-completion {:cv cv :ev* ev* :status-label status-label :completion-ns-fn completion-ns-fn
                                       :server-ev server-ev :close-popovers-fn close-popovers-fn})
        {:keys [run-fn stop-fn run-selection-fn run-changed-fn]}
        (win-run/build {:active-tab active-tab :cv cv :ev* ev* :sel* sel* :status-label status-label})
        {:keys [close-fn new-tab-fn step-tab-fn digit-tab-fn]}
        (win-tabs/build {:tabs tabs :active-id active-id :active-tab active-tab :tab-ctx* *tab-ctx})
        {:keys [h-paned inspector-state inspecting? inspect-queued inspector-last-args on-view on-inspector-close pane]}
        (win-inspector/build {:active-tab active-tab :cv cv :ev* ev* :status-label status-label :right* *right})
        {:keys [slot part-panels* right panel-focus-fn]}
        (win-right-slot/build {:win win :h-paned h-paned :pane pane :sp sp :rp rp :lp lp :hp hp
                               :settings-path* settings-path* :status-label status-label})
        _ (win-right-slot/publish {:right right :right* *right})
        {:keys [source-jump-fn source-close-fn source-back-fn source-open-fn jump-back-fn source-keys]}
        (win-help/build-source-keys {:source-ctx source-ctx :active-tab active-tab :right right :sp sp :win win :tabs tabs
                                     :open-file! win-tabs/open-file! :select-tab! win-tabs/select-tab! :tab-by-id win-tabs/tab-by-id
                                     :tab-ctx* *tab-ctx})
        {:keys [parinfer-mode* update-parinfer-label! parinfer-status-fn parinfer-fn]}
        (win-status-bar/build-parinfer {:settings-path* settings-path* :active-tab active-tab :parinfer-label parinfer-label
                                        :evaluating-fn evaluating-fn :status-label status-label :cv cv})
        {:keys [open-fn save-fn save-as-fn inspect-last-ctx inspect-last-fn inspect-form-fn close-inspector-fn
                toggle-focus-fn clear-console-fn clear-last-fn open-find-fn open-replace-fn]}
        (win-context/build-key-fns {:cv cv :win win :active-tab active-tab :ev* ev* :sel* sel* :status-label status-label
                                    :right right :pane pane :inspector-state inspector-state :inspecting? inspecting?
                                    :inspect-queued inspect-queued :inspector-last-args inspector-last-args
                                    :on-view on-view :tab-ctx* *tab-ctx})
        ;; win-tree-ops/build makes the tree's pane, and win-layout/build the
        ;; Files toggle, the tree's outer paned, the editor and console
        ;; paned, the toolbar and its buttons, the column and the key
        ;; controller.
        {:keys [tree-pane] *open-folder :open-folder* *folder-result :folder-result*}
        (win-tree-ops/build {:status-label status-label :tab-ctx* *tab-ctx})
        {:keys [files-btn tree-paned paned toolbar run-btn stop-btn open-btn save-btn reset-chk reset-state vbox keyctl]}
        (win-layout/build {})
        {:keys [part-error! part-contribution]}
        (win-context/build-parts {:win win :status-label status-label :cv cv :ev* ev* :sel* sel* :tabs tabs
                                  :active-tab active-tab :settings-path* settings-path* :bang* bang*
                                  :recent-tabs recent-tabs :right right :tree-pane tree-pane :tree-paned tree-paned
                                  :paned paned :files-btn files-btn :panel-focus-fn panel-focus-fn
                                  :unread-label unread-label :plugins-on* plugins-on* :rp rp :lp lp :hp hp
                                  :pending-label pending-label :inspect-last-ctx inspect-last-ctx
                                  :source-ctx source-ctx :part-panels* part-panels* :tab-ctx* *tab-ctx})
        {:keys [repl-activate-menu-action-fn]}
        (win-context/build-console-menu {:cv cv :repl-complete-fn repl-complete-fn :clear-last-fn clear-last-fn
                                         :part-contribution part-contribution :status-label status-label})
        {:keys [window-key-args window-key-fn]}
        (win-context/build-keys {:panel-focus-fn panel-focus-fn :hints-toggle-fn hints-toggle-fn :run-changed-fn run-changed-fn
                                 :tabs tabs :recent-tabs recent-tabs :status-label status-label :run-fn run-fn
                                 :stop-fn stop-fn :open-fn open-fn :save-fn save-fn :save-as-fn save-as-fn
                                 :close-fn close-fn :run-selection-fn run-selection-fn :toggle-focus-fn toggle-focus-fn
                                 :clear-console-fn clear-console-fn :clear-last-fn clear-last-fn :zoom-fn zoom-fn
                                 :evaluating-fn evaluating-fn :win win :active-tab active-tab :open-find-fn open-find-fn
                                 :open-replace-fn open-replace-fn :cycle-theme-fn cycle-theme-fn
                                 :close-popovers-fn close-popovers-fn :inspect-last-fn inspect-last-fn
                                 :inspect-form-fn inspect-form-fn :pane pane :close-inspector-fn close-inspector-fn
                                 :source-keys source-keys :new-tab-fn new-tab-fn :step-tab-fn step-tab-fn
                                 :digit-tab-fn digit-tab-fn :parinfer-fn parinfer-fn
                                 :part-contribution part-contribution :tab-ctx* *tab-ctx})]
    (win-layout/place! {:toolbar toolbar :files-btn files-btn :run-btn run-btn :stop-btn stop-btn :open-btn open-btn
                        :save-btn save-btn :reset-chk reset-chk :paned paned :notebook notebook :cv cv
                        :h-paned h-paned :pane pane :sp sp :slot slot :rp rp :lp lp :hp hp :part-panels* part-panels*
                        :vbox vbox :tree-paned tree-paned :tree-pane tree-pane :win win :status-label status-label
                        :hint-label hint-label :pending-label pending-label :unread-label unread-label
                        :dialect-label dialect-label :parinfer-label parinfer-label :line-col-label line-col-label
                        :update-parinfer-label! update-parinfer-label!})
    (win-theme/connect! {:cv cv :provider provider :theme-name theme-name :sp sp :pane pane})
    (win-layout/connect! {:run-btn run-btn :stop-btn stop-btn :open-btn open-btn :save-btn save-btn :reset-chk reset-chk
                          :keyctl keyctl :win win :status-label status-label :run-fn run-fn :stop-fn stop-fn
                          :open-fn open-fn :save-fn save-fn :ev* ev* :window-key-fn window-key-fn})
    (win-startup/connect-quit! {:tabs tabs :cv cv :win win :app app :confirmed-close? confirmed-close? :suppress-close? suppress-close? :closing? closing? :popovers popovers :ev* ev* :part-contribution part-contribution :part-error! part-error! :tab-ctx* *tab-ctx})
    (win-help/connect! {:cv cv :status-label status-label :sp sp :source-jump-fn source-jump-fn :source-open-fn source-open-fn :source-close-fn source-close-fn :register-popover-fn register-popover-fn :pane pane :repl-completion repl-completion :repl-complete-fn repl-complete-fn})
    (win-startup/startup! {:part-contribution part-contribution :cv cv :status-label status-label :dialect-label dialect-label :sel* sel* :ev* ev* :reset-chk reset-chk :reset-state reset-state :right right :inspector-state inspector-state :active-tab active-tab :file file :tree-pane tree-pane :active? active? :win win :line-col-label line-col-label :close-popovers-fn close-popovers-fn :register-popover-fn register-popover-fn :source-ctx source-ctx :settings-path* settings-path* :inspect-last-fn inspect-last-fn :inspect-form-fn inspect-form-fn :open-find-fn open-find-fn :open-replace-fn open-replace-fn :tabs tabs :next-id next-id :notebook notebook :theme-name theme-name :completion-opts completion-opts :active-id active-id :popovers popovers :window-key-args window-key-args :repl-complete-fn repl-complete-fn :parinfer-mode* parinfer-mode* :parinfer-status-fn parinfer-status-fn :update-parinfer-label! update-parinfer-label! :part-error! part-error! :recent-tabs recent-tabs :hint-label hint-label :hints-on? hints-on? :files-btn files-btn :dir dir :cwd cwd :theme-ctx theme-ctx :current-report current-report :current-open-paths current-open-paths :folder-result* *folder-result :tab-ctx* *tab-ctx :open-folder* *open-folder})
    (when on-ready (on-ready (win-ui/ui-map {:tabs tabs :part-contribution part-contribution :cv cv :session session :status-label status-label :win win :notebook notebook :app app :active-tab active-tab :parinfer-label parinfer-label :parinfer-fn parinfer-fn :line-col-label line-col-label :ev* ev* :sel* sel* :dialect-label dialect-label :reset-chk reset-chk :window-key-args window-key-args :hint-label hint-label :unread-label unread-label :window-key-fn window-key-fn :run-btn run-btn :stop-btn stop-btn :open-btn open-btn :save-btn save-btn :tree-pane tree-pane :files-btn files-btn :tree-paned tree-paned :h-paned h-paned :current-open-paths current-open-paths :run-fn run-fn :stop-fn stop-fn :run-selection-fn run-selection-fn :toggle-focus-fn toggle-focus-fn :clear-console-fn clear-console-fn :clear-last-fn clear-last-fn :zoom-fn zoom-fn :set-theme-fn set-theme-fn :theme-name theme-name :theme-choice theme-choice :system-dark-fn* system-dark-fn* :theme-ctx theme-ctx :arm-count arm-count :poll-armed? poll-armed? :provider provider :cycle-theme-fn cycle-theme-fn :settings-path* settings-path* :close-popovers-fn close-popovers-fn :register-popover-fn register-popover-fn :repl-activate-menu-action-fn repl-activate-menu-action-fn :repl-complete-fn repl-complete-fn :repl-completion repl-completion :pane pane :inspect-last-fn inspect-last-fn :inspect-form-fn inspect-form-fn :inspector-state inspector-state :inspector-last-args inspector-last-args :sp sp :slot slot :rp rp :pending-label pending-label :lp lp :right right :panel-focus-fn panel-focus-fn :on-inspector-close on-inspector-close :hp hp :source-jump-fn source-jump-fn :source-open-fn source-open-fn :source-back-fn source-back-fn :jump-back-fn jump-back-fn :source-close-fn source-close-fn :confirmed-close? confirmed-close? :closing? closing? :suppress-close? suppress-close? :folder-result* *folder-result :tab-ctx* *tab-ctx})))))

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
