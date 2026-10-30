(ns jonquil.runtime-registry-test
  "stop-all! is jonquil.main's own guard against the process-exit path
  orphaning a child: a start still mid-spawn when it runs must still be
  torn down, and stop-all! must not return before that's actually true."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jolt.process :as p]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.child :as child]
            [jonquil.nrepl.child-test :refer [temp-project]]
            [jonquil.nrepl.evaluator :as nrepl]
            [jonquil.nrepl.jank :as jank-eval]
            [jonquil.nrepl.jank-child :as jank-child]
            [jonquil.runtime-registry :as registry]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- make [dir]
  (ev/make {:profile dialect/jolt :runtime :nrepl :project-dir dir}))

(deftest stop-all-tears-down-a-mid-cold-start-child-test
  (let [e (make (temp-project))
        real-start child/start!
        captured (atom nil)]
    (try
      (with-redefs [child/start! (fn [d root]
                                   ;; Same shape as evaluator-test's own
                                   ;; cold-start probe: the spawn itself is
                                   ;; slow, so stop-all! (called below,
                                   ;; while this is still sleeping) has to
                                   ;; wait for it, not just observe a nil
                                   ;; child-pid it hasn't set yet.
                                   (Thread/sleep 5000)
                                   (let [c (real-start d root)]
                                     (reset! captured c)
                                     c))]
        (registry/register! e)
        (let [start-fut (future (ev/start! e))]
          (Thread/sleep 500) ;; start-runtime! is now blocked in child/start!, holding start-lock
          (registry/stop-all!)
          (is (some? @captured) "stop-all! waited long enough for the delayed start to spawn its child")
          (is (not (child/alive? @captured)) "and for it to die, not to be left orphaned")
          (is (nil? (nrepl/child-pid e)))
          @start-fut))
      (finally
        (registry/unregister! e)
        (when (and @captured (child/alive? @captured)) (child/kill! @captured))))))

(deftest window-close-order-during-start-leaves-no-child-test
  ;; close-request-fn's own order is stop!, then
  ;; registry/unregister! in a finally, then (later, from jonquil.main)
  ;; registry/stop-all!. Reproduces that exact order against a start still
  ;; mid-spawn, and asserts stop-all! doesn't return before the delayed
  ;; start's own child has actually spawned and died -- unregister! must
  ;; not have dropped e from the registry while it was still unsettled.
  (let [e (make (temp-project))
        real-start child/start!
        captured (atom nil)]
    (try
      (with-redefs [child/start! (fn [d root]
                                   (Thread/sleep 5000)
                                   (let [c (real-start d root)]
                                     (reset! captured c)
                                     c))]
        (registry/register! e)
        (future (ev/start! e))
        (Thread/sleep 500) ;; start-runtime! is now blocked in child/start!, holding start-lock
        ;; The window's exact close order (close-request-fn).
        (try (ev/stop! e) (finally (registry/unregister! e)))
        (registry/stop-all!)
        (is (some? @captured) "stop-all! waited long enough for the delayed start to spawn its child")
        (is (not (child/alive? @captured)) "and for it to die, not to be left orphaned"))
      (finally
        (registry/unregister! e)
        (when (and @captured (child/alive? @captured)) (child/kill! @captured))))))

(deftest stop-all-only-touches-registered-evaluators-test
  (let [e (make (temp-project))
        untouched (make (temp-project))]
    (try
      (ev/start! e)
      (ev/start! untouched)
      (registry/register! e)
      ;; untouched is deliberately never registered.
      (let [untouched-pid (nrepl/child-pid untouched)]
        (registry/stop-all!)
        (is (nil? (nrepl/child-pid e)) "the registered evaluator is stopped")
        (is (= untouched-pid (nrepl/child-pid untouched)) "an unregistered evaluator is left alone")
        ;; A second call, with e already settled, still returns promptly
        ;; and without incident.
        (registry/stop-all!)
        (is (nil? (nrepl/child-pid e))))
      (finally
        (registry/unregister! e)
        (ev/stop! e)
        (ev/stop! untouched)))))

(deftest stop-all-twice-is-harmless-test
  ;; A normal quit runs stop-all! from -main and again from the shutdown
  ;; hook at System/exit.
  (let [e (make (temp-project))]
    (try
      (ev/start! e)
      (registry/register! e)
      (let [pid (nrepl/child-pid e)]
        (is (some? pid) "the child is up before the first stop-all!")
        (is (nil? (registry/stop-all!)))
        (is (nil? (registry/stop-all!)) "the second call returns nil without throwing")
        (is (nil? (nrepl/child-pid e)) "the child is gone"))
      (finally
        (registry/unregister! e)
        (ev/stop! e)))))

(defn- jank-run-dirs []
  (set (filter #(re-matches #"jonquil-jank-run-.+" %)
               (seq (.list (io/file (System/getProperty "java.io.tmpdir")))))))

(defn- pgrep? [pattern]
  (let [r (p/sh "pgrep" "-f" pattern)]
    (boolean (and (zero? (:exit r)) (not (str/blank? (:out r)))))))

(deftest quit-while-jank-starts-leaves-nothing-behind-test
  ;; the window's close order (stop!,
  ;; unregister!, stop-all!) used to find a Jank evaluator "settled" while
  ;; its launch was still polling for the port file, so System/exit could
  ;; cut the launch off with the process and run dir still there. The
  ;; stand-in never writes a port file, so the launch stays in flight.
  (let [before (jank-run-dirs)
        bin (str (temp-dirs/temp-dir! "jonquil-jank-test-") "/quit-jank")
        _ (do (spit bin "#!/usr/bin/env bash\nsleep 60\n")
              (.setExecutable (io/file bin) true))
        launched (promise)
        e (jank-eval/make-jank {:profile dialect/jank :runtime :launch :project-dir nil}
                               {:find-binary (fn [] bin)
                                ;; Slow to spawn, then the real launcher, so
                                ;; stop-all! must outwait the whole launch.
                                :launch (fn [o]
                                          (Thread/sleep 300)
                                          (try (jank-child/start! o)
                                               (finally (deliver launched true))))})]
    (try
      (registry/register! e)
      (ev/eval! e "(+ 1 2)" {:on-output (fn [_ _]) :on-complete (fn [_])})
      (Thread/sleep 100) ;; the launch is now sleeping before its spawn
      (try (ev/stop! e) (finally (registry/unregister! e)))
      (registry/stop-all!)
      (is (realized? launched) "stop-all! outwaited the launch")
      (is (not (pgrep? "quit-jank")) "no process left")
      (is (= before (jank-run-dirs)) "no run dir left")
      (finally
        (registry/unregister! e)
        (ev/stop! e)))))
