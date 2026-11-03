(ns jonquil.text-offsets
  "Character offsets and string indexes, and the conversion between them.
  A snapshot's :caret and :sel-start/:sel-end are character offsets, the
  unit GTK's text iters count in. subs, nth and count take string indexes,
  which since jolt-lang/jolt#1472 count UTF-16 units, as on the JVM. The two
  agree until a character outside the Basic Multilingual Plane, such as an
  emoji, which is one character and two units. A text without one takes
  the identity path, so callers check astral? once and skip the rest.

  A conversion goes through the text's starts (astral-starts), built in one
  scan, so an op converting k offsets costs one pass over the text and k
  binary searches, not k passes.")

(defn char-count
  "How many characters s holds."
  [^String s]
  (.codePointCount s 0 (count s)))

(defn astral?
  "Whether s holds a character that takes two string indexes."
  [^String s]
  (not= (count s) (char-count s)))

(defn astral-starts
  "The ascending string indexes at which s's two-index characters start."
  [^String s]
  (let [n (count s)]
    (loop [i 0 acc (transient [])]
      (cond
        (>= i n) (persistent! acc)
        (Character/isHighSurrogate (.charAt s i)) (recur (+ i 2) (conj! acc i))
        :else (recur (inc i) acc)))))

(defn- leading
  "How many of starts' first entries satisfy ok? (called with an entry's
  position), for an ok? that holds up to some position and not after."
  [starts ok?]
  (loop [lo 0 hi (count starts)]
    (if (< lo hi)
      (let [mid (quot (+ lo hi) 2)]
        (if (ok? mid) (recur (inc mid) hi) (recur lo mid)))
      lo)))

;; Both conversions move an offset outside the text by the same distance
;; past the end it is beyond, so an op's own bounds check (a caret past the
;; end, a negative one) answers as it did before the conversion.

(defn to-index
  "The string index of character offset n, in the text whose astral-starts
  are starts."
  [starts n]
  ;; the j-th start is character offset (start - j)
  (+ n (leading starts #(< (- (nth starts %) %) n))))

(defn to-char
  "The character offset of string index i, in the text whose astral-starts
  are starts. An index between a character's two halves is that
  character's offset."
  [starts i]
  (- i (leading starts #(< (nth starts %) i))))

(defn snapshot->indexes
  "snap with :caret, :sel-start and :sel-end as string indexes into its
  :text. A missing or nil key stays as it is."
  [{:keys [text] :as snap}]
  (let [starts (astral-starts text)]
    (reduce (fn [m k]
              (if-some [n (get m k)] (assoc m k (to-index starts n)) m))
            snap
            [:caret :sel-start :sel-end])))

(defn keys->chars
  "m with the string indexes under ks, where present and not nil, as
  character offsets into s."
  [s m ks]
  (let [starts (astral-starts s)]
    (reduce (fn [m k]
              (if-some [i (get m k)] (assoc m k (to-char starts i)) m))
            m
            ks)))
