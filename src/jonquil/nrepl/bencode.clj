(ns jonquil.nrepl.bencode
  "Bencode, nREPL's wire format. Pure over byte arrays and a byte source.

  encode: strings (UTF-8, length in bytes), keywords and symbols (as
  strings, keeping a namespace), integers, sequential collections (lists)
  and maps (dictionaries, keys sorted, nil values left out). Anything else
  throws ex-info.

  decode: dictionaries keep their keys as strings, byte strings become
  UTF-8 strings, integers become longs. It reads from a byte source, a
  zero-arg fn returning the next byte 0..255 or -1 at end of input.
  End of input before a value starts is ::eof; inside a value it throws
  ex-info with :type ::truncated."
  (:import (java.io ByteArrayOutputStream)))

(def ^:private max-bytes
  "Maximum length of a single bencode string. This is far above any real
   nREPL message, and guards against unbounded allocation attacks."
  (* 64 1024 1024))

(defn- key-string [k]
  (cond (string? k) k
        (keyword? k) (if-let [n (namespace k)] (str n "/" (name k)) (name k))
        (symbol? k) (str k)
        :else (throw (ex-info (str "bencode: unsupported key " (pr-str k)) {:key k}))))

(defn- write-str! [^ByteArrayOutputStream out ^String s]
  (let [b (.getBytes s "UTF-8")]
    (.write out (.getBytes (str (count b) ":") "UTF-8"))
    (.write out b)))

(defn- write! [^ByteArrayOutputStream out x]
  (cond
    (string? x) (write-str! out x)
    (or (keyword? x) (symbol? x)) (write-str! out (key-string x))
    (integer? x) (.write out (.getBytes (str "i" x "e") "UTF-8"))
    (map? x) (do (.write out (int \d))
                 (doseq [[k v] (sort-by (comp key-string key) x)
                         :when (some? v)]
                   (write-str! out (key-string k))
                   (write! out v))
                 (.write out (int \e)))
    (sequential? x) (do (.write out (int \l))
                        (doseq [v x] (write! out v))
                        (.write out (int \e)))
    :else (throw (ex-info (str "bencode: can't encode " (pr-str x)) {:value x}))))

(defn encode
  "x as a bencode byte array."
  ^bytes [x]
  (let [out (ByteArrayOutputStream.)]
    (write! out x)
    (.toByteArray out)))

(defn- truncated []
  (throw (ex-info "bencode: input ended inside a value" {:type ::truncated})))

(defn- next! [src]
  (let [b (src)] (if (neg? b) (truncated) b)))

(defn- parse-integer
  "The long that digit text s spells, or throws ::malformed for an empty
  text, a lone minus, or an out-of-range number."
  [s]
  (letfn [(malformed [] (throw (ex-info "bencode: malformed integer" {:type ::malformed :text s})))]
    (if (or (empty? s) (= s "-"))
      (malformed)
      (try (Long/parseLong s)
           (catch NumberFormatException _ (malformed))))))

(defn- read-digits
  "Reads decimal digits (and a leading minus) up to the terminator byte t,
  consuming t. first-b is a byte already read."
  [src first-b t]
  (loop [b first-b acc (StringBuilder.) pos 0]
    (cond
      (= b (int t)) (parse-integer (str acc))
      (and (= b (int \-)) (zero? pos)) (recur (next! src) (.append acc (char b)) (inc pos))
      (<= (int \0) b (int \9)) (recur (next! src) (.append acc (char b)) (inc pos))
      (= b (int \-)) (throw (ex-info "bencode: malformed integer" {:type ::malformed :text (str acc (char b))}))
      :else (throw (ex-info (str "bencode: unexpected byte " b " in a number") {:byte b})))))

(defn- read-bytes [src n]
  (when (or (neg? n) (> n max-bytes))
    (throw (ex-info "bencode: string length out of range" {:type ::too-large :length n})))
  (let [buf (byte-array n)]
    (dotimes [i n] (aset-byte buf i (unchecked-byte (next! src))))
    (String. buf "UTF-8")))

(declare read-value)

(defn- read-from [src b]
  (cond
    (= b (int \i)) (read-digits src (next! src) \e)
    (= b (int \l)) (loop [acc []]
                     (let [b (next! src)]
                       (if (= b (int \e)) acc (recur (conj acc (read-from src b))))))
    (= b (int \d)) (loop [acc {}]
                     (let [b (next! src)]
                       (if (= b (int \e))
                         acc
                         (let [k (read-from src b)
                               v (read-from src (next! src))]
                           (recur (assoc acc k v))))))
    (<= (int \0) b (int \9)) (read-bytes src (read-digits src b \:))
    :else (throw (ex-info (str "bencode: unexpected byte " b) {:byte b}))))

(defn decode
  "The next value from src, or ::eof when src is already at its end."
  [src]
  (let [b (src)]
    (if (neg? b) ::eof (read-from src b))))

(defn byte-source
  "A byte source over a java.io.InputStream, reading through a 64 KiB
  buffer so a message costs a few reads, not one per byte."
  [^java.io.InputStream in]
  (let [buf (byte-array 65536)
        pos (atom 0)
        lim (atom 0)]
    (fn []
      (when (>= @pos @lim)
        (reset! pos 0)
        (reset! lim (max 0 (.read in buf 0 (alength buf)))))
      (if (>= @pos @lim)
        -1
        (let [b (aget buf @pos)]
          (swap! pos inc)
          (bit-and 0xff b))))))
