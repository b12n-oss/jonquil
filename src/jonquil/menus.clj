(ns jonquil.menus
  "The menus as data: what jonquil/menus.edn lists, checked against the
  registered commands, and each item with the action name and the key the
  window half shows for it. No GTK here; jonquil.gtk.menus builds the
  widgets.

  A menu is {:label s :sections [[item ...] ...]}. An item is {:command id
  :label s}, or a submenu of the same shape as a menu."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.palette :as palette]))

(defmacro ^:private embedded-text
  "The text of classpath file path, read when this form is compiled (the
  installed app has no source tree)."
  [path]
  (slurp (io/resource path)))

(def shipped
  "The shipped menus, from jonquil/menus.edn."
  (edn/read-string (embedded-text "jonquil/menus.edn")))

(defn- submenu? [item] (contains? item :sections))

(defn items
  "Every command item under menu, submenus' included, in order."
  [menu]
  (for [section (:sections menu)
        item section
        leaf (if (submenu? item) (items item) [item])]
    leaf))

(defn command-ids
  "Every command the menus name, in order."
  [menus]
  (mapcat #(map :command (items %)) menus))

;; --- checks -----------------------------------------------------------------

(defn- menu-problems
  "What's wrong under menu, each sentence starting with its path."
  [path {:keys [label sections]} commands]
  (let [here (str/join " > " (conj path (or label "?")))]
    (concat
     (when (str/blank? label) [(str here " has no label")])
     (when-not (and (sequential? sections) (seq sections)) [(str here " has no sections")])
     (for [section sections :when (empty? section)]
       (str here " has an empty section"))
     (for [section sections
           item section
           p (cond
               (submenu? item) (menu-problems (conj path (or label "?")) item commands)
               (str/blank? (:label item)) [(str here ": " (pr-str (:command item)) " has no label")]
               (not (contains? commands (:command item)))
               [(str here ": " (pr-str (:command item)) " isn't a command")])]
       p))))

(defn problems
  "What's wrong with menus, one sentence each, given the registered commands
  {id ...}. Empty when all is well."
  [menus commands]
  (vec (concat
        (mapcat #(menu-problems [] % commands) menus)
        (for [[id n] (frequencies (command-ids menus)) :when (> n 1)]
          (str (pr-str id) " is listed twice")))))

;; --- actions and keys -------------------------------------------------------

(defn action-name
  "The GAction name for command id: its namespace and name joined by a dot,
  \"file.save-as\" for :file/save-as."
  [id]
  (str (namespace id) "." (name id)))

(defn command-of
  "action-name's inverse."
  [action]
  (let [i (str/index-of action ".")]
    (keyword (subs action 0 i) (subs action (inc i)))))

(def ^:private accel-modifiers
  ;; GTK's accelerator syntax, in the order GTK itself prints them
  [[:shift "<Shift>"] [:alt "<Alt>"] [:ctrl "<Control>"] [:meta "<Meta>"] [:super "<Super>"]])

(defn accel
  "binding as a GTK accelerator string, \"<Shift><Meta>s\": :cmd is Meta on
  macOS and Control elsewhere, and a :keys binding shows its first key by
  name."
  [binding os]
  (let [mods (set (or (:exact binding) (:mods binding)))
        command (if (= "Mac OS X" os) :meta :ctrl)
        held (cond-> (disj mods :cmd) (contains? mods :cmd) (conj command))
        names (sort (:keys binding))
        ;; the main keyboard's key ahead of the keypad's twin (Return, KP_Enter)
        k (or (:key binding) (first (remove #(str/starts-with? % "KP_") names)) (first names))]
    (str (apply str (for [[m text] accel-modifiers :when (contains? held m)] text)) k)))

(defn- shown-binding
  "The binding whose key the menu shows for command id: the first in the
  window's then the editor's keys that runs it under no condition. A key
  with a :when works only some of the time, and a menu item showing it
  would take the key at the other times."
  [keymap id]
  (first (for [scope [:window :editor]
               b (get keymap scope)
               ;; nor a key sequence: a menu item can show one chord only
               :when (and (= id (:command b)) (not (:when b)) (not (:then b)))]
           b)))

(defn- sequence-binding
  "The key sequence the menu names for command id when no single key runs
  it: the first binding with a :then and no condition."
  [keymap id]
  (first (for [scope [:window :editor]
               b (get keymap scope)
               :when (and (= id (:command b)) (:then b) (not (:when b)))]
           b)))

(def ^:private mac-symbols
  ;; the signs a Mac's menus draw for the modifiers
  [["Cmd+" "⌘"] ["Ctrl+" "⌃"] ["Option+" "⌥"] ["Shift+" "⇧"]])

(defn keys-text
  "binding's chords for the end of a menu item's label: as the palette
  shows them, with a Mac's signs for the modifiers on macOS."
  [binding os]
  (let [text (palette/chord-label binding os)]
    (if (= "Mac OS X" os)
      (reduce (fn [t [word sign]] (str/replace t word sign)) text mac-symbols)
      text)))

(defn- with-keys
  "menu with each command item given :action and :accel (nil with no key).
  An item whose command has only a key sequence gets :keys-text, the
  sequence as text, since an accelerator is one chord."
  [menu keymap os]
  (update menu :sections
          (fn [sections]
            (mapv (fn [section]
                    (mapv (fn [item]
                            (if (submenu? item)
                              (with-keys item keymap os)
                              (let [b (shown-binding keymap (:command item))
                                    s (when-not b (sequence-binding keymap (:command item)))]
                                (cond-> (assoc item
                                               :action (action-name (:command item))
                                               :accel (when b (accel b os)))
                                  s (assoc :keys-text (keys-text s os))))))
                          section))
                  sections))))

(defn tree
  "menus with each item's action name and key, for the window half:
  {:menus (default shipped) :keymap {:window [...] :editor [...]} :os s}."
  [{:keys [menus keymap os] :or {menus shipped}}]
  (mapv #(with-keys % keymap os) menus))
