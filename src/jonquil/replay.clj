(ns jonquil.replay
  "The record behind \"replay last session\": per project, an EDN map
  {:version 1 :previous [entry ...] :current [entry ...] :history [text ...]}
  in replays/<folder>-<hash>.edn beside settings.edn. An entry is
  {:kind :file :path abs :at ms :dialect name} or
  {:kind :form :text s :ns \"user\" :source-file abs-or-nil :at ms :dialect name}.
  The record holds whatever the user typed, secrets included, so the file is
  owner-only (rw-------) and a directory made for it is rwx------. Nothing
  here throws: loading a bad file gives an empty record plus a :warning, and
  saving returns nil or an error string."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.commands :as commands]
            [jonquil.last-session :as last-session]
            [jonquil.world :as world]))

(defn empty-record
  "A record with nothing in it."
  []
  {:version 1 :previous [] :current [] :history []})

(defn record-path
  "The project's record file: replays/<folder>-<hash>.edn beside the
  settings file, or replays/no-folder.edn for a nil root. The leaf and hash
  are made as jonquil.world/world-dir makes them."
  [settings-path root]
  (let [dir (str (.getParent (java.io.File. ^String settings-path)) "/replays")]
    (if root
      (let [leaf (str/replace (.getName (java.io.File. ^String root)) #"[^A-Za-z0-9._-]" "-")]
        (str dir "/" leaf "-" (Integer/toHexString (.hashCode ^String root)) ".edn"))
      (str dir "/no-folder.edn"))))

(defn- file-name [path]
  (.getName (java.io.File. ^String path)))

(defn- good-entry? [e]
  (and (map? e) (contains? #{:file :form} (:kind e))))

(defn- clean-entries [xs]
  (vec (filter good-entry? xs)))

(defn load-record
  "{:record r} from path. A missing file is {:record (empty-record)}. A file
  that isn't EDN, isn't a map, or whose :previous, :current or :history
  isn't sequential (when present) is the empty record plus a :warning.
  Entries that aren't maps with :kind :file or :form are dropped silently."
  [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      {:record (empty-record)}
      (let [m (try (edn/read-string (slurp f)) (catch Exception _ ::unreadable))
            ok? (and (map? m)
                     (every? #(or (nil? (get m %)) (sequential? (get m %)))
                             [:previous :current :history]))]
        (if ok?
          {:record {:version 1
                    :previous (clean-entries (:previous m))
                    :current (clean-entries (:current m))
                    :history (vec (filter string? (:history m)))}}
          {:record (empty-record)
           :warning (str "Couldn't read " (file-name path)
                         ", so the last session starts empty.")})))))

(def ^:private owner-only-dir "rwx------")

(defn- make-private-dir!
  "Makes the directory of path as rwx------ when it doesn't exist yet.
  Never throws."
  [path]
  (try
    (let [dir (.getParentFile (java.io.File. ^String path))]
      (when (and dir (not (.exists dir)))
        (.mkdirs dir)
        (java.nio.file.Files/setPosixFilePermissions
         (.toPath dir)
         (java.nio.file.attribute.PosixFilePermissions/fromString owner-only-dir))))
    (catch Exception _ nil)))

(defn save-record!
  "Writes record to path atomically; the file is rw------- from the moment
  it exists. A replays/ directory it has to make is rwx------. Failing to
  set permissions never fails the save. Never throws: nil on success, else
  \"couldn't save <path>: <message>\"."
  [path record]
  (try
    (make-private-dir! path)
    (last-session/write-atomically! path (pr-str record) {:owner-only? true})
    (catch Exception e
      (str "couldn't save " path ": " (.getMessage e)))))

;; --- entries -------------------------------------------------------------

(def ^:private slot-cap 2000)
(def ^:private history-cap 500)

(defn- kid-text [code kid] (subs code (:start kid) (:end kid)))

(defn- skip-meta-map
  "Index just past the balanced {...} map that starts at i in s, strings
  skipped. Past the end of s when it never closes."
  [s i]
  (loop [j i depth 0 in-str? false]
    (if (>= j (count s))
      j
      (let [c (nth s j)]
        (cond
          in-str? (cond (= c \\) (recur (+ j 2) depth true)
                        (= c \") (recur (inc j) depth false)
                        :else (recur (inc j) depth true))
          (= c \") (recur (inc j) depth true)
          (= c \{) (recur (inc j) (inc depth) false)
          (= c \}) (if (= 1 depth) (inc j) (recur (inc j) (dec depth) false))
          :else (recur (inc j) depth false))))))

(defn- strip-meta
  "s without leading ^:kw, ^Sym and ^{...} metadata prefixes (repeated
  ones too), trimmed."
  [s]
  (loop [t (str/triml s)]
    (if (str/starts-with? t "^")
      (let [end (if (str/starts-with? t "^{")
                  (skip-meta-map t 1)
                  (or (some (fn [i] (when (Character/isWhitespace ^char (nth t i)) i))
                            (range (count t)))
                      (count t)))]
        (recur (str/triml (subs t (min end (count t))))))
      t)))

(defn- plain-symbol? [s]
  (boolean (re-matches #"[^\s()\[\]{}\"'`~@^;,\\]+" s)))

(defn- in-ns-target
  "The ns an (ns x) or (in-ns 'x) form switches to, or nil: ns takes a plain
  symbol (metadata allowed), in-ns only a quoted plain symbol."
  [head arg]
  (when arg
    (let [t (strip-meta arg)]
      (if (= "ns" head)
          (when (plain-symbol? t) t)
          (when (str/starts-with? t "'")
            (let [sym (strip-meta (subs t 1))]
              (when (plain-symbol? sym) sym)))))))

(defn- absolute-path? [p]
  (boolean (or (str/starts-with? p "/") (re-find #"^[A-Za-z]:[\\/]" p))))

(defn- load-file-path
  "The path of a (load-file \"/abs/path\") form, or nil for any other form
  or a relative path."
  [head kid-texts]
  (when (and (= "load-file" head) (second kid-texts))
    (let [t (second kid-texts)]
      (when (and (> (count t) 1) (str/starts-with? t "\"") (str/ends-with? t "\""))
        (let [p (try (edn/read-string t) (catch Exception _ nil))]
          (when (and (string? p) (absolute-path? p)) p))))))

(defn entries
  "Entries for what one action ran. source is :run (a whole buffer: a saved
  one is a file entry, an untitled one is split into forms), :form,
  :selection or :repl (forms). An in-ns or ns form moves the ns the later
  forms are recorded in, starting from ns (default \"user\"). An absolute
  (load-file \"...\") is a file entry; a relative one stays a form."
  [{:keys [source code path ns at dialect]}]
  (if (and (= :run source) path)
    [{:kind :file :path path :at at :dialect dialect}]
    (let [source-file (when (contains? #{:form :selection} source) path)]
      (loop [forms (commands/top-level-forms (or code "")) cur-ns (or ns "user") out []]
        (if-let [f (first forms)]
          (let [kids (:kids f)
                texts (mapv #(kid-text code %) kids)
                head (when (and (seq kids) (not (:open (first kids)))) (first texts))
                lf (load-file-path head texts)
                next-ns (or (when (contains? #{"ns" "in-ns"} head)
                              (in-ns-target head (second texts)))
                            cur-ns)]
            (recur (rest forms) next-ns
                   (conj out (if lf
                               {:kind :file :path lf :at at :dialect dialect}
                               {:kind :form :text (kid-text code f) :ns cur-ns
                                :source-file source-file :at at :dialect dialect}))))
          out)))))

;; --- slots ---------------------------------------------------------------

(defn add-entries
  "record with es appended to :current, the newest 2,000 kept."
  [record es]
  (assoc record :current (vec (take-last slot-cap (concat (:current record) es)))))

(defn open-slot
  "A new session starts: :current goes after :previous (the newest 2,000
  kept) and :current is empty. Unchanged when :current is empty."
  [record]
  (if (empty? (:current record))
    record
    (assoc record
           :previous (vec (take-last slot-cap (concat (:previous record) (:current record))))
           :current [])))

(defn forget
  "record with :previous emptied."
  [record]
  (assoc record :previous []))

(defn with-history
  "record with :history set to the last 500 of items."
  [record items]
  (assoc record :history (vec (take-last history-cap items))))

;; --- compaction ----------------------------------------------------------

(def ^:private def-heads
  #{"def" "defn" "defn-" "defmacro" "defonce" "defmulti" "defmethod"
    "defprotocol" "defrecord" "deftype" "definterface" "defstruct"})

(def ^:private setup-heads #{"ns" "in-ns" "require" "use" "import" "refer"})

(defn- form-key
  "[group key] for a form entry: group is :defs or :others."
  [{:keys [text ns]}]
  (let [kids (:kids (first (commands/top-level-forms text)))
        texts (mapv #(kid-text text %) kids)
        head (when (and (seq kids) (not (:open (first kids)))) (first texts))
        names (map strip-meta (rest texts))]
    (cond
      (contains? def-heads head)
      [:defs (if (= "defmethod" head)
               [ns head (first names) (second names)]
               [ns head (first names)])]
      (contains? setup-heads head) [:defs [:setup text]]
      :else [:others [:other text]])))

(defn- keep-last
  "The entries whose key is the last one seen for it, in order."
  [es key-fn]
  (let [last-idx (reduce-kv (fn [m i e] (assoc m (key-fn e) i)) {} (vec es))]
    (vec (keep-indexed (fn [i e] (when (= i (get last-idx (key-fn e))) e)) es))))

(defn compact
  "Collapses es into {:files [path ...] :defs [entry ...] :others [entry ...]}.
  Files are deduped at their last position. Forms recorded from a file
  that is in :files are dropped. A def is kept once per [ns head name]
  (plus the dispatch value for defmethod), setup forms and other forms once
  per identical text, each at its last position."
  [es]
  (let [files (keep-last (filter #(= :file (:kind %)) es) :path)
        loaded (set (map :path files))
        forms (->> es
                   (filter #(= :form (:kind %)))
                   (remove #(contains? loaded (:source-file %)))
                   (mapv #(assoc % ::k (form-key %))))
        kept (keep-last forms ::k)
        strip #(dissoc % ::k)
        group (fn [g] (into [] (comp (filter #(= g (first (::k %)))) (map strip)) kept))]
    {:files (mapv :path files)
     :defs (group :defs)
     :others (group :others)}))

;; --- summary and script --------------------------------------------------

(def ^:private day-ms 86400000)

(defn- when-text [now es]
  (let [newest (apply max (map :at es))
        oldest (apply min (map :at es))]
    (if (<= (- newest oldest) day-ms)
      (world/ago-text now newest)
      (str "from " (world/ago-text now oldest) " to " (world/ago-text now newest)))))

(defn- newest-dialect [es]
  (:dialect (apply max-key :at es)))

(defn summary
  "{:files n :defs n :others n :dialect newest-name :when text} for es, or
  nil when es is empty. now is epoch ms."
  [es now]
  (when (seq es)
    (let [c (compact es)]
      {:files (count (:files c))
       :defs (count (:defs c))
       :others (count (:others c))
       :dialect (newest-dialect es)
       :when (when-text now es)})))

(defn- plural [n one many] (str n " " (if (= 1 n) one many)))

(defn- ns-runs
  "Lines for forms: an (in-ns 'x) before the first and before each form whose
  ns differs from the one before. render makes a form's own line."
  [forms render]
  (loop [fs forms prev ::none out []]
    (if-let [f (first fs)]
      (recur (rest fs) (:ns f)
             (cond-> out
               (not= prev (:ns f)) (conj (str "(in-ns '" (:ns f) ")"))
               true (conj (render f))))
      out)))

(defn script
  "The replay tab's text for es: a header, then files, definitions and other
  forms (commented out with #_), each under a heading and left out when
  empty. opts: :root project folder or nil, :now epoch ms, :dialect the
  current window's dialect name."
  [es {:keys [root now dialect]}]
  (let [{:keys [files defs others]} (compact es)
        s (summary es now)
        recorded (:dialect s)
        header (str ";; Last session "
                    (if root (str "in " root) "with no folder")
                    " (" recorded ", " (:when s) "), " (plural (count files) "file" "files") ",\n"
                    ";; " (plural (count defs) "definition" "definitions") ", "
                    (plural (count others) "other form" "other forms")
                    ". Nothing has run. Edit, then Run (Cmd/Ctrl+R).\n"
                    ";; A replay runs the code again, so results can differ from last time."
                    (when (not= recorded dialect)
                      (str "\n;; Recorded in " recorded "; this window runs " dialect ".")))
        section (fn [heading lines] (when (seq lines) (str heading "\n" (str/join "\n" lines))))]
    (str/join "\n\n"
              (remove nil?
                      [header
                       (section ";; Files you ran, loaded as they are on disk now"
                                (map #(str "(load-file " (pr-str %) ")") files))
                       (section ";; Definitions from the console"
                                (ns-runs defs :text))
                       (section ";; Other forms, skipped: remove the #_ to run one"
                                (ns-runs others #(str "#_" (:text %))))]))))
