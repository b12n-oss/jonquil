(ns jonquil.app-cairo-script-test
  (:require [app-cairo-script :as acs]
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
