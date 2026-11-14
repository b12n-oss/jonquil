(ns jonquil.key-prefs
  "The Keys tab of Preferences as data: the rows it lists, the chord a key
  press stands for, what that chord would take from, and your keys.edn
  (jonquil.user-keys) after recording it. No GTK here; the window half is
  jonquil.gtk.prefs-window."
  (:require [clojure.string :as str]
            [jonquil.keymap :as km]
            [jonquil.palette :as palette]
            [jonquil.user-keys :as user-keys]))

;; --- the rows ---------------------------------------------------------------

(defn- source-of
  "Where binding b of scope comes from: \"yours\" when keys.edn holds it,
  the key set's name when the chosen set does, the plugin's name for a
  plugin's command, else \"default\"."
  [b scope commands user key-set]
  (cond
    (some #{b} (get user scope)) "yours"
    (some #{b} (get key-set scope)) (:label key-set)
    (:plugin (get commands (:command b))) (name (:plugin (get commands (:command b))))
    :else "default"))

(defn rows
  "The Keys tab's rows: one per binding that runs a command, window keys
  then editor keys in keymap order, then one per command with no key that
  the palette lists, sorted by description. A row is {:scope :command :doc
  :chord :source :binding :fixed?}. key-set is the chosen set, {:label
  :window :editor}, or nil. :chord is \"\" and :binding nil for a
  command with no key. :fixed? marks a binding with a :when condition: it
  belongs to a chain of keys, so the tab shows it and won't record over it."
  [{:keys [keymap commands user os key-set]}]
  (let [bound (for [scope [:window :editor]
                    b (get keymap scope)
                    :let [id (:command b)]
                    :when (and id (contains? commands id))]
                {:scope scope :command id :doc (:doc (get commands id))
                 :chord (palette/chord-label b os) :source (source-of b scope commands user key-set)
                 :binding b :fixed? (boolean (:when b))})
        has-key (set (map :command bound))
        unbound (for [[id c] (sort-by (comp :doc val) commands)
                      :when (and (not (has-key id)) (not (false? (:palette? c))))]
                  {:scope (:scope c) :command id :doc (:doc c)
                   :chord "" :source "" :binding nil :fixed? false})]
    (vec (concat bound unbound))))

(defn filter-rows
  "The rows whose description, chord or source holds text, whatever its
  case. Blank text keeps them all."
  [rows text]
  (let [needle (str/lower-case (str/trim (or text "")))]
    (if (str/blank? needle)
      rows
      (filterv (fn [{:keys [doc chord source]}]
                 (some #(str/includes? (str/lower-case (or % "")) needle) [doc chord source]))
               rows))))

;; --- a key press as a chord -------------------------------------------------

(defn- mac? [os] (= "Mac OS X" os))

(defn- modifier-key?
  "True for a modifier's own keyval (gdkkeysyms.h Shift_L 0xffe1 to Hyper_R
  0xffee, and ISO_Level3_Shift 0xfe03)."
  [keyval]
  (or (<= 0xffe1 keyval 0xffee) (= 0xfe03 keyval)))

(defn- held-modifiers
  "The modifiers in state as a binding names them: the platform's command
  key (Meta on a Mac, Control elsewhere) is :cmd, the rest keep their name."
  [state os]
  (let [held (set (for [[m mask] km/MODIFIER-MASKS :when (pos? (bit-and state mask))] m))
        command (if (mac? os) :meta :ctrl)]
    (if (contains? held command)
      (conj (disj held command) :cmd)
      held)))

(defn- press-chord*
  "press-chord's answer for a key named name (or nil), its keyval and the
  modifiers held."
  [name keyval mods]
  (cond
    (modifier-key? keyval) {:waiting true}
    (nil? name) {:problem "Jonquil can't bind that key yet. Try another."}
    (and (km/typing-key? name) (empty? (disj mods :shift)))
    {:problem "A key that types needs a modifier other than Shift, or it would replace typing."}
    :else {:chord (cond-> {:key name} (seq mods) (assoc :mods mods))}))

(defn press-chord
  "What a key press stands for while the Keys tab records: {:chord {:key
  name :mods #{...}}} (no :mods when none is held), {:waiting true} for a
  modifier key alone, or {:problem sentence}. keyval is lower-cased, as the
  window's key handler has it. physical is the physical key's name when the
  window knows it (macOS with Option held, where the keyval is another
  character), and it names the key ahead of keyval."
  ([keyval state os] (press-chord keyval state os nil))
  ([keyval state os physical]
   (press-chord* (or physical (km/key-name keyval)) keyval (held-modifiers state os))))

(defn chord-text
  "chord as a person reads it, \"Cmd+Shift+E\"."
  [chord os]
  (palette/chord-label chord os))

;; --- what a chord would take from --------------------------------------------

(defn- chord-state
  "The GDK modifier state a chord's :mods stand for on os."
  [{:keys [mods]} os]
  (reduce bit-or 0
          (for [m mods]
            (get km/MODIFIER-MASKS (if (= :cmd m) (if (mac? os) :meta :ctrl) m)))))

(defn conflict
  "The binding chord would take a key from, as {:scope :binding}, or nil:
  the first binding of another command, or with a fixed outcome, that the
  chord fires today, in the window's keys and then the editor's. Every
  :when is taken as holding. command's own bindings don't count."
  [{:keys [keymap command os]} chord]
  (let [keyval (km/key-value (:key chord))
        state (chord-state chord os)]
    (first (for [scope [:window :editor]
                 :let [b (km/resolve-binding (remove #(= command (:command %)) (get keymap scope))
                                             keyval state (constantly true))]
                 :when b]
             {:scope scope :binding b}))))

;; --- recording ---------------------------------------------------------------

(def ^:private chord-keys [:key :keys :mods :exact :not :physical?])

(defn- removal
  "The :remove entry that names binding b: its chord and its command."
  [b]
  (select-keys b [:key :keys :mods :exact :command]))

(defn- without
  "user less binding b of scope: dropped from your own bindings when it is
  one of them, else named in :remove."
  [user scope b]
  (if (some #{b} (get user scope))
    (update user scope (fn [v] (vec (remove #{b} v))))
    (update user :remove (fn [v] (vec (distinct (conj (vec v) (removal b))))))))

(defn record
  "user, your keys.edn as jonquil.user-keys reads it, after chord is
  recorded for row. The row's old binding goes: changed in place when it is
  yours, removed when it is a plugin's or Jonquil's. The new binding keeps
  the old one's :args. taken is conflict's answer, or nil: the binding it
  names gives the chord up the same way."
  [user {:keys [scope command binding]} chord taken]
  (let [mine (merge (apply dissoc (or binding {}) :when chord-keys) chord {:command command})
        yours? (some #{binding} (get user scope))
        cleared (cond-> user
                  taken (without (:scope taken) (:binding taken))
                  (and binding (not yours?)) (without scope binding))]
    (if yours?
      (update cleared scope (fn [v] (vec (distinct (map #(if (= binding %) mine %) v)))))
      (update cleared scope (fn [v] (vec (distinct (cons mine v))))))))

(defn reset-command
  "user with command back to its plugin's or Jonquil's keys: your bindings
  for it go, and so do the :remove entries that name it."
  [user command]
  (let [others (fn [v] (vec (remove #(= command (:command %)) v)))]
    (-> user (update :window others) (update :editor others) (update :remove others))))

;; --- the file ----------------------------------------------------------------

(def written-mark
  "The first line of a keys.edn that Preferences wrote."
  ";; Your own key bindings for Jonquil, written by Preferences.")

(def ^:private key-order [:key :keys :mods :exact :not :physical? :when :command :args :outcome])

(defn- binding-text
  "b as EDN on one line, its keys in key-order."
  [b]
  (str "{"
       (str/join " " (for [k (concat (filter #(contains? b %) key-order)
                                     (remove (set key-order) (keys b)))]
                       (str (pr-str k) " " (pr-str (get b k)))))
       "}"))

(defn- vector-text
  "The bindings under label, one a line, aligned under the first."
  [label bindings]
  (let [head (str label " [")]
    (str head
         (str/join (str "\n" (apply str (repeat (count head) " "))) (map binding-text bindings))
         "]")))

(defn file-text
  "keys.edn's text for user, with a comment on top that says what wrote
  it. jonquil.user-keys/read-keys reads it back as user."
  [user]
  (str written-mark "\n"
       ";; Saving this file in Jonquil reloads it. A change made in Preferences\n"
       ";; rewrites it, so comments you add here won't last.\n"
       (vector-text "{:window" (:window user)) "\n"
       (vector-text " :editor" (:editor user)) "\n"
       (vector-text " :remove" (:remove user)) "}\n"))

(defn- comment-free
  "text with its string literals blanked, so a ; inside one isn't taken for
  a comment."
  [text]
  (str/replace text #"\"(?:[^\"\\]|\\.)*\"" "\"\""))

(defn hand-comments?
  "True when text, a keys.edn's contents or nil, holds comments that a
  rewrite would lose: any comment in a file that is neither the starter
  file nor one Preferences wrote."
  [text]
  (boolean
   (and text
        (not= text user-keys/template)
        (not (str/starts-with? text written-mark))
        (str/includes? (comment-free text) ";"))))
