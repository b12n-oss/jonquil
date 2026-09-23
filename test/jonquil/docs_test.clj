(ns jonquil.docs-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.docs :as docs]))

(def ^:once sandbox (create-ns 'jonquil.docs-test.sandbox))

(defn setup-sandbox []
  (binding [*ns* sandbox]
    (refer-clojure)
    (load-string "(defn hello \"Says hi.\" [who] who)")))

(deftest symbol-doc-custom-namespace-test
  (setup-sandbox)
  (let [result (docs/symbol-doc 'jonquil.docs-test.sandbox "hello" nil nil)]
    (is (= :found (:status result)))
    (is (= "Says hi." (:doc result)))
    (is (= "([who])" (:arglists result)))))

(deftest symbol-doc-core-test
  (let [result (docs/symbol-doc 'jonquil.docs-test.sandbox "map" nil nil)]
    (is (= :found (:status result)))
    (is (= "clojure.core" (:ns result)))))

(deftest symbol-doc-special-form-test
  (let [result (docs/symbol-doc 'jonquil.docs-test.sandbox "if" nil nil)]
    (is (= true (:special-form result)))))

(deftest candidates-test
  (setup-sandbox)
  (let [results (docs/candidates 'jonquil.docs-test.sandbox "hel" "" 0)]
    (is (= "hello" (:symbol (first results))))))

(deftest missing-namespace-throws-test
  (is (thrown? Exception (docs/symbol-doc 'no.such.ns "map" nil nil))))
