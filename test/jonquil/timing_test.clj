(ns jonquil.timing-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.timing :as timing]))

(defn- run [form-text]
  (let [out (java.io.StringWriter.)
        r (binding [*out* out] (eval (read-string (timing/code form-text 100))))]
    [r (str out)]))

(deftest fast-form-test
  (let [[r out] (run "(+ 1 2)")]
    (is (<= 3 (:batches r) 10) (str r))
    (is (> (:batch-size r) 1))
    (is (>= (:baseline-ns r) 0))
    (is (str/starts-with? out "; Time: ") out)))

(deftest slow-form-test
  (let [t0 (System/nanoTime)
        [r out] (run "(Thread/sleep 1)")
        ms (/ (- (System/nanoTime) t0) 1e6)]
    (is (>= (:ns-per-call r) 900000) (str r))
    (is (< ms 3000) (str ms " ms"))
    (is (re-find #"; Time: [0-9.]+ ms per call" out) out)))

(deftest throwing-form-test
  (is (thrown? ArithmeticException (run "(/ 1 0)"))))

(deftest harness-names-test
  (testing "a form's own jonquil-time- local shadows nothing of the harness"
    (let [[r _] (run "(let [jonquil-time-f 7] jonquil-time-f)")]
      (is (<= 3 (:batches r) 10) (str r)))))

(deftest supported-test
  (is (every? timing/supported? [:jolt :clj :nrepl]))
  (is (not-any? timing/supported? [:cljs :cljd :jank])))

(deftest slow-form-stays-near-the-budget-test
  ;; a call slower than budget/20 used to run 10 batches of one: 50 ms x 11
  (let [t0 (System/nanoTime)
        [r out] (run "(Thread/sleep 50)")
        ms (/ (- (System/nanoTime) t0) 1e6)]
    (is (< ms 400) (str ms " ms for a 100 ms budget"))
    (is (<= 3 (:batches r) 5) (str r))
    (is (str/includes? out (str "over " (:batches r) " batches")) out)))

(deftest form-slower-than-the-budget-runs-once-test
  (let [t0 (System/nanoTime)
        [r out] (run "(Thread/sleep 150)")
        ms (/ (- (System/nanoTime) t0) 1e6)]
    (is (< ms 400) (str ms " ms"))
    (is (= 1 (:batches r)))
    (is (re-find #"; Time: [0-9.]+ ms for one call" out) out)))

(deftest never-negative-test
  ;; an empty form against an empty call differs by noise either way: the
  ;; answer is 0, never a negative time
  (dotimes [_ 3]
    (let [[r out] (run "nil")]
      (is (>= (:ns-per-call r) 0) (str r))
      (is (str/includes? out "too fast to tell") out))))

(deftest confirm-alert-test
  (let [{:keys [message detail buttons]} (timing/confirm-alert "(swap! a inc)")]
    (is (= "Time (swap! a inc)?" message))
    (is (str/includes? detail "side effect") detail)
    (is (str/includes? detail "millions of times") detail)
    (is (= ["Time It" "Time It, Don't Ask Again" "Cancel"] buttons)))
  (testing "a long form is cut to 60 characters"
    (let [long-form (str "(do " (apply str (repeat 40 "x ")) ")")]
      (is (= (str "Time " (subs long-form 0 60) "…?") (:message (timing/confirm-alert long-form)))))))
