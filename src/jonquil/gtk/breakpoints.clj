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

(defn heads
  "The spots opts the runtime's last answer about ed's heads gives for text
  (jonquil.breakpoints/cached-heads): {} when there's none, or it was for
  another ns form, so every head that isn't a known core form counts as a
  macro. A toggle asks, and so does each Run that ends well."
  [ed text]
  (bp/cached-heads (some-> (get-in ed [:changed :bp-heads]) deref) text))

(defn set-heads!
  "Keeps the runtime's answer r ({:heads :ns-found?}) about ed's heads,
  asked with ns form text ns-form (nil for none), merged into the last one
  when that was asked with the same ns form."
  [ed ns-form r]
  (when-let [a (get-in ed [:changed :bp-heads])]
    (swap! a (fn [c] (if (and c (= ns-form (:ns-form c)))
                       (-> c (update :heads merge (:heads r)) (assoc :ns-found? (:ns-found? r)))
                       (assoc (select-keys r [:heads :ns-found?]) :ns-form ns-form)))))
  nil)

(defn dropped
  "The lines of ed's breakpoints in range that nothing can hold now (inside
  a macro, by the runtime's last answer), when evaluator e has them armed."
  [ed e text range]
  (let [{:keys [start end] :or {start 0 end (count text)}} range
        ls (when (and (supported? e) (ev/breakpoints-on? e)) (lines ed text))
        starts-in (fn [l] (let [s (nth (bp/line-starts text) (dec l) nil)] (and s (<= start s) (< s end))))]
    (bp/dropped-lines text (filter starts-in ls) (heads ed text))))

(defn sent-text
  "What a Run sends from ed: text's range ({:start :end}, the whole text
  when nil) with ed's breakpoints in, when evaluator e has them armed and
  some lie in the range; the range's text as it is otherwise. tab-id
  goes in each breakpoint (the gutter's arrow finds the tab by it), and
  the forms holding one get step points (jonquil.breakpoints/instrument)."
  [ed e text range tab-id]
  (let [{:keys [start end] :or {start 0 end (count text)}} range
        ls (when (and (supported? e) (ev/breakpoints-on? e)) (seq (lines ed text)))]
    (if ls
      (do (some-> (get-in ed [:changed :bp-sent]) (reset! text))
          (:text (bp/instrument text ls (merge {:start start :end end :tab tab-id} (heads ed text)))))
      (subs text start end))))
