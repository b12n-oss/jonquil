(ns jonquil.change-sets
  "LM3's change sets: a named bundle of top-level forms, kept per project
  as EDN beside the settings file, to review together, send to the running
  program in one go or put back. An entry holds the file's path, the form's
  signature and place (as jonquil.compare keys a form for one-form compare),
  the form's text, and the text it had at the last Run that ended well, nil
  for a form new since then. A set read from disk is checked first, so a
  malformed file is listed as broken and never applied. No GTK here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.changed :as changed]
            [jonquil.commands :as commands]
            [jonquil.compare :as compare]
            [jonquil.last-session :as last-session]
            [jonquil.plugin-review :as review]
            [jonquil.source :as source]
            [jonquil.tabs :as tabs]))

(def MAX-PROPOSED-FORMS
  ;; estimate: far more forms than one idea holds, few enough that the
  ;; review panel's text stays readable
  200)

(def MAX-SET-BYTES
  ;; a choice: a set holds forms, so a file this big is not one of ours, and
  ;; the palette reads every file of the folder each time it opens
  1048576)

(def MAX-PROPOSED-CHARS
  ;; estimate, as plugin-mcp's MAX-SOURCE-CHARS: bigger than any form list
  ;; seen so far, small enough to keep a pasted megabyte out of the panel
  262144)

;; --- gathering -------------------------------------------------------------

(defn- find-key
  "The entry of keys (compare/form-keys) with k's signature and place."
  [keys {:keys [sig] n :nth}]
  (first (filter #(and (= sig (:sig %)) (= n (:nth %))) keys)))

(defn from-changed
  "The entries for the forms of tab-text that changed since they last ran,
  as the gutter marks them, in buffer order: ran is the set of keys the
  tab's forms ran as (jonquil.changed), by default the keys of
  last-run-text's forms. Each keeps path, the form's key, its text now and
  its text in last-run-text (nil when last-run-text has no such form, or
  is nil). [] when the tab never ran."
  ([tab-text last-run-text path]
   (from-changed tab-text last-run-text path
                 (when last-run-text (set (map :key (changed/forms last-run-text))))))
  ([tab-text last-run-text path ran]
   (let [keys (compare/form-keys tab-text)
         by-start (into {} (map (juxt :start identity)) keys)
         before-keys (when last-run-text (compare/form-keys last-run-text))]
     (mapv (fn [{:keys [start end]}]
             (let [k (get by-start start)
                   b (find-key before-keys k)]
               {:path path :sig (:sig k) :nth (:nth k) :name (:name k)
                :text (subs tab-text start end)
                :before (when b (subs last-run-text (:start b) (:end b)))}))
           (changed/changed tab-text ran)))))

;; --- reviewing --------------------------------------------------------------

(defn entry-diff
  "The line diff from an entry's last-run text to its text now, as the
  plugin review shows one."
  [{:keys [before text]}]
  (review/diff-text before text))

(defn set-text
  "The review panel's text for set s: one section per form, a line naming
  its file and form (\"(new)\" for a form with no last-run text), then its
  diff, a blank line between."
  [s]
  (str/join "\n\n"
            (map (fn [e]
                   (str (:path e) "  " (:name e) (when (nil? (:before e)) " (new)") "\n" (entry-diff e)))
                 (:entries s))))

;; --- reverting ---------------------------------------------------------------

(defn revert-edits
  "{:edits :skipped} for putting entries' last-run text back in text, one
  tab's text. :edits is [[start end replacement] ...], ascending and not
  overlapping, so they apply as one undo step. :skipped is [{:name :reason}]
  for a form that no longer matches its signature, is new since the last
  Run, already reads as it did, or is listed twice."
  [text entries]
  (let [keys (compare/form-keys text)
        skip (fn [acc name reason] (update acc :skipped conj {:name name :reason reason}))
        step (fn [acc {:keys [name before] :as e}]
               (let [r (when before (find-key keys e))
                     current (when r (subs text (:start r) (:end r)))]
                 (cond
                   (nil? before) (skip acc name "it is new since the last Run, so there is no earlier text to put back")
                   (nil? r) (skip acc name (str "This tab has no " (compare/form-shown e) " any more."))
                   (= before current) (skip acc name "it already reads as it did at the last Run")
                   ;; the place alone could be another form after an edit
                   (not= (:text e) current) (skip acc name "it changed since the set was made")
                   (contains? (:seen acc) (:start r)) (skip acc name "it is listed twice")
                   :else (-> acc
                             (update :seen conj (:start r))
                             (update :edits conj [(:start r) (:end r) before])))))
        {:keys [edits skipped]} (reduce step {:edits [] :skipped [] :seen #{}} entries)]
    {:edits (vec (sort-by first edits)) :skipped skipped}))

;; --- the set and its file ------------------------------------------------------

(defn make-set
  "A set named name over entries. opts: :at (ms, default now) and :by, who
  proposed it (nil for one a person made)."
  [name entries {:keys [at by]}]
  {:version 1 :name name :at (or at (System/currentTimeMillis)) :by by :entries (vec entries)})

(defn- entry? [e]
  (and (map? e)
       (string? (:path e)) (not (str/blank? (:path e)))
       (vector? (:sig e)) (seq (:sig e)) (every? string? (:sig e))
       (integer? (:nth e)) (not (neg? (:nth e)))
       (string? (:name e))
       (string? (:text e))
       (or (nil? (:before e)) (string? (:before e)))))

(defn valid-set?
  "Whether s has the shape of make-set's, with at least one entry."
  [s]
  (boolean
   (and (map? s)
        (= 1 (:version s))
        (string? (:name s)) (not (str/blank? (:name s)))
        (number? (:at s))
        (or (nil? (:by s)) (string? (:by s)))
        (vector? (:entries s)) (seq (:entries s)) (every? entry? (:entries s)))))

(defn safe-name
  "A file name for a set called name: letters, digits, dot, underscore and
  dash kept, any other run turned into a dash, no leading dot or dash, at
  most 60 characters; nil when nothing is left."
  [name]
  (let [n (-> (str name)
              (str/replace #"[^A-Za-z0-9._-]+" "-")
              (str/replace #"^[.-]+" "")
              (str/replace #"-+$" ""))
        n (if (> (count n) 60) (subs n 0 60) n)]
    (when-not (str/blank? n) n)))

(defn sets-dir
  "The project's change sets folder: change-sets/<folder>-<hash> beside the
  settings file, or change-sets/no-folder for a nil root. The leaf and hash
  are made as jonquil.world/world-dir makes them."
  [settings-path root]
  (let [dir (str (.getParent (java.io.File. ^String settings-path)) "/change-sets")]
    (if root
      (let [leaf (str/replace (.getName (java.io.File. ^String root)) #"[^A-Za-z0-9._-]" "-")]
        (str dir "/" leaf "-" (Integer/toHexString (.hashCode ^String root))))
      (str dir "/no-folder"))))

(defn set-path [dir name] (str dir "/" name ".edn"))

(defn read-set
  "{:set s} for the file at path, or {:broken why} when it can't be read,
  isn't EDN or isn't a valid set."
  [path]
  (try
    (if (> (.length (io/file path)) MAX-SET-BYTES)
      {:broken "It is over 1 MB, so it is not read."}
      (let [s (edn/read-string (slurp path))]
        (if (valid-set? s) {:set s} {:broken "It is not a valid change set."})))
    (catch :default e
      {:broken (str "It can't be read: " (or (ex-message e) (str e)))})))

(defn- already-there [n]
  (str "a change set called " n " exists already; pick another name"))

(defn write-set!
  "Writes s as its file in dir: a temp file beside it, then one move, so a
  reader never sees half a file. Replaces a set of the same name, unless
  opts has :create-new? true, which refuses an existing name and replaces
  nothing. nil, or a sentence on failure."
  ([dir s] (write-set! dir s nil))
  ([dir s {:keys [create-new?]}]
   (if-let [n (safe-name (:name s))]
     (let [path (set-path dir n)
           tmp (str path ".tmp-" (System/nanoTime))]
       (try
         (.mkdirs (io/file dir))
         (if-not create-new?
           (last-session/write-atomically! path (pr-str s))
           (do (spit tmp (pr-str s))
               (try (java.nio.file.Files/move
                     (.toPath (java.io.File. ^String tmp)) (.toPath (java.io.File. ^String path))
                     (make-array java.nio.file.CopyOption 0))
                    nil
                    (catch :default e
                      (if (.exists (io/file path))
                        (already-there n)
                        (throw e)))
                    (finally (io/delete-file tmp true)))))
         (catch :default e (str "The change set couldn't be saved: " (or (ex-message e) (str e))))))
     "The change set needs a name with some letters or digits in it.")))

(defn delete-set!
  "Removes the file of the set listed as name from dir. name must be what
  safe-name would make of it, so it can't leave the folder. nil, or a
  sentence on failure (including a file that is not there, or stays)."
  [dir name]
  (if-not (and (string? name) (= name (safe-name name)))
    "That isn't the name of a kept change set."
    (try (let [f (io/file (set-path dir name))]
           (cond
             (not (.exists f)) (str "There is no change set called " name ".")
             (do (io/delete-file f true) (.exists f)) (str "The change set " name " couldn't be deleted.")
             :else nil))
         (catch :default e (str "The change set couldn't be deleted: " (or (ex-message e) (str e)))))))

(defn list-sets
  "dir's sets by name: {:name :path} plus :set, or :broken and why. [] for
  a missing folder."
  [dir]
  (->> (or (.listFiles (io/file dir)) [])
       (keep (fn [^java.io.File f]
               (when-let [[_ n] (re-matches #"(.+)\.edn" (.getName f))]
                 (merge {:name n :path (.getPath f)} (read-set (.getPath f))))))
       (sort-by :name)
       vec))

;; --- an agent's proposal ----------------------------------------------------------

(defn clean-client
  "A client's own name made safe to show and save: control characters
  dropped, trimmed, at most 60 characters; nil when nothing is left or it
  isn't a string."
  [name]
  (when (string? name)
    (let [t (str/trim (apply str (remove (fn [c] (let [n (int c)] (or (< n 32) (<= 127 n 159)))) name)))
          t (if (> (count t) 60) (subs t 0 60) t)]
      (when-not (str/blank? t) t))))

(def ^:private clojure-extensions #{"clj" "cljc" "cljs" "cljd" "bb"})

(defn- extension [^String path]
  (let [n (.getName (java.io.File. path))
        i (str/last-index-of n ".")]
    (when (and i (pos? i)) (subs n (inc i)))))

(defn- proposal-file
  "{:path canonical :disk text} for a proposed form's path under root, or
  {:error msg}: the path must stay under root once symlinks and .. are
  resolved, and name a readable Clojure file that exists."
  [root path]
  (let [f (let [f (io/file path)] (if (.isAbsolute f) f (io/file root path)))
        canon (try (.getCanonicalPath f) (catch :default _ nil))]
    (cond
      (nil? canon) {:error (str path " isn't a usable path")}
      (not (source/under? canon root)) {:error (str path " is outside the project")}
      (not (.isFile (io/file canon))) {:error (str path " isn't a file in the project")}
      (not (contains? clojure-extensions (extension canon))) {:error (str path " isn't a Clojure file (clj, cljc, cljs, cljd or bb)")}
      (not (.canRead (io/file canon))) {:error (str path " can't be read")}
      (> (.length (io/file canon)) MAX-SET-BYTES) {:error (str path " is too big to propose a form for")}
      :else (try {:path canon :disk (slurp canon)}
                 (catch :default _ {:error (str path " can't be read")})))))

(defn- proposal-entry
  "The unplaced entry for one proposed {\"path\" \"text\"} of root, or
  {:error msg}: :nth is 0 and :before nil until proposal-entries places it."
  [root p]
  (let [path (when (map? p) (get p "path"))
        text (when (map? p) (get p "text"))
        problem (when (and (string? text) (not (str/blank? text))) (commands/pre-run-problem text))
        forms (when (and (string? text) (not (str/blank? text)) (not problem)) (compare/form-keys text))]
    (cond
      (not (and (string? path) (not (str/blank? path)))) {:error "each form needs a path"}
      (not (and (string? text) (not (str/blank? text)))) {:error (str "the form for " path " needs its text")}
      (tabs/nul-line text) {:error (str "the form for " path " holds a NUL byte")}
      problem {:error (str "the form for " path ": " problem)}
      (not= 1 (count forms)) {:error (str "each entry is one top-level form, and the one for " path " has " (count forms))}
      :else
      (let [file (proposal-file root path)]
        (if (:error file)
          file
          (let [k (first forms)]
            {:path (:path file) :disk (:disk file) :sig (:sig k) :nth 0 :name (:name k) :text text :before nil}))))))

(defn- place
  "entries (proposal-entry's) with :nth and :before set: the n-th proposed
  form of a signature in a file replaces the file's n-th form of it, and
  :before is that form's text, nil when the file has fewer."
  [entries]
  (let [disks (into {} (map (juxt :path (comp compare/form-keys :disk))) entries)]
    (:out (reduce (fn [{:keys [seen out]} {:keys [path sig disk] :as e}]
                    (let [n (get seen [path sig] 0)
                          k (find-key (get disks path) {:sig sig :nth n})]
                      {:seen (assoc seen [path sig] (inc n))
                       :out (conj out (-> e (dissoc :disk) (assoc :nth n :name (compare/form-name sig n))
                                          (assoc :before (when k (subs disk (:start k) (:end k))))))}))
                  {:seen {} :out []}
                  entries))))

(defn proposal-entries
  "{:entries [...]} for an agent's list of {\"path\" \"text\"}, each one
  top-level form of a Clojure file under root (a relative path is read
  against root), paths made canonical so one file groups as one, or {:error
  msg} naming why none is saved."
  [root forms]
  (cond
    (not (sequential? forms)) {:error "forms must be a list of {path, text}"}
    (empty? forms) {:error "a change set needs at least one form"}
    (> (count forms) MAX-PROPOSED-FORMS) {:error (str "a change set holds at most " MAX-PROPOSED-FORMS " forms")}
    (> (reduce + (map #(count (str (when (map? %) (get % "text")))) forms)) MAX-PROPOSED-CHARS)
    {:error (str "the forms are over " MAX-PROPOSED-CHARS " characters in all")}
    :else
    (let [canon-root (try (.getCanonicalPath (io/file root)) (catch :default _ root))
          es (mapv #(proposal-entry canon-root %) forms)]
      (if-let [bad (first (filter :error es))]
        bad
        {:entries (place es)}))))

(defn summary-text
  "The panel's summary line for set s; draft? for one not kept yet. A
  client's name is shown as what it called itself."
  [s draft?]
  (let [n (count (:entries s))
        files (count (distinct (map :path (:entries s))))
        by (clean-client (:by s))]
    (str "Change set \"" (:name s) "\"" (when draft? " (not kept yet)") ": "
         n (if (= 1 n) " form" " forms") " in " files (if (= 1 files) " file" " files")
         (when by (str ", proposed by a client calling itself \"" by "\"")) ".")))
