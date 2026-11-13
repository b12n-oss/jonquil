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

(deftest pick-version-test
  (is (= "9.9.9" (version/pick-version {:checkout "/w" :override "9.9.9" :describe "0.1.0-1-gabc" :compiled "x"}))
      "from source the override wins")
  (is (= "0.1.0-1-gabc" (version/pick-version {:checkout "/w" :describe "0.1.0-1-gabc" :compiled "stale"}))
      "from source the checkout's describe beats the compiled string")
  (is (= "dev" (version/pick-version {:checkout "/w" :compiled "stale"})) "source, no git")
  (is (= "0.1.0" (version/pick-version {:override "9.9.9" :describe "1.1.1" :compiled "0.1.0"}))
      "a built binary keeps its compile-time string and ignores the environment"))

(deftest version-is-a-non-empty-string
  (is (string? version/version))
  (is (seq version/version)))

(deftest version-line-test
  (is (= (str "Jonquil " (version/current-version)) (version/version-line)))
  (is (str/starts-with? (version/version-line) "Jonquil ")))
