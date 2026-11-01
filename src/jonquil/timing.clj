(ns jonquil.timing
  "Time the form at the caret, Genera's WITH-FORM-MEASURED: the form runs
  in batches for about the budget, each against a batch of an empty call,
  and the difference per call is the time it takes. A form slower than
  the whole budget is timed once. code builds the
  harness as text, so it runs in the window's own runtime, where the form
  really runs. No GTK here."
  (:require [clojure.string :as str]))

(def ^:private dialects
  ;; where the harness's System/nanoTime exists; a general nREPL server
  ;; without it (Basilisp, ClojureScript) answers with its own eval error
  #{:jolt :clj :nrepl})

(defn supported?
  "True when dialect-id's runtime can run the harness."
  [dialect-id]
  (contains? dialects dialect-id))

(def ^:private template
  ;; Every name starts with jonquil-time- so the form can't capture one.
  ;; Batches double until one takes a twentieth of the budget. Then a batch
  ;; of the form and one of an empty call take turns until the budget is
  ;; spent: at least 3 of each, at most 10. A first call slower than the
  ;; whole budget is the answer, timed once.
  "(let [jonquil-time-t0 (System/nanoTime)
      jonquil-time-f (fn [] FORM)
      jonquil-time-g (fn [] nil)
      jonquil-time-budget (* BUDGET 1000000)
      jonquil-time-slice (quot jonquil-time-budget 20)
      jonquil-time-batch (fn [h n] (let [t0 (System/nanoTime)] (dotimes [_ n] (h)) (- (System/nanoTime) t0)))
      [jonquil-time-n jonquil-time-once] (loop [n 1]
                                           (let [t (jonquil-time-batch jonquil-time-f n)]
                                             (cond (and (= n 1) (>= t jonquil-time-budget)) [1 t]
                                                   (or (>= t jonquil-time-slice) (>= n 1073741824)) [n nil]
                                                   :else (recur (* 2 n)))))
      [jonquil-time-xs jonquil-time-bs] (if jonquil-time-once
                                          [[(double jonquil-time-once)] [0.0]]
                                          (loop [xs [] bs []]
                                            (if (or (= 10 (count xs))
                                                    (and (>= (count xs) 3) (>= (- (System/nanoTime) jonquil-time-t0) jonquil-time-budget)))
                                              [xs bs]
                                              (recur (conj xs (/ (double (jonquil-time-batch jonquil-time-f jonquil-time-n)) jonquil-time-n))
                                                     (conj bs (/ (double (jonquil-time-batch jonquil-time-g jonquil-time-n)) jonquil-time-n))))))
      jonquil-time-k (count jonquil-time-xs)
      jonquil-time-mean (fn [xs] (/ (reduce + xs) (count xs)))
      jonquil-time-m (jonquil-time-mean jonquil-time-xs)
      jonquil-time-base (jonquil-time-mean jonquil-time-bs)
      jonquil-time-spread (Math/sqrt (jonquil-time-mean (map (fn [x] (let [d (- x jonquil-time-m)] (* d d))) jonquil-time-xs)))
      jonquil-time-base-spread (Math/sqrt (jonquil-time-mean (map (fn [x] (let [d (- x jonquil-time-base)] (* d d))) jonquil-time-bs)))
      jonquil-time-per-call (max 0.0 (- jonquil-time-m jonquil-time-base))
      jonquil-time-distinct? DISTINCT
      jonquil-time-show (fn [ns] (let [[v u] (cond (< ns 1e3) [ns \"ns\"] (< ns 1e6) [(/ ns 1e3) \"µs\"]
                                                   (< ns 1e9) [(/ ns 1e6) \"ms\"] :else [(/ ns 1e9) \"s\"])]
                                   (str (/ (Math/round (* v 10.0)) 10.0) \" \" u)))]
  (println (cond
             jonquil-time-once
             (str \"; Time: \" (jonquil-time-show jonquil-time-once) \" for one call (slower than the budget, so timed once)\")
             (jonquil-time-distinct? jonquil-time-per-call jonquil-time-base jonquil-time-spread jonquil-time-base-spread)
             (str \"; Time: \" (jonquil-time-show jonquil-time-per-call) \" per call (± \"
                  (jonquil-time-show jonquil-time-spread) \" over \" jonquil-time-k \" batches of \" jonquil-time-n
                  \"; an empty call's \" (jonquil-time-show jonquil-time-base) \" taken off)\")
             :else
             (str \"; Time: too fast to tell apart from an empty call (\" (jonquil-time-show jonquil-time-base) \")\")))
  {:ns-per-call jonquil-time-per-call :spread-ns jonquil-time-spread :baseline-ns jonquil-time-base
   :batches jonquil-time-k :batch-size jonquil-time-n})")

(defn form-label
  "form-text cut to 60 characters, for a line that names it."
  [form-text]
  (if (> (count form-text) 60) (str (subs form-text 0 60) "…") form-text))

(defn confirm-alert
  "The alert before timing form-text, because every side effect it has
  runs on every call: {:message :detail :buttons}, buttons Time It, Time
  It and don't ask again in this window, and Cancel."
  [form-text]
  {:message (str "Time " (form-label form-text) "?")
   :detail (str "It runs over and over for about a second, often millions of times, "
                "so any side effect, such as writing a file, swapping an atom, printing "
                "or calling a server, happens every time.")
   :buttons ["Time It" "Time It, Don't Ask Again" "Cancel"]})

(def ^:private distinct-snippet
  ;; Measured 2026-11-01: nil timed against an empty call differs by up to
  ;; 1.2% of the empty call on a quiet machine, and by 2% in a run that
  ;; reported "0.1 ns per call" with a 0.0 ns spread (the form's spread
  ;; alone says nothing of the empty call's or of the gap between two
  ;; closures). A time counts when it clears both spreads together and 5%
  ;; of the empty call.
  "(fn [per-call base spread base-spread]
     (and (> per-call (+ spread base-spread)) (>= per-call (* 0.05 base)) (>= per-call 0.05)))")

(defn distinct-code
  "The harness's verdict as code: (fn [per-call base spread base-spread])
  true when per-call is a time, not noise."
  []
  distinct-snippet)

(defn code
  "The harness around form-text for a budget of budget-ms, as text to
  evaluate. It prints one ; Time: line and answers {:ns-per-call
  :spread-ns :baseline-ns :batches :batch-size}. A form that throws throws
  out of it, once."
  [form-text budget-ms]
  (-> template
      (str/replace "DISTINCT" distinct-snippet)
      (str/replace "BUDGET" (str (long budget-ms)))
      (str/replace "FORM" form-text)))
