(ns jonquil.gtk.parts.menus
  "The window's menus as a window part (jonquil.parts.menus/spec): a menu
  bar on macOS and a menu button at the end of the toolbar elsewhere, both
  from jonquil/menus.edn, each item running its command as the palette
  would and showing the key the live key map gives it."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.menus :as gm]
            [jonquil.menus :as menus]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.user-keys :as user-keys]))

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
  "Shows model: the system menu bar on macOS, else in button, the menu
  button at the end of the toolbar."
  [{:keys [win]} button model]
  (if (mac?)
    (let [app (g/gtk-window-get-application win)]
      (when-not (ffi/null? app) (g/gtk-application-set-menubar app model)))
    (g/gtk-menu-button-set-menu-model button model)))

(defn- toolbar-button!
  "The menu button at the end of the toolbar (the Files button's box), for
  a system with no menu bar of its own; nil on macOS."
  [{:keys [files-btn]}]
  (when-not (mac?)
    (let [button (gm/menu-button!)
          toolbar (g/gtk-widget-get-parent files-btn)]
      (when-not (ffi/null? toolbar) (g/gtk-box-append toolbar button))
      button)))

(defn- refresh!
  "Rebuilds the menus when the keys they show have changed: shown is an
  atom holding the tree the menus were last built from."
  [wctx button shown]
  (let [tree (live-tree)]
    (when (not= tree @shown)
      (reset! shown tree)
      (show! wctx button (gm/model! tree))))
  nil)

(defn- install
  "The menus part for one window: builds the menus and their actions,
  rebuilds the menus whenever your keys.edn or the plugins change the key
  map, and answers :menu-yield-fn, which the window's key handler calls
  before it passes a key on. The first build runs before keys.edn is read
  (the command palette part loads it), so the same watch brings your own
  keys in at startup. ui's :menus is {:group :button :yield! :yielded?
  :shown :refresh!} for the self-tests."
  [{:keys [win set-status!] :as wctx}]
  (let [group (gm/actions! (live-tree) (run-fn wctx) set-status!)
        button (toolbar-button! wctx)
        shown (atom nil)
        refresh-fn (fn [] (refresh! wctx button shown))
        yielded? (atom false)
        yield! (fn [] (gm/yield! win group yielded?))]
    (gm/attach! win group)
    (refresh-fn)
    (doseq [a [user-keys/live plugin-registry/live]]
      (add-watch a ::menus (fn [_ _ old new] (when (not= old new) (on-gui! refresh-fn)))))
    {:fns {:menu-yield-fn yield!}
     :ui {:menus {:group group :button button :yield! yield! :yielded? yielded?
                  :shown shown :refresh! refresh-fn}}}))

(def part
  "The menus part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :menus}))
