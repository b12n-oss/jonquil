(ns jonquil.live.clj-attach-test
  "Drives jonquil.nrepl.jvm against a REAL clojure -M:serve nREPL server
  (clojure-cli.repl-server 0.1.0 on nREPL 1.7.0), started once for this
  namespace by dev/clj_server.clj. Run with `bb test:clj`, not `bb test`
  (ruling 12): it needs Java 17+, the clojure CLI and, on a first run
  that hasn't cached clojure-cli.repl-server yet, the network.

  clj-server plays the human who already has a server running, the way
  jonquil.nrepl.child plays the human for the :nrepl dialect's own live
  suite: Jonquil's own evaluator code never starts, stops or signals
  this JVM.

  Every test but the last shares one server (the :once fixture) and
  makes its own fresh Jvm evaluator against it, so each gets its own
  clone session; a namespace loaded by one test's Run stays loaded for
  the whole server (real Clojure semantics), which is why
  not-loaded-live uses a namespace ('demo.never') nothing else ever
  touches. The last test, which checks .nrepl-port's fate after stop!,
  starts and stops its own separate server instead."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [clj-server :as clj-server]
            [jonquil.clj :as clj]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.jvm :as jvm]))

(def ^:dynamic *srv* nil)

(defn- once-fixture [f]
  (let [srv (clj-server/start!)]
    (try
      (binding [*srv* srv] (f))
      (finally (clj-server/stop! srv)))))

(use-fixtures :once once-fixture)

(defn- make []
  (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir (:dir *srv*)}))

(defn- run
  "Evaluates and waits. {:result result :out output :waits on-wait-input
  calls}."
  [e code opts]
  (let [done (promise) out (atom []) waits (atom [])]
    (ev/eval! e code (assoc opts
                            :on-output (fn [k s] (swap! out conj [k s]))
                            :on-wait-input (fn [w] (swap! waits conj w))
                            :on-complete (fn [r] (deliver done r))))
    {:result (deref done 15000 :timeout) :out @out :waits @waits}))

(defn- wait-until!
  "Polls (pred) every 20ms, up to ~5s, for a background worker to reach a
  point a test needs to synchronize on (waiting-for-input? becoming
  true)."
  [pred]
  (loop [n 0]
    (when (and (< n 250) (not (pred)))
      (Thread/sleep 20)
      (recur (inc n)))))

(deftest run-then-private-call-live
  (let [e (make)
        file (.getCanonicalPath (io/file (:dir *srv*) "src/demo/core.clj"))
        {:keys [result]} (run e clj-server/fixture-core-text {:run? true :path file})]
    (is (= :ok (:status result)))
    (is (= 'demo.core (ev/prompt-ns e)) "the prompt moves to the buffer's own namespace")
    (let [{:keys [result]} (run e "(secret)" {:run? false :ns-source clj-server/fixture-core-text})]
      (is (= :ok (:status result)))
      (is (= ":private-ok" (:printed result))))))

(deftest not-loaded-live
  (let [e (make)
        {:keys [result]} (run e "(+ 1 2)" {:run? false :ns-source "(ns demo.never)\n(+ 1 2)"})]
    (is (= {:status :notice :text (clj/not-loaded-message "demo.never")} result))))

(deftest output-streams-live
  (let [e (make)
        events (atom [])
        done (promise)]
    (ev/eval! e "(do (println :a) (flush) (Thread/sleep 1000) (println :b) (flush) 42)"
              {:run? false
               :on-output (fn [k s] (swap! events conj [(System/currentTimeMillis) k s]))
               :on-complete (fn [r] (deliver done r))})
    (let [result (deref done 15000 :timeout)
          at (fn [text] (first (keep (fn [[t k s]] (when (and (= :out k) (= text s)) t)) @events)))
          a-at (at ":a\n")
          b-at (at ":b\n")]
      (is (= :ok (:status result)))
      (is (= "42" (:printed result)))
      (is (some? a-at) "the :a output arrived")
      (is (some? b-at) "the :b output arrived")
      (is (>= (- b-at a-at) 900) (str "b arrived " (- b-at a-at) " ms after a")))))

(deftest stop-busy-loop-live
  (let [e (make)
        done (promise)]
    (ev/eval! e "(loop [i 0] (recur (inc i)))" {:run? false :on-complete (fn [r] (deliver done r))})
    (Thread/sleep 300)
    (let [start (System/nanoTime)]
      (ev/interrupt! e)
      (is (= :interrupted (:status (deref done 1500 :timeout))))
      (is (< (/ (- (System/nanoTime) start) 1e6) 1500.0) "interrupted well under 1500 ms"))
    (is (= {:status :ok :printed "3" :ns 'user} (:result (run e "(+ 1 2)" {:run? false})))
        "the next eval answers on the same connection")))

(deftest stop-during-read-line-live
  (let [e (make)
        done (promise)]
    (ev/eval! e "(read-line)" {:run? false :on-complete (fn [r] (deliver done r))})
    (wait-until! (fn [] (ev/waiting-for-input? e)))
    (is (true? (ev/waiting-for-input? e)))
    (ev/interrupt! e)
    (is (= :interrupted (:status (deref done 2000 :timeout))))
    (is (false? (ev/waiting-for-input? e)) "the console leaves its input-waiting state")))

(deftest stdin-live
  (let [e (make)
        waits (atom [])
        done (promise)]
    (ev/eval! e "(read-line)" {:run? false
                               :on-wait-input (fn [w] (swap! waits conj w))
                               :on-complete (fn [r] (deliver done r))})
    (wait-until! (fn [] (ev/waiting-for-input? e)))
    (is (true? (ev/waiting-for-input? e)))
    (is (nil? (ev/send-input! e "typed")))
    (is (= {:status :ok :printed "\"typed\"" :ns 'user} (deref done 2000 :timeout)))
    (is (false? (ev/waiting-for-input? e)))))

(deftest server-stopped-leaves-no-port-file-live
  (let [srv (clj-server/start!)]
    (clj-server/stop! srv)
    (is (not (.exists (io/file (:dir srv) ".nrepl-port"))))))
