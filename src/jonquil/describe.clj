(ns jonquil.describe
  "What a key does, which keys were pressed last, and every binding, as
  console lines, from the live keymap and registry (plugins included). No
  GTK here: jonquil.gtk.window reads the key and prints the lines.

  A described key is matched the way jonquil.keymap/dispatch would match
  it, but with every :when condition taken as holding, so a guarded
  binding is described with its conditions rather than skipped."
  (:require [clojure.string :as str]
            [jonquil.keymap :as km]
            [jonquil.palette :as palette]))

(def ^:private modifier-names
  #{"Shift_L" "Shift_R" "Control_L" "Control_R" "Alt_L" "Alt_R" "Meta_L" "Meta_R"
    "Super_L" "Super_R" "Hyper_L" "Hyper_R" "Caps_Lock" "ISO_Level3_Shift"})

(defn modifier-key?
  "True for a key that only modifies others (Shift, Control, Cmd, ...)."
  [key-name]
  (contains? modifier-names key-name))

(defn- held [state]
  (set (for [[k mask] km/MODIFIER-MASKS :when (pos? (bit-and state mask))] k)))

(defn chord-text
  "The pressed key as a person reads it, spelled as the palette spells
  chords: on macOS the Meta bit reads as Cmd, elsewhere the Control bit
  does."
  [key-name state os]
  (let [cmd-bit (if (= "Mac OS X" os) :meta :ctrl)
        mods (set (map #(if (= cmd-bit %) :cmd %) (held state)))]
    (palette/chord-label {:key key-name :exact mods} os)))

(defn- source [commands id]
  (if-let [p (:plugin (get commands id))] (name p) "built-in"))

(defn key-matches
  "Every :window then :editor binding in keymap that matches keyval and
  state with each :when condition taken as holding, in dispatch order, as
  {:scope :command :outcome :when :doc :source}."
  [{:keys [keymap commands keyval state physical]}]
  (vec (for [scope [:window :editor]
             b (get keymap scope)
             :when (km/matches? b keyval state (constantly true) physical)]
         {:scope scope :command (:command b) :outcome (:outcome b) :when (:when b)
          :doc (get-in commands [(:command b) :doc]) :source (source commands (:command b))})))

(defn- match-text [{:keys [scope command outcome doc source] guards :when}]
  (if command
    (str "runs " (or doc (str command)) " (" command ", " source ", " (name scope) ")"
         (when (seq guards) (str ", only when " (str/join ", " (map name guards)))))
    (if (= :to-gtk outcome)
      (str "goes to GTK here (" (name scope) ")")
      (str "answers " outcome " here (" (name scope) ")"))))

(defn describe-lines
  "Console lines saying what the key chord does, from key-matches."
  [chord matches]
  (case (count matches)
    0 [(str "; " chord " isn't bound: it goes to GTK (typed text, or the focused widget's own key).")]
    1 [(str "; " chord " " (match-text (first matches)))]
    (into [(str "; " chord ", in order, the first that applies wins:")]
          (map #(str "; " (match-text %)) matches))))

(def RECENT-MAX
  ;; a choice: enough to see what just happened, short enough to read
  50)

(defn remember
  "ring with entry added last, at most RECENT-MAX entries. Plain typing
  merges into the entry before it when that is typing too."
  [ring entry]
  (let [prev (peek ring)
        r (if (and (:typing entry) (:typing prev))
            (conj (pop ring) {:typing (+ (:typing prev) (:typing entry))})
            (conj ring entry))
        n (count r)]
    (if (> n RECENT-MAX) (subvec r (- n RECENT-MAX)) r)))

(defn recent-entry
  "One recent key: {:typing 1} for a printable key without Cmd, Ctrl, Alt,
  Meta or Super, so what was typed is never kept; otherwise the chord and
  the command its first match runs (nil when none)."
  [key-name state os matches]
  (if (and (= 1 (count key-name)) (empty? (disj (held state) :shift)))
    {:typing 1}
    {:chord (chord-text key-name state os) :command (:command (first matches))}))

(defn recent-lines
  "Console lines listing the recent keys, oldest first."
  [ring]
  (if (empty? ring)
    ["; No keys yet."]
    (into ["; Recent keys, oldest first:"]
          (map (fn [{:keys [typing chord command]}]
                 (if typing
                   (str "; typing (" typing (if (= 1 typing) " key)" " keys)"))
                   (str "; " chord "  " (if command (str command) "unbound"))))
               ring))))

(defn binding-lines
  "Console lines listing every bound command, window keys then editor keys,
  in keymap order: chord, doc, source."
  [{:keys [keymap commands os]}]
  (vec (mapcat (fn [[scope title]]
                 (cons title
                       (for [b (get keymap scope)
                             :let [id (:command b)]
                             :when (and id (contains? commands id))]
                         (str "; " (palette/chord-label b os) "  " (get-in commands [id :doc])
                              "  " (source commands id)))))
               [[:window "; Window keys"] [:editor "; Editor keys"]])))
