(ns jonquil.results-rt-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.results-rt :as results-rt]))

(deftest kinds-test
  (is (= :value (:kind (results-rt/keep! 42))))
  (is (= :var (:kind (results-rt/keep! #'clojure.core/map))))
  (is (= :ns (:kind (results-rt/keep! (the-ns 'user)))))
  (is (= :exception (:kind (results-rt/keep! (ex-info "x" {}))))))

(deftest round-trip-and-ring-test
  (let [{:keys [id]} (results-rt/keep! [1 2 3])]
    (is (= [1 2 3] (results-rt/value id)))
    (dotimes [i 100] (results-rt/keep! i))
    (is (= ::results-rt/gone (results-rt/value id)) "the ring keeps the last 100")
    (is (= ::results-rt/gone (results-rt/value -5)))))

(deftest value!-test
  (let [{:keys [id]} (results-rt/keep! :kept)]
    (is (= :kept (results-rt/value! id))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"That result is gone" (results-rt/value! -9))))

(def hits (atom 0))

(deftest a-lazy-result-is-kept-unrealized-test
  (reset! hits 0)
  (let [xs (map (fn [x] (swap! hits inc) x) [1 2 3])
        {:keys [id kind]} (results-rt/keep! xs)]
    (is (= :value kind))
    (is (= 0 @hits))
    (is (identical? xs (results-rt/value id)))))
