(ns jonquil.nrepl.inspect-middleware-test
  "The inspector middleware's pure handler, run in this process. In a Jolt
  child the same code answers the inspect-* ops (jonquil.nrepl.evaluator)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [jonquil.nrepl.inspect-middleware :as mw]))

(defn- view [state request]
  (let [r (mw/handle state request)]
    (is (= ["done"] (get r "status")))
    (edn/read-string (get r "view"))))

(deftest only-inspect-ops-are-handled
  (is (nil? (mw/handle (atom nil) {"op" "eval" "code" "1"})))
  (is (nil? (mw/handle (atom nil) {"op" "describe"}))))

(deftest start-takes-star-1-and-the-ops-navigate
  (let [state (atom nil)
        data {:a [1 2 3] :rows [{:id 1 :n "a"} {:id 2 :n "b"}]}]
    (binding [*1 data]
      (let [v (view state {"op" "inspect-start" "rows" 10 "cols" 60})]
        (is (= "*1" (:path v)))
        (is (= 2 (:total v)))))
    (testing "in, table, out, page"
      (let [v (view state {"op" "inspect-in" "idx" 1})]
        (is (= "*1 / :rows" (:path v)))
        (is (= "list" (:alt-view v)) "uniform maps open as a table"))
      (is (= "table" (:alt-view (view state {"op" "inspect-toggle-table"}))))
      (let [v (view state {"op" "inspect-out"})]
        (is (= "*1" (:path v)))
        (is (= 1 (:cursor v))))
      (is (= 0 (:start (view state {"op" "inspect-page" "delta" 1}))) "one page: paging stays put"))
    (is (= "no metadata" (:note (view state {"op" "inspect-toggle-meta" "cursor" 0}))))))

(deftest numbers-may-arrive-as-strings
  (let [state (atom nil)]
    (binding [*1 [10 20 30]]
      (view state {"op" "inspect-start" "rows" "2" "cols" "40"}))
    (is (= 2 (:start (view state {"op" "inspect-page" "delta" "1"}))))))

(deftest def-interns-into-the-named-namespace
  (let [state (atom nil)
        target (create-ns 'jonquil.nrepl.inspect-middleware-test.target)]
    (binding [*1 {:k 42}]
      (view state {"op" "inspect-start" "rows" 10 "cols" 40}))
    (is (= "#'jonquil.nrepl.inspect-middleware-test.target/picked"
           (:defd (view state {"op" "inspect-def" "name" "picked"
                               "ns" "jonquil.nrepl.inspect-middleware-test.target"}))))
    (is (= {:k 42} @(ns-resolve target 'picked)))
    (remove-ns 'jonquil.nrepl.inspect-middleware-test.target)))

(deftest def-without-a-known-ns-uses-user
  (let [state (atom nil)]
    (binding [*1 7]
      (view state {"op" "inspect-start" "rows" 10 "cols" 40}))
    (is (= "#'user/jonquil-mw-test-picked"
           (:defd (view state {"op" "inspect-def" "name" "jonquil-mw-test-picked" "ns" "no.such.ns"}))))
    (ns-unmap 'user 'jonquil-mw-test-picked)))
