(ns jonquil.docs-panel
  "LM6's docs panel, the parts that need no GTK: the pages it shows (a
  symbol's doc, or a guide page), its back and forward history capped at
  max-history, the bookmarks kept in the settings file's :doc-bookmarks, the
  palette entries that list them, the symbol under a click in a page, and
  the guide: where the running checkout keeps docs/guide, its pages, and
  markdown turned into the plain text the panel shows."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; --- pages ------------------------------------------------------------------

(defn symbol-page
  "The page for the doc of sym, written as typed."
  [sym]
  {:kind :symbol :id sym})

(defn guide-page
  "The page for the guide page id, its path under docs/guide without .md."
  [id]
  {:kind :guide :id id})

(defn valid-page?
  "True for a map with a :kind of :symbol or :guide and a non-blank string :id."
  [p]
  (and (map? p)
       (contains? #{:symbol :guide} (:kind p))
       (string? (:id p))
       (not (str/blank? (:id p)))))

(defn page-label
  "How a page reads in the panel and the palette: the symbol, or Guide: id."
  [{:keys [kind id]}]
  (if (= :guide kind) (str "Guide: " id) id))

(defn page-key
  "A page as one string, symbol:map or guide:keys, for a palette entry's id."
  [{:keys [kind id]}]
  (str (name kind) ":" id))

(defn key->page
  "The page a page-key names (the first colon splits it), nil for anything else."
  [k]
  (when (string? k)
    (when-let [i (str/index-of k ":")]
      (let [kind (subs k 0 i)
            id (subs k (inc i))]
        (case kind
          "symbol" (when-not (str/blank? id) (symbol-page id))
          "guide" (when-not (str/blank? id) (guide-page id))
          nil)))))

(defn canonical-id
  "The id to keep for a symbol page: the qualified name a full doc's title
  gives (clojure.core/map for map) when sym was typed bare and the title is
  one word, else sym as it was written."
  [sym title]
  (if (and (string? title)
           (not (str/includes? sym "/"))
           (re-matches #"\S+" title))
    title
    sym))

;; --- history ----------------------------------------------------------------

(def max-history
  "How many pages the history keeps."
  50)

(def empty-history
  "A history with no page: {:items [page ...] :at index-of-the-current-one}."
  {:items [] :at -1})

(defn current
  "The page the history is on, nil for an empty one."
  [{:keys [items at]}]
  (get items at))

(defn push
  "h with page after the current one, the pages that were ahead of it gone,
  the oldest dropped past max-history. The page already shown adds nothing."
  [{:keys [items at] :as h} page]
  (if (= page (current h))
    h
    (let [items (conj (subvec items 0 (inc at)) page)
          items (subvec items (max 0 (- (count items) max-history)))]
      {:items items :at (dec (count items))})))

(defn back
  "h one page earlier, nil at the oldest."
  [{:keys [at] :as h}]
  (when (pos? at) (assoc h :at (dec at))))

(defn forward
  "h one page later, nil at the newest."
  [{:keys [items at] :as h}]
  (when (< (inc at) (count items)) (assoc h :at (inc at))))

(defn position-text
  "\"page 2 of 7\" for the page h is on, \"\" for an empty history."
  [{:keys [items at]}]
  (if (empty? items) "" (str "page " (inc at) " of " (count items))))

;; --- bookmarks --------------------------------------------------------------

(defn bookmarked?
  "True when page is among bookmarks."
  [bookmarks page]
  (boolean (some #{page} bookmarks)))

(defn add-bookmark
  "bookmarks (nil reads as none) with page at the end, unless it is there
  already or isn't a valid page."
  [bookmarks page]
  (let [bookmarks (vec bookmarks)]
    (if (and (valid-page? page) (not (bookmarked? bookmarks page)))
      (conj bookmarks page)
      bookmarks)))

(defn remove-bookmark
  "bookmarks without page."
  [bookmarks page]
  (vec (remove #{page} bookmarks)))

(defn toggle-bookmark
  "{:added? b :bookmarks bs}: page added when it wasn't bookmarked, else removed."
  [bookmarks page]
  (if (bookmarked? bookmarks page)
    {:added? false :bookmarks (remove-bookmark bookmarks page)}
    {:added? true :bookmarks (add-bookmark bookmarks page)}))

(defn- page-entry
  "A palette entry that opens page, doc reading prefix then text."
  [prefix text page]
  {:id [:docs/page (page-key page)] :doc (str prefix text) :chords [] :source "doc"})

(defn bookmark-entries
  "The palette entries that open each bookmark: {:id [:docs/page \"symbol:map\"]
  :doc \"Doc bookmark: map\" :chords [] :source \"doc\"}. The window runs
  these itself; they aren't registry commands."
  [bookmarks]
  (vec (for [p bookmarks] (page-entry "Doc bookmark: " (page-label p) p))))

;; --- links ------------------------------------------------------------------

(def ^:private non-symbol-chars
  "Characters that end a symbol in a page's text."
  #{\space \tab \newline \return \( \) \[ \] \{ \} \" \, \; \' \` \~ \^ \@ \\ \#})

(defn- symbol-char? [c] (not (contains? non-symbol-chars c)))

(defn- word-bounds
  "[start end) of the run of symbol characters holding offset, nil when the
  character there isn't one."
  [^String text offset]
  (when (and (<= 0 offset) (< offset (count text)) (symbol-char? (.charAt text (int offset))))
    (let [start (loop [i offset] (if (and (pos? i) (symbol-char? (.charAt text (int (dec i))))) (recur (dec i)) i))
          end (loop [i (inc offset)] (if (and (< i (count text)) (symbol-char? (.charAt text (int i)))) (recur (inc i)) i))]
      [start end])))

(defn symbol-at
  "The symbol in text at character offset, as a click on it names one: a run
  of symbol characters without the periods and colons that end it. nil on
  whitespace, brackets, past the end, and for a keyword, a number or
  nothing but punctuation."
  [text offset]
  (when (and (string? text) (integer? offset))
    (when-let [[s e] (word-bounds text offset)]
      (let [w (str/replace (subs text s e) #"[.:]+$" "")]
        (when (and (seq w)
                   (not (str/starts-with? w ":"))
                   (re-find #"[A-Za-z*+!?<>=&%_-]" w)
                   (not (re-matches #"[-+]?\d[\d.]*" w)))
          w)))))

;; --- the guide --------------------------------------------------------------

(defn guide-dir
  "The docs/guide directory of the checkout that holds source-path (a file
  two directories below the source root, such as src/jonquil/keymap.edn),
  as a canonical path; nil when source-path is nil or missing, or that tree
  has no docs/guide/index.md. The installed app is one compiled file with
  no source tree, so it has none."
  [source-path]
  (when source-path
    (let [f (io/file source-path)]
      (when (.isFile f)
        (let [root (some-> f .getCanonicalFile .getParentFile .getParentFile .getParentFile)
              guide (when root (io/file root "docs" "guide"))]
          (when (and guide (.isFile (io/file guide "index.md")))
            (.getCanonicalPath guide)))))))

(defn- first-heading
  "The text of md's first line that starts with \"# \", else nil."
  [md]
  (some (fn [line] (when (str/starts-with? line "# ") (str/trim (subs line 2))))
        (str/split-lines md)))

(defn- relative-id
  "The path of the file at path below the directory at base-path (both
  canonical), without .md, with / separators."
  [base-path path]
  (-> (subs path (inc (count base-path)))
      (str/replace "\\" "/")
      (str/replace #"\.md$" "")))

(defn guide-pages
  "The markdown pages under dir, recursively, sorted by id: {:id \"keys\"
  :title \"Keys\" :path file}. The id is the path below dir without .md; the
  title is the page's first # heading, else the id. [] for a nil or missing dir."
  [dir]
  (if-not (and dir (.isDirectory (io/file dir)))
    []
    (let [base (.getCanonicalFile (io/file dir))
          base-path (.getPath base)]
      (->> (file-seq base)
           (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".md")))
           (map (fn [^java.io.File f]
                  (let [id (relative-id base-path (.getPath f))]
                    {:id id :title (or (first-heading (slurp f)) id) :path (.getPath f)})))
           (sort-by :id)
           vec))))

(defn guide-file
  "The canonical path of guide page id's markdown file under dir, nil when
  dir or the file is missing or id climbs out of dir (a bookmark is read
  from the settings file, so its id is data, not trusted)."
  [dir id]
  (when (and dir (string? id) (not (str/blank? id)))
    (let [base (.getCanonicalFile (io/file dir))
          f (.getCanonicalFile (io/file base (str id ".md")))]
      (when (and (.isFile f) (str/starts-with? (.getPath f) (str (.getPath base) "/")))
        (.getPath f)))))

(defn guide-entries
  "The palette entries that open each guide page: {:id [:docs/page
  \"guide:keys\"] :doc \"Guide page: Keys (keys)\" :chords [] :source \"doc\"}.
  A page whose title is its id reads without the parenthesis."
  [pages]
  (vec (for [{:keys [id title]} pages]
         (page-entry "Guide page: " (if (= id title) id (str title " (" id ")")) (guide-page id)))))

(def ^:private kept-tag-re
  #"</?(?:details|summary|strong|em|b|i|br|p|a|code|kbd|sub|sup)(?:\s[^>]*)?>")

(defn- plain-line
  "A markdown line as plain text: links and images as their text, ** and
  backticks dropped, the common HTML tags dropped."
  [line]
  (-> line
      (str/replace kept-tag-re "")
      (str/replace #"!?\[([^\]\n]{0,200})\]\([^)\n]{0,400}\)" "$1")
      (str/replace "**" "")
      (str/replace "`" "")))

(defn- heading
  "[level text] for a markdown heading line, nil for any other."
  [line]
  (when-let [[_ hashes text] (re-matches #"(#{1,6})\s+(.*\S)\s*" line)]
    [(count hashes) text]))

(defn- heading-lines
  "The lines a heading of level takes in the panel: its text, underlined for
  levels 1 and 2, with a blank line before."
  [level text]
  (let [t (plain-line text)]
    (cond-> [t]
      (= 1 level) (conj (apply str (repeat (count t) "=")))
      (= 2 level) (conj (apply str (repeat (count t) "-"))))))

(defn- summary-text
  "The text inside a <summary> line, nil for any other line."
  [line]
  (when-let [[_ inner] (re-matches #"\s*<summary>(.*)</summary>\s*" line)]
    (plain-line inner)))

(defn- prose-line
  "The lines one markdown line outside a code fence becomes, as a vector."
  [line]
  (let [t (str/trim line)]
    (cond
      (or (str/starts-with? t "<!--") (#{"<details>" "</details>"} t)) []
      (re-matches #"\|?[\s:|]*-[\s:|-]*" t) (if (str/includes? t "|") [] [(plain-line line)])
      (heading line) (let [[level text] (heading line)] (into [""] (heading-lines level text)))
      (summary-text line) (into [""] (heading-lines 2 (summary-text line)))
      :else [(plain-line line)])))

(defn guide-text
  "Markdown md as the plain text the panel shows: headings kept, underlined
  for the first two levels, links as their text, code fences' lines
  indented four spaces, a table's separator row and the HTML around a
  collapsible section dropped, runs of blank lines made one. \"\" for nil."
  [md]
  (if-not (string? md)
    ""
    (let [lines (str/split-lines (str/replace md "\r\n" "\n"))
          out (loop [[line & more :as ls] lines, fenced? false, acc []]
                (cond
                  (empty? ls) acc
                  (str/starts-with? (str/triml line) "```") (recur more (not fenced?) acc)
                  fenced? (recur more fenced? (conj acc (str "    " line)))
                  :else (recur more fenced? (into acc (prose-line line)))))
          squeezed (reduce (fn [acc l]
                             (if (and (str/blank? l) (or (empty? acc) (str/blank? (peek acc))))
                               acc
                               (conj acc (if (str/blank? l) "" l))))
                           [] out)]
      (-> (str/join "\n" squeezed) str/trim))))
