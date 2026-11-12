(ns jonquil.source
  "Where a definition outside the buffer lives, and its text: the pure half
  of the source panel. A lookup result's :file is either an absolute path
  or a jar:file:<jar>!/<entry> URL; that shape measured identical on
  clojure-cli.repl, plain nREPL 1.7.0 and cider-nrepl 0.62.2. Nothing here touches GTK."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def history-limit
  ;; The bound on the panel's own back history.
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

(defn- jar-location
  "location's map for a jar: URL string file at line, nil when it has no !/
  entry."
  [file line]
  (when-let [bang (str/index-of file "!/")]
    (let [jar (uri-path (subs file 4 bang))
          entry (subs file (+ bang 2))]
      {:jar jar :entry entry :line line :editable? false
       :label (str (file-name entry) " in " (file-name jar))})))

(defn- file-location
  "location's map for a file: URL or absolute path file at line, editable
  when it lies under project-dir."
  [file line project-dir]
  (let [path (if (str/starts-with? file "file:") (uri-path file) file)]
    {:path path :line line
     :editable? (boolean (and project-dir (under? path project-dir)))
     :label (file-name path)}))

(defn location
  "Where lookup-info's definition lives, or nil when it carries no
  resolvable :file. opts is {:project-dir dir-or-nil}. A plain file gives
  {:path p}, a jar entry {:jar j :entry e}; both add :line (1 when absent
  or not positive), :editable? (only a plain file under :project-dir,
  compared canonically, so a symlinked project dir counts and a .. path
  climbing out doesn't) and :label, the panel header's text.

  Only a jar:/file: URL or an absolute path counts as a location; anything
  else is nil, the same as no :file at all. MEASURED (jolt run, not
  recalled): jonquil.syntax/get-symbol-doc's runtime-Var branch always
  hands an in-process Jolt lookup a :file string -- a bare relative
  classpath name (e.g. \"clojure/core.clj\") when the Var's own metadata
  carries one, else the literal word \"unknown\" (its own (or (:file m)
  \"unknown\") fallback) -- and neither shape is a place this function, or
  anything that opens what it returns, can actually read."
  [{:keys [file line]} {:keys [project-dir]}]
  (when (and (string? file) (seq file)
             (or (str/starts-with? file "jar:")
                 (str/starts-with? file "file:")
                 (str/starts-with? file "/")))
    (let [line (if (and (integer? line) (pos? line)) line 1)]
      (if (str/starts-with? file "jar:")
        (jar-location file line)
        (file-location file line project-dir)))))

(defn- existing-file-path
  "The canonical path of p when it names a file, else nil (also for nil p)."
  [p]
  (when p
    (let [f (io/file p)] (when (.isFile f) (.getCanonicalPath f)))))

(defn classpath-file
  "A bare classpath name (\"demo/util.clj\") as something location can read,
  resolved with io/resource on the roots this process searches: a file: URL
  whose file exists gives its canonical path, a jar:file:<jar>!/<entry> URL
  is kept as it is, anything else is nil. Jolt from jolt-lang/jolt f714b0ed
  (2026-10-19, after v0.8.23) records a required namespace's :file this way,
  as JVM Clojure does; v0.8.23 recorded the root's spelling (an absolute
  path, or ./src/...). What a stdlib name such as clojure/string.clj gives
  depends on the Jolt: releases to v0.8.23 answer a URL whose string is
  the bare name (nil here), and jolt main (jolt-lang/jolt #1438, PR #1440,
  2026-10-29) a jar:file:jolt-embedded-resources.jar!/... URL, kept like
  any other jar entry; slurp reads it (measured, 6462 chars). Never
  throws (a lookup mustn't)."
  [cp-name]
  (try
    (when-let [u (io/resource cp-name)]
      (let [s (str u)]
        (cond
          (and (str/starts-with? s "jar:file:") (str/includes? s "!/")) s
          (str/starts-with? s "file:") (existing-file-path (uri-path s)))))
    (catch :default _ nil)))

(defn- classpath-name?
  "True for a bare source name such as demo/util.clj: not a URL, not a path
  (absolute or ./), ending in a source extension. Leaves out
  get-symbol-doc's \"unknown\" and v0.8.23's ./src/... spelling."
  [f]
  (and (string? f)
       (re-find #"\.(clj|cljc|jolt)$" f)
       (not (re-find #"^(jar:|file:|/|\./|\.\./)" f))))

(defn resolve-file
  "doc-info with a bare classpath :file (classpath-name?) replaced by
  classpath-file's answer when it has one, so location can open it. Any
  other doc-info comes back unchanged."
  [doc-info]
  (let [f (:file doc-info)]
    (if-let [p (when (classpath-name? f) (classpath-file f))]
      (assoc doc-info :file p)
      doc-info)))

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
