(ns jonquil.docs-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.docs :as docs]))

(def sandbox (create-ns 'jonquil.docs-test.sandbox))

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

(deftest removed-namespace-falls-back-to-user-test
  (docs/clear-doc-cache!)
  (is (= :found (:status (docs/symbol-doc 'no.such.ns "map" "" 0))))
  (is (some #(= "map" (:symbol %)) (docs/candidates 'no.such.ns "ma" "" 0))))

(deftest program-namespace-test
  (docs/clear-doc-cache!)
  (create-ns 'p4.docs.demo)
  (binding [*ns* (the-ns 'p4.docs.demo)]
    (eval '(clojure.core/refer-clojure))
    (eval '(defn greet "Says hi." [name] (str "hi " name))))
  (let [d (docs/symbol-doc 'p4.docs.demo "greet" "" 0)]
    (is (= :found (:status d)))
    (is (re-find #"Says hi" (str (:doc d)))))
  (is (some #(= "greet" (:symbol %)) (docs/candidates 'p4.docs.demo "gr" "" 0)))
  (remove-ns 'p4.docs.demo))

(deftest memo-is-keyed-by-namespace-and-text-test
  (docs/clear-doc-cache!)
  (let [t1 "(defn zork \"one\" [a] a)"
        t2 "(defn zork \"two\" [a] a)"]
    (is (not= (docs/symbol-doc 'user "zork" t1 0) (docs/symbol-doc 'user "zork" t2 0))
        "a changed buffer isn't served the old doc")))
