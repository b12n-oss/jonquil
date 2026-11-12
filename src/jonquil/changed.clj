(ns jonquil.changed
  "Which top-level forms changed since they last ran (LM3). A form's key is
  its tokens without comments, joined by a space, so reindenting,
  reformatting and comment edits don't count as a change; a tab's ran set
  holds the keys of its forms as they last ran. (comment ...) blocks are
  never marked. No GTK here."
  (:require [clojure.string :as str]
            [jonquil.commands :as commands]
            [jonquil.syntax :as syntax]))

(defn- gap
  "text between two tokens, without whitespace or commas: the reader-macro
  characters the tokenizer leaves out of its tokens (' @ ` ~ ~@ and a
  dispatch #)."
  [^String text a b]
  (str/replace (subs text a b) #"[\s,]" ""))

(defn- form-pieces
  "The key pieces of one form spanning start..end: its non-comment tokens
  (inside, in offset order) and the reader-macro characters between them."
  [text start end inside]
  (loop [pos start ts inside out []]
    (if-let [[t s e] (first ts)]
      (let [g (gap text pos (max pos s))]
        (recur e (rest ts) (cond-> out
                             (seq g) (conj g)
                             (not= :comment t) (conj (subs text s e)))))
      (let [g (gap text (min pos end) end)] (cond-> out (seq g) (conj g))))))

(defn- form-entry
  "One {:start :end :key} for the top-level form f, plus the tokens left
  after it, as [entry after]. toks must start at or before f in offset
  order."
  [text {:keys [start end]} toks]
  (let [toks (drop-while (fn [[_ _ e]] (<= e start)) toks)
        [inside after] (split-with (fn [[_ s]] (< s end)) toks)
        pieces (form-pieces text start end inside)]
    [{:start start :end end :key (str/join " " pieces)} after]))

(defn forms
  "text's top-level forms as [{:start :end :key}], (comment ...) blocks
  left out. The key is every token but comments, and the reader-macro
  characters between them, joined by a space."
  [text]
  (let [tops (remove #(commands/comment-form? text %) (commands/top-level-forms text))]
    ;; one sweep: forms and tokens are both in offset order
    (loop [tops tops toks (syntax/tokenize text) acc []]
      (if-let [f (first tops)]
        (let [[entry after] (form-entry text f toks)]
          (recur (rest tops) after (conj acc entry)))
        acc))))

(defn- ident
  "The [head name] a key defines, (def NAME ...) or any def-like head, or
  nil: what a later run of the same definition replaces."
  [key]
  (when-let [i (str/index-of key "(")]
    (let [[head & more] (rest (str/split (subs key i) #" "))]
      (when (and head (str/starts-with? head "def"))
        (when-let [n (first (remove #(str/starts-with? % "^") more))]
          [head n])))))

(defn changed
  "The forms of text whose key isn't in ran; [] when ran is nil (the tab
  never ran)."
  [text ran]
  (if (nil? ran) [] (filterv #(not (contains? ran (:key %))) (forms text))))

(defn last-run-after
  "The tab's last-run text after an evaluation of source (:run, :form or
  :selection) that sent text and ended with status: a whole Run that ended
  :ok replaces it with the buffer text it ran, anything else keeps prev.
  What Compare with the last Run compares the buffer against."
  [prev text source status]
  (if (and (= :run source) (= :ok status)) text prev))

(defn record
  "ran after a run that ended :ok: a :run replaces it with every form's key
  in text; :form and :selection add the keys of the forms in text, the
  code they sent, each replacing the earlier key of the definition it
  makes (so undoing back to an older version after Run Form marks it)."
  [ran text kind]
  (let [ks (set (map :key (forms text)))]
    (if (= :run kind)
      ks
      (let [replaced (set (keep ident ks))]
        (into (set (remove #(contains? replaced (ident %)) (or ran #{}))) ks)))))
