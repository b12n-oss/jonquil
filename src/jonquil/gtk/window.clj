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

(defn- build-frame
  "The window map's first stages: app and opts through the startup, status
  bar, quit, console and theme builders and the right-hand panels."
  [app opts]
  (-> (assoc opts :app app :current-report current-report :current-open-paths current-open-paths)
      win-startup/build
      win-status-bar/build
      win-quit/build
      win-console/build
      win-theme/build
      win-right-slot/build-panels))

(defn- build-workspace
  "The middle stages: the tab fns, then help, completion, run, tabs, the
  inspector and the right-hand slot."
  [w]
  (-> (assoc w :open-file! win-tabs/open-file! :select-tab! win-tabs/select-tab! :tab-by-id win-tabs/tab-by-id)
      win-help/build
      win-console/build-completion
      win-run/build
      win-tabs/build
      win-inspector/build
      win-right-slot/build
      win-right-slot/publish))

(defn- build-bindings
  "The last stages: the key fns, the tree and layout, the parts, the console
  menu and the keys."
  [w]
  (-> w
      win-help/build-source-keys
      win-status-bar/build-parinfer
      win-context/build-key-fns
      ;; win-tree-ops/build makes the tree's pane, and win-layout/build the
      ;; Files toggle, the tree's outer paned, the editor and console
      ;; paned, the toolbar and its buttons, the column and the key
      ;; controller.
      win-tree-ops/build
      win-layout/build
      win-context/build-parts
      win-context/build-console-menu
      win-context/build-keys))

(defn- build-window
  "The window map for app: opts run through each build stage in turn."
  [app opts]
  (-> (build-frame app opts)
      build-workspace
      build-bindings))

(defn- activate! [app {:keys [on-ready] :as opts}]
  (let [w (build-window app opts)]
    (win-layout/place! w)
    (win-theme/connect! w)
    (win-layout/connect! w)
    (win-startup/connect-quit! w)
    (win-help/connect! w)
    (win-startup/startup! w)
    (when on-ready (on-ready (win-ui/ui-map w)))))

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
  text read it back. :activate-menu-action!*/:repl-
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
