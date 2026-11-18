(ns jonquil.namespaces-view-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.namespaces-view :as nv]))

(def ^:private top
  {:namespaces [{:name "demo.store" :file "demo/store.clj" :vars 6}
                {:name "clojure.string" :file "clojure/string.clj" :vars 21}
                {:name "demo.core" :file "demo/core.clj" :vars 4}
                {:name "clojure.core" :file "clojure/core.clj" :vars 640}
                {:name "user" :file nil :vars 0}]
   :dropped 0})

(defn- own? [f] (boolean (re-find #"^demo/" f)))

(defn- names [rows] (mapv (juxt :kind :name) rows))

(deftest top-rows-own-first
  (let [rows (nv/top-rows top {:own? own? :expanded? false :filter ""})]
    (is (= [[:ns "demo.core"] [:ns "demo.store"] [:group nil]] (names rows)))
    (is (= {:kind :group :key ::nv/libraries :label "Libraries" :count 3 :expanded? false} (last rows)))
    (is (= {:kind :ns :key "demo.core" :name "demo.core" :count 4 :own? true} (first rows)))))

(deftest expanded-shows-libraries
  (is (= [[:ns "demo.core"] [:ns "demo.store"] [:group nil]
          [:ns "clojure.core"] [:ns "clojure.string"] [:ns "user"]]
         (names (nv/top-rows top {:own? own? :expanded? true :filter ""})))))

(deftest filter-opens-libraries
  (let [rows (nv/top-rows top {:own? own? :expanded? false :filter "STR"})]
    (is (= [[:group nil] [:ns "clojure.string"]] (names rows)))
    (is (= 1 (:count (first rows))))
    (is (true? (:expanded? (first rows))))))

(deftest no-libraries-no-group
  (is (= [[:ns "demo.core"]]
         (names (nv/top-rows {:namespaces [{:name "demo.core" :file "demo/core.clj" :vars 1}]}
                             {:own? own? :expanded? false :filter ""})))))

(deftest dropped-adds-more-row
  (is (= {:kind :more :key ::nv/more :text "12 more not shown"}
         (last (nv/top-rows (assoc top :dropped 12) {:own? own? :expanded? false :filter ""}))))
  (is (= "3 more not shown" (:text (last (nv/var-rows {:vars [] :dropped 3} {:filter ""}))))))

(def ^:private vars
  {:found? true
   :vars [{:name "visit!" :arglists ["[name]"] :doc nil :file "demo/core.clj" :line 11 :bound? true}
          {:name "greet" :arglists ["[name]"] :doc "Says hello." :file "demo/core.clj" :line 4 :bound? true}
          {:name "visits" :arglists [] :private? true :bound? true}
          {:name "w" :arglists ["[& body]"] :macro? true :bound? true}]
   :dropped 0})

(deftest var-rows-meta
  (let [rows (nv/var-rows vars {:filter ""})]
    (is (= ["greet" "visit!" "visits" "w"] (mapv :name rows)))
    (is (= "[name] · Says hello." (:meta (first rows))))
    (is (= "[name]" (:meta (second rows))))
    (is (= "private" (:meta (nth rows 2))))
    (is (= "macro · [& body]" (:meta (nth rows 3))))
    (is (= {:kind :var :key "greet" :name "greet" :meta "[name] · Says hello." :private? false
            :macro? false :bound? true :file "demo/core.clj" :line 4 :arglists ["[name]"] :doc "Says hello."}
           (first rows))))
  (is (= ["visit!" "visits"] (mapv :name (nv/var-rows vars {:filter "VISIT"})))))

(deftest bare-var-texts
  (let [r (first (nv/var-rows {:vars [{:name "x"}]} {:filter ""}))]
    (is (= "" (:meta r)))
    (is (= "demo.core/x\nNo doc. · Return jumps, Shift+Return inspects" (nv/var-footer "demo.core" r))))
  (let [r (first (nv/var-rows vars {:filter "greet"}))]
    (is (= "demo.core/greet [name]\nSays hello. · demo/core.clj:4 · Return jumps, Shift+Return inspects"
           (nv/var-footer "demo.core" r)))))

(deftest crumb-plural
  (is (= "All namespaces › demo.core · 1 var" (nv/crumb "demo.core" 1)))
  (is (= "All namespaces › demo.core · 4 vars" (nv/crumb "demo.core" 4))))

(deftest top-footer-counts
  (is (= "2 own namespaces, 3 from libraries" (nv/top-footer top own?)))
  (is (= "1 own namespace, 0 from libraries"
         (nv/top-footer {:namespaces [{:name "demo.core" :file "demo/core.clj"}]} own?))))

(deftest reselect-keeps-or-falls-back
  (let [rows (nv/top-rows top {:own? own? :expanded? true :filter ""})]
    (is (= 4 (nv/reselect rows "clojure.string")))
    (is (= 0 (nv/reselect rows "gone.ns")))
    (testing "only a group row: nothing to select"
      (is (nil? (nv/reselect [{:kind :group :key ::nv/libraries}] "x"))))
    (is (= 1 (nv/reselect [{:kind :group :key ::nv/libraries} {:kind :ns :key "a"}] nil)))))

(deftest inspect-refusal-cases
  (is (nil? (nv/inspect-refusal "demo.core" {:name "greet" :bound? true})))
  (is (= "demo.core/w is a macro, with no value to inspect."
         (nv/inspect-refusal "demo.core" {:name "w" :macro? true :bound? true})))
  (is (= "demo.core/later has no value yet." (nv/inspect-refusal "demo.core" {:name "later" :bound? false}))))
