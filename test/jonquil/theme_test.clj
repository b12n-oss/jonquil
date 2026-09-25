(ns jonquil.theme-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.theme :as theme]))

(def token-kinds
  ;; jonquil.paint/token-styles' keys
  #{:comment :string :regex :char :keyword :number :special-form :builtin :constant :metatag})

(def console-kinds #{:out :err :prompt :result :error :system})

(defn- hex? [s] (boolean (and (string? s) (re-matches #"#[0-9a-f]{6}" s))))

(defn- lin [c] (let [c (/ c 255.0)] (if (<= c 0.03928) (/ c 12.92) (Math/pow (/ (+ c 0.055) 1.055) 2.4))))
(defn- lum [hex]
  (let [n (Long/parseLong (subs hex 1) 16)]
    (+ (* 0.2126 (lin (bit-and (bit-shift-right n 16) 255)))
       (* 0.7152 (lin (bit-and (bit-shift-right n 8) 255)))
       (* 0.0722 (lin (bit-and n 255))))))
(defn- contrast [a b]
  (let [x (lum a) y (lum b)] (/ (+ (max x y) 0.05) (+ (min x y) 0.05))))

(deftest every-theme-is-complete-test
  (doseq [k theme/theme-names
          :let [t (theme/theme k)]]
    (testing (name k)
      (is (= k (:name t)))
      (is (boolean? (:dark? t)))
      (is (every? hex? (map t [:background :text :caret :selection :find-highlight :red])))
      (is (= token-kinds (set (keys (:tokens t)))))
      (is (every? hex? (vals (:tokens t))))
      (is (every? hex? (map #(theme/console-color t %) console-kinds)))
      (is (= 7 (count (:rainbow t))))
      (is (= 7 (count (set (:rainbow t)))))
      (is (every? hex? (:rainbow t)))
      (is (not (contains? (set (:rainbow t)) (:red t)))))))

(deftest dark-flags-test
  (is (= [false true false true] (mapv #(:dark? (theme/theme %)) theme/theme-names))))

(deftest readable-test
  ;; Floors are the measured minimums (2026-09-25). Solarized keeps the
  ;; slides palette's low contrast (text 4.13, lowest token 2.48).
  (doseq [k theme/theme-names
          :let [t (theme/theme k) bg (:background t)]]
    (testing (name k)
      (is (<= 4.0 (contrast (:text t) bg)))
      (is (<= 3.4 (contrast (:text t) (:selection t))))
      (is (<= 3.4 (contrast (:text t) (:find-highlight t))))
      (is (<= 2.4 (apply min (map #(contrast % bg) (concat (vals (:tokens t)) (:rainbow t) [(:red t)]))))))))

(deftest next-theme-test
  (is (= [:dark :solarized :navy :light] (mapv theme/next-theme theme/theme-names)))
  (is (= :dark (theme/next-theme :bogus))))

(deftest theme-fallback-test
  (is (= :light (:name (theme/theme :bogus))))
  (is (= :light (:name (theme/theme nil)))))

(deftest colors-test
  (let [t (theme/theme :navy)]
    (is (= "#c792ea" (theme/token-color t :keyword)))
    (is (= "#c8cad8" (theme/token-color t :symbol)))
    (is (= "#c8cad8" (theme/console-color t :out)))
    (is (= "#f07178" (theme/console-color t :err)))
    (is (= "#f07178" (theme/console-color t :error)))
    (is (= "#82aaff" (theme/console-color t :prompt)))))

(deftest code-css-test
  (let [css (theme/code-css (theme/theme :dark) 15)]
    (is (str/includes? css ".jonquil-code { font-size: 15px;"))
    (is (str/includes? css "background-color: #000000;"))
    (is (str/includes? css "color: #e0e0e0;"))
    (is (str/includes? css "caret-color: #6cb4ff;"))
    (is (str/includes? css ".jonquil-code text selection { background-color: #3e4451;"))))
