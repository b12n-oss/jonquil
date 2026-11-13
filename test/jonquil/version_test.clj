(ns jonquil.version-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.version :as version]))

(deftest version-from-describe-test
  (is (= "0.1.0" (version/version-from-describe "v0.1.0")) "a release tag drops its v")
  (is (= "0.1.0-3-gabc1234" (version/version-from-describe "v0.1.0-3-gabc1234")))
  (is (= "abc1234" (version/version-from-describe "abc1234")) "no tag yet: the short SHA")
  (is (= "vfoo" (version/version-from-describe "vfoo")) "only a v before a digit goes"))

(deftest version-is-a-non-empty-string
  (is (string? version/version))
  (is (seq version/version)))

(deftest version-line-test
  (is (= (str "Jonquil " version/version) (version/version-line)))
  (is (str/starts-with? (version/version-line) "Jonquil ")))
