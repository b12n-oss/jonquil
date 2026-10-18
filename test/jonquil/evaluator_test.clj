(ns jonquil.evaluator-test
  "The in-process engine through the Evaluator protocol: the same answers
  jonquil.session and jonquil.docs give when called directly."
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.dialect :as dialect]
            [jonquil.docs :as docs]
            [jonquil.evaluator :as ev]
            ;; Bare, unaliased (jonquil.nrepl.connection-test's own
            ;; convention): loaded only for its :cljd defmethod on
            ;; connection/make-attached, transitively registering
            ;; connection.clj's own :attach defmethod on ev/make too --
            ;; full-doc-protocol-test's second assertion needs both to
            ;; dispatch at all.
            [jonquil.nrepl.attach]
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
      (session/reset-namespaces! e :run))))

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

(deftest full-doc-protocol-test
  (is (not (satisfies? ev/FullDoc (ev/make {:runtime :in-process}))))
  (is (not (satisfies? ev/FullDoc (ev/make {:profile jonquil.dialect/cljd :runtime :attach :project-dir "/nowhere"})))))

(deftest inspector-protocol-test
  (is (satisfies? ev/Inspector (ev/make {:runtime :in-process})))
  (is (true? (ev/inspectable? (ev/make {:runtime :in-process}))))
  (is (not (satisfies? ev/Inspector (ev/make {:profile jonquil.dialect/cljd :runtime :attach :project-dir "/nowhere"})))))

(deftest help-matches-docs-test
  (let [e (fresh)]
    (run e "(defn helped \"Helps.\" [x] x)" true)
    (is (= (docs/candidates 'user "hel" "(hel" 4 nil) (ev/complete e "hel" "(hel" 4 nil)))
    (is (some #{"helped"} (map :symbol (ev/complete e "hel" "(hel" 4 nil))))
    (is (= "Helps." (:doc (ev/lookup e "helped" "(helped 1)" 1))))
    (is (= "clojure.core" (:ns (ev/lookup e "map" "(map inc [1])" 1))))
    (is (nil? (ev/lookup e "" "" 0)))))

(deftest ask-help-test
  (testing "an answer inside the grace wait comes back now, and on-later never runs"
    (let [later (atom 0)]
      (is (= [:now 7] (ev/ask-help! 1000 (fn [] 7) (fn [_] (swap! later inc)))))
      (Thread/sleep 50)
      (is (zero? @later))))
  (testing "nil is an answer too"
    (is (= [:now nil] (ev/ask-help! 1000 (fn [] nil) (fn [_] nil)))))
  (testing "a late answer goes to on-later, with the long limit bound"
    (let [got (promise)]
      (is (= [:later] (ev/ask-help! 10 (fn [] (Thread/sleep 150) (ev/help-timeout 300)) (fn [r] (deliver got r)))))
      (is (= ev/async-help-timeout-ms (deref got 2000 :timeout)))))
  (testing "a throw is an answer help-error reads, late and now"
    (let [got (promise)]
      (ev/ask-help! 10 (fn [] (Thread/sleep 50) (throw (ex-info "boom" {}))) (fn [r] (deliver got r)))
      (is (= "boom" (ev/help-error (deref got 2000 :timeout)))))
    (is (= "bang" (ev/help-error (second (ev/ask-help! 1000 (fn [] (throw (ex-info "bang" {}))) (fn [_] nil))))))
    (is (nil? (ev/help-error [{:symbol "map"}])) "a candidate list isn't an error")
    (is (nil? (ev/help-error {:status :found :name "map"})) "nor is a doc"))
  (testing "every request settles exactly once, whichever side wins"
    (let [later (atom 0)
          nows (count (filter #(= :now (first %))
                              (doall (for [_ (range 100)] (ev/ask-help! 0 (fn [] 1) (fn [_] (swap! later inc)))))))]
      ;; the late ones land on worker threads: poll, rather than guess how long
      (loop [n 0] (when (and (< n 300) (< (+ nows @later) 100)) (Thread/sleep 10) (recur (inc n))))
      (is (= 100 (+ nows @later)))))
  (testing "unbound, help-timeout is the evaluator's own"
    (is (= 300 (ev/help-timeout 300)))))

(deftest server-help-test
  (is (not (ev/server-help? (fresh))) "in-process Jolt is asked directly"))
