(ns jonquil.results-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.results :as results]))

(deftest verbs-test
  (is (= [:inspect :copy :define :jump] (results/verbs :var)))
  (is (= [:inspect :copy :define] (results/verbs :value)))
  (is (= [:inspect :copy :define] (results/verbs :exception)))
  (is (= [:inspect :copy :define] (results/verbs :ns))))

(deftest labels-test
  (is (= "Jump to This Var" (results/label :jump)))
  (is (= "Inspect This Result" (results/label :inspect))))

(deftest define-input-test
  (is (= {:text "(def  (jonquil.results-rt/value! 7 \"e1\"))" :caret 5} (results/define-input {:id 7 :epoch "e1"}))))

(deftest offered-test
  (is (results/offered? :jump :var))
  (is (not (results/offered? :jump :value)))
  (is (every? #(results/offered? % :exception) [:inspect :copy :define])))

(deftest var-name-test
  (is (= "clojure.core/map" (results/var-name "=> #'clojure.core/map\n")))
  (is (nil? (results/var-name "=> 42\n"))))

(deftest copy-text-test
  (is (= "#'clojure.core/map" (results/copy-text "=> #'clojure.core/map\n")))
  (is (= "Divide by zero" (results/copy-text "Divide by zero\n"))))
