(ns jonquil.gtk.menus
  "The window's menus as widgets, from jonquil.menus/tree: a GMenu model,
  one action per command in a group the window holds under the prefix
  \"menu\", the macOS menu bar or a menu button elsewhere, and yield!, which
  takes the actions away for one key.

  A menu item shows its command's key, and on macOS a menu item with a key
  also fires on that key whenever the window's own key handler passes the
  key on (measured 2026-11-14: the handler sees every key first, and a key
  it answers never reaches the menu). The handler passes keys on on
  purpose, to a text entry or to the editor's own handler, and the menu
  must not take those. So before it passes one on it calls yield!: the
  action group comes off the window, GTK finds no action for the key and
  hands it to the focused widget, and the group goes back on idle."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.ffi :as g]
            [jonquil.menus :as menus]))

(def PREFIX "The action group's prefix on the window." "menu")

(defn- item!
  "A GMenuItem for item, showing its key when it has one."
  [{:keys [label action accel]}]
  (let [mi (g/g-menu-item-new label (str PREFIX "." action))]
    (when accel
      (g/g-menu-item-set-attribute-value mi "accel" (g/g-variant-new-string accel)))
    mi))

(defn- menu!
  "A GMenu for sections: one section each, submenus built the same way."
  [sections]
  (let [menu (g/g-menu-new)]
    (doseq [section sections]
      (let [sm (g/g-menu-new)]
        (doseq [item section]
          (if (:sections item)
            (g/g-menu-append-submenu sm (:label item) (menu! (:sections item)))
            (let [mi (item! item)]
              (g/g-menu-append-item sm mi)
              (g/g-object-unref mi))))
        (g/g-menu-append-section menu ffi/null sm)))
    menu))

(defn model!
  "The GMenu for tree (jonquil.menus/tree): one submenu per menu."
  [tree]
  (let [bar (g/g-menu-new)]
    (doseq [{:keys [label sections]} tree]
      (g/g-menu-append-submenu bar label (menu! sections)))
    bar))

(defn actions!
  "{:group :by-id}: a GSimpleActionGroup with one action per command of
  tree, and each action by its command id. Activating one calls (run!
  command-id); a throw goes to (on-error message), never into GTK, where it
  would end the process. A command in ticked, a set of ids, gets a boolean
  state, which the menus draw as a tick (check! sets it)."
  [tree run! on-error ticked]
  (let [group (g/g-simple-action-group-new)
        by-id (volatile! {})]
    (doseq [menu tree
            {:keys [command action]} (menus/items menu)]
      (let [a (if (contains? ticked command)
                (g/g-simple-action-new-stateful action ffi/null (g/g-variant-new-boolean 0))
                (g/g-simple-action-new action ffi/null))]
        (vswap! by-id assoc command a)
        (g/g-signal-connect-data a "activate"
                                 (callable (fn [_action _param _data]
                                             (try (run! command)
                                                  (catch :default e
                                                    (on-error (str "; menu: " (ex-message e)))))
                                             nil)
                                           [:pointer :pointer :pointer] :void)
                                 ffi/null ffi/null g/CONNECT-DEFAULT)
        (g/g-action-map-add-action group a)))
    {:group group :by-id @by-id}))

(defn check!
  "Ticks or unticks a stateful action, and greys it out when it can't be
  changed now."
  [action on? enabled?]
  (g/g-simple-action-set-state action (g/g-variant-new-boolean (if on? 1 0)))
  (g/g-simple-action-set-enabled action (if enabled? 1 0))
  nil)

(defn attach!
  "Puts group on win under PREFIX, so the menus find their actions."
  [win group]
  (g/gtk-widget-insert-action-group win PREFIX group))

(defn yield!
  "Takes the menu's actions off win for the key being handled, and puts
  them back on idle. yielded? is an atom, true in between, so several keys
  in one turn schedule one return."
  [win group yielded?]
  (when (compare-and-set! yielded? false true)
    (g/gtk-widget-insert-action-group win PREFIX ffi/null)
    (on-gui! (fn []
               (attach! win group)
               (reset! yielded? false))))
  nil)

(defn offered?
  "Whether win offers the menu action for command id right now. It
  activates the action when it does, so only a test asks."
  [win id]
  (pos? (g/gtk-widget-activate-action-variant win (str PREFIX "." (menus/action-name id)) ffi/null)))
