(ns jonquil.plugin-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.edit :as edit]
            [jonquil.plugin :as p]))

(deftest defplugin-test
  (testing "inside a load, each call is recorded in order, with its line"
    (let [defs (binding [p/*definitions* (atom [])]
                 (p/defplugin {:id :a})
                 (p/defplugin {:id :b})
                 @p/*definitions*)]
      (is (= [:a :b] (map :id defs)))
      (is (every? #(let [l (::p/line %)] (or (nil? l) (integer? l))) defs))))
  (testing "a non-map argument is recorded as it is, for the validator to name"
    (let [defs (binding [p/*definitions* (atom [])] (p/defplugin [1 2]) @p/*definitions*)]
      (is (= [[1 2]] defs))))
  (testing "outside a load it throws"
    (let [e (try (p/defplugin {:id :a}) nil (catch :default e e))]
      (is (some? e))
      (is (true? (:plugin/outside-load (ex-data e)))))))

(defn- snap [text caret] {:text text :caret caret :sel-start caret :sel-end caret})

(deftest form-at-caret-test
  (is (= {:text "(a (b c))" :start 0 :end 9} (p/form-at-caret (snap "(a (b c))" 4))))
  (is (nil? (p/form-at-caret (snap "   " 1))))
  (is (nil? (p/form-at-caret (snap "(a)\n\n(b)" 4))) "between forms"))

(deftest wrap-form-test
  (let [s (snap "(a b)" 1)
        op (p/wrap-form s "(when " ")")]
    (is (= "(when (a b))" (edit/apply-edits (:text s) (:edits op))))
    (is (= 7 (:caret op))))
  (is (nil? (p/wrap-form (snap "(a)\n\n(b)" 4) "(when " ")"))))
