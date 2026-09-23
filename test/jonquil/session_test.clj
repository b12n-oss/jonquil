(ns jonquil.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.session :as session]))

(defn- run [f s code]
  (let [done (promise)]
    (f s code {:on-complete (fn [r] (deliver done r))})
    (deref done 5000 :timeout)))

(deftest namespaces-created-test
  (is (= #{'b} (session/namespaces-created ['a] ['a 'b 'lib] ['lib])))
  (is (= #{} (session/namespaces-created ['a] ['a] []))))

(deftest reset-mode-test
  (let [s (session/make-session)]
    (is (= :hi (:value (run session/run-definitions! s "(defn greet [] :hi) (greet)"))))
    (testing "a namespace the program created, and one a REPL entry created"
      (run session/run-definitions! s "(ns scratch.p2a) (def q 1)")
      (run session/submit-repl! s "(ns scratch.p2r) (def y 1)")
      (is (some? (find-ns 'scratch.p2a)))
      (is (some? (find-ns 'scratch.p2r))))
    (testing "Run resets: user's greet and both created namespaces are gone"
      (let [r (run session/run-definitions! s "(greet)")]
        (is (= :error (:status r))))
      (is (nil? (find-ns 'scratch.p2a)))
      (is (nil? (find-ns 'scratch.p2r))))
    (testing "user exists again as soon as run-definitions! returns"
      (session/run-definitions! s "(+ 1 2)")
      (is (some? (find-ns 'user))))))

(deftest library-survives-reset-test
  (let [s (session/make-session)]
    (run session/run-definitions! s "(require 'clojure.data) :loaded")
    (let [r (run session/run-definitions! s "(clojure.data/diff [1 2] [1 3])")]
      (is (= :ok (:status r)))
      (is (= "[[nil 2] [nil 3] [1]]" (:printed r))))))

(deftest accumulate-mode-test
  (let [s (session/make-session)]
    (session/set-mode! s :accumulate)
    (run session/run-definitions! s "(defn greet [] :hi)")
    (is (= :hi (:value (run session/run-definitions! s "(greet)"))))))

(deftest repl-follows-run-test
  (let [s (session/make-session)]
    (run session/run-definitions! s "(def x 41)")
    (is (= 42 (:value (run session/submit-repl! s "(inc x)"))))
    (is (= ["(inc x)"] (:items @(:history (:ctx s)))))
    (testing "the prompt follows an (ns ...) the Run ends in"
      (run session/run-definitions! s "(ns scratch.p2p) (def z 7)")
      (is (= 7 (:value (run session/submit-repl! s "z")))))))

(deftest run-while-running-resets-nothing-test
  (let [s (session/make-session)
        done (promise)]
    (session/run-definitions! s "(def k 1) (Thread/sleep 400) k"
                              {:on-complete (fn [r] (deliver done r))})
    (Thread/sleep 50)
    (is (string? (session/run-definitions! s "(+ 1 2)")))
    (testing "the refused Run did not remove user under the running program"
      (let [r (deref done 3000 :timeout)]
        (is (= :ok (:status r)))
        (is (= 1 (:value r)))))))
