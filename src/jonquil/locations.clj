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

(defn entries
  "One entry per [start end] range, ranges in buffer order: its 1-based
  line and that whole line, trimmed and cut to TEXT-MAX characters. Line
  numbers are counted in one pass over the text, not from the start for
  each range."
  [^String text ranges]
  (loop [rs ranges pos 0 line 1 acc (transient [])]
    (if-let [[s e] (first rs)]
      (let [line (loop [i pos line line]
                   (let [j (.indexOf text "\n" (int i))]
                     (if (and (>= j 0) (< j s)) (recur (inc j) (inc line)) line)))]
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

(defn project-uses
  "A list from jonquil.usages/scan's result for name: :scope :project, every
  kind shown, each entry carrying :path, :how and :id, its place in :all,
  the whole list the filter picks from."
  [{:keys [entries matched]} name]
  (let [n (count entries)
        all (into [] (map-indexed (fn [i e] (assoc e :id i))) (take LIST-MAX entries))]
    {:title (str "Usages of " name " (" n " in " matched (if (= 1 matched) " file" " files") ")"
                 (when (> n LIST-MAX) (str ", the first " LIST-MAX)))
     :scope :project :all all :entries all :hows (set hows) :index nil}))

(defn set-hows
  "lst showing only the entries whose :how is in shown, in order. The
  current place stays when it's still shown, else moves to the next shown
  entry, else the last; nil when nothing shows."
  [lst shown]
  (let [cur (some->> (:index lst) (nth (:entries lst)) :id)
        es (filterv #(contains? shown (:how %)) (:all lst))
        idx (when (and cur (seq es))
              (or (first (keep-indexed (fn [i e] (when (>= (:id e) cur) i)) es))
                  (dec (count es))))]
    (assoc lst :hows shown :entries es :index idx)))

(def ^:private how-words {:call "call" :value "value" :definition "def" :ns "ns"})

(defn row-text
  "The panel's row for entry e of lst: line: text, and for a project list
  path:line: kind: text, the path relative to lst's :root."
  [lst {:keys [line text path how]}]
  (if (= :project (:scope lst))
    (let [root (:root lst)
          rel (if (and root path (str/starts-with? path (str root "/")))
                (subs path (inc (count root)))
                path)]
      (str rel ":" line ": " (how-words how) ": " text))
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
