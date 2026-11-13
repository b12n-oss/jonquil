(ns jonquil.version-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.version :as version]))

(deftest version-from-describe-test
  (is (= "0.1.0" (version/version-from-describe "v0.1.0")) "a release tag drops its v")
  (is (= "0.1.0-3-gabc1234" (version/version-from-describe "v0.1.0-3-gabc1234")))
  (is (= "abc1234" (version/version-from-describe "abc1234")) "no tag yet: the short SHA")
  (is (= "vfoo" (version/version-from-describe "vfoo")) "only a v before a digit goes"))

(deftest checkout-dir-test
  (is (= "/w/jonquil" (version/checkout-dir "file:/w/jonquil/src/jonquil/version.clj"))
      "a source file's checkout is the dir above src")
  (is (= "/my work/jonquil" (version/checkout-dir "file:/my%20work/jonquil/src/jonquil/version.clj"))
      "percent-escapes are decoded")
  (is (nil? (version/checkout-dir "jar:file:jolt-embedded-resources.jar!/jonquil/version.clj")))
  (is (nil? (version/checkout-dir "file:/w/other/version.clj")) "not under src/jonquil")
  (is (nil? (version/checkout-dir nil))))

(deftest baked-test
  (is (= "9.9.9" (version/baked "9.9.9")))
  (is (nil? (version/baked "")) "an empty JONQUIL_VERSION bakes nothing")
  (is (nil? (version/baked nil))))

(deftest pick-version-test
  (is (= "9.9.9" (version/pick-version {:built "9.9.9" :source "0.1.0-1-gabc"}))
      "a baked version wins over the checkout")
  (is (= "0.1.0-1-gabc" (version/pick-version {:source "0.1.0-1-gabc"}))
      "with nothing baked, the checkout's describe")
  (is (= "dev" (version/pick-version {})) "nothing baked and no git"))

(deftest version-line-test
  (is (= (str "Jonquil " (version/current-version)) (version/version-line)))
  (is (str/starts-with? (version/version-line) "Jonquil ")))
