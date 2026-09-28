(ns jonquil.nrepl.attach-test
  "The attach evaluator against dev/fake_cljd_nrepl.clj's fake fork server.
  Every test owns its temp project and its fake server, and stops both."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fake-cljd-nrepl :as fake]
            [jonquil.cljd :as cljd]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.attach :as attach]
            [jonquil.nrepl.client :as client]))

(def drive-text
  "(ns demo.drive\n  (:require [cljd.string :as str]))\n\n(defn where [] 1)\n\n(comment\n  (where))\n\n(defonce state (atom nil))\n")

(defn- temp-app
  "A throwaway app directory: pubspec.yaml, dev/demo/drive.cljd, and an
  NREPL.lock holding port-text (none when nil). [dir file]."
  [port-text]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "jonquil-attach-test-" (System/currentTimeMillis) "-" (random-uuid)))
        f (io/file d "dev/demo/drive.cljd")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "pubspec.yaml") "name: demo\n")
    (spit f drive-text)
    (when port-text (spit (io/file d "NREPL.lock") port-text))
    [(.getCanonicalPath d) (.getCanonicalPath f)]))

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete ^java.io.File f)))

(defn- lock-state
  "NREPL.lock's bytes and mtime, and whether a REPL.lock exists: the files
  Jonquil must never write, delete or create."
  [dir]
  (let [f (io/file dir "NREPL.lock")]
    [(when (.exists f) (slurp f)) (.lastModified f) (.exists (io/file dir "REPL.lock"))]))

(defn- make [dir]
  (attach/make-attach {:profile dialect/cljd :runtime :attach :project-dir dir}
                      {:describe-timeout-ms 500 :clone-timeout-ms 500 :still-waiting-ms 200}))

(defn- run
  "Evaluates and waits. [result output] where output is every on-output
  call as [kind text]."
  [e code opts]
  (let [done (promise) out (atom [])]
    (ev/eval! e code (assoc opts
                            :on-output (fn [k s] (swap! out conj [k s]))
                            :on-complete (fn [r] (deliver done r))))
    [(deref done 10000 :timeout) @out]))

(defn- texts [out kind] (keep (fn [[k s]] (when (= k kind) s)) out))

(defn- with-app
  "Runs (f srv dir file) against a fake server (opts) whose port is in the
  app's NREPL.lock, then stops the server and deletes the app."
  [opts f]
  (let [srv (fake/start! opts)
        [dir file] (temp-app (str (:port srv) "\n"))]
    (try (f srv dir file)
         (finally (fake/stop! srv) (delete-tree! dir)))))

(deftest make-dispatches-on-runtime-test
  (let [e (ev/make {:profile dialect/cljd :runtime :attach :project-dir "/nowhere"})]
    (is (instance? jonquil.nrepl.attach.Attach e))
    (is (not (attach/connected? e)) "nothing connects until an evaluation needs it")
    (is (nil? (ev/start! e)))
    (is (= 'cljd.user (ev/prompt-ns e)))
    (is (= 5 (count (ev/limitations e))))))

(deftest connect-rules-test
  (testing "no NREPL.lock: the not-running notice, and nothing written"
    (let [[dir file] (temp-app nil) e (make dir)]
      (try
        (is (= {:status :notice :text cljd/not-running-message} (first (run e "(+ 1 2)" {:run? false}))))
        (is (= {:status :notice :text cljd/not-running-message} (first (run e drive-text {:run? true :path file}))))
        (is (not (attach/connected? e)))
        (is (= [nil 0 false] (lock-state dir)))
        (finally (delete-tree! dir)))))
  (testing "no project at all"
    (let [e (attach/make-attach {:profile dialect/cljd :runtime :attach :project-dir nil})]
      (is (= {:status :notice :text cljd/no-project-message} (first (run e "1" {:run? false}))))))
  (testing "NREPL.lock without a port"
    (let [[dir _] (temp-app "not a port\n") e (make dir) before (lock-state dir)]
      (try
        (is (= {:status :notice :text cljd/bad-port-file-message} (first (run e "1" {:run? false}))))
        (is (= before (lock-state dir)))
        (finally (delete-tree! dir)))))
  (testing "a port nothing answers: the refused notice, after the connecting line"
    (let [dead (doto (fake/start!) fake/stop!)
          [dir _] (temp-app (str (:port dead) "\n")) e (make dir) before (lock-state dir)
          [r out] (run e "1" {:run? false})]
      (try
        (is (= {:status :notice :text (cljd/refused-message (:port dead))} r))
        (is (= [(str (cljd/connecting-message (:port dead)) "\n")] (texts out :system)))
        (is (= before (lock-state dir)) "the stale file is never deleted or rewritten")
        (finally (delete-tree! dir)))))
  (testing "a server without eval and load-file: not ClojureDart, and no code is sent"
    (with-app {:ops ["describe" "clone"]}
      (fn [srv dir _]
        (let [e (make dir)]
          (is (= {:status :notice :text (cljd/not-cljd-message (:port srv))} (first (run e "1" {:run? false}))))
          (is (= ["describe"] (fake/ops-seen srv)))
          (is (not (attach/connected? e)))))))
  (testing "a listener that never answers describe: not ClojureDart, bounded by the describe timeout"
    (with-app {:mute? true}
      (fn [srv dir _]
        (is (= {:status :notice :text (cljd/not-cljd-message (:port srv))} (first (run (make dir) "1" {:run? false}))))))))

(deftest load-run-test
  (with-app {}
    (fn [srv dir file]
      (let [e (make dir) seen (atom []) before (lock-state dir)]
        (attach/on-connection! e (fn [c] (swap! seen conj c)))
        (let [[r out] (run e (str drive-text "(where)\n") {:run? true :path file})]
          (testing "quiet: one summary line, not a value per form"
            (is (= {:status :notice :text "; Loaded demo.drive (4 forms)"} r)))
          (is (= [fake/banner] (texts out :out)) "the banner, once")
          (is (str/starts-with? (first (texts out :system)) "; Connecting to the app on port ")))
        (testing "load-file carries the buffer's text (unsaved edits count), the absolute path and the name"
          (let [req (last (fake/requests srv))]
            (is (= ["load-file" (str drive-text "(where)\n") file "drive.cljd"]
                   [(get req "op") (get req "file") (get req "file-path") (get req "file-name")]))))
        (is (= 'demo.drive (ev/prompt-ns e)) "the session ends in the file's namespace")
        (is (attach/connected? e))
        (is (= [true] @seen))
        (testing "a second Run reuses the session: no second clone, no second banner"
          (let [[r out] (run e drive-text {:run? true :path file})]
            (is (= "; Loaded demo.drive (3 forms)" (:text r)))
            (is (empty? (texts out :out)))
            (is (= 1 (count (filter #{"clone"} (fake/ops-seen srv)))))))
        (is (= before (lock-state dir)))))))

(deftest load-stops-and-untitled-test
  (with-app {:fail-on "boom"}
    (fn [srv dir file]
      (let [e (make dir)
            [r out] (run e "(ns demo.drive)\n(defn a [])\n(defn b [])\n(boom)\n(defn c [])" {:run? true :path file})]
        (is (= {:status :error :error-text "Load stopped at form 3 of demo.drive"} r))
        (is (= [fake/fail-err] (texts out :err)) "the err is shown as it arrives")
        (testing "an untitled buffer: refused before anything is sent"
          (let [n (count (fake/ops-seen srv))]
            (is (= {:status :error :error-text cljd/save-first-message} (first (run e drive-text {:run? true :path nil}))))
            (is (= n (count (fake/ops-seen srv))))))))))

(deftest namespace-switch-test
  (with-app {}
    (fn [srv dir file]
      (let [e (make dir)
            codes (fn [] (keep (fn [m] (get m "code")) (fake/requests srv)))]
        (run e drive-text {:run? true :path file})
        (testing "the session is already in the buffer's namespace: only the selection goes"
          (is (= :ok (:status (first (run e "(where)" {:run? false :ns-source drive-text})))))
          (is (= [cljd/probe-code "(where)"] (codes))))
        (testing "another buffer's namespace: its ns form first, then the selection"
          (run e "(g)" {:run? false :ns-source "(ns other.place)\n(defn g [] 2)"})
          (is (= [cljd/probe-code "(where)" "(ns other.place)" "(g)"] (codes)))
          (is (= 'other.place (ev/prompt-ns e))))
        (testing "a buffer with no ns form evaluates in cljd.user"
          (run e "(+ 1 2)" {:run? false :ns-source "(+ 1 2)"})
          (is (= ["(ns cljd.user)" "(+ 1 2)"] (take-last 2 (codes)))))
        (testing "a REPL entry (no :ns-source) stays where the session is"
          (run e "(h)" {:run? false})
          (is (= "(h)" (last (codes))))
          (is (= 'cljd.user (ev/prompt-ns e))))
        (is (= ["(where)" "(g)" "(+ 1 2)" "(h)"] (:items @(ev/history e)))
            "every eval is REPL history; the ns switches aren't")))))

(deftest wake-up-probe-test
  (let [codes (fn [srv] (vec (keep (fn [m] (get m "code")) (fake/requests srv))))]
    (testing "a fresh session's first request is Jonquil's own probe; the banner shows once, the probe's value never"
      (with-app {:answers {"(where)" "{:slide 21, :of 54}"}}
        (fn [srv dir _]
          (let [[r out] (run (make dir) "(where)" {:run? false})]
            (is (= {:status :ok :printed "{:slide 21, :of 54}" :ns 'cljd.user} r))
            (is (= [fake/banner] (texts out :out)))
            (is (= ["describe" "clone" "eval" "eval"] (fake/ops-seen srv)))
            (is (= [cljd/probe-code "(where)"] (codes srv)))))))
    (testing "a probe answered nil is sent once more; the user's code still goes once"
      (with-app {:asleep-for 1}
        (fn [srv dir _]
          (is (= :ok (:status (first (run (make dir) "(where)" {:run? false})))))
          (is (= [cljd/probe-code cljd/probe-code "(where)"] (codes srv))))))
    (testing "two nil probes: say so, and never send the user's code; the next Run probes again"
      (with-app {:asleep-for 2}
        (fn [srv dir file]
          (let [e (make dir)]
            (is (= {:status :notice :text cljd/not-awake-message} (first (run e drive-text {:run? true :path file}))))
            (is (= [cljd/probe-code cljd/probe-code] (codes srv)))
            (is (not-any? #{"load-file"} (fake/ops-seen srv)))
            (is (= :notice (:status (first (run e drive-text {:run? true :path file})))))
            (is (= [cljd/probe-code cljd/probe-code cljd/probe-code] (codes srv)) "same session, still fresh")
            (is (= 1 (count (filter #{"load-file"} (fake/ops-seen srv)))) "sent once the probe answered")))))
    (testing "a user form that returns nil is sent exactly once, fresh session or not"
      (with-app {:answers {"(next!)" "nil"}}
        (fn [srv dir _]
          (let [e (make dir)]
            (is (= "nil" (:printed (first (run e "(next!)" {:run? false})))))
            (is (= "nil" (:printed (first (run e "(next!)" {:run? false})))))
            (is (= [cljd/probe-code "(next!)" "(next!)"] (codes srv)))))))
    (testing "a println's out (fork 8aaa4628: out before value) is shown, and the form runs once"
      (with-app {:prints {"println" ":side-effect\n"} :answers {"(println :side-effect)" "nil"}}
        (fn [srv dir _]
          (let [[r out] (run (make dir) "(println :side-effect)" {:run? false})]
            (is (= "nil" (:printed r)))
            (is (= [fake/banner ":side-effect\n"] (texts out :out)))
            (is (= [cljd/probe-code "(println :side-effect)"] (codes srv)))))))))

(deftest still-waiting-test
  (with-app {:slow {"(slow)" 500}}
    (fn [_ dir _]
      (let [[r out] (run (make dir) "(slow)" {:run? false})]
        (is (= :ok (:status r)) "no client-side timeout: the answer still lands")
        (is (= 1 (count (filter #{(str cljd/still-waiting-message "\n")} (texts out :system)))))))))

(deftest stop-detaches-test
  (with-app {:slow {"(slow)" 3000}}
    (fn [srv dir _]
      (let [e (make dir) seen (atom []) done (promise) out (atom [])
            before (lock-state dir)]
        (attach/on-connection! e (fn [c] (swap! seen conj c)))
        (run e "1" {:run? false})
        (ev/eval! e "(slow)" {:run? false :on-output (fn [k s] (swap! out conj [k s]))
                              :on-complete (fn [r] (deliver done r))})
        (loop [n 0] (when (and (< n 250) (not (some #{"(slow)"} (keep (fn [m] (get m "code")) (fake/requests srv)))))
                      (Thread/sleep 20) (recur (inc n))))
        (ev/interrupt! e)
        (is (= {:status :interrupted} (deref done 1000 :timeout)) "Stop stops waiting at once")
        (is (= [(str cljd/detached-message "\n")] (texts @out :system)))
        (is (not (attach/connected? e)))
        (is (= [true false] @seen))
        (is (not (ev/evaluating? e)))
        (testing "the next evaluation reconnects with a fresh clone"
          (let [[r out2] (run e "(+ 1 2)" {:run? false})]
            (is (= :ok (:status r)))
            (is (= [fake/banner] (texts out2 :out)))
            (is (= 2 (count (filter #{"clone"} (fake/ops-seen srv)))))))
        (testing "stop! closes the socket and sends nothing"
          (ev/stop! e)
          (is (not (attach/connected? e))))
        (Thread/sleep 100)
        (is (not-any? #{"interrupt" "close"} (fake/ops-seen srv)) "never interrupt, never close")
        (is (= before (lock-state dir)))))))

(deftest dropped-connection-test
  (with-app {:drop-on "(quit-app)"}
    (fn [srv dir _]
      (let [e (make dir) seen (atom [])]
        (attach/on-connection! e (fn [c] (swap! seen conj c)))
        (is (= {:status :error :error-text cljd/lost-connection} (first (run e "(quit-app)" {:run? false}))))
        (is (not (attach/connected? e)))
        (is (= [true false] @seen))
        (is (= :ok (:status (first (run e "(+ 1 2)" {:run? false})))) "the next Run reconnects")
        (is (= 2 (count (filter #{"clone"} (fake/ops-seen srv)))))))))

(deftest busy-test
  (with-app {:slow {"(slow)" 400}}
    (fn [_ dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(slow)" {:run? false :on-complete (fn [r] (deliver done r))})
        (is (= attach/busy-warning (ev/eval! e "1" {:run? false})))
        (is (ev/evaluating? e))
        (is (= :ok (:status (deref done 5000 :timeout))))))))

(deftest stop-during-connect-test
  ;; describe answers 400ms late (well inside make's 500ms describe
  ;; timeout), so Stop lands mid-connect! while describe is still
  ;; pending, at a point where the connect (a full-ops server) would
  ;; otherwise succeed: op-set will carry eval and load-file, and clone
  ;; would answer fine.
  ;;
  ;; Fix round 3, finding 2: a fixed (Thread/sleep 100) here can overrun
  ;; the 400ms describe delay when a full suite is loading the machine,
  ;; so connect! finishes (describe's own stop-check sees nothing yet)
  ;; before Stop ever lands. Polling fake/ops-seen for "describe" (the
  ;; same technique stop-during-wake-probe-test uses for the probe's own
  ;; code) waits for exactly the moment connect! is waiting on it,
  ;; regardless of how long that takes to arrive.
  (with-app {:slow-ops {"describe" 400}}
    (fn [srv dir _]
      (let [e (make dir) seen (atom []) done (promise)
            clone-count (fn [] (count (filter #{"clone"} (fake/ops-seen srv))))]
        (attach/on-connection! e (fn [c] (swap! seen conj c)))
        (ev/eval! e "1" {:run? false :on-complete (fn [r] (deliver done r))})
        (loop [n 0] (when (and (< n 250) (not (some #{"describe"} (fake/ops-seen srv))))
                      (Thread/sleep 20) (recur (inc n))))
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 2000 :timeout)))
            "Stop during connect! (waiting on describe) ends the evaluation at once")
        (is (not (attach/connected? e))
            "the connection connect! was building was never published")
        (is (= [] @seen) "never notified: nothing was ever published to notify about")
        (let [clones-before (clone-count)]
          (is (= :ok (:status (first (run e "(+ 1 2)" {:run? false})))) "the next Run reconnects for real")
          (is (= (inc clones-before) (clone-count))
              "a fresh clone: the interrupted attempt left no stale connection to reuse"))))))

(deftest idle-stop-detaches-test
  (with-app {}
    (fn [srv dir _]
      (let [e (make dir) seen (atom [])]
        (attach/on-connection! e (fn [c] (swap! seen conj c)))
        (is (= :ok (:status (first (run e "1" {:run? false})))) "establish a connection")
        (is (attach/connected? e))
        (ev/interrupt! e)
        (is (not (attach/connected? e)) "an idle Stop on a still-connected session detaches too")
        (is (= [true false] @seen))
        (is (not-any? #{"interrupt" "close"} (fake/ops-seen srv)) "never interrupt, never close")))))

(deftest stale-reply-ignored-test
  ;; A message that arrives on a connection attach.clj has already moved
  ;; away from (a detach racing an already-buffered reply) must not
  ;; mutate the next session's :session-ns or reach a finished
  ;; evaluation's console. request! is private; deref'd via its Var
  ;; below to drive the race deterministically: the fake's :slow holds
  ;; the reply back 300ms, giving a huge margin over the 60ms wait before
  ;; the state is swapped out from under the in-flight call.
  (with-app {:slow {"(slow-form)" 300} :prints {"(slow-form)" "leak\n"}}
    (fn [srv dir _]
      (let [conn (client/connect (:port srv))
            sid (:new-session (first (client/request-sync conn {:op "clone"} 2000)))
            e (make dir)
            out (atom [])]
        (reset! (:state e) {:running nil :conn conn :session sid :ops #{"eval" "load-file"}
                            :session-ns "cljd.user" :fresh? false})
        (let [fut (future (#'attach/request! e {:op "eval" :code "(slow-form)"}
                                             (fn [k s] (swap! out conj [k s]))))]
          (Thread/sleep 60)
          (swap! (:state e) assoc :conn ::a-different-connection :session-ns "other.ns")
          @fut
          (is (= "other.ns" (:session-ns @(:state e)))
              "the stale reply's :ns \"cljd.user\" never overwrote the newer session's namespace")
          (is (not (some #{[:out "leak\n"]} @out))
              "the stale reply's :out never reached this finished evaluation's console"))
        (client/close! conn)))))

(deftest load-error-survives-stop-test
  ;; Fix round 2, finding A: finish! used to squash EVERY non-:interrupted
  ;; result to :interrupted whenever stop-requested? was true, which
  ;; masked load-result's own "Load stopped at form N" diagnostic end to
  ;; end. :stop-requested? is set directly on state, not through
  ;; ev/interrupt! (which always detaches too, closing the socket and
  ;; injecting a disconnected status into this SAME in-flight load-file
  ;; request -- a different, already-correct case: load-result already
  ;; reads a genuine disconnect+Stop as :interrupted). Setting the flag
  ;; alone, while the socket stays open, isolates finish!'s own decision:
  ;; the load keeps running for real and fails on (boom) with its own
  ;; eval-error, never a disconnect, so the diagnostic is what must
  ;; survive. The 300ms :slow delay on an earlier form is a wide,
  ;; deterministic margin over the 100ms wait before the flag is set.
  (with-app {:fail-on "boom" :slow {"(slow)" 300}}
    (fn [_ dir file]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(ns demo.drive)\n(defn a [])\n(slow)\n(boom)\n(defn c [])"
                  {:run? true :path file :on-complete (fn [r] (deliver done r))})
        (Thread/sleep 100)
        (swap! (:state e) assoc-in [:running :stop-requested?] true)
        (is (= {:status :error :error-text "Load stopped at form 3 of demo.drive"}
               (deref done 2000 :timeout))
            "the load's own diagnostic reaches on-complete even though Stop was requested mid-load")))))

(deftest stop-during-wake-probe-test
  ;; Stop pressed while the fresh-session wake probe is in flight still
  ;; reads as :interrupted -- a regression guard for wake!'s own new
  ;; stop-check (finding A), now that finish! no longer squashes this
  ;; for it.
  (with-app {:slow {cljd/probe-code 300}}
    (fn [srv dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "1" {:run? false :on-complete (fn [r] (deliver done r))})
        (loop [n 0] (when (and (< n 250) (not (some #{cljd/probe-code} (keep (fn [m] (get m "code")) (fake/requests srv)))))
                      (Thread/sleep 20) (recur (inc n))))
        (ev/interrupt! e)
        (is (= {:status :interrupted} (deref done 2000 :timeout))
            "Stop during the wake probe still ends the evaluation as :interrupted")))))

(deftest stop-during-asleep-probe-test
  ;; Fix round 3, finding 1: wake!'s stop-check only covered #{:lost
  ;; :failed}, so a Stop that lands while BOTH probes answer asleep (r
  ;; stays :asleep after the retry is exhausted) fell through to the
  ;; :notice not-awake-message instead of :interrupted -- reachable
  ;; whenever the app's genuine nil reply beats a detach's synthetic
  ;; disconnect (round 2's finding A note: cljd's own connect skill
  ;; already documents a hot-restart REPL answering nil).
  ;;
  ;; :stop-requested? is set directly on state, not through
  ;; ev/interrupt! (which always detaches too, injecting a disconnected
  ;; status that would turn this into the already-correct :lost case
  ;; instead). :answers makes every probe answer "nil" (the fake's
  ;; :asleep-for path doesn't consult :slow at all, so it can't be
  ;; delayed the same way), and :slow gives each of the two probes its
  ;; own 400ms wait. Polling fake/requests for the first probe's own
  ;; code (the same technique stop-during-wake-probe-test uses) before
  ;; setting the flag guarantees connect! has already finished, so the
  ;; flag can only be read by wake!'s own check, never connect!'s
  ;; (fixed in round 2, finding 7) -- a fixed sleep here raced exactly
  ;; that and was measured to pass for the wrong reason under load.
  ;;
  ;; Measured (isolating this file's own tests, not a full-suite
  ;; probe): stop-during-wake-probe-test's own connection, immediately
  ;; before this test, keeps its fake server's per-connection future
  ;; alive up to its own :slow duration (300ms) after ev/interrupt!
  ;; already unblocked that test's client side -- interrupt! only
  ;; detaches the client; the fake's Thread/sleep keeps running
  ;; server-side until it wakes and fails to write to the now-closed
  ;; socket. Starting this test's own connection while that lingering
  ;; future is still alive was measured to shorten THIS test's probe
  ;; wait to under 300ms (a premature :lost, already correctly handled
  ;; before this fix, so the test passed without ever exercising the
  ;; :asleep fallthrough this finding is about). The settle wait below
  ;; is comfortably over that 300ms.
  (with-app {:answers {cljd/probe-code "nil"} :slow {cljd/probe-code 400}}
    (fn [srv dir _]
      (Thread/sleep 400)
      (let [e (make dir) done (promise)]
        (ev/eval! e "1" {:run? false :on-complete (fn [r] (deliver done r))})
        (loop [n 0] (when (and (< n 250) (not (some #{cljd/probe-code} (keep (fn [m] (get m "code")) (fake/requests srv)))))
                      (Thread/sleep 20) (recur (inc n))))
        (swap! (:state e) assoc-in [:running :stop-requested?] true)
        (is (= {:status :interrupted} (deref done 2000 :timeout))
            "Stop landing during the asleep probes still ends the evaluation as :interrupted, not a not-awake notice")))))
