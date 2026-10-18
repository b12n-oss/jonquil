(ns jonquil.nrepl.evaluator-test
  "The nREPL evaluator against real Jolt children. Each test stops its own
  evaluator, which kills only the child that evaluator spawned."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.inspect :as inspect]
            [jonquil.nrepl.child :as child]
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

(defn- run-owned [e code owner]
  (let [done (promise) out (atom [])]
    (ev/eval! e code {:run? true :owner owner
                      :on-output (fn [k s] (swap! out conj [k s]))
                      :on-complete (fn [r] (deliver done r))})
    [(deref done 20000 :timeout) @out]))

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

(defn- alive? [pid] (str/includes? (:out (shell/sh "ps" "-p" (str pid) "-o" "pid=")) (str pid)))

(deftest make-dispatches-on-runtime-test
  (let [e (make (temp-project))]
    (is (instance? jonquil.nrepl.evaluator.NRepl e))
    (is (nil? (nrepl/child-pid e)) "not started until asked")
    (is (= 2 (count (ev/limitations e))) "stdin and jump; the inspector line only when a live child lacks it")))

(deftest run-repl-and-help-test
  (let [dir (temp-project) e (make dir)]
    (try
      (is (nil? (ev/start! e)))
      (let [[r out] (run e "(ns demo.run)\n(defn greet \"Says hi.\" [n] (println \"hi\" n) :hi)\n(greet 1)" true (str dir "/src/demo/run.clj"))]
        (is (= {:status :ok :printed ":hi" :ns 'demo.run} r))
        (is (= [[:out "hi 1\n"]] out) "output arrives once, when the evaluation finishes")
        (is (= 'demo.run (ev/prompt-ns e))))
      (is (= ":hi" (:printed (first (run e "(greet 2)" false)))) "the REPL follows the Run's namespace")
      (is (= ["(greet 2)"] (:items @(ev/history e))))
      (testing "an error is text, not a Throwable"
        (is (= {:status :error :error-text "Error: Divide by zero"}
               (dissoc (first (run e "(/ 1 0)" false)) :ns))))
      (testing "help: the server's names merged with offline ones"
        (is (some #{"greet"} (map :symbol (ev/complete e "gre" "(gre" 4 nil))))
        (is (= "Says hi." (:doc (ev/lookup e "greet" "(greet 1)" 1))))
        (is (nil? (:line (ev/lookup e "greet" "(greet 1)" 1))) "lookup has no line")
        (is (= :found (:status (ev/lookup e "if" "(if x y)" 1))) "a special form falls back to the offline doc"))
      (testing "busy"
        (let [done (promise)]
          (ev/eval! e "(Thread/sleep 500)" {:run? false :on-complete (fn [r] (deliver done r))})
          (is (= nrepl/busy-warning (ev/eval! e "1" {:run? false})))
          (is (ev/evaluating? e))
          (deref done 5000 :timeout)))
      (finally (ev/stop! e)))))

(deftest reset-on-run-clears-the-child-test
  (let [dir (temp-project) e (make dir)]
    (try
      (run e "(def kept 1) (ns demo.a) (def in-a 2)" true)
      ;; The REPL follows the Run into demo.a, as it does in-process.
      (run e "(def repl-def 3)" false)
      (is (= "[1 3]" (:printed (first (run e "[user/kept demo.a/repl-def]" false)))))
      (run e "(+ 1 1)" true)
      (is (= "[nil nil]"
             (:printed (first (run e "[(resolve 'user/kept) (find-ns 'demo.a)]" false))))
          "user defs, and the Run's namespace with its REPL def, are gone")
      (testing ":accumulate keeps them"
        (ev/set-mode! e :accumulate)
        (run e "(def kept 1)" true)
        (run e "(+ 1 1)" true)
        (is (= "1" (:printed (first (run e "user/kept" false))))))
      (finally (ev/stop! e)))))

(deftest stop-interrupts-test
  (let [e (make (temp-project)) done (promise)]
    (try
      (ev/start! e)
      (let [pid (nrepl/child-pid e)]
        (ev/eval! e "(def before 1) (loop [] (recur))" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-for-eval-id e)
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 5000 :timeout))))
        (is (= pid (nrepl/child-pid e)) "interrupt, not a restart")
        (is (= "1" (:printed (first (run e "before" false)))) "the session keeps its state"))
      (finally (ev/stop! e)))))

(deftest stop-while-unresponsive-restarts-test
  (let [e (make (temp-project)) done (promise) out (atom [])]
    (try
      (ev/start! e)
      (let [pid (nrepl/child-pid e)]
        (ev/eval! e "(loop [] (recur))" {:run? false
                                         :on-output (fn [k s] (swap! out conj [k s]))
                                         :on-complete (fn [r] (deliver done r))})
        (wait-for-eval-id e)
        (shell/sh "kill" "-STOP" (str pid))
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 10000 :timeout))))
        (is (wait-until (fn [] (some? (nrepl/child-pid e))) 10000) "a new child")
        (is (not= pid (nrepl/child-pid e)))
        (is (not (alive? pid)) "the stopped child was killed")
        (is (wait-until (fn [] (some (fn [[_ s]] (str/includes? s "Stop restarted the Jolt runtime")) @out)) 5000))
        (is (= "3" (:printed (first (run e "(+ 1 2)" false)))) "the new child answers"))
      (finally (ev/stop! e)))))

(deftest child-dies-mid-eval-test
  (let [e (make (temp-project)) done (promise)]
    (try
      (ev/start! e)
      (let [pid (nrepl/child-pid e)]
        (ev/eval! e "(Thread/sleep 30000)" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-for-eval-id e)
        (shell/sh "kill" "-9" (str pid))
        (is (= {:status :error :error-text "The Jolt runtime exited before this evaluation finished."}
               (deref done 5000 :timeout)))
        (is (not (ev/evaluating? e)))
        (let [[r out] (run e "(+ 1 2)" false)]
          (is (= "3" (:printed r)) "the next evaluation starts a new child")
          (is (some (fn [[k s]] (and (= :system k) (str/includes? s "had exited"))) out))))
      (finally (ev/stop! e)))))

(deftest stdin-notice-test
  (let [e (make (temp-project))]
    (try
      (let [[r out] (run e "(read-line)" false)]
        (is (= "nil" (:printed r)))
        (is (= [[:system nrepl/stdin-notice]] out)))
      (is (= [] (second (run e "(read-string \"1\")" false))))
      (finally (ev/stop! e)))))

(deftest stop-kills-its-own-child-only-test
  (let [dir (temp-project)
        _ (spit (io/file dir ".nrepl-port") "4242\n")
        e (make dir)
        other (make (temp-project))]
    (ev/start! e)
    (ev/start! other)
    (let [pid (nrepl/child-pid e) other-pid (nrepl/child-pid other)]
      (ev/stop! e)
      (is (not (alive? pid)))
      (is (alive? other-pid) "another evaluator's child is untouched")
      (is (= "4242\n" (slurp (io/file dir ".nrepl-port"))))
      (is (nil? (nrepl/child-pid e)))
      (is (nil? (ev/stop! e)) "twice is fine")
      (ev/stop! other)
      (is (not (alive? other-pid))))))

(deftest no-middleware-means-stop-by-restart-test
  (with-redefs [child/middleware-source (constantly nil)]
    (let [e (make (temp-project)) done (promise)]
      (try
        (ev/start! e)
        (is (some #(str/includes? % "no interrupt op") (ev/limitations e)))
        (let [pid (nrepl/child-pid e)]
          (ev/eval! e "(loop [] (recur))" {:run? false :on-complete (fn [r] (deliver done r))})
          (wait-for-eval-id e)
          (ev/interrupt! e)
          (is (= :interrupted (:status (deref done 5000 :timeout))))
          (is (wait-until (fn [] (let [p (nrepl/child-pid e)] (and p (not= p pid)))) 10000) "restarted"))
        (finally (ev/stop! e))))))

(deftest unfetchable-git-middleware-falls-back-to-a-plain-server-test
  (with-redefs [child/middleware-source
                (constantly {:git {:git/url "file:///nonexistent/jonquil-nrepl.git"
                                   :git/tag "v0.4.0" :git/sha "7efdd6c"}})]
    (let [e (make (temp-project))]
      (try
        (is (nil? (ev/start! e)) "the retry without middleware starts")
        (is (some #(str/includes? % "couldn't be fetched") (ev/limitations e)))
        (is (some #(str/includes? % "Jolt said: git dep: could not list file:///nonexistent") (ev/limitations e))
            "jolt's own line, not a guess")
        (is (some #(str/includes? % "no interrupt op") (ev/limitations e)))
        (is (= "3" (:printed (first (run e "(+ 1 2)" false)))))
        (finally (ev/stop! e))))))

(defn- fetch-failure
  "An ex-info shaped like child/start!'s for a child that exited, whose log
  holds jolt's git-dependency failure line."
  []
  (let [log (io/file (temp-dirs/temp-dir! "jonquil-nrepl-test-") "child.log")]
    (spit log "Unhandled exception: git dep: could not list https://x.invalid/n.git (git ls-remote exited 128 after 3 attempts: fatal: unable to access)\n  trace:\n")
    (ex-info "jolt nrepl-server exited: ..." {:log (.getPath log)})))

(deftest fetch-error-line-test
  (is (= "git dep: could not list https://x.invalid/n.git (git ls-remote exited 128 after 3 attempts: fatal: unable to access)"
         (nrepl/fetch-error-line (fetch-failure))))
  (is (nil? (nrepl/fetch-error-line (ex-info "no log" {}))))
  (let [log (io/file (temp-dirs/temp-dir! "jonquil-nrepl-test-") "child.log")]
    (spit log "jolt nrepl-server: bind() failed\n")
    (is (nil? (nrepl/fetch-error-line (ex-info "x" {:log (.getPath log)}))) "another failure isn't a fetch failure")))

(deftest only-a-fetch-failure-falls-back-test
  (let [sources (atom [])]
    (with-redefs [child/middleware-source (constantly {:git child/nrepl-git-coord})
                  child/start! (fn [_ source] (swap! sources conj source)
                                 (throw (ex-info "jolt nrepl-server didn't answer within 15000 ms" {})))]
      (let [e (make (temp-project))]
        (is (str/starts-with? (ev/start! e) "Couldn't start Jolt over nREPL: jolt nrepl-server didn't answer"))
        (is (= [{:git child/nrepl-git-coord}] @sources) "a timeout isn't retried as a plain server")
        (ev/stop! e)))))

(deftest no-fallback-after-stop-test
  (let [sources (atom []) e (make (temp-project))]
    (with-redefs [child/middleware-source (constantly {:git child/nrepl-git-coord})
                  child/start! (fn [_ source] (swap! sources conj source)
                                 (swap! (:state e) assoc :stopped? true)
                                 (throw (fetch-failure)))]
      (is (string? (ev/start! e)))
      (is (= 1 (count @sources)) "stop! during the fetch: no plain-server retry"))))

(deftest a-failed-fetch-is-remembered-test
  (let [sources (atom [])
        real-start child/start!]
    (with-redefs [child/middleware-source (constantly {:git child/nrepl-git-coord})
                  child/start! (fn [d source] (swap! sources conj source)
                                 (if source (throw (fetch-failure)) (real-start d source)))]
      (let [e (make (temp-project))]
        (try
          (is (nil? (ev/start! e)))
          (is (= [{:git child/nrepl-git-coord} nil] @sources))
          (let [pid (nrepl/child-pid e) done (promise)]
            (ev/eval! e "(loop [] (recur))" {:run? false :on-complete (fn [r] (deliver done r))})
            (wait-for-eval-id e)
            (ev/interrupt! e)
            (is (= :interrupted (:status (deref done 5000 :timeout))))
            (is (wait-until (fn [] (let [p (nrepl/child-pid e)] (and p (not= p pid)))) 10000) "Stop restarted the plain server"))
          (is (= [{:git child/nrepl-git-coord} nil nil] @sources) "the restart skipped the fetch")
          (is (some #(str/includes? % "Jolt said: git dep:") (ev/limitations e)) "the line survives the restart")
          (finally (ev/stop! e)))))))

(defn- inspect [e op args]
  (let [p (promise)]
    (ev/inspect! e op args (fn [v] (deliver p v)))
    (deref p 10000 :timeout)))

(deftest inspector-over-nrepl-test
  (let [e (make (temp-project))]
    (try
      (is (ev/inspectable? e) "true before the child is live")
      (is (= {:error nrepl/inspect-not-running} (inspect e :start {:rows 10 :cols 60})))
      (ev/start! e)
      (is (ev/inspectable? e))
      (is (not-any? #(str/includes? % "inspector") (ev/limitations e)))
      (run e "(def data {:a [1 2 3] :rows [{:id 1} {:id 2}]})" false)
      (run e "data" false)
      (let [v (inspect e :start {:rows 10 :cols 60})]
        (is (= ["*1" 2] [(:path v) (:total v)])))
      (is (= "*1 / :rows" (:path (inspect e :in {:idx 1}))))
      (is (= "*1" (:path (inspect e :out {}))))
      (is (= "#'user/picked" (:defd (inspect e :def {:name "picked"}))) "into the prompt namespace")
      (is (= "true" (:printed (first (run e "(= *1 data picked)" false)))) "the ops left *1 alone")
      (finally (ev/stop! e)))))

(deftest a-plain-server-still-has-the-inspector-test
  (with-redefs [child/middleware-source (constantly nil)]
    (let [e (make (temp-project))]
      (try
        (ev/start! e)
        (is (ev/inspectable? e))
        (run e "[:x :y]" false)
        (is (= 2 (:total (inspect e :start {:rows 10 :cols 40}))))
        (finally (ev/stop! e))))))

(deftest no-inspector-files-means-unavailable-test
  (with-redefs [child/inspect-root (constantly nil)]
    (let [e (make (temp-project))]
      (try
        (ev/start! e)
        (is (not (ev/inspectable? e)))
        (is (= {:error inspect/unavailable-message} (inspect e :start {:rows 10 :cols 40})))
        (is (some #(str/includes? % "No inspector") (ev/limitations e)))
        (finally (ev/stop! e))))))

(deftest limitations-name-the-way-back-test
  (let [e (make (temp-project))]
    (try
      (ev/start! e)
      (let [ls (ev/limitations e)]
        (is (not-any? #(str/includes? % "Output appears when") ls) "nREPL v0.4.0 streams")
        (is (some #(str/includes? % "{:runtime :in-process}") ls)))
      (finally (ev/stop! e)))))

(deftest stop-during-start-leaves-no-child-test
  (let [dir (temp-project) e (make dir)
        release (promise)
        real-start child/start!
        captured (atom nil)]
    (try
      (with-redefs [child/start! (fn [d root]
                                   ;; Blocks the spawn step so the test can call
                                   ;; stop! while start-runtime! is still inside it.
                                   (deref release 5000 nil)
                                   (let [c (real-start d root)]
                                     (reset! captured c)
                                     c))]
        (let [start-fut (future (ev/start! e))]
          (Thread/sleep 300) ;; start-runtime! is now blocked in child/start!, holding start-lock
          (let [stop-fut (future (ev/stop! e))]
            (Thread/sleep 200) ;; stop! has set its stopping flag and is now blocked on start-lock
            (deliver release true)
            @start-fut
            @stop-fut))
        (is (wait-until (fn [] (some? @captured)) 5000) "a child was spawned")
        (is (wait-until (fn [] (not (child/alive? @captured))) 3000) "and it's dead, not orphaned")
        (is (nil? (nrepl/child-pid e))))
      (finally
        (when (and @captured (child/alive? @captured)) (child/kill! @captured))))))

(deftest stop-during-cold-start-returns-fast-test
  (let [dir (temp-project) e (make dir)
        real-start child/start!
        captured (atom nil)]
    (try
      (with-redefs [child/start! (fn [d root]
                                   ;; A cold start (child/start! itself, not
                                   ;; just the connect/clone after it) takes
                                   ;; 5s here, well past start-lock's own
                                   ;; ready-deadline-ms window in spirit --
                                   ;; long enough that a blocking stop! would
                                   ;; freeze the GTK thread for real.
                                   (Thread/sleep 5000)
                                   (let [c (real-start d root)]
                                     (reset! captured c)
                                     c))]
        (let [start-fut (future (ev/start! e))]
          (Thread/sleep 500) ;; start-runtime! is now blocked in child/start!, holding start-lock
          (let [t0 (System/currentTimeMillis)]
            (ev/stop! e)
            (let [elapsed (- (System/currentTimeMillis) t0)]
              (is (< elapsed 200) (str "stop! during a cold start took " elapsed " ms"))))
          (is (wait-until (fn [] (some? @captured)) 8000) "the delayed start eventually spawned a child")
          (is (wait-until (fn [] (not (child/alive? @captured))) 8000) "and it's dead, not orphaned")
          (is (nil? (nrepl/child-pid e)))
          @start-fut))
      (finally
        (when (and @captured (child/alive? @captured)) (child/kill! @captured))))))

(deftest no-spawn-after-stop-test
  ;; I2 (phase-close review): start-runtime! didn't check :stopped? before
  ;; spawning, so a queued eval! (or a direct start!) reached after stop!
  ;; still spawned a doomed child. Counts real spawns with a redef and
  ;; asserts eval! after stop! never triggers one, and completes instead of
  ;; hanging.
  (let [e (make (temp-project))
        real-start child/start!
        spawn-count (atom 0)]
    (with-redefs [child/start! (fn [d root] (swap! spawn-count inc) (real-start d root))]
      (ev/stop! e)
      (let [[r _] (run e "(+ 1 2)" false)]
        (is (not= :timeout r) "the eval completed, it didn't hang")
        (is (contains? #{:error :interrupted} (:status r)) (str "no successful eval after stop!: " r)))
      (is (zero? @spawn-count) "start-runtime! spawned nothing once :stopped? was set"))))

(deftest double-stop-restarts-once-test
  (let [e (make (temp-project)) done (promise) out (atom [])
        complete-count (atom 0)]
    (try
      (ev/start! e)
      (let [pid (nrepl/child-pid e)]
        (ev/eval! e "(loop [] (recur))" {:run? false
                                         :on-output (fn [k s] (swap! out conj [k s]))
                                         :on-complete (fn [r] (swap! complete-count inc) (deliver done r))})
        (wait-for-eval-id e)
        (shell/sh "kill" "-STOP" (str pid))
        (ev/interrupt! e)
        (ev/interrupt! e)
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 10000 :timeout))))
        (is (wait-until (fn [] (some? (nrepl/child-pid e))) 10000) "a new child")
        (Thread/sleep 300)
        (is (= 1 (count (filter (fn [[_ s]] (str/includes? s "Stop restarted the Jolt runtime")) @out)))
            "exactly one restart line for three presses")
        (is (= 1 @complete-count) "on-complete fires once"))
      (finally (ev/stop! e)))))

(deftest stop-racing-finish-never-sticks-test
  ;; I1 (phase-close review): interrupt! used to read :running and write
  ;; it back in a separate step, so a finish! landing in between recreated
  ;; :running out of nil, and eval! then refused every later Run as busy
  ;; for good. Mirrors the reviewer's own probe (phase-review-report.md,
  ;; probe/stuck.clj): a presser future hammers interrupt! against no
  ;; child (so :running never gets an :id) while this thread repeatedly
  ;; sets and clears :running, the shape finish! uses. The probe found 921
  ;; stuck states in 200000 cycles on the pre-fix code.
  (let [e (make (temp-project))
        state (:state e)
        stop? (atom false)
        presser (future (loop [] (when-not @stop? (ev/interrupt! e) (recur))))]
    (dotimes [_ 20000]
      (swap! state assoc :running {:opts {}})
      (swap! state assoc :running nil))
    (reset! stop? true)
    @presser
    (is (nil? (:running @state)) "finish!'s clear always wins; interrupt! never recreates :running")
    (is (not (ev/evaluating? e)))
    (try
      (is (not= nrepl/busy-warning (ev/eval! e "1" {:run? false})) "the next eval! isn't refused as busy")
      (finally (ev/stop! e)))))

(deftest stop-before-send-is-honoured-test
  ;; I4 (phase-close review): a Stop landing after run-worker!'s own check
  ;; but before send-main! records the request's :id used to be lost --
  ;; interrupt! had nothing to act on yet, and nothing looked at the flag
  ;; again once the id landed. Redefines client/request! to call
  ;; interrupt! right after the real send goes out (send! writes and
  ;; flushes synchronously inside request!, so the write can't be undone)
  ;; but before send-main! records the id, exactly the gap I4 closes. Only
  ;; the eval/load-file send is intercepted, not the interrupt op the fix
  ;; itself goes on to send.
  (let [dir (temp-project) e (make dir)
        real-request client/request!]
    (try
      (ev/start! e)
      (let [done (promise) out (atom [])]
        (with-redefs [client/request! (fn [conn msg on-msg]
                                        (let [id (real-request conn msg on-msg)]
                                          (when (contains? #{"eval" "load-file"} (:op msg))
                                            (ev/interrupt! e))
                                          id))]
          (ev/eval! e "(loop [] (recur))" {:run? false
                                           :on-output (fn [k s] (swap! out conj [k s]))
                                           :on-complete (fn [r] (deliver done r))})
          (is (= :interrupted (:status (deref done 10000 :timeout))))
          (is (= [] @out) "the loop never produced output")))
      (finally (ev/stop! e)))))

(deftest failed-restart-is-reported-test
  ;; I3 (phase-close review): restart-for-stop! used to always print the
  ;; success line, even when the restart failed. Forces start-runtime! to
  ;; fail during the restart and checks the console shows the failure
  ;; line instead of the "restarted" line.
  (let [e (make (temp-project)) done (promise) out (atom [])]
    (try
      (ev/start! e)
      (let [pid (nrepl/child-pid e)]
        (ev/eval! e "(loop [] (recur))" {:run? false
                                         :on-output (fn [k s] (swap! out conj [k s]))
                                         :on-complete (fn [r] (deliver done r))})
        (wait-for-eval-id e)
        (shell/sh "kill" "-STOP" (str pid))
        (with-redefs [child/start! (fn [_ _] (throw (ex-info "boom" {})))]
          (ev/interrupt! e)
          (is (= :interrupted (:status (deref done 10000 :timeout))))
          (is (wait-until (fn [] (some (fn [[k s]] (and (= :error k)
                                                        (str/includes? s "couldn't restart the Jolt runtime")))
                                       @out))
                          5000)
              "the console shows the failed restart")
          (is (not (some (fn [[_ s]] (str/includes? s "Stop restarted the Jolt runtime")) @out))
              "and not the success line")))
      (finally (ev/stop! e)))))

(deftest owner-reset-over-nrepl-test
  (let [e (make (temp-project))]
    (try
      (is (= :ok (:status (first (run-owned e "(ns tabs.a) (defn hello [] :hi)" 1)))))
      (is (= :ok (:status (first (run-owned e "(ns tabs.b) (def x 1)" 2)))))
      (testing "a reset Run for owner 1 leaves tabs.b and recreates tabs.a"
        (is (= :ok (:status (first (run-owned e "(ns tabs.a) (def only 1)" 1)))))
        (is (= "1" (:printed (first (run e "tabs.b/x" false)))))
        (is (= "nil" (:printed (first (run e "(ns-resolve 'tabs.a 'hello)" false))))))
      (testing "the owner snapshots don't move the session out of the Run's namespace"
        (run-owned e "(ns tabs.c) (def here 3)" 3)
        (is (= "3" (:printed (first (run e "here" false))))))
      (finally (ev/stop! e)))))

(deftest history-vars-survive-owner-snapshots-test
  ;; Final review finding 1: the owner snapshots are evals on the user's
  ;; session, and the server shifts *1/*2/*3 on every successful eval.
  (let [e (make (temp-project))]
    (try
      (run e "(+ 1 2)" false)
      (run e "(+ 10 10)" false)
      (is (= "[20 3]" (:printed (first (run e "[*1 *2]" false)))))
      (run-owned e "(ns tabs.h) (def v 5) v" 1)
      (is (= "5" (:printed (first (run e "*1" false)))) "a Run's value is *1 afterwards")
      (finally (ev/stop! e)))))

(deftest history-vars-survive-reset-on-run-test
  ;; Reset on Run's cleanup is one more eval on the user's session, and the
  ;; server shifts *1/*2/*3 for it too.
  (let [e (make (temp-project))]
    (try
      (run e "(throw (ex-info \"boom\" {}))" false)
      (doseq [c ["1" "2" "3"]] (run e c false))
      (run e "(+ 10 20)" true)
      (is (= "[30 3 2]" (:printed (first (run e "[*1 *2 *3]" false)))))
      (is (= "\"boom\"" (:printed (first (run e "(ex-message *e)" false)))) "the cleanup leaves *e alone")
      (finally (ev/stop! e)))))

(deftest repl-helpers-referred-test
  (let [e (make (temp-project))]
    (try
      (is (= [[:out "{:a 1}\n"]] (second (run e "(pprint {:a 1})" true))) "in a Run")
      (is (str/includes? (apply str (map second (second (run e "(doc inc)" false)))) "clojure.core/inc")
          "at the prompt")
      (is (= ":mine" (:printed (first (run e "(defn dir [& _] :mine) (dir)" false))))
          "a program's own definition wins")
      (run e "{:a 1}" false)
      (is (= [[:out "{:a 1}\n"]] (second (run e "(pp)" false))) "pp prints *1")
      (finally (ev/stop! e)))))

(deftest release-owner-over-nrepl-test
  ;; Final review finding 2: a closed tab's namespaces go to :repl, so the
  ;; next Run clears them.
  (let [e (make (temp-project))]
    (try
      (run-owned e "(ns orphan.a) (defn old-fn [] 1)" 2)
      (ev/release-owner! e 2)
      (run-owned e "(ns orphan.a) (def x 1)" 3)
      (is (= "nil" (:printed (first (run e "(ns-resolve 'orphan.a 'old-fn)" false)))))
      (finally (ev/stop! e)))))
