(ns jonquil.gtk.namespaces-pane-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.namespaces-pane :as np]))

(deftest list-key-test
  (is (= :back (np/list-key 0xff08 0)))
  (is (= :inspect (np/list-key 0xff0d g/SHIFT-MASK)))
  (is (= :inspect (np/list-key 0xff8d g/SHIFT-MASK)))
  (is (nil? (np/list-key 0xff0d 0)) "plain Return is row-activated's")
  (is (nil? (np/list-key 0x61 0))))

(deftest filter-key-test
  (is (= :down (np/filter-key 0xff54)))
  (is (nil? (np/filter-key 0xff52))))

(deftest row-texts-test
  (is (= ["demo.core" "4"] ((juxt np/row-label np/row-meta) {:kind :ns :name "demo.core" :count 4})))
  (is (= ["▸ Libraries (3)" ""] ((juxt np/row-label np/row-meta) {:kind :group :label "Libraries" :count 3})))
  (is (= "▾ Libraries (3)" (np/row-label {:kind :group :label "Libraries" :count 3 :expanded? true})))
  (is (= ["greet" "[n] · Hi."] ((juxt np/row-label np/row-meta) {:kind :var :name "greet" :meta "[n] · Hi."})))
  (is (= ["2 more not shown" ""] ((juxt np/row-label np/row-meta) {:kind :more :text "2 more not shown"}))))
