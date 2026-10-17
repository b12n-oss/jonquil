(ns jonquil.mcp.json
  "JSON for the MCP server, on clojure.data.json.

  Jolt's data.json throws on a surrogate-pair escape and writes non-BMP
  characters as invalid escapes, so `decode` rewrites escapes first and
  `encode` writes characters raw."
  (:require [clojure.data.json :as dj]
            [clojure.string :as str]))

;; A real (not backslash-escaped) \uD8xx\uDCxx pair. Group 1 keeps any
;; escaped backslashes that precede it.
(def ^:private pair-re
  #"(?i)(?<!\\)((?:\\\\)*)\\u(d[89ab][0-9a-f]{2})\\u(d[c-f][0-9a-f]{2})")

;; Any surrogate escape still left after pairs are gone.
(def ^:private lone-re
  #"(?i)(?<!\\)((?:\\\\)*)\\ud[89a-f][0-9a-f]{2}")

(defn- code-point [hi lo]
  (+ 0x10000
     (* (- (Integer/parseInt hi 16) 0xD800) 0x400)
     (- (Integer/parseInt lo 16) 0xDC00)))

(defn- fix-surrogates [s]
  (-> s
      (str/replace pair-re
                   (fn [[_ slashes hi lo]]
                     (str slashes (Character/toString (int (code-point hi lo))))))
      (str/replace lone-re
                   (fn [[_ slashes]] (str slashes "\\ufffd")))))

(defn decode
  "Parse a JSON string into Clojure data with string keys. Throws ex-info
  on invalid JSON."
  [s]
  (try
    (dj/read-str (fix-surrogates s))
    (catch Exception e
      (throw (ex-info (str "invalid JSON: " (ex-message e)) {:input s} e)))))

(defn encode
  "Write x as one line of JSON, non-ASCII characters and slashes raw."
  [x]
  (dj/write-str x :escape-unicode false :escape-slash false))
