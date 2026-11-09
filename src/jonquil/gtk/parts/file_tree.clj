(ns jonquil.gtk.parts.file-tree
  "The file tree's behaviour as a window part (the command is
  jonquil.parts.file-tree/spec): Show/Hide Files, its saved width and
  visibility, its Escape keys' context, its highlight on a tab switch and its
  width save at quit. Moved out of jonquil.gtk.window; the tree's widgets
  (tree-pane, tree-paned, files-btn) stay in the window and come in wctx."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.file-tree-pane :as file-tree-pane]
            [jonquil.settings :as settings]))

(defn- tree-keys
  "The Escape keys' context: whether the tree has focus or its menu is open,
  and how to close the menu or leave the tree for the editor."
  [{:keys [win active-tab tree-pane]}]
  {:focused? (fn [] (file-tree-pane/focused? tree-pane win))
   :leave! (fn [] (editor/focus! (:ed (active-tab))))
   :menu-open? (fn [] (:open? (file-tree-pane/menu-state tree-pane)))
   :close-menu! (fn [] (g/gtk-popover-popdown (:popover tree-pane)))})

(defn- toggle-tree-fn
  [{:keys [files-btn]}]
  (fn [] (g/gtk-toggle-button-set-active
          files-btn
          (if (zero? (g/gtk-toggle-button-get-active files-btn)) 1 0))))

(defn- toggled-handler
  "The files button's toggled handler, the one place that shows, hides and
  saves the tree. applying-start? is true while the starting visibility is
  applied."
  [{:keys [win cv active-tab settings-path* tree-pane tree-paned]} applying-start?]
  (fn [btn]
    (let [show? (pos? (g/gtk-toggle-button-get-active btn))
          width (g/gtk-paned-get-position tree-paned)
          had-focus? (file-tree-pane/focused? tree-pane win)]
      (g/gtk-widget-set-visible (:root tree-pane) (if show? 1 0))
      ;; Hiding the focused tree leaves focus nowhere;
      ;; give it to the editor.
      (when (and (not show?) had-focus?) (editor/focus! (:ed (active-tab))))
      ;; Only a person's toggle is a choice worth saving;
      ;; the starting state isn't.
      (when-not @applying-start?
        (when-let [err (settings/save-settings!
                        @settings-path* {:file-tree {:visible? show? :width (max 1 width)}})]
          (cview/append! cv :system (str "; settings: " err "\n")))))))

(defn- tree-start
  "The tree's starting width and visibility (saved, else shown only for a
  directory launch), and the toggle's handler."
  [{:keys [tree-pane tree-paned files-btn contain-click] :as wctx} applying-start?]
  (fn [{:keys [tree-settings dir]}]
    (g/gtk-paned-set-position tree-paned (or (:width tree-settings) 240))
    (g/g-signal-connect-data files-btn "toggled"
                             (callable (contain-click "files" (toggled-handler wctx applying-start?))
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-set-visible (:root tree-pane) 0)
    (when (if (some? (:visible? tree-settings)) (:visible? tree-settings) (boolean dir))
      (reset! applying-start? true)
      (try (g/gtk-toggle-button-set-active files-btn 1)
           (finally (reset! applying-start? false))))))

(defn- save-width!
  "The tree's width, when it's showing. Answers the save's error or nil."
  [{:keys [settings-path* tree-pane tree-paned]}]
  (when (pos? (g/gtk-widget-get-visible (:root tree-pane)))
    (settings/save-settings!
     @settings-path*
     {:file-tree {:visible? true
                  :width (g/gtk-paned-get-position tree-paned)}})))

(defn- on-quit
  "The quit hook: saves the width and says on stderr when that fails. Never
  throws."
  [wctx]
  (fn []
    (try (when-let [err (save-width! wctx)]
           (binding [*out* *err*]
             (println "jonquil: saving the file tree's width failed:" err)))
         (catch :default e
           (binding [*out* *err*]
             (println "jonquil: saving the file tree's width failed:" (ex-message e)))))))

(defn- install
  "The file tree part for one window."
  [{:keys [tree-pane] :as wctx}]
  (let [applying-start? (atom false)]
    {:fns {:toggle-tree-fn (toggle-tree-fn wctx)
           :tree-keys (tree-keys wctx)
           :tree-start! (tree-start wctx applying-start?)}
     :hooks {:on-quit (on-quit wctx)
             ;; The tree selects the active tab's file (never expanding
             ;; folders for it).
             :on-switch (fn [_ctx t] (file-tree-pane/select-path! tree-pane @(:path t)))}}))

(def part
  "The file tree part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :file-tree}))
