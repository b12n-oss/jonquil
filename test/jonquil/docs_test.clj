(ns jonquil.docs-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.docs :as docs]
            [jonquil.syntax :as syntax]))

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

(deftest the-same-name-in-two-namespaces-keeps-its-own-doc-test
  ;; The cache upstream's syntax kept was keyed by name alone, so the second
  ;; namespace was served the first one's doc.
  (docs/clear-doc-cache!)
  (doseq [[n doc] [['p4.docs.twin-a "From a."] ['p4.docs.twin-b "From b."]]]
    (create-ns n)
    (binding [*ns* (the-ns n)]
      (eval '(clojure.core/refer-clojure))
      (eval (list 'defn 'twin doc '[x] 'x))))
  (try
    (is (= "From a." (:doc (docs/symbol-doc 'p4.docs.twin-a "twin" "" 0))))
    (is (= "From b." (:doc (docs/symbol-doc 'p4.docs.twin-b "twin" "" 0))) "not a's cached doc")
    (is (= "From a." (:doc (docs/symbol-doc 'p4.docs.twin-a "twin" "" 0))) "and a's is still a's")
    (finally (remove-ns 'p4.docs.twin-a) (remove-ns 'p4.docs.twin-b))))

(deftest a-failed-lookup-is-not-cached-test
  (docs/clear-doc-cache!)
  (let [calls (atom 0)]
    (with-redefs [syntax/get-symbol-doc (fn [& _] (swap! calls inc) (throw (ex-info "boom" {})))]
      (is (nil? (docs/symbol-doc 'user "zork" "x" 0))))
    (with-redefs [syntax/get-symbol-doc (fn [& _] (swap! calls inc) {:status :found :doc "back"})]
      (is (= "back" (:doc (docs/symbol-doc 'user "zork" "x" 0))) "the next lookup asks again")
      (is (= "back" (:doc (docs/symbol-doc 'user "zork" "x" 0)))))
    (is (= 2 @calls) "a lookup that finished is cached")))

(deftest a-lookup-overtaken-by-a-clear-is-not-cached-test
  ;; A lookup that started before a clear (a Run redefining the name) mustn't
  ;; store its answer after it.
  (docs/clear-doc-cache!)
  (let [answer (atom "old")]
    (with-redefs [syntax/get-symbol-doc (fn [& _]
                                          (let [a @answer]
                                            (when (= a "old") (docs/clear-doc-cache!) (reset! answer "new"))
                                            {:status :found :doc a}))]
      (is (= "old" (:doc (docs/symbol-doc 'user "zork" "y" 0))) "the caller still gets its answer")
      (is (= "new" (:doc (docs/symbol-doc 'user "zork" "y" 0))) "the next lookup isn't served it"))))

(deftest a-full-memo-restarts-with-the-new-entry-test
  ;; At 256 entries the next store drops them all and keeps only itself.
  (docs/clear-doc-cache!)
  (let [calls (atom 0)]
    (with-redefs [syntax/get-symbol-doc (fn [n & _] (swap! calls inc) {:status :found :doc n})]
      (dotimes [i 256] (docs/symbol-doc 'user (str "n" i) "" 0))
      (is (= 256 @calls))
      (docs/symbol-doc 'user "n255" "" 0)
      (is (= 256 @calls) "a full memo still serves its entries")
      (docs/symbol-doc 'user "extra" "" 0)
      (is (= 257 @calls))
      (docs/symbol-doc 'user "extra" "" 0)
      (is (= 257 @calls) "the new entry is kept")
      (docs/symbol-doc 'user "n0" "" 0)
      (is (= 258 @calls) "the older entries are gone"))))
