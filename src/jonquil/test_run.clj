(ns jonquil.test-run
  "Running a project's clojure.test tests through an evaluator.

  `snippet` is code for the project's runtime: it reloads a test namespace,
  runs it with clojure.test's report rebound, and its value is a string
  holding one EDN map. `read-value` reads that back, `report` turns it into
  counts and a sorted failure list, and `text` and `data` are the two forms
  an answer takes. Pure: no evaluator, no files."
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
  "s as a symbol when it is a plain symbol name; throws naming what otherwise."
  [what s]
  (if (and (string? s) (re-matches name-re s))
    (symbol s)
    (throw (ex-info (str "Not a " what " name: " (pr-str s)) {}))))

;; The three forms below are data: `snippet` fills the capital names in and
;; prints the result. They resolve clojure.test's vars by name at run time,
;; so the code compiles before clojure.test is loaded.

(def ^:private show-form
  '(fn [v]
     (let [s (binding [*print-length* 100 *print-level* 10]
               (if (instance? Throwable v)
                 (str (.getName (class v)) ": " (ex-message v))
                 (pr-str v)))]
       (if (> (count s) MAX-CHARS) (str (subs s 0 MAX-CHARS) "...") s))))

(def ^:private collector-form
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
         nil))))

(def ^:private run-form
  '(do
     (require 'clojure.test)
     RELOAD
     (let [tv (fn [n] (resolve (symbol "clojure.test" n)))
           state (atom {:tests 0 :pass 0 :fail 0 :error 0 :failures [] :failures-dropped 0})
           collect (COLLECTOR state tv SHOW)
           ns-sym 'NS-SYM
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
         (pr-str (assoc (deref state) :outcome outcome))))))

(defn snippet
  "Code that runs namespace :ns (a string), or only its test :test, and
  whose value is a string holding the run as EDN: :outcome (:ran, :no-tests
  or :no-such-test), the counts :tests :pass :fail :error, :failures (at
  most max-failures rows) and :failures-dropped. A load failure is left to
  throw. :reload? false skips reloading the namespace from disk. Throws
  when :ns or :test isn't a plain symbol name."
  [{:keys [ns test reload?] :or {reload? true}}]
  (let [ns-sym (checked-symbol "namespace" ns)
        test-name (when (some? test) (name (checked-symbol "test" test)))
        fill (fn [form m] (walk/postwalk-replace m form))
        limits {'MAX-CHARS max-shown-chars 'MAX-FAILURES max-failures}
        form (fill run-form
                   {'RELOAD (when reload? (list 'require (list 'quote ns-sym) :reload))
                    'NS-SYM ns-sym
                    'TEST-NAME test-name
                    'SHOW (fill show-form limits)
                    'COLLECTOR (fill collector-form limits)})]
    (binding [*print-length* nil *print-level* nil]
      (pr-str form))))

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

(defn- failure-lines [{:keys [test kind file line context message expected actual]}]
  [(str (if (= "error" kind) "ERROR " "FAIL ") test " (" file ":" line ")"
        (when (seq context) (str " " (str/join " > " context)))
        (when message (str ": " message)))
   (str "  expected: " expected)
   (str "  actual:   " actual)])

(defn text
  "The report for reading: a summary line, three lines per failure, and a
  line counting the failures left out. Ends with a newline."
  [{:keys [failures failures-dropped] :as report}]
  (str (str/join "\n" (concat [(summary-line report)]
                              (mapcat failure-lines failures)
                              (when (pos? failures-dropped)
                                [(str failures-dropped " more failures not listed.")])))
       "\n"))

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
