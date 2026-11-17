(ns jonquil.test-run
  "Running a project's clojure.test tests through an evaluator.

  `snippet` is code for the project's runtime: it reloads a test namespace,
  runs it with clojure.test's report rebound, and its value is a string
  holding one EDN map. `read-value` reads that back, `report` turns it into
  counts and a sorted failure list, and `text` and `data` are the two forms
  an answer takes. No evaluator here, and only project-files reads the disk."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def max-failures
  "How many failures one run lists; the rest are only counted."
  100)

(def max-shown-chars
  "How many characters of a printed expected or actual value are kept."
  2000)

(defn supported?
  "True for the dialect profile ids whose tests the snippet can run."
  [profile-id]
  (contains? #{:jolt :clj} profile-id))

(def ^:private name-re #"[A-Za-z*+!\-_?<>=][A-Za-z0-9.*+!\-_?<>=']*")

(defn- checked-symbol
  "s as a symbol when it is a plain symbol name that reads back as one
  (nil and true don't); throws naming what otherwise. A test name has no
  dot, which a runtime would take for a class name."
  [what s]
  (if (and (string? s)
           (re-matches name-re s)
           (symbol? (edn/read-string s))
           (or (not= "test" what) (not (str/includes? s "."))))
    (symbol s)
    (throw (ex-info (str "Not a " what " name: " (pr-str s)) {}))))

(def ^:private core-names
  "The clojure.core names the snippet's forms use."
  '#{fn let case swap! update inc first deref when name meta vec reverse some? str
     if-let into keep when-let ns-interns find-ns require identical? get remove or
     seq not doseq keys ns-unmap resolve symbol atom ns-resolve and filter vals cond
     nil? empty? = with-bindings binding pr-str assoc instance? ex-message class
     count > subs < conj some replace alter-meta! *print-length* *print-level*})

(defn- qualified
  "form with its clojure.core names written in full. The snippet runs in
  whatever namespace the session is in, which may not refer clojure.core
  or may have redefined one of its names."
  [form]
  (walk/postwalk #(if (and (symbol? %) (contains? core-names %))
                    (symbol "clojure.core" (name %))
                    %)
                 form))

;; The forms below are data: `snippet` fills the capital names in and
;; prints the result. They resolve the vars of clojure.test, clojure.string
;; and clojure.java.io by name at run time, so the code compiles before
;; those are loaded.

(def ^:private show-form
  (qualified
   '(fn [v]
      (let [s (binding [*print-length* 100 *print-level* 10]
                (if (instance? Throwable v)
                  (str (.getName (class v)) ": " (ex-message v))
                  (pr-str v)))]
        (if (> (count s) MAX-CHARS) (str (subs s 0 MAX-CHARS) "...") s)))))

(def ^:private collector-form
  (qualified
   '(fn [state tv show]
      (fn [m]
        (case (:type m)
          :begin-test-var (swap! state update :tests inc)
          :pass (swap! state update :pass inc)
          (:fail :error)
          (let [v (first (deref (tv "*testing-vars*")))
                row {:test (when v (name (:name (meta v))))
                     :kind (:type m)
                     :file (when v (:file (meta v)))
                     :event-file (:file m)
                     :line (:line m)
                     :var-line (when v (:line (meta v)))
                     :context (vec (reverse (deref (tv "*testing-contexts*"))))
                     :message (when (some? (:message m)) (str (:message m)))
                     :expected (show (:expected m))
                     :actual (show (:actual m))}]
            (swap! state (fn [s]
                           (let [s (update s (:type m) inc)]
                             (if (< (count (:failures s)) MAX-FAILURES)
                               (update s :failures conj row)
                               (update s :failures-dropped inc))))))
          nil)))))

;; A reload redefines what the file holds and removes nothing, so a test
;; that was renamed or deleted would stay in the namespace and keep running.
;; A reloaded deftest has a new :test fn. The ones whose fn is unchanged
;; after a reload are no longer in the file, and are unmapped. When no test
;; changed at all, the file may not have been read: Jolt reloads a
;; namespace it finds no file for as a no-op. So the unchanged tests go
;; only when the namespace's file is on the load path, and a namespace
;; defined in the session keeps its tests.
(def ^:private reload-form
  (qualified
   '(fn [ns-sym]
      (let [test-fns (fn []
                       (if-let [n (find-ns ns-sym)]
                         (into {} (keep (fn [[s v]] (when-let [f (:test (meta v))] [s f]))
                                        (ns-interns n)))
                         {}))
            rv (fn [s] (deref (resolve s)))
            on-path? (fn []
                       (require 'clojure.string 'clojure.java.io)
                       (let [swap (rv 'clojure.string/replace)
                             base (swap (swap (str ns-sym) "-" "_") "." "/")]
                         (some (fn [ext] ((rv 'clojure.java.io/resource) (str base ext)))
                               [".clj" ".cljc" ".jolt"])))
            before (test-fns)
            _ (require ns-sym :reload)
            after (test-fns)
            fresh (into {} (remove (fn [[s f]] (identical? f (get before s))) after))]
        (when (or (seq fresh) (on-path?))
          (doseq [s (remove fresh (keys after))]
            (ns-unmap ns-sym s)))))))

;; The window runs the buffer itself between two evaluations, so there is
;; no reload to compare across. mark-form tags every test the namespace
;; holds before that Run. Evaluating a deftest again replaces its var's
;; metadata, tag included, so after the Run the tag is left only on tests
;; the buffer no longer defines, and drop-marked-form unmaps those.
(def ^:private mark-form
  (qualified
   '(let [n (find-ns 'NS-SYM)]
      (count (keep (fn [v]
                     (when (:test (meta v))
                       (alter-meta! v assoc :jonquil/stale true)))
                   (if n (vals (ns-interns n)) []))))))

(def ^:private drop-marked-form
  (qualified
   '(fn [ns-sym]
      (when-let [n (find-ns ns-sym)]
        (doseq [[s v] (ns-interns n)]
          (when (:jonquil/stale (meta v))
            (ns-unmap ns-sym s)))))))

(def ^:private run-form
  (qualified
   '(do
      (require 'clojure.test)
      (let [tv (fn [n] (resolve (symbol "clojure.test" n)))
            state (atom {:tests 0 :pass 0 :fail 0 :error 0 :failures [] :failures-dropped 0})
            collect (COLLECTOR state tv SHOW)
            ns-sym 'NS-SYM
            _ (RELOAD ns-sym)
            vars (if TEST-NAME
                   (let [v (ns-resolve ns-sym (symbol TEST-NAME))]
                     (when (and v (:test (meta v))) [v]))
                   (filter (fn [v] (:test (meta v))) (vals (ns-interns ns-sym))))
            outcome (cond (nil? vars) :no-such-test (empty? vars) :no-tests :else :ran)]
        (when (= outcome :ran)
          (with-bindings {(tv "report") collect}
            (if TEST-NAME
              ((deref (tv "test-vars")) vars)
              ((deref (tv "test-ns")) ns-sym))))
        (binding [*print-length* nil *print-level* nil]
          (pr-str (assoc (deref state) :outcome outcome)))))))

(defn snippet
  "Code that runs namespace :ns (a string), or only its test :test, and
  whose value is a string holding the run as EDN: :outcome (:ran, :no-tests
  or :no-such-test), the counts :tests :pass :fail :error, :failures (at
  most max-failures rows) and :failures-dropped. A load failure is left to
  throw. The namespace is reloaded from disk first, and the tests the
  reload didn't redefine (renamed or deleted in the file) are unmapped.
  :reload? false skips the reload. :drop-marked? true is for a namespace
  the caller has just run itself after mark-snippet: no reload, and the
  tests still carrying mark-snippet's tag are unmapped. Throws when :ns or
  :test isn't a plain symbol name."
  [{:keys [ns test reload? drop-marked?] :or {reload? true}}]
  (let [ns-sym (checked-symbol "namespace" ns)
        test-name (when (some? test) (name (checked-symbol "test" test)))
        fill (fn [form m] (walk/postwalk-replace m form))
        limits {'MAX-CHARS max-shown-chars 'MAX-FAILURES max-failures}
        form (fill run-form
                   {'RELOAD (cond
                              drop-marked? drop-marked-form
                              reload? reload-form
                              :else (qualified '(fn [_] nil)))
                    'NS-SYM ns-sym
                    'TEST-NAME test-name
                    'SHOW (fill show-form limits)
                    'COLLECTOR (fill collector-form limits)})]
    (binding [*print-length* nil *print-level* nil]
      (pr-str form))))

(defn mark-snippet
  "Code that tags every test namespace :ns holds, for snippet's
  :drop-marked? to find after the caller has run the namespace's text
  again. Its value is how many it tagged, 0 when the namespace isn't
  loaded. Throws when :ns isn't a plain symbol name."
  [{:keys [ns]}]
  (binding [*print-length* nil *print-level* nil]
    (pr-str (walk/postwalk-replace {'NS-SYM (checked-symbol "namespace" ns)} mark-form))))

(defn form-name
  "The name a top-level form defines, as a string: the symbol after the
  form's head, metadata skipped. nil when the text isn't such a form."
  [text]
  (let [n (second (re-find #"^\(\s*[^\s()\[\]{}\"]+\s+(?:\^(?:\{[^}]*\}|[^\s]+)\s+)*([^\s()\[\]{}\"^]+)" (str text)))]
    (when (and n (re-matches name-re n)) n)))

(def ^:private max-located-files 20000)

(defn project-files
  "The paths of the files under dir, at most 20000, skipping dot
  directories, target and node_modules. The one fn here that reads the
  disk: callers hand its answer to locate."
  [dir]
  (let [skip? (fn [^java.io.File f]
                (let [n (.getName f)]
                  (or (str/starts-with? n ".") (contains? #{"target" "node_modules"} n))))
        walk (fn walk [^java.io.File d]
               (mapcat (fn [^java.io.File f]
                         (cond
                           (skip? f) nil
                           (.isDirectory f) (walk f)
                           :else [(.getPath f)]))
                       (.listFiles d)))]
    (take max-located-files (walk (java.io.File. ^String (str dir))))))

(defn read-value
  "The snippet's map from an evaluation's printed value, a printed string
  holding EDN. nil when it doesn't read as that."
  [printed]
  (try
    (let [s (edn/read-string printed)
          m (when (string? s) (edn/read-string s))]
      (when (map? m) m))
    (catch :default _ nil)))

(defn locate
  "The one path in paths that is rel or ends with /rel. nil when none or
  several do."
  [paths rel]
  (when-not (str/blank? rel)
    (let [suffix (str "/" rel)
          hits (take 2 (filter #(or (= % rel) (str/ends-with? % suffix)) paths))]
      (when (= 1 (count hits)) (first hits)))))

(defn- failure-row
  "A snippet failure as the report's row. The line is the event's when the
  event is in the test's own file; a throw from elsewhere (Numbers.java on
  the JVM) falls back to the deftest's line."
  [locate f]
  (let [file (:file f)
        base (when file (last (str/split file #"/")))
        own-file? (and (:line f) (some? base) (= base (:event-file f)))]
    {:test (:test f)
     :kind (name (:kind f))
     :file (or (when file (locate file)) file)
     :line (if own-file? (:line f) (or (:var-line f) (:line f)))
     :context (vec (:context f))
     :message (:message f)
     :expected (:expected f)
     :actual (:actual f)}))

(defn- status-of [{:keys [outcome tests fail error]}]
  (cond
    (= :no-such-test outcome) "error"
    (= :no-tests outcome) "no-tests"
    (pos? (+ fail error)) "failed"
    (zero? tests) "no-tests"
    :else "ok"))

(defn report
  "The run as {:status :ns :counts :failures :failures-dropped :message}
  from the snippet's value. opts holds :ns and :test, the names asked for,
  and :locate, a fn from a source-root-relative path to an absolute one or
  nil. :status is \"ok\", \"failed\", \"no-tests\" or \"error\" (the named
  test doesn't exist, said in :message). Failures sort by file, line, test."
  [value {:keys [ns test locate]}]
  (let [locate (or locate (constantly nil))
        {:keys [tests pass fail error failures failures-dropped]} value
        status (status-of value)]
    {:status status
     :ns ns
     :counts {"tests" tests "assertions" (+ pass fail error)
              "pass" pass "fail" fail "error" error}
     :failures (vec (sort-by (juxt :file :line :test) (map #(failure-row locate %) failures)))
     :failures-dropped (or failures-dropped 0)
     :message (when (= :no-such-test (:outcome value))
                (str "No test named " test " in " ns "."))}))

(defn- counted [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn- summary-line [{:keys [status ns counts message]}]
  (let [{:strs [tests assertions pass fail error]} counts
        ran (str (counted tests "test") ", " (counted assertions "assertion"))]
    (case status
      "ok" (str "All passed in " ns ": " ran)
      "no-tests" (str "No tests in " ns ".")
      "error" message
      (str (counted (+ fail error) "failure") " in " ns ": " ran ", "
           pass " passed, " fail " failed, " (counted error "error")))))

(defn- failure-lines
  "The three lines for one failure. shown-file turns its file into what
  the first line shows."
  [shown-file {:keys [test kind file line context message expected actual]}]
  [(str (if (= "error" kind) "ERROR " "FAIL ") test " (" (shown-file file) ":" line ")"
        (when (seq context) (str " " (str/join " > " context)))
        (when message (str ": " message)))
   (str "  expected: " expected)
   (str "  actual:   " actual)])

(defn text
  "The report for reading: a summary line, three lines per failure, and a
  line counting the failures left out. Ends with a newline."
  [{:keys [failures failures-dropped] :as report}]
  (str (str/join "\n" (concat [(summary-line report)]
                              (mapcat #(failure-lines identity %) failures)
                              (when (pos? failures-dropped)
                                [(str failures-dropped " more failures not listed.")])))
       "\n"))

(defn- file-name [path]
  (when path (last (str/split path #"/"))))

(defn console-lines
  "The report as the window's console shows it: `; Tests: ` and the
  summary, then three lines per failure with the file's name alone, then a
  line counting the failures left out. Every line starts with `; `."
  [{:keys [failures failures-dropped] :as report}]
  (let [summary (summary-line report)
        first-line (str "Tests: " (str/lower-case (subs summary 0 1)) (subs summary 1))]
    (mapv #(str "; " %)
          (concat [first-line]
                  (mapcat #(failure-lines file-name %) failures)
                  (when (pos? failures-dropped)
                    [(str failures-dropped " more failures not listed.")])))))

(defn- failure-data [{:keys [message] :as f}]
  (cond-> {"test" (:test f) "kind" (:kind f) "file" (:file f) "line" (:line f)
           "context" (:context f) "expected" (:expected f) "actual" (:actual f)}
    message (assoc "message" message)))

(defn data
  "The report as an MCP answer's structuredContent. extra holds what the
  evaluation printed: :output, :err (the stderr part, when known), :dropped
  (characters cut from the output's front) and :ms."
  [{:keys [status ns counts failures failures-dropped message]} {:keys [output err dropped ms]}]
  (cond-> {"status" status
           "ns" ns
           "counts" counts
           "failures" (mapv failure-data failures)
           "output" (str output)}
    (pos? failures-dropped) (assoc "failures_dropped" failures-dropped)
    message (assoc "error" {"message" message})
    (seq err) (assoc "err" err)
    (and dropped (pos? dropped)) (assoc "dropped_chars" dropped)
    ms (assoc "ms" ms)))
