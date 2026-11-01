(ns jonquil.restarts-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.restarts :as restarts]))

(def ^:private in-process
  ;; console/error-text of an in-process eval of (greet-xyz 1), measured
  "Error [clojure.lang.ExceptionInfo]: Unable to resolve symbol: greet-xyz in this context\nLocation: line 1, col 1")

(def ^:private jvm
  ;; JVM Clojure 1.12.3, measured: the CompilerException and its cause
  "Syntax error compiling at (1:1).\nUnable to resolve symbol: undefined-xyz in this context")

(deftest missing-name-test
  (is (= "greet-xyz" (restarts/missing-name in-process)))
  (is (= "undefined-xyz" (restarts/missing-name jvm)))
  (is (nil? (restarts/missing-name "Error [java.lang.ArithmeticException]: Divide by zero")))
  (is (nil? (restarts/missing-name "Unable to resolve symbol: str/foo in this context")) "qualified")
  (is (nil? (restarts/missing-name nil))))

(deftest options-test
  (testing "a REPL entry, Run Selection or Run Form: all three, define when a name is missing"
    (doseq [kind [:repl :selection :form]]
      (is (= [{:id :retry :label "Retry"} {:id :edit :label "Edit and retry"}
              {:id :define :label "Define greet-xyz and retry"}]
             (restarts/options {:kind kind :code "(greet-xyz 1)" :error-text in-process})))
      (is (= [{:id :retry :label "Retry"} {:id :edit :label "Edit and retry"}]
             (restarts/options {:kind kind :code "(/ 1 0)" :error-text "Divide by zero"})))))
  (testing "a Run: retry only"
    (is (= [{:id :retry :label "Retry"}]
           (restarts/options {:kind :run :code "(greet-xyz 1)" :error-text in-process}))))
  (testing "no failure"
    (is (= [] (restarts/options nil)))))

(deftest define-input-test
  (is (= {:text "(def greet )\n(greet 1)" :caret 11} (restarts/define-input "greet" "(greet 1)"))))

(def ran
  ;; public on purpose: a #= payload must be able to reach it for the test to mean anything
  (atom 0))

(deftest missing-name-never-evaluates-test
  ;; #= in error text must not run in the IDE (the reader's read-eval); the
  ;; commas make the whole call one \S+ token for the regex
  (is (nil? (restarts/missing-name "Unable to resolve symbol: #=(swap!,jonquil.restarts-test/ran,inc) in this context")))
  (is (zero? @ran) "nothing in the error text ran")
  (is (nil? (restarts/missing-name "Unable to resolve symbol: foo(bar) in this context")))
  (is (= "foo-bar?" (restarts/missing-name "Unable to resolve symbol: foo-bar? in this context"))))

(deftest record-failure-test
  (let [a (atom nil)
        where {:kind :repl :code "(f)" :tab-id 3}]
    (restarts/record-failure! a where {:status :error} (constantly "boom"))
    (is (= {:kind :repl :code "(f)" :tab-id 3 :error-text "boom"} @a))
    (restarts/record-failure! a where {:status :interrupted} (constantly "x"))
    (is (some? @a) "a Stop leaves the failure as it was")
    (restarts/record-failure! a where {:status :ok} (constantly "x"))
    (is (nil? @a) "a success clears it")
    (testing "a throwing error-text never escapes: the failure is still recorded"
      (is (nil? (restarts/record-failure! a where {:status :error} (fn [_] (throw (Exception. "bad result"))))))
      (is (= {:kind :repl :code "(f)" :tab-id 3 :error-text nil} @a)))))
