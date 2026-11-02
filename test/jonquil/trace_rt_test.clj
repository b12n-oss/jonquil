(ns jonquil.trace-rt-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.trace-rt :as trace]))

(defn- fresh [f]
  (trace/untrace-all!)
  (trace/clear!)
  (try (f) (finally (trace/untrace-all!) (trace/clear!))))

(use-fixtures :each fresh)

(defn tsq [x] (* x x))
(defn tsum [n] (reduce + (map tsq (range n))))
(defn tfact [n] (if (< n 2) 1 (* n (tfact (dec n)))))
(defn tdiv [x] (/ 1 x))
(def not-a-fn 42)

(def ^:private here "jonquil.trace-rt-test")

(defn- entries [] (:entries (trace/log-since -1)))

(deftest trace-records-calls-through-earlier-callers-test
  (is (= {:traced "jonquil.trace-rt-test/tsq"} (trace/trace! 'tsq here)))
  (is (= 5 (tsum 3)))
  (let [es (entries)]
    (is (= 3 (count es)))
    (is (= ["(jonquil.trace-rt-test/tsq 0)" "(jonquil.trace-rt-test/tsq 1)" "(jonquil.trace-rt-test/tsq 2)"] (mapv :args es)))
    (is (= ["0" "1" "4"] (mapv :ret es)))
    (is (every? #(= 0 (:depth %)) es))
    (is (= 4 (trace/value (:id (last es)) :ret))))
  (is (= {:already "jonquil.trace-rt-test/tsq"} (trace/trace! 'tsq here))))

(deftest recursion-test
  ;; measured on Jolt: a fn's call to itself doesn't go through its var, so
  ;; only the outer call is recorded (the guide says so); calls between
  ;; traced fns nest (nesting-test)
  (trace/trace! 'tfact here)
  (is (= 6 (tfact 3)))
  (let [es (entries)]
    (is (= [0] (mapv :depth es)))
    (is (= ["6"] (mapv :ret es)))
    (is (not-any? :pending es))))

(deftest nesting-test
  (trace/trace! 'tsq here)
  (trace/trace! 'tsum here)
  (is (= 1 (tsum 2)))
  (let [es (entries)]
    (is (= [0 1 1] (mapv :depth es)))
    (is (= ["1" "0" "1"] (mapv :ret es)))))

(deftest a-throw-is-recorded-and-depth-unwinds-test
  (trace/trace! 'tdiv here)
  (is (thrown? ArithmeticException (tdiv 0)))
  (is (= 1 (tdiv 1)))
  (let [[a b] (entries)]
    (is (str/starts-with? (:threw a) "ArithmeticException"))
    (is (nil? (:ret a)))
    (is (= [0 0] [(:depth a) (:depth b)]))
    (is (= "(jonquil.trace-rt-test/tdiv 0)" (:args a)))
    (is (= [0] (vec (trace/value (:id a) :args))))))

(deftest refusals-test
  (is (:error (trace/trace! 'not-a-fn here)))
  (is (:error (trace/trace! 'no-such-thing here)))
  (is (:error (trace/trace! 'when here)) "a macro")
  (is (= 42 not-a-fn)))

(deftest untrace-restores-the-original-test
  (let [orig tsq]
    (trace/trace! 'tsq here)
    (is (not (identical? orig @#'tsq)))
    (is (= {:untraced "jonquil.trace-rt-test/tsq"} (trace/untrace! 'tsq here)))
    (is (identical? orig @#'tsq))
    (tsum 3)
    (is (empty? (entries)))))

(deftest untrace-all-test
  (trace/trace! 'tsq here)
  (trace/trace! 'tdiv here)
  (is (= ["jonquil.trace-rt-test/tdiv" "jonquil.trace-rt-test/tsq"] (trace/traced)))
  (is (= {:count 2} (trace/untrace-all!)))
  (is (= [] (trace/traced))))

(deftest the-log-keeps-the-last-500-test
  (trace/trace! 'tsq here)
  (dotimes [i 510] (tsq i))
  (let [es (entries)]
    (is (= 500 (count es)))
    (is (= "(jonquil.trace-rt-test/tsq 10)" (:args (first es))))
    (is (= ::trace/gone (trace/value 0 :ret)))))

(deftest log-since-and-lazy-results-test
  (trace/trace! 'tsq here)
  (tsq 1)
  (let [{:keys [last]} (trace/log-since -1)]
    (tsq 2)
    (is (= ["(jonquil.trace-rt-test/tsq 2)"] (mapv :args (:entries (trace/log-since last)))))))

(def lazy-hits (atom 0))
(defn tlazy [] (map (fn [x] (swap! lazy-hits inc) x) [1 2 3]))

(deftest an-unrealized-result-is-never-realized-test
  (trace/trace! 'tlazy here)
  (reset! lazy-hits 0)
  (tlazy)
  (is (= "<lazy seq, not realized>" (:ret (first (entries)))))
  (is (= 0 @lazy-hits)))
