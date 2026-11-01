(ns jonquil.nrepl.break-middleware-test
  "The Jolt child's break middleware, run in this process with a fake
  jolt.debug break (jonquil.debugger/*break-fn*)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [jonquil.debugger :as debugger]
            [jonquil.nrepl.break-middleware :as mw]))

(def ^:private fake-frames
  [{:fn 'user/bi :file nil :line 1 :locals {'n 21}}])

(defn- fake-break [handler thunk]
  (try (thunk) (catch Throwable t (handler t fake-frames) (throw t))))

(defn- wait-for [pred] (loop [n 0] (cond (pred) true (> n 150) false :else (do (Thread/sleep 20) (recur (inc n))))))

(defn- setup
  "A handler over a fresh sessions atom whose next records requests and, on
  clone, answers new-session \"s1\" and keeps the session break it saw
  bound, as jolt-lang/nrepl's session middleware starts its worker under
  that binding. {:h :sessions :seen :entry}."
  []
  (let [sessions (atom {})
        seen (atom [])
        entry (atom nil)
        h (mw/handler sessions (fn [req]
                                 (swap! seen conj (get req "op"))
                                 (when (= "clone" (get req "op"))
                                   (reset! entry mw/*session-break*)
                                   ((:reply req) {"new-session" "s1" "status" ["done"]}))))]
    (h {"op" "clone" :reply (fn [_])})
    {:h h :sessions sessions :seen seen :entry @entry}))

(defn- ask
  "Sends request through h; the replies it gets, waiting up to 3 s for one
  with a status."
  [h request]
  (let [replies (atom [])]
    (h (assoc request :reply (fn [m] (swap! replies conj m))))
    (wait-for #(some (fn [m] (get m "status")) @replies))
    @replies))

(defn- eval-under
  "Runs the wrapped (f code) on a future under the session break e, as the
  session's worker would. The future yields the value or the throwable."
  [e f]
  (let [w (mw/wrap f)]
    (future (binding [mw/*session-break* e]
              (try (w "code") (catch Throwable t t))))))

(deftest clone-binds-a-session-break-test
  (let [{:keys [sessions entry]} (setup)]
    (is (some? entry))
    (is (identical? entry (get @sessions "s1")))))

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
        (is (empty? @sent)))
      (testing "an eval-string inside an armed one passes straight through"
        (h {"op" "eval" "session" "s1" "jonquil-break" "1" :reply (fn [m] (swap! sent conj m))})
        (let [inner (mw/wrap (fn [_] (/ 1 0)))
              fut (eval-under entry (fn [_] (try (inner "nested") (catch ArithmeticException _ :caught-inside))))]
          (is (= :caught-inside (deref fut 3000 :blocked)))
          (is (empty? @sent)))))))

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
  (with-redefs [debugger/*break-fn* fake-break]
    (let [{:keys [h]} (setup)]
      (is (= [{"status" ["done"] "available" "true"}] (ask h {"op" "jonquil-break-check" "session" "s1"}))))))
