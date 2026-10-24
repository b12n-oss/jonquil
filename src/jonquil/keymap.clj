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
  replaces behave.")

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
   "braceright" 0x07d "braceleft" 0x07b})

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

(defn matches?
  "True when keyval is the binding's key (or one of its keys), every :mods
  modifier is held and no :not modifier is."
  [binding keyval state]
  (and (contains? (set (map key-value (key-names binding))) keyval)
       (every? #(held? state %) (:mods binding))
       (not-any? #(held? state %) (:not binding))))

(defn resolve-binding
  "The first binding in bindings that matches, or nil."
  [bindings keyval state]
  (some #(when (matches? % keyval state) %) bindings))

(def ^:private binding-keys #{:key :keys :mods :not :command :args})

(defn problems
  "What's wrong with bindings, one sentence each naming the binding's
  position, given the registered command ids. Empty when all is well."
  [bindings command-ids]
  (vec
   (for [[i b] (map-indexed vector bindings)
         :let [names (key-names b)
               mods (concat (:mods b) (:not b))]
         p (cond-> []
             (and (nil? (:key b)) (nil? (:keys b))) (conj "has neither :key nor :keys")
             (and (:key b) (:keys b)) (conj "has both :key and :keys")
             (some #(nil? (key-value %)) names)
             (conj (str "names an unknown key " (pr-str (remove key-value names))))
             (some (complement modifiers) mods)
             (conj (str "names an unknown modifier " (pr-str (remove modifiers mods))))
             (not (contains? command-ids (:command b)))
             (conj (str "names an unregistered command " (pr-str (:command b))))
             (some (complement binding-keys) (keys b))
             (conj (str "has unknown keys " (pr-str (remove binding-keys (keys b))))))]
     (str "binding " i " " p))))
