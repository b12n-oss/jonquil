(ns jonquil.replay
  "The record behind \"replay last session\": per project, an EDN map
  {:version 1 :previous [entry ...] :current [entry ...] :history [text ...]}
  in replays/<folder>-<hash>.edn beside settings.edn. An entry is
  {:kind :file :path abs :at ms :dialect name} or
  {:kind :form :text s :ns \"user\" :source-file abs-or-nil :at ms :dialect name
  :key k}, k being [head name] for a definition ([head name dispatch] for
  defmethod), :setup or :other; an entry without :key is parsed for it.
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
  (and (map? e) (contains? #{:file :form} (:kind e)) (number? (:at e))))

(defn- clean-entries [xs]
  (vec (filter good-entry? xs)))

(defn load-record
  "{:record r} from path. A missing file is {:record (empty-record)}. A file
  that isn't EDN, isn't a map, or whose :previous, :current or :history
  isn't sequential (when present) is the empty record plus a :warning.
  Entries that aren't maps with :kind :file or :form and a number :at are
  dropped silently."
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
  "Makes the directory of path when it doesn't exist yet, and sets it (new
  or already there) to rwx------. Never throws."
  [path]
  (try
    (let [dir (.getParentFile (java.io.File. ^String path))]
      (when dir
        (when-not (.exists dir) (.mkdirs dir))
        (java.nio.file.Files/setPosixFilePermissions
         (.toPath dir)
         (java.nio.file.attribute.PosixFilePermissions/fromString owner-only-dir))))
    (catch Exception _ nil)))

(defn save-record!
  "Writes record to path atomically; the file is rw------- from the moment
  it exists. Its replays/ directory, made or already there, is set to
  rwx------. Failing to
  set permissions never fails the save. Never throws: nil on success, else
  \"couldn't save <path>: <message>\"."
  [path record]
  (try
    (make-private-dir! path)
    (last-session/write-atomically! path (pr-str record) {:owner-only? true})
    (catch Exception e
      (str "couldn't save " path ": " (.getMessage e)))))

;; --- entries -------------------------------------------------------------

(def ^:private def-heads
  #{"def" "defn" "defn-" "defmacro" "defonce" "defmulti" "defmethod"
    "defprotocol" "defrecord" "deftype" "definterface" "defstruct"})

(def ^:private setup-heads #{"ns" "in-ns" "require" "use" "import" "refer"})

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

(defn- key-of
  "A form entry's :key from its head and its kids' texts: [head name] for a
  definition ([head name dispatch] for defmethod), name metadata stripped,
  :setup for a setup form and :other for the rest."
  [head texts]
  (cond
    (contains? def-heads head)
    (let [names (map strip-meta (rest texts))]
      (if (= "defmethod" head)
        [head (first names) (second names)]
        [head (first names)]))
    (contains? setup-heads head) :setup
    :else :other))

(defn entries
  "Entries for what one action ran. source is :run (a whole buffer: a saved
  one is a file entry, an untitled one is split into forms), :form,
  :selection or :repl (forms). An in-ns or ns form moves the ns the later
  forms are recorded in, starting from ns (default \"user\"). A top-level
  (in-ns 'x) does only that and is no entry of its own, since the script
  writes its in-ns lines from each form's :ns. An absolute
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
                target (when (contains? #{"ns" "in-ns"} head)
                         (in-ns-target head (second texts)))]
            (recur (rest forms) (or target cur-ns)
                   (cond
                     (and target (= "in-ns" head)) out
                     lf (conj out {:kind :file :path lf :at at :dialect dialect})
                     :else (conj out {:kind :form :text (kid-text code f) :ns cur-ns
                                      :source-file source-file :at at :dialect dialect
                                      :key (key-of head texts)}))))
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
  "record with :previous emptied. A successful Run of the replay tab applies
  carry-skipped first, so the other forms still skipped in the tab outlive
  this; Forget last session calls it alone and clears everything."
  [record]
  (assoc record :previous []))

(defn with-history
  "record with :history set to the last 500 of items."
  [record items]
  (assoc record :history (vec (take-last history-cap items))))

;; --- compaction ----------------------------------------------------------

(defn- parse-key
  "key-of for a form's text, for an entry recorded without :key."
  [text]
  (let [kids (:kids (first (commands/top-level-forms text)))
        texts (mapv #(kid-text text %) kids)
        head (when (and (seq kids) (not (:open (first kids)))) (first texts))]
    (key-of head texts)))

(defn- form-key
  "[group key] for a form entry: group is :defs or :others. A setup form
  keys on [:setup ns text], a definition on [ns head name ...], another
  form on [:other text]."
  [{:keys [text ns] :as e}]
  (let [k (:key e)
        k (if (or (= :setup k) (= :other k) (vector? k)) k (parse-key text))]
    (cond
      (= :setup k) [:defs [:setup ns text]]
      (= :other k) [:others [:other text]]
      :else [:defs (into [ns] k)])))

(defn- setup-key? [k] (= :setup (first (second k))))

(defn- keep-by
  "The entries whose key is the one kept for it, in order: the first seen
  when (first? key), else the last."
  [es key-fn first?]
  (let [idx (reduce-kv (fn [m i e]
                         (let [k (key-fn e)]
                           (if (and (contains? m k) (first? k)) m (assoc m k i))))
                       {} (vec es))]
    (vec (keep-indexed (fn [i e] (when (= i (get idx (key-fn e))) e)) es))))

(defn compact
  "Collapses es into {:files [path ...] :defs [entry ...] :others [entry ...]}.
  Files are deduped at their last position. Forms recorded from a file
  that is in :files are dropped. A def is kept once per [ns head name]
  (plus the dispatch value for defmethod) and other forms once per
  identical text, each at its last position. A setup form is kept once per
  ns and identical text at its first position, so it stays ahead of the
  definitions that need it."
  [es]
  (let [files (keep-by (filter #(= :file (:kind %)) es) :path (constantly false))
        loaded (set (map :path files))
        forms (->> es
                   (filter #(= :form (:kind %)))
                   (remove #(contains? loaded (:source-file %)))
                   (mapv #(assoc % ::k (form-key %))))
        kept (keep-by forms ::k setup-key?)
        strip #(dissoc % ::k)
        group (fn [g] (into [] (comp (filter #(= g (first (::k %)))) (map strip)) kept))]
    {:files (mapv :path files)
     :defs (group :defs)
     :others (group :others)}))

(defn carry-skipped
  "record with :current extended by the other forms of (compact (:previous
  record)), in that order, that are still skipped in run-text: those whose
  \"#_\" followed by their exact text occurs in it. Each keeps its own :ns,
  :at, :dialect and :key, and the 2,000 cap applies (add-entries). :previous
  is unchanged. A nil or blank run-text carries nothing. A form the user
  deleted, edited or reindented in the tab no longer matches and isn't
  carried; one whose #_ came off ran, so the Run records it itself."
  [record run-text]
  (if (str/blank? run-text)
    record
    (add-entries record
                 (filterv #(str/includes? run-text (str "#_" (:text %)))
                          (:others (compact (:previous record)))))))

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
  nil when es is empty. now is epoch ms; c, when given, is (compact es)."
  ([es now] (when (seq es) (summary es now (compact es))))
  ([es now c]
   (when (seq es)
     {:files (count (:files c))
      :defs (count (:defs c))
      :others (count (:others c))
      :dialect (newest-dialect es)
      :when (when-text now es)})))

(defn- plural [n one many] (str n " " (if (= 1 n) one many)))

(defn- ns-runs
  "Lines for forms: an (in-ns 'x) before the first and before each form whose
  ns differs from the one before, written with prefix in front. render
  makes a form's own line."
  [forms render prefix]
  (loop [fs forms prev ::none out []]
    (if-let [f (first fs)]
      (recur (rest fs) (:ns f)
             (cond-> out
               (not= prev (:ns f)) (conj (str prefix "(in-ns '" (:ns f) ")"))
               true (conj (render f))))
      out)))

(defn script
  "The replay tab's text for es: a header, then files, definitions and other
  forms (commented out with #_, their in-ns lines too), each under a
  heading and left out when empty. An unedited Run ends with the prompt in
  the definitions group's last namespace, as any Run that ends in another ns
  does. opts: :root project folder or nil, :now epoch ms, :dialect the
  current window's dialect name."
  [es {:keys [root now dialect]}]
  (let [c (compact es)
        {:keys [files defs others]} c
        s (summary es now c)
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
                                (ns-runs defs :text ""))
                       (section ";; Other forms, skipped: remove the #_ to run one"
                                (ns-runs others #(str "#_" (:text %)) "#_"))]))))
