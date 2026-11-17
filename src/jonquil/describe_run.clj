(ns jonquil.describe-run
  "Describing what a project's runtime has loaded, for the MCP tools
  jonquil_namespaces, jonquil_vars and jonquil_apropos.

  The snippets are code for the project's runtime whose value is a string
  holding one EDN map (jonquil.test-run/read-value reads it back). The
  answer makers turn that map into a tool's text and structured content.
  No evaluator here, and nothing reads the disk."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
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
               'file-of '(fn [n] (first (keep (fn [[s v]] (:file (row s v))) (ns-interns n))))
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
                                (assoc (row s v) :ns (str (ns-name n)) :ns-file (file-of n)))}
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
  with its :ns and :ns-file (the namespace's file, as namespaces-snippet
  finds it), sorted by namespace then name, at most :cap."
  [{:keys [query cap] :or {cap runtime-cap}}]
  (printed apropos-form {'QUERY (str query) 'CAP cap}))

;; --- answers -----------------------------------------------------------------

(defn clamp-limit
  "x as a whole number in 1..runtime-cap, default when x isn't a number."
  [x default]
  (if (number? x) (long (max 1 (min runtime-cap x))) default))

(defn shown-file
  "file as the answer shows it: relative to :root when :locate finds it in
  the project, else as given. nil stays nil."
  [file {:keys [root locate]}]
  (when file
    (if-let [p (locate file)]
      (let [prefix (str root "/")]
        (if (str/starts-with? p prefix) (subs p (count prefix)) p))
      file)))

(defn project-hit
  "A path in paths that is rel or ends with /rel, the first one, nil when
  none does. Unlike test-run/locate, two hits still count: the file is in
  the project, even when which copy it is can't be told."
  [paths rel]
  (when-not (str/blank? rel)
    (let [suffix (str "/" rel)]
      (first (filter #(or (= % rel) (str/ends-with? % suffix)) paths)))))

(defn- own?
  "True when file is the project's: :own? says so when given, else :locate
  finding it."
  [file {:keys [locate] own-fn :own?}]
  (boolean (and file (if own-fn (own-fn file) (locate file)))))

(defn- remembering
  "opts with :locate and :own? remembered per file, so an answer looks each
  file up once however many vars it holds."
  [opts]
  (cond-> (update opts :locate memoize)
    (:own? opts) (update :own? memoize)))

(defn- more-line [n]
  (when (pos? n) (str "(" n " more not shown; raise limit to see them)")))

(defn- answer [lines data]
  {:text (str/join "\n" (remove nil? lines))
   :error? false
   :data (assoc data "status" "ok")})

(defn- own-first
  "rows split by own? on (file-of row): own ones first, the rest after them
  only when all?. [kept left-out-count]."
  [rows file-of all? opts]
  (let [{mine true theirs false} (group-by #(own? (file-of %) opts) rows)]
    [(concat mine (when all? theirs)) (if all? 0 (count theirs))]))

(defn namespaces-answer
  "The jonquil_namespaces answer for namespaces-snippet's value v: the
  project's own namespaces (their file found by :locate) first, the rest
  after them with :all?, else only counted as hidden. At most :limit
  (500)."
  [v {:keys [all? limit] :as opts}]
  (let [opts (remembering opts)
        limit (clamp-limit limit 500)
        [kept hidden] (own-first (sort-by :name (:namespaces v)) :file all? opts)
        shown (take limit kept)
        dropped (+ (or (:dropped v) 0) (- (count kept) (count shown)))]
    (answer (concat (map :name shown)
                    [(when (pos? hidden)
                       (str "(+ " hidden " Clojure and library namespaces; pass all: true to list them)"))
                     (more-line dropped)])
            {"namespaces" (mapv (fn [{:keys [name file vars]}]
                                  {"name" name "file" (shown-file file opts) "vars" vars
                                   "own" (own? file opts)})
                                shown)
             "hidden" hidden
             "dropped" dropped})))

(defn- var-data [{:keys [arglists doc file line private? macro?] :as row} opts]
  {"name" (:name row) "arglists" arglists "doc" doc "file" (shown-file file opts)
   "line" line "private" private? "macro" macro?})

(defn- location [file line]
  (when file (if line (str file ":" line) file)))

(defn- var-line [head {:keys [arglists doc private? macro?]} file line]
  (str/join "  " (remove str/blank? [(str/join " " (concat [head] arglists
                                                           (when private? ["(private)"])
                                                           (when macro? ["(macro)"])))
                                     doc
                                     (location file line)])))

(defn vars-answer
  "The jonquil_vars answer for vars-snippet's value v and namespace :ns:
  its vars sorted by name, private ones left out unless :private? (true),
  at most :limit (500). An error naming :ns when it isn't loaded, whose
  hint names :run-hint, the server's tool for running a file."
  [v {:keys [ns private? limit run-hint] :or {private? true run-hint "jonquil_run_file"} :as opts}]
  (if-not (:found? v)
    {:text (str "Namespace " ns " isn't loaded. Run its file (" run-hint "), then call again.")
     :error? true
     :data {"status" "not-found" "ns" ns}}
    (let [opts (remembering opts)
          limit (clamp-limit limit 500)
          kept (cond->> (sort-by :name (:vars v)) (not private?) (remove :private?))
          shown (take limit kept)
          dropped (+ (or (:dropped v) 0) (- (count kept) (count shown)))
          rows (map #(var-data % opts) shown)]
      (answer (concat (map (fn [row d] (var-line (:name row) row (get d "file") (:line row))) shown rows)
                      [(more-line dropped)])
              {"ns" ns "vars" (vec rows) "dropped" dropped}))))

(defn- apropos-line [row d]
  (str/join "  " (remove nil? [(str (:ns row) "/" (:name row) " " (str/join " " (:arglists row)))
                               (location (get d "file") (:line row))])))

(defn- left-out-line [hidden]
  (when (pos? hidden)
    (str "(" hidden " more in Clojure and libraries; pass all: true to list them)")))

(defn apropos-answer
  "The jonquil_apropos answer for apropos-snippet's value v: the project's
  own vars first, the rest after them unless :all? is false (then only
  counted, as hidden), each part sorted by namespace then name, at most
  :limit (50). A var is the program's own when its file, or its
  namespace's (a macro on Jolt has none of its own), is in the project."
  [v {:keys [query all? limit] :or {all? true} :as opts}]
  (let [opts (remembering opts)
        limit (clamp-limit limit 50)
        [kept hidden] (own-first (sort-by (juxt :ns :name) (:vars v)) #(or (:file %) (:ns-file %)) all? opts)
        shown (take limit kept)
        dropped (+ (or (:dropped v) 0) (- (count kept) (count shown)))
        rows (map (fn [row] (-> (var-data row opts) (select-keys ["name" "arglists" "doc" "file" "line"])
                                (assoc "ns" (:ns row))))
                  shown)
        data {"query" query "matches" (vec rows) "hidden" hidden "dropped" dropped}]
    (cond
      (seq shown) (answer (concat (map apropos-line shown rows) [(left-out-line hidden) (more-line dropped)]) data)
      (pos? hidden) (answer [(str "No var of the program's own has a name containing " (pr-str (str query)) ". "
                                  (left-out-line hidden))]
                            data)
      :else (answer [(str "No loaded var's name contains " (pr-str (str query)) ".")] data))))
