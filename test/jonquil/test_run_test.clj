(ns jonquil.test-run-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.test-run :as tr]))

(deftest supported-profiles
  (is (tr/supported? :jolt))
  (is (tr/supported? :clj))
  (is (not-any? tr/supported? [:cljs :cljd :jank :nrepl nil])))

(deftest snippet-is-one-form-naming-the-namespace
  (let [s (tr/snippet {:ns "demo.core-test"})
        form (read-string s)]
    (is (= 'do (first form)))
    (is (str/includes? s "(clojure.core/require ns-sym :reload)"))
    (is (str/includes? s "(quote demo.core-test)"))
    (is (= form (read-string (str s " :more"))) "nothing after the first form")))

(deftest snippet-without-reload
  (is (not (str/includes? (tr/snippet {:ns "demo.core-test" :reload? false}) ":reload"))))

;; The snippet runs in whatever namespace the session is in.
(deftest snippet-names-clojure-core-in-full
  (let [s (tr/snippet {:ns "demo.core-test" :test "a"})]
    (is (nil? (re-find #"[(\[ ](let|fn|require|update|swap!|when|if-let|binding|pr-str|str|deref|resolve|count|first|some|replace) " s)))
    (is (str/includes? s "(clojure.core/let ["))))

(deftest snippet-rejects-odd-names
  (is (thrown? Exception (tr/snippet {:ns "a) (b"})))
  (is (thrown? Exception (tr/snippet {:ns "a" :test "x y"})))
  (is (thrown? Exception (tr/snippet {:ns nil})))
  (is (thrown? Exception (tr/snippet {:ns "\"a\""})))
  (is (thrown? Exception (tr/snippet {:ns "nil"})))
  (is (thrown? Exception (tr/snippet {:ns "a" :test "true"})))
  (is (thrown? Exception (tr/snippet {:ns "a" :test "b.c"})))
  (is (string? (tr/snippet {:ns "a.b-c" :test "d-e?"}))))

(deftest snippet-bounds-printing
  (let [s (tr/snippet {:ns "demo.core-test"})]
    (is (str/includes? s "clojure.core/*print-length* 100"))
    (is (str/includes? s "clojure.core/*print-level* 10"))
    (is (str/includes? s (str tr/max-shown-chars)))))

(deftest read-value-reads-twice
  (is (= {:outcome :ran} (tr/read-value (pr-str (pr-str {:outcome :ran})))))
  (is (nil? (tr/read-value "garbage(")))
  (is (nil? (tr/read-value nil)))
  (is (nil? (tr/read-value "{:a 1}")) "a map that was not printed as a string")
  (is (nil? (tr/read-value (pr-str "42")))))

(deftest locate-needs-one-match
  (is (= "/p/test/demo/a.clj" (tr/locate ["/p/test/demo/a.clj" "/p/src/demo/b.clj"] "demo/a.clj")))
  (is (nil? (tr/locate ["/p/test/demo/a.clj" "/p/src/demo/a.clj"] "demo/a.clj")))
  (is (nil? (tr/locate ["/p/test/demo/b.clj"] "demo/a.clj")))
  (is (nil? (tr/locate ["/p/x/demo/a.clj"] "emo/a.clj")))
  (is (= "/p/demo/a.clj" (tr/locate ["/p/demo/a.clj"] "/p/demo/a.clj")))
  (is (nil? (tr/locate ["/p/demo/a.clj"] nil))))

(defn- value [m]
  (merge {:outcome :ran :tests 0 :pass 0 :fail 0 :error 0 :failures [] :failures-dropped 0} m))

(defn- failure [m]
  (merge {:test "fails" :kind :fail :file "demo/core_test.clj" :event-file "core_test.clj"
          :line 9 :var-line 6 :context ["outer" "inner"] :message "msg"
          :expected "(= 1 2)" :actual "(not (= 1 2))"}
         m))

(def ^:private thrown-failure
  (failure {:test "throws" :kind :error :event-file "Numbers.java" :line 190 :var-line 11
            :context [] :message nil :expected "(= 1 (/ 1 0))"
            :actual "java.lang.ArithmeticException: Divide by zero"}))

(deftest report-statuses
  (let [status (fn [m] (:status (tr/report (value m) {:ns "a" :test "t"})))]
    (is (= "ok" (status {:tests 2 :pass 3})))
    (is (= "failed" (status {:tests 2 :pass 1 :fail 1 :failures [(failure {})]})))
    (is (= "failed" (status {:tests 0 :error 1 :failures [thrown-failure]})))
    (is (= "no-tests" (status {:outcome :no-tests})))
    (is (= "no-tests" (status {})))
    (is (= "error" (status {:outcome :no-such-test})))))

(deftest report-counts-and-message
  (let [r (tr/report (value {:tests 3 :pass 1 :fail 1 :error 1}) {:ns "a.b" :test nil})]
    (is (= {"tests" 3 "assertions" 3 "pass" 1 "fail" 1 "error" 1} (:counts r)))
    (is (= "a.b" (:ns r)))
    (is (nil? (:message r))))
  (is (= "No test named nope in a.b."
         (:message (tr/report (value {:outcome :no-such-test}) {:ns "a.b" :test "nope"})))))

(deftest report-line-and-file
  (let [locate {"demo/core_test.clj" "/p/test/demo/core_test.clj"}
        rows (fn [fs opts] (:failures (tr/report (value {:failures fs}) opts)))
        [f] (rows [(failure {})] {:ns "a" :locate locate})
        [t] (rows [thrown-failure] {:ns "a" :locate locate})
        [u] (rows [(failure {})] {:ns "a"})
        [o] (rows [(failure {:event-file nil :line nil})] {:ns "a"})]
    (is (= ["/p/test/demo/core_test.clj" 9 "fail"] ((juxt :file :line :kind) f)))
    (is (= ["/p/test/demo/core_test.clj" 11 "error"] ((juxt :file :line :kind) t)))
    (is (= "demo/core_test.clj" (:file u)))
    (is (= 6 (:line o)))))

(deftest report-sorts-failures
  (let [fs [(failure {:test "c" :line 30})
            (failure {:test "b" :line 9})
            (failure {:test "a" :line 9})
            (failure {:test "z" :file "demo/a_test.clj" :event-file "a_test.clj" :line 99})]]
    (is (= ["z" "a" "b" "c"]
           (map :test (:failures (tr/report (value {:failures fs}) {:ns "a"})))))))

(def ^:private two-failures
  (tr/report (value {:tests 3 :pass 1 :fail 1 :error 1 :failures [thrown-failure (failure {})]})
             {:ns "demo.core-test"
              :locate {"demo/core_test.clj" "test/demo/core_test.clj"}}))

(deftest text-matches-the-design
  (is (= (str "2 failures in demo.core-test: 3 tests, 3 assertions, 1 passed, 1 failed, 1 error\n"
              "FAIL fails (test/demo/core_test.clj:9) outer > inner: msg\n"
              "  expected: (= 1 2)\n"
              "  actual:   (not (= 1 2))\n"
              "ERROR throws (test/demo/core_test.clj:11)\n"
              "  expected: (= 1 (/ 1 0))\n"
              "  actual:   java.lang.ArithmeticException: Divide by zero\n")
         (tr/text two-failures))))

(deftest text-other-summaries
  (let [text (fn [m opts] (tr/text (tr/report (value m) (merge {:ns "a.b"} opts))))]
    (is (= "All passed in a.b: 3 tests, 4 assertions\n" (text {:tests 3 :pass 4} {})))
    (is (= "All passed in a.b: 1 test, 1 assertion\n" (text {:tests 1 :pass 1} {})))
    (is (= "No tests in a.b.\n" (text {:outcome :no-tests} {})))
    (is (= "No test named t in a.b.\n" (text {:outcome :no-such-test} {:test "t"})))
    (is (str/starts-with? (text {:tests 1 :fail 1 :failures [(failure {})]} {})
                          "1 failure in a.b: 1 test, 1 assertion, 0 passed, 1 failed, 0 errors\n"))
    (is (str/ends-with? (text {:tests 1 :fail 103 :failures [(failure {})] :failures-dropped 3} {})
                        "\n3 more failures not listed.\n"))
    (is (str/includes? (text {:tests 1 :fail 1 :failures [(failure {:context []})]} {})
                       "FAIL fails (demo/core_test.clj:9): msg\n"))))

(deftest data-shape
  (let [d (tr/data two-failures {:output "out\n" :ms 12})
        [f t] (get d "failures")]
    (is (= #{"status" "ns" "counts" "failures" "output" "ms"} (set (keys d))))
    (is (= "failed" (get d "status")))
    (is (= "out\n" (get d "output")))
    (is (= {"test" "fails" "kind" "fail" "file" "test/demo/core_test.clj" "line" 9
            "context" ["outer" "inner"] "message" "msg"
            "expected" "(= 1 2)" "actual" "(not (= 1 2))"}
           f))
    (is (not (contains? t "message")))
    (is (= [] (get t "context")))))

(deftest data-optional-keys
  (let [dropped (tr/report (value {:tests 1 :fail 101 :failures [(failure {})] :failures-dropped 1}) {:ns "a"})
        d (tr/data dropped {:output "" :err "e\n" :dropped 7 :ms 1})
        missing (tr/data (tr/report (value {:outcome :no-such-test}) {:ns "a" :test "t"}) {})]
    (is (= 1 (get d "failures_dropped")))
    (is (= "e\n" (get d "err")))
    (is (= 7 (get d "dropped_chars")))
    (is (= {"message" "No test named t in a."} (get missing "error")))
    (is (= "" (get missing "output")))
    (is (not (contains? missing "ms")))))

(deftest mark-snippet-is-one-qualified-form
  (let [s (tr/mark-snippet {:ns "demo.core-test"})
        form (read-string s)]
    (is (= 'clojure.core/let (first form)))
    (is (str/includes? s "(quote demo.core-test)"))
    (is (str/includes? s ":jonquil/stale"))
    (is (nil? (re-find #"[(\[ ](let|fn|count|keep|when|alter-meta!|vals) " s)))
    (is (thrown? Exception (tr/mark-snippet {:ns "a) (b"})))))

(deftest snippet-drop-marked
  (let [s (tr/snippet {:ns "demo.core-test" :drop-marked? true})]
    (is (str/includes? s ":jonquil/stale"))
    (is (not (str/includes? s ":reload")) "the caller ran the text itself")))

;; Runs clojure.test inside clojure.test, in a namespace of its own.
(deftest mark-then-rerun-drops-the-renamed
  (let [head "(ns jonquil.test-run-test.marked (:require [clojure.test :refer [deftest is]]))\n"
        run! (fn [code] (binding [*ns* *ns*] (load-string code)))
        value (fn [] (tr/read-value (pr-str (run! (tr/snippet {:ns "jonquil.test-run-test.marked"
                                                               :drop-marked? true})))))]
    (try
      (run! (str head "(deftest keep-me (is true))\n(deftest old-name (is (= 1 2)))\n"))
      (is (= 2 (run! (tr/mark-snippet {:ns "jonquil.test-run-test.marked"}))))
      (run! (str head "(deftest keep-me (is true))\n(deftest new-name (is true))\n"))
      (let [v (value)]
        (is (= [2 2 0] ((juxt :tests :pass :fail) v)))
        (is (= :ran (:outcome v))))
      (is (nil? (ns-resolve 'jonquil.test-run-test.marked 'old-name)))
      (is (= 0 (run! (tr/mark-snippet {:ns "jonquil.test-run-test.no-such"}))))
      (finally (remove-ns 'jonquil.test-run-test.marked)))))

(deftest console-lines-for-each-status
  (let [lines (fn [m opts] (tr/console-lines (tr/report (value m) (merge {:ns "a.b"} opts))))]
    (is (= ["; Tests: 2 failures in demo.core-test: 3 tests, 3 assertions, 1 passed, 1 failed, 1 error"
            "; FAIL fails (core_test.clj:9) outer > inner: msg"
            ";   expected: (= 1 2)"
            ";   actual:   (not (= 1 2))"
            "; ERROR throws (core_test.clj:11)"
            ";   expected: (= 1 (/ 1 0))"
            ";   actual:   java.lang.ArithmeticException: Divide by zero"]
           (tr/console-lines two-failures)))
    (is (= ["; Tests: all passed in a.b: 3 tests, 4 assertions"] (lines {:tests 3 :pass 4} {})))
    (is (= ["; Tests: no tests in a.b."] (lines {:outcome :no-tests} {})))
    (is (= ["; Tests: no test named t in a.b."] (lines {:outcome :no-such-test} {:test "t"})))
    (is (= "; 3 more failures not listed."
           (last (lines {:tests 1 :fail 103 :failures [(failure {})] :failures-dropped 3} {}))))))
