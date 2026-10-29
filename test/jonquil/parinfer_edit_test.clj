(ns jonquil.parinfer-edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.edit :as edit]
            [jonquil.parinfer-edit :as pe]))

(defn- run
  "adjust on the edit from [before bc] to [after ac] in mode, applied:
  [text caret], or nil when adjust has nothing to do."
  [mode before bc after ac]
  (when-let [{:keys [edits caret]} (pe/adjust {:mode mode :prev-text before :prev-caret bc
                                               :text after :caret ac})]
    [(edit/apply-edits after edits) caret]))

(deftest region-follows-brackets-test
  (let [t "(a)\n\n(b\n c)\n(d)"]
    (is (= [5 11] (pe/region t 9)) "the form around the caret, whole lines")
    (is (= [4 4] (pe/region t 4)) "a blank line between forms is its own region"))
  (is (= [0 7] (pe/region "(a) (b)\n(c)" 1)) "forms sharing a line come out as one"))

(deftest region-falls-back-to-indentation-test
  (let [t "(a\n  \"doc\nline\"\n(b"]
    (is (= [0 15] (pe/region t 11)) "a docstring line at column 0 doesn't start a region")
    (is (= [16 18] (pe/region t 17)))))

(deftest adjust-closes-a-new-form-test
  (is (= ["(a)\n()\n(b)" 5] (run :smart "(a)\n\n(b)" 4 "(a)\n(\n(b)" 5))))

(deftest adjust-dedent-moves-closer-test
  (testing "deleting a body line's indent moves the closer up, inside that form only"
    (is (= ["(z)\n(a)\nb\n(y)" 8] (run :smart "(z)\n(a\n  b)\n(y)" 9 "(z)\n(a\nb)\n(y)" 7)))
    (is (= ["(z)\n(a)\nb\n(y)" 8] (run :indent "(z)\n(a\n  b)\n(y)" 9 "(z)\n(a\nb)\n(y)" 7)))))

(deftest adjust-reindents-an-unstable-form-first-test
  (let [before "(z)\n(fn [a b]\n     (h a))\n(q [c]\n     d)"
        after "(z)\n(fn [a b]\n     (h ax))\n(q [c]\n     d)"
        [t c :as r] (run :smart before 24 after 24)]
    (testing "the first edit re-indents the edited form and moves no closer"
      (is (= ["(z)\n(fn [a b]\n    (h ax))\n(q [c]\n     d)" 23] r)))
    (testing "the next edit finds it stable and runs Smart, which has nothing to change"
      (is (nil? (run :smart t c (str (subs t 0 c) "y" (subs t c)) (inc c)))))))

(deftest adjust-opens-a-paren-in-an-unstable-form-test
  (testing "the indentation is fixed, then the keystroke runs Smart, so ( closes;
           Paren mode alone fails here, and the next edit would find the form unbalanced"
    (is (= ["(z)\n(fn [a b]\n    (h (a)))" 22]
           (run :smart "(z)\n(fn [a b]\n     (h a))" 22 "(z)\n(fn [a b]\n     (h (a))" 23)))))

(deftest adjust-crlf-test
  (is (= ["(a)\r\n()\r\n(b)" 6] (run :smart "(a)\r\n\r\n(b)" 5 "(a)\r\n(\r\n(b)" 6)))
  (is (= ["(a)\n()\n(b)" 5] (run :smart "(a)\n\n(b)" 4 "(a)\n(\n(b)" 5))))

(deftest adjust-nil-cases-test
  (is (nil? (run :smart "(a)" 1 "(a)" 1)) "no change")
  (is (nil? (run :smart "(a b)" 2 "(a xb)" 4)) "Parinfer has nothing to fix")
  (is (nil? (run :paren "(a)\n" 4 "(a)\n(" 5)) "Paren mode fails on an unclosed form"))

(deftest adjust-paste-spans-forms-test
  (is (= ["(a)\n(c)\n(d)\n(b)" 10] (run :smart "(a)\n\n(b)" 4 "(a)\n(c\n(d\n(b)" 9))))

(deftest adjust-skips-a-form-over-max-lines-test
  (testing "a form longer than :max-lines is left as typed"
    (is (nil? (pe/adjust {:mode :smart :max-lines 1
                          :prev-text "(z)\n(a\n  b)\n(y)" :prev-caret 9
                          :text "(z)\n(a\nb)\n(y)" :caret 7})))
    (is (some? (pe/adjust {:mode :smart :max-lines 2
                           :prev-text "(z)\n(a\n  b)\n(y)" :prev-caret 9
                           :text "(z)\n(a\nb)\n(y)" :caret 7})))))
