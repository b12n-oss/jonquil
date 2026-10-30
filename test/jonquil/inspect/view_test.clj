(ns jonquil.inspect.view-test
  (:require [clojure.core.protocols]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.inspect.view :as view]))

(def sample {:a [1 2 {:b "héllo 😀"}] :c #{:x :y} :d (range 30)})

(defn- fresh [] (atom nil))

(deftest start-map-test
  (let [v (view/start (fresh) sample 10 40)]
    (is (= "*1" (:path v)))
    (is (= "PersistentArrayMap · 3 items" (:info v)))
    (is (= 0 (:start v)))
    (is (= 3 (:total v)))
    (is (= ":a · [1 2 {:b \"héllo 😀\"}]" (first (:entries v))))
    (is (nil? (:alt-view v)))))

(deftest in-out-keeps-page-test
  ;; out comes back on the page you left, cursor relative.
  (let [a (fresh)]
    (view/start a (vec (range 25)) 10 40)
    (view/page a 1)
    (let [inside (view/in a 13)]
      (is (= "*1 / 13" (:path inside))))
    (let [back (view/out a)]
      (is (= 10 (:start back)))
      (is (= 3 (:cursor back))))))

(deftest table-toggle-test
  (let [a (fresh)
        v (view/start a [{:id 1 :name "a"} {:id 2 :name "b"}] 10 40)]
    (is (= "list" (:alt-view v)))
    (is (str/includes? (:header v) ":id"))
    (let [l (view/toggle-table a)]
      (is (= "table" (:alt-view l)))
      (is (= "0 · {:id 1, :name \"a\"}" (first (:entries l)))))
    (is (= "not tabular" (:note (view/toggle-table (doto (fresh) (view/start [1 2] 10 40))))))))

(deftest meta-test
  (let [a (fresh)]
    (is (= "meta" (:meta-action (view/start a (with-meta [1 2] {:doc "m"}) 10 40))))
    (let [m (view/toggle-meta a 0)]
      (is (= "*1 / ^meta" (:path m)))
      (is (= "hide" (:meta-action m))))
    (is (= "*1" (:path (view/toggle-meta a 0)))))
  (is (= "no metadata" (:note (view/toggle-meta (doto (fresh) (view/start [1] 10 40)) 0)))))

(deftest leaf-and-cap-test
  (is (= "\"a plain string\"" (:leaf (view/start (fresh) "a plain string" 10 40))))
  (let [a (fresh)
        v (view/start a (range 2000) 10 40)]
    (is (= "LongRange · 1000+ items" (:info v)))
    (is (= 1000 (:total v)))
    (is (= 10 (:start (view/page a 1))))
    (is (= 990 (:start (view/page a 1000))) "clamped to the last page")))

(deftest def-test
  (let [a (fresh) target (create-ns 'jonquil.inspect.view-test.target)]
    (view/start a {:k [7 8]} 10 40)
    (view/in a 0)
    (let [r (view/def-as a target "picked")]
      (is (= "#'jonquil.inspect.view-test.target/picked" (:defd r)))
      (is (= [7 8] @(ns-resolve target 'picked))))
    (remove-ns 'jonquil.inspect.view-test.target)))

(deftest run-op-catches-test
  ;; a throwing value leaves the current view plus :note.
  (let [boom (reify clojure.core.protocols/Datafiable
               (datafy [_] (throw (ex-info "boom" {}))))]
    (testing "with a view showing, the throw keeps it and adds the message"
      (let [a (fresh)]
        (view/run-op a :start {:value [1 2] :rows 10 :cols 40})
        (let [r (view/run-op a :start {:value boom :rows 10 :cols 40})]
          (is (= "boom" (:note r)))
          (is (= "PersistentVector · 2 items" (:info r))))))
    (testing "with no view yet, the fallback map"
      ;; measured: render on a still-nil view doesn't throw (entries on nil
      ;; is nil, so it falls into the leaf branch), so this exercises
      ;; run-op's outer catch only; type-name of nil is the string "nil".
      (is (= {:path "" :info "nil" :header "" :note "boom"}
             (select-keys (view/run-op (atom nil) :start {:value boom :rows 10 :cols 40})
                          [:path :info :header :note]))))))

;; --- the history of inspected objects (Jonquil's own) ----------------------

(defn- start! [a v] (view/run-op a :start {:value v :rows 10 :cols 40}))

(deftest history-steps-between-roots-test
  (let [a (fresh)]
    (start! a [1])
    (start! a [1 2])
    (is (= {:at 3 :count 3} (:history (start! a [1 2 3]))))
    (let [r (view/run-op a :earlier {})]
      (is (= 2 (:total r)))
      (is (= {:at 2 :count 3} (:history r))))
    (is (= 1 (:total (view/run-op a :earlier {}))))
    (testing "the oldest stays put and says so"
      (let [r (view/run-op a :earlier {})]
        (is (= 1 (:total r)))
        (is (= "No earlier object." (:note r)))
        (is (= {:at 1 :count 3} (:history r)))))
    (is (= 2 (:total (view/run-op a :later {}))))
    (is (= 3 (:total (view/run-op a :later {}))))
    (is (= "No later object." (:note (view/run-op a :later {}))))))

(deftest history-new-root-drops-later-ones-test
  (let [a (fresh)]
    (start! a [1]) (start! a [1 2]) (start! a [1 2 3])
    (view/run-op a :earlier {})
    (let [r (start! a [1 2 3 4])]
      (is (= {:at 3 :count 3} (:history r)))
      (is (= 4 (:total r))))
    (is (= 2 (:total (view/run-op a :earlier {}))))))

(deftest history-same-object-once-test
  (let [a (fresh) v [1 2]]
    (start! a [1])
    (start! a v)
    (is (= {:at 2 :count 2} (:history (start! a v))) "identical? to the current root: not added")))

(deftest history-lands-on-root-top-test
  (let [a (fresh)]
    (start! a [[:x :y]])
    (start! a [1 2])
    (view/run-op a :earlier {})
    (is (= "*1 / 0" (:path (view/run-op a :in {:idx 0}))))
    (view/run-op a :later {})
    (let [r (view/run-op a :earlier {})]
      (is (= "*1" (:path r)) "a fresh stack, not the path you left")
      (is (= 0 (:start r))))))

(deftest history-max-test
  (let [a (fresh)]
    (doseq [n (range 1 26)] (start! a (vec (range n))))
    (is (= {:at view/HISTORY-MAX :count view/HISTORY-MAX} (:history (view/run-op a :page {:delta 0}))))
    (dotimes [_ (dec view/HISTORY-MAX)] (view/run-op a :earlier {}))
    (is (= 16 (:total (view/run-op a :page {:delta 0}))) "the oldest kept is the 16th (25 - 10 + 1)")))
