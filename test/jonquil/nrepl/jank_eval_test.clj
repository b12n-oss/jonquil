(ns jonquil.nrepl.jank-eval-test
  "The jank evaluator against dev/fake_jank_nrepl.clj, with the child
  process faked: a launch fn that writes the fake's port into a temp run
  dir, so no real jank is needed."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [fake-jank-nrepl :as fake]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.jank :as jank]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.jank :as jank-eval]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def core-text "(ns demo.core)\n(defn f [] 1)\n")

(defn- make-test-jank
  "A Jank evaluator over srv with a faked child. Returns {:ev e :launches
  atom :kills atom :launch-opts atom}; opts override the hooks."
  ([srv] (make-test-jank srv {}))
  ([srv overrides]
   (let [launches (atom 0) kills (atom 0) launch-opts (atom [])
         launch (fn [o]
                  (swap! launches inc)
                  (swap! launch-opts conj o)
                  (let [d (temp-dirs/temp-dir! "jonquil-jank-eval-test-")]
                    (spit (io/file d ".nrepl-port") (str (:port srv) "\n"))
                    {:run-dir d :pid -1 :live (atom true)}))
         e (jank-eval/make-jank
            {:profile dialect/jank :runtime :launch :project-dir nil}
            (merge {:timings {:describe-timeout-ms 500 :clone-timeout-ms 500}
                    :launch launch
                    :find-binary (fn [] "/fake/jank")
                    :alive? (fn [c] (boolean (some-> (:live c) deref)))
                    :kill (fn [c] (swap! kills inc) (some-> (:live c) (reset! false)))}
                   overrides))]
     {:ev e :launches launches :kills kills :launch-opts launch-opts})))

(defn- run
  "Evaluates and waits. {:result r :out [[kind text]...]}."
  [e code opts]
  (let [done (promise) out (atom [])]
    (ev/eval! e code (assoc opts
                            :on-output (fn [k s] (swap! out conj [k s]))
                            :on-complete (fn [r] (deliver done r))))
    {:result (deref done 10000 :timeout) :out @out}))

(defn- all-text [out] (apply str (map second out)))

(defn- wait-until! [pred]
  (loop [n 0]
    (when (and (< n 250) (not (pred)))
      (Thread/sleep 20)
      (recur (inc n)))))

(defn- with-fake [opts f]
  (let [srv (fake/start! opts)]
    (try (f srv) (finally (fake/stop! srv)))))

(defmacro ^:private with-jank
  "Binds sym to (make-test-jank srv overrides) over a fake (fake-opts),
  stops the evaluator and the fake afterwards."
  [[sym fake-opts overrides srv-sym] & body]
  `(with-fake ~fake-opts
     (fn [~srv-sym]
       (let [~sym (make-test-jank ~srv-sym ~overrides)]
         (try ~@body (finally (ev/stop! (:ev ~sym))))))))

(deftest make-test
  (let [e (ev/make {:profile dialect/jank :runtime :launch :project-dir nil})]
    (is (instance? jonquil.nrepl.jank.Jank e))
    (is (satisfies? connection/Attached e))
    (is (not (connection/connected? e)))
    (is (nil? (ev/start! e)))
    (is (= 'user (ev/prompt-ns e)))
    (is (= jank/limitations (ev/limitations e)))
    (is (false? (ev/waiting-for-input? e)))))

(deftest lazy-start-test
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (with-jank [j {} {} srv]
    (is (zero? @(:launches j)) "nothing launches before the first evaluation")
    (let [{:keys [result out]} (run (:ev j) "(+ 1 2)" {:run? false})]
      (is (= :ok (:status result)))
      (is (= 1 @(:launches j)))
      (is (re-find (re-pattern (java.util.regex.Pattern/quote (jank/starting-message "/fake/jank")))
                   (all-text out))))))

(deftest run-moves-prompt-test
  (with-jank [j {} {} srv]
    (let [{:keys [result]} (run (:ev j) "(ns demo.core)\n(defn f [] 1)" {:run? true})]
      (is (= :ok (:status result)))
      (is (= 'demo.core (ev/prompt-ns (:ev j))))
      (is (some #{"load-file"} (fake/ops-seen srv))))))

(deftest repl-line-test
  (with-jank [j {:answers {"(+ 1 2)" "3"}} {} srv]
    (let [{:keys [result]} (run (:ev j) "(+ 1 2)" {:run? false})]
      (is (= {:status :ok :printed "3" :ns 'user} (select-keys result [:status :printed :ns])))
      (is (= "user" (get (last (filter #(= "eval" (get % "op")) (fake/requests srv))) "ns"))))))

(deftest selection-not-loaded-test
  (with-jank [j {} {} srv]
    (let [{:keys [result]} (run (:ev j) "(f)" {:run? false :ns-source "(ns demo.other)"})]
      (is (= {:status :notice :text (jank/not-loaded-message "demo.other")} result))
      (let [codes (keep #(get % "code") (fake/requests srv))]
        (is (some #{(jank/find-ns-code "demo.other")} codes))
        (is (not (some #{"(f)"} codes)))))))

(deftest selection-loaded-test
  (with-jank [j {} {} srv]
    (run (:ev j) core-text {:run? true})
    (let [{:keys [result]} (run (:ev j) "(f)" {:run? false :ns-source core-text})]
      (is (= :ok (:status result)))
      (is (= "demo.core" (get (last (filter #(= "eval" (get % "op")) (fake/requests srv))) "ns")))
      (is (= 'demo.core (ev/prompt-ns (:ev j))) "a selection leaves the prompt alone")))
  (with-jank [j {} {} srv]
    (let [{:keys [result]} (run (:ev j) "(g)" {:run? false :ns-source "(def x 1)"})]
      (is (= :ok (:status result)) "no ns form: evaluates in the prompt ns, no find-ns")
      (is (not-any? #{(jank/find-ns-code "user")} (keep #(get % "code") (fake/requests srv)))))))

(deftest stop-kills-child-test
  (with-jank [j {:slow {"(spin)" 5000}} {} srv]
    (let [e (:ev j) done (promise) out (atom [])]
      (ev/eval! e "(spin)" {:run? false
                            :on-output (fn [k s] (swap! out conj [k s]))
                            :on-complete (fn [r] (deliver done r))})
      (wait-until! #(some (fn [m] (= "(spin)" (get m "code"))) (fake/requests srv)))
      (ev/interrupt! e)
      (is (= {:status :interrupted} (deref done 5000 :timeout)))
      (wait-until! #(= 1 @(:kills j)))
      (is (= 1 @(:kills j)) "Stop's kill runs off the caller's thread")
      (is (re-find (re-pattern (java.util.regex.Pattern/quote jank/stopped-message)) (all-text @out)))
      (is (nil? (jank-eval/child-pid e)))
      (is (= :ok (:status (:result (run e "(+ 1 1)" {:run? false})))))
      (is (= 2 @(:launches j))))))

(deftest idle-stop-is-noop-test
  (with-jank [j {} {} srv]
    (run (:ev j) "1" {:run? false})
    (ev/interrupt! (:ev j))
    (is (zero? @(:kills j)))
    (is (connection/connected? (:ev j)))))

(deftest stop-during-start-test
  (let [started (promise) launched (atom 0)]
    (with-jank [j {} {:launch (fn [{:keys [cancelled?]}]
                                (swap! launched inc)
                                (deliver started true)
                                (loop [] (if (cancelled?) {:error :cancelled} (do (Thread/sleep 10) (recur)))))}
                srv]
      (let [e (:ev j) done (promise)]
        (ev/eval! e "1" {:run? false :on-output (fn [_ _]) :on-complete (fn [r] (deliver done r))})
        (deref started 5000 nil)
        (ev/interrupt! e)
        (is (= {:status :interrupted} (deref done 5000 :timeout)))
        (is (nil? (jank-eval/child-pid e)))
        (is (zero? @(:kills j)))))))

(deftest stop-races-launch-test
  (testing "stop! while launch is in flight: the child launch returns is killed"
    (let [gate (promise) started (promise)]
      (with-jank [j {} {:launch (fn [_]
                                  (deliver started true)
                                  @gate
                                  (let [d (temp-dirs/temp-dir! "jonquil-jank-eval-test-")]
                                    (spit (io/file d ".nrepl-port") (str (:port srv) "\n"))
                                    {:run-dir d :pid -1 :live (atom true)}))}
                  srv]
        (let [e (:ev j) done (promise)]
          (ev/eval! e "1" {:run? false :on-output (fn [_ _]) :on-complete (fn [r] (deliver done r))})
          (deref started 5000 nil)
          (ev/stop! e)
          (deliver gate true)
          (is (= {:status :interrupted} (deref done 5000 :timeout)))
          (is (= 1 @(:kills j)) "the late child is killed")
          (is (nil? (jank-eval/child-pid e))))))))

(deftest reset-restarts-test
  (with-jank [j {} {} srv]
    (let [e (:ev j)]
      (ev/set-mode! e :reset)
      (let [{:keys [result]} (run e core-text {:run? true})]
        (is (= :ok (:status result)))
        (is (= 1 @(:launches j)) "a fresh child isn't restarted"))
      (let [{:keys [result out]} (run e core-text {:run? true})]
        (is (= :ok (:status result)))
        (is (= 2 @(:launches j)))
        (is (= 1 @(:kills j)))
        (is (re-find (re-pattern (java.util.regex.Pattern/quote jank/reset-message)) (all-text out))))))
  (with-jank [j {} {} srv]
    (let [e (:ev j)]
      (ev/set-mode! e :accumulate)
      (run e core-text {:run? true})
      (run e core-text {:run? true})
      (is (= 1 @(:launches j)) "accumulate mode keeps the child"))))

(deftest reset-is-the-default-mode-test
  ;; The window's first evaluator never gets set-mode!, and its Reset on
  ;; Run box starts ticked, so the default has to be reset.
  (with-jank [j {} {} srv]
    (let [e (:ev j)]
      (run e core-text {:run? true})
      (let [{:keys [out]} (run e core-text {:run? true})]
        (is (= 2 @(:launches j)))
        (is (re-find (re-pattern (java.util.regex.Pattern/quote jank/reset-message)) (all-text out)))))))

(deftest child-death-test
  (with-jank [j {:slow {"(spin)" 5000}} {} srv]
    (let [e (:ev j) done (promise)]
      (ev/eval! e "(spin)" {:run? false :on-output (fn [_ _]) :on-complete (fn [r] (deliver done r))})
      (wait-until! #(some (fn [m] (= "(spin)" (get m "code"))) (fake/requests srv)))
      (fake/drop-connections! srv)
      (is (= {:status :error :error-text jank/lost-connection} (deref done 5000 :timeout)))
      (is (= 1 @(:kills j)))
      (is (= :ok (:status (:result (run e "(+ 1 1)" {:run? false})))))
      (is (= 2 @(:launches j))))))

(deftest selection-lookup-disconnect-test
  ;; The find-ns request loses its connection (child died): the result is
  ;; the settled disconnect, not a "not loaded" notice, and the child dies.
  (with-jank [j {:slow {"some?" 5000}} {} srv]
    (let [e (:ev j) done (promise)]
      (ev/eval! e "(f)" {:run? false :ns-source "(ns demo.other)"
                         :on-output (fn [_ _]) :on-complete (fn [r] (deliver done r))})
      (wait-until! #(some (fn [m] (= (jank/find-ns-code "demo.other") (get m "code"))) (fake/requests srv)))
      (fake/drop-connections! srv)
      (is (= {:status :error :error-text jank/lost-connection} (deref done 5000 :timeout)))
      (is (= 1 @(:kills j))))))

(deftest no-binary-test
  (with-jank [j {} {:find-binary (fn [] nil)} srv]
    (is (= {:status :notice :text jank/no-binary-message}
           (:result (run (:ev j) "1" {:run? false}))))
    (is (zero? @(:launches j)))))

(deftest start-failure-test
  (with-jank [j {} {:launch (fn [_] {:error "boom"})} srv]
    (is (= {:status :notice :text (jank/start-failed-message "boom")}
           (:result (run (:ev j) "1" {:run? false}))))
    (is (nil? (jank-eval/child-pid (:ev j))))))

(deftest launch-throw-test
  (with-jank [j {} {:launch (fn [_] (throw (ex-info "kaboom" {})))} srv]
    (let [{:keys [result]} (run (:ev j) "1" {:run? false})]
      (is (= :error (:status result)))
      (is (re-find #"kaboom" (:error-text result))))))

(deftest launch-options-test
  (with-jank [j {} {} srv]
    (let [d (temp-dirs/temp-dir! "jonquil-jank-eval-test-")]
      (run (:ev j) "1" {:run? true :path (str d "/a.jank")})
      (let [o (first @(:launch-opts j))]
        (is (= "/fake/jank" (:binary o)))
        (is (= (str d) (:module-path o)) "no project: the file's own directory")
        (is (fn? (:cancelled? o)))))))

(deftest launch-options-ns-root-test
  ;; A loose file's Run passes the root its ns implies, so sibling
  ;; namespaces resolve (src/talk/core.jank, ns talk.core -> src).
  (with-jank [j {} {} srv]
    (let [d (temp-dirs/temp-dir! "jonquil-jank-eval-test-")]
      (run (:ev j) "(ns talk.core)\n1" {:run? true :path (str d "/src/talk/core.jank")})
      (is (= (str d "/src") (:module-path (first @(:launch-opts j))))))))

(deftest never-lookup-test
  (with-jank [j {} {} srv]
    (let [e (:ev j)]
      (run e core-text {:run? true})
      (run e "(+ 1 2)" {:run? false})
      (ev/lookup e "map" "(map)" 1)
      (ev/complete e {:prefix "ma" :text "(ma" :pos 3 :extra-context {}})
      (deref (let [p (promise)] (ev/full-doc e "map" "(map)" #(deliver p %)) p) 5000 nil)
      (is (not (some #{"lookup"} (fake/ops-seen srv)))))))

(deftest stop!-idempotent-test
  (with-jank [j {} {} srv]
    (run (:ev j) "1" {:run? false})
    (ev/stop! (:ev j))
    (ev/stop! (:ev j))
    (is (= 1 @(:kills j)))
    (is (not (connection/connected? (:ev j))))))

(deftest connect-notice-replaces-child-test
  (let [dead (doto (fake/start!) fake/stop!) n (atom 0)]
    (with-jank [j {} {:launch (fn [_]
                                (let [d (temp-dirs/temp-dir! "jonquil-jank-eval-test-")
                                      port (if (= 1 (swap! n inc)) (:port dead) (:port srv))]
                                  (spit (io/file d ".nrepl-port") (str port "\n"))
                                  {:run-dir d :pid -1 :live (atom true)}))}
                srv]
      (let [e (:ev j)]
        (is (= :notice (:status (:result (run e "1" {:run? false})))))
        (is (= 1 @(:kills j)) "the child that wouldn't connect is killed")
        (is (nil? (jank-eval/child-pid e)))
        (is (= :ok (:status (:result (run e "1" {:run? false})))))
        (is (= 2 @n) "the next evaluation launches again")))))

(deftest stopped-sentence-follows-result-test
  (with-jank [j {:slow {"(spin)" 5000}} {} srv]
    (let [e (:ev j) events (atom []) done (promise)]
      (ev/eval! e "(spin)" {:run? false
                            :on-output (fn [_ s] (swap! events conj [:out s]))
                            :on-complete (fn [r] (swap! events conj [:complete (:status r)]) (deliver done r))})
      (wait-until! #(some (fn [m] (= "(spin)" (get m "code"))) (fake/requests srv)))
      (ev/interrupt! e)
      (deref done 5000 nil)
      (wait-until! #(some (fn [[k s]] (and (= k :out) (= s (str jank/stopped-message "\n")))) @events))
      (let [ks (map (fn [[k s]] (if (= k :complete) :complete (when (= s (str jank/stopped-message "\n")) :stopped))) @events)]
        (is (< (.indexOf (vec ks) :complete) (.indexOf (vec ks) :stopped)))
        (is (not= -1 (.indexOf (vec ks) :stopped)))))))

;; --- help ---

(defn- candidate-names [cands] (set (map :symbol cands)))

(deftest complete-merges-server-test
  (with-jank [j {:completions {"gr" [["greet" "demo.core" "function"]]}} {} srv]
    (let [e (:ev j)]
      (run e "(+ 1 2)" {:run? false})
      (is (contains? (candidate-names (ev/complete e {:prefix "gr" :text "(gr" :pos 3 :extra-context {}})) "greet"))
      (is (some #{"completions"} (fake/ops-seen srv))))))

(deftest complete-offline-when-idle-not-connected-test
  (with-jank [j {:completions {"gr" [["greet" "demo.core" "function"]]}} {} srv]
    (let [e (:ev j)
          cands (ev/complete e {:prefix "gr" :text "(defn greet [])\n(gr" :pos 19 :extra-context {}})]
      (is (sequential? cands))
      (is (not (contains? (candidate-names cands) "demo.core/greet")))
      (is (empty? (fake/requests srv)) "not connected: nothing sent"))))

(deftest help-while-running-test
  (with-jank [j {:slow {"(spin)" 800}
                 :completions {"gr" [["greet" "demo.core" "function"]]}
                 :docs {"greet" ["([w])" "Says hi."]}} {} srv]
    (let [e (:ev j) done (promise)]
      (run e "(+ 1 2)" {:run? false})
      (ev/eval! e "(spin)" {:run? false :on-output (fn [_ _]) :on-complete #(deliver done %)})
      (wait-until! #(some (fn [m] (= "(spin)" (get m "code"))) (fake/requests srv)))
      (let [before (count (fake/requests srv))
            cands (ev/complete e {:prefix "gr" :text "(gr" :pos 3 :extra-context {}})
            doc (ev/lookup e "greet" "(greet)" 1)]
        (is (not (contains? (candidate-names cands) "greet")))
        (is (= jank/busy-doc (:doc doc)))
        (is (= before (count (fake/requests srv))) "no completions, no metadata eval while running"))
      (is (= :ok (:status (deref done 10000 :timeout)))))))

(deftest lookup-meta-test
  (with-jank [j {:docs {"greet" ["([w])" "Says hi."]}} {} srv]
    (let [e (:ev j)]
      (is (= "No docs: jank isn't running yet. Run something first."
             (:doc (ev/lookup e "greet" "(greet)" 1))))
      (run e "(+ 1 2)" {:run? false})
      (let [d (ev/lookup e "greet" "(greet)" 1)]
        (is (= :found (:status d)))
        (is (= "([w])" (:arglists d)))
        (is (= "Says hi." (:doc d))))
      (is (not (some #{"lookup"} (fake/ops-seen srv)))))))

(deftest lookup-unknown-test
  (with-jank [j {} {} srv]
    (let [e (:ev j)]
      (run e "(+ 1 2)" {:run? false})
      (is (= "No docs for nope" (:doc (ev/lookup e "nope" "(nope)" 1)))))))

(deftest lookup-timeout-test
  (with-jank [j {:slow {"(resolve (quote greet))" 1000}} {:timings {:help-timeout-ms 100}} srv]
    (let [e (:ev j)]
      (run e "(+ 1 2)" {:run? false})
      (is (= "No docs: jank didn't answer in time." (:doc (ev/lookup e "greet" "(greet)" 1)))))))

(deftest full-doc-test
  (with-jank [j {:docs {"greet" ["([w])" "Says hi."]}} {} srv]
    (let [e (:ev j) p (promise) calls (atom 0)]
      (run e "(+ 1 2)" {:run? false})
      (ev/full-doc e "greet" "(greet)" (fn [d] (swap! calls inc) (deliver p d)))
      (let [d (deref p 5000 :timeout)]
        (is (= :found (:status d)))
        (is (= "Says hi." (:doc d))))
      (Thread/sleep 100)
      (is (= 1 @calls)))))

(deftest stop-returns-before-a-slow-kill-test
  ;; Stop runs on the GTK thread: the kill is backgrounded, and the evaluator
  ;; isn't settled (the registry waits on it before quitting) until it ends.
  (let [release (promise)]
    (with-jank [j {:slow {"(spin)" 5000}}
                {:kill (fn [c] (deref release 5000 nil) (some-> (:live c) (reset! false)))} srv]
      (let [e (:ev j) done (promise) heard (atom [])]
        (connection/on-connection! e (fn [c] (swap! heard conj c)))
        (ev/eval! e "(spin)" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-until! #(some (fn [m] (= "(spin)" (get m "code"))) (fake/requests srv)))
        (let [t0 (System/currentTimeMillis)]
          (ev/interrupt! e)
          (is (< (- (System/currentTimeMillis) t0) 500) "interrupt! didn't wait for the kill"))
        (is (= {:status :interrupted} (deref done 5000 :timeout)))
        (is (not (jank-eval/settled? e)) "a kill still running keeps the evaluator unsettled")
        (is (= false (last @heard)) "disconnected is said at Stop")
        (deliver release true)
        (wait-until! #(jank-eval/settled? e))
        (is (jank-eval/settled? e))))))
