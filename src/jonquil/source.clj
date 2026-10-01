(ns jonquil.source
  "Where a definition outside the buffer lives, and its text: the pure half
  of the source panel. A lookup result's :file is either an absolute path
  or a jar:file:<jar>!/<entry> URL; the clj-servers spike measured that
  shape identical on clojure-cli.repl, plain nREPL 1.7.0 and cider-nrepl
  0.62.2. Nothing here touches GTK."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def history-limit
  ;; The spec's bound on the panel's own back history.
  50)

(def moved-note
  "The definition's line is past the end of the file; it may have changed since the server loaded it.")

(defn- canonical [p]
  (try (.getCanonicalPath (io/file p)) (catch :default _ nil)))

(defn same-path?
  "True when paths a and b name the same file, compared canonically."
  [a b]
  (let [ca (canonical a)]
    (boolean (and ca (= ca (canonical b))))))

(defn- under? [path dir]
  (let [p (canonical path) d (canonical dir)]
    (boolean (and p d (or (= p d) (str/starts-with? p (str d "/")))))))

(defn- file-name [p] (last (str/split p #"/")))

(defn- uri-path
  "The decoded path of a file: URI string (percent escapes, UTF-8 included),
  or the text after file: when it isn't a valid URI."
  [s]
  (try (.getPath (java.net.URI. s))
       (catch :default _ (subs s 5))))

(defn location
  "Where lookup-info's definition lives, or nil when it carries no :file.
  opts is {:project-dir dir-or-nil}. A plain file gives {:path p}, a jar
  entry {:jar j :entry e}; both add :line (1 when absent or not positive),
  :editable? (only a plain file under :project-dir, compared canonically,
  so a symlinked project dir counts and a .. path climbing out doesn't) and
  :label, the panel header's text."
  [{:keys [file line]} {:keys [project-dir]}]
  (when (and (string? file) (seq file))
    (let [line (if (and (integer? line) (pos? line)) line 1)]
      (if (str/starts-with? file "jar:")
        (when-let [bang (str/index-of file "!/")]
          (let [jar (uri-path (subs file 4 bang))
                entry (subs file (+ bang 2))]
            {:jar jar :entry entry :line line :editable? false
             :label (str (file-name entry) " in " (file-name jar))}))
        (let [path (if (str/starts-with? file "file:") (uri-path file) file)]
          {:path path :line line
           :editable? (boolean (and project-dir (under? path project-dir)))
           :label (file-name path)})))))

(defn same-file?
  "True when locations a and b show the same file or jar entry."
  [a b]
  (boolean
   (and a b
        (if (:jar a)
          (and (= (:jar a) (:jar b)) (= (:entry a) (:entry b)))
          (and (:path a) (:path b) (same-path? (:path a) (:path b)))))))

(defn read-text
  "{:text t} for loc's file or jar entry, else {:error msg}: a missing or
  unreadable file, a missing jar, or a jar without the entry. A jar is read
  locally (java.util.zip.ZipFile), since attach only ever reaches a server
  on this machine."
  [{:keys [path jar entry]}]
  (try
    (if jar
      (let [zf (java.util.zip.ZipFile. ^String jar)]
        (try
          (if-let [e (.getEntry zf entry)]
            {:text (slurp (.getInputStream zf e))}
            {:error (str entry " isn't in " (file-name jar) ".")})
          (finally (.close zf))))
      {:text (slurp path)})
    (catch :default e
      {:error (str "Couldn't read " (or path jar) ": " (ex-message e))})))

(defn- line-count [text]
  (inc (count (filter #(= \newline %) text))))

(defn clamp-line
  "{:line n :moved? bool}: line, or text's last line when line is past it."
  [text line]
  (let [n (line-count text)]
    (if (> line n) {:line n :moved? true} {:line line :moved? false})))

(defn line-offset
  "The character offset where 1-based line starts in text; text's end when
  line is past the last line."
  [text line]
  (loop [i 0 l 1]
    (if (>= l line)
      i
      (if-let [nl (str/index-of text "\n" i)]
        (recur (inc nl) (inc l))
        (count text)))))

(defn line-at
  "The 1-based line holding offset in text."
  [text offset]
  (line-count (subs text 0 (max 0 (min offset (count text))))))

(defn push
  "history with entry added, keeping the newest history-limit entries."
  [history entry]
  (vec (take-last history-limit (conj (vec history) entry))))

(defn back
  "[newest-entry rest-of-history], or nil for an empty history."
  [history]
  (when (seq history)
    (let [h (vec history)]
      [(peek h) (pop h)])))
