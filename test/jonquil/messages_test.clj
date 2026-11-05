(ns jonquil.messages-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.messages :as m]))

(defn- adds [log entries]
  (reduce (fn [l [e t]] (m/add l e t)) log (map vector entries (range))))

(deftest add-assigns-ids
  (let [l (-> (m/empty-log)
              (m/add {:kind :agent :summary "a"} 100)
              (m/add {:kind :agent :summary "b"} 200))]
    (is (= [1 2] (map :id (:entries l))))
    (is (= [100 200] (map :at (:entries l))))
    (is (= 3 (:next-id l)))))

(deftest add-collapses-repeat
  (let [ready {:kind :status :summary "Ready"}
        l (-> (m/empty-log) (m/add ready 1) (m/add ready 2) (m/add ready 3))]
    (is (= 1 (count (:entries l))))
    (is (= {:id 1 :at 3 :repeat 3} (select-keys (first (:entries l)) [:id :at :repeat])))
    (testing "a different summary adds a row"
      (let [l (m/add l {:kind :status :summary "Running…"} 4)]
        (is (= [1 2] (map :id (:entries l))))))
    (testing "the same text as a failure is a different row"
      (let [l (m/add l {:kind :status :summary "Ready" :error? true} 4)]
        (is (= 2 (count (:entries l))))))))

(deftest add-drops-past-cap
  (let [l (adds (m/empty-log) (for [i (range 1005)] {:kind :agent :summary (str "e" i)}))]
    (is (= 1000 (count (:entries l))))
    (is (= 6 (:id (first (:entries l)))))
    (is (= 5 (:dropped l)))))

(deftest unread-counts-shown-kinds
  (let [l (adds (m/empty-log) (for [k [:agent :agent-read :plugin :connection :runtime :status]]
                                {:kind k :summary (name k)}))]
    (is (= 4 (m/unread-count l)))
    (let [r (m/mark-read l)]
      (is (= 0 (m/unread-count r)))
      (is (= 1 (m/unread-count (m/add r {:kind :agent :summary "new"} 9))))
      (testing "a repeat of a read entry doesn't count again"
        (let [r (m/mark-read (m/add l {:kind :agent :summary "again"} 7))]
          (is (= 0 (m/unread-count (m/add r {:kind :agent :summary "again"} 9)))))))
    (is (= 0 (:read-id (m/mark-read (m/empty-log)))))))

(deftest visible-newest-first-and-filtered
  (let [l (adds (m/empty-log) [{:kind :agent :summary "a"} {:kind :status :summary "s"}
                               {:kind :agent :summary "b"}])]
    (is (= [3 1] (map :id (m/visible l #{:agent}))))
    (is (= [3 2 1] (map :id (m/visible l #{:agent :status}))))))

(deftest cap-text-lines
  (let [s (str/join "\n" (repeat 25 "x"))
        out (str/split-lines (m/cap-text s))]
    (is (= 21 (count out)))
    (is (= (repeat 20 "x") (take 20 out)))
    (is (= "… 5 more lines" (last out))))
  (is (= "short" (m/cap-text "short")))
  (is (nil? (m/cap-text nil))))

(deftest cap-text-chars
  (let [out (m/cap-text (apply str (repeat 5000 "y")))]
    (is (= (str (apply str (repeat 2000 "y")) "\n… cut at 2,000 characters") out))))

(deftest log!-is-safe-from-futures
  (let [a (atom (m/empty-log))
        fs (doall (for [i (range 200)] (future (m/log! a {:kind :agent :summary (str "call " i)}))))]
    (run! deref fs)
    (is (= 200 (count (:entries @a))))
    (is (= (range 1 201) (map :id (:entries @a))))))

(deftest log!-never-throws
  (is (nil? (binding [*err* (java.io.StringWriter.)]
              (m/log! (atom :not-a-log) {:kind :agent :summary "x"})))))

(deftest file-changes-are-shown-and-counted
  (is (contains? m/SHOWN-KINDS :file))
  (is (contains? m/COUNTED-KINDS :file)))

(deftest hidden-kinds-are-dropped-first
  (let [l (adds (m/empty-log) (concat [{:kind :agent :summary "first agent"}]
                                      (for [i (range 999)] {:kind :status :summary (str "s" i)})
                                      (for [i (range 5)] {:kind :agent :summary (str "a" i)})))]
    (is (= 1000 (count (:entries l))))
    (is (= 5 (:dropped l)))
    (is (= 6 (count (filter #(= :agent (:kind %)) (:entries l)))) "no agent row went while status rows were left")
    (is (= "first agent" (:summary (first (:entries l)))))
    (is (apply < (map :id (:entries l))) "still in id order"))
  (testing "with only shown kinds, the oldest goes"
    (let [l (adds (m/empty-log) (for [i (range 1002)] {:kind :agent :summary (str "e" i)}))]
      (is (= 3 (:id (first (:entries l))))))))
