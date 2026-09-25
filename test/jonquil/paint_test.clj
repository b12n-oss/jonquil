(ns jonquil.paint-test
  "Upstream's three Swing-bound highlighting tests (syntax_test.clj at
  41c56b8: highlight-doc-test, setup-syntax-highlighting-test,
  rainbow-parentheses-test), re-expressed against the paint plan. Colours
  are the light theme's (jonquil.theme); the kinds and flags are
  upstream's."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.paint :as paint]
            [jonquil.syntax :as syntax]
            [jonquil.theme :as theme]))

(def light (theme/theme :light))

(deftest highlight-doc-test
  (let [code "(defn greet [name]\n  ; greeting\n  (println (str \"Hello, \" name)))\n(greet \"World\")"
        plan (paint/paint-plan code)]
    (is (= {:fg "#cf222e" :bold true} (paint/style-at light plan 1)))
    (is (= {:fg "#8c95a0" :italic true} (paint/style-at light plan (str/index-of code "; greeting"))))
    (is (= {:fg "#953800"} (paint/style-at light plan (str/index-of code "println"))))
    (is (= {:fg "#0a3069"} (paint/style-at light plan (str/index-of code "\"Hello, \""))))))

(deftest def-is-special-form-test
  (is (= {:fg "#cf222e" :bold true} (paint/style-at light (paint/paint-plan "(def a 10)") 1))))

(deftest rainbow-parentheses-test
  (testing "7 levels applied to nested brackets"
    (let [code "([{[({[()]})]}])"
          plan (paint/paint-plan code)
          matches (:matches (syntax/compute-brackets code))]
      (dotimes [depth 8]
        (is (= {:fg (nth (:rainbow light) (mod depth 7)) :bold true} (paint/style-at light plan depth)))
        (is (= {:fg (nth (:rainbow light) (mod depth 7)) :bold true}
               (paint/style-at light plan (get matches depth)))))))
  (testing "unmatched bracket takes the theme's red"
    (is (= {:fg "#cf222e" :bold true} (paint/style-at light (paint/paint-plan "(foo))") 5)))))

(deftest every-kind-has-a-colour-in-every-theme-test
  (doseq [k theme/theme-names
          :let [t (theme/theme k)]
          kind (keys paint/token-styles)]
    (is (re-matches #"#[0-9a-f]{6}" (:fg (paint/style t kind))) (str k " " kind)))
  (is (= paint/rainbow-count (count (:rainbow light)))))

(deftest empty-and-plain-text-test
  (is (= [] (paint/paint-plan "")))
  (is (= [] (paint/paint-plan nil)))
  (is (nil? (paint/style-at light (paint/paint-plan "abc") 0))))
