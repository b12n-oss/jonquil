(ns jonquil.gtk.parts.menus
  "The window's menus as a window part (jonquil.parts.menus/spec): a menu
  bar on macOS and a menu button at the end of the toolbar elsewhere, both
  from jonquil/menus.edn, each item running its command as the palette
  would and showing the key the live key map gives it."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.menus :as gm]
            [jonquil.menus :as menus]
            [jonquil.plugin-registry :as plugin-registry]))

(defn- mac? [] (= "Mac OS X" (System/getProperty "os.name")))

(defn- live-tree
  "The menus with the keys of the live key map."
  []
  (let [plugins @plugin-registry/live]
    (menus/tree {:keymap {:window (plugin-registry/window-bindings plugins)
                          :editor (plugin-registry/editor-bindings plugins)}
                 :os (System/getProperty "os.name")})))

(defn- run-fn
  "Runs a command the way the palette does, on the active tab."
  [{:keys [active-tab]}]
  (fn [id]
    ((deref (:on-run (:palette (active-tab)))) id)))

(defn- show!
  "Shows model: the system menu bar on macOS, else a menu button at the
  end of the toolbar, which is the Files button's box. Answers the button,
  or nil."
  [{:keys [win files-btn]} model]
  (if (mac?)
    (let [app (g/gtk-window-get-application win)]
      (when-not (ffi/null? app) (g/gtk-application-set-menubar app model))
      nil)
    (let [button (gm/menu-button! model)
          toolbar (g/gtk-widget-get-parent files-btn)]
      (when-not (ffi/null? toolbar) (g/gtk-box-append toolbar button))
      button)))

(defn- install
  "The menus part for one window: builds the menus and their actions and
  answers :menu-yield-fn, which the window's key handler calls before it
  passes a key on. ui's :menus is {:group :model :button :yield! :yielded?}
  for the self-tests."
  [{:keys [win set-status!] :as wctx}]
  (let [tree (live-tree)
        group (gm/actions! tree (run-fn wctx) set-status!)
        model (gm/model! tree)
        yielded? (atom false)
        yield! (fn [] (gm/yield! win group yielded?))]
    (gm/attach! win group)
    {:fns {:menu-yield-fn yield!}
     :ui {:menus {:group group :model model :button (show! wctx model)
                  :yield! yield! :yielded? yielded?}}}))

(def part
  "The menus part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :menus}))
