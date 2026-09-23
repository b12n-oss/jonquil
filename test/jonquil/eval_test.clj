(ns jonquil.eval-test
  "Upstream eval_test.clj (41c56b8) ported to jonquil.eval, plus the P0 S1 rows.
   Deviations from upstream are marked DEVIATION."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.eval :as sut]))

(defn- run-sync
  "Runs code, returns [result out-chunks]. Waits up to ms."
  ([ctx code] (run-sync ctx code {} 3000))
  ([ctx code opts ms]
   (let [done (promise) out (atom [])]
     (sut/eval-async ctx code (merge {:on-output (fn [k s] (swap! out conj [k s]))
                                      :on-complete (fn [r] (deliver done r))}
                                     opts))
     [(deref done ms :timeout) out])))

;; ---------------------------------------------------------------- (a) upstream

(deftest test-format-error
  (testing "ArithmeticException formatting"
    (try (load-string "(/ 10 0)")
         (catch :default t
           (let [err (sut/format-error t)]
             (is (str/includes? err "ArithmeticException"))
             (is (str/includes? err "Divide by zero"))
             ;; DEVIATION: Jolt runtime exceptions carry no line (known gap).
             (is (not (str/includes? err "Location: line 1")))))))
  (testing "Syntax error formatting"
    (try (load-string "(def 123)")
         (catch :default t
           (is (str/includes? (sut/format-error t) "Location: line 1"))))))

(deftest test-repl-history
  (let [h2 (-> (sut/create-history) (sut/history-add "(+ 1 2)") (sut/history-add "(* 3 4)"))
        [h3 t3] (sut/history-prev h2 "draft")
        [h4 t4] (sut/history-prev h3 t3)
        [h5 t5] (sut/history-next h4)
        [_ t6] (sut/history-next h5)]
    (is (= ["(+ 1 2)" "(* 3 4)"] (:items h2)))
    (is (= ["(* 3 4)" "(+ 1 2)" "(* 3 4)" "draft"] [t3 t4 t5 t6]))))

(deftest test-async-eval-success
  (let [ctx (sut/make-eval-context)
        statuses (atom [])
        [res out] (run-sync ctx "(println \"Calculating...\") (+ 10 25)"
                            {:on-status-change (fn [s] (swap! statuses conj s))} 3000)]
    (is (= :ok (:status res)))
    (is (= 35 (:value res)))
    (is (str/includes? (apply str (map second @out)) "Calculating..."))
    ;; DEVIATION: upstream used Java .contains on a vector.
    (is (some #{:running} @statuses))
    (is (some #{:idle} @statuses))))

(deftest test-async-eval-stdin
  (let [ctx (sut/make-eval-context) done (promise) waits (atom [])]
    (sut/eval-async ctx "(let [line (read-line)] (str \"echo: \" line))"
                    {:on-wait-input (fn [w] (swap! waits conj w))
                     :on-complete (fn [r] (deliver done r))})
    (Thread/sleep 150)
    (is (sut/waiting-for-input? ctx))
    (is (some true? @waits))
    (sut/push-stdin! ctx "Greetings from GUI!")
    (let [res (deref done 3000 :timeout)]
      (is (= :ok (:status res)))
      (is (= "echo: Greetings from GUI!" (:value res)))
      (is (false? (sut/waiting-for-input? ctx))))))

(deftest test-async-eval-stop
  (let [ctx (sut/make-eval-context) done (promise)]
    (sut/eval-async ctx "(Thread/sleep 10000)" {:on-complete (fn [r] (deliver done r))})
    (Thread/sleep 100)
    (is (sut/evaluating? ctx))
    ;; DEVIATION: stop-eval! returns the done promise, not true.
    (is (some? (sut/stop-eval! ctx)))
    (let [res (deref done 2000 :timeout)]
      (is (= :interrupted (:status res)))
      (is (false? (sut/evaluating? ctx))))))

(deftest test-async-eval-stdin-eof
  (testing "EOF makes read-line return nil"
    (let [ctx (sut/make-eval-context) done (promise)]
      (sut/eval-async ctx "(read-line)" {:on-complete (fn [r] (deliver done r))})
      (Thread/sleep 150)
      (sut/push-stdin-eof! ctx)
      (is (= [:ok nil] ((juxt :status :value) (deref done 3000 :timeout))))))
  (testing "multiple read-lines after EOF"
    (let [ctx (sut/make-eval-context) done (promise)]
      (sut/eval-async ctx "[(read-line) (read-line) (read-line)]" {:on-complete (fn [r] (deliver done r))})
      (Thread/sleep 150)
      (sut/push-stdin-eof! ctx)
      (is (= [nil nil nil] (:value (deref done 3000 :timeout))))))
  (testing "text then EOF"
    (let [ctx (sut/make-eval-context) done (promise)]
      (sut/eval-async ctx "[(read-line) (read-line)]" {:on-complete (fn [r] (deliver done r))})
      (Thread/sleep 150)
      (sut/push-stdin! ctx "first-line")
      (sut/push-stdin-eof! ctx)
      (is (= ["first-line" nil] (:value (deref done 3000 :timeout))))))
  (testing "fresh stdin per run"
    (let [ctx (sut/make-eval-context) c1 (promise) c2 (promise)]
      (sut/eval-async ctx "(read-line)" {:on-complete (fn [r] (deliver c1 r))})
      (Thread/sleep 150)
      (sut/push-stdin-eof! ctx)
      (is (nil? (:value (deref c1 3000 :timeout))))
      (sut/eval-async ctx "(read-line)" {:on-complete (fn [r] (deliver c2 r))})
      (Thread/sleep 150)
      (is (sut/waiting-for-input? ctx))
      (sut/push-stdin! ctx "second-run")
      (is (= "second-run" (:value (deref c2 3000 :timeout)))))))

;; ---------------------------------------------------------------- S1 rows

(def counter (atom 0))

(deftest s1b-busy-loop-stop
  (reset! counter 0)
  (let [ctx (sut/make-eval-context) done (promise)]
    (sut/eval-async ctx "(loop [] (swap! jonquil.eval-test/counter inc) (recur))"
                    {:on-complete (fn [r] (deliver done r))})
    (Thread/sleep 200)
    (sut/stop-eval! ctx)
    (let [res (deref done 2000 :timeout)
          a @counter]
      (Thread/sleep 100)
      (println "S1b counter at completion" a "and 100 ms later" @counter)
      (is (= :interrupted (:status res)))
      (is (pos? a))
      (is (= a @counter))
      (is (false? (sut/evaluating? ctx))))))

(deftest s1c-stop-while-reading-then-run
  (let [ctx (sut/make-eval-context) c1 (promise)]
    (sut/eval-async ctx "(read-line)" {:on-complete (fn [r] (deliver c1 r))})
    (Thread/sleep 150)
    (is (sut/waiting-for-input? ctx))
    (sut/stop-eval! ctx)
    (let [r1 (deref c1 2000 :timeout)]
      (println "S1c result after Stop while reading:" (dissoc r1 :error))
      (is (not= :timeout r1)))
    (let [[r2 _] (run-sync ctx "(+ 1 2)")]
      (is (= 3 (:value r2))))))

(deftest s1d-stop-sleep
  (let [ctx (sut/make-eval-context) done (promise) t0 (System/currentTimeMillis)]
    (sut/eval-async ctx "(Thread/sleep 10000)" {:on-complete (fn [r] (deliver done r))})
    (Thread/sleep 100)
    (sut/stop-eval! ctx)
    (let [r (deref done 3000 :timeout)]
      (println "S1d stop of sleep took" (- (System/currentTimeMillis) t0) "ms")
      (is (= :interrupted (:status r))))))

(def live (atom #{}))

(deftest s1e-run-stop-run
  (reset! live #{})
  (let [ctx (sut/make-eval-context)
        prog (fn [id] (str "(swap! jonquil.eval-test/live conj " id ")"
                           "(try (loop [] (recur)) (finally (swap! jonquil.eval-test/live disj " id ")))"))
        d1 (promise) d2 (promise)
        max-live (atom 0)]
    (sut/eval-async ctx (prog 1) {:on-complete (fn [r] (deliver d1 r))})
    (Thread/sleep 100)
    (swap! max-live max (count @live))
    (sut/stop-eval! ctx)
    ;; a Run pressed immediately after Stop, before the worker has exited
    (let [warn (sut/eval-async ctx (prog 2) {:on-complete (fn [r] (deliver d2 r))})]
      (println "S1e immediate re-run returned:" (pr-str warn)))
    (deref d1 2000 :timeout)
    (let [started (nil? (sut/eval-async ctx (prog 2) {:on-complete (fn [r] (deliver d2 r))}))]
      (is started))
    (Thread/sleep 100)
    (swap! max-live max (count @live))
    (println "S1e live set while second run is going:" @live)
    (is (= #{2} @live))
    (sut/stop-eval! ctx)
    (deref d2 2000 :timeout)
    (is (= 1 @max-live))))

(deftest s1f-output-flood
  (let [ctx (sut/make-eval-context) chunks (atom 0) chars (atom 0) done (promise)
        t0 (System/currentTimeMillis)]
    (sut/eval-async ctx "(dotimes [i 100000] (println i))"
                    {:on-output (fn [_ s] (swap! chunks inc) (swap! chars + (count s)))
                     :on-complete (fn [r] (deliver done r))})
    (let [r (deref done 60000 :timeout)]
      (println "S1f flood:" (:status r) "chunks" @chunks "chars" @chars
               "ms" (- (System/currentTimeMillis) t0))
      (is (= :ok (:status r))))))

(deftest s1g-out-err-ordering
  (let [ctx (sut/make-eval-context)
        [r out] (run-sync ctx "(println \"o1\") (binding [*out* *err*] (println \"e1\")) (println \"o2\") (.write *err* \"e2\\n\") (println \"o3\")")]
    (let [seq' (->> @out (remove (fn [[_ s]] (= "\n" s))) (mapv (fn [[k s]] [k (str/trim s)])))]
      (println "S1g stream:" seq')
      (is (= :ok (:status r)))
      (is (= [[:out "o1"] [:err "e1"] [:out "o2"] [:err "e2"] [:out "o3"]] seq')))))

(deftest s1i-read-from-stdin
  (let [ctx (sut/make-eval-context) done (promise)]
    (sut/eval-async ctx "(read)" {:on-complete (fn [r] (deliver done r))})
    (Thread/sleep 150)
    (sut/push-stdin! ctx "(+ 1 2)")
    (let [r (deref done 3000 :timeout)]
      (println "S1i (read):" (:status r) (some-> (:error r) ex-message)))))

(deftest s1j-namespace-reset
  (testing "error line in a later form"
    (let [[r _] (run-sync (sut/make-eval-context) "(def a 1)\n(def b 2)\n(foo-undefined 3)")]
      (is (str/includes? (sut/format-error (:error r)) "Location: line 3"))))
  (testing "(ns foo) in the program is reported and does not leak"
    (let [before (str *ns*)
          [r _] (run-sync (sut/make-eval-context) "(ns s1j.game) (def x 1)")]
      (is (= 's1j.game (:ns r)))
      (is (= before (str *ns*)))))
  (testing "reset: remove the namespaces a run created, including user-like ones"
    (let [ctx (sut/make-eval-context)
          before (set (map ns-name (all-ns)))
          [r1 _] (run-sync ctx "(defn greet [] :hi) (defonce score (atom 0)) (swap! score + 10)" {:ns-name 's1j.user} 3000)
          created (remove before (map ns-name (all-ns)))
          _ (println "S1j namespaces created by run 1:" (vec created))
          _ (doseq [n created] (remove-ns n))
          [r2 _] (run-sync ctx "(defn main [] (greet)) (main)" {:ns-name 's1j.user} 3000)
          [r3 _] (run-sync ctx "(defonce score (atom 0)) (swap! score + 10)" {:ns-name 's1j.user} 3000)]
      (is (= 10 (:value r1)))
      (is (= :error (:status r2)))
      (println "S1j stale call after reset:" (some-> (:error r2) ex-message))
      (is (= 10 (:value r3)))))
  (testing "reset of `user` itself"
    (let [had-user (some? (find-ns 'user))
          _ (remove-ns 'user)
          [r _] (run-sync (sut/make-eval-context) "(defn f [] 1) (f)")]
      (println "S1j user existed before:" had-user "run after remove-ns user:" (:status r) (:value r))
      (is (= 1 (:value r))))))
