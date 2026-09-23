(ns jonquil.paint-test
  "Upstream's three Swing-bound highlighting tests (syntax_test.clj at
  41c56b8: highlight-doc-test, setup-syntax-highlighting-test,
  rainbow-parentheses-test), re-expressed against the paint plan."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.paint :as paint]
            [jonquil.syntax :as syntax]))

(deftest highlight-doc-test
  (let [code "(defn greet [name]\n  ; greeting\n  (println (str \"Hello, \" name)))\n(greet \"World\")"
        plan (paint/paint-plan code)]
    (is (= {:fg "#0000cd" :bold true} (paint/style-at plan 1)))
    (is (= {:fg "#6a737d" :italic true} (paint/style-at plan (str/index-of code "; greeting"))))
    (is (= {:fg "#005a9e"} (paint/style-at plan (str/index-of code "println"))))
    (is (= {:fg "#22863a"} (paint/style-at plan (str/index-of code "\"Hello, \""))))))

(deftest def-is-special-form-test
  (is (= {:fg "#0000cd" :bold true} (paint/style-at (paint/paint-plan "(def a 10)") 1))))

(deftest rainbow-parentheses-test
  (testing "rainbow definitions"
    (is (= 7 (count paint/rainbow)))
    (is (= 7 (count (set paint/rainbow)))))
  (testing "7 levels applied to nested brackets"
    (let [code "([{[({[()]})]}])"
          plan (paint/paint-plan code)
          matches (:matches (syntax/compute-brackets code))]
      (dotimes [depth 8]
        (is (= {:fg (nth paint/rainbow (mod depth 7)) :bold true} (paint/style-at plan depth)))
        (is (= {:fg (nth paint/rainbow (mod depth 7)) :bold true}
               (paint/style-at plan (get matches depth)))))))
  (testing "unmatched bracket"
    (is (= {:fg "#dc0000" :bold true} (paint/style-at (paint/paint-plan "(foo))") 5)))))

(deftest empty-and-plain-text-test
  (is (= [] (paint/paint-plan "")))
  (is (= [] (paint/paint-plan nil)))
  (is (nil? (paint/style-at (paint/paint-plan "abc") 0))))
