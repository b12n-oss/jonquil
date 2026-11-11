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

(defn- just-before?
  "Whether text right before offset i, past spaces, ends with prefix."
  [^String text i prefix]
  (str/ends-with? (str/trimr (subs text 0 i)) prefix))

(defn- opener?
  "Whether ch opens a list, vector or map."
  [ch]
  (contains? #{\( \[ \{} ch))

(defn- top-level-or-reader-conditional?
  "Whether a form opened with stack (the offsets of the openers around it)
  is at the top level, or directly inside a reader conditional."
  [text stack]
  (or (empty? stack)
      (and (= 1 (count stack))
           (let [p (peek stack)]
             (or (just-before? text p "#?") (just-before? text p "#?@"))))))

(defn- ns-opener?
  "Whether the ( at offset s, followed by token next, starts a live ns form."
  [text stack s next-token]
  (let [[nk ns' ne] next-token]
    (and (contains? code-kinds nk) (= "ns" (subs text ns' ne))
         (not (just-before? text s "#_"))
         (top-level-or-reader-conditional? text stack))))

(defn- bracket-step
  "[stack spans] after the bracket token at the head of ts (at offset s, the
  next token second in ts): an opener is pushed (and its ns form's span
  recorded), a closer pops."
  [{:keys [text matches]} [stack spans] ts]
  (let [[_ s] (first ts)
        next-token (second ts)]
    (if (opener? (.charAt ^String text (int s)))
      (let [close (when (and (= \( (.charAt ^String text (int s)))
                             (ns-opener? text stack s next-token))
                    (get matches s))]
        [(conj stack s) (cond-> spans close (conj [s (inc close)]))])
      [(if (seq stack) (pop stack) stack) spans])))

(defn- ns-spans
  "[[start end] ...] of text's ns forms: a (ns ...) at the top level, or
  directly inside a reader conditional (#?(:clj (ns ...))), but never one
  in a (comment ...) block or after #_."
  [text tokens]
  (let [{:keys [matches]} (syntax/compute-brackets text)
        ctx {:text text :matches matches}]
    (loop [ts (seq tokens) stack [] spans []]
      (if-let [[kind] (first ts)]
        (if (= :bracket kind)
          (let [[stack' spans'] (bracket-step ctx [stack spans] ts)]
            (recur (rest ts) stack' spans'))
          (recur (rest ts) stack spans))
        spans))))

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
            spans (ns-spans text tokens)
            defs (set (map (juxt :start :end) (syntax/find-definitions text name)))]
        (into []
              (keep-indexed
               (fn [i [kind s e]]
                 (when (and (contains? code-kinds kind) (names? (subs text s e) name))
                   {:start s :end e
                    :how (use-kind {:text text :spans spans :defs defs
                                    :prev (when (pos? i) (nth tokens (dec i)))}
                                   s e)})))
              tokens)))))

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
                   (mapcat (fn [^java.io.File f]
                             (let [n (.getName f) dir? (.isDirectory f)]
                               (cond
                                 (file-tree/ignored? ignore n dir?) nil
                                 dir? (walk f)
                                 (file-tree/clojure-file? n) [(.getCanonicalPath f)]
                                 :else nil)))
                           (or (.listFiles dir) [])))))]
    (vec (sort (distinct (walk (io/file root)))))))

(defn scan
  "{:entries [{:path :start :end :line :text :how}] :files n :matched n
  :unreadable n :hashes {path h}}: every use of name in root's
  project-files, files in path order. texts maps a path to an open tab's
  text, which replaces the file's own. :hashes holds the hash of the text
  each matched file was searched in, so a caller can tell whether its
  offsets still fit. A file that can't be read is skipped and counted."
  [{:keys [root name texts]}]
  (let [paths (project-files root)
        results (for [p paths]
                  (let [text (or (get texts p) (try (slurp p) (catch Throwable _ ::unreadable)))]
                    (if (= ::unreadable text)
                      {:unreadable 1}
                      (let [hs (hits text name)]
                        {:path p :hash (hash text)
                         :entries (map (fn [e h] (assoc e :path p :how (:how h)))
                                       (loc/entries text (map (juxt :start :end) hs))
                                       hs)}))))]
    {:entries (vec (mapcat :entries results))
     :files (count paths)
     :matched (count (filter (comp seq :entries) results))
     :unreadable (reduce + 0 (keep :unreadable results))
     :hashes (into {} (keep (fn [r] (when (seq (:entries r)) [(:path r) (:hash r)]))) results)}))
