(ns jonquil.fibers-rt-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.fibers]
            [jonquil.fibers-rt :as fr]))

(defn- spawn [f] ((requiring-resolve 'jolt.fibers/spawn) f))

(defn- by-id [id] (some #(when (= id (:id %)) %) (:fibers (fr/op :list {}))))

(defn- wait-state [id want]
  (loop [n 0]
    (let [s (:state (by-id id))]
      (if (or (contains? want s) (> n 100)) s (do (Thread/sleep 10) (recur (inc n)))))))

(deftest age-and-watch-test
  (if-not (fr/available?)
    (println "SKIP fibers-rt: this Jolt has no jolt.fibers")
    (do
      (is (= {:watching? true} (fr/op :watch {})))
      (is (= {:watching? true} (fr/op :watch {})) "watching twice")
      (let [spawn-var (requiring-resolve 'jolt.fibers/spawn)
            wrapped @spawn-var]
        (fr/op :watch {})
        (is (identical? wrapped @spawn-var) "spawn is wrapped once"))
      (let [stop (atom false)
            loop-fib (spawn (fn [] (loop [] (when-not @stop ((requiring-resolve 'jolt.fibers/yield)) (recur)))))
            {:keys [fibers]} (fr/op :list {})
            newest (first fibers)]
        (is (some? newest) "the spawned fiber is listed")
        (is (#{:running :ready :parked} (:state newest)))
        (is (string? (:label newest)))
        (is (<= 0 (:age-ms newest)))
        (testing "interrupt"
          (is (= {:ok true} (fr/op :interrupt {:id (:id newest)})))
          (is (= :dead (wait-state (:id newest) #{:dead}))))
        (testing "an id that finished, and one never seen"
          (is (= {:error (str "Fiber #" (:id newest) " has already finished.")} (fr/op :kill {:id (:id newest)})))
          (is (= {:error "No fiber #999999."} (fr/op :interrupt {:id 999999}))))
        (testing "kill"
          (let [_ (spawn (fn [] (loop [] (when-not @stop ((requiring-resolve 'jolt.fibers/yield)) (recur)))))
                id (:id (first (:fibers (fr/op :list {}))))]
            (is (= {:ok true} (fr/op :kill {:id id})))
            (is (= :dead (wait-state id #{:dead})))))
        (testing "forget drops finished fibers; at most 50 finished are kept"
          (dotimes [_ 60] (spawn (fn [] :x)))
          (Thread/sleep 200)
          (is (<= (count (filter #(#{:done :dead} (:state %)) (:fibers (fr/op :list {})))) 50))
          (is (= {:ok true} (fr/op :forget {})))
          (is (empty? (filter #(#{:done :dead} (:state %)) (:fibers (fr/op :list {}))))))
        (reset! stop true)
        (is (some? loop-fib))))))

(defn worker? [] :w)

(deftest label-test
  (is (= "jonquil.fibers-rt-test/worker?" (fr/label worker?)) "demunged")
  (is (= "a fn" (fr/label (fn [] 1)))))

(deftest spawn-stays-cheap-test
  (when (fr/available?)
    (fr/op :watch {})
    (let [stop (atom false)
          spawn (requiring-resolve 'jolt.fibers/spawn)
          yield (requiring-resolve 'jolt.fibers/yield)
          t0 (System/currentTimeMillis)]
      (dotimes [_ 3000] (spawn (fn [] (loop [] (when-not @stop (yield) (recur))))))
      (let [ms (- (System/currentTimeMillis) t0)]
        (reset! stop true)
        (is (< ms 1500) (str "3000 spawns of live fibers while watching took " ms " ms"))))))

(deftest interrupt-that-lands-on-a-finished-fiber-test
  (when (fr/available?)
    (fr/op :watch {})
    (let [stop (atom false)
          fib ((requiring-resolve 'jolt.fibers/spawn) (fn [] (loop [] (when-not @stop ((requiring-resolve 'jolt.fibers/yield)) (recur)))))
          id (:id (first (:fibers (fr/op :list {}))))]
      (with-redefs [jolt.fibers/interrupt! (fn [& _] false)]
        (is (= {:error (str "Fiber #" id " has already finished.")} (fr/op :interrupt {:id id}))
            "interrupt! answered false: it finished in between"))
      (reset! stop true)
      (is (some? fib)))))

