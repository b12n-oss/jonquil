(ns jonquil.text-offsets
  "Character offsets and string indexes, and the conversion between them.
  A snapshot's :caret and :sel-start/:sel-end are character offsets, the
  unit GTK's text iters count in. subs, nth and count take string indexes,
  which since jolt-lang/jolt#1472 count UTF-16 units, as on the JVM. The two
  agree until a character outside the Basic Multilingual Plane, such as an
  emoji, which is one character and two units. A text without one takes
  the identity path, so callers check astral? once and skip the rest.")

(defn char-count
  "How many characters s holds."
  [^String s]
  (.codePointCount s 0 (count s)))

(defn astral?
  "Whether s holds a character that takes two string indexes."
  [^String s]
  (not= (count s) (char-count s)))

;; Both conversions move an offset outside s by the same distance past the
;; end it is beyond, so an op's own bounds check (a caret past the end, a
;; negative one) answers as it did before the conversion.

(defn to-index
  "The string index of character offset n in s."
  [^String s n]
  (let [chars (char-count s)]
    (cond
      (neg? n) n
      (> n chars) (+ (count s) (- n chars))
      :else (.offsetByCodePoints s 0 n))))

(defn to-char
  "The character offset of string index i in s."
  [^String s i]
  (cond
    (neg? i) i
    (> i (count s)) (+ (char-count s) (- i (count s)))
    :else (.codePointCount s 0 i)))

(defn snapshot->indexes
  "snap with :caret, :sel-start and :sel-end as string indexes into its
  :text. A missing or nil key stays as it is."
  [{:keys [text] :as snap}]
  (reduce (fn [m k]
            (if-some [n (get m k)] (assoc m k (to-index text n)) m))
          snap
          [:caret :sel-start :sel-end]))

(defn keys->chars
  "m with the string indexes under ks, where present and not nil, as
  character offsets into s."
  [s m ks]
  (reduce (fn [m k]
            (if-some [i (get m k)] (assoc m k (to-char s i)) m))
          m
          ks))
