(ns jonquil.keymap
  "Key bindings as data (plugins design, Part 1): an ordered vector of
  bindings per scope, matched first-match-wins against a keyval and a GDK
  modifier state. No GTK here, so the matcher runs headless; the window
  lower-cases the keyval (gdk_keyval_to_lower, M4) before asking.

  A binding is {:key name | :keys #{name ...}, :mods #{...}, :not #{...},
  :command id, :args map}. Modifiers are :shift :alt :ctrl :meta :super and
  :cmd, which means Control or Meta on every platform, as command-chord?
  always has. Every :mods modifier must be held, no :not modifier may be,
  and a modifier in neither set is ignored, the way the cond clauses it
  replaces behave.

  A binding may name a fixed :outcome (:handled or :to-gtk) instead of a
  :command; dispatch answers it without running anything.

  :exact #{...} (instead of :mods and :not) requires exactly that set among
  :shift :alt :ctrl :meta, the other three up; :super is ignored, as the
  editor's shift-only? and no-modifiers? ignored it. :physical? true lets
  the binding also match when dispatch's :physical option, the key name of
  the physical key pressed (or nil), is one of its keys: the macOS
  Cmd+Option+F/L keycode fallback (jonquil.gtk.editor/physical-key), kept
  per binding so no other chord picks it up."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def MODIFIER-MASKS
  "GDK modifier bits, from gdktypes.h. Copied rather than read from
  jonquil.gtk.ffi so this namespace loads without GTK; a window self-test
  row pins them against the ffi's."
  {:shift (bit-shift-left 1 0)
   :ctrl (bit-shift-left 1 2)
   :alt (bit-shift-left 1 3)
   :super (bit-shift-left 1 26)
   :meta (bit-shift-left 1 28)})

(def ^:private named-keys
  ;; gdkkeysyms.h values for the names that aren't their own character
  {"equal" 0x03d "plus" 0x02b "minus" 0x02d
   "braceright" 0x07d "braceleft" 0x07b
   "F3" 0xffc0 "F5" 0xffc2 "F6" 0xffc3 "Escape" 0xff1b "bracketleft" 0x05b
   "space" 0x020 "slash" 0x02f "semicolon" 0x03b "F1" 0xffbe "F2" 0xffbf "F12" 0xffc9})

(defn key-value
  "The keyval a binding's key name stands for: a lower-case letter or a
  digit is its own character code, anything else is a gdkkeysyms.h name.
  nil when the name is unknown."
  [name]
  (or (get named-keys name)
      (when (and (string? name) (= 1 (count name))
                 (re-matches #"[a-z0-9]" name))
        (int (first name)))))

(def ^:private modifiers (conj (set (keys MODIFIER-MASKS)) :cmd))

(defn- held?
  [state m]
  (if (= :cmd m)
    (not (zero? (bit-and state (bit-or (:ctrl MODIFIER-MASKS) (:meta MODIFIER-MASKS)))))
    (not (zero? (bit-and state (get MODIFIER-MASKS m))))))

(defn- key-names [{:keys [key] :as binding}]
  (if key [key] (:keys binding)))

(def ^:private outcomes #{:handled :to-gtk})

(def ^:private exact-modifiers #{:shift :alt :ctrl :meta})

(defn- modifiers-match? [binding state]
  (if (contains? binding :exact)
    (every? #(= (contains? (:exact binding) %) (held? state %)) exact-modifiers)
    (and (every? #(held? state %) (:mods binding))
         (not-any? #(held? state %) (:not binding)))))

(defn matches?
  "True when keyval is the binding's key (or one of its keys), every :mods
  modifier is held, no :not modifier is, and every :when condition holds.
  The conditions are asked through holds? (fn [id] bool), in order, only
  once the key and modifiers have matched, stopping at the first false:
  the short-circuit order of the cond clauses the keymap replaces, so a
  focus check never runs for a key it couldn't concern. Without holds?
  a binding with :when never matches. physical is the physical key's name
  for a :physical? binding (see the ns doc), nil when there's none."
  ([binding keyval state] (matches? binding keyval state (constantly false)))
  ([binding keyval state holds?] (matches? binding keyval state holds? nil))
  ([binding keyval state holds? physical]
   (and (or (contains? (set (map key-value (key-names binding))) keyval)
            (and (true? (:physical? binding)) (some? physical)
                 (contains? (set (key-names binding)) physical)))
        (modifiers-match? binding state)
        (every? #(boolean (holds? %)) (:when binding)))))

(defn resolve-binding
  "The first binding in bindings that matches, or nil."
  ([bindings keyval state] (resolve-binding bindings keyval state (constantly false)))
  ([bindings keyval state holds?]
   (some #(when (matches? % keyval state holds?) %) bindings)))

(defn dispatch
  "Walks bindings in order, over those that match keyval and state (with
  holds?), until one answers: a binding's fixed :outcome, or what (run!
  binding) returns for its command. :handled and :to-gtk (the key is
  GTK's, so stop here) are answers; :next, or nil for an unregistered
  command, moves on to the next matching binding. :unmatched when the
  bindings run out. The :physical option goes to matches?."
  [bindings keyval state {:keys [holds? run! physical]}]
  (or (some (fn [b]
              (when (matches? b keyval state holds? physical)
                (let [o (if (contains? b :outcome) (:outcome b) (run! b))]
                  (when (contains? outcomes o) o))))
            bindings)
      :unmatched))

(def ^:private binding-keys #{:key :keys :mods :not :exact :physical? :when :command :args :outcome})

(defn problems
  "What's wrong with bindings, one sentence each naming the binding's
  position, given the registered command and condition ids. Empty when all
  is well."
  ([bindings command-ids] (problems bindings command-ids #{}))
  ([bindings command-ids condition-ids]
  (vec
   (for [[i b] (map-indexed vector bindings)
         :let [names (key-names b)
               mods (concat (:mods b) (:not b) (:exact b))]
         p (cond-> []
             (and (nil? (:key b)) (nil? (:keys b))) (conj "has neither :key nor :keys")
             (and (:key b) (:keys b)) (conj "has both :key and :keys")
             (some #(nil? (key-value %)) names)
             (conj (str "names an unknown key " (pr-str (remove key-value names))))
             (some (complement modifiers) mods)
             (conj (str "names an unknown modifier " (pr-str (remove modifiers mods))))
             (and (some modifiers (:exact b)) (some (complement exact-modifiers) (:exact b)))
             (conj (str ":exact names " (pr-str (remove exact-modifiers (:exact b)))
                        ", only :shift :alt :ctrl :meta"))
             (and (contains? b :exact) (or (contains? b :mods) (contains? b :not)))
             (conj "has :exact alongside :mods or :not")
             (and (contains? b :physical?) (not (boolean? (:physical? b))))
             (conj ":physical? isn't true or false")
             (and (contains? b :command) (contains? b :outcome))
             (conj "has both :command and :outcome")
             (and (not (contains? b :command)) (not (contains? b :outcome)))
             (conj "has neither :command nor :outcome")
             (and (contains? b :command) (not (contains? command-ids (:command b))))
             (conj (str "names an unregistered command " (pr-str (:command b))))
             (and (contains? b :outcome) (not (contains? outcomes (:outcome b))))
             (conj (str "names an unknown outcome " (pr-str (:outcome b)) ", not :handled or :to-gtk"))
             (and (contains? b :when) (not (vector? (:when b))))
             (conj ":when isn't a vector")
             (and (vector? (:when b)) (some (complement condition-ids) (:when b)))
             (conj (str "names an unregistered condition "
                        (pr-str (remove condition-ids (:when b)))))
             (some (complement binding-keys) (keys b))
             (conj (str "has unknown keys " (pr-str (remove binding-keys (keys b))))))]
     (str "binding " i " " p)))))

(defmacro ^:private embedded-text
  "The text of classpath file path, read when this form is compiled (the
  installed app has no source tree, as jonquil.nrepl.cljs-node-files
  explains)."
  [path]
  (slurp (io/resource path)))

(def shipped
  "The shipped keymap, {scope [bindings...]}, from jonquil/keymap.edn."
  (edn/read-string (embedded-text "jonquil/keymap.edn")))
