(ns jonquil.smoke-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.core :as core]))

(deftest app-name-test
  (is (= "Jonquil" core/app-name)))
