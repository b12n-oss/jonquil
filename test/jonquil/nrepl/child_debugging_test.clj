(ns jonquil.nrepl.child-debugging-test
  "Live nREPL tests of debugging in the Jolt child: Break on error, breakpoints and stepping, trace, worlds, live results and fibers (the rest of the evaluator is jonquil.nrepl.evaluator-test)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.breakpoints :as bp]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.child-test :refer [temp-project]]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.evaluator :as nrepl]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- make [dir]
  (ev/make {:profile dialect/jolt :runtime :nrepl :project-dir dir}))

(defn- run
  "Evaluates and waits. [result output] where output is every on-output
  call as [kind text]."
  ([e code run?] (run e code run? nil))
  ([e code run? path]
   (let [done (promise) out (atom [])]
     (ev/eval! e code {:run? run? :path path
                       :on-output (fn [k s] (swap! out conj [k s]))
                       :on-complete (fn [r] (deliver done r))})
     [(deref done 20000 :timeout) @out])))

(defn- wait-until [pred ms]
  (loop [n 0] (cond (pred) true (> (* n 20) ms) false :else (do (Thread/sleep 20) (recur (inc n))))))

(defn- wait-for-eval-id
  "Polls (:state e)'s [:running :id] every 10 ms, up to about 5000 ms,
  standing in for 'the eval request is out' instead of a fixed sleep: a
  guessed sleep could still fire before send-main! records :id (evaluator.
  clj's own send-main!/interrupt!), under load, sending the caller's next
  step (an interrupt or a kill signal) too early. Fails the test with a
  clear message on timeout rather than silently letting the next step run
  against a request that was never sent."
  [e]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (if-let [id (get-in @(:state e) [:running :id])]
        id
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 10) (recur))
          (do (is false "the eval request's :id never appeared in :running within 5000 ms")
              nil))))))

(defn- inspect [e op args]
  (let [p (promise)]
    (ev/inspect! e op args (fn [v] (deliver p v)))
    (deref p 10000 :timeout)))

(defn- run-quiet [e code ns-source]
  (let [done (promise) out (atom [])]
    (ev/eval! e code {:quiet "jonquil-quiet-test:" :ns-source ns-source
                      :on-output (fn [k s] (swap! out conj [k s]))
                      :on-complete (fn [r] (deliver done r))})
    [(deref done 20000 :timeout) @out]))

;; --- Break on error in the child ------------------------------------------------

(deftest paused-and-resumed-message-test
  (is (= {:title "T" :frames []} (nrepl/paused-message {:jonquil-paused "{:title \"T\" :frames []}"})))
  (is (nil? (nrepl/paused-message {:out "x"})))
  (is (= {:continued? true} (nrepl/resumed-message {:jonquil-resumed "continued"})))
  (is (= {:continued? false} (nrepl/resumed-message {:jonquil-resumed "stopped"})))
  (is (nil? (nrepl/resumed-message {:value "1"}))))

(deftest breakable-test
  (is (true? (nrepl/breakable? {:break-on? true} {})))
  (is (false? (nrepl/breakable? {:break-on? true} {:quiet "token:"})) "a quiet eval")
  (is (false? (nrepl/breakable? {:break-on? true} {:no-break true})) "auto-require's")
  (is (false? (nrepl/breakable? {:break-on? false} {}))))

(deftest breakpoints-armed-test
  (is (true? (nrepl/breakpoints-armed? {:breakpoints-on? true} {})))
  (is (false? (nrepl/breakpoints-armed? {:breakpoints-on? true} {:quiet "token:"})) "a quiet eval")
  (is (false? (nrepl/breakpoints-armed? {:breakpoints-on? true} {:no-break true})) "auto-require's")
  (is (false? (nrepl/breakpoints-armed? {} {}))))

(deftest breakpoints-reply-test
  (is (= {:on? true :debug-turned-on? true} (nrepl/breakpoints-reply [{:on "true" :debug-turned-on "true"}])))
  (is (= {:on? false :debug-turned-on? false} (nrepl/breakpoints-reply [{:on "false"}])))
  (is (= {:error "nope"} (nrepl/breakpoints-reply [{:error "nope"}])))
  (is (:error (nrepl/breakpoints-reply [{:status ["done"]}]))))

(defn- toggle-break-sync [e]
  (let [p (promise)] (ev/toggle-break! e (fn [r] (deliver p r))) (deref p 20000 :timeout)))

(defn- break-eval-sync [e i code]
  (let [p (promise)] (ev/break-eval! e i code (fn [r] (deliver p r))) (deref p 20000 :timeout)))

(defn- start-run
  "Starts an eval without waiting; a promise of its result."
  [e code opts]
  (let [done (promise)]
    (ev/eval! e code (assoc opts :run? false :on-complete (fn [r] (deliver done r))))
    done))

(deftest break-in-the-child-test
  ;; live: needs a child Jolt with jolt.debug, which JONQUIL_JOLT can name
  ;; (JONQUIL_JOLT=~/.local/bin/jolt-debug jolt-debug -M:test ...)
  (let [e (make (temp-project))]
    (try
      (is (= "3" (:printed (first (run e "(+ 1 2)" false)))) "the child is up")
      (let [r (toggle-break-sync e)]
        (if (and (:error r) (not (System/getenv "JONQUIL_JOLT")))
          (do (println "SKIP break-in-the-child-test: this child can't break:" (:error r))
              (is (string? (:error r)) "a child without jolt.debug says so"))
          (let [paused (atom (promise))
                resumed (atom [])]
            (is (= {:on? true} r))
            (ev/set-break-handlers! e {:on-pause (fn [d] (deliver @paused d))
                                       :on-resume (fn [how] (swap! resumed conj how))})
            ;; debug mode first, so bi keeps its locals
            (let [p (promise)]
              (ev/toggle-debug-mode! e (fn [r] (deliver p r)))
              (is (= {:on? true} (deref p 20000 :timeout))))
            (run e "(defn bi [n] (/ n 0))" false)
            (testing "an error pauses, the field evaluates there, Continue lets it through"
              (let [done (start-run e "(bi 21)" {})
                    d (deref @paused 20000 nil)]
                (is (= "user/bi" (:fn (first (:frames d)))))
                (is (some #{{:name "n" :text "21"}} (:locals (first (:frames d)))))
                (is (not (realized? done)))
                (is (= {:out "" :text "42"} (break-eval-sync e 0 "(* n 2)")))
                (is (true? (ev/continue-break! e)))
                (let [r (deref done 20000 :timeout)]
                  (is (= :error (:status r)))
                  (is (str/includes? (str (:error-text r)) "Divide by zero")))
                (is (= [{:continued? true}] @resumed))))
            (testing "Stop while paused: interrupted, and the next eval runs"
              (reset! paused (promise))
              (let [done (start-run e "(bi 1)" {})]
                (is (some? (deref @paused 20000 nil)))
                (ev/interrupt! e)
                (is (= :interrupted (:status (deref done 20000 :timeout))))
                (is (= {:continued? false} (last @resumed)) "the pause is dropped")
                (is (= "3" (:printed (first (run e "(+ 1 2)" false)))))))
            (testing "a quiet eval never pauses"
              (reset! paused (promise))
              (is (= :error (:status (first (run-quiet e "(bi 2)" nil)))))
              (is (not (realized? @paused))))
            (is (false? (ev/continue-break! e)) "nothing paused")
            (is (= {:on? false} (toggle-break-sync e)))
            (testing "off: no pause"
              (is (= :error (:status (first (run e "(bi 3)" false)))))
              (is (not (realized? @paused)))))))
      (finally (ev/stop! e)))))

;; --- breakpoints in the child (LM5) ----------------------------------------------

(defn- set-breakpoints-sync [e on?]
  (let [p (promise)] (ev/set-breakpoints! e on? (fn [r] (deliver p r))) (deref p 20000 :timeout)))

(deftest breakpoints-in-the-child-test
  (let [e (make (temp-project))]
    (try
      (is (= "3" (:printed (first (run e "(+ 1 2)" false)))) "the child is up")
      (let [r (set-breakpoints-sync e true)]
        (if (and (:error r) (not (System/getenv "JONQUIL_JOLT")))
          (do (println "SKIP breakpoints-in-the-child-test: this child can't break:" (:error r))
              (is (string? (:error r))))
          (let [paused (promise)
                resumed (atom [])]
            (is (true? (:on? r)) (pr-str r))
            (ev/set-break-handlers! e {:on-pause (fn [d] (deliver paused d))
                                       :on-resume (fn [how] (swap! resumed conj how))})
            (run e (:text (bp/instrument "(defn bpf [x]\n  (let [y (* x 2)]\n    (+ y 1)))" [3])) false)
            (testing "a call from the prompt pauses at the breakpoint, with Break on error off"
              (let [done (start-run e "(bpf 20)" {})
                    d (deref paused 20000 nil)]
                (is (= "Breakpoint at line 3" (:title d)))
                (is (some #{{:name "y" :text "40"}} (:locals (first (:frames d)))) (pr-str d))
                (is (= {:out "" :text "41"} (break-eval-sync e 0 "(inc y)")))
                (is (true? (ev/continue-break! e)))
                (is (= "41" (:printed (deref done 20000 :timeout))))
                (is (= [{:continued? true}] @resumed))))
            (is (= {:on? false :debug-turned-on? false} (set-breakpoints-sync e false)))
            (testing "disarmed: the same code runs through"
              (is (= "41" (:printed (first (run e "(bpf 20)" false)))))))))
      (finally (ev/stop! e)))))

(defn- step-sync
  ([ev] (step-sync ev :step))
  ([ev mode]
   (let [p (promise)] (ev/step-break! ev mode (fn [r] (deliver p r))) (deref p 20000 :timeout))))

(deftest stepping-in-the-child-test
  (let [e (make (temp-project))
        text "(defn sa [x]\n  (let [y (inc x)]\n    (str y)\n    (* y 2)))\n(defn sb [x]\n  (let [r (sa x)]\n    (+ r 1)))"]
    (try
      (is (= "3" (:printed (first (run e "(+ 1 2)" false)))) "the child is up")
      (let [r (set-breakpoints-sync e true)]
        (if (and (:error r) (not (System/getenv "JONQUIL_JOLT")))
          (do (println "SKIP stepping-in-the-child-test: this child can't break:" (:error r))
              (is (string? (:error r))))
          (let [paused (atom (promise))
                next-pause (fn [] (let [d (deref @paused 20000 nil)] (reset! paused (promise)) d))]
            (ev/set-break-handlers! e {:on-pause (fn [d] (deliver @paused d)) :on-resume (fn [_])})
            (run e (:text (bp/instrument text [2 7])) false)
            (testing "Step pauses at the next line, then the caller's; Continue runs on"
              (let [done (start-run e "(sb 1)" {})]
                (is (= "Breakpoint at line 2" (:title (next-pause))))
                (is (true? (step-sync e)))
                (is (= "Stepped to line 3" (:title (next-pause))))
                (is (true? (step-sync e)))
                (is (= "Stepped to line 4" (:title (next-pause))))
                (is (true? (step-sync e)))
                (is (= "Breakpoint at line 7" (:title (next-pause))))
                (is (true? (ev/continue-break! e)))
                (is (= "5" (:printed (deref done 20000 :timeout))))))
            (testing "Stop while stepping ends the run; the next starts with stepping off"
              (let [done (start-run e "(sb 1)" {})]
                (is (some? (next-pause)))
                (is (true? (step-sync e)))
                (is (= "Stepped to line 3" (:title (next-pause))))
                (ev/interrupt! e)
                (is (= :interrupted (:status (deref done 20000 :timeout))))
                (let [done2 (start-run e "(sb 1)" {})]
                  (is (= "Breakpoint at line 2" (:title (next-pause))))
                  (ev/continue-break! e)
                  (is (= "Breakpoint at line 7" (:title (next-pause))))
                  (ev/continue-break! e)
                  (is (= "5" (:printed (deref done2 20000 :timeout))))))))))
      (finally (ev/stop! e)))))

(deftest a-step-stays-on-its-thread-in-the-child-test
  ;; futures running only step points while the evaluation's own thread
  ;; steps (its next line comes 30 ms after the Step): none of them may
  ;; take its Step
  (let [e (make (temp-project))
        text "(defn sf [x m]\n  (when m\n    (Thread/sleep 30))\n  (let [y (inc x)]\n    (* y 2)))"]
    (try
      (is (= "3" (:printed (first (run e "(+ 1 2)" false)))) "the child is up")
      (let [r (set-breakpoints-sync e true)]
        (if (and (:error r) (not (System/getenv "JONQUIL_JOLT")))
          (do (println "SKIP a-step-stays-on-its-thread-in-the-child-test:" (:error r))
              (is (string? (:error r))))
          (let [paused (atom (promise))
                next-pause (fn [] (let [d (deref @paused 20000 nil)] (reset! paused (promise)) d))
                x-of (fn [d] (some #(when (= "x" (:name %)) (:text %)) (:locals (first (:frames d)))))]
            (ev/set-break-handlers! e {:on-pause (fn [d] (deliver @paused d)) :on-resume (fn [_])})
            (run e (:text (bp/instrument text [3])) false)
            (let [done (start-run e (str "(do (def stop? (atom false))"
                                         " (dotimes [_ 4] (future (while (not @stop?) (sf 1 false))))"
                                         " (Thread/sleep 50)"
                                         " (let [v (sf 2 true)] (reset! stop? true) v))")
                                  {})
                  d1 (next-pause)]
              (is (= ["Breakpoint at line 3" "2"] [(:title d1) (x-of d1)]))
              (is (true? (step-sync e)))
              (let [d2 (next-pause)]
                (is (= ["Stepped to line 4" "2"] [(:title d2) (x-of d2)])
                    "the evaluation's own thread, not a future running the same lines"))
              (is (true? (step-sync e)))
              (let [d3 (next-pause)]
                (is (= ["Stepped to line 5" "2"] [(:title d3) (x-of d3)])))
              (is (true? (ev/continue-break! e)))
              (is (= "6" (:printed (deref done 20000 :timeout))))))))
      (finally (ev/stop! e)))))

(deftest step-over-and-out-in-the-child-test
  (let [e (make (temp-project))
        text "(defn a [x]\n  (when (neg? x)\n    (str \"never\"))\n  (let [y (inc x)]\n    (* y 2)))\n(defn b [x]\n  (let [r (a x)]\n    (+ r 1)))"]
    (try
      (is (= "3" (:printed (first (run e "(+ 1 2)" false)))) "the child is up")
      (let [r (set-breakpoints-sync e true)]
        (if (and (:error r) (not (System/getenv "JONQUIL_JOLT")))
          (do (println "SKIP step-over-and-out-in-the-child-test:" (:error r))
              (is (string? (:error r))))
          (let [paused (atom (promise))
                next-pause (fn [] (let [d (deref @paused 20000 nil)] (reset! paused (promise)) d))]
            (ev/set-break-handlers! e {:on-pause (fn [d] (deliver @paused d)) :on-resume (fn [_])})
            (run e (:text (bp/instrument text [3 7])) false)
            (testing "Step Over stays in b"
              (let [done (start-run e "(b 1)" {})]
                (is (= "Breakpoint at line 7" (:title (next-pause))))
                (is (true? (step-sync e :over)))
                (is (= "Stepped to line 8" (:title (next-pause))))
                (ev/continue-break! e)
                (is (= "5" (:printed (deref done 20000 :timeout))))))
            (testing "Step Into goes into a, and Step Out lands back in b"
              (let [done (start-run e "(b 1)" {})]
                (is (= "Breakpoint at line 7" (:title (next-pause))))
                (is (true? (step-sync e :step)))
                (is (= "Stepped to line 2" (:title (next-pause))))
                (is (true? (step-sync e :out)))
                (is (= "Stepped to line 8" (:title (next-pause))))
                (ev/continue-break! e)
                (is (= "5" (:printed (deref done 20000 :timeout))))))
            (testing "Step Out from the outermost frame runs to the end, as Continue"
              (let [done (start-run e "(b 1)" {})]
                (is (= "Breakpoint at line 7" (:title (next-pause))))
                (is (true? (step-sync e :out)))
                (is (= "5" (:printed (deref done 20000 :timeout)))))))))
      (finally (ev/stop! e)))))

(deftest step-all-in-the-child-test
  (let [e (make (temp-project))
        text "(defn a [x]\n  (when (neg? x)\n    (str \"never\"))\n  (let [y (inc x)]\n    (* y 2)))\n(defn b [x]\n  (let [r (a x)]\n    (+ r 1)))"]
    (try
      (is (= "3" (:printed (first (run e "(+ 1 2)" false)))) "the child is up")
      (let [r (set-breakpoints-sync e true)]
        (if (and (:error r) (not (System/getenv "JONQUIL_JOLT")))
          (do (println "SKIP step-all-in-the-child-test:" (:error r))
              (is (string? (:error r))))
          (let [paused (atom (promise))
                next-pause (fn [] (let [d (deref @paused 20000 nil)] (reset! paused (promise)) d))
                step-from-7 (fn []
                              (let [done (start-run e "(b 1)" {})]
                                (is (= "Breakpoint at line 7" (:title (next-pause))))
                                (is (true? (step-sync e :step)))
                                (let [t (:title (next-pause))]
                                  (ev/continue-break! e)
                                  (is (= "5" (:printed (deref done 20000 :timeout))))
                                  t)))]
            (ev/set-break-handlers! e {:on-pause (fn [d] (deliver @paused d)) :on-resume (fn [_])})
            (testing "a dot in b only: a runs through"
              (run e (:text (bp/instrument text [7])) false)
              (is (= "Stepped to line 8" (step-from-7))))
            (testing "an instrumented tab: Step Into goes into a"
              (run e (:text (bp/instrument text [7] {:step-all? true})) false)
              (is (= "Stepped to line 2" (step-from-7)))))))
      (finally (ev/stop! e)))))

(deftest breakpoints-armed-before-the-child-starts-test
  (let [e (make (temp-project))]
    (try
      (is (= {:on? true :pending? true} (set-breakpoints-sync e true)) "no child yet: pending")
      (let [paused (promise)
            failed (atom nil)]
        (ev/set-break-handlers! e {:on-pause (fn [d] (deliver paused d)) :on-resume (fn [_])})
        (swap! (:state e) update :breakpoints-pending (fn [f] (fn [r] (reset! failed r) (f r))))
        (let [r (first (run e (:text (bp/instrument "(defn bpg [x]\n  (let [y (* x 2)]\n    (+ y 1)))" [3])) false))]
          (if (some-> @failed :error)
            (do (println "SKIP breakpoints-armed-before-the-child-starts-test:" (:error @failed))
                (is (false? (ev/breakpoints-on? e)) "a child that can't arm turns them off"))
            (do (is (= :ok (:status r)) (pr-str r))
                (is (nil? (:breakpoints-pending @(:state e))) "the first eval's reply confirmed them")
                (let [done (start-run e "(bpg 20)" {})]
                  (is (= "Breakpoint at line 3" (:title (deref paused 20000 nil))))
                  (is (true? (ev/continue-break! e)))
                  (is (= "41" (:printed (deref done 20000 :timeout)))))))))
      (finally (ev/stop! e)))))

;; --- trace in the child (LM4) ----------------------------------------------------

(defn- trace-sync [e op args]
  (let [p (promise)]
    (ev/trace-op! e op args (fn [r] (deliver p r)))
    (deref p 20000 :timeout)))

(deftest trace-in-the-child-test
  (let [e (make (temp-project))]
    (try
      (run e "(defn ttf [x] (* 10 x))" false)
      (is (= {:traced "user/ttf"} (trace-sync e :trace {:sym "ttf" :ns "user"})))
      (is (= "20" (:printed (first (run e "(ttf 2)" false)))))
      (let [{:keys [entries]} (trace-sync e :log {:since -1})
            id (:id (first entries))]
        (is (= ["(user/ttf 2)"] (mapv :args entries)))
        (let [v (inspect e :start-trace {:id id :part :ret :label "(user/ttf 2)" :rows 10 :cols 40})]
          (is (re-find #"\b20\b" (pr-str v)) (pr-str v))
          (is (= "(user/ttf 2)" (:path v)) "a traced call is labelled by the call")))
      (is (= "20" (:printed (first (run e "*1" false)))) "the trace ops left *1 alone")
      (is (= {:count 1} (trace-sync e :untrace-all {})))
      (finally (ev/stop! e)))))

;; --- Save World and Restore World in the child (LM7) ---------------------------

(defn- world-sync [e op args]
  (let [p (promise)]
    (ev/world-op! e op args (fn [r] (deliver p r)))
    (deref p 20000 :timeout)))

(deftest world-in-the-child-test
  (let [e (make (temp-project))
        path (str (System/getProperty "java.io.tmpdir") "/jonquil-world-child-" (System/nanoTime) ".world")]
    (try
      (run e "(ns demo.w) (def counter (atom 41)) (def rows [{:a 1}]) (defn f [x] x)" false)
      (is (= {:saved 2 :placeholders []}
             (select-keys (world-sync e :save {:path path}) [:saved :placeholders]))
          "demo.w's two data vars; the fn isn't written")
      (run e "(do (reset! demo.w/counter 0) (alter-var-root (var demo.w/rows) (constantly nil)))" false)
      (is (= 2 (:restored (world-sync e :restore {:path path}))))
      (is (= "[41 [{:a 1}]]" (:printed (first (run e "[@demo.w/counter demo.w/rows]" false)))))
      (is (= {:missing true} (world-sync e :restore {:path (str path ".none")})))
      (finally (ev/stop! e) (.delete (java.io.File. path))))))

;; --- live results in the child (LM1) ---------------------------------------------

(deftest results-in-the-child-test
  (let [e (make (temp-project))]
    (try
      (let [[ok] (run e "(+ 1 2)" false)
            [err] (run e "(/ 1 0)" false)]
        (is (= :value (:kind (:ref ok))) (pr-str ok))
        (is (= :exception (:kind (:ref err))))
        (is (= "3" (:printed (first (run e "*1" false)))) "the keep op left *1 alone")
        (run e "(+ 10 10)" false)
        (let [v (inspect e :start-result {:id (:id (:ref ok)) :epoch (:epoch (:ref ok)) :label "3" :rows 10 :cols 40})]
          (is (re-find #"\b3\b" (pr-str v)) (pr-str v))
          (is (= "3" (:path v)) "a clicked result is labelled by its line")))
      (finally (ev/stop! e)))))

;; --- the break handlers hear each evaluation start and end in the child --------------

(deftest eval-start-and-end-handlers-in-the-child-test
  (let [e (make (temp-project))
        events (atom [])
        completed (atom false)
        run! (fn [code]
               (reset! completed false)
               (let [done (promise)]
                 (ev/eval! e code {:run? false :on-complete (fn [r] (reset! completed true) (deliver done r))})
                 (let [r (deref done 20000 :timeout)]
                   (Thread/sleep 200)
                   r)))]
    (try
      (ev/set-break-handlers! e {:on-eval-start (fn [id] (swap! events conj [:start id]))
                                 :on-eval-end (fn [id] (swap! events conj [:end id @completed]))})
      (is (= "3" (:printed (run! "(+ 1 2)"))))
      (let [[[k1 id1] [k2 id2 done?] :as evs] @events]
        (is (= 2 (count evs)) (str "one start and one end, the auto-require calling neither: " (pr-str evs)))
        (is (= [:start :end] [k1 k2]))
        (is (and (some? id1) (identical? id1 id2)))
        (is (true? done?) "the end comes after the evaluation's own on-complete"))
      (finally (ev/stop! e)))))

(deftest fibers-in-the-child-test
  (let [e (make (temp-project))
        op (fn [k args] (let [p (promise)] (ev/fibers-op! e k args (fn [r] (deliver p r))) (deref p 20000 :timeout)))]
    (try
      (is (= "3" (:printed (first (run e "(+ 1 2)" false)))) "the child is up")
      (let [w (op :watch {})]
        (if (and (:error w) (not (System/getenv "JONQUIL_JOLT")))
          (do (println "SKIP fibers-in-the-child-test: this child has no fibers:" (:error w))
              (is (string? (:error w))))
          (do (is (= {:watching? true} w))
              (run e "(def fib-stop (atom false)) (jolt.fibers/spawn (fn [] (loop [] (when-not @fib-stop (jolt.fibers/yield) (recur)))))" false)
              (let [{:keys [fibers]} (op :list {})
                    id (:id (first fibers))]
                (is (#{:running :ready :parked} (:state (first fibers))) (pr-str fibers))
                (is (= {:ok true} (op :interrupt {:id id})) (pr-str fibers))))))
      (finally (ev/stop! e)))))

(deftest line-counts-in-the-child-test
  (let [dir (temp-project)
        e (make dir)
        call (fn [f & args] (let [p (promise)] (apply f e (concat args [(fn [r] (deliver p r))])) (deref p 20000 :timeout)))
        f (java.io.File. ^String dir "lc_child.clj")]
    (try
      (is (= "3" (:printed (first (run e "(+ 1 2)" false)))) "the child is up")
      (spit f "(ns lc-child)\n(defn twice [] (dotimes [_ 2] (inc 1)))\n(twice)\n(def a 1)\n(def b 2)\n")
      (let [on (call ev/toggle-line-counts!)]
        (if (and (:error on) (not (System/getenv "JONQUIL_JOLT")))
          (do (println "SKIP line-counts-in-the-child-test: this child can't count lines:" (:error on))
              (is (string? (:error on))))
          (do (is (= {:on? true} on))
              (run e (str "(load-file " (pr-str (.getPath f)) ")") false)
              (is (= 1 (get-in (call ev/line-counts! (.getPath f)) [:counts 3])) "the call on line 3 ran once")
              (is (= {:ok true} (call ev/clear-line-counts!)))
              (is (= {} (:counts (call ev/line-counts! (.getPath f)))))
              ;; a Run sends the file's path (load-file's file-path), and a
              ;; Run with :clear-line-counts? counts that Run alone
              (run e "(set! *print-length* 2)" false)
              (dotimes [_ 2]
                (let [done (promise)]
                  (ev/eval! e (slurp f) {:run? true :path (.getPath f) :clear-line-counts? true
                                         :on-output (fn [& _]) :on-complete (fn [r] (deliver done r))})
                  (deref done 20000 :timeout)))
              (let [r (call ev/line-counts! (.getPath f))]
                (is (= 1 (get-in r [:counts 3])) (str "the Run's own count, read past *print-length*: " r))
                (is (< 2 (count (:counts r))) "more lines than *print-length*")
                (is (= :counts (:mode r))))
              (is (= {:on? false} (call ev/toggle-line-counts!))))))
      (finally (ev/stop! e)))))

(deftest stop-sends-a-frame-interrupt-test
  ;; interrupt-frame-eval! runs in a future on every Stop, whether or not a
  ;; frame eval is running, when the child offers the op.
  (let [e (make (temp-project)) done (promise) sent (atom [])
        real client/request-sync]
    (try
      (ev/start! e)
      (ev/eval! e "(loop [] (recur))" {:run? false :on-complete (fn [r] (deliver done r))})
      (wait-for-eval-id e)
      (with-redefs [client/request-sync (fn [conn msg timeout]
                                          (swap! sent conj (:op msg))
                                          (real conn msg timeout))]
        (ev/interrupt! e)
        (is (wait-until (fn [] (some #{"jonquil-frame-interrupt"} @sent)) 5000)
            "the frame interrupt request went out")
        (is (= :interrupted (:status (deref done 10000 :timeout)))))
      (finally (ev/stop! e)))))

(deftest a-throwing-stop-request-is-reported-test
  ;; interrupt-or-restart!'s catch: a throw from the interrupt request is
  ;; said on the console as "Stop failed", never lost in the future.
  (let [e (make (temp-project)) done (promise) out (atom [])
        real client/request-sync]
    (try
      (ev/start! e)
      (ev/eval! e "(loop [] (recur))" {:run? false
                                       :on-output (fn [k s] (swap! out conj [k s]))
                                       :on-complete (fn [r] (deliver done r))})
      (wait-for-eval-id e)
      (with-redefs [client/request-sync (fn [conn msg timeout]
                                          (if (= "interrupt" (:op msg))
                                            (throw (ex-info "kaboom" {}))
                                            (real conn msg timeout)))]
        (ev/interrupt! e)
        (is (wait-until (fn [] (some (fn [[k s]] (and (= :error k) (= "; Stop failed: kaboom\n" s)))
                                     @out))
                        5000)
            "the console shows the failed stop"))
      (finally (ev/stop! e)))))

(deftest breakpoints-wait-for-a-child-test
  (let [e (nrepl/make-nrepl {:dialect :jolt :project-dir (str (temp-project))})
        answers (atom [])
        first-answer (promise)]
    (ev/set-breakpoints! e true (fn [r] (swap! answers conj r) (deliver first-answer r)))
    (is (= {:on? true :pending? true} (deref first-answer 3000 :timeout))
        "no child yet: pending, armed by the first evaluation")
    (is (true? (ev/breakpoints-on? e)) "so the first Run sends breakpoint code and arms them")
    (testing "that evaluation's reply confirms them"
      (nrepl/breakpoints-watch! e {:jonquil-breakpoints-armed "true"})
      (is (true? (ev/breakpoints-on? e)))
      (is (= 1 (count @answers))))
    (testing "or says why it couldn't, once"
      (let [second-answer (promise)]
        (ev/set-breakpoints! e true (fn [r] (swap! answers conj r) (deliver second-answer r)))
        (is (= {:on? true :pending? true} (deref second-answer 3000 :timeout))))
      (nrepl/breakpoints-watch! e {:jonquil-breakpoints-error "no jolt.debug"})
      (is (false? (ev/breakpoints-on? e)))
      (is (= {:error "no jolt.debug" :pending-failed? true} (last @answers)))
      (nrepl/breakpoints-watch! e {:jonquil-breakpoints-error "no jolt.debug"})
      (is (= 1 (count (filter :pending-failed? @answers)))))))
