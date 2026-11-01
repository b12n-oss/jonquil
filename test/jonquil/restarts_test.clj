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
