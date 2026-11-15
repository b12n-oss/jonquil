(ns jonquil.header-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.header :as header]))

(def commands
  {:tree/toggle {:doc "Show or hide the file tree."}
   :file/open {:doc "Open a file."}
   :tab/new {:doc "Open a new tab."}
   :run/run {:doc "Run the buffer."}
   :run/stop {:doc "Stop the running evaluation."}
   :file/save {:doc "Save the buffer."}
   :terminal/show {:doc "Show the terminal."}
   :edit/format-all {:doc "Format the buffer."}})

(def menu-items
  [{:command :terminal/show :label "Terminal"}])

(defn- ids [side plan] (mapv :command (get plan side)))

(deftest no-setting-is-the-default-test
  (let [p (header/plan nil commands menu-items)]
    (is (= (:start header/default) (ids :start p)))
    (is (= (:end header/default) (ids :end p)))
    (is (= [] (:skipped p)))
    (is (every? :widget (concat (:start p) (:end p))) "every default button is one the window makes")))

(deftest a-side-left-out-keeps-the-default-test
  (let [p (header/plan {:end [:run/run :terminal/show]} commands menu-items)]
    (is (= (:start header/default) (ids :start p)))
    (is (= [:run/run :terminal/show] (ids :end p)))))

(deftest an-empty-side-has-no-buttons-test
  (is (= [] (ids :start (header/plan {:start []} commands menu-items)))))

(deftest a-new-button-takes-the-menu-label-and-the-command-doc-test
  (let [[run term fmt] (:end (header/plan {:end [:run/run :terminal/show :edit/format-all]} commands menu-items))]
    (is (= {:command :run/run :widget :run-btn} run))
    (is (= {:command :terminal/show :label "Terminal" :tooltip "Show the terminal."} term))
    (testing "no menu has it, so the label is the id's name"
      (is (= "format-all" (:label fmt))))))

(deftest unknown-and-repeated-ids-are-skipped-test
  (let [p (header/plan {:start [:file/open :no/such]
                        :end [:file/open :run/run :run/run]}
                       commands menu-items)]
    (is (= [:file/open] (ids :start p)))
    (is (= [:run/run] (ids :end p)))
    (is (= [{:command :no/such :reason :unknown}
            {:command :file/open :reason :twice}
            {:command :run/run :reason :twice}]
           (:skipped p)))
    (let [text (header/skipped-text (:skipped p))]
      (is (str/includes? text ":no/such isn't a command"))
      (is (str/includes? text ":file/open is already in the header")))))

(deftest nothing-skipped-says-nothing-test
  (is (nil? (header/skipped-text []))))
