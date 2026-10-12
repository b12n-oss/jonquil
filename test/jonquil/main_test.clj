(ns jonquil.main-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.main :as main]))

(deftest gtk-argv-test
  (is (= ["jonquil" "/w/a.clj" "/w/sub"] (main/gtk-argv ["a.clj" "sub"] "/w")))
  (is (= ["jonquil" "/x/b.clj"] (main/gtk-argv ["/x/b.clj"] "/w")))
  (is (= ["jonquil" "/w/-x.clj" "/w/--help"] (main/gtk-argv ["-x.clj" "--help"] "/w")))
  (is (= ["jonquil"] (main/gtk-argv [] "/w"))))
