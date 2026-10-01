(ns jonquil.runtime-registry-test
  "stop-all! is jonquil.main's own guard against the process-exit path
  orphaning a child: a start still mid-spawn when it runs must still be
  torn down, and stop-all! must not return before that's actually true."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.child :as child]
            [jonquil.nrepl.child-test :refer [temp-project]]
            [temp-dirs]
            [jonquil.nrepl.evaluator :as nrepl]
            [jonquil.runtime-registry :as registry]))

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
  ;; C1 (phase-close review): close-request-fn's own order is stop!, then
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
