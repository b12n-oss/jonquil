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

(defn entries
  "One entry per [start end] range: its 1-based line and that whole line,
  trimmed and cut to TEXT-MAX characters."
  [text ranges]
  (mapv (fn [[s e]] {:start s :end e :line (syntax/line-number-at-offset text s)
                     :text (line-text text s)})
        ranges))

(defn- make-list [title text ranges]
  {:title title :entries (entries text ranges) :index nil})

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
