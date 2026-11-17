(ns jonquil.describe-run
  "Describing what a project's runtime has loaded, for the MCP tools
  jonquil_namespaces, jonquil_vars and jonquil_apropos.

  The snippets are code for the project's runtime whose value is a string
  holding one EDN map (jonquil.test-run/read-value reads it back). The
  answer makers turn that map into a tool's text and structured content.
  No evaluator here, and nothing reads the disk."
  (:require [clojure.walk :as walk]
            [jonquil.test-run :as test-run]))

(def runtime-cap
  "How many rows a snippet sends at most; the rest are only counted."
  5000)

(defn supported?
  "True for the dialect profile ids whose runtime the snippets run on."
  [profile-id]
  (test-run/supported? profile-id))

(def ^:private core-names
  "The clojure.core names the snippets' forms use."
  '#{fn let when if-let when-let str string? number? sequential? vec map keep first
     meta vals ns-interns all-ns ns-name find-ns sort-by count take drop boolean
     not= apply take-while = binding pr-str *print-length* *print-level* require
     resolve deref for and or max - assoc})

;; The forms below are data: the snippet fns fill the capital names in and
;; print the result. Every var's row is built from a meta map read inside
;; its own try, and each field is checked for its type, so a var with odd
;; metadata shows with what it has instead of failing the whole answer.
(def ^:private row-form
  (test-run/qualify
   core-names
   '(fn [s v]
      (let [m (try (meta v) (catch Throwable _ nil))
            a (:arglists m)
            d (:doc m)
            f (:file m)
            l (:line m)]
        {:name (str s)
         :arglists (if (sequential? a) (vec (map pr-str a)) [])
         :doc (when (string? d) (apply str (take-while (fn [c] (not= c \newline)) d)))
         :file (when (and (string? f) (not= f "NO_SOURCE_PATH")) f)
         :line (when (number? l) l)
         :private? (boolean (:private m))
         :macro? (boolean (:macro m))}))))

(defn- capped
  "rows (a sorted seq in the runtime) as {:rows first-cap :dropped rest}."
  [k]
  (test-run/qualify core-names
                    (list 'let ['rs 'ROWS]
                          {k '(vec (take CAP rs)) :dropped '(max 0 (- (count rs) CAP))})))

(def ^:private namespaces-form
  (test-run/qualify
   core-names
   (list 'let ['row 'ROW
               'file-of '(fn [n] (first (keep (fn [[s v]] (:file (row s v))) (ns-interns n))))]
         (list 'binding '[*print-length* nil *print-level* nil]
               (list 'pr-str
                     (walk/postwalk-replace
                      {'ROWS '(map (fn [n] {:name (str (ns-name n))
                                            :file (file-of n)
                                            :vars (count (ns-interns n))})
                                   (sort-by (fn [n] (str (ns-name n))) (all-ns)))}
                      (capped :namespaces)))))))

(def ^:private vars-form
  (test-run/qualify
   core-names
   (list 'let ['row 'ROW 'n '(find-ns 'NS-SYM)]
         (list 'binding '[*print-length* nil *print-level* nil]
               (list 'pr-str
                     (list 'if 'n
                           (list 'assoc
                                 (walk/postwalk-replace
                                  {'ROWS '(map (fn [[s v]] (row s v))
                                               (sort-by (fn [[s _]] (str s)) (ns-interns n)))}
                                  (capped :vars))
                                 :found? true)
                           {:found? false :vars [] :dropped 0}))))))

(def ^:private apropos-form
  (test-run/qualify
   core-names
   (list 'let ['row 'ROW
               '_ '(require 'clojure.string)
               'lower '(deref (resolve 'clojure.string/lower-case))
               'has? '(deref (resolve 'clojure.string/includes?))
               'q '(lower QUERY)]
         (list 'binding '[*print-length* nil *print-level* nil]
               (list 'pr-str
                     (walk/postwalk-replace
                      {'ROWS '(for [n (sort-by (fn [n] (str (ns-name n))) (all-ns))
                                    [s v] (sort-by (fn [[s _]] (str s)) (ns-interns n))
                                    :when (has? (lower (str s)) q)]
                                (assoc (row s v) :ns (str (ns-name n))))}
                      (capped :vars)))))))

(defn- printed
  "form with the capital names in m filled in, printed in full."
  [form m]
  (binding [*print-length* nil *print-level* nil]
    (pr-str (walk/postwalk-replace (merge {'ROW row-form} m) form))))

(defn namespaces-snippet
  "Code whose value is a string of EDN {:namespaces [{:name :file :vars}]
  :dropped n}: every loaded namespace sorted by name, at most :cap
  (runtime-cap) of them. :file is the first file one of its vars names."
  ([] (namespaces-snippet {}))
  ([{:keys [cap] :or {cap runtime-cap}}]
   (printed namespaces-form {'CAP cap})))

(defn vars-snippet
  "Code whose value is a string of EDN {:found? bool :vars [row] :dropped
  n} for the vars namespace :ns interns, sorted by name, at most :cap. A row
  is {:name :arglists :doc :file :line :private? :macro?}, :arglists each
  printed, :doc the docstring's first line. Throws when :ns isn't a plain
  symbol name."
  [{:keys [ns cap] :or {cap runtime-cap}}]
  (printed vars-form {'NS-SYM (test-run/checked-symbol "namespace" ns) 'CAP cap}))

(defn apropos-snippet
  "Code whose value is a string of EDN {:vars [row] :dropped n}: the vars
  of every loaded namespace whose name holds :query in any case, each row
  with its :ns, sorted by namespace then name, at most :cap."
  [{:keys [query cap] :or {cap runtime-cap}}]
  (printed apropos-form {'QUERY (str query) 'CAP cap}))
