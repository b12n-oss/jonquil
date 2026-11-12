(ns jonquil.gtk.breakpoints
  "A tab's breakpoints (LM5) as text marks in its editor's buffer, drawn as
  the gutter's dots (jonquil.gtk.gutter/set-dots!). A mark has right
  gravity, so a line pushed down by a newline typed at its start takes its
  dot with it. And the code a Run sends with them in
  (jonquil.breakpoints/instrument). GTK thread only."
  (:require [clojure.string :as str]
            [jonquil.breakpoints :as bp]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.gutter :as gutter]))

(defn- gutter-of [ed] (get-in ed [:changed :gutter]))

(defn marks
  "ed's breakpoint marks, [] for an editor without a gutter."
  [ed]
  (if-let [gt (gutter-of ed)] @(:dots gt) []))

(defn- line-of
  "The 1-based line holding offset in text."
  [text offset]
  (inc (count (filter #{\newline} (subs text 0 (min offset (count text)))))))

(defn- line-start
  "The offset line (1-based) starts at in text."
  [text line]
  (loop [i 0 l 1]
    (if (= l line)
      i
      (if-let [j (str/index-of text "\n" i)] (recur (inc j) (inc l)) i))))

(defn mark-lines
  "[[mark line] ...] for ed's marks, over text (ed's text now)."
  [ed text]
  (let [buf (:buf ed)]
    (mapv (fn [m] [m (line-of text (g/mark-offset buf m))]) (marks ed))))

(defn lines
  "The 1-based lines of ed's breakpoints, sorted, each once."
  [ed text]
  (vec (sort (distinct (map second (mark-lines ed text))))))

(defn mark-on
  "The mark on line, or nil."
  [ed text line]
  (some (fn [[m l]] (when (= l line) m)) (mark-lines ed text)))

(defn add!
  "A dot on line (1-based) of ed's text."
  [ed text line]
  (let [buf (:buf ed)
        at (line-start text line)
        m (g/with-iters* buf at at (fn [a _] (g/gtk-text-buffer-create-mark buf nil a 0)))]
    (gutter/set-dots! (gutter-of ed) (conj (marks ed) m))
    m))

(defn remove!
  "Takes mark's dot away."
  [ed mark]
  (gutter/set-dots! (gutter-of ed) (vec (remove #(= mark %) (marks ed))))
  (g/gtk-text-buffer-delete-mark (:buf ed) mark)
  nil)

(defn clear!
  "Takes every dot in ed away: how many there were."
  [ed]
  (let [ms (marks ed)]
    (doseq [m ms] (remove! ed m))
    (count ms)))

(defn supported?
  "True when the evaluator e can run breakpoints."
  [e]
  (boolean (and e (satisfies? ev/Breakpoints e))))

(defn sent-text
  "What a Run sends from ed: text's range ({:start :end}, the whole text
  when nil) with ed's breakpoints in, when evaluator e runs them and some
  lie in the range; the range's text as it is otherwise."
  [ed e text range]
  (let [{:keys [start end] :or {start 0 end (count text)}} range
        ls (when (supported? e) (seq (lines ed text)))]
    (if ls
      (:text (bp/instrument text ls {:start start :end end}))
      (subs text start end))))
