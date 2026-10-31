(ns jonquil.timing
  "Time the form at the caret, Genera's WITH-FORM-MEASURED: the form runs
  in batches for about the budget, against the same batches of an empty
  call, and the difference per call is the time it takes. code builds the
  harness as text, so it runs in the window's own runtime, where the form
  really runs. No GTK here."
  (:require [clojure.string :as str]))

(def ^:private dialects
  ;; where the harness's System/nanoTime exists
  #{:jolt :clj :nrepl})

(defn supported?
  "True when dialect-id's runtime can run the harness."
  [dialect-id]
  (contains? dialects dialect-id))

(def ^:private template
  ;; Every name starts with jonquil-time- so the form can't capture one.
  ;; Batches double until one takes a twentieth of the budget, then 10 of
  ;; them run for the form and 10 for an empty call.
  "(let [jonquil-time-f (fn [] FORM)
      jonquil-time-g (fn [] nil)
      jonquil-time-slice (quot (* BUDGET 1000000) 20)
      jonquil-time-batch (fn [h n] (let [t0 (System/nanoTime)] (dotimes [_ n] (h)) (- (System/nanoTime) t0)))
      jonquil-time-n (loop [n 1]
                       (if (or (>= (jonquil-time-batch jonquil-time-f n) jonquil-time-slice) (>= n 1073741824))
                         n
                         (recur (* 2 n))))
      jonquil-time-per (fn [h] (vec (for [_ (range 10)] (/ (double (jonquil-time-batch h jonquil-time-n)) jonquil-time-n))))
      jonquil-time-xs (jonquil-time-per jonquil-time-f)
      jonquil-time-bs (jonquil-time-per jonquil-time-g)
      jonquil-time-mean (fn [xs] (/ (reduce + xs) (count xs)))
      jonquil-time-m (jonquil-time-mean jonquil-time-xs)
      jonquil-time-base (jonquil-time-mean jonquil-time-bs)
      jonquil-time-spread (Math/sqrt (jonquil-time-mean (map (fn [x] (let [d (- x jonquil-time-m)] (* d d))) jonquil-time-xs)))
      jonquil-time-per-call (max 0.0 (- jonquil-time-m jonquil-time-base))
      jonquil-time-show (fn [ns] (let [[v u] (cond (< ns 1e3) [ns \"ns\"] (< ns 1e6) [(/ ns 1e3) \"µs\"]
                                                   (< ns 1e9) [(/ ns 1e6) \"ms\"] :else [(/ ns 1e9) \"s\"])]
                                   (str (/ (Math/round (* v 10.0)) 10.0) \" \" u)))]
  (println (if (> jonquil-time-per-call jonquil-time-spread)
             (str \"; Time: \" (jonquil-time-show jonquil-time-per-call) \" per call (± \"
                  (jonquil-time-show jonquil-time-spread) \" over 10 batches of \" jonquil-time-n
                  \"; an empty call's \" (jonquil-time-show jonquil-time-base) \" taken off)\")
             (str \"; Time: too fast to tell apart from an empty call (\" (jonquil-time-show jonquil-time-base) \")\")))
  {:ns-per-call jonquil-time-per-call :spread-ns jonquil-time-spread :baseline-ns jonquil-time-base
   :batches 10 :batch-size jonquil-time-n})")

(defn code
  "The harness around form-text for a budget of budget-ms, as text to
  evaluate. It prints one ; Time: line and answers {:ns-per-call
  :spread-ns :baseline-ns :batches :batch-size}. A form that throws throws
  out of it, once."
  [form-text budget-ms]
  (-> template
      (str/replace "BUDGET" (str (long budget-ms)))
      (str/replace "FORM" form-text)))
