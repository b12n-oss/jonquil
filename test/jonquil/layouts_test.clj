(ns jonquil.layouts-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.layouts :as layouts]))

(deftest layout-name-test
  (is (= "Editor only" (layouts/layout-name {:tree? false :panel nil})))
  (is (= "File tree" (layouts/layout-name {:tree? true :panel nil})))
  (is (= "Trace panel" (layouts/layout-name {:tree? false :panel :trace})))
  (is (= "File tree and Frames panel" (layouts/layout-name {:tree? true :panel :debugger}))
      "the panel's own label"))

(deftest save-test
  (let [a {:tree? true :tree-width 240 :panel :trace :panel-width 400 :split 430}
        b (assoc a :tree-width 300)]
    (is (= {"File tree and Trace panel" a} (layouts/save {} a)))
    (is (= {"File tree and Trace panel" b} (layouts/save (layouts/save {} a) b)) "the same parts: replaced")
    (is (= 2 (count (layouts/save (layouts/save {} a) (assoc a :panel nil)))))))

(deftest entries-test
  (is (= [{:id [:layout/use "Editor only"] :doc "Use layout: Editor only" :chords [] :source "layout"}
          {:id [:layout/use "File tree"] :doc "Use layout: File tree" :chords [] :source "layout"}]
         (layouts/entries {"File tree" {} "Editor only" {}}))
      "sorted by name"))

(deftest valid-test
  (is (layouts/valid? {:tree? true :tree-width 240 :panel :trace :panel-width 400 :split 430}))
  (is (layouts/valid? {:tree? false :panel nil}))
  (is (not (layouts/valid? {:panel "trace"})) "a string panel")
  (is (not (layouts/valid? {:panel :review})) "a panel the palette can't bring back")
  (is (not (layouts/valid? {:tree-width -1})))
  (is (not (layouts/valid? {:tree? "yes"}))))

(deftest save-drops-a-panel-it-cant-bring-back-test
  (is (= {"File tree" {:tree? true :panel nil}} (layouts/save {} {:tree? true :panel :review}))))

