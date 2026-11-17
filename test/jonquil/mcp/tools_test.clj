(ns jonquil.mcp.tools-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.file-tree :as ft]
            [jonquil.mcp.tools :as tools]
            [jonquil.runtime-registry :as registry]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

;; Made on first use, not at load: temp-dirs' fixture deletes every recorded
;; directory when any namespace's tests end, and all test namespaces load first.
(def ^:private env
  (delay
    (let [dir (temp-dirs/temp-dir! "jonquil-mcp-test-")
          settings-path (str (io/file (temp-dirs/temp-dir! "jonquil-mcp-test-") "settings.edn"))]
      (spit (io/file dir "deps.edn") "{}")
      ;; In process, so jolt test starts no Jolt child (a deps.edn project
      ;; otherwise runs out of process; jolt mcp:smoke covers that path).
      (spit (io/file dir ".jonquil.edn") "{:runtime :in-process}")
      {:dir dir
       :t (tools/make-tools {:dir dir :settings-path settings-path})})))

(defn- dir [] (:dir @env))

(defn- call [name args] ((:call (:t @env)) name args))

(deftest defs-name-seven-tools
  (is (= #{"jonquil_eval" "jonquil_run_file" "jonquil_test" "jonquil_check_brackets"
           "jonquil_doc" "jonquil_complete" "jonquil_project"}
         (set (map #(get % "name") (:defs (:t @env))))))
  (is (every? #(vector? (get-in % ["inputSchema" "required"])) (:defs (:t @env)))))

(defn- plain [r] (dissoc r :data))

(deftest eval-value-and-output
  (is (= {:text "hi\n=> 3" :error? false}
         (plain (call "jonquil_eval" {"code" "(println \"hi\") (+ 1 2)"})))))

(deftest eval-data-names-the-parts
  (let [d (:data (call "jonquil_eval" {"code" "(println \"hi\") (+ 1 2)"}))]
    (is (= {"status" "ok" "output" "hi\n" "value" "3" "ns" "user"} (dissoc d "ms")))
    (is (nat-int? (get d "ms")))))

(deftest eval-data-keeps-stderr-apart
  (let [d (:data (call "jonquil_eval" {"code" "(println \"o\") (binding [*out* *err*] (println \"e\")) 1"}))]
    (is (= "o\ne\n" (get d "output")))
    (is (= "e\n" (get d "err")))))

(deftest eval-error-data
  (let [d (:data (call "jonquil_eval" {"code" "(/ 1 0)"}))]
    (is (= "error" (get d "status")))
    (is (not (contains? d "value")))
    (is (str/includes? (get-in d ["error" "message"]) "Divide by zero"))
    (is (str/includes? (get-in d ["error" "type"]) "ArithmeticException"))))

(deftest eval-timeout-data
  (let [d (:data (call "jonquil_eval" {"code" "(println \"p\") (loop [] (recur))" "timeout_ms" 300}))]
    (is (= "timeout" (get d "status")))
    (is (= "p\n" (get d "output")))
    (is (>= (get d "ms") 300))))

(deftest eval-output-is-capped
  (let [r (call "jonquil_eval" {"code" "(dotimes [_ 100] (print \"0123456789\")) :done"
                                "max_output_chars" 25})]
    (is (= (str "; 975 earlier characters of output dropped\n"
                "5678901234567890123456789\n=> :done")
           (:text r)))
    (is (= 975 (get-in r [:data "dropped_chars"])))
    (is (= 25 (count (get-in r [:data "output"]))))
    (is (not (:error? r)))))

(deftest eval-error-is-error
  (let [r (call "jonquil_eval" {"code" "(/ 1 0)"})]
    (is (:error? r))
    (is (str/includes? (:text r) "Divide by zero"))))

(deftest eval-keeps-state
  (call "jonquil_eval" {"code" "(def x 41)"})
  (is (str/ends-with? (:text (call "jonquil_eval" {"code" "(inc x)"})) "=> 42")))

(deftest eval-timeout-interrupts
  (let [t0 (System/currentTimeMillis)
        r (call "jonquil_eval" {"code" "(loop [] (recur))" "timeout_ms" 300})]
    (is (< (- (System/currentTimeMillis) t0) 6000))
    (is (:error? r))
    (is (str/includes? (:text r) "300"))
    (is (= "=> 2" (:text (call "jonquil_eval" {"code" "(+ 1 1)"}))))))

(deftest eval-print-without-newline
  (is (= "x\n=> 1" (:text (call "jonquil_eval" {"code" "(print \"x\") 1"})))))

(deftest stuck-evaluation-is-evicted
  (let [d (temp-dirs/temp-dir! "jonquil-mcp-test-")
        _ (spit (io/file d "deps.edn") "{}")
        ;; The fresh evaluator after the eviction stays in process too.
        _ (spit (io/file d ".jonquil.edn") "{:runtime :in-process}")
        k (dialect/selection-key (ft/project-selection d nil))
        stopped (atom false)
        fake (reify ev/Evaluator
               (eval! [_ _ _] nil)
               (start! [_] nil)
               (evaluating? [_] true)
               (waiting-for-input? [_] false)
               (send-input! [_ _] nil)
               (send-eof! [_] nil)
               (history [_] nil)
               (prompt-ns [_] 'user)
               (set-mode! [_ _] nil)
               (complete [_ _] [])
               (lookup [_ _ _ _] nil)
               (limitations [_] [])
               (interrupt! [_] nil)
               (stop! [_] (reset! stopped true) nil))
        tt (tools/make-tools {:dir d :cache (atom {k fake}) :grace-ms 200
                              :settings-path (str (io/file d "s.edn"))})
        r ((:call tt) "jonquil_eval" {"code" "(+ 1 1)" "timeout_ms" 100})]
    (is (:error? r))
    (is (str/includes? (:text r) "fresh one will be started"))
    (is @stopped)
    (is (= "=> 2" (:text ((:call tt) "jonquil_eval" {"code" "(+ 1 1)"}))))))

(deftest eval-needs-code
  (is (thrown? Throwable (call "jonquil_eval" {}))))

(deftest run-file
  (let [f (io/file (dir) "a.clj")]
    (spit f "(ns demo.a) (def y 7) y")
    (let [r (call "jonquil_run_file" {"path" (.getPath f)})]
      (is (false? (:error? r)))
      (is (str/ends-with? (:text r) "=> 7")))))

(deftest run-file-missing-or-dir
  (let [missing (str (io/file (dir) "nope.clj"))]
    (is (thrown-with-msg? Throwable (re-pattern (java.util.regex.Pattern/quote missing))
                          (call "jonquil_run_file" {"path" missing})))
    (is (thrown-with-msg? Throwable (re-pattern (java.util.regex.Pattern/quote (dir)))
                          (call "jonquil_run_file" {"path" (dir)})))))

(deftest check-brackets-ok-and-problem
  (is (= {:text "ok" :error? false} (call "jonquil_check_brackets" {"text" "(+ 1 2)"})))
  (let [r (call "jonquil_check_brackets" {"text" "(+ 1\n(2"})]
    (is (false? (:error? r)))
    (is (str/includes? (:text r) "never closed")))
  (let [f (io/file (dir) "b.clj")]
    (spit f "(+ 1 2")
    (is (str/includes? (:text (call "jonquil_check_brackets" {"path" (.getPath f)}))
                       "never closed")))
  (is (thrown? Throwable (call "jonquil_check_brackets" {}))))

(deftest doc-for-map
  (let [r (call "jonquil_doc" {"symbol" "map"})]
    (is (false? (:error? r)))
    (is (str/includes? (:text r) "map"))
    (is (str/includes? (:text r) "[")))
  (let [r (call "jonquil_doc" {"symbol" "no-such-thing-xyz"})]
    (is (false? (:error? r)))
    (is (seq (:text r)))))

(deftest complete-prefix
  (let [r (call "jonquil_complete" {"prefix" "ma"})
        lines (str/split-lines (:text r))]
    (is (false? (:error? r)))
    (is (some #{"map"} lines))
    (is (<= (count lines) 50)))
  (is (= "No completions for zzqqxx." (:text (call "jonquil_complete" {"prefix" "zzqqxx"})))))

(deftest project-info
  (let [p (temp-dirs/temp-dir! "jonquil-mcp-test-")
        before (count @@#'registry/live)]
    (spit (io/file p ".jonquil.edn") "{:runtime :nrepl}")
    (let [r (call "jonquil_project" {"path" p})]
      (is (false? (:error? r)))
      (is (str/includes? (:text r) "nREPL"))
      (is (str/includes? (:text r) (.getCanonicalPath (io/file p))))
      (is (str/includes? (:text r) "Chosen by: fallback")))
    (is (= before (count @@#'registry/live)))))

(deftest unknown-tool-throws-and-nil-args-count-as-empty
  (let [ex (try (call "jonquil_nope" {}) nil (catch Throwable e e))]
    (is (= "Unknown tool: jonquil_nope" (ex-message ex))))
  (is (= "Missing required argument: symbol"
         (ex-message (try ((:call (:t @env)) "jonquil_doc" nil) nil (catch Throwable e e))))))

(deftest eval-path-picks-the-project
  (let [d (temp-dirs/temp-dir! "jonquil-mcp-test-")
        _ (spit (io/file d ".jonquil.edn") "{:runtime :in-process}")
        f (str (io/file d "a.clj"))
        _ (spit f "(ns a)")
        tt (tools/make-tools {:dir (dir) :settings-path (str (io/file d "s.edn"))})
        asked (atom [])
        select @#'tools/selection]
    (with-redefs [tools/selection (fn [ctx path] (swap! asked conj path) (select ctx path))]
      (is (= {:text "=> 3" :error? false} (plain ((:call tt) "jonquil_eval" {"code" "(+ 1 2)" "path" f}))))
      (is (seq @asked))
      (is (every? #(= (.getCanonicalPath (io/file f)) (.getCanonicalPath (io/file %))) @asked)
          "every project lookup the eval makes is for the path it was given")
      (reset! asked [])
      (is (= {:text "=> 3" :error? false} (plain ((:call tt) "jonquil_eval" {"code" "(+ 1 2)"}))))
      (is (seq @asked))
      (is (every? nil? @asked) "with no path, the lookup is for no path"))))

;; --- jonquil_test ------------------------------------------------------------

(def ^:private core-test-text
  (str "(ns demo.core-test\n"
       "  (:require [clojure.test :refer [deftest is testing]]))\n"
       "\n"
       "(deftest passes (is (= 1 1)))\n"
       "\n"
       "(deftest fails\n"
       "  (testing \"outer\"\n"
       "    (testing \"inner\"\n"
       "      (is (= 1 2) \"msg\"))))\n"
       "\n"
       "(deftest throws\n"
       "  (is (= 1 (/ 1 0))))\n"))

(def ^:private other-tests
  {"noisy_test.clj" (str "(ns demo.noisy-test (:require [clojure.test :refer [deftest is]]))\n"
                         "(deftest noisy (dotimes [_ 100] (print \"0123456789\")) (is true))\n")
   "lazy_test.clj" (str "(ns demo.lazy-test (:require [clojure.test :refer [deftest is]]))\n"
                        "(deftest endless (is (= 1 (range))))\n")
   "outside_test.clj" (str "(ns demo.outside-test (:require [clojure.test :refer [deftest]]))\n"
                           "(deftest outside (throw (ex-info \"boom\" {})))\n")
   "loop_test.clj" (str "(ns demo.loop-test (:require [clojure.test :refer [deftest is]]))\n"
                        "(deftest spins (is (loop [] (recur))))\n")
   "empty_test.clj" "(ns demo.empty-test)\n"})

(defn- test-project!
  "A temp Jolt project, in process, with the demo tests under test/demo."
  []
  (let [dir (temp-dirs/temp-dir! "jonquil-mcp-testtool-")
        demo (io/file dir "test" "demo")]
    (.mkdirs demo)
    (spit (io/file dir "deps.edn") "{:paths [\"src\" \"test\"]}")
    (spit (io/file dir ".jonquil.edn") "{:runtime :in-process}")
    (spit (io/file demo "core_test.clj") core-test-text)
    (doseq [[n text] other-tests] (spit (io/file demo n) text))
    dir))

(def ^:private test-env
  (delay
    (let [dir (test-project!)
          settings-path (str (io/file (temp-dirs/temp-dir! "jonquil-mcp-testtool-") "settings.edn"))]
      {:dir dir :t (tools/make-tools {:dir dir :settings-path settings-path})})))

(defn- run-tests [args] ((:call (:t @test-env)) "jonquil_test" args))

(defn- core-test-file [] (io/file (:dir @test-env) "test" "demo" "core_test.clj"))

(deftest test-runs-a-namespace
  (let [r (run-tests {"ns" "demo.core-test"})
        d (:data r)
        [f t] (get d "failures")]
    (is (not (:error? r)))
    (is (= "failed" (get d "status")))
    (is (= "demo.core-test" (get d "ns")))
    (is (= {"tests" 3 "assertions" 3 "pass" 1 "fail" 1 "error" 1} (get d "counts")))
    (is (= (.getCanonicalPath (core-test-file)) (.getCanonicalPath (io/file (get f "file")))))
    (is (= ["fails" "fail" 9 ["outer" "inner"] "msg" "(= 1 2)" "(not (= 1 2))"]
           (map #(get f %) ["test" "kind" "line" "context" "message" "expected" "actual"])))
    (is (= ["throws" "error"] (map #(get t %) ["test" "kind"])))
    (is (str/includes? (get t "actual") "Divide by zero"))
    (is (str/starts-with? (:text r) "2 failures in demo.core-test: 3 tests, 3 assertions, 1 passed, 1 failed, 1 error\nFAIL fails ("))
    (is (nat-int? (get d "ms")))))

(deftest test-runs-one-test
  (let [r (run-tests {"ns" "demo.core-test" "test" "passes"})]
    (is (= "All passed in demo.core-test: 1 test, 1 assertion\n" (:text r)))
    (is (= "ok" (get-in r [:data "status"])))
    (is (not (:error? r)))))

(deftest test-takes-a-file-path
  (let [d (:data (run-tests {"path" (.getPath (core-test-file))}))]
    (is (= "demo.core-test" (get d "ns")))
    (is (= 3 (get-in d ["counts" "tests"])))))

(deftest test-sees-an-edit
  (let [dir (:dir @test-env)
        f (io/file dir "test" "demo" "edited_test.clj")
        text (fn [n] (str "(ns demo.edited-test (:require [clojure.test :refer [deftest is]]))\n"
                          "(deftest changes (is (= 1 " n ")))\n"))
        status (fn [] (get-in (run-tests {"ns" "demo.edited-test"}) [:data "status"]))]
    (spit f (text 2))
    (is (= "failed" (status)))
    (spit f (text 1))
    (is (= "ok" (status)))))

(deftest test-forgets-renamed-and-deleted-tests
  (let [f (io/file (:dir @test-env) "test" "demo" "stale_test.clj")
        head "(ns demo.stale-test (:require [clojure.test :refer [deftest is]]))\n"
        run (fn [args] (:data (run-tests (merge {"ns" "demo.stale-test"} args))))]
    (spit f (str head "(deftest keep-me (is true))\n"
                 "(deftest old-name (is (= 1 2)))\n"
                 "(deftest deleted (is (= 1 3)))\n"))
    (is (= {"tests" 3 "assertions" 3 "pass" 1 "fail" 2 "error" 0} (get (run {}) "counts")))
    (spit f (str head "(deftest keep-me (is true))\n(deftest new-name (is (= 1 1)))\n"))
    (let [d (run {})]
      (is (= "ok" (get d "status")))
      (is (= {"tests" 2 "assertions" 2 "pass" 2 "fail" 0 "error" 0} (get d "counts"))))
    (is (= "error" (get (run {"test" "old-name"}) "status")) "the old name is gone")
    (is (= "ok" (get (run {"test" "new-name"}) "status")))
    (spit f head)
    (is (= "no-tests" (get (run {}) "status")) "a file left with no tests runs none")))

;; A test file outside the source paths, loaded with Run: its namespace has
;; no file the runtime can reload, so its tests stay and run as they are.
(deftest test-keeps-a-namespace-loaded-from-off-the-path
  (let [f (io/file (:dir @test-env) "examples" "demo" "offpath_test.clj")]
    (.mkdirs (.getParentFile f))
    (spit f (str "(ns demo.offpath-test (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest here (is true))\n"))
    ((:call (:t @test-env)) "jonquil_run_file" {"path" (.getPath f)})
    (dotimes [_ 2]
      (let [d (:data (run-tests {"ns" "demo.offpath-test"}))]
        (is (= "ok" (get d "status")))
        (is (= 1 (get-in d ["counts" "tests"])))))))

(deftest test-runs-from-a-bare-namespace
  (let [ev! (fn [code] ((:call (:t @test-env)) "jonquil_eval" {"code" code}))]
    (ev! "(in-ns 'demo.bare-scratch)")
    (try
      (is (= "ok" (get-in (run-tests {"ns" "demo.core-test" "test" "passes"}) [:data "status"])))
      (finally (ev! "(clojure.core/in-ns 'user)")))))

(deftest test-no-such-test
  (let [r (run-tests {"ns" "demo.core-test" "test" "nope"})]
    (is (:error? r))
    (is (= "error" (get-in r [:data "status"])))
    (is (= "No test named nope in demo.core-test.\n" (:text r)))))

(deftest test-no-tests
  (let [r (run-tests {"ns" "demo.empty-test"})]
    (is (:error? r))
    (is (= "no-tests" (get-in r [:data "status"])))))

;; Jolt's (require .. :reload) leaves a namespace that has no file as it
;; is, so tests defined only in the session run. JVM Clojure throws there.
(deftest test-session-only-namespace-runs-as-it-is
  ((:call (:t @test-env)) "jonquil_eval"
   {"code" "(ns demo.only-here (:require [clojure.test :refer [deftest is]])) (deftest t (is true)) (in-ns 'user)"})
  (let [r (run-tests {"ns" "demo.only-here"})]
    (is (= "ok" (get-in r [:data "status"])))
    (is (= 1 (get-in r [:data "counts" "tests"])))))

(deftest test-missing-namespace-errors
  (let [r (run-tests {"ns" "demo.nowhere-test"})]
    (is (:error? r))
    (is (= "error" (get-in r [:data "status"])))
    (is (= "demo.nowhere-test" (get-in r [:data "ns"])))
    (is (not (contains? (:data r) "value")))
    (is (str/includes? (get-in r [:data "error" "message"]) "nowhere_test"))))

(deftest test-needs-ns-or-file
  (is (thrown-with-msg? Exception #"Give ns, or the path of a test file" (run-tests {})))
  (is (thrown-with-msg? Exception #"Give ns, or the path of a test file"
                        (run-tests {"path" (:dir @test-env)})))
  (let [f (io/file (:dir @test-env) "notes.clj")]
    (spit f "(+ 1 2)\n")
    (is (thrown-with-msg? Exception #"No ns form in" (run-tests {"path" (.getPath f)}))))
  (is (thrown-with-msg? Exception #"Not a namespace name" (run-tests {"ns" "a) (b"}))))

(deftest test-unsupported-dialect
  (let [dir (temp-dirs/temp-dir! "jonquil-mcp-testtool-")
        cache (atom {})
        t (tools/make-tools {:dir dir :cache cache
                             :settings-path (str (io/file dir "settings.edn"))})
        _ (spit (io/file dir ".jonquil.edn") "{:dialect :cljs}")
        r ((:call t) "jonquil_test" {"ns" "demo.core-test"})]
    (is (:error? r))
    (is (= {"status" "unsupported"} (:data r)))
    (is (str/starts-with? (:text r) "jonquil_test runs Jolt and JVM Clojure tests for now. This project is "))
    (is (= {} @cache) "no runtime was made")))

(deftest test-timeout
  (let [r (run-tests {"ns" "demo.loop-test" "timeout_ms" 300})]
    (is (:error? r))
    (is (= "timeout" (get-in r [:data "status"])))
    (is (= "demo.loop-test" (get-in r [:data "ns"])))))

(deftest test-output-is-capped
  (let [r (run-tests {"ns" "demo.noisy-test" "max_output_chars" 25})]
    (is (= (str "; 975 earlier characters of output dropped\n"
                "All passed in demo.noisy-test: 1 test, 1 assertion\n"
                "5678901234567890123456789")
           (:text r)))
    (is (= 975 (get-in r [:data "dropped_chars"])))
    (is (= 1 (get-in r [:data "counts" "pass"])))))

(deftest test-infinite-actual-returns
  (let [d (:data (run-tests {"ns" "demo.lazy-test" "timeout_ms" 5000}))
        actual (get-in d ["failures" 0 "actual"])]
    (is (= "failed" (get d "status")))
    (is (str/starts-with? actual "(not (= 1 (0 1 2"))
    (is (<= (count actual) 2003))))

(deftest test-throw-outside-is
  (let [d (:data (run-tests {"ns" "demo.outside-test"}))
        [f] (get d "failures")]
    (is (= {"tests" 1 "assertions" 1 "pass" 0 "fail" 0 "error" 1} (get d "counts")))
    (is (= 1 (count (get d "failures"))))
    (is (= ["outside" "error" 2] (map #(get f %) ["test" "kind" "line"])))
    (is (str/includes? (get f "actual") "boom"))))

(deftest test-leaves-the-repl-alone
  (let [ev! (fn [code] (:text ((:call (:t @test-env)) "jonquil_eval" {"code" code})))]
    (ev! "42")
    (run-tests {"ns" "demo.core-test" "test" "passes"})
    (is (= "=> 42" (ev! "*1")))))
