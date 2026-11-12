(ns jonquil.eval-test
  "Upstream eval_test.clj (41c56b8) ported to jonquil.eval, plus Jonquil's own rows.
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
             ;; Jolt since #1489 (not the Linux image's v0.8.23) wraps the
             ;; throw in a CompilerException carrying the form's line, as
             ;; JVM Clojure does; the Location shows exactly when it's there
             (is (= (boolean (:clojure.error/line (ex-data t)))
                    (str/includes? err "Location: line 1")))))))
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

;; ---------------------------------------------------------------- rows

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
      (is (not= :timeout r1))
      (is (= :interrupted (:status r1))))
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

;; "Run pressed immediately after Stop, before the worker has exited" does
;; not always get the "already in progress" warning: the tight Stop
;; handshake means that immediate call can sometimes race ahead of run1's
;; worker and actually START run 2 (returning nil) instead of warning -- both outcomes are legitimate
;; results of a real race, and the test's actual invariant (at most one
;; program is ever live, and a second run does get going once the first
;; has stopped) holds under either one. So: if the immediate call started
;; run 2, use it as run 2; otherwise wait for run1 to fully finish and
;; start run 2 afresh, as before.
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
    (let [immediate (sut/eval-async ctx (prog 2) {:on-complete (fn [r] (deliver d2 r))})]
      (println "S1e immediate re-run returned:" (pr-str immediate))
      (deref d1 2000 :timeout)
      (when (some? immediate)
        ;; immediate got the warning (the pre-round-1 outcome): run1 has
        ;; since finished, so this call should start run 2 for real.
        (let [started (nil? (sut/eval-async ctx (prog 2) {:on-complete (fn [r] (deliver d2 r))}))]
          (is started))))
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
    (let [r (deref done 60000 :timeout)
          ;; 100000 numbers 0..99999 as digit chars, plus one newline each.
          expected-chars (+ (reduce + (map (comp count str) (range 100000))) 100000)]
      (println "S1f flood:" (:status r) "chunks" @chunks "chars" @chars
               "ms" (- (System/currentTimeMillis) t0))
      (is (= :ok (:status r)))
      (is (= expected-chars @chars)))))

(deftest s1g-out-err-ordering
  (let [ctx (sut/make-eval-context)
        [r out] (run-sync ctx "(println \"o1\") (binding [*out* *err*] (println \"e1\")) (println \"o2\") (.write *err* \"e2\\n\") (println \"o3\")")
        seq' (->> @out (remove (fn [[_ s]] (= "\n" s))) (mapv (fn [[k s]] [k (str/trim s)])))]
    (println "S1g stream:" seq')
    (is (= :ok (:status r)))
    (is (= [[:out "o1"] [:err "e1"] [:out "o2"] [:err "e2"] [:out "o3"]] seq'))))

(deftest s1i-read-from-stdin
  (let [ctx (sut/make-eval-context) done (promise)]
    (sut/eval-async ctx "(read)" {:on-complete (fn [r] (deliver done r))})
    (Thread/sleep 150)
    (sut/push-stdin! ctx "(+ 1 2)")
    (let [r (deref done 3000 :timeout)]
      (println "S1i (read):" (:status r) (some-> (:error r) ex-message))
      ;; `read` returns once the form is complete, without waiting for EOF on
      ;; the console's stdin (jolt-lang/jolt#1133 and #1137).
      (is (= [:ok '(+ 1 2)] ((juxt :status :value) r))))))

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
      (is (= 1 (:value r)))
      ;; Clean up: eval-async's own create-ns recreated `user` and this run
      ;; defined `f` in it, which would otherwise leak `f` into `user` for
      ;; every later test that runs in the default namespace.
      (when (find-ns 'user) (ns-unmap 'user 'f)))))

;; ---------------------------------------------------------------- idle notify

(deftest idle-notify-throw-does-not-block-completion
  (let [ctx (sut/make-eval-context)
        done (promise)
        status-threw? (atom false)]
    (sut/eval-async ctx "(+ 1 1)"
                    {:on-status-change (fn [s]
                                         (when (= :idle s)
                                           (reset! status-threw? true)
                                           (throw (ex-info "boom from on-status-change" {}))))
                     :on-complete (fn [r] (deliver done r))})
    (let [r (deref done 3000 :timeout)]
      (is (true? @status-threw?))
      (is (not= :timeout r))
      (is (= :ok (:status r)))
      (is (= 2 (:value r)))
      (is (false? (sut/evaluating? ctx))))))

(deftest stop-immediately-after-run-interrupts-promptly
  (dotimes [i 5]
    (let [ctx (sut/make-eval-context)
          done (promise)
          t0 (System/currentTimeMillis)]
      (sut/eval-async ctx "(Thread/sleep 10000)" {:on-complete (fn [r] (deliver done r))})
      (sut/stop-eval! ctx)
      (let [r (deref done 5000 :timeout)
            elapsed (- (System/currentTimeMillis) t0)]
        (println "FIX2 iteration" i "stop-immediately-after-run took" elapsed "ms, result" (:status r))
        (is (not= :timeout r) (str "iteration " i " timed out waiting for :interrupted"))
        (is (= :interrupted (:status r)) (str "iteration " i))
        (is (< elapsed 5000) (str "iteration " i " took " elapsed "ms, expected well under the 10000ms sleep"))))))

(deftest no-stale-idle-after-next-run-starts
  ;; A watch on running-state fires SYNCHRONOUSLY, on run1's own worker
  ;; thread, as part of the very reset! that flips it to idle-state -- i.e.
  ;; strictly before run1 goes on to call on-status-change :idle. From
  ;; inside that watch we start run2 and then sleep briefly before letting
  ;; the watch (and so the reset!) return, which gives run2's own worker a
  ;; window to run if nothing serialises it against run1's still-pending
  ;; :idle notification. This is exactly the gap
  ;; (a new run starting between the old worker's state reset and its
  ;; notification), widened deterministically instead of hoping a real
  ;; scheduler race lands the wrong way often enough to catch in CI.
  (dotimes [i 5]
    (let [ctx (sut/make-eval-context)
          log (atom [])
          run2-started? (atom false)
          run1-done (promise)
          run2-done (promise)
          watch-key (gensym "fix3-watch")]
      (add-watch (:running-state ctx) watch-key
                 (fn [_ _ old new]
                   (when (and (= :idle (:status new))
                              (not= :idle (:status old))
                              (compare-and-set! run2-started? false true))
                     (sut/eval-async ctx "(+ 2 2)"
                                     {:on-status-change (fn [s2] (swap! log conj [:run2 s2]))
                                      :on-complete (fn [r2] (deliver run2-done r2))})
                     (Thread/sleep 200))))
      (sut/eval-async ctx "(+ 1 1)"
                      {:on-status-change (fn [s] (swap! log conj [:run1 s]))
                       :on-complete (fn [r1] (deliver run1-done r1))})
      ;; Wait for BOTH runs to fully finish (not just run2, which can race
      ;; ahead of run1's own :idle notification here) before reading the
      ;; log, or we'd race the test's own assertions against run1's worker.
      (let [r1 (deref run1-done 3000 :timeout)
            r2 (deref run2-done 3000 :timeout)
            entries @log
            idle1-idx (first (keep-indexed (fn [i [who s]] (when (and (= who :run1) (= s :idle)) i)) entries))
            running2-idx (first (keep-indexed (fn [i [who s]] (when (and (= who :run2) (= s :running)) i)) entries))]
        (remove-watch (:running-state ctx) watch-key)
        (println "FIX3 iteration" i "notification log:" entries)
        (is (not= :timeout r1) (str "iteration " i))
        (is (not= :timeout r2) (str "iteration " i))
        (is (= 2 (:value r1)) (str "iteration " i))
        (is (= 4 (:value r2)) (str "iteration " i))
        (is (some? idle1-idx) (str "iteration " i ": run1 must have notified :idle"))
        (is (some? running2-idx) (str "iteration " i ": run2 must have notified :running"))
        (is (< idle1-idx running2-idx)
            (str "iteration " i ": run2's :running notification must never precede run1's :idle notification, log=" entries))))))

;; ---------------------------------------------------------------- Stop races

(deftest stop-clears-interrupt-before-blocking-oncomplete
  (let [ctx (sut/make-eval-context)
        q (java.util.concurrent.LinkedBlockingQueue.)]
    (sut/eval-async ctx "(loop [] (recur))" {:on-complete (fn [r] (.put q r))})
    (Thread/sleep 100)
    (sut/stop-eval! ctx)
    (let [item (.poll q 3000 java.util.concurrent.TimeUnit/MILLISECONDS)]
      (is (some? item) "on-complete's blocking .put must still deliver after Stop")
      (is (= :interrupted (:status item))))))

(deftest create-writer-char-array-branches
  (testing "1-arg write with a char array"
    (let [out (atom [])
          w (sut/create-writer (fn [s] (swap! out conj s)))]
      (.write w (char-array "hi"))
      (is (= "hi" (apply str @out)))))
  (testing "3-arg write with a char array slice"
    (let [out (atom [])
          w (sut/create-writer (fn [s] (swap! out conj s)))]
      (.write w (char-array "hello") 1 3)
      (is (= "ell" (apply str @out)))))
  (testing "through eval-async, *out* is bound to the capture writer"
    (let [[r out] (run-sync (sut/make-eval-context) "(.write *out* (char-array \"hi\\n\"))")]
      (is (= :ok (:status r)))
      (is (str/includes? (apply str (map second @out)) "hi")))))

(deftest printed-realizes-under-binding-and-truncates
  (testing "side effects of realizing :value arrive through on-output, :printed matches"
    (let [[r out] (run-sync (sut/make-eval-context)
                            "(map (fn [x] (println :side x) x) [1 2])")]
      (is (= :ok (:status r)))
      (is (= "(1 2)" (:printed r)))
      (is (str/includes? (apply str (map second @out)) ":side 1"))
      (is (str/includes? (apply str (map second @out)) ":side 2"))))
  (testing "an infinite seq truncates via *print-length* instead of hanging"
    (let [[r _] (run-sync (sut/make-eval-context) "(range)" {} 5000)]
      (is (= :ok (:status r)))
      (is (str/starts-with? (:printed r) "(0 1 2"))
      (is (str/ends-with? (:printed r) "...)")))))

(deftest doc-referred-and-honours-bound-out
  (let [[r out] (run-sync (sut/make-eval-context) "(doc inc)")]
    (is (= :ok (:status r)))
    (is (str/includes? (apply str (map second @out)) "inc"))))

(deftest history-vars-shift-after-each-value
  (let [ctx (sut/make-eval-context)]
    (doseq [c ["1" "2" "3"]] (run-sync ctx c))
    (is (= "[3 2 1]" (:printed (first (run-sync ctx "[*1 *2 *3]")))))
    (is (= "[[3 2 1] 3 2]" (:printed (first (run-sync ctx "[*1 *2 *3]")))))))

(deftest star-e-holds-the-last-error
  (let [ctx (sut/make-eval-context)]
    (run-sync ctx "1")
    (is (= :error (:status (first (run-sync ctx "(throw (ex-info \"boom\" {:k 1}))")))))
    (is (= "{:k 1}" (:printed (first (run-sync ctx "(ex-data *e)")))))
    (is (= "{:k 1}" (:printed (first (run-sync ctx "(ex-data *e)")))) "a later success keeps *e")
    (is (= "1" (:printed (first (run-sync ctx "*3")))) "an error doesn't shift *1")))

(deftest stop-leaves-history-vars-alone
  (let [ctx (sut/make-eval-context) done (promise)]
    (run-sync ctx "1")
    (run-sync ctx "(throw (ex-info \"e1\" {}))")
    (sut/eval-async ctx "(loop [] (recur))" {:on-complete (fn [r] (deliver done r))})
    (Thread/sleep 200)
    (sut/stop-eval! ctx)
    (is (= :interrupted (:status (deref done 2000 :timeout))))
    (is (= "[1 \"e1\"]" (:printed (first (run-sync ctx "[*1 (ex-message *e)]")))))))

(deftest pprint-referred-and-honours-bound-out
  (let [[r out] (run-sync (sut/make-eval-context) "(pprint {:a 1 :b [1 2 3]})")]
    (is (= :ok (:status r)))
    (is (= [[:out "{:a 1, :b [1 2 3]}\n"]] @out))))

(deftest pp-referred-and-prints-the-last-value
  (let [ctx (sut/make-eval-context)]
    (run-sync ctx "{:a 1 :b [1 2 3]}")
    (let [[r out] (run-sync ctx "(pp)")]
      (is (= :ok (:status r)))
      (is (= [[:out "{:a 1, :b [1 2 3]}\n"]] @out)))))

(deftest stop-leaves-a-finishing-worker-alone
  (let [ctx (sut/make-eval-context)
        flag (atom false)]
    (reset! (:running-state ctx) {:status :running :finishing true
                                  :token (jolt.host/make-interrupt)
                                  :done (promise) :stop-requested flag})
    (is (nil? (sut/stop-eval! ctx)))
    (is (false? @flag) "a finishing run is not re-classified as interrupted")))

(deftest before-idle-runs-while-still-evaluating-test
  ;; jonquil.session marks a Run's vars there, so nothing else may evaluate
  ;; until it returns.
  (let [ctx (sut/make-eval-context)
        seen (atom nil)
        [r] (run-sync ctx "(+ 1 2)"
                      {:before-idle (fn [res] (reset! seen {:status (:status res) :busy (sut/evaluating? ctx)
                                                            :refused (string? (sut/eval-async ctx "1" {}))}))}
                      3000)]
    (is (= :ok (:status r)))
    (is (= {:status :ok :busy true :refused true} @seen) "the context is still busy, so a second eval is refused")
    (is (not (sut/evaluating? ctx)) "and it's idle once on-complete has run"))
  (testing "a throwing before-idle still completes"
    (let [ctx (sut/make-eval-context)
          [r] (binding [*err* (java.io.StringWriter.)]
                (run-sync ctx "(+ 1 2)" {:before-idle (fn [_] (throw (ex-info "boom" {})))} 3000))]
      (is (= 3 (:value r))))))

(deftest eval-string-keeps-the-switch-test
  ;; Jolt from jolt-lang/jolt #1460 binds *ns* around load-string, as JVM
  ;; Clojure does; eval-string must leave the code's own switch in place
  (binding [*ns* (the-ns 'user)]
    (is (= 1 (sut/eval-string "(ns jonquil.eval-test.switched) (def v 1) v")))
    (is (= 'jonquil.eval-test.switched (ns-name *ns*))))
  (binding [*ns* (the-ns 'user)]
    (sut/eval-string "(ns jonquil.eval-test.switched) (in-ns 'user)")
    (is (= 'user (ns-name *ns*)))))

(deftest wrap-eval-test
  (let [ran (atom 0)
        [r _] (run-sync (sut/make-eval-context) "(+ 1 2)"
                        {:wrap-eval (fn [thunk] (swap! ran inc) (thunk))} 3000)]
    (is (= [:ok "3"] [(:status r) (:printed r)]))
    (is (= 1 @ran))))

(deftest before-eval-test
  (testing "runs on the worker before the evaluation, with *out* already the console's"
    (let [order (atom [])
          [r out] (run-sync (sut/make-eval-context) "(+ 1 2)"
                            {:before-eval (fn [] (swap! order conj :before) (print "hi"))} 3000)]
      (is (= 3 (:value r)))
      (is (= [:before] @order))
      (is (= [[:out "hi"]] @out))))
  (testing "a throw is reported and the evaluation goes ahead"
    (let [[r _] (binding [*err* (java.io.StringWriter.)]
                  (run-sync (sut/make-eval-context) "(+ 1 2)"
                            {:before-eval (fn [] (throw (ex-info "boom" {})))} 3000))]
      (is (= 3 (:value r)))))
  (testing "an interrupt signal thrown from it stops the evaluation"
    (let [[r _] (run-sync (sut/make-eval-context) "(reset! jonquil.eval-test/counter -1)"
                          {:before-eval (fn [] (throw (ex-info "stop" {:jolt/interrupted true})))} 3000)]
      (is (= :interrupted (:status r)))
      (is (not= -1 @counter) "the code never ran"))))

(deftest quiet-leaves-history-vars-test
  (let [ctx (sut/make-eval-context)]
    (run-sync ctx "1")
    (run-sync ctx "2" {:quiet true} 3000)
    (is (= "1" (:printed (first (run-sync ctx "*1")))))))

(deftest read-line-callback-sequence-test
  (let [ctx (sut/make-eval-context) done (promise) statuses (atom [])]
    (sut/eval-async ctx "(read-line)" {:on-status-change (fn [st] (swap! statuses conj st))
                                       :on-complete (fn [r] (deliver done r))})
    (loop [i 0] (when (and (< i 300) (not (sut/waiting-for-input? ctx))) (Thread/sleep 10) (recur (inc i))))
    (sut/push-stdin! ctx "hello")
    (let [r (deref done 3000 :timeout)]
      (Thread/sleep 30)
      (is (= :ok (:status r)))
      (is (= "\"hello\"" (:printed r)))
      (is (= [:running :waiting-input :running :idle] @statuses)))))

(deftest settled-waits-for-on-complete-test
  (testing "the context goes idle before on-complete runs; settled? waits for on-complete too"
    (let [ctx (sut/make-eval-context)
          entered (promise)
          release (promise)
          done (promise)]
      (is (sut/settled? ctx) "a fresh context is settled")
      (sut/eval-async ctx "(+ 1 2)" {:on-complete (fn [r] (deliver entered true) @release (deliver done r))})
      (is (true? (deref entered 5000 :timeout)) "on-complete started")
      (is (not (sut/evaluating? ctx)) "idle already, while on-complete is still running")
      (is (not (sut/settled? ctx)) "but not settled")
      (deliver release true)
      (is (= :ok (:status (deref done 5000 :timeout))))
      (is (loop [n 0] (cond (sut/settled? ctx) true (> n 200) false :else (do (Thread/sleep 10) (recur (inc n)))))
          "settled once on-complete returned")))
  (testing "an on-complete that throws still settles"
    (let [ctx (sut/make-eval-context)
          done (promise)]
      (sut/eval-async ctx "(+ 1 2)" {:on-complete (fn [_] (deliver done true) (throw (ex-info "boom" {})))})
      (deref done 5000 :timeout)
      (is (loop [n 0] (cond (sut/settled? ctx) true (> n 200) false :else (do (Thread/sleep 10) (recur (inc n)))))))))
