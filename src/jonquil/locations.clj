(ns jonquil.locations
  "The locations list: an ordered list of places in a buffer and the one
  you are at, the Lisp Machine's possibility set. A list is
  {:title :entries [{:start :end :line :text}] :index nil-or-n}. No GTK
  here: jonquil.gtk.window anchors the entries to marks and steps through
  them, and jonquil.gtk.locations-pane shows them."
  (:require [clojure.string :as str]
            [jonquil.syntax :as syntax]))

(def ^:private TEXT-MAX
  ;; a choice: one readable row in the panel
  120)

(defn- line-text [^String text offset]
  (let [start (inc (or (str/last-index-of text "\n" (max 0 (dec offset))) -1))
        end (or (str/index-of text "\n" offset) (count text))
        line (str/trim (subs text start end))]
    (if (> (count line) TEXT-MAX) (subs line 0 TEXT-MAX) line)))

(def LIST-MAX
  ;; a choice: past this a list stops being something to step through, and
  ;; every place costs two marks in the buffer and a row in the panel
  1000)

(defn- line-number
  "The 1-based line of offset s in text, counting from pos (the start of
  line number line) in one forward pass."
  [^String text pos line s]
  (loop [i pos line line]
    (let [j (.indexOf text "\n" (int i))]
      (if (and (>= j 0) (< j s)) (recur (inc j) (inc line)) line))))

(defn entries
  "One entry per [start end] range, ranges in buffer order: its 1-based
  line and that whole line, trimmed and cut to TEXT-MAX characters. Line
  numbers are counted in one pass over the text, not from the start for
  each range."
  [^String text ranges]
  (loop [rs ranges pos 0 line 1 acc (transient [])]
    (if-let [[s e] (first rs)]
      (let [line (line-number text pos line s)]
        (recur (rest rs) s line (conj! acc {:start s :end e :line line :text (line-text text s)})))
      (persistent! acc))))

(defn- make-list [title text ranges]
  (let [n (count ranges)]
    {:title (if (> n LIST-MAX) (str title " (the first " LIST-MAX " of " n ")") title)
     :entries (entries text (take LIST-MAX ranges)) :index nil}))

(defn uses
  "Every use of the symbol sym in text, as code (never in a comment,
  string or keyword)."
  [text sym]
  (make-list (str "Uses of " sym) text (syntax/find-symbol-occurrences text sym)))

(defn definitions
  "Every def* form defining sym in text (a defmulti and each defmethod)."
  [text sym]
  (make-list (str "Definitions of " sym) text
             (map (juxt :start :end) (syntax/find-definitions text sym))))

(defn matches
  "Every match of query in text, case ignored as the find bar searches."
  [text query]
  (make-list (str "Matches for " query) text
             (syntax/find-text-matches text query {:case-sensitive? false})))

(def hows
  "The kinds a project usage can be, in the order the filter shows them."
  [:call :value :definition :ns])

(def match-kinds
  "The kinds of place that aren't the searched-for var, in the order the
  filter shows them: another var or a local, and a place the file can't
  decide (jonquil.usages/scan's :match)."
  [:other :unresolved])

(defn- keep-first
  "At most LIST-MAX of entries, in order, the target's own (:match :this, or
  none given) kept before any other."
  [entries]
  (if (<= (count entries) LIST-MAX)
    (vec entries)
    (let [this? #(= :this (:match % :this))
          n-this (min LIST-MAX (count (filter this? entries)))
          keep-this (set (take n-this (keep-indexed (fn [i e] (when (this? e) i)) entries)))
          room (- LIST-MAX n-this)
          keep-other (set (take room (keep-indexed (fn [i e] (when-not (this? e) i)) entries)))]
      (into [] (keep-indexed (fn [i e] (when (or (keep-this i) (keep-other i)) e))) entries))))

(defn- shown?
  "Whether entry e shows under lst's :hows and :matches."
  [lst e]
  (and (contains? (:hows lst) (:how e))
       (contains? (:matches lst) (:match e :this))))

(defn- refilter
  "lst with :entries recomputed from :all, the current place kept when it's
  still shown, else the next shown entry, else the last; nil when nothing
  shows."
  [lst]
  (let [cur (some->> (:index lst) (nth (:entries lst)) :id)
        es (filterv #(shown? lst %) (:all lst))
        idx (when (and cur (seq es))
              (or (first (keep-indexed (fn [i e] (when (>= (:id e) cur) i)) es))
                  (dec (count es))))]
    (assoc lst :entries es :index idx)))

(defn project-uses
  "A list from jonquil.usages/scan's result for name: :scope :project, each
  entry carrying :path, :how, :match and :id, its place in :all, the whole
  list the filter picks from. When the scan's :target is a var
  (:target-var?), only that var's own places show at first (:matches
  #{:this}) and :match-counts counts the rest for their buttons; the title
  names the var. :hashes is the scan's, the text each file was searched in."
  [{:keys [entries matched hashes target]} name]
  (let [var-name (:var target)
        this (if var-name (filterv #(= :this (:match %)) entries) entries)
        n (count this)
        files (if var-name (count (distinct (map :path this))) matched)
        all (into [] (map-indexed (fn [i e] (assoc e :id i))) (keep-first entries))]
    (refilter
     {:title (str "Usages of " (or var-name name) " (" n " in " files (if (= 1 files) " file" " files") ")"
                  (when (> (count entries) LIST-MAX) (str ", the first " LIST-MAX)))
      :scope :project :all all :index nil :entries []
      :hows (set hows)
      :matches (if var-name #{:this} (into #{:this} match-kinds))
      :target-var? (some? var-name)
      :match-counts (into {} (map (fn [k] [k (count (filter #(= k (:match %)) entries))])) match-kinds)
      :hashes (or hashes {})})))

(defn set-hows
  "lst showing only the entries whose :how is in shown and, for a list
  whose target is a var, whose :match is :this or in shown (the filter's
  ticked set mixes hows and match-kinds). The current place stays when
  it's still shown, else moves to the next shown entry, else the last;
  nil when nothing shows."
  [lst shown]
  (refilter (assoc lst
                   :hows (into #{} (filter (set hows)) shown)
                   :matches (if (:target-var? lst)
                              (into #{:this} (filter (set match-kinds)) shown)
                              (:matches lst)))))

(def ^:private how-words {:call "call" :value "value" :definition "def" :ns "ns"})

(def ^:private match-words {:other ", other var" :unresolved ", unresolved"})

(defn row-text
  "The panel's row for entry e of lst: line: text, and for a project list
  path:line: kind: text, the path relative to lst's :root, the kind saying
  when the place isn't the searched-for var."
  [lst {:keys [line text path how match]}]
  (if (= :project (:scope lst))
    (let [root (:root lst)
          rel (if (and root path (str/starts-with? path (str root "/")))
                (subs path (inc (count root)))
                path)]
      (str rel ":" line ": " (how-words how) (match-words match) ": " text))
    (str line ": " text)))

(defn step
  "lst with its index moved by delta: from no index, +1 goes to the first
  entry and -1 to the last; otherwise it moves and stops at either end.
  :moved? is false when it was already there, or the list is empty."
  [lst delta]
  (let [n (count (:entries lst))
        i (:index lst)
        j (cond (zero? n) nil
                (nil? i) (if (pos? delta) 0 (dec n))
                :else (max 0 (min (dec n) (+ i delta))))]
    (if (or (nil? j) (= i j))
      {:list lst :moved? false}
      {:list (assoc lst :index j) :moved? true})))

(defn position-text
  "\"2 of 4\" at an entry, else how many places there are."
  [lst]
  (let [n (count (:entries lst))]
    (cond (:index lst) (str (inc (:index lst)) " of " n)
          (zero? n) "No places"
          (= 1 n) "1 place"
          :else (str n " places"))))
