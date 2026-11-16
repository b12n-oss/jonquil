(ns jonquil.places-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.places :as places]))

(def panels [:inspector :source :debugger :trace])

(deftest no-setting-is-the-usual-window-test
  (let [p (places/plan nil panels)]
    (is (= [:file-tree] (:left p)))
    (is (= [:repl :terminal] (:bottom p)))
    (is (= panels (:right p)))
    (is (= [] (:skipped p)))
    (is (= :right (get-in p [:place-of :debugger])))))

(deftest named-parts-come-first-and-the-rest-keep-their-place-test
  (let [p (places/plan {:right [:repl] :bottom [:terminal :debugger]} panels)]
    (is (= [:file-tree] (:left p)))
    (is (= [:repl :inspector :source :trace] (:right p)))
    (is (= [:terminal :debugger] (:bottom p)))
    (is (= :right (get-in p [:place-of :repl])))))

(deftest a-place-can-be-emptied-test
  (testing "the tree on the right leaves the left place empty"
    (let [p (places/plan {:right [:file-tree]} panels)]
      (is (= [] (:left p)))
      (is (= [:file-tree :inspector :source :debugger :trace] (:right p))))))

(deftest unknown-and-repeated-ids-are-skipped-test
  (let [p (places/plan {:left [:file-tree :no/such] :right [:repl] :bottom [:repl]} panels)]
    (is (= [:file-tree] (:left p)))
    (is (= [:repl :inspector :source :debugger :trace] (:right p)))
    (is (= [:terminal] (:bottom p)))
    (is (= [{:id :no/such :reason :unknown} {:id :repl :reason :twice}] (:skipped p)))))

(deftest skipped-text-test
  (is (nil? (places/skipped-text [])))
  (is (= "; settings.edn :layout: :no/such isn't a part, :repl already has a place, left out\n"
         (places/skipped-text [{:id :no/such :reason :unknown} {:id :repl :reason :twice}]))))

(deftest moved-takes-the-id-out-and-adds-it-to-the-new-place-test
  (testing "the id leaves every list and goes at the end of the new place's"
    (is (= {:left [] :right [:repl :terminal] :bottom [:debugger]}
           (places/moved {:right [:repl] :bottom [:terminal :debugger]} :terminal :right))))
  (testing "nothing in the file yet starts from an empty map"
    (is (= {:left [] :right [:file-tree] :bottom []} (places/moved nil :file-tree :right))))
  (testing "unknown ids and keys the file holds stay"
    (is (= {:left [:no/such] :right [] :bottom [:repl] :note "mine"}
           (places/moved {:left [:no/such :repl] :note "mine"} :repl :bottom))))
  (testing "a list that isn't a vector, or a :layout that isn't a map, is started again"
    (is (= {:left [:repl] :right [] :bottom []} (places/moved {:left :oops} :repl :left)))
    (is (= {:left [] :right [] :bottom [:repl]} (places/moved [:bad] :repl :bottom))))
  (testing "a repeated id goes from every list"
    (is (= {:left [] :right [:repl] :bottom []}
           (places/moved {:left [:repl] :bottom [:repl]} :repl :right)))))

(deftest move-label-names-the-place-test
  (is (= "Move to Left" (places/move-label :left)))
  (is (= "Move to Bottom" (places/move-label :bottom))))
