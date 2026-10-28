(ns jonquil.palette
  "The command palette's data (plugins design, Part 1, step S8): the
  registered commands as entries a person can filter, each with its doc
  line, the chords that run it and its source. No GTK here;
  jonquil.gtk.palette shows the entries and runs the one picked."
  (:require [clojure.string :as str]))

(def ^:private key-display
  {"equal" "=" "plus" "+" "minus" "-" "slash" "/" "semicolon" ";" "bracketleft" "["
   "braceleft" "{" "braceright" "}" "space" "Space" "Return" "Enter" "KP_Enter" "Enter"
   "Escape" "Esc"})

(defn- key-label [k]
  (or (key-display k) (if (= 1 (count k)) (str/upper-case k) k)))

(defn- modifier-label [m mac?]
  (case m
    :cmd (if mac? "Cmd" "Ctrl")
    :ctrl "Ctrl"
    :alt (if mac? "Option" "Alt")
    :shift "Shift"
    :meta "Meta"
    :super "Super"
    (name m)))

(defn chord-label
  "binding's chords as a person reads them, \"Cmd+Shift+P\": modifiers from
  :mods or :exact in the order Cmd, Ctrl, Alt, Shift, with :cmd shown as
  Cmd on macOS and Ctrl elsewhere and :alt as Option on macOS. A :keys
  binding gives one chord per key, sorted by key name and joined with
  \", \"; two keys that read the same (Return, KP_Enter) show once."
  [binding os]
  (let [mac? (= "Mac OS X" os)
        mods (or (:exact binding) (:mods binding) #{})
        prefix (apply str (for [m [:cmd :ctrl :meta :super :alt :shift] :when (contains? mods m)]
                            (str (modifier-label m mac?) "+")))
        ks (if (:key binding) [(:key binding)] (sort (:keys binding)))]
    (str/join ", " (distinct (map #(str prefix (key-label %)) ks)))))

(defn entries
  "Every command in commands without :palette? false, sorted by doc, as
  {:id :doc :chords [label ...] :source s}, s \"built-in\" or the plugin's
  id name. :chords are the labels of the :window then :editor bindings in
  keymap that name the command, in keymap order, without repeats."
  [{:keys [commands keymap os]}]
  (let [bindings (concat (:window keymap) (:editor keymap))]
    (->> commands
         (remove (fn [[_ c]] (false? (:palette? c))))
         (map (fn [[id c]]
                {:id id :doc (:doc c) :source (if-let [p (:plugin c)] (name p) "built-in")
                 :chords (vec (distinct (for [b bindings :when (= id (:command b))]
                                          (chord-label b os))))}))
         (sort-by :doc)
         vec)))

(defn filter-entries
  "The entries every word of query (split on whitespace, case ignored)
  appears in, read from each entry's doc and id; order kept. A blank query
  keeps all."
  [entries query]
  (let [words (remove str/blank? (str/split (str/lower-case (or query "")) #"\s+"))]
    (filterv (fn [{:keys [doc id]}]
               (let [text (str/lower-case (str doc " " id))]
                 (every? #(str/includes? text %) words)))
             entries)))

(defn row-text
  "One palette row: the doc, its chords when it has any, and its source."
  [{:keys [doc chords source]}]
  (str doc (when (seq chords) (str "  " (str/join ", " chords))) "  " source))

(defn move
  "st {:shown [...] :index i} with the index moved by delta, clamped to
  the shown entries; 0 when none are shown."
  [st delta]
  (let [n (count (:shown st))]
    (assoc st :index (if (zero? n) 0 (max 0 (min (dec n) (+ (:index st) delta)))))))
