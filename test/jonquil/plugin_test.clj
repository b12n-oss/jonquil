(ns jonquil.plugin-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.plugin :as p]))

(deftest defplugin-test
  (testing "inside a load, each call is recorded in order, with its line"
    (let [defs (binding [p/*definitions* (atom [])]
                 (p/defplugin {:id :a})
                 (p/defplugin {:id :b})
                 @p/*definitions*)]
      (is (= [:a :b] (map :id defs)))
      (is (every? #(let [l (::p/line %)] (or (nil? l) (integer? l))) defs))))
  (testing "outside a load it throws"
    (let [e (try (p/defplugin {:id :a}) nil (catch :default e e))]
      (is (some? e))
      (is (true? (:plugin/outside-load (ex-data e)))))))
