(ns jonquil.nrepl.jvm-test
  "The JVM attach evaluator against dev/fake_jvm_nrepl.clj's fake server.
  Every test owns its temp project and its fake server, and stops both."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fake-jvm-nrepl :as fake]
            [jonquil.clj :as clj]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.jvm :as jvm]))

(def fixture-core-text
  ;; The spike fixture's exact text (.superpowers/sdd/clj/fixture-core.clj):
  ;; demo.core with greet, a private secret returning :private-ok, and
  ;; nested. Embedded rather than read at test time: the planning store is
  ;; gitignored scratch, so tests can't depend on it.
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
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "jonquil-jvm-test-" (System/currentTimeMillis) "-" (random-uuid)))
        f (io/file d "src/demo/core.clj")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "deps.edn") "{:deps {}}\n")
    (spit (io/file d ".jonquil.edn") "{:dialect :clj}\n")
    (spit f fixture-core-text)
    (when port-text (spit (io/file d ".nrepl-port") port-text))
    [(.getCanonicalPath d) (.getCanonicalPath f)]))

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete ^java.io.File f)))

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
         (finally (fake/stop! srv) (delete-tree! dir)))))

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
        (finally (delete-tree! dir)))))
  (testing ".nrepl-port without a port number: the bad-port-file notice"
    (let [[dir _] (temp-project "abc") e (make dir) before (lock-state dir)]
      (try
        (is (= {:status :notice :text clj/bad-port-file-message} (:result (run e "1" {:run? false}))))
        (is (= before (lock-state dir)))
        (finally (delete-tree! dir)))))
  (testing "a port nothing answers: the refused notice, after the connecting line"
    (let [dead (doto (fake/start!) fake/stop!)
          [dir _] (temp-project (str (:port dead))) e (make dir) before (lock-state dir)
          {:keys [result out]} (run e "1" {:run? false})]
      (try
        (is (= {:status :notice :text (clj/refused-message (:port dead))} result))
        (is (= [(str (clj/connecting-message (:port dead)) "\n")] (texts out :system)))
        (is (= before (lock-state dir)) "the stale file is never deleted or rewritten")
        (finally (delete-tree! dir)))))
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
        (is (= 1 (count (filter #{"eval"} (fake/ops-seen srv)))) "exactly one eval request was sent")
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
        (let [start (System/nanoTime)]
          (is (= :interrupted (:status (do (ev/interrupt! e) (deref done 1000 :timeout)))))
          (is (< (/ (- (System/nanoTime) start) 1e6) 1000.0) "interrupted well under 1000 ms"))
        (let [req (last (filter #(= "interrupt" (get % "op")) (fake/requests srv)))]
          (is (some? req) "an interrupt was sent")
          (is (some? (get req "interrupt-id")) "carrying the running request's id"))
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
        (wait-until! (fn [] (some #{[:system (str clj/interrupt-failed-message "\n")]} @out)))
        (is (= [:system (str clj/interrupt-failed-message "\n")] (last @out)))
        (is (not (connection/connected? e)) "the failed interrupt detaches")
        (wait-until! (fn [] (not (ev/evaluating? e))))
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
        (is (= "typed\n" (get (last (filter #(= "stdin" (get % "op")) (fake/requests srv))) "stdin")))
        (let [n (count (fake/ops-seen srv))]
          (is (nil? (ev/send-eof! e)))
          (is (= n (count (fake/ops-seen srv)))))))))

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
        (is (= ["describe" "clone" "load-file"] (fake/ops-seen srv)) "no reset form is sent")))))
