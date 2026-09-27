(ns jonquil.evaluator-test
  "The in-process engine through the Evaluator protocol: the same answers
  jonquil.session and jonquil.docs give when called directly."
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.dialect :as dialect]
            [jonquil.docs :as docs]
            [jonquil.evaluator :as ev]
            [jonquil.session :as session]))

(defn- run [e code run?]
  (let [done (promise)]
    (ev/eval! e code {:run? run? :on-complete (fn [r] (deliver done r))})
    (deref done 5000 :timeout)))

(defn- fresh []
  (ev/make (dialect/select dialect/profiles nil nil nil)))

(deftest make-dispatches-on-runtime-test
  (let [e (fresh)]
    (is (instance? jonquil.session.InProcess e))
    (is (satisfies? ev/Evaluator e))
    (testing "it keeps the keys the session map had"
      (is (some? (:ctx e)))
      (is (= 'user (:prompt-ns @(:state e)))))
    (is (nil? (ev/start! e)))
    (is (nil? (ev/stop! e)))
    (is (= [] (ev/limitations e)))))

(deftest run-and-repl-test
  (let [e (fresh)]
    (is (= :hi (:value (run e "(defn greet [] :hi) (greet)" true))))
    (is (= :hi (:value (run e "(greet)" false))))
    (is (= ["(greet)"] (:items @(ev/history e))) "a REPL entry is recorded, a Run isn't")
    (testing "Run resets in :reset mode"
      (is (= :error (:status (run e "(greet)" true)))))
    (testing ":accumulate keeps definitions across Runs"
      (ev/set-mode! e :accumulate)
      (is (= :accumulate (:mode @(:state e))))
      (run e "(def kept 5)" true)
      (is (= 5 (:value (run e "kept" true)))))
    (testing "the prompt follows the program's namespace"
      (run e "(ns scratch.evaltest) (def z 7)" true)
      (is (= 'scratch.evaltest (ev/prompt-ns e)))
      (is (= 7 (:value (run e "z" false))))
      (session/reset-namespaces! e))))

(deftest busy-and-interrupt-test
  (let [e (fresh)
        done (promise)]
    (is (nil? (ev/eval! e "(loop [] (recur))" {:run? true :on-complete (fn [r] (deliver done r))})))
    (Thread/sleep 100)
    (is (ev/evaluating? e))
    (is (string? (ev/eval! e "(+ 1 2)" {:run? false})) "busy: the warning string")
    (is (nil? (ev/interrupt! e)))
    (is (= :interrupted (:status (deref done 3000 :timeout))))
    (is (not (ev/evaluating? e)))))

(deftest stdin-test
  (let [e (fresh)
        done (promise)]
    (ev/eval! e "(read-line)" {:run? true :on-complete (fn [r] (deliver done r))})
    (loop [n 0] (when (and (< n 100) (not (ev/waiting-for-input? e))) (Thread/sleep 20) (recur (inc n))))
    (is (ev/waiting-for-input? e))
    (ev/send-input! e "typed")
    (is (= "typed" (:value (deref done 3000 :timeout))))))

(deftest help-matches-docs-test
  (let [e (fresh)]
    (run e "(defn helped \"Helps.\" [x] x)" true)
    (is (= (docs/candidates 'user "hel" "(hel" 4 nil) (ev/complete e "hel" "(hel" 4 nil)))
    (is (some #{"helped"} (map :symbol (ev/complete e "hel" "(hel" 4 nil))))
    (is (= "Helps." (:doc (ev/lookup e "helped" "(helped 1)" 1))))
    (is (= "clojure.core" (:ns (ev/lookup e "map" "(map inc [1])" 1))))
    (is (nil? (ev/lookup e "" "" 0)))))
