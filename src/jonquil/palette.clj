(ns jonquil.palette
  "The command palette's data: the
  registered commands as entries a person can filter, each with its doc
  line, the chords that run it and its source. No GTK here;
  jonquil.gtk.palette shows the entries and runs the one picked."
  (:require [clojure.string :as str]))

(def ^:private key-display
  {"equal" "=" "plus" "+" "minus" "-" "slash" "/" "semicolon" ";" "bracketleft" "["
   "braceleft" "{" "braceright" "}" "space" "Space" "Return" "Enter" "KP_Enter" "Enter"
   "Escape" "Esc" "period" "." "greater" ">"})

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

(defn tab-entries
  "The palette's tab entries, Genera's Select order: every open tab but
  current-id, most recently used first, as {:id [:tab/switch id] :doc
  \"Switch to tab: label\" :chords [] :source \"tab\"}. recent is
  [{:id :label}] most recent first. The window runs these itself; they are
  not registry commands."
  [recent current-id]
  (vec (for [{:keys [id label]} recent :when (not= id current-id)]
         {:id [:tab/switch id] :doc (str "Switch to tab: " label) :chords [] :source "tab"})))

(def PANEL-LABELS
  "The right-hand panels a switch can bring back, by the window's key for
  each, with the name the palette gives it. Review and History are left
  out: each shows one proposal or one plugin's history, opened from that
  thing, so bringing one back would show a stale view."
  {:inspector "Inspector" :source "Source" :locations "Locations"
   :debugger "Frames" :trace "Trace" :plugins "Plugins" :messages "Messages"})

(defn panel-entries
  "The palette's panel entries: every recent panel but showing, most
  recently shown first, as {:id [:panel/switch k] :doc \"Switch to panel:
  Label\" :chords [] :source \"panel\"}. recent is the panels' keys, most
  recent first; showing is the key of the panel on screen, or nil. Only
  PANEL-LABELS' panels are offered. The window runs these itself."
  [recent showing]
  (vec (for [k recent :when (and (not= k showing) (contains? PANEL-LABELS k))]
         {:id [:panel/switch k] :doc (str "Switch to panel: " (PANEL-LABELS k))
          :chords [] :source "panel"})))

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

(defn bang?
  "True when text, an entry typed at the Interactions prompt, is a `!`
  command rather than code: one line whose first non-blank character is !,
  holding no bracket or quote, and either nothing after the ! or a space
  somewhere after it (`!clear the console`, `! clear`). So a symbol that
  starts with ! (`!x`, `!=`), a multi-line entry and anything with a form
  in it still go to the evaluator."
  [text]
  (let [t (str/trim (or text ""))
        rest (when (str/starts-with? t "!") (subs t 1))]
    (boolean
     (and rest
          (not (re-find #"[\n\r()\[\]{}\"]" rest))
          (or (str/blank? rest) (re-find #"\s" rest))))))

(defn bang-pick
  "What a `!` line at the prompt does with entries (the palette's command
  entries): {:run id :doc d} when the words after ! pick one, filtered as
  the palette's field filters, an entry whose doc equals the words winning
  over the longer ones they also match; {:choices [doc ...] :words w} when
  several match; {:none w} when none does; {:help true} for ! alone."
  [entries text]
  (let [words (str/trim (subs (str/triml text) 1))
        hits (filter-entries entries words)
        bare (fn [s] (str/replace (str/lower-case (str/trim (or s ""))) #"\.$" ""))
        exact (filter #(= (bare words) (bare (:doc %))) hits)
        one (cond (= 1 (count hits)) (first hits)
                  (= 1 (count exact)) (first exact))]
    (cond (str/blank? words) {:help true}
          one {:run (:id one) :doc (:doc one)}
          (seq hits) {:choices (mapv :doc hits) :words words}
          :else {:none words})))

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
