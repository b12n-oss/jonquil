(ns jonquil.nrepl.cljs-node-eval-test
  "The ClojureScript-on-node evaluator against dev/fake_cljs_prepl.clj, with
  the child process faked: a launch hook that returns the fake's port."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [fake-cljs-prepl :as fake]
            [jonquil.cljs-node :as cljs-node]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.cljs-node :as node]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.jank]))

(defn- make-test-node [srv overrides]
  (let [launches (atom 0) kills (atom 0)
        e (node/make-cljs-node
           {:profile dialect/cljs :runtime :launch :project-dir nil}
           (merge {:launch (fn [_] (swap! launches inc) {:port (:port srv) :pid -1 :log "/fake/log"})
                   :find-clojure (fn [] "/fake/clojure")
                   :ensure-launcher (fn [] "/fake/launcher.clj")
                   :alive? (fn [_] true)
                   :kill (fn [_] (swap! kills inc))}
                  overrides))]
    {:ev e :launches launches :kills kills}))

(defn- run [e code opts]
  (let [done (promise) out (atom [])]
    (ev/eval! e code (assoc opts
                            :on-output (fn [k s] (swap! out conj [k s]))
                            :on-complete (fn [r] (deliver done r))))
    {:result (deref done 10000 :timeout) :out @out}))

(defn- wait-until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true
                   (> (System/currentTimeMillis) deadline) false
                   :else (do (Thread/sleep 20) (recur))))))

(defn- with-node [fake-opts overrides f]
  (let [srv (fake/start! fake-opts)
        n (make-test-node srv overrides)]
    (try (f srv n) (finally (ev/stop! (:ev n)) (fake/stop! srv)))))

(deftest dispatch-split-test
  (is (instance? jonquil.nrepl.cljs_node.CljsNode
                 (ev/make {:profile dialect/cljs :runtime :launch :project-dir nil})))
  (is (instance? jonquil.nrepl.jank.Jank
                 (ev/make {:profile dialect/jank :runtime :launch :project-dir nil}))))

(deftest run-and-setup-test
  (with-node {} {}
    (fn [srv {:keys [ev launches]}]
      (is (zero? @launches))
      (let [{:keys [result out]} (run ev "(def a 1)\n(+ a 2)" {:run? true})]
        (is (= {:status :ok :printed "3"} (select-keys result [:status :printed])))
        (is (= 'cljs.user (ev/prompt-ns ev)))
        (is (re-find #"Starting ClojureScript" (apply str (map second out)))))
      (is (= cljs-node/setup-form (first (fake/received-forms srv))))
      (is (connection/connected? ev)))))

(deftest error-then-recover-test
  (with-node {:wedge-on-throw-without-setup? true} {}
    (fn [_ {:keys [ev]}]
      (is (= {:status :error :error-text "Error: boom"}
             (select-keys (:result (run ev "(throw (ex-info \"boom\" {}))" {:run? false}))
                          [:status :error-text])))
      (is (= "2" (:printed (:result (run ev "(+ 1 1)" {:run? false}))))))))

(deftest output-before-complete-test
  (with-node {} {}
    (fn [_ {:keys [ev]}]
      (let [order (atom []) done (promise)]
        (ev/eval! ev "(println \"hi\")" {:run? false
                                         :on-output (fn [k s] (swap! order conj [k s]))
                                         :on-complete (fn [r] (swap! order conj :complete) (deliver done r))})
        (deref done 10000 nil)
        (is (some #{[:out "hi\n"]} @order))
        (is (= :complete (last @order)))))))

(deftest unbalanced-sends-nothing-test
  (with-node {} {}
    (fn [srv {:keys [ev launches]}]
      (let [r (:result (run ev "(+ 1" {:run? true}))]
        (is (= :error (:status r)))
        (is (string? (:error-text r))))
      (is (zero? @launches))
      (is (empty? (fake/received-forms srv))))))

(deftest incomplete-input-sends-nothing-test
  (with-node {} {}
    (fn [srv {:keys [ev]}]
      (is (= "2" (:printed (:result (run ev "(+ 1 1)" {:run? false})))))
      (let [before (count (fake/received-forms srv))]
        (doseq [code ["\"abc" "(+ 1 2) #_" "(+ 1 2) '" "(+ 1 2) @" "\\"]]
          (let [r (:result (run ev code {:run? false}))]
            (is (= :error (:status r)) code)
            (is (re-find #"^Error: EOF" (str (:error-text r))) code)))
        (is (= before (count (fake/received-forms srv)))))
      (is (= "3" (:printed (:result (run ev "(+ 1 2)" {:run? false}))))))))

(deftest ns-prefix-test
  (with-node {} {}
    (fn [srv {:keys [ev]}]
      (let [r (:result (run ev "(+ 1 2)" {:run? false :ns-source "(ns demo.core)"}))]
        (is (= "3" (:printed r)))
        (is (= 'demo.core (ev/prompt-ns ev)))
        (is (some #(re-find #"^\(in-ns 'demo.core\)" %) (fake/received-forms srv)))))))

(deftest stop-test
  (with-node {:slow-ms 5000} {}
    (fn [srv {:keys [ev launches kills]}]
      (let [done (promise) out (atom [])]
        (ev/eval! ev "(slow)" {:run? false
                               :on-output (fn [k s] (swap! out conj [k s]))
                               :on-complete (fn [r] (deliver done r))})
        (loop [n 0]
          (when (and (< n 250) (not (some #(re-find #"slow" %) (fake/received-forms srv))))
            (Thread/sleep 20) (recur (inc n))))
        (ev/interrupt! ev)
        (is (= {:status :interrupted} (deref done 5000 :timeout)))
        (is (wait-until #(= 1 @kills) 3000) "Stop's kill runs off the caller's thread")
        (is (re-find #"Stopped" (apply str (map second @out))))
        (is (= "2" (:printed (:result (run ev "(+ 1 1)" {:run? false})))))
        (is (= 2 @launches))))))

(deftest reset-mode-test
  (with-node {} {}
    (fn [_ {:keys [ev launches kills]}]
      (run ev "(def a 1)" {:run? true})
      (is (zero? @kills))
      (let [{:keys [out]} (run ev "(def b 2)" {:run? true})]
        (is (= 1 @kills))
        (is (= 2 @launches))
        (is (re-find #"Reset on Run" (apply str (map second out))))))))

(deftest lookup-not-running-test
  (with-node {} {}
    (fn [_ {:keys [ev]}]
      (is (= node/not-running-doc (:doc (ev/lookup ev 'map "" 0)))))))

(deftest process-exit-test
  (with-node {:slow-ms 5000} {}
    (fn [srv {:keys [ev kills]}]
      (let [done (promise)]
        (ev/eval! ev "(slow)" {:run? false :on-complete (fn [r] (deliver done r))})
        (loop [n 0]
          (when (and (< n 250) (not (some #(re-find #"slow" %) (fake/received-forms srv))))
            (Thread/sleep 20) (recur (inc n))))
        (doseq [s @(:conns srv)] (.close ^java.net.Socket s))
        (is (= {:status :error :error-text node/exited-text} (deref done 5000 :timeout)))
        (is (= 1 @kills))))))

(deftest late-help-reply-is-drained-test
  ;; The help form contains "slow", so the fake answers it after 1.5 s,
  ;; past the 200 ms help bound. The next evaluation must still finish
  ;; with its own value, not wait on or swallow the stale reply.
  (with-node {:slow-ms 1500} {:timings {:help-timeout-ms 200}}
    (fn [_ {:keys [ev]}]
      (is (= "1" (:printed (:result (run ev "1" {:run? false})))))
      (is (= node/timeout-doc (:doc (ev/lookup ev 'slow "" 0))))
      (let [r (:result (run ev "(+ 20 22)" {:run? false}))]
        (is (= {:status :ok :printed "42"} (select-keys r [:status :printed])))))))

(deftest stop-returns-before-a-slow-kill-test
  ;; Stop is called on the GTK thread; killing a JVM and node can take a
  ;; second or two, so the kill runs in the background, and the evaluator
  ;; isn't settled (the registry waits on it before quitting) until it ends.
  (let [release (promise)]
    (with-node {:slow-ms 5000} {:kill (fn [_] (deref release 5000 nil))}
      (fn [srv {:keys [ev]}]
        (let [done (promise) heard (atom [])]
          (connection/on-connection! ev (fn [c] (swap! heard conj c)))
          (ev/eval! ev "(slow)" {:run? false :on-complete (fn [r] (deliver done r))})
          (is (wait-until #(some (fn [f] (re-find #"slow" f)) (fake/received-forms srv)) 5000))
          (let [t0 (System/currentTimeMillis)]
            (ev/interrupt! ev)
            (is (< (- (System/currentTimeMillis) t0) 500) "interrupt! didn't wait for the kill"))
          (is (= {:status :interrupted} (deref done 5000 :timeout)))
          (is (not (node/settled? ev)) "a kill still running keeps the evaluator unsettled")
          (is (= false (last @heard)) "the window hears disconnected at Stop, not when the kill ends")
          (is (= "2" (:printed (:result (run ev "(+ 1 1)" {:run? false})))) "the next eval connects meanwhile")
          (is (= true (last @heard)))
          (deliver release true)
          (is (wait-until #(node/settled? ev) 3000))
          (Thread/sleep 100)
          (is (= true (last @heard)) "the old kill ending says nothing that contradicts the new child"))))))

(deftest output-between-evaluations-test
  ;; node can print after an evaluation finished (a setTimeout, a promise):
  ;; with no evaluation waiting, :out and :err go to the last evaluation's
  ;; on-output instead of being dropped.
  (with-node {} {}
    (fn [_ {:keys [ev]}]
      (let [out (atom [])]
        (ev/eval! ev "(+ 1 1)" {:run? false :on-output (fn [k s] (swap! out conj [k s]))
                                :on-complete (fn [_])})
        (is (wait-until #(not (ev/evaluating? ev)) 5000))
        (#'node/dispatch! ev (atom false) {:tag :out :val "late\n"})
        (#'node/dispatch! ev (atom false) {:tag :err :val "warn\n"})
        (#'node/dispatch! ev (atom false) {:tag :ret :val "9" :form "x"})
        (is (= [[:out "late\n"] [:err "warn\n"]] (filterv (comp #{"late\n" "warn\n"} second) @out)))
        (is (not-any? #(= "9" (second %)) @out) "a stray ret isn't shown")))))

(deftest doc-text-reads-the-doc-forms-value-test
  (let [form (#'node/doc-form 'map)
        doc "-------------------------\ncljs.core/map\n([f])\n  Returns a lazy sequence."]
    (is (= "(with-out-str (cljs.repl/doc map))" form))
    (is (= (str/trim doc)
           (#'node/doc-text [{:tag :out :val "stray\n"}
                             {:tag :ret :form form :val (pr-str doc)}
                             {:tag :ret :form ":jonquil.eval/end-3" :val ":jonquil.eval/end-3"}]
                            form))
        "printed output never passes for docs; only the doc form's value counts")
    (is (= "" (#'node/doc-text [{:tag :out :val "only output\n"}] form)))
    (is (= "" (#'node/doc-text [{:tag :ret :form form :exception true :val "{:cause \"x\"}"}] form)))))
