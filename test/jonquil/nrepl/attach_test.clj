(ns jonquil.nrepl.attach-test
  "The attach evaluator against dev/fake_cljd_nrepl.clj's fake fork server.
  Every test owns its temp project and its fake server, and stops both."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [fake-cljd-nrepl :as fake]
            [jonquil.cljd :as cljd]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.attach :as attach]
            [jonquil.nrepl.client :as client]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def drive-text
  "(ns demo.drive\n  (:require [cljd.string :as str]))\n\n(defn where [] 1)\n\n(comment\n  (where))\n\n(defonce state (atom nil))\n")

(defn- temp-app
  "A throwaway app directory: pubspec.yaml, dev/demo/drive.cljd, and an
  NREPL.lock holding port-text (none when nil). [dir file]."
  [port-text]
  (let [d (io/file (temp-dirs/temp-dir! "jonquil-attach-test-"))
        f (io/file d "dev/demo/drive.cljd")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "pubspec.yaml") "name: demo\n")
    (spit f drive-text)
    (when port-text (spit (io/file d "NREPL.lock") port-text))
    [(.getCanonicalPath d) (.getCanonicalPath f)]))

(defn- lock-state
  "NREPL.lock's bytes and mtime, and whether a REPL.lock exists: the files
  Jonquil must never write, delete or create."
  [dir]
  (let [f (io/file dir "NREPL.lock")]
    [(when (.exists f) (slurp f)) (.lastModified f) (.exists (io/file dir "REPL.lock"))]))

(defn- make [dir]
  (attach/make-attach {:profile dialect/cljd :runtime :attach :project-dir dir}
                      {:describe-timeout-ms 500 :clone-timeout-ms 500 :still-waiting-ms 200 :help-timeout-ms 200}))

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
         (finally (fake/stop! srv) (temp-dirs/delete-tree! dir)))))

(deftest make-dispatches-on-runtime-test
  (let [e (ev/make {:profile dialect/cljd :runtime :attach :project-dir "/nowhere"})]
    (is (instance? jonquil.nrepl.attach.Attach e))
    (is (not (attach/connected? e)) "nothing connects until an evaluation needs it")
    (is (nil? (ev/start! e)))
    (is (= 'cljd.user (ev/prompt-ns e)))
    (is (= 5 (count (ev/limitations e))))
    (testing "println output comes back (fork 8aaa4628); it's lost only when the form throws"
      (is (some #(str/includes? % "println") (ev/limitations e)))
      (is (not-any? #(str/includes? % "doesn't come back") (ev/limitations e))))))

(deftest connect-rules-test
  (testing "no NREPL.lock: the not-running notice, and nothing written"
    (let [[dir file] (temp-app nil) e (make dir)]
      (try
        (is (= {:status :notice :text cljd/not-running-message} (first (run e "(+ 1 2)" {:run? false}))))
        (is (= {:status :notice :text cljd/not-running-message} (first (run e drive-text {:run? true :path file}))))
        (is (not (attach/connected? e)))
        (is (= [nil 0 false] (lock-state dir)))
        (finally (temp-dirs/delete-tree! dir)))))
  (testing "no project at all"
    (let [e (attach/make-attach {:profile dialect/cljd :runtime :attach :project-dir nil})]
      (is (= {:status :notice :text cljd/no-project-message} (first (run e "1" {:run? false}))))))
  (testing "NREPL.lock without a port"
    (let [[dir _] (temp-app "not a port\n") e (make dir) before (lock-state dir)]
      (try
        (is (= {:status :notice :text cljd/bad-port-file-message} (first (run e "1" {:run? false}))))
        (is (= before (lock-state dir)))
        (finally (temp-dirs/delete-tree! dir)))))
  (testing "a port nothing answers: the refused notice, after the connecting line"
    (let [dead (doto (fake/start!) fake/stop!)
          [dir _] (temp-app (str (:port dead) "\n")) e (make dir) before (lock-state dir)
          [r out] (run e "1" {:run? false})]
      (try
        (is (= {:status :notice :text (cljd/refused-message (:port dead))} r))
        (is (= [(str (cljd/connecting-message (:port dead)) "\n")] (texts out :system)))
        (is (= before (lock-state dir)) "the stale file is never deleted or rewritten")
        (finally (temp-dirs/delete-tree! dir)))))
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
        (is (= {:status :notice :text (cljd/not-cljd-message (:port srv))} (first (run (make dir) "1" {:run? false})))))))
  (testing "the app quits mid-connect (describe answers disconnected): the refused sentence, not not-cljd"
    (with-app {:slow-ops {"describe" 300}}
      (fn [srv dir _]
        (let [e (make dir)]
          (future
            (loop [n 0] (when (and (< n 250) (not (some #{"describe"} (fake/ops-seen srv))))
                          (Thread/sleep 20) (recur (inc n))))
            (fake/drop-connections! srv))
          (is (= {:status :notice :text (cljd/refused-message (:port srv))}
                 (first (run e "1" {:run? false}))))
          (is (not (attach/connected? e))))))))

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
  ;; the 200ms still-waiting-ms threshold (make) also covers the
  ;; fresh-session wake probe's own request, so under load a slow connect
  ;; can print the line once for the probe and once for "(slow)" itself.
  ;; Connecting and waking first, off the clock, means the timed run below
  ;; only ever times the "(slow)" eval.
  (with-app {:slow {"(slow)" 500}}
    (fn [_ dir _]
      (let [e (make dir)]
        (run e "1" {:run? false})
        (let [[r out] (run e "(slow)" {:run? false})]
          (is (= :ok (:status r)) "no client-side timeout: the answer still lands")
          (is (= 1 (count (filter #{(str cljd/still-waiting-message "\n")} (texts out :system))))))))))

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

;; offline completion and lookup-backed quick doc.

(deftest complete-is-offline-test
  (with-app {}
    (fn [srv dir _]
      (let [e (make dir)
            cands (ev/complete e "wh" drive-text 0 nil)]
        (is (= "where" (:symbol (first cands))) "the buffer's own name first")
        (is (some #{"when"} (map :symbol cands)))
        (is (some #{"str/"} (map :symbol (ev/complete e "st" drive-text 0 nil))) "an alias from the ns form")
        (is (empty? (fake/ops-seen srv)) "completion never talks to the app, or connects")))))

(def ^:private where-info
  {"ns" "demo.drive" "name" "where" "doc" "Where the deck is now." "arglists" "([])" "arglists-str" "([])"})

(deftest lookup-test
  (testing "detached: quick doc never connects on its own"
    (with-app {:docs {"where" where-info}}
      (fn [srv dir _]
        (let [e (make dir)]
          (is (= (cljd/no-docs "where" cljd/not-attached-doc) (ev/lookup e "where" drive-text 0)))
          (is (empty? (fake/ops-seen srv)))))))
  (testing "attached, fork 685e56de: found in the buffer's namespace, or no-info"
    (with-app {:docs {"where" where-info}}
      (fn [srv dir _]
        (let [e (make dir)]
          (run e "1" {:run? false})
          (is (= {:status :found :symbol "where" :ns "demo.drive" :name "where" :arglists "([])"
                  :doc "Where the deck is now."}
                 (ev/lookup e "where" drive-text 0)))
          (is (= "demo.drive" (get (last (fake/requests srv)) "ns")) "the buffer's ns, not the session's")
          (is (= (cljd/no-docs "nope" "No docs for nope") (ev/lookup e "nope" drive-text 0)))
          (is (= "cljd.user" (do (ev/lookup e "where" "(where)" 0) (get (last (fake/requests srv)) "ns")))
              "text with no ns form: the session's namespace")))))
  (testing "attached, the older fork 573781a2 (lookup-error): the older-fork sentence"
    (with-app {}
      (fn [_ dir _]
        (let [e (make dir)]
          (run e "1" {:run? false})
          (is (= (cljd/no-docs "map" cljd/no-lookup-doc) (ev/lookup e "map" drive-text 0)))))))
  (testing "a server that doesn't advertise lookup: the same sentence, and nothing is sent"
    (with-app {:ops ["clone" "describe" "eval" "load-file"]}
      (fn [srv dir _]
        (let [e (make dir)]
          (run e "1" {:run? false})
          (is (= (cljd/no-docs "map" cljd/no-lookup-doc) (ev/lookup e "map" drive-text 0)))
          (is (not-any? #{"lookup"} (fake/ops-seen srv)))))))
  (testing "an app too busy to answer in time: bounded, and says so"
    (with-app {:docs {"where" where-info} :lookup-ms 1000}
      (fn [_ dir _]
        (let [e (make dir)
              _ (run e "1" {:run? false})
              t0 (System/currentTimeMillis)
              r (ev/lookup e "where" drive-text 0)]
          (is (= (cljd/no-docs "where" cljd/lookup-timeout-doc) r))
          (is (< (- (System/currentTimeMillis) t0) 900) "the help timeout, not the app's answer"))))))

(deftest stop-during-connect-test
  ;; describe answers 400ms late (well inside make's 500ms describe
  ;; timeout), so Stop lands mid-connect! while describe is still
  ;; pending, at a point where the connect (a full-ops server) would
  ;; otherwise succeed: op-set will carry eval and load-file, and clone
  ;; would answer fine.
  ;;
  ;; A fixed (Thread/sleep 100) here can overrun
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
              "a fresh clone: the interrupted attempt left no stale connection to reuse")))))
  (testing "Stop closes the in-progress connection at once, not after describe's own (slow) reply"
    ;; describe-timeout-ms is deliberately large here (10000, well past the
    ;; server's own 3000ms slow-ops delay), so client/request-sync's own
    ;; internal bound can't be what ends this -- only connect! publishing
    ;; the raw conn as :connecting, for detach! to close, can.
    (with-app {:slow-ops {"describe" 3000}}
      (fn [srv dir _]
        (let [e (attach/make-attach {:profile dialect/cljd :runtime :attach :project-dir dir}
                                    {:describe-timeout-ms 10000 :clone-timeout-ms 500
                                     :still-waiting-ms 200 :help-timeout-ms 200})
              done (promise)]
          (ev/eval! e "1" {:run? false :on-complete (fn [r] (deliver done r))})
          (loop [n 0] (when (and (< n 250) (not (some #{"describe"} (fake/ops-seen srv))))
                        (Thread/sleep 20) (recur (inc n))))
          (let [t0 (System/currentTimeMillis)]
            (ev/interrupt! e)
            (is (= :interrupted (:status (deref done 2000 :timeout))))
            (is (< (- (System/currentTimeMillis) t0) 1000)
                "finishes within about 1s of the Stop, not describe's own 3000ms reply")))))))

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
  ;; finish! must not squash a non-:interrupted result to :interrupted just
  ;; because stop-requested? is true; that would mask load-result's own "Load stopped at form N" diagnostic end to
  ;; end. :stop-requested? is set directly on state, not through
  ;; ev/interrupt! (which always detaches too, closing the socket and
  ;; injecting a disconnected status into this SAME in-flight load-file
  ;; request -- a different, already-correct case: load-result already
  ;; reads a genuine disconnect+Stop as :interrupted). Setting the flag
  ;; alone, while the socket stays open, isolates finish!'s own decision:
  ;; the load keeps running for real and fails on (boom) with its own
  ;; eval-error, never a disconnect, so the diagnostic is what must
  ;; survive. A fixed 100ms wait before setting the flag can land before
  ;; the load-file request has even been sent under load, so this polls
  ;; fake/requests for "load-file" instead (the same technique
  ;; stop-during-wake-probe-test uses for the probe's own code).
  (with-app {:fail-on "boom" :slow {"(slow)" 300}}
    (fn [srv dir file]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(ns demo.drive)\n(defn a [])\n(slow)\n(boom)\n(defn c [])"
                  {:run? true :path file :on-complete (fn [r] (deliver done r))})
        (loop [n 0] (when (and (< n 250) (not (some #{"load-file"} (fake/ops-seen srv))))
                      (Thread/sleep 20) (recur (inc n))))
        (swap! (:state e) assoc-in [:running :stop-requested?] true)
        (is (= {:status :error :error-text "Load stopped at form 3 of demo.drive"}
               (deref done 2000 :timeout))
            "the load's own diagnostic reaches on-complete even though Stop was requested mid-load")))))

(deftest stop-during-wake-probe-test
  ;; Stop pressed while the fresh-session wake probe is in flight still
  ;; reads as :interrupted -- a regression guard for wake!'s own new
  ;; stop-check, now that finish! no longer squashes this
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

(deftest stop-before-ns-switch-test
  ;; a Stop that lands between the wake probe and the next request
  ;; (here, the ns-switch eval Run Selection sends first) used to crash
  ;; instead of ending as :interrupted: request! was called after detach!
  ;; had already set :conn nil, so client/request! swapped on (:counter
  ;; nil) -- "Error: nil where a clojure.lang.IAtom is required". with-redefs
  ;; on cljd/probe-result calls ev/interrupt! (which detaches) and then
  ;; delegates to the real probe-result, landing Stop at exactly that gap.
  (with-app {}
    (fn [srv dir _]
      (let [e (make dir) orig cljd/probe-result]
        (with-redefs [cljd/probe-result (fn [msgs] (ev/interrupt! e) (orig msgs))]
          (let [[r _out] (run e "(where)" {:run? false :ns-source "(ns other.place)\n(defn g [] 2)"})]
            (is (= {:status :interrupted} r))
            (is (not-any? #{"(ns other.place)" "(where)"}
                          (keep (fn [m] (get m "code")) (fake/requests srv)))
                "no user code, and no ns-switch, is ever sent")))))))

(deftest stale-publish-race-test
  ;; Stop can land in the gap between connect!'s (stop-requested? ev)
  ;; check (guarding whether the fresh clone gets published) and the
  ;; swap! that actually publishes it. with-redefs on the private
  ;; stop-requested? makes the race deterministic: its SECOND call (the
  ;; one guarding the publish) sets the real flag directly on state, as a
  ;; genuine Stop landing right there would, but still answers false
  ;; itself -- so connect! reaches the publish believing Stop hasn't
  ;; landed, with the real flag already set underneath it. The fix reads
  ;; the flag from the state map inside the same swap!, not through this
  ;; (now-stale) function result, so it still catches the race.
  (with-app {}
    (fn [_srv dir _]
      (let [e (make dir) calls (atom 0)]
        (with-redefs [attach/stop-requested? (fn [ev]
                                               (let [n (swap! calls inc)]
                                                 (when (= 2 n)
                                                   (swap! (:state ev) assoc-in [:running :stop-requested?] true))
                                                 false))]
          (let [[r _out] (run e "1" {:run? false})]
            (is (= {:status :interrupted} r))))
        (is (not (attach/connected? e)) "the connection connect! was building was never published")))))

(deftest stop-during-asleep-probe-test
  ;; wake!'s stop-check must cover more than #{:lost :failed}: a Stop that
  ;; lands while BOTH probes answer asleep (r stays :asleep after the
  ;; retry is exhausted) must give :interrupted, not fall through to the
  ;; :notice not-awake-message -- reachable
  ;; whenever the app's genuine nil reply beats a detach's synthetic
  ;; disconnect (cljd's own connect skill
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
  ;; -- a fixed sleep here raced exactly
  ;; that and was measured to pass for the wrong reason under load.
  ;;
  ;; this test used to need a 400ms settle here, because
  ;; stop-during-wake-probe-test's own fake server (immediately before this
  ;; one) left its per-connection thread alive up to its own :slow duration
  ;; after ev/interrupt! had already unblocked that test's client side --
  ;; interrupt! only detaches the client; the fake's own Thread/sleep kept
  ;; running server-side regardless, until it woke and failed to write to
  ;; the now-closed socket. fake/stop! now wakes every sleeper at once and
  ;; waits for every connection thread to actually end before it returns,
  ;; so the previous test's server is fully quiet by the time this one's
  ;; with-app starts a new one: no settle needed.
  (with-app {:answers {cljd/probe-code "nil"} :slow {cljd/probe-code 400}}
    (fn [srv dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "1" {:run? false :on-complete (fn [r] (deliver done r))})
        (loop [n 0] (when (and (< n 250) (not (some #{cljd/probe-code} (keep (fn [m] (get m "code")) (fake/requests srv)))))
                      (Thread/sleep 20) (recur (inc n))))
        (swap! (:state e) assoc-in [:running :stop-requested?] true)
        (is (= {:status :interrupted} (deref done 2000 :timeout))
            "Stop landing during the asleep probes still ends the evaluation as :interrupted, not a not-awake notice")))))
