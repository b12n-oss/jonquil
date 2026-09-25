(ns jonquil.commands-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.commands :as c]
            [jonquil.edit :as edit]))

(defn- snap
  ([text caret] {:text text :caret caret :sel-start caret :sel-end caret})
  ([text s e] {:text text :caret e :sel-start s :sel-end e}))

(deftest run-selection-text-test
  (testing "a selection wins"
    (is (= "(+ 1 2)" (c/run-selection-text (snap "x (+ 1 2) y" 2 9)))))
  (testing "no selection sends the caret's line"
    (is (= "(inc 1)" (c/run-selection-text (snap "(def a 1)\n(inc 1)\n(dec 2)" 12))))
    (is (= "(def a 1)" (c/run-selection-text (snap "(def a 1)\n(inc 1)" 0))))
    (is (= "(inc 1)" (c/run-selection-text (snap "(def a 1)\n(inc 1)" 10))))
    (is (= "(inc 1)" (c/run-selection-text (snap "(def a 1)\n(inc 1)" 17)))))
  (testing "a blank line sends nothing"
    (is (nil? (c/run-selection-text (snap "(a)\n\n(b)" 4))))
    (is (nil? (c/run-selection-text (snap "" 0))))))

(deftest format-ops-test
  (let [text "(defn f [x]\n(inc x))"]
    (is (= "(defn f [x]\n  (inc x))"
           (edit/apply-edits text (:edits (c/format-all-op (snap text 0))))))
    (is (nil? (c/format-all-op (snap "(defn f [x]\n  (inc x))" 0))) "already formatted"))
  (testing "selection formats only the lines it spans"
    (let [text "(defn a []\n1)\n(defn b []\n2)"
          out (edit/apply-edits text (:edits (c/format-selection-op (snap text 14 26))))]
      (is (= "(defn a []\n1)\n(defn b []\n  2)" out))))
  (testing "no selection formats the caret's line"
    (let [text "(defn a []\n1)"]
      (is (= "(defn a []\n  1)"
             (edit/apply-edits text (:edits (c/format-selection-op (snap text 12)))))))))

(deftest pre-run-problem-test
  (is (nil? (c/pre-run-problem "(defn f [x] (inc x))")))
  (is (= "The ( on line 3 is never closed."
         (c/pre-run-problem "(ok)\n\n(defn f [x]")))
  (testing "an early error form no longer hides the unclosed one"
    (is (= "The ( on line 2 is never closed."
           (c/pre-run-problem "(undefined-fn)\n(defn g [y]"))))
  (testing "a stray closer"
    (is (= "The ) on line 2 has no matching opener."
           (c/pre-run-problem "(+ 1 2)\n(inc 3))"))))
  (testing "brackets in strings and comments don't count"
    (is (nil? (c/pre-run-problem "(println \"(\") ; )")))))

(deftest find-navigation-test
  (let [ms [[0 3] [8 11] [16 19]]]
    (is (= [8 11] (c/next-match ms 4)))
    (is (= [0 3] (c/next-match ms 17)) "wraps to the first")
    (is (= [8 11] (c/prev-match ms 16)))
    (is (= [16 19] (c/prev-match ms 0)) "wraps to the last")
    (is (nil? (c/next-match [] 0)))))

(deftest replace-test
  (let [text "foo bar foo baz foo"
        ms [[0 3] [8 11] [16 19]]]
    (testing "replace-current replaces the selected match and selects the next, shifted"
      (let [r (c/replace-current (snap text 0 3) ms "quux")]
        (is (= "quux bar foo baz foo" (edit/apply-edits text (:edits r))))
        (is (= [9 12] (:sel r)))))
    (testing "a selection that isn't a match changes nothing"
      (is (nil? (c/replace-current (snap text 1 3) ms "x"))))
    (testing "replace-all"
      (let [r (c/replace-all ms "X")]
        (is (= "X bar X baz X" (edit/apply-edits text (:edits r))))
        (is (= 3 (:count r)))))
    (is (nil? (c/replace-all [] "X")))))

(deftest zoom-test
  (is (= 14 (c/zoom 13 :in)))
  (is (= 12 (c/zoom 13 :out)))
  (is (= 32 (c/zoom 32 :in)) "clamps at the top")
  (is (= 9 (c/zoom 9 :out)) "clamps at the bottom")
  (is (= 13 (c/zoom 24 :reset))))
