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

(deftest rebuild-test
  (is (false? (pp/rebuild? [{:id 1 :state :parked :age-ms 1000}] [{:id 1 :state :parked :age-ms 2000}]))
      "only the age moved: the rows are relabelled in place")
  (is (true? (pp/rebuild? [{:id 1 :state :parked}] [{:id 1 :state :dead}])))
  (is (true? (pp/rebuild? [{:id 1 :state :parked}] [{:id 2 :state :parked} {:id 1 :state :parked}]))))

(deftest refresh-problem-test
  (is (= :status (pp/no-jolt-goes-to true)) "a person asked: the status bar")
  (is (= :footer (pp/no-jolt-goes-to false)) "the timer: the panel's own footer, never the status bar"))

