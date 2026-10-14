(ns jonquil.nrepl.jvm-test
  "The JVM attach evaluator against dev/fake_jvm_nrepl.clj's fake server.
  Every test owns its temp project and its fake server, and stops both."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [fake-jvm-nrepl :as fake]
            [jonquil.clj :as clj]
            [jonquil.cljd :as cljd]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.inspect :as inspect]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.jvm :as jvm]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def fixture-core-text
  ;; The spike fixture's exact text (its own gitignored planning-store
  ;; file): demo.core with greet, a private secret returning :private-ok,
  ;; and nested. Embedded rather than read at test time: the planning
  ;; store is gitignored scratch, so tests can't depend on it.
  (str "(ns demo.core\n"
       "  \"A fixture namespace for the clj-attach spike.\")\n"
       "\n"
       "(defn greet\n"
       "  \"Says hello to someone.\"\n"
       "  [who]\n"
       "  (str \"hello \" who))\n"
       "\n"
       "(defn- secret [] :private-ok)\n"
       "\n"
       "(def nested {:a [1 2 {:b \"héllo 😀\"}] :c #{:x :y} :d (range 30)})\n"))

(defn- temp-project
  "A throwaway JVM project: deps.edn, .jonquil.edn {:dialect :clj},
  src/demo/core.clj (the spike fixture's text), and .nrepl-port holding
  port-text with no trailing newline (nil for none). [dir file]."
  [port-text]
  (let [d (io/file (temp-dirs/temp-dir! "jonquil-jvm-test-"))
        f (io/file d "src/demo/core.clj")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "deps.edn") "{:deps {}}\n")
    (spit (io/file d ".jonquil.edn") "{:dialect :clj}\n")
    (spit f fixture-core-text)
    (when port-text (spit (io/file d ".nrepl-port") port-text))
    [(.getCanonicalPath d) (.getCanonicalPath f)]))

(defn- lock-state
  ".nrepl-port's bytes and mtime: the file Jonquil must never write or
  delete."
  [dir]
  (let [f (io/file dir ".nrepl-port")]
    [(when (.exists f) (slurp f)) (.lastModified f)]))

(defn- make [dir]
  (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir dir}
                {:describe-timeout-ms 500 :clone-timeout-ms 500 :interrupt-timeout-ms 200
                 :help-timeout-ms 200 :full-doc-timeout-ms 500}))

(defn- run
  "Evaluates and waits. {:result result :out output :waits on-wait-input
  calls}."
  [e code opts]
  (let [done (promise) out (atom []) waits (atom [])]
    (ev/eval! e code (assoc opts
                            :on-output (fn [k s] (swap! out conj [k s]))
                            :on-wait-input (fn [w] (swap! waits conj w))
                            :on-complete (fn [r] (deliver done r))))
    {:result (deref done 10000 :timeout) :out @out :waits @waits}))

(defn- texts [out kind] (keep (fn [[k s]] (when (= k kind) s)) out))

(defn- eval-ns-values
  "Every eval request's own \"ns\" field, in order."
  [srv]
  (keep (fn [m] (get m "ns")) (filter #(= "eval" (get % "op")) (fake/requests srv))))

(defn- with-project
  "Runs (f srv dir file) against a fake server (opts) whose port is in the
  project's .nrepl-port, then stops the server and deletes the project."
  [opts f]
  (let [srv (fake/start! opts)
        [dir file] (temp-project (str (:port srv)))]
    (try (f srv dir file)
         (finally (fake/stop! srv) (temp-dirs/delete-tree! dir)))))

(defn- wait-until!
  "Polls (pred) every 20ms, up to ~5s, for a background worker to reach a
  point a test needs to synchronize on (a request landing at the fake, or
  waiting-for-input? becoming true)."
  [pred]
  (loop [n 0]
    (when (and (< n 250) (not (pred)))
      (Thread/sleep 20)
      (recur (inc n)))))

(deftest make-test
  (let [e (ev/make {:profile dialect/clj :runtime :attach :project-dir "/nowhere"})]
    (is (instance? jonquil.nrepl.jvm.Jvm e))
    (is (satisfies? connection/Attached e))
    (is (not (connection/connected? e)) "nothing connects until an evaluation needs it")
    (is (nil? (ev/start! e)))
    (is (= 'user (ev/prompt-ns e)))
    (is (= clj/limitations (ev/limitations e)))))

(deftest connect-keeps-version-test
  (doseq [[opts expected] [[{} [1 7]] [{:nrepl-version [1 8 0]} [1 8]] [{:nrepl-version :none} nil]]]
    (with-project opts
      (fn [_ dir _]
        (let [e (make dir)]
          (is (= :ok (:status (:result (run e "(+ 1 2)" {:run? false})))) "connects whatever describe says")
          (is (= expected (:nrepl-version @(:state e))))
          (is (false? (:eof-noted? @(:state e)))))))))

(deftest connect-rules-test
  (testing "no project at all: the no-project notice"
    (let [e (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir nil})]
      (is (= {:status :notice :text clj/no-project-message} (:result (run e "1" {:run? false}))))))
  (testing "no .nrepl-port: the not-running notice, and nothing written"
    (let [[dir _] (temp-project nil) e (make dir)]
      (try
        (is (= {:status :notice :text (clj/not-running-message dir)}
               (:result (run e "(+ 1 2)" {:run? false}))))
        (is (nil? (first (lock-state dir))))
        (finally (temp-dirs/delete-tree! dir)))))
  (testing ".nrepl-port without a port number: the bad-port-file notice"
    (let [[dir _] (temp-project "abc") e (make dir) before (lock-state dir)]
      (try
        (is (= {:status :notice :text clj/bad-port-file-message} (:result (run e "1" {:run? false}))))
        (is (= before (lock-state dir)))
        (finally (temp-dirs/delete-tree! dir)))))
  (testing "a port nothing answers: the refused notice, after the connecting line"
    (let [dead (doto (fake/start!) fake/stop!)
          [dir _] (temp-project (str (:port dead))) e (make dir) before (lock-state dir)
          {:keys [result out]} (run e "1" {:run? false})]
      (try
        (is (= {:status :notice :text (clj/refused-message (:port dead))} result))
        (is (= [(str (clj/connecting-message (:port dead)) "\n")] (texts out :system)))
        (is (= before (lock-state dir)) "the stale file is never deleted or rewritten")
        (finally (temp-dirs/delete-tree! dir)))))
  (testing "a server without eval and load-file: the not-server notice"
    (with-project {:ops ["describe" "clone"]}
      (fn [srv dir _]
        (let [e (make dir)]
          (is (= {:status :notice :text (clj/not-server-message (:port srv))}
                 (:result (run e "1" {:run? false}))))
          (is (not (connection/connected? e)))))))
  (testing "a server with only eval and load-file (no interrupt, no stdin): still connects, no identity check"
    (with-project {:ops ["describe" "clone" "eval" "load-file"]}
      (fn [_srv dir _]
        (let [e (make dir)]
          (is (= :ok (:status (:result (run e "1" {:run? false})))))
          (is (connection/connected? e))))))
  (testing "the connecting line is output once per connect; .nrepl-port's bytes and mtime never change"
    (with-project {}
      (fn [srv dir _]
        (let [e (make dir) before (lock-state dir)
              {:keys [out]} (run e "1" {:run? false})]
          (is (= [(str (clj/connecting-message (:port srv)) "\n")] (texts out :system)))
          (is (= before (lock-state dir))))))))

(deftest run-sets-prompt-test
  (with-project {}
    (fn [srv dir file]
      (let [e (make dir)
            {:keys [result]} (run e fixture-core-text {:run? true :path file})]
        (is (= {:status :ok :printed "#'demo.core/nested"} result))
        (let [req (last (fake/requests srv))]
          (is (= "load-file" (get req "op")))
          (is (= (.getCanonicalPath (io/file file)) (get req "file-path")))
          (is (= "core.clj" (get req "file-name"))))
        (is (= 'demo.core (ev/prompt-ns e)) "the prompt moves to the buffer's own namespace")))))

(deftest repl-line-ns-test
  (with-project {}
    (fn [srv dir file]
      (let [e (make dir)]
        (is (= :ok (:status (:result (run e "(+ 1 2)" {:run? false})))))
        (is (= "user" (last (eval-ns-values srv))) "before any Run, the prompt namespace is user")
        (run e fixture-core-text {:run? true :path file})
        (is (= :ok (:status (:result (run e "(+ 1 2)" {:run? false})))))
        (is (= "demo.core" (last (eval-ns-values srv))) "after Run, the prompt namespace is the buffer's own")
        (is (= :ok (:status (:result (run e "(in-ns 'other)" {:run? false})))))
        (is (= 'other (ev/prompt-ns e)) "a REPL entry's reply :ns moves the prompt")))))

(deftest selection-ns-test
  (with-project {}
    (fn [srv dir file]
      (let [e (make dir)]
        (run e fixture-core-text {:run? true :path file})
        (is (= :ok (:status (:result (run e "(greet \"x\")" {:run? false :ns-source fixture-core-text})))))
        (is (= "demo.core" (last (eval-ns-values srv))) "the selection is sent with the buffer's own namespace")
        (is (= 'demo.core (ev/prompt-ns e)) "the prompt is unchanged by a selection")))))

(deftest not-loaded-test
  (with-project {}
    (fn [srv dir _]
      (let [e (make dir)
            {:keys [result]} (run e "(secret)" {:run? false :ns-source "(ns demo.core)\n(secret)"})]
        (is (= {:status :notice :text (clj/not-loaded-message "demo.core")} result))
        (is (= ["nil" "(secret)"] (keep #(when (= "eval" (get % "op")) (get % "code")) (fake/requests srv)))
            "the connect-time registration's nil, then exactly one eval: the selection's own")
        (is (not-any? #(str/includes? (or (get % "code") "") "(ns ") (fake/requests srv))
            "the buffer's own ns form is never sent")))))

(deftest output-order-test
  (with-project {:prints {"(p)" "hi\n"}}
    (fn [_srv dir _]
      (let [e (make dir) counter (atom 0) out-at (atom nil) complete-at (atom nil) done (promise)]
        (ev/eval! e "(p)" {:run? false
                           :on-output (fn [k s] (when (and (= :out k) (= "hi\n" s))
                                                  (reset! out-at (swap! counter inc))))
                           :on-complete (fn [r] (reset! complete-at (swap! counter inc)) (deliver done r))})
        (is (= :ok (:status (deref done 10000 :timeout))))
        (is (some? @out-at) "the printed text arrived")
        (is (some? @complete-at) "on-complete fired")
        (is (< @out-at @complete-at) "on-output arrives before on-complete's :printed")))))

(deftest stop-interrupts-test
  (with-project {:slow {"(slow)" 5000}}
    (fn [srv dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(slow)" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (some #{"(slow)"} (keep #(get % "code") (fake/requests srv)))))
        (Thread/sleep 100)
        (let [expected-id (get-in @(:state e) [:running :id])
              start (System/nanoTime)]
          (is (= :interrupted (:status (do (ev/interrupt! e) (deref done 1000 :timeout)))))
          (is (< (/ (- (System/nanoTime) start) 1e6) 1000.0) "interrupted well under 1000 ms")
          (let [req (last (filter #(= "interrupt" (get % "op")) (fake/requests srv)))]
            (is (some? req) "an interrupt was sent")
            ;; fake/requests dissoc's the wire "id" (handle!'s own log strips
            ;; it), so this compares against the id Jonquil itself tracked
            ;; for the running request -- the same value interrupt! reads.
            (is (= expected-id (get req "interrupt-id")) "carrying the running request's own id")))
        (is (= {:status :ok :printed "3" :ns 'user} (:result (run e "(+ 1 2)" {:run? false})))
            "the next eval answers on the same connection")
        (is (= 1 (count (filter #{"clone"} (fake/ops-seen srv)))) "no reconnect")))))

(deftest stop-interrupt-ignored-test
  (with-project {:slow {"(slow)" 5000} :ignore-interrupt? true}
    (fn [srv dir _]
      (let [e (make dir) out (atom []) done (promise)]
        (ev/eval! e "(slow)" {:run? false
                              :on-output (fn [k s] (swap! out conj [k s]))
                              :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (some #{"(slow)"} (keep #(get % "code") (fake/requests srv)))))
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 2000 :timeout))) "one on-complete, :interrupted")
        (is (= [:system (str clj/interrupt-failed-message "\n")] (last @out)))
        (is (not (connection/connected? e)) "the failed interrupt detaches")
        (is (= :ok (:status (:result (run e "(+ 1 2)" {:run? false})))))
        (is (= 2 (count (filter #{"clone"} (fake/ops-seen srv))))
            "the next eval reconnects with a fresh clone")))))

(deftest stop-without-interrupt-op-test
  (with-project {:ops (vec (remove #{"interrupt"} fake/jvm-ops)) :slow {"(slow)" 5000}}
    (fn [srv dir _]
      (let [e (make dir) out (atom []) done (promise)]
        (ev/eval! e "(slow)" {:run? false
                              :on-output (fn [k s] (swap! out conj [k s]))
                              :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (some #{"(slow)"} (keep #(get % "code") (fake/requests srv)))))
        (ev/interrupt! e)
        (is (= [:system (str clj/interrupt-failed-message "\n")] (last @out)))
        (is (not (connection/connected? e)))
        (is (not-any? #{"interrupt"} (fake/ops-seen srv)) "interrupt is never sent without the op")
        (is (= :interrupted (:status (deref done 2000 :timeout))))))))

(deftest interrupt-then-stop-test
  ;; apply-selection! calls interrupt! then stop! back to back when
  ;; switching away from a busy window. stop! must still get the
  ;; interrupt out, not detach out from under interrupt!'s own send.
  ;; :interrupted can arrive via detach!'s own local, synthetic
  ;; disconnected reply (fired the moment stop!'s clock future closes the
  ;; socket) rather than a real reply from the fake, so it can resolve
  ;; before the fake has actually logged the op it already received on
  ;; the wire; wait-until! gives that a moment to catch up instead of
  ;; asserting against it synchronously.
  (with-project {:slow {"(slow)" 5000}}
    (fn [srv dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(slow)" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (some #{"(slow)"} (keep #(get % "code") (fake/requests srv)))))
        (ev/interrupt! e)
        (is (nil? (ev/stop! e)))
        (is (= :interrupted (:status (deref done 2000 :timeout))) "exactly one on-complete, :interrupted")
        (wait-until! (fn [] (some #{"interrupt"} (fake/ops-seen srv))))
        (is (some #{"interrupt"} (fake/ops-seen srv)))
        (wait-until! (fn [] (not (connection/connected? e))))
        (is (not (connection/connected? e)))))))

(deftest stop-alone-while-running-test
  ;; See interrupt-then-stop-test's comment.
  (with-project {:slow {"(slow)" 5000}}
    (fn [srv dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(slow)" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (some #{"(slow)"} (keep #(get % "code") (fake/requests srv)))))
        (is (nil? (ev/stop! e)))
        (is (= :interrupted (:status (deref done 2000 :timeout))) "exactly one on-complete, :interrupted")
        (wait-until! (fn [] (some #{"interrupt"} (fake/ops-seen srv))))
        (is (some #{"interrupt"} (fake/ops-seen srv)))
        (wait-until! (fn [] (not (connection/connected? e))))
        (is (not (connection/connected? e)))))))

(deftest stop-while-idle-test
  (with-project {}
    (fn [srv dir _]
      (let [e (make dir)]
        (is (= :ok (:status (:result (run e "1" {:run? false})))))
        (is (connection/connected? e))
        (is (nil? (ev/stop! e)))
        (is (not-any? #{"interrupt"} (fake/ops-seen srv)) "an idle stop! sends no interrupt")
        (is (not (connection/connected? e)) "and detaches")))))

(deftest stop-ignored-interrupt-still-detaches-test
  (with-project {:slow {"(slow)" 5000} :ignore-interrupt? true}
    (fn [srv dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(slow)" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (some #{"(slow)"} (keep #(get % "code") (fake/requests srv)))))
        (let [start (System/nanoTime)]
          (is (nil? (ev/stop! e)))
          (wait-until! (fn [] (not (connection/connected? e))))
          (is (< (/ (- (System/nanoTime) start) 1e6) 1000.0) "detaches well under 1000 ms"))
        (is (= :interrupted (:status (deref done 2000 :timeout))))))))

(deftest stop-during-connect-test
  ;; The fake has no :slow-ops (ruling 9: only handle!/evaluate!/the
  ;; options differ from the cljd fake, and slow-ops isn't one of them).
  ;; :mute? true never answers anything, so connect!'s own describe
  ;; request sits pending -- a real, timing-based window (no with-redefs:
  ;; this codebase's cross-namespace calls are direct-linked, so a
  ;; with-redefs on connection.clj's own client/request-sync or
  ;; connect! call never reaches code compiled inside those functions;
  ;; measured against this Jolt build with a throwaway probe script) in
  ;; which connect! has already published the raw socket at :connecting
  ;; but :conn is still nil. This exercises interrupt!'s own guard: it
  ;; detaches at once, with no sentence and no interrupt op (a fresh
  ;; evaluator's :ops is still #{}).
  (with-project {:mute? true}
    (fn [srv dir _]
      (let [e (make dir) done (promise) out (atom [])]
        (ev/eval! e "1" {:run? false
                         :on-output (fn [k s] (swap! out conj [k s]))
                         :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (some #{"describe"} (fake/ops-seen srv))))
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 2000 :timeout))) "one on-complete, :interrupted")
        (is (not (connection/connected? e)))
        (is (not-any? #{[:system (str clj/interrupt-failed-message "\n")]} @out)
            "no sentence: nothing was ever sent to interrupt")
        (is (not-any? #{"interrupt"} (fake/ops-seen srv)) "and no interrupt op is sent")))))

(deftest stop-before-request-test
  ;; Stop lands after connect! has already published a live session, but
  ;; before the worker sends the eval or load-file request.
  ;; jvm.clj routes every stop-requested? check (including the one its
  ;; connect policy hands to connection/connect!) through its own local
  ;; var so a with-redefs reached through THIS namespace's own alias can
  ;; see it -- the same reason jonquil.nrepl.attach keeps a private
  ;; stop-requested? wrapper of its own. This redefines it to flip the
  ;; REAL flag on state (so the worker's own post-connect check, called
  ;; through the very same var, reads a genuine stop) only once connect!
  ;; itself has already asked and gotten false at least twice (describe,
  ;; then the pre-publish clone check) -- i.e. only after a real success,
  ;; never during the handshake.
  (with-project {}
    (fn [srv dir _]
      (let [e (make dir) done (promise) calls (atom 0)]
        (with-redefs [jvm/stop-requested? (fn [ev2]
                                            (let [n (swap! calls inc)]
                                              (if (< n 3)
                                                false
                                                (do (swap! (:state ev2) assoc-in [:running :stop-requested?] true)
                                                    true))))]
          (ev/eval! e "1" {:run? false :on-complete (fn [r] (deliver done r))})
          (is (= :interrupted (:status (deref done 2000 :timeout))) "one on-complete, :interrupted"))
        (is (not-any? #{"eval"} (fake/ops-seen srv)) "no request is ever sent")
        (is (connection/connected? e) "the connection itself is fine; only this eval was cancelled")))))

(deftest double-stop-test
  ;; A second interrupt! call for the same evaluation does nothing -- one
  ;; interrupt op, one poller future, one sentence at most.
  (with-project {:slow {"(slow)" 5000} :ignore-interrupt? true}
    (fn [srv dir _]
      (let [e (make dir) out (atom []) done (promise)]
        (ev/eval! e "(slow)" {:run? false
                              :on-output (fn [k s] (swap! out conj [k s]))
                              :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (some #{"(slow)"} (keep #(get % "code") (fake/requests srv)))))
        (ev/interrupt! e)
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 2000 :timeout))) "one on-complete, :interrupted")
        (is (= 1 (count (filter #{[:system (str clj/interrupt-failed-message "\n")]} @out)))
            "one sentence at most")
        (is (= 1 (count (filter #{"interrupt"} (fake/ops-seen srv)))) "only one interrupt sent")))))

(deftest interrupt-future-tracks-its-own-eval-test
  ;; Regression guard: A's failed-interrupt future must not detach a
  ;; later evaluation's connection. A ends (via a real,
  ;; successful interrupt) well inside its own poller's interrupt-timeout
  ;; window; B claims :running with a fresh token before that window
  ;; closes and is still running when A's poller would have timed out.
  (with-project {:slow {"(a)" 5000 "(b)" 500}}
    (fn [srv dir _]
      (let [e (make dir) out (atom []) done-a (promise) done-b (promise)]
        (ev/eval! e "(a)" {:run? false
                           :on-output (fn [k s] (swap! out conj [k s]))
                           :on-complete (fn [r] (deliver done-a r))})
        (wait-until! (fn [] (some #{"(a)"} (keep #(get % "code") (fake/requests srv)))))
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done-a 2000 :timeout))) "A stops via the real interrupt op")
        (ev/eval! e "(b)" {:run? false :on-complete (fn [r] (deliver done-b r))})
        (wait-until! (fn [] (some #{"(b)"} (keep #(get % "code") (fake/requests srv)))))
        ;; A's poller is still counting toward its own interrupt-timeout-ms
        ;; deadline (measured from when interrupt! was called for A); B is
        ;; now the running evaluation and is still in flight (500ms) when
        ;; that deadline arrives.
        (is (= {:status :ok :printed ":ok" :ns 'user} (deref done-b 2000 :timeout))
            "B finishes normally: A's stale poller must not detach its connection")
        (is (not-any? #{[:system (str clj/interrupt-failed-message "\n")]} @out)
            "A's poller saw B's token, not its own, and stayed quiet")
        (is (connection/connected? e))))))

(deftest idle-stop-test
  (with-project {}
    (fn [srv dir _]
      (let [e (make dir)]
        (is (= :ok (:status (:result (run e "1" {:run? false})))))
        (is (connection/connected? e))
        (is (nil? (ev/interrupt! e)))
        (is (not-any? #{"interrupt"} (fake/ops-seen srv)) "an idle Stop sends nothing")
        (is (connection/connected? e) "and stays connected (ruling 5)")))))

(deftest stdin-test
  (with-project {}
    (fn [srv dir _]
      (let [e (make dir) waits (atom []) done (promise)]
        (ev/eval! e "(read-line)" {:run? false
                                   :on-wait-input (fn [w] (swap! waits conj w))
                                   :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (ev/waiting-for-input? e)))
        (is (true? (ev/waiting-for-input? e)))
        (is (= [true] @waits))
        (is (nil? (ev/send-input! e "typed")))
        (is (= {:status :ok :printed "\"typed\"" :ns 'user} (deref done 2000 :timeout)))
        (is (false? (ev/waiting-for-input? e)))
        (is (= [true false] @waits))
        (is (= "typed\n" (get (last (filter #(= "stdin" (get % "op")) (fake/requests srv))) "stdin")))))))

(defn- start-read!
  "Starts (read-line) and waits until it blocks. {:done promise :out atom
  :waits atom}."
  [e]
  (let [done (promise) out (atom []) waits (atom [])]
    (ev/eval! e "(read-line)" {:run? false
                               :on-output (fn [k s] (swap! out conj [k s]))
                               :on-wait-input (fn [w] (swap! waits conj w))
                               :on-complete (fn [r] (deliver done r))})
    (wait-until! (fn [] (ev/waiting-for-input? e)))
    {:done done :out out :waits waits}))

(defn- eof-notes
  "The :system lines about EOF, leaving out the connect progress line."
  [out]
  (filter #(str/includes? % "close stdin") (texts out :system)))

(defn- stdin-count [srv] (count (filter #{"stdin"} (fake/ops-seen srv))))

(deftest eof-on-1-8-test
  (with-project {:nrepl-version [1 8 0]}
    (fn [srv dir _]
      (let [e (make dir) {:keys [done waits]} (start-read! e)]
        (is (true? (ev/waiting-for-input? e)))
        (is (nil? (ev/send-eof! e)))
        (is (= {:status :ok :printed "nil" :ns 'user} (deref done 2000 :timeout)))
        (is (= [true false] @waits))
        (is (= "" (get (last (filter #(= "stdin" (get % "op")) (fake/requests srv))) "stdin")))
        (is (false? (ev/waiting-for-input? e)))))))

(deftest eof-twice-sends-once-test
  (with-project {:nrepl-version [1 8 0]}
    (fn [srv dir _]
      (let [e (make dir) {:keys [done]} (start-read! e)]
        (ev/send-eof! e)
        (ev/send-eof! e)
        (is (= :ok (:status (deref done 2000 :timeout))))
        (Thread/sleep 100)
        (is (= 1 (stdin-count srv)))))))

(deftest eof-idle-sends-nothing-test
  (with-project {:nrepl-version [1 8 0]}
    (fn [srv dir _]
      (let [e (make dir)]
        (is (= :ok (:status (:result (run e "(+ 1 2)" {:run? false})))))
        (let [n (count (fake/ops-seen srv))]
          (is (nil? (ev/send-eof! e)))
          (Thread/sleep 100)
          (is (= n (count (fake/ops-seen srv)))))
        (let [{:keys [done]} (start-read! e)]
          (is (true? (ev/waiting-for-input? e)))
          (ev/send-input! e "x")
          (is (= "\"x\"" (:printed (deref done 2000 :timeout)))))))))

(deftest eof-on-1-7-test
  (with-project {}
    (fn [srv dir _]
      (let [e (make dir) {:keys [done out]} (start-read! e)]
        (is (nil? (ev/send-eof! e)))
        (Thread/sleep 100)
        (is (zero? (stdin-count srv)))
        (is (true? (ev/waiting-for-input? e)))
        (is (= [(str (clj/eof-unsupported-message [1 7]) "\n")] (eof-notes @out)))
        (ev/send-input! e "typed")
        (is (= :ok (:status (deref done 2000 :timeout))))))))

(deftest eof-note-once-per-connection-test
  (with-project {}
    (fn [_srv dir _]
      (let [e (make dir) note (str (clj/eof-unsupported-message [1 7]) "\n")
            r1 (start-read! e)]
        (ev/send-eof! e)
        (ev/send-eof! e)
        (ev/send-input! e "a")
        (deref (:done r1) 2000 :timeout)
        (let [r2 (start-read! e)]
          (ev/send-eof! e)
          (ev/send-input! e "b")
          (deref (:done r2) 2000 :timeout)
          (is (= [note] (eof-notes @(:out r1))) "two presses in one read, one note")
          (is (empty? (eof-notes @(:out r2))) "a second read on the same connection stays quiet"))
        (connection/detach! e)
        (let [r3 (start-read! e)]
          (ev/send-eof! e)
          (is (= [note] (eof-notes @(:out r3))) "a fresh connection notes again")
          (ev/send-input! e "c")
          (deref (:done r3) 2000 :timeout))))))

(deftest eof-unknown-version-test
  (with-project {:nrepl-version :none}
    (fn [srv dir _]
      (let [e (make dir) {:keys [done out]} (start-read! e)]
        (ev/send-eof! e)
        (Thread/sleep 100)
        (is (zero? (stdin-count srv)))
        (is (= [(str (clj/eof-unsupported-message nil) "\n")] (eof-notes @out)))
        (ev/send-input! e "typed")
        (deref done 2000 :timeout)))))

(deftest eof-closed-conn-test
  (with-project {:nrepl-version [1 8 0]}
    (fn [srv dir _]
      (let [e (make dir) {:keys [done]} (start-read! e)]
        (fake/drop-connections! srv)
        (is (nil? (ev/send-eof! e)))
        (let [r (deref done 2000 :timeout)]
          (is (not= :timeout r))
          (is (some? r)))))))

(deftest stop-during-read-test
  (with-project {}
    (fn [_srv dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(read-line)" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (ev/waiting-for-input? e)))
        (is (true? (ev/waiting-for-input? e)))
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 2000 :timeout))))
        (is (false? (ev/waiting-for-input? e)) "the console leaves its input-waiting state")))))

(deftest dropped-connection-test
  (with-project {:slow {"(slow)" 5000}}
    (fn [srv dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(slow)" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (some #{"(slow)"} (keep #(get % "code") (fake/requests srv)))))
        (fake/drop-connections! srv)
        (is (= {:status :error :error-text clj/lost-connection} (deref done 2000 :timeout)))
        (is (not (connection/connected? e)))
        (is (= {:status :ok :printed "3" :ns 'user} (:result (run e "(+ 1 2)" {:run? false})))
            "the next eval reconnects and answers")
        (is (= 2 (count (filter #{"clone"} (fake/ops-seen srv)))))))))

(deftest reconnect-resets-prompt-test
  (with-project {}
    (fn [srv dir file]
      (let [e (make dir)]
        (run e fixture-core-text {:run? true :path file})
        (is (= 'demo.core (ev/prompt-ns e)))
        (fake/drop-connections! srv)
        (wait-until! (fn [] (not (connection/connected? e))))
        (is (= :ok (:status (:result (run e "(+ 1 2)" {:run? false})))))
        (is (= "user" (last (eval-ns-values srv)))
            "the reconnect's fresh session evaluates in user, not the last Run's namespace")
        (is (= 'user (ev/prompt-ns e)))))))

(deftest busy-test
  (with-project {:slow {"(slow)" 400}}
    (fn [_srv dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(slow)" {:run? false :on-complete (fn [r] (deliver done r))})
        (is (= jvm/busy-warning (ev/eval! e "1" {:run? false})))
        (is (ev/evaluating? e))
        (is (= :ok (:status (deref done 5000 :timeout))))))))

(deftest reset-mode-test
  (with-project {}
    (fn [srv dir file]
      (let [e (make dir)]
        (is (nil? (ev/set-mode! e :reset)))
        (run e fixture-core-text {:run? true :path file})
        (is (= ["describe" "clone" "eval" "load-file"] (fake/ops-seen srv))
            "no reset form is sent")
        (is (= ["nil"] (keep #(when (= "eval" (get % "op")) (get % "code")) (fake/requests srv)))
            "the one eval is the connect-time registration's nil, never a reset form")))))

(deftest stop-during-registration-test
  ;; A Stop that lands during the connect-time registration round trip
  ;; finishes :interrupted and sends nothing else: the user's own request
  ;; never goes out.
  (with-project {:slow {"nil" 1000}}
    (fn [srv dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(+ 1 2)" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (some #(= "nil" (get % "code")) (fake/requests srv))))
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 3000 :timeout))))
        (is (not-any? #(= "(+ 1 2)" (get % "code")) (fake/requests srv))
            "the user's request never went out")))))

(deftest complete-test
  (with-project {:completions {"gre" [["greet" "demo.core"]]}}
    (fn [srv dir file]
      (let [e (make dir) text "(gre" pos (count text)]
        (testing "not connected: offline candidates only, and the fake never sees a completions request"
          (is (= (cljd/candidates dialect/clj "gre" text pos nil) (ev/complete e "gre" text pos nil)))
          (is (not (connection/connected? e)))
          (is (not-any? #{"completions"} (fake/ops-seen srv))))
        (run e fixture-core-text {:run? true :path file})
        (let [cands (ev/complete e "gre" text pos nil)]
          (testing "after a Run, the server's own candidates are merged in"
            (is (some #(= {:symbol "greet" :category :user} %) cands)))
          (testing "the request carried the buffer's own ns"
            (is (= "demo.core" (get (last (filter #(= "completions" (get % "op")) (fake/requests srv))) "ns"))))
          (testing "no candidate's :symbol starts with jolt. (never docs/candidates)"
            (is (not-any? #(str/starts-with? (:symbol %) "jolt.") cands))))))))

(deftest lookup-test
  (testing "not connected"
    (with-project {}
      (fn [_srv dir _]
        (let [e (make dir)]
          (is (= (cljd/no-docs "greet" clj/not-attached-doc) (ev/lookup e "greet" fixture-core-text 0)))))))
  (testing "connected: found, with a Defined in line for the definition outside the buffer, and nil for an unknown symbol"
    ;; :docs' own "file" has to be the temp project's real canonical
    ;; src/demo/core.clj path, which with-project can't provide (it starts
    ;; the fake, whose opts fix the :docs table, before the temp project
    ;; and its path exist). So this builds the same two pieces (temp-project,
    ;; then fake/start!) in the other order, using the same helpers.
    (let [[dir file] (temp-project nil)]
      (try
        (let [srv (fake/start! {:docs {"greet" {"ns" "demo.core" "name" "greet" "doc" "Says hello."
                                                "arglists-str" "([who])" "file" file "line" 4}}})]
          (spit (io/file dir ".nrepl-port") (str (:port srv)))
          (try
            (let [e (make dir)]
              (run e fixture-core-text {:run? true :path file})
              (let [d (ev/lookup e "greet" fixture-core-text 0)]
                (is (= :found (:status d)))
                (is (= "demo.core" (:ns d)))
                (is (= (str "Defined in " file ":4") (:location d))))
              (is (nil? (ev/lookup e "nope" fixture-core-text 0))))
            (finally (fake/stop! srv))))
        (finally (temp-dirs/delete-tree! dir)))))
  (testing "a timeout (a non-vector reply) gives the timeout sentence"
    (with-project {:docs {"greet" {"ns" "demo.core" "name" "greet"}} :lookup-ms 1000}
      (fn [_srv dir _]
        (let [e (make dir)]
          (run e "1" {:run? false})
          (is (= (cljd/no-docs "greet" clj/lookup-timeout-doc) (ev/lookup e "greet" fixture-core-text 0)))))))
  (testing "no lookup op: the no-lookup sentence"
    (with-project {:ops (vec (remove #{"lookup"} fake/jvm-ops))}
      (fn [_srv dir _]
        (let [e (make dir)]
          (run e "1" {:run? false})
          (is (= (cljd/no-docs "greet" clj/no-lookup-doc) (ev/lookup e "greet" fixture-core-text 0))))))))

(def full-doc-greet-text
  "The fake's own :full-docs answer for greet -- P3.T2's step 5 text,
  reused by the :clj self-test rows for the same fixture symbol."
  "demo.core/greet\n([who])\nSays hello.\n\n== examples")

(def ^:private greet-docs
  ;; lookup's own info for greet: full-doc only sends doc for a symbol the
  ;; lookup found.
  {"greet" {"ns" "demo.core" "name" "greet" "doc" "Says hello." "arglists-str" "([who])"}})

(defn- full-doc-sync
  "Calls ev/full-doc and waits up to timeout-ms for its on-result, once.
  :timeout when it never fires."
  [e sym text timeout-ms]
  (let [done (promise)]
    (ev/full-doc e sym text (fn [r] (deliver done r)))
    (deref done timeout-ms :timeout)))

(deftest full-doc-test
  (testing "the Jvm evaluator satisfies FullDoc"
    (with-project {}
      (fn [_srv dir _] (is (satisfies? ev/FullDoc (make dir))))))
  (testing "not connected: :not-attached at once, nothing sent"
    (with-project {:full-docs {"greet" full-doc-greet-text}}
      (fn [srv dir _]
        (let [e (make dir)]
          (is (= {:status :not-attached} (full-doc-sync e "greet" fixture-core-text 2000)))
          (is (empty? (fake/ops-seen srv)))))))
  (testing "connected, after a Run: the fake's own full-docs text, and the request carried the buffer's own ns"
    (with-project {:docs greet-docs :full-docs {"greet" full-doc-greet-text}}
      (fn [srv dir file]
        (let [e (make dir)]
          (run e fixture-core-text {:run? true :path file})
          (is (= {:status :ok :text full-doc-greet-text}
                 (full-doc-sync e "greet" fixture-core-text 2000)))
          (is (= "demo.core" (get (last (filter #(= "doc" (get % "op")) (fake/requests srv))) "ns")))))))
  (testing "a timeout (the server's own doc-ms outlasts full-doc-timeout-ms): :timeout, well within doc-ms"
    (with-project {:docs greet-docs :doc-ms 1000}
      (fn [_srv dir _]
        (let [e (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir dir}
                              {:describe-timeout-ms 500 :clone-timeout-ms 500 :interrupt-timeout-ms 200
                               :help-timeout-ms 200 :full-doc-timeout-ms 200})
              start (System/nanoTime)]
          (run e "1" {:run? false})
          (is (= {:status :timeout} (full-doc-sync e "greet" "1" 1000)))
          (is (< (/ (- (System/nanoTime) start) 1e6) 1000.0) "well under doc-ms's own 1000 ms")))))
  (testing "no doc op: :no-op, nothing sent"
    (with-project {:ops (vec (remove #{"doc"} fake/jvm-ops))}
      (fn [srv dir _]
        (let [e (make dir)]
          (run e "1" {:run? false})
          (is (= {:status :no-op} (full-doc-sync e "greet" "1" 2000)))
          (is (not-any? #{"doc"} (fake/ops-seen srv)))))))
  (testing "a symbol the namespace doesn't know: :not-found, and doc is never sent"
    (with-project {:full-docs {"nope" "never sent"}}
      (fn [srv dir _]
        (let [e (make dir)]
          (run e "1" {:run? false})
          (is (= {:status :not-found} (full-doc-sync e "nope" "1" 2000)))
          (is (not-any? #{"doc"} (fake/ops-seen srv))))))))

(deftest lookup-not-loaded-test
  ;; Before Run: the buffer's own ns (demo.never) never loaded on the
  ;; server, so lookup falls back once to the prompt ns (user, always
  ;; loaded). A symbol the fallback resolves still answers with its docs;
  ;; one it doesn't gives the not-loaded no-docs, naming the buffer's own
  ;; namespace, never "user".
  (with-project {:docs {"map" {"ns" "clojure.core" "name" "map" "doc" "Returns a lazy sequence."
                               "arglists-str" "([f] [f coll])"}}}
    (fn [_srv dir _]
      (let [e (make dir)]
        (run e "1" {:run? false})
        (testing "a symbol the prompt ns resolves: the doc, via the fallback"
          (let [d (ev/lookup e "map" "(ns demo.never)\n(map inc [1])" 0)]
            (is (= :found (:status d)))
            (is (= "clojure.core" (:ns d)))))
        (testing "a symbol the prompt ns doesn't resolve: the not-loaded no-docs"
          (is (= (cljd/no-docs "secret" (clj/not-loaded-doc "demo.never"))
                 (ev/lookup e "secret" "(ns demo.never)\n(secret)" 0))))))))

(deftest full-doc-not-loaded-test
  ;; Before Run: neither the buffer's own ns (demo.never) nor the prompt
  ;; ns (user, loaded but knowing nothing about map here) resolves, so
  ;; this reports :not-loaded quickly -- well under :full-doc-timeout-ms
  ;; -- and never sends doc at all.
  (with-project {}
    (fn [srv dir _]
      (let [e (make dir)]
        (run e "1" {:run? false})
        (let [start (System/nanoTime)
              result (full-doc-sync e "map" "(ns demo.never)\n(map inc [1])" 1000)]
          (is (= {:status :not-loaded :ns "demo.never"} result))
          (is (< (/ (- (System/nanoTime) start) 1e6) 450.0) "well under :full-doc-timeout-ms (500)")
          (is (not-any? #{"doc"} (fake/ops-seen srv))))))))

(deftest full-doc-disconnect-test
  ;; A disconnect while doc is outstanding (the fake's own :doc-ms delay)
  ;; gives :not-attached, never :ok with a blank/nil text.
  (with-project {:docs {"map" {"ns" "clojure.core" "name" "map" "doc" "Returns a lazy sequence."}}
                 :doc-ms 3000}
    (fn [srv dir _]
      (let [e (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir dir}
                            {:describe-timeout-ms 500 :clone-timeout-ms 500 :interrupt-timeout-ms 200
                             :help-timeout-ms 200 :full-doc-timeout-ms 5000})
            done (promise)]
        (run e "1" {:run? false})
        (ev/full-doc e "map" "1" (fn [r] (deliver done r)))
        (wait-until! (fn [] (some #{"doc"} (fake/ops-seen srv))))
        (fake/drop-connections! srv)
        (is (= {:status :not-attached} (deref done 2000 :timeout)))))))

(defn- inspect-sync
  "Calls ev/inspect! and waits up to timeout-ms for its on-view, once.
  :timeout when it never fires."
  [e op args timeout-ms]
  (let [done (promise)]
    (ev/inspect! e op args (fn [v] (deliver done v)))
    (deref done timeout-ms :timeout)))

(defn- on-view-once!
  "Calls ev/inspect! e op args, waits up to 2000ms for on-view's first
  call, then waits another 200ms and returns the total number of times
  on-view fired (inspect-on-view-once-test's own count-calls-then-wait
  check). mid-flight, when given, runs right after inspect! is dispatched
  -- a chance to fake/drop-connections! etc. before the first reply
  lands."
  ([e op args] (on-view-once! e op args nil))
  ([e op args mid-flight]
   (let [calls (atom 0) first-call (promise)]
     (ev/inspect! e op args (fn [v] (swap! calls inc) (deliver first-call v)))
     (when mid-flight (mid-flight))
     (deref first-call 2000 :timeout)
     (Thread/sleep 200)
     @calls)))

(deftest registration-test
  ;; Review focus 1: after a Run on a fresh connection, the fake saw an eval of "nil"
  ;; before the load-file, and inspect! :start shows the Run's value, not nil.
  (with-project {}
    (fn [srv dir file]
      (let [e (make dir)]
        (run e fixture-core-text {:run? true :path file})
        (is (= ["describe" "clone" "eval" "load-file"] (fake/ops-seen srv)))
        (is (= "nil" (get (nth (fake/requests srv) 2) "code")) "the registration eval's own code")
        (let [v (inspect-sync e :start {:rows 10 :cols 40} 2000)]
          (is (not= "nil" (:leaf v)))
          (is (str/includes? (:leaf v) "nested")))))))

(deftest registration-only-with-the-op-test
  ;; :ops without "inspect-start": no extra eval is sent (fake/requests has only the load-file).
  (with-project {:ops (vec (remove #{"inspect-start"} fake/jvm-ops))}
    (fn [srv dir file]
      (let [e (make dir)]
        (run e fixture-core-text {:run? true :path file})
        (is (= ["describe" "clone" "load-file"] (fake/ops-seen srv)))))))

(deftest inspect-ops-test
  ;; after a REPL eval of {:a [1 2]}: :start, :in {:idx 0} (path "*1 / :a"), :out (:cursor 0),
  ;; :page {:delta 1}, :meta {:cursor 0} (note "no metadata"), :table (:alt-view flips: spec probe 1
  ;; showed {:a [1 2 ...]} rendering as a table, so it toggles rather than answering "not tabular"),
  ;; :def {:name "x"} (:defd "#'user/x"); each request carried :session and the right op name.
  (with-project {:answers {"{:a [1 2]}" "{:a [1 2]}"}}
    (fn [srv dir _]
      (let [e (make dir)]
        (run e "{:a [1 2]}" {:run? false})
        (let [v (inspect-sync e :start {:rows 10 :cols 40} 2000)]
          (is (= "*1" (:path v)))
          (is (= "PersistentArrayMap · 1 items" (:info v)))
          (is (= "list" (:alt-view v))))
        (let [v (inspect-sync e :in {:idx 0} 2000)]
          (is (= "*1 / :a" (:path v))))
        (let [v (inspect-sync e :out {} 2000)]
          (is (= 0 (:cursor v))))
        (is (not (contains? (inspect-sync e :page {:delta 1} 2000) :error)))
        (let [v (inspect-sync e :meta {:cursor 0} 2000)]
          (is (= "no metadata" (:note v))))
        (let [v (inspect-sync e :table {} 2000)]
          (is (= "table" (:alt-view v))))
        (let [v (inspect-sync e :def {:name "x"} 2000)]
          (is (= "#'user/x" (:defd v))))
        (let [reqs (filter #(str/starts-with? (or (get % "op") "") "inspect-") (fake/requests srv))]
          (is (= ["inspect-start" "inspect-in" "inspect-out" "inspect-page" "inspect-toggle-meta"
                  "inspect-toggle-table" "inspect-def"]
                 (map #(get % "op") reqs)))
          (is (every? #(some? (get % "session")) reqs)))))))

(deftest inspect-not-attached-test
  ;; before any evaluation: {:error clj/inspect-not-attached}, nothing sent
  (with-project {}
    (fn [srv dir _]
      (let [e (make dir)]
        (is (= {:error clj/inspect-not-attached} (inspect-sync e :start {:rows 10 :cols 40} 2000)))
        (is (empty? (fake/ops-seen srv)))))))

(deftest inspect-unavailable-test
  ;; :ops without inspect-start: {:error inspect/unavailable-message}; inspectable? false once connected
  (with-project {:ops (vec (remove #{"inspect-start"} fake/jvm-ops))}
    (fn [_srv dir _]
      (let [e (make dir)]
        (run e "1" {:run? false})
        (is (false? (ev/inspectable? e)))
        (is (= {:error inspect/unavailable-message} (inspect-sync e :start {:rows 10 :cols 40} 2000)))))))

(deftest inspect-timeout-test
  ;; fake :inspect-ms 1000 with :inspect-timeout-ms 200 -> {:error inspect/timeout-message} in < 1000 ms
  (with-project {:inspect-ms 1000}
    (fn [_srv dir _]
      (let [e (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir dir}
                            {:describe-timeout-ms 500 :clone-timeout-ms 500 :interrupt-timeout-ms 200
                             :help-timeout-ms 200 :full-doc-timeout-ms 500 :inspect-timeout-ms 200})
            start (System/nanoTime)]
        (run e "1" {:run? false})
        (is (= {:error inspect/timeout-message} (inspect-sync e :start {:rows 10 :cols 40} 2000)))
        (is (< (/ (- (System/nanoTime) start) 1e6) 1000.0) "well under the fake's own 1000 ms :inspect-ms")))))

(deftest inspect-lost-connection-test
  ;; review focus 5: fake/drop-connections! while :inspect-ms delays -> {:error clj/lost-connection :lost? true}
  (with-project {:inspect-ms 1000}
    (fn [srv dir _]
      (let [e (make dir)
            done (promise)]
        (run e "1" {:run? false})
        (ev/inspect! e :start {:rows 10 :cols 40} (fn [v] (deliver done v)))
        (wait-until! (fn [] (some #{"inspect-start"} (fake/ops-seen srv))))
        (fake/drop-connections! srv)
        (is (= {:error clj/lost-connection :lost? true} (deref done 2000 :timeout)))))))

(deftest inspect-lost-connection-idle-test
  ;; I2: the connection drops while the panel is IDLE (nothing in flight),
  ;; not mid round-trip like inspect-lost-connection-test above. :conn is
  ;; non-nil but closed (connect! never nils it back out on a passive
  ;; drop -- only detach!/a fresh connect! does), so this must answer
  ;; clj/lost-connection, never clj/inspect-not-attached.
  (with-project {}
    (fn [srv dir _]
      (let [e (make dir)]
        (run e "1" {:run? false})
        (fake/drop-connections! srv)
        (wait-until! (fn [] (not (connection/connected? e))))
        (is (= {:error clj/lost-connection :lost? true}
               (inspect-sync e :start {:rows 10 :cols 40} 2000)))))))

(deftest inspect-on-view-once-test
  ;; on-view is called exactly once on each path above (count calls; wait 200 ms after the first)
  (testing "not attached"
    (with-project {}
      (fn [_srv dir _]
        (let [e (make dir)]
          (is (= 1 (on-view-once! e :start {:rows 10 :cols 40})))))))
  (testing "unavailable"
    (with-project {:ops (vec (remove #{"inspect-start"} fake/jvm-ops))}
      (fn [_srv dir _]
        (let [e (make dir)]
          (run e "1" {:run? false})
          (is (= 1 (on-view-once! e :start {:rows 10 :cols 40})))))))
  (testing "a successful op"
    (with-project {}
      (fn [_srv dir _]
        (let [e (make dir)]
          (run e "1" {:run? false})
          (is (= 1 (on-view-once! e :start {:rows 10 :cols 40})))))))
  (testing "a timeout"
    (with-project {:inspect-ms 400}
      (fn [_srv dir _]
        (let [e (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir dir}
                              {:describe-timeout-ms 500 :clone-timeout-ms 500 :interrupt-timeout-ms 200
                               :help-timeout-ms 200 :full-doc-timeout-ms 500 :inspect-timeout-ms 100})]
          (run e "1" {:run? false})
          (is (= 1 (on-view-once! e :start {:rows 10 :cols 40})))))))
  (testing "a lost connection"
    (with-project {:inspect-ms 1000}
      (fn [srv dir _]
        (let [e (make dir)]
          (run e "1" {:run? false})
          (is (= 1 (on-view-once! e :start {:rows 10 :cols 40}
                                  (fn []
                                    (wait-until! (fn [] (some #{"inspect-start"} (fake/ops-seen srv))))
                                    (fake/drop-connections! srv))))))))))


;; --- auto-require (.jonquil.edn :auto-require, via the selection) ---

(defn- make-auto [dir nss]
  (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir dir :auto-require nss}
                {:describe-timeout-ms 500 :clone-timeout-ms 500 :interrupt-timeout-ms 200
                 :help-timeout-ms 200 :full-doc-timeout-ms 500}))

(defn- eval-codes [srv]
  (keep #(when (= "eval" (get % "op")) (get % "code")) (fake/requests srv)))

(def ^:private require-code "(require 'my.app.core 'my.app.dev)")

(deftest auto-require-once-per-connection-test
  (with-project {}
    (fn [srv dir file]
      (let [e (make-auto dir '[my.app.core my.app.dev])
            {:keys [result out]} (run e fixture-core-text {:run? true :path file})]
        (is (= :ok (:status result)))
        (is (= ["describe" "clone" "eval" "eval" "load-file"] (fake/ops-seen srv))
            "registration, then the require, then the user's own Run")
        (is (= ["nil" require-code] (eval-codes srv)))
        (is (some #(= (str (clj/auto-require-message '[my.app.core my.app.dev]) "\n") %) (texts out :system)))
        (run e "(+ 1 2)" {:run? false})
        (is (= ["nil" require-code "(+ 1 2)"] (eval-codes srv))
            "not sent again on the same connection")))))

(deftest auto-require-after-reconnect-test
  (with-project {}
    (fn [srv dir _]
      (let [e (make-auto dir '[my.app.core])]
        (run e "(+ 1 2)" {:run? false})
        (fake/drop-connections! srv)
        (wait-until! (fn [] (not (connection/connected? e))))
        (is (= :ok (:status (:result (run e "(+ 1 2)" {:run? false})))))
        (is (= 2 (count (filter #{"(require 'my.app.core)"} (eval-codes srv))))
            "a restarted server has forgotten everything, so a reconnect requires again")))))

(deftest auto-require-failure-still-runs-test
  (with-project {:fail-on "my.app.core"}
    (fn [srv dir _]
      (let [e (make-auto dir '[my.app.core])
            {:keys [result out]} (run e "(+ 1 2)" {:run? false})]
        (is (= :ok (:status result)) "the user's own request still answers")
        (is (= "3" (:printed result)))
        (is (some #(= (str clj/auto-require-failed-message "\n") %) (texts out :system)))
        (is (some #(str/includes? % "boom") (texts out :err)) "the server's own error streams first")
        (run e "(+ 1 2)" {:run? false})
        (is (= 1 (count (filter #{"(require 'my.app.core)"} (eval-codes srv))))
            "no retry on the same connection")))))

(deftest auto-require-none-test
  (with-project {}
    (fn [srv dir _]
      (let [e (make-auto dir nil)]
        (run e "(+ 1 2)" {:run? false})
        (is (= ["nil" "(+ 1 2)"] (eval-codes srv)))))))

(deftest stop-during-auto-require-test
  (with-project {:slow {"my.app.core" 1000}}
    (fn [srv dir _]
      (let [e (make-auto dir '[my.app.core]) done (promise) out (atom [])]
        (ev/eval! e "(+ 1 2)" {:run? false
                               :on-output (fn [k s] (swap! out conj [k s]))
                               :on-complete (fn [r] (deliver done r))})
        (wait-until! (fn [] (some #(= "(require 'my.app.core)" (get % "code")) (fake/requests srv))))
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 3000 :timeout))))
        (is (not-any? #(= "(+ 1 2)" (get % "code")) (fake/requests srv))
            "the user's request never went out")
        (is (not-any? #(= (str clj/auto-require-failed-message "\n") %) (eof-notes @out))
            "a Stop isn't reported as a failed require")))))
