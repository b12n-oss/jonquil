(ns jonquil.paint
  "Turns buffer text into a paint plan: style spans a front-end applies as
  tags. Pure. Mirrors upstream DrClojure.syntax/highlight-doc! (41c56b8) with
  the Swing document replaced by data: token styles first, then rainbow and
  unmatched brackets on top, so a later span wins where two overlap."
  (:require [jonquil.syntax :as syntax]
            [jonquil.theme :as theme]))

(def token-styles
  ;; Each token kind's bold/italic flags (upstream default-palette's, at
  ;; 41c56b8). Colours come from the active theme (jonquil.theme). :symbol
  ;; and :bracket are absent on purpose: highlight-doc! leaves plain symbols
  ;; at the default style and paints brackets only through the rainbow pass.
  {:comment      {:italic true}
   :string       {}
   :regex        {}
   :char         {}
   :keyword      {:bold true}
   :number       {}
   :special-form {:bold true}
   :builtin      {}
   :constant     {:bold true}
   :metatag      {:italic true}})

(def rainbow-count
  ;; Upstream cycles rainbow brackets by depth mod 7.
  7)

(defn style
  "The style map for a paint-plan key under theme t (a jonquil.theme map):
  :fg from the theme, :bold/:italic from the kind."
  [t k]
  (cond
    (= k :unmatched) {:fg (:red t) :bold true}
    (and (vector? k) (= :rainbow (first k))) {:fg (nth (:rainbow t) (second k)) :bold true}
    :else (when-let [flags (get token-styles k)]
            (assoc flags :fg (theme/token-color t k)))))

(defn style-at
  "The effective style map at offset pos under theme t, or nil for the
  default style."
  [t plan pos]
  (some (fn [[k s e]] (when (and (<= s pos) (< pos e)) (style t k)))
        (rseq plan)))

(defn- reclassify
  "A symbol-like token's kind under profile's own :special-forms and
  :builtins (a jonquil.dialect profile) instead of jonquil.syntax's, which
  are the Jolt profile's. Every other token kind is left as tokenize gave
  it, so constants, keywords and the rest paint the same in every dialect."
  [profile text [t s e :as token]]
  (if (#{:symbol :special-form :builtin} t)
    (let [sym (subs text s e)]
      [(cond (contains? (:special-forms profile) sym) :special-form
             (contains? (:builtins profile) sym) :builtin
             :else :symbol)
       s e])
    token))

(defn paint-plan
  "Returns a vector of [style-key start end]. Style keys are token types,
  [:rainbow n] with n in 0..6, or :unmatched. With a profile (a
  jonquil.dialect profile), symbols are classified by that profile's sets;
  without one, or with the Jolt profile, the plan is jonquil.syntax's own."
  ([text] (paint-plan nil text))
  ([profile text]
   (if (or (nil? text) (= "" text))
     []
     (let [raw (syntax/tokenize text)
           tokens (into []
                        (keep (fn [[t s e]] (when (contains? token-styles t) [t s e])))
                        (if profile (map (fn [tok] (reclassify profile text tok)) raw) raw))
           n rainbow-count
           brackets (mapv (fn [[s e depth kind]]
                            [(if (= kind :unmatched) :unmatched [:rainbow (mod depth n)]) s e])
                          (:bracket-tokens (syntax/compute-brackets text)))]
       (into tokens brackets)))))

(defn- common-prefix
  "Length of the longest common prefix of strings a and b."
  [^String a ^String b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i n) (= (nth a i) (nth b i))) (recur (inc i)) i))))

(defn- common-suffix
  "Length of the longest common suffix of a and b, at most limit."
  [^String a ^String b limit]
  (let [la (count a) lb (count b)]
    (loop [i 0]
      (if (and (< i limit) (= (nth a (- la 1 i)) (nth b (- lb 1 i)))) (recur (inc i)) i))))

(defn- edit-window
  "{:p :q :old-len :new-len :delta :old-tail :new-tail}: the prefix and
  suffix lengths the edit left alone (p, q) and where the changed middle
  sits in the old and new text."
  [old-text new-text bounds]
  (let [old-len (count old-text)
        new-len (count new-text)
        p (min (common-prefix old-text new-text) (:prefix bounds 0))
        q (common-suffix old-text new-text
                         (min (:suffix bounds 0) (- (min old-len new-len) p)))]
    {:p p :q q :old-len old-len :new-len new-len
     :delta (- new-len old-len)
     :old-tail (- old-len q)
     :new-tail (- new-len q)}))

(defn- crossing-span
  "[a b] in new-text offsets for the old span s..e that straddles the edit."
  [{:keys [p old-tail new-tail delta]} s e]
  [(cond (< s p) s (>= s old-tail) (+ s delta) :else p)
   (cond (> e old-tail) (+ e delta) (> e p) new-tail :else e)])

(defn- sort-old-span
  "reduce step: files the old span before the edit and after it under :kept
  (shifted when after), and one that crosses it under :crossing."
  [{:keys [p old-tail delta] :as window} acc [k s e :as span]]
  (cond (<= e p) (update acc :kept conj! span)
        (>= s old-tail) (update acc :kept conj! [k (+ s delta) (+ e delta)])
        :else (update acc :crossing conj! (crossing-span window s e))))

(defn- sort-old-plan
  "{:kept #{span} :crossing [[a b]]} for old-plan around the edit window."
  [old-plan window]
  (let [{:keys [kept crossing]}
        (reduce (partial sort-old-span window)
                {:kept (transient #{}) :crossing (transient [])}
                old-plan)]
    {:kept (persistent! kept) :crossing (persistent! crossing)}))

(defn- changed-spans
  "[a b] pairs for the spans that differ between old and new plan: the
  symmetric difference of new-plan and kept, plus the crossing spans."
  [new-plan {:keys [kept crossing]}]
  (let [new-set (set new-plan)
        changed (concat (remove kept new-plan)
                        (remove new-set kept))]
    (concat (map (fn [[_ s e]] [s e]) changed) crossing)))

(defn plan-diff
  "The smallest region to re-tag after an edit. Returns {:range [a b] :apply
  spans} in new-text offsets, or nil when nothing needs re-tagging. :apply is
  every span of new-plan that intersects [a b), whole and in plan order, so a
  front-end clears its tags over :range and applies :apply.

  Old spans before the edit stay, old spans after it shift, and old spans
  crossing it count as changed. The changed set is the symmetric difference
  of the new plan and the stayed-or-shifted old spans, plus the crossing
  spans.

  Comparing texts can't locate an edit: typing ( just before an existing (
  makes the common prefix one char longer than the real edit start, and GTK
  gives inherited tags to the char at the real start. So bounds is
  {:prefix m :suffix n}: no edit since old-text touched the first m chars or
  the last n chars. nil means unknown and re-tags the whole text. One pass over each plan plus hash sets, so linear in span count.

  Takes one map: {:old-text :old-plan :new-text :new-plan :bounds}."
  [{:keys [old-text old-plan new-text new-plan bounds]}]
  (let [{:keys [p q old-len new-len new-tail] :as window} (edit-window old-text new-text bounds)
        spans (changed-spans new-plan (sort-old-plan old-plan window))]
    (when-not (and (empty? spans) (>= (+ p q) old-len) (>= (+ p q) new-len))
      (let [a (reduce min p (map first spans))
            b (reduce max new-tail (map second spans))]
        {:range [a b]
         :apply (filterv (fn [[_ s e]] (and (< s b) (> e a))) new-plan)}))))
