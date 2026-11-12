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
            [jonquil.plugin-review :as review]
            [jonquil.tabs :as tabs]))

(def MAX-PROPOSED-FORMS
  ;; estimate: far more forms than one idea holds, few enough that the
  ;; review panel's text stays readable
  200)

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
               (let [r (when before (find-key keys e))]
                 (cond
                   (nil? before) (skip acc name "it is new since the last Run, so there is no earlier text to put back")
                   (nil? r) (skip acc name (str "This tab has no " (compare/form-shown e) " any more."))
                   (= before (subs text (:start r) (:end r))) (skip acc name "it already reads as it did at the last Run")
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
    (let [s (edn/read-string (slurp path))]
      (if (valid-set? s) {:set s} {:broken "It is not a valid change set."}))
    (catch :default e
      {:broken (str "It can't be read: " (or (ex-message e) (str e)))})))

(defn write-set!
  "Writes s as its file in dir, replacing a set of the same name. nil, or a
  sentence on failure."
  [dir s]
  (if-let [n (safe-name (:name s))]
    (try (.mkdirs (io/file dir))
         (spit (set-path dir n) (pr-str s))
         nil
         (catch :default e (str "The change set couldn't be saved: " (or (ex-message e) (str e)))))
    "The change set needs a name with some letters or digits in it."))

(defn delete-set!
  "Removes the set called name from dir. nil, or a sentence on failure."
  [dir name]
  (try (let [f (io/file (set-path dir name))]
         (when (.exists f) (io/delete-file f))
         nil)
       (catch :default e (str "The change set couldn't be deleted: " (or (ex-message e) (str e))))))

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

(defn- proposal-entry
  "The entry for one proposed {\"path\" \"text\"} of root, or {:error msg}.
  Its :before is the form as the file on disk has it now, so the review
  shows what the proposal would change."
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
      (let [abs (let [f (io/file path)] (.getPath (if (.isAbsolute f) f (io/file root path))))
            k (first forms)
            file (io/file abs)
            disk (when (.isFile file) (try (slurp file) (catch :default _ nil)))
            r (when disk (compare/form-range disk k))]
        {:path abs :sig (:sig k) :nth 0 :name (:name k) :text text
         :before (when r (subs disk (:start r) (:end r)))}))))

(defn proposal-entries
  "{:entries [...]} for an agent's list of {\"path\" \"text\"}, each one
  top-level form (a path relative to root is made absolute), or {:error
  msg} naming why none is saved."
  [root forms]
  (cond
    (not (sequential? forms)) {:error "forms must be a list of {path, text}"}
    (empty? forms) {:error "a change set needs at least one form"}
    (> (count forms) MAX-PROPOSED-FORMS) {:error (str "a change set holds at most " MAX-PROPOSED-FORMS " forms")}
    (> (reduce + (map #(count (str (when (map? %) (get % "text")))) forms)) MAX-PROPOSED-CHARS)
    {:error (str "the forms are over " MAX-PROPOSED-CHARS " characters in all")}
    :else
    (let [es (mapv #(proposal-entry root %) forms)]
      (if-let [bad (first (filter :error es))]
        bad
        {:entries es}))))

(defn summary-text
  "The panel's summary line for set s; draft? for one not kept yet."
  [s draft?]
  (let [n (count (:entries s))
        files (count (distinct (map :path (:entries s))))]
    (str "Change set \"" (:name s) "\"" (when draft? " (not kept yet)") ": "
         n (if (= 1 n) " form" " forms") " in " files (if (= 1 files) " file" " files")
         (when (:by s) (str ", proposed by " (:by s))) ".")))
