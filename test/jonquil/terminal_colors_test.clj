(ns jonquil.terminal-colors-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.terminal-colors :as tc]
            [jonquil.theme :as theme]))

(deftest palette-shape-test
  (doseq [p [tc/LIGHT tc/DARK]]
    (is (= 16 (count p)))
    (is (every? #(re-matches #"#[0-9a-f]{6}" %) p)))
  (is (= tc/DARK (tc/palette true)))
  (is (= tc/LIGHT (tc/palette false))))

(deftest colors-test
  (let [t (theme/theme :dark)]
    (is (= {:foreground (:text t) :background (:background t) :palette tc/DARK} (tc/colors t))))
  (is (= tc/LIGHT (:palette (tc/colors (theme/theme :light))))))

(deftest font-test
  (is (= "Monospace 13px" (tc/font 13)))
  (is (= "Monospace 20px" (tc/font 20))))

(deftest contrast-test
  (is (< 20.9 (tc/contrast "#000000" "#ffffff") 21.1))
  (is (= 1.0 (tc/contrast "#336699" "#336699"))))

(deftest every-colour-reads-on-every-theme-test
  (testing "each palette colour has a contrast of 3 or more on each theme's background"
    (doseq [[k t] theme/themes
            [i c] (map-indexed vector (tc/palette (:dark? t)))
            :when (not= i (tc/background-end (:dark? t)))]
      (is (<= 3.0 (tc/contrast c (:background t)))
          (str "colour " i " " c " on " k " " (:background t) ": "
               (format "%.2f" (double (tc/contrast c (:background t))))))))
  (testing "the background end is black on a dark theme and bright white on a light one"
    (is (= "#241f31" (nth tc/DARK (tc/background-end true))))
    (is (= "#ffffff" (nth tc/LIGHT (tc/background-end false))))))
