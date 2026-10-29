(ns jonquil.syntax-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.syntax :as syntax]))

(def ^:private sample
  "(defn greet [name]\n  (println \"hi\" name)) ; done\n{:a [1 2]}")

(deftest tokenize-reuses-identical-text-test
  (testing "the same string object gets the same result object back"
    (let [t sample]
      (is (identical? (syntax/tokenize t) (syntax/tokenize t))))))

(deftest compute-brackets-reuses-identical-text-test
  (testing "the same string object gets the same result object back"
    (let [t sample]
      (is (identical? (syntax/compute-brackets t) (syntax/compute-brackets t))))))

(deftest compute-brackets-equal-reuses-equal-text-test
  (testing "an equal copy of the cached text gets the cached result, without a new scan"
    (let [copy (str (StringBuilder. ^String sample))
          r (syntax/compute-brackets sample)
          scans (atom 0)
          real @#'syntax/compute-brackets*]
      (with-redefs-fn {#'syntax/compute-brackets* (fn [t] (swap! scans inc) (real t))}
        (fn []
          (is (identical? r (syntax/compute-brackets-equal copy)))
          (is (zero? @scans)))))
    (testing "different text is scanned and becomes the cached entry"
      (let [other "(x [y])"]
        (is (= (syntax/compute-brackets other) (syntax/compute-brackets-equal (str (StringBuilder. other)))))))))

(deftest tokenize-equal-but-distinct-text-test
  (testing "an equal copy of the text recomputes an equal result"
    (let [copy (str (StringBuilder. ^String sample))]
      (is (not (identical? copy sample)))
      (is (= (syntax/tokenize sample) (syntax/tokenize copy))))))

(deftest tokenize-nil-test
  (testing "nil still tokenises to an empty vector, not a cached value"
    (is (= [] (syntax/tokenize nil)))))
