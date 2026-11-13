(ns jonquil.app-cairo-script-test
  (:require [app-cairo-script :as acs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest the-pin-test
  (is (= {:version "1.18.6"
          :url "https://cairographics.org/releases/cairo-1.18.6.tar.xz"
          :sha256 "1c767308174337a74694da0f3ec069c271452163a1ef4540964c50c301f157d4"}
         acs/PIN)))

(deftest keg-mismatch-test
  (is (nil? (acs/keg-mismatch "1.18.6")))
  (is (nil? (acs/keg-mismatch "1.18.6_1")) "a Homebrew revision is the same source")
  (is (= "Homebrew's cairo is 1.18.8 but the bundled script interpreter is pinned to cairo 1.18.6: update app-cairo-script/PIN (version, url, sha256)"
         (acs/keg-mismatch "1.18.8"))))

(deftest the-pin-is-checked-even-with-a-cached-build-test
  (is (= :use-cache (acs/cache-decision "1.18.6" true)))
  (is (= :build (acs/cache-decision "1.18.6_1" false)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"update app-cairo-script/PIN"
                        (acs/cache-decision "1.18.8" true))
      "a cached 1.18.6 build never goes beside a newer Homebrew cairo"))

(deftest the-note-lists-every-build-option-test
  (let [n (acs/build-note)]
    (is (str/includes? n "https://cairographics.org/releases/cairo-1.18.6.tar.xz"))
    (doseq [o ["-Dlzo=disabled" "-Dxlib=disabled" "-Dxcb=disabled" "-Dfreetype=enabled" "-Dfontconfig=enabled" "--buildtype=release"]]
      (is (str/includes? n o) o))))
