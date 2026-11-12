(ns jonquil.nrepl.break-middleware-test
  "The Jolt child's break middleware, run in this process with a fake
  jolt.debug break (jonquil.debugger/*break-fn*)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [jolt.nrepl :as server]
            [jonquil.breakpoints :as bp]
            [jonquil.debugger :as debugger]
            [jonquil.nrepl.break-middleware :as mw]))

(def ^:private fake-frames
  [{:fn 'user/bi :file nil :line 1 :locals {'n 21}}])

(defn- fake-break [handler thunk]
  (try (thunk) (catch Throwable t (handler t fake-frames) (throw t))))

(defn- wait-for [pred] (loop [n 0] (cond (pred) true (> n 150) false :else (do (Thread/sleep 20) (recur (inc n))))))

(defn- setup
  "A handler over a fresh sessions atom whose next records requests and, on
  clone, answers new-session \"s1\" and keeps the eval wrapper it saw
  bound, as jolt-lang/nrepl's session middleware starts its worker under
  that binding. {:h :sessions :seen :entry :wrapper}."
  []
  (let [sessions (atom {})
        seen (atom [])
        wrapper (atom nil)
        h (mw/handler sessions (fn [req]
                                 (swap! seen conj (get req "op"))
                                 (when (= "clone" (get req "op"))
                                   (reset! wrapper (some-> (mw/hook-var) deref))
                                   ((:reply req) {"new-session" "s1" "status" ["done"]}))))]
    (h {"op" "clone" :reply (fn [_])})
    {:h h :sessions sessions :seen seen :entry (get @sessions "s1") :wrapper @wrapper}))

(defn- ask
  "Sends request through h; the replies it gets, waiting up to 3 s for one
  with a status."
  [h request]
  (let [replies (atom [])]
    (h (assoc request :reply (fn [m] (swap! replies conj m))))
    (wait-for #(some (fn [m] (get m "status")) @replies))
    @replies))

(defn- eval-under
  "Runs (f code) on a future through session e's eval wrapper, as
  jolt.nrepl/evaluate does on the session's worker. The future yields the
  value or the throwable."
  [e f]
  (future (try ((mw/session-wrapper e) (fn [] (f "code"))) (catch Throwable t t))))

(deftest clone-binds-the-session-wrapper-test
  (let [{:keys [entry wrapper]} (setup)]
    (is (some? entry))
    (if (mw/hook-var)
      (is (fn? wrapper) "the clone's worker gets the session's wrapper")
      (is (nil? wrapper) "a Jolt without the hook gets nothing bound"))))

(deftest eval-arms-only-when-asked-test
  (let [{:keys [h entry seen]} (setup)]
    (h {"op" "eval" "session" "s1" "jonquil-break" "1" :reply (fn [_])})
    (is (true? @(:armed? entry)))
    (h {"op" "eval" "session" "s1" :reply (fn [_])})
    (is (false? @(:armed? entry)))
    (h {"op" "load-file" "session" "s1" "jonquil-break" "1" :reply (fn [_])})
    (is (true? @(:armed? entry)))
    (is (= ["clone" "eval" "eval" "load-file"] @seen) "evals go on to the session middleware")))

(deftest an-armed-eval-pauses-and-continues-test
  (with-redefs [debugger/*break-fn* fake-break]
    (let [{:keys [h entry]} (setup)
          sent (atom [])
          boom (ex-info "boom" {})]
      (h {"op" "eval" "session" "s1" "jonquil-break" "1" :reply (fn [m] (swap! sent conj m))})
      (let [fut (eval-under entry (fn [_] (throw boom)))]
        (is (wait-for #(some (fn [m] (get m "jonquil-paused")) @sent)))
        (let [data (edn/read-string (some (fn [m] (get m "jonquil-paused")) @sent))]
          (is (= "ExceptionInfo: boom {}" (:title data)))
          (is (= [{:name "n" :text "21"}] (:locals (first (:frames data))))))
        (is (= [{"status" ["done"] "frame-out" "" "text" "42"}]
               (ask h {"op" "jonquil-break-eval" "session" "s1" "frame" 0 "code" "(* n 2)"})))
        (is (= [{"status" ["done"] "continued" "true"}] (ask h {"op" "jonquil-break-continue" "session" "s1"})))
        (is (identical? boom (deref fut 3000 :blocked)))
        (is (wait-for #(some (fn [m] (= "continued" (get m "jonquil-resumed"))) @sent)))
        (is (= [{"status" ["done"] "continued" "false"}] (ask h {"op" "jonquil-break-continue" "session" "s1"})))
        (is (= [{"status" ["done"] "error" "Not paused any more."}]
               (ask h {"op" "jonquil-break-eval" "session" "s1" "frame" 0 "code" "n"})))))))

(deftest unarmed-and-nested-never-pause-test
  (with-redefs [debugger/*break-fn* fake-break]
    (let [{:keys [h entry]} (setup)
          sent (atom [])]
      (testing "an eval without jonquil-break"
        (h {"op" "eval" "session" "s1" :reply (fn [m] (swap! sent conj m))})
        (is (instance? ArithmeticException (deref (eval-under entry (fn [_] (/ 1 0))) 3000 :blocked)))
        (is (empty? @sent))))))

(deftest interrupt-ends-a-pause-test
  (with-redefs [debugger/*break-fn* fake-break]
    (let [{:keys [h entry seen]} (setup)
          sent (atom [])]
      (h {"op" "eval" "session" "s1" "jonquil-break" "1" :reply (fn [m] (swap! sent conj m))})
      (let [fut (eval-under entry (fn [_] (/ 1 0)))]
        (is (wait-for #(some (fn [m] (get m "jonquil-paused")) @sent)))
        (h {"op" "interrupt" "session" "s1" :reply (fn [_])})
        (is (instance? InterruptedException (deref fut 3000 :blocked)))
        (is (wait-for #(some (fn [m] (= "stopped" (get m "jonquil-resumed"))) @sent)))
        (is (= "interrupt" (last @seen)) "interrupt goes on to the session middleware")))))

(deftest check-test
  (with-redefs [debugger/*break-fn* fake-break
                mw/hooked? (constantly true)]
    (let [{:keys [h]} (setup)]
      (is (= [{"status" ["done"] "available" "true"}] (ask h {"op" "jonquil-break-check" "session" "s1"}))))))

(deftest arming-ends-with-the-eval-test
  ;; an armed eval disarms its session when it ends, so nothing else that
  ;; runs on the worker afterwards counts as the person's eval
  (with-redefs [debugger/*break-fn* fake-break]
    (let [{:keys [h entry]} (setup)]
      (h {"op" "eval" "session" "s1" "jonquil-break" "1" :reply (fn [_])})
      (is (= 3 (deref (eval-under entry (fn [_] 3)) 3000 :blocked)))
      (is (false? @(:armed? entry)))
      (testing "and when it ends in a continued pause"
        (h {"op" "eval" "session" "s1" "jonquil-break" "1" :reply (fn [_])})
        (let [fut (eval-under entry (fn [_] (/ 1 0)))]
          (is (wait-for #(some? @(:paused (:break entry)))))
          (debugger/continue! (:break entry))
          (is (instance? ArithmeticException (deref fut 3000 :blocked)))
          (is (false? @(:armed? entry)))
          (is (nil? @(:paused (:break entry)))))))))

(deftest the-hook-is-checked-test
  ;; the break needs jolt.nrepl/*eval-wrapper*; the check says so without it
  (is (= (some? (resolve 'jolt.nrepl/*eval-wrapper*)) (mw/hooked?)))
  (with-redefs [debugger/*break-fn* fake-break
                mw/hooked? (constantly false)]
    (let [{:keys [h]} (setup)
          [r] (ask h {"op" "jonquil-break-check" "session" "s1"})]
      (is (= "false" (get r "available")))
      (is (re-find #"\*eval-wrapper\*" (get r "reason"))))))

(deftest evaluate-pauses-through-the-hook-test
  ;; jolt.nrepl/evaluate itself, with the session's wrapper bound as clone
  ;; binds it: an armed eval's throw pauses before evaluate catches it
  (if-let [v (mw/hook-var)]
    (with-redefs [debugger/*break-fn* fake-break]
      (let [{:keys [h entry wrapper]} (setup)
            sent (atom [])]
        (h {"op" "eval" "session" "s1" "jonquil-break" "1" :reply (fn [m] (swap! sent conj m))})
        ;; the wrapper clone bound, so a clone binding another session's
        ;; wrapper fails here
        (let [fut (future (with-bindings {v wrapper}
                            (server/evaluate "(/ 1 0)" "user" {:out (fn [_])})))]
          (is (wait-for #(some (fn [m] (get m "jonquil-paused")) @sent)))
          (debugger/continue! (:break entry))
          (is (some? (:err (deref fut 3000 :blocked))) "evaluate still reports the error"))))
    (println "SKIP evaluate-pauses-through-the-hook-test: this Jolt has no jolt.nrepl/*eval-wrapper*")))

(deftest check-without-a-break-test
  (with-redefs [debugger/break-available? (constantly false)]
    (let [{:keys [h]} (setup)]
      (is (= [{"status" ["done"] "available" "false"}] (ask h {"op" "jonquil-break-check" "session" "s1"}))))))

(deftest break-ops-without-a-session-or-a-pause-test
  (with-redefs [debugger/*break-fn* fake-break]
    (let [{:keys [h seen]} (setup)]
      (is (= [{"status" ["done"] "error" "Not paused any more."}]
             (ask h {"op" "jonquil-break-eval" "session" "nope" "frame" 0 "code" "n"})))
      (is (= [{"status" ["done"] "error" "Not paused any more."}]
             (ask h {"op" "jonquil-break-eval" "session" "s1" "frame" 0 "code" "n"})) "a known session that isn't paused")
      (is (= [{"status" ["done"] "continued" "false"}]
             (ask h {"op" "jonquil-break-continue" "session" "nope"})))
      (h {"op" "interrupt" "session" "nope" :reply (fn [_])})
      (is (= "interrupt" (last @seen)) "interrupt goes on without a session"))))

(deftest close-forgets-the-session-test
  (with-redefs [debugger/*break-fn* fake-break]
    (let [{:keys [h sessions seen]} (setup)]
      (is (contains? @sessions "s1"))
      (h {"op" "close" "session" "s1" :reply (fn [_])})
      (is (not (contains? @sessions "s1")))
      (is (= "close" (last @seen)) "close goes on to the session middleware")
      (h {"op" "describe" :reply (fn [_])})
      (is (= "describe" (last @seen)) "any other op goes on"))))

;; --- breakpoints (LM5), with the real jolt.debug ---------------------------------

(def ^:private adder (bp/instrument "(fn [x]\n  (let [y (* x 2)]\n    (+ y 1)))" [3]))

(deftest breakpoints-op-arms-and-disarms-test
  (if-not (debugger/break-available?)
    (is true "skipped: this Jolt has no jolt.debug")
    (let [{:keys [h entry]} (setup)]
      (try
        (let [[r] (ask h {"op" "jonquil-breakpoints" "session" "s1" "on" "true"})]
          (is (= "true" (get r "on")))
          (is (identical? (:hook entry) @debugger/breakpoint-hook)))
        (is (= "false" (get (first (ask h {"op" "jonquil-breakpoints" "session" "s1" "on" "false"})) "on")))
        (is (nil? @debugger/breakpoint-hook))
        (finally (reset! debugger/breakpoint-hook nil) (debugger/set-debug-mode! false))))))

(deftest a-marked-eval-pauses-at-a-breakpoint-test
  (if-not (debugger/break-available?)
    (is true "skipped: this Jolt has no jolt.debug")
    (let [{:keys [h entry]} (setup)
          sent (atom [])]
      (try
        (h {"op" "eval" "session" "s1" "jonquil-breakpoints" "1" :reply (fn [m] (swap! sent conj m))})
        (is (identical? (:hook entry) @debugger/breakpoint-hook) "the marked eval armed the hook")
        (let [f (eval (read-string (:text adder)))
              fut (eval-under entry (fn [_] (f 20)))]
          (is (wait-for #(some (fn [m] (get m "jonquil-paused")) @sent)))
          (let [data (edn/read-string (some (fn [m] (get m "jonquil-paused")) @sent))]
            (is (= "Breakpoint at line 3" (:title data)))
            (is (some #(= {:name "y" :text "40"} %) (:locals (first (:frames data))))))
          (is (= [{"status" ["done"] "continued" "true"}] (ask h {"op" "jonquil-break-continue" "session" "s1"})))
          (is (= 41 (deref fut 5000 :blocked)))
          (testing "after the eval, a breakpoint reached on the session runs on"
            (is (= 41 (deref (eval-under entry (fn [_] (f 20))) 5000 :blocked)))))
        (finally (reset! debugger/breakpoint-hook nil) (debugger/set-debug-mode! false))))))

(deftest a-future-paused-when-its-eval-ends-is-woken-test
  (if-not (debugger/break-available?)
    (is true "skipped: this Jolt has no jolt.debug")
    (let [{:keys [h entry]} (setup)
          sent (atom [])]
      (try
        (h {"op" "eval" "session" "s1" "jonquil-breakpoints" "1" :reply (fn [m] (swap! sent conj m))})
        (let [f (eval (read-string (:text adder)))
              fut (promise)
              ev (eval-under entry (fn [_]
                                     (deliver fut (future (f 20)))
                                     (loop [i 0] (when (and (< i 250) (not (some #(get % "jonquil-paused") @sent)))
                                                   (Thread/sleep 20) (recur (inc i))))
                                     :eval-done))]
          (is (= :eval-done (deref ev 5000 :blocked)))
          (is (= 41 (deref @fut 5000 :blocked))
              "the eval's end wakes the future it left paused, which the window can no longer continue"))
        (finally (reset! debugger/breakpoint-hook nil) (debugger/set-debug-mode! false))))))

(deftest breakpoints-op-answers-heads-test
  (let [{:keys [h]} (setup)
        [r] (ask h {"op" "jonquil-breakpoints" "session" "s1" "ns" "user" "heads" (pr-str ["when" "nope"])})]
    (is (= {:heads {"when" {:var "clojure.core/when" :macro true} "nope" nil} :ns-found? true}
           (edn/read-string (get r "heads"))))))

(deftest a-marked-eval-answers-whether-it-armed-test
  (let [{:keys [h]} (setup)
        sent (atom [])]
    (with-redefs [debugger/break-available? (constantly false)]
      (h {"op" "eval" "session" "s1" "jonquil-breakpoints" "1" :reply (fn [m] (swap! sent conj m))})
      (is (string? (some #(get % "jonquil-breakpoints-error") @sent)) "on its own reply, before the eval runs"))
    (when (debugger/break-available?)
      (try
        (reset! sent [])
        (h {"op" "eval" "session" "s1" "jonquil-breakpoints" "1" :reply (fn [m] (swap! sent conj m))})
        (is (= "true" (some #(get % "jonquil-breakpoints-armed") @sent)))
        (finally (reset! debugger/breakpoint-hook nil) (debugger/set-debug-mode! false))))))

(deftest breakpoints-op-on-a-jolt-without-jolt-debug-test
  (with-redefs [debugger/break-available? (constantly false)]
    (let [{:keys [h]} (setup)
          [r] (ask h {"op" "jonquil-breakpoints" "session" "s1" "on" "true"})]
      (is (string? (get r "error")) "arming says why, so the window sends no breakpoint code")
      (is (nil? @debugger/breakpoint-hook)))))
