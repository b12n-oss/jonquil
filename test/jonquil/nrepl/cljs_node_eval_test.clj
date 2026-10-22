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

;; --- browser mode ---

(def ^:private fake-url "http://127.0.0.1:1234")

(defn- url-of
  "The page URL of launch n (default the first): built from the HTTP port
  the evaluator passed to it."
  ([launches] (url-of launches 0))
  ([launches n] (str "http://127.0.0.1:" (:http-port (nth @launches n)))))

(defn- with-browser
  "Like with-node, for a browser selection. f gets srv and a map with :ev,
  :launches (the opts each launch got), :opened (urls :open-url got) and
  :kills, plus :killed (each child the kill hook got). launch-results is a
  seq of overrides for the first launches (e.g. {:error :http-port-taken});
  later launches succeed."
  [fake-opts {:keys [launch-results open-result timings]} f]
  (let [srv (fake/start! fake-opts)
        launches (atom []) opened (atom []) kills (atom 0) killed (atom [])
        results (atom (vec launch-results))
        e (node/make-cljs-node
           {:profile dialect/cljs :runtime :launch :project-dir nil :cljs-target :browser}
           {:timings timings
            :launch (fn [o]
                      (swap! launches conj o)
                      (let [r (first @results)]
                        (swap! results #(vec (rest %)))
                        (or r {:port (:port srv) :url fake-url :pid -1 :log "/fake/log"
                               :run-dir "/fake/run"})))
            :open-url (fn [url] (swap! opened conj url) (if (nil? open-result) true open-result))
            :find-clojure (fn [] "/fake/clojure")
            :ensure-launcher (fn [] "/fake/launcher.clj")
            :alive? (fn [_] true)
            :kill (fn [c] (swap! kills inc) (swap! killed conj c))})]
    (try (f srv {:ev e :launches launches :opened opened :kills kills :killed killed})
         (finally (ev/stop! e) (fake/stop! srv)))))

(defn- text-of [out] (apply str (map second out)))

(deftest browser-first-run-opens-once-test
  (with-browser {} {}
    (fn [_ {:keys [ev launches opened]}]
      (let [{:keys [result out]} (run ev "(+ 1 2)" {:run? false})]
        (is (= "3" (:printed result)))
        (is (= [(url-of launches)] @opened))
        (is (str/includes? (text-of out) (cljs-node/opened-message (url-of launches))))
        (is (= :browser (:target (first @launches))))
        (is (integer? (:http-port (first @launches)))))
      (let [{:keys [out]} (run ev "(+ 1 1)" {:run? false})]
        (is (= [(url-of launches)] @opened) "a second evaluation doesn't open again")
        (is (not (str/includes? (text-of out) "Opened")))))))

(deftest browser-open-url-failure-test
  (with-browser {} {:open-result false}
    (fn [_ {:keys [ev launches]}]
      (let [{:keys [out]} (run ev "1" {:run? false})]
        (is (str/includes? (text-of out) (str "; Open " (url-of launches) " in a browser.")))
        (is (not (str/includes? (text-of out) "Opened")))))))

(deftest browser-stop-then-run-reloads-test
  (with-browser {:slow-ms 5000} {}
    (fn [srv {:keys [ev launches opened kills]}]
      (let [done (promise)]
        (ev/eval! ev "(slow)" {:run? false :on-complete (fn [r] (deliver done r))})
        (is (wait-until #(some (fn [f] (re-find #"slow" f)) (fake/received-forms srv)) 5000))
        (ev/interrupt! ev)
        (is (= {:status :interrupted} (deref done 5000 :timeout)))
        (is (wait-until #(= 1 @kills) 3000)))
      (let [{:keys [result out]} (run ev "(+ 1 1)" {:run? false})]
        (is (= "2" (:printed result)))
        (is (str/includes? (text-of out) (cljs-node/reload-message (url-of launches))))
        (is (= 1 (count @opened)) "no second tab")
        (is (= 2 (count @launches)))
        (is (= (:http-port (first @launches)) (:http-port (second @launches)))
            "the same HTTP port for both launches")))))

(deftest browser-port-taken-relaunches-test
  (with-browser {} {:launch-results [{:error :http-port-taken}]}
    (fn [_ {:keys [ev launches opened]}]
      (let [{:keys [result out]} (run ev "(+ 1 1)" {:run? false})]
        (is (= "2" (:printed result)))
        (is (= 2 (count @launches)))
        (is (not= (:http-port (first @launches)) (:http-port (second @launches))))
        (is (not (str/includes? (text-of out) "restarted")))
        ;; The page was never opened, so it opens now, at the new address.
        (is (= [(url-of launches 1)] @opened))
        (is (str/includes? (text-of out) (cljs-node/opened-message (url-of launches 1))))
        (is (str/includes? (text-of out) (str "; The browser port changed: the page is at "
                                              (url-of launches 1) ".")))
        (is (not (str/includes? (text-of out) "open http://"))))))
  (with-browser {} {:launch-results [{:error :http-port-taken} {:error :http-port-taken}]}
    (fn [_ {:keys [ev launches]}]
      (let [r (:result (run ev "(+ 1 1)" {:run? false}))]
        (is (= :notice (:status r)))
        (is (= node/port-twice-message (:text r)))
        (is (= 2 (count @launches)))))))

(deftest browser-port-change-after-the-first-open-asks-to-open-test
  (with-browser {} {:launch-results [nil {:error :http-port-taken}]}
    (fn [_ {:keys [ev launches opened]}]
      (run ev "(+ 1 1)" {:run? false})
      (#'node/kill-child! ev)
      (let [{:keys [out]} (run ev "(+ 1 1)" {:run? false})]
        (is (= 3 (count @launches)))
        (is (= 1 (count @opened)) "no second tab")
        (is (str/includes? (text-of out) (str "; The browser port changed: open " (url-of launches 2) ".")))))))

(deftest waiting-line-is-kept-for-node-test
  (with-node {} {}
    (fn [_ {:keys [ev]}]
      (let [out (atom [])]
        (ev/eval! ev "(+ 1 1)" {:run? true :on-output (fn [k s] (swap! out conj [k s]))
                                :on-complete (fn [_])})
        (is (wait-until #(not (ev/evaluating? ev)) 5000))
        (#'node/dispatch! ev (atom false) {:tag :out :val "Waiting for browser to connect to x\n"})
        (is (str/includes? (text-of @out) "Waiting for browser to connect to x"))))))

(deftest browser-waiting-line-is-dropped-test
  (with-browser {} {}
    (fn [_ {:keys [ev]}]
      (let [out (atom [])]
        (ev/eval! ev "(+ 1 1)" {:run? false :on-output (fn [k s] (swap! out conj [k s]))
                                :on-complete (fn [_])})
        (is (wait-until #(not (ev/evaluating? ev)) 5000))
        (#'node/dispatch! ev (atom false)
                          {:tag :out :val "Waiting for browser to connect to http://127.0.0.1:1234 ...\n"})
        (#'node/dispatch! ev (atom false) {:tag :out :val "kept\n"})
        (is (not (str/includes? (text-of @out) "Waiting for browser")))
        (is (str/includes? (text-of @out) "kept"))))))

(deftest browser-setup-waits-for-the-page-test
  ;; The browser's setup form blocks until a page connects: the page is
  ;; opened first (from the evaluator's own port), the wait is the page
  ;; bound, the child is kept, and the next Run finishes the setup.
  (with-browser {:hold-setup-ms 1200} {:timings {:page-timeout-ms 400}}
    (fn [srv {:keys [ev launches opened kills]}]
      (let [t0 (System/currentTimeMillis)
            r (:result (run ev "(+ 1 2)" {:run? false}))
            url (str "http://127.0.0.1:" (:http-port (first @launches)))]
        (is (= {:status :notice :text (cljs-node/no-page-message url)} r))
        (is (< (- (System/currentTimeMillis) t0) 1100) "the notice came at the page bound")
        (is (= [url] @opened) "opened before the setup finished")
        (is (zero? @kills) "the child is kept")
        (is (not (connection/connected? ev)) "not ready until the setup answers")
        (is (wait-until #(connection/connected? ev) 3000) "the late setup ret makes it ready")
        (let [r2 (:result (run ev "(+ 1 2)" {:run? false}))]
          (is (= "3" (:printed r2)) (pr-str r2)))
        (is (= 1 (count @launches)))
        (is (zero? @kills))
        (is (= [url] @opened))
        (is (= 1 (count (filter #(= cljs-node/setup-form %) (fake/received-forms srv)))))))))

(deftest browser-next-run-waits-for-pending-setup-test
  (with-browser {:hold-setup-ms 800} {:timings {:page-timeout-ms 300}}
    (fn [_ {:keys [ev kills]}]
      (is (= :notice (:status (:result (run ev "(+ 1 2)" {:run? false})))))
      ;; The setup answers ~500 ms later, inside this Run's own bound.
      (let [r (:result (run ev "(+ 1 2)" {:run? false}))]
        (is (or (= "3" (:printed r)) (= :notice (:status r))) (pr-str r)))
      (let [r (:result (run ev "(+ 1 2)" {:run? false}))]
        (is (= "3" (:printed r)) (pr-str r)))
      (is (zero? @kills)))))

(deftest browser-failed-late-setup-relaunches-test
  ;; The setup answers with an exception after the page bound: the child is
  ;; not kept to report it forever, the next Run launches a fresh one.
  (with-browser {:hold-setup-ms 500 :fail-setup? true} {:timings {:page-timeout-ms 200}}
    (fn [_ {:keys [ev launches kills killed]}]
      (is (= :notice (:status (:result (run ev "(+ 1 2)" {:run? false})))))
      (is (zero? @kills) "kept while the setup is pending")
      (is (wait-until #(= :failed (:setup-pending @(:state ev))) 3000))
      (let [{:keys [result out]} (run ev "(+ 1 2)" {:run? false})]
        (is (= 2 (count @launches)) "relaunched")
        (is (= 1 @kills) "the failed child was killed")
        (is (= :notice (:status result)) (pr-str result))
        (is (str/includes? (text-of out) (cljs-node/start-failed-message
                                          "the session setup failed. The log: /fake/log")))
        (is (nil? (:run-dir (first @killed))) "the kill keeps the run dir, so the log stays")))))

(deftest browser-setup-failing-inside-the-bound-kills-the-child-test
  (with-browser {:fail-setup? true} {}
    (fn [_ {:keys [ev launches kills killed]}]
      (let [r (:result (run ev "(+ 1 2)" {:run? false}))]
        (is (= :notice (:status r)))
        (is (str/includes? (:text r) "the session setup failed. The log: /fake/log") (:text r)))
      (is (= 1 @kills))
      (is (nil? (:run-dir (first @killed))) "the log stays")
      (is (nil? (:child @(:state ev))))
      (is (= 1 (count @launches))))))

(deftest browser-never-opens-an-empty-url-test
  (with-browser {} {}
    (fn [_ {:keys [ev opened]}]
      (run ev "1" {:run? false})
      (is (seq @opened))
      (is (every? #(str/starts-with? % "http://127.0.0.1:") @opened)))))

(deftest browser-limitations-test
  (with-browser {} {}
    (fn [_ {:keys [ev]}]
      (is (= cljs-node/browser-limitations (ev/limitations ev)))))
  (with-node {} {}
    (fn [_ {:keys [ev]}]
      (is (= cljs-node/limitations (ev/limitations ev))))))

(deftest node-never-opens-a-url-test
  (let [opened (atom [])]
    (with-node {} {:open-url (fn [u] (swap! opened conj u) true)}
      (fn [_ {:keys [ev]}]
        (run ev "(+ 1 1)" {:run? true})
        (run ev "(+ 1 1)" {:run? true})
        (is (empty? @opened))))))

(deftest open-url-leaves-a-slow-opener-running-test
  (let [dir (java.io.File/createTempFile "jonquil-open" "")
        _ (.delete dir) _ (.mkdirs dir)
        slow (java.io.File. dir "slow.sh")]
    (try
      (spit slow "#!/bin/sh\nsleep 2\n")
      (.setExecutable slow true)
      (is (true? (#'node/open-url! "http://x" (.getPath slow))) "still running after a second is fine")
      (finally (doseq [f [slow dir]] (.delete f))))))

(deftest open-url-honours-the-override-command-test
  (let [dir (java.io.File/createTempFile "jonquil-open" "")
        _ (.delete dir) _ (.mkdirs dir)
        out (java.io.File. dir "url.txt")
        script (java.io.File. dir "fake-open.sh")
        bad (java.io.File. dir "fail.sh")]
    (try
      (spit script (str "#!/bin/sh\nprintf '%s' \"$1\" > " (.getPath out) "\n"))
      (spit bad "#!/bin/sh\nexit 3\n")
      (.setExecutable script true) (.setExecutable bad true)
      (is (true? (#'node/open-url! "http://127.0.0.1:4321" (.getPath script))))
      (is (wait-until #(.exists out) 2000))
      (is (= "http://127.0.0.1:4321" (slurp out)))
      (is (false? (#'node/open-url! "http://x" (.getPath bad))) "a non-zero exit is false")
      (is (false? (#'node/open-url! "http://x" "/no/such/command")) "never throws")
      (finally (doseq [f [out script bad dir]] (.delete f))))))

(deftest node-setup-failure-keeps-and-names-the-log-test
  ;; A failed setup names the child's log, and the kill leaves the run dir
  ;; (the log's folder) on disk; a plain Stop still removes it.
  (let [srv (fake/start! {:fail-setup? true})
        killed (atom [])
        {:keys [ev]} (make-test-node srv {:launch (fn [_] {:port (:port srv) :pid -1 :log "/fake/log"
                                                            :run-dir "/fake/run"})
                                          :kill (fn [c] (swap! killed conj c))})]
    (try
      (let [r (:result (run ev "(+ 1 2)" {:run? false}))]
        (is (= {:status :notice
                :text (cljs-node/start-failed-message "the session setup failed. The log: /fake/log")}
               r)))
      (is (= 1 (count @killed)))
      (is (nil? (:run-dir (first @killed))))
      (is (nil? (:child @(:state ev))))
      (finally (ev/stop! ev) (fake/stop! srv))))
  (with-node {} {}
    (fn [_ {:keys [ev]}]
      (let [killed (atom [])
            ev (assoc ev :hooks (assoc (:hooks ev) :kill (fn [c] (swap! killed conj c))))]
        (swap! (:state ev) assoc :child {:pid -1 :run-dir "/fake/run"})
        (#'node/kill-child! ev)
        (is (= "/fake/run" (:run-dir (first @killed))) "without :keep-log? the run dir goes")))))

(deftest connect-failure-keeps-and-names-the-log-test
  ;; The child's port refuses the connection: the notice names the log and
  ;; the kill leaves the run dir.
  (let [dead (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort dead)
        _ (.close dead)
        killed (atom [])
        e (node/make-cljs-node
           {:profile dialect/cljs :runtime :launch :project-dir nil}
           {:launch (fn [_] {:port port :pid -1 :log "/fake/log" :run-dir "/fake/run"})
            :find-clojure (fn [] "/fake/clojure")
            :ensure-launcher (fn [] "/fake/launcher.clj")
            :alive? (fn [_] true)
            :kill (fn [c] (swap! killed conj c))})]
    (try
      (let [r (:result (run e "(+ 1 2)" {:run? false}))]
        (is (= :notice (:status r)) (pr-str r))
        (is (str/includes? (:text r) (str "couldn't connect to port " port)) (:text r))
        (is (str/ends-with? (:text r) ". The log: /fake/log") (:text r)))
      (is (= 1 (count @killed)))
      (is (nil? (:run-dir (first @killed))) "the log stays")
      (finally (ev/stop! e)))))
