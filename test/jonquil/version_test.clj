(ns jonquil.version-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.version :as version]))

(deftest pick-version-test
  (is (= "9.9.9" (version/pick-version {:built "9.9.9" :source "0.1.0-1-gabc"}))
      "a baked version wins over the checkout")
  (is (= "0.1.0-1-gabc" (version/pick-version {:source "0.1.0-1-gabc"}))
      "with nothing baked, the checkout's describe")
  (is (= "dev" (version/pick-version {})) "nothing baked and no git"))

(deftest version-line-test
  (is (str/starts-with? (version/version-line) "Jonquil "))
  (is (seq (str/trim (subs (version/version-line) (count "Jonquil "))))
      "a version follows the name"))
