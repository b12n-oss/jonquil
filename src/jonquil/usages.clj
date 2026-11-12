(ns jonquil.usages
  "Find Usages in Project, pure: every use of a name in the project's
  Clojure files, how each one uses it, and whether it names the var the
  search started from (jonquil.resolve reads each file's ns form and
  bindings for that). jonquil.gtk.parts.locations runs scan on a future and
  turns its result into a jonquil.locations list."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.file-tree :as file-tree]
            [jonquil.locations :as loc]
            [jonquil.resolve :as resolve]
            [jonquil.syntax :as syntax]))

(def ^:private code-kinds
  ;; the tokenizer's kinds for code, as syntax/find-symbol-occurrences reads them
  #{:symbol :special-form :builtin :constant})

(defn search-name
  "The name a search looks for: str/join and join both look for join. A
  bare / (division) is its own name."
  [s]
  (let [i (str/last-index-of s "/")]
    (if (and i (pos? i) (< (inc i) (count s))) (subs s (inc i)) s)))

(defn- names?
  "Whether token text t is name, bare or qualified (q/name)."
  [^String t name]
  (or (= t name)
      (and (> (count t) (inc (count name)))
           (str/ends-with? t (str "/" name))
           (= (search-name t) name))))

(defn- use-kind
  "How the use spanning [s e) is made: :ns inside an ns form's span,
  :definition as a def's name, :call right after an open paren, else
  :value. prev is the token before it, or nil."
  [{:keys [^String text spans defs prev]} s e]
  (let [[pk ps pe] prev]
    (cond
      (some (fn [[a b]] (and (<= a s) (< e b))) spans) :ns
      (contains? defs [s e]) :definition
      (and (= :bracket pk) (= "(" (subs text ps pe))) :call
      :else :value)))

(defn- hit-at
  "The {:start :end :how} for token i of ctx's :tokens when it is a use of
  :name as code, else nil. ctx also holds :text, :spans and :defs."
  [{:keys [^String text tokens spans defs name]} i [kind s e]]
  (when (and (contains? code-kinds kind) (names? (subs text s e) name))
    {:start s :end e
     :how (use-kind {:text text :spans spans :defs defs
                     :prev (when (pos? i) (nth tokens (dec i)))}
                    s e)}))

(defn hits
  "Every use of name in text as code, in order: [{:start :end :how}], how
  one of :ns (inside an ns form, ns-spans), :definition (the name of a def form),
  :call (the first thing in a list, so (join ...) and #(join ...)) or
  :value (anywhere else). Never in a string, comment, regex or keyword. A
  qualified name searches by its name part."
  [^String text search]
  (let [name (search-name search)]
    (if (or (str/blank? name) (not (str/includes? text name)))
      []
      (let [tokens (vec (remove #(= :comment (first %)) (syntax/tokenize text)))
            spans (resolve/ns-spans text tokens)
            defs (set (map (juxt :start :end) (syntax/find-definitions text name)))
            ctx {:text text :tokens tokens :spans spans :defs defs :name name}]
        (into [] (keep-indexed #(hit-at ctx %1 %2)) tokens)))))

(defn- dir-paths
  "The Clojure file paths under one entry f of a folder, as a seq: nothing
  for what ignore hides, f's contents (walk) for a folder, f itself for a
  Clojure file."
  [ignore walk ^java.io.File f]
  (let [n (.getName f) dir? (.isDirectory f)]
    (cond
      (file-tree/ignored? ignore n dir?) nil
      dir? (walk f)
      (file-tree/clojure-file? n) [(.getCanonicalPath f)]
      :else nil)))

(defn project-files
  "Every Clojure file under root, sorted by path, skipping what the file
  tree hides: dot folders and files, target and node_modules, and what
  root's .gitignore names."
  [root]
  (let [ignore (file-tree/parse-gitignore
                (let [f (io/file root ".gitignore")] (when (.isFile f) (slurp f))))
        ;; canonical folders already walked: a link back up the tree, or
        ;; two links to one folder, is walked once (file-tree/rows guards
        ;; its rows the same way)
        seen (volatile! #{})
        walk (fn walk [^java.io.File dir]
               (let [c (.getCanonicalPath dir)]
                 (when-not (contains? @seen c)
                   (vswap! seen conj c)
                   (mapcat #(dir-paths ignore walk %) (or (.listFiles dir) [])))))]
    (vec (sort (distinct (walk (io/file root)))))))

(defn- match
  "How hit h in a file (ctx, a resolve/file-context) relates to target, a
  resolution of the symbol the search started from: :this when it names
  the same var, :unresolved when the file can't tell (or resolving threw),
  else :other (another var, or a local). Every hit is :this when target
  isn't a var."
  [ctx target text h]
  (if-not (:var target)
    :this
    (let [r (try (resolve/resolve-at ctx (:start h) (subs text (:start h) (:end h)))
                 ;; one odd file shouldn't fail the whole search
                 (catch :default _ {:unresolved true}))]
      (cond (= (:var r) (:var target)) :this
            (:unresolved r) :unresolved
            :else :other))))

(defn- file-entries
  "The scan's result for one readable file at path p with text."
  [{:keys [name target features]} p text]
  (let [hs (hits text name)
        ctx (when (and (seq hs) (:var target))
              (resolve/file-context text (resolve/features-for p features)))]
    {:path p :hash (hash text)
     :entries (map (fn [e h] (assoc e :path p :how (:how h) :match (match ctx target text h)))
                   (loc/entries text (map (juxt :start :end) hs))
                   hs)}))

(defn scan
  "{:entries [{:path :start :end :line :text :how :match}] :files n :matched n
  :unreadable n :hashes {path h} :target target}: every use of name in
  root's project-files, files in path order. texts maps a path to an open
  tab's text, which replaces the file's own. target is the resolution of
  the symbol the search started from (resolve/resolve-at), or nil;
  features the window dialect's reader features, for .cljc files. Each
  entry's :match is :this, :other or :unresolved (see match). :hashes holds
  the hash of the text each matched file was searched in, so a caller can
  tell whether its offsets still fit. A file that can't be read is skipped
  and counted."
  [{:keys [root texts target] :as opts}]
  (let [paths (project-files root)
        results (for [p paths]
                  (let [text (or (get texts p) (try (slurp p) (catch Throwable _ ::unreadable)))]
                    (if (= ::unreadable text)
                      {:unreadable 1}
                      (file-entries opts p text))))]
    {:entries (vec (mapcat :entries results))
     :files (count paths)
     :matched (count (filter (comp seq :entries) results))
     :unreadable (reduce + 0 (keep :unreadable results))
     :hashes (into {} (keep (fn [r] (when (seq (:entries r)) [(:path r) (:hash r)]))) results)
     :target target}))
