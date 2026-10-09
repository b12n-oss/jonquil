(ns jonquil.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.evaluator :as ev]
            [jonquil.session :as session]))

(defn- run
  ([f s code] (run f s code {}))
  ([f s code opts]
   (let [done (promise)]
     (f s code (assoc opts :on-complete (fn [r] (deliver done r))))
     (deref done 5000 :timeout))))

(defn- run-sync [s code] (run session/run-definitions! s code))
(defn- repl-sync [s code] (run session/submit-repl! s code))

(defn- inspect-sync [s op args]
  (let [p (promise)]
    (ev/inspect! s op args (fn [r] (deliver p r)))
    (deref p 2000 :timeout)))

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

(deftest owner-reset-keeps-other-owners-test
  (let [s (session/make-session)]
    (run session/run-definitions! s "(ns tabs.a) (defn hello [] :hi)" {:owner 1})
    (run session/run-definitions! s "(ns tabs.b) (def x 1)" {:owner 2})
    (is (= #{'tabs.a} (get (:created @(:state s)) 1)))
    (is (= #{'tabs.b} (get (:created @(:state s)) 2)))
    (testing "a reset Run for owner 2 leaves owner 1's namespace"
      (run session/run-definitions! s "(ns tabs.b) (def x 2)" {:owner 2})
      (is (some? (find-ns 'tabs.a)))
      (is (= :hi (:value (run session/submit-repl! s "(tabs.a/hello)")))))
    (testing "owner 1's own Run removes and recreates tabs.a"
      (run session/run-definitions! s "(ns tabs.a) (def only 1)" {:owner 1})
      (is (nil? (ns-resolve 'tabs.a 'hello))))))

(deftest owner-repl-namespaces-go-with-any-run-test
  (let [s (session/make-session)]
    (run session/submit-repl! s "(create-ns 'tabs.scratch)")
    (is (= #{'tabs.scratch} (get (:created @(:state s)) :repl)))
    (run session/run-definitions! s "(+ 1 2)" {:owner 7})
    (is (nil? (find-ns 'tabs.scratch)))))

(deftest owner-run-while-running-test
  (let [s (session/make-session)
        done (promise)]
    (run session/run-definitions! s "(ns tabs.keep) (def v 1)" {:owner 1})
    (session/run-definitions! s "(Thread/sleep 400) :done"
                              {:owner 2 :on-complete (fn [r] (deliver done r))})
    (Thread/sleep 50)
    (is (string? (session/run-definitions! s "(+ 1 2)" {:owner 1})))
    (deref done 3000 :timeout)
    (is (some? (find-ns 'tabs.keep)) "the refused Run removed nothing")))

(deftest last-value-test
  (let [s (session/make-session)]
    (run-sync s "{:a [1 2]}")
    (is (= "PersistentArrayMap · 1 items" (:info (inspect-sync s :start {:rows 10 :cols 40}))))
    (repl-sync s "(throw (ex-info \"no\" {}))")
    (is (= "PersistentArrayMap · 1 items" (:info (inspect-sync s :start {:rows 10 :cols 40})))
        "an error leaves the last value")
    (repl-sync s "[7 8 9]")
    (is (= "*1 / 1" (:path (do (inspect-sync s :start {:rows 10 :cols 40})
                               (inspect-sync s :in {:idx 1})))))))

(deftest inspect-def-test
  (let [s (session/make-session)]
    (repl-sync s "(ns scratch.inspect)")
    (repl-sync s "{:k 42}")
    (inspect-sync s :start {:rows 10 :cols 40})
    (inspect-sync s :in {:idx 0})
    (is (= "#'scratch.inspect/answer" (:defd (inspect-sync s :def {:name "answer"}))))
    (is (= 42 @(ns-resolve 'scratch.inspect 'answer)))))

(deftest inspect-throw-test
  ;; Review focus 4: a value whose datafy throws answers with a :note, and
  ;; on-view still fires exactly once.
  (let [s (session/make-session) calls (atom 0) p (promise)]
    (repl-sync s "(reify clojure.core.protocols/Datafiable (datafy [_] (throw (ex-info \"boom\" {}))))")
    (ev/inspect! s :start {:rows 10 :cols 40} (fn [r] (swap! calls inc) (deliver p r)))
    (is (= "boom" (:note (deref p 2000 :timeout))))
    (Thread/sleep 100)
    (is (= 1 @calls))))

(deftest inspect-outer-catch-test
  ;; A def whose prompt namespace no longer exists fails before run-op: the
  ;; outer catch answers {:error ...} with a non-nil message, exactly once.
  (let [s (session/make-session)]
    (swap! (:state s) assoc :prompt-ns 'jonquil.no-such-ns-for-inspect)
    (let [r (inspect-sync s :def {:name "x"})]
      (is (string? (:error r))))))
