(ns jonquil.changed-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.changed :as changed]))

(defn- texts [text forms] (mapv (fn [{:keys [start end]}] (subs text start end)) forms))

(deftest keys-ignore-whitespace-and-comments-test
  (let [a "(defn f [x]\n  x) ; hi"
        b "(defn f [x] x)"
        k (fn [t] (:key (first (changed/forms t))))]
    (is (= (k a) (k b)))
    (is (not= (k b) (k "(defn f [x] (inc x))")))
    (is (not= (k "(def s \"a b\")") (k "(def s \"a  b\")")) "a string's text is a token")))

(deftest forms-skip-comment-blocks-test
  (let [t "(def a 1)\n(comment (a) (b))\n#_(def c 3)\n^:private (def d 4)"]
    (is (= ["(def a 1)" "^:private (def d 4)"] (texts t (changed/forms t))))))

(deftest changed-test
  (let [t "(defn f [] 1)\n\n(defn g [] 2)"
        ran (changed/record nil t :run)]
    (is (= [] (changed/changed t nil)) "a tab that never ran marks nothing")
    (is (= [] (changed/changed t ran)))
    (let [t2 "(defn f [] 1)\n\n(defn g [] 3)"]
      (is (= ["(defn g [] 3)"] (texts t2 (changed/changed t2 ran))))
      (testing "Run Form adds the forms it sent"
        (is (= [] (changed/changed t2 (changed/record ran "(defn g [] 3)" :form))))))
    (testing "reindenting changes nothing"
      (is (= [] (changed/changed "(defn f []\n  1)\n(defn g [] 2)" ran))))))

(deftest identical-forms-test
  (let [t "(def x 1)\n(def x 1)"
        ran (changed/record nil t :run)
        t2 "(def x 1)\n(def x 2)"]
    (is (= [[10 19]] (mapv (juxt :start :end) (changed/changed t2 ran))))))

(deftest reader-macros-count-test
  ;; the tokenizer leaves ' @ ` ~ ~@ and a dispatch # out of its tokens; a
  ;; change to any of them is a change to the code
  (let [k (fn [t] (:key (first (changed/forms t))))]
    (doseq [[a b] [["(def a 'x)" "(def a x)"]
                   ["(f @a)" "(f a)"]
                   ["(def s #{1})" "(def s {1})"]
                   ["(def g #(inc %))" "(def g (inc %))"]
                   ["(defmacro m [b] `(f ~b))" "(defmacro m [b] (f b))"]
                   ["(defmacro m [b] `(f ~@b))" "(defmacro m [b] `(f ~b))"]
                   ["(def c #?(:clj 1))" "(def c ?(:clj 1))"]]]
      (is (not= (k a) (k b)) (str a " vs " b)))
    (is (= (k "(def a [1 2])") (k "(def a [1,  2])")) "commas are whitespace")))

(deftest a-rerun-definition-replaces-its-record-test
  ;; Run, then Run Form an edit, then undo it: the buffer's f differs from
  ;; the f that ran last, so it is marked
  (let [t1 "(defn f [] 1)\n(defn g [] 2)"
        ran (-> (changed/record nil t1 :run)
                (changed/record "(defn f [] 2)" :form))]
    (is (= ["(defn f [] 1)"] (texts t1 (changed/changed t1 ran))))
    (is (= [] (changed/changed "(defn f [] 2)\n(defn g [] 2)" ran)))
    (testing "forms that define nothing just add"
      (let [r2 (changed/record ran "(println :hi)" :form)]
        (is (= [] (changed/changed "(defn f [] 2)\n(defn g [] 2)" r2)))))))
