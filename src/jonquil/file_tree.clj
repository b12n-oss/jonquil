(ns jonquil.file-tree
  "The file tree's model as plain data (file-tree spec): which folder is the
  root, which folders are expanded, and the ignore rules, plus the
  functions that list a folder, flatten the expanded tree into the rows the
  pane shows, and decide the root from the launch argument. Reads the disk
  but never touches GTK.

  A model is {:root path-or-nil :expanded #{folder-path} :ignore rules}.
  Every path in it is canonical (java.io.File/getCanonicalPath), so a
  symlinked folder's children carry the target's paths (file-tree spike
  §5) and match what tabs' find-open compares."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.dialect :as dialect]))

(def ^:private always-hidden-dirs #{"target" "node_modules"})

(def ^:private clojure-exts #{"clj" "cljc" "cljs" "cljd" "edn" "bb"})

(defn- canonical [p] (.getCanonicalPath (io/file p)))

(defn- glob->regex
  "A .gitignore name pattern as a regex over the whole name: * is any run
  of characters, everything else is literal."
  [pat]
  (re-pattern (str/join ".*" (map (fn [part] (java.util.regex.Pattern/quote part))
                                  (str/split pat #"\*" -1)))))

(defn parse-gitignore
  "The simple rules from a .gitignore's text (spec decision 5): plain names,
  * globs within one name, and dir/ folder-only rules. Blank lines, #
  comments, and any line with !, ** or a / other than a trailing one are
  skipped, so a rule the simple matcher can't honour never half-applies.
  Returns [{:re regex :dir-only? bool}]."
  [text]
  (into []
        (keep (fn [line]
                (let [l (str/trim line)
                      dir-only? (str/ends-with? l "/")
                      pat (if dir-only? (subs l 0 (dec (count l))) l)]
                  (when-not (or (str/blank? pat) (str/starts-with? l "#")
                                (str/includes? l "!") (str/includes? l "**")
                                (str/includes? pat "/"))
                    {:re (glob->regex pat) :dir-only? dir-only?}))))
        (str/split-lines (or text ""))))

(defn ignored?
  "Whether an entry named name (a folder when dir?) is hidden: a dotfile, a
  target or node_modules folder, or a match of one of rules."
  [rules name dir?]
  (boolean
   (or (str/starts-with? name ".")
       (and dir? (contains? always-hidden-dirs name))
       (some (fn [{:keys [re dir-only?]}]
               (and (or dir? (not dir-only?)) (re-matches re name)))
             rules))))

(defn clojure-file?
  "A Clojure-family file name: shown normally; anything else is dimmed."
  [name]
  (let [i (str/last-index-of name ".")]
    (boolean (and i (contains? clojure-exts (subs name (inc i)))))))

(defn load-model
  "A fresh model rooted at root (nil for no root), with the root's own
  .gitignore read when there is one."
  [root]
  (let [root (when root (canonical root))
        gi (when root (io/file root ".gitignore"))]
    {:root root
     :expanded #{}
     :ignore (if (and gi (.isFile gi))
               (parse-gitignore (try (slurp gi) (catch Exception _ "")))
               [])}))

(defn children
  "dir's entries, ignore rules applied, folders first then files, each by
  lower-cased name: [{:path :name :dir?}]. {:error msg} when dir can't be
  listed."
  [{:keys [ignore]} dir]
  (let [f (io/file dir)
        names (try (.list f) (catch Exception _ nil))]
    (if (nil? names)
      {:error (str "can't read " dir)}
      (->> names
           (keep (fn [n]
                   (let [c (io/file f n)
                         dir? (.isDirectory c)]
                     (when-not (or (ignored? ignore n dir?) (not (.exists c)))
                       {:path (canonical c) :name n :dir? dir?}))))
           (sort-by (fn [{:keys [name dir?]}] [(if dir? 0 1) (str/lower-case name)]))
           vec))))

(defn rows
  "The visible rows, depth first: [{:path :name :depth :dir? :expanded? :dim?
  :error?}], depth 0 for the root's children. An expanded folder that can't
  be listed shows one \"(can't read)\" row under it; one that no longer
  exists is simply not listed by its parent any more."
  [{:keys [root expanded] :as model}]
  (if-not root
    []
        ;; ancestors: the folders above this point. A symlink to one of them
    ;; (or to the folder itself) has the same canonical path, so it's shown
    ;; collapsed instead of recursed into forever (final review finding 2).
    (letfn [(walk [dir depth ancestors]
              (let [cs (children model dir)]
                (if (map? cs)
                  [{:path (str dir "/") :name "(can't read)" :depth depth :dir? false :dim? true :error? true}]
                  (mapcat (fn [{:keys [path name dir?]}]
                            (let [open? (and dir? (contains? expanded path) (not (contains? ancestors path)))
                                  row {:path path :name name :depth depth :dir? dir? :expanded? open?
                                       :dim? (and (not dir?) (not (clojure-file? name))) :error? false}]
                              (if open? (cons row (walk path (inc depth) (conj ancestors path))) [row])))
                          cs))))]
      (let [cs (children model root)]
        (if (map? cs) [] (vec (walk root 0 #{root})))))))

(defn toggle
  "model with folder dir expanded, or collapsed when it already was."
  [model dir]
  (update model :expanded (fn [e] (if (contains? e dir) (disj e dir) (conj e dir)))))

(defn prune
  "model without expanded folders that no longer exist."
  [model]
  (update model :expanded (fn [e] (set (filter (fn [d] (.isDirectory (io/file d))) e)))))

(defn under-root?
  "Whether path lies under model's root (compared canonically)."
  [{:keys [root]} path]
  (boolean (and root path
                (str/starts-with? (canonical path) (str root "/")))))

(defn expand-to
  "model with every folder strictly between the root and path expanded, so
  path's row is visible (used after a save). A path outside the root
  leaves model as it is."
  [{:keys [root] :as model} path]
  (if-not (under-root? model path)
    model
    (let [parts (str/split (subs (canonical path) (inc (count root))) #"/")
          dirs (reductions (fn [acc p] (str acc "/" p)) (str root "/" (first parts)) (rest (butlast parts)))]
      (update model :expanded into (when (> (count parts) 1) dirs)))))

(defn project-selection
  "dialect/select for directory dir as a project, with default the settings
  file's :dialect: dir's own .jonquil.edn or marker decides, as it would for
  a file inside it with no extension to argue. find-project starts at a
  path's parent, so it's given a child of dir; select gets no path, so a
  single marker decides (final review finding 1: a pubspec.yaml directory
  must select ClojureDart)."
  [dir default]
  (dialect/select dialect/profiles
                  (dialect/find-project dialect/profiles (str (io/file dir "_")))
                  nil
                  default))

(defn root-for
  "The tree's starting root (spec decision 2): a directory argument; a
  file's project directory, else its folder; else cwd, unless cwd is nil,
  blank or / (a Finder or Dock launch), which gives nil."
  [{:keys [dir file cwd]}]
  (cond
    dir (canonical dir)
    file (or (some-> (dialect/find-project dialect/profiles file) :dir canonical)
             (canonical (.getParent (io/file (canonical file)))))
    (or (str/blank? cwd) (= "/" cwd)) nil
    :else cwd))
