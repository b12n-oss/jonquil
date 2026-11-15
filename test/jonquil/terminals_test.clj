(ns jonquil.terminals-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.terminals :as terminals]))

(deftest label-test
  (is (= "Terminal 1" (terminals/label 1)))
  (is (= "Terminal 12" (terminals/label 12))))

(deftest next-number-test
  (is (= 1 (terminals/next-number [])))
  (is (= 2 (terminals/next-number [1])))
  (testing "a closed number in the middle isn't used again while a higher one is open"
    (is (= 4 (terminals/next-number [1 3]))))
  (testing "closing every terminal starts the count again"
    (is (= 1 (terminals/next-number nil)))))

(deftest target-test
  (testing "the terminal whose tab is showing"
    (is (= 2 (terminals/target {:numbers [1 2 3] :showing 2 :last 3}))))
  (testing "from Interactions, the one used last"
    (is (= 3 (terminals/target {:numbers [1 2 3] :showing nil :last 3}))))
  (testing "the first when the one used last has closed"
    (is (= 1 (terminals/target {:numbers [1 2] :showing nil :last 3}))))
  (testing "none: a new one is wanted"
    (is (nil? (terminals/target {:numbers [] :showing nil :last 1})))))

(deftest after-close-test
  (is (= 3 (terminals/after-close [1 2 3] 2)) "the tab after it")
  (is (= 2 (terminals/after-close [1 2 3] 3)) "the one before the last tab")
  (is (nil? (terminals/after-close [1] 1)) "it was the only one")
  (is (nil? (terminals/after-close [1 2] 9)) "not an open terminal"))

(deftest close-alert-test
  (is (= {:message "A program is still running in Terminal 2."
          :detail "Closing the tab ends it."
          :buttons ["Close Anyway" "Cancel"]}
         (terminals/close-alert 2))))
