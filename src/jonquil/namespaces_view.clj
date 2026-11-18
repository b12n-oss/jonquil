(ns jonquil.namespaces-view
  "What the Namespaces panel shows, made from jonquil.describe-run's
  namespaces and vars snippet values: the rows, the crumb line and the
  footer text. No GTK and no runtime here."
  (:require [clojure.string :as str]))

(defn- matches?
  "True when q is blank or name holds it, in any case."
  [q name]
  (or (str/blank? q)
      (str/includes? (str/lower-case (str name)) (str/lower-case q))))

(defn- more-row [dropped]
  (when (pos? (or dropped 0))
    {:kind :more :key ::more :text (str dropped " more not shown")}))

(defn- ns-row [own? {:keys [name vars]}]
  {:kind :ns :key name :name name :count (or vars 0) :own? own?})

(defn- own-ns? [own? {:keys [file]}]
  (boolean (and file (own? file))))

(defn top-rows
  "The top level's rows for the namespaces snippet's value: the program's
  own namespaces (own? true of the namespace's file; none without a file),
  then a Libraries group row when any library namespace passes filter,
  then those library rows while the group is expanded? or filter isn't
  blank, then a row counting what the runtime left out. Each part sorted
  by name; filter matches the name in any case."
  [{:keys [namespaces dropped]} {:keys [own? expanded?] q :filter}]
  (let [shown (sort-by :name (filter #(matches? q (:name %)) namespaces))
        {mine true theirs false} (group-by #(own-ns? own? %) shown)
        open? (or expanded? (not (str/blank? q)))]
    (vec (concat (map #(ns-row true %) mine)
                 (when (seq theirs)
                   [{:kind :group :key ::libraries :label "Libraries" :count (count theirs) :expanded? (boolean open?)}])
                 (when open? (map #(ns-row false %) theirs))
                 (some-> (more-row dropped) vector)))))

(defn- var-meta
  "A var row's dim text: private and macro marks, the arglists, the doc."
  [{:keys [private? macro? arglists doc]}]
  (str/join " · " (remove str/blank? [(when private? "private") (when macro? "macro")
                                      (str/join " " arglists) doc])))

(defn var-rows
  "A namespace's rows for the vars snippet's value, sorted by name and
  narrowed by filter, then a row counting what the runtime left out."
  [{:keys [vars dropped]} {q :filter}]
  (vec (concat (for [{:keys [name file line arglists doc] :as v} (sort-by :name vars)
                     :when (matches? q name)]
                 {:kind :var :key name :name name :meta (var-meta v)
                  :private? (boolean (:private? v)) :macro? (boolean (:macro? v))
                  :bound? (boolean (:bound? v)) :file file :line line
                  :arglists (vec arglists) :doc doc})
               (some-> (more-row dropped) vector))))

(defn crumb
  "The line above a namespace's vars."
  [ns-name n]
  (str "All namespaces › " ns-name " · " n (if (= 1 n) " var" " vars")))

(defn var-footer
  "The footer for the selected var: its full name and arglists, then its
  doc (or \"No doc.\"), its place when known, and the keys."
  [ns-name {:keys [name arglists doc file line]}]
  (str ns-name "/" name (when (seq arglists) (str " " (str/join " " arglists)))
       "\n"
       (str/join " · " (remove nil? [(if (str/blank? doc) "No doc." doc)
                                     (when (and file line) (str file ":" line))
                                     "Return jumps, Shift+Return inspects"]))))

(defn top-footer
  "The footer at the top level: how many namespaces are the program's own
  and how many come from libraries."
  [{:keys [namespaces]} own?]
  (let [n (count (filter #(own-ns? own? %) namespaces))]
    (str n (if (= 1 n) " own namespace, " " own namespaces, ")
         (- (count namespaces) n) " from libraries")))

(defn reselect
  "The index in rows of the row whose :key is key, else of the first
  namespace or var row, else nil."
  [rows key]
  (let [idx (fn [pred] (first (keep-indexed (fn [i r] (when (pred r) i)) rows)))]
    (or (when (some? key) (idx #(= key (:key %))))
        (idx #(#{:ns :var} (:kind %))))))

(defn inspect-refusal
  "Why Shift+Return can't inspect the var row's value, or nil when it can."
  [ns-name {:keys [name macro? bound?]}]
  (cond
    macro? (str ns-name "/" name " is a macro, with no value to inspect.")
    (not bound?) (str ns-name "/" name " has no value yet.")))
