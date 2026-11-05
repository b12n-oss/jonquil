(ns jonquil.gtk.processes-pane-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.processes-pane :as pp]))

(deftest age-text-test
  (is (= ["0s" "59s" "1m" "59m" "1h" "25h"]
         (mapv pp/age-text [0 59999 60000 3599999 3600000 (* 25 3600000)]))))

(deftest row-text-test
  (is (= "#3  parked  user/worker  12s"
         (pp/row-text {:id 3 :state :parked :label "user/worker" :age-ms 12500}))))

(deftest footer-text-test
  (is (= "No fibers yet: fibers spawned from now on show here." (pp/footer-text [])))
  (is (= "2 fibers, 1 still running. Fibers spawned before the panel first opened aren't listed."
         (pp/footer-text [{:state :parked} {:state :done}])))
  (is (= "1 fiber, 0 still running. Fibers spawned before the panel first opened aren't listed."
         (pp/footer-text [{:state :dead}]))))

(deftest selected-index-test
  (let [fs [{:id 9} {:id 7} {:id 4}]]
    (is (= 1 (pp/selected-index fs 7)))
    (is (= 0 (pp/selected-index fs 3)))
    (is (nil? (pp/selected-index [] 7)))))
