(ns jonquil.version.describe-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [jonquil.version.describe :as describe]))

(deftest version-from-describe-test
  (is (= "0.1.0" (describe/version-from-describe "v0.1.0")) "a release tag drops its v")
  (is (= "0.1.0-3-gabc1234" (describe/version-from-describe "v0.1.0-3-gabc1234")))
  (is (= "abc1234" (describe/version-from-describe "abc1234")) "no tag yet: the short SHA")
  (is (= "vfoo" (describe/version-from-describe "vfoo")) "only a v before a digit goes"))

(deftest checkout-dir-test
  (is (= "/w/jonquil" (describe/checkout-dir "file:/w/jonquil/src/jonquil/version.clj"))
      "a source file's checkout is the dir above src")
  (is (= "/x" (describe/checkout-dir "file:///x/src/jonquil/version.clj")) "empty authority")
  (is (= "/my work/jonquil" (describe/checkout-dir "file:/my%20work/jonquil/src/jonquil/version.clj")))
  (is (= "/tmp/café" (describe/checkout-dir "file:/tmp/caf%C3%A9/src/jonquil/version.clj"))
      "multi-byte escapes decode as UTF-8")
  (is (nil? (describe/checkout-dir "jar:file:jolt-embedded-resources.jar!/jonquil/version.clj")))
  (is (nil? (describe/checkout-dir "file:/w/other/version.clj")) "not under src/jonquil")
  (is (nil? (describe/checkout-dir "file:/w/%zz/src/jonquil/version.clj")) "a bad escape is nil, not a throw")
  (is (nil? (describe/checkout-dir nil))))

(deftest git-describe-never-throws-test
  (is (nil? (describe/git-describe "/nonexistent/dir/for/jonquil")))
  (let [d (doto (java.io.File/createTempFile "jq-nogit" "") (.delete) (.mkdirs))]
    (try
      (is (nil? (describe/describe-checkout (.getPath d))) "no .git: nothing, even inside another repo")
      (finally (.delete d))))
  (is (nil? (describe/describe-checkout nil))))

(deftest describe-checkout-needs-its-own-git-test
  (is (string? (describe/describe-checkout ".")) "this checkout has a .git (dir or worktree file)")
  (is (.exists (io/file "." ".git"))))

(deftest build-env-test
  (let [no-git (fn [] nil)
        git (fn [] "0.1.0-2-gabc")]
    (is (= {"JONQUIL_VERSION" "9.9.9"} (describe/build-env "9.9.9" git)) "the env value wins")
    (is (= {"JONQUIL_VERSION" "0.1.0-2-gabc"} (describe/build-env "" git)) "empty counts as unset")
    (is (= {"JONQUIL_VERSION" "0.1.0-2-gabc"} (describe/build-env nil git)))
    (is (= {} (describe/build-env nil no-git)) "neither: bake nothing")
    (is (= "9.9.9" (describe/build-version "9.9.9" no-git)))
    (is (= "0.1.0-2-gabc" (describe/build-version nil git)))))

(deftest baked-test
  (is (= "9.9.9" (describe/baked "9.9.9")))
  (is (nil? (describe/baked "")))
  (is (nil? (describe/baked nil))))
