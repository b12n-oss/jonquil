(ns jonquil.usages
  "Find Usages in Project, pure: every use of a name in the project's
  Clojure files, and how each one uses it. By name, so a same-named symbol
  in another namespace counts too; telling them apart needs a resolved
  index, a later step (ROADMAP LM2). jonquil.gtk.window runs scan on a
  future and turns its result into a jonquil.locations list."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.file-tree :as file-tree]
            [jonquil.locations :as loc]
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

(defn- ns-span
  "[start end] of text's top-level (ns ...) form, or nil."
  [text tokens]
  (let [{:keys [matches]} (syntax/compute-brackets text)]
    (some (fn [[[t1 s1 e1] [t2 s2 e2]]]
            (when (and (= :bracket t1) (= "(" (subs text s1 e1))
                       (contains? code-kinds t2) (= "ns" (subs text s2 e2)))
              (when-let [close (get matches s1)] [s1 (inc close)])))
          (partition 2 1 tokens))))

(defn hits
  "Every use of name in text as code, in order: [{:start :end :how}], how
  one of :ns (inside the ns form), :definition (the name of a def form),
  :call (the first thing in a list, so (join ...) and #(join ...)) or
  :value (anywhere else). Never in a string, comment, regex or keyword. A
  qualified name searches by its name part."
  [^String text search]
  (let [name (search-name search)]
    (if (or (str/blank? name) (not (str/includes? text name)))
      []
      (let [tokens (vec (remove #(= :comment (first %)) (syntax/tokenize text)))
            [ns-s ns-e] (ns-span text tokens)
            defs (set (map (juxt :start :end) (syntax/find-definitions text name)))]
        (into []
              (keep-indexed
               (fn [i [kind s e]]
                 (when (and (contains? code-kinds kind) (names? (subs text s e) name))
                   (let [[pk ps pe] (when (pos? i) (nth tokens (dec i)))]
                     {:start s :end e
                      :how (cond
                             (and ns-s (<= ns-s s) (< e ns-e)) :ns
                             (contains? defs [s e]) :definition
                             (and (= :bracket pk) (= "(" (subs text ps pe))) :call
                             :else :value)}))))
              tokens)))))

(defn project-files
  "Every Clojure file under root, sorted by path, skipping what the file
  tree hides: dot folders and files, target and node_modules, and what
  root's .gitignore names."
  [root]
  (let [ignore (file-tree/parse-gitignore
                (let [f (io/file root ".gitignore")] (when (.isFile f) (slurp f))))
        walk (fn walk [^java.io.File dir]
               (mapcat (fn [^java.io.File f]
                         (let [n (.getName f) dir? (.isDirectory f)]
                           (cond
                             (file-tree/ignored? ignore n dir?) nil
                             dir? (walk f)
                             (file-tree/clojure-file? n) [(.getCanonicalPath f)]
                             :else nil)))
                       (or (.listFiles dir) [])))]
    (vec (sort (walk (io/file root))))))

(defn scan
  "{:entries [{:path :start :end :line :text :how}] :files n :matched n
  :unreadable n}: every use of name in root's project-files, files in path
  order. texts maps a path to an open tab's text, which replaces the file's
  own. A file that can't be read is skipped and counted."
  [{:keys [root name texts]}]
  (let [paths (project-files root)
        results (for [p paths]
                  (let [text (or (get texts p) (try (slurp p) (catch Throwable _ ::unreadable)))]
                    (if (= ::unreadable text)
                      {:unreadable 1}
                      (let [hs (hits text name)]
                        {:entries (map (fn [e h] (assoc e :path p :how (:how h)))
                                       (loc/entries text (map (juxt :start :end) hs))
                                       hs)}))))]
    {:entries (vec (mapcat :entries results))
     :files (count paths)
     :matched (count (filter (comp seq :entries) results))
     :unreadable (reduce + 0 (keep :unreadable results))}))
