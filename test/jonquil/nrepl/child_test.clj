(ns jonquil.nrepl.child-test
  "Spawns real `jolt nrepl-server` children (jolt on PATH; the injected
  middleware from child/middleware-root). Every child a test starts, the
  test kills by its own pid."
  (:require [clojure.java.io :as io]
            [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jolt.process :as p]
            [jonquil.nrepl.child :as child]
            [jonquil.nrepl.client :as client]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- force-kill!
  "Cleanup for a test whose fix isn't in place yet: SIGTERM, then
  SIGKILL if it's still alive after a short wait. Only touches the proc
  this test itself captured."
  [proc]
  (when (and proc (p/alive? proc))
    (p/destroy proc)
    (when (= ::alive (deref proc 1000 ::alive))
      (.destroyForcibly ^java.lang.Process (:proc proc)))))

(defn- wait-until [pred ms]
  (loop [n 0] (cond (pred) true (> (* n 50) ms) false :else (do (Thread/sleep 50) (recur (inc n))))))

(defn temp-project
  "A throwaway project: deps.edn {:paths [\"src\"]} and src/demo/core.clj."
  []
  (let [d (io/file (temp-dirs/temp-dir! "jonquil-nrepl-test-"))]
    (.mkdirs (io/file d "src/demo"))
    (spit (io/file d "deps.edn") "{:paths [\"src\"]}")
    (spit (io/file d "src/demo/core.clj") "(ns demo.core)\n\n(defn twice\n  \"Doubles x.\"\n  [x]\n  (* 2 x))\n")
    (.getCanonicalPath d)))

(deftest launch-args-test
  (is (= ["jolt" "-Sdeps"
          "{:deps {nrepl/nrepl {:local/root \"/r/nrepl\"}} :nrepl/middleware [nrepl.middleware/default-middleware]}"
          "nrepl-server" "7950"]
         (child/launch-args 7950 "/r/nrepl")))
  (is (= ["jolt" "nrepl-server" "7951"] (child/launch-args 7951 nil))))

(deftest middleware-root-test
  (is (some? (child/middleware-root)) "these tests need the jolt-lang/nrepl checkout (JONQUIL_JOLT_NREPL or ~/dev/jolt-lang/nrepl)"))

(deftest port-free-test
  (with-open [_ (java.net.ServerSocket. 7990 1 (java.net.InetAddress/getByName "0.0.0.0"))]
    (is (not (child/port-free? 7990)) "held on the wildcard address"))
  (with-open [_ (java.net.ServerSocket. 7991 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (is (not (child/port-free? 7991)) "held on loopback")))

(deftest start-answers-and-kill-cleans-up-test
  (let [dir (temp-project)
        _ (spit (io/file dir ".nrepl-port") "4242\n")
        c (child/start! dir (child/middleware-root))]
    (try
      (is (child/alive? c))
      (is (= (str (:port c)) (str/trim (slurp (io/file dir ".nrepl-port")))) "the child writes its port")
      (let [conn (client/connect (:port c))]
        (is (contains? (set (keys (:ops (first (client/request-sync conn {:op "describe"} 5000))))) "interrupt"))
        (client/close! conn))
      (finally
        (is (= 143 (child/kill! c)) "SIGTERM")))
    (is (not (child/alive? c)))
    (is (= "4242\n" (slurp (io/file dir ".nrepl-port"))) "the .nrepl-port that was there is back")
    (is (not (.exists (io/file (:log c)))) "the log is deleted")))

(deftest kill-escalates-on-a-stopped-child-test
  (let [c (child/start! (temp-project) nil)]
    (clojure.java.shell/sh "kill" "-STOP" (str (:pid c)))
    (is (= 137 (child/kill! c)) "SIGKILL after the SIGTERM grace")
    (is (not (child/alive? c)))
    (is (not (.exists (io/file (:dir c) ".nrepl-port"))) "no stale port file")))

(deftest a-failed-bind-moves-to-the-next-port-test
  (let [held (java.net.ServerSocket. child/port-base 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (try
      ;; Pretend the scan's probe missed the holder, so the child's own bind
      ;; is the one that fails.
      (with-redefs [child/port-free? (constantly true)]
        (let [c (child/start! (temp-project) nil)]
          (try (is (= (inc child/port-base) (:port c)))
               (finally (child/kill! c)))))
      (finally (.close held)))))

(deftest relative-dir-is-made-absolute-test
  (let [dir (temp-project)
        rel (str (.relativize (.toPath (io/file (.getCanonicalPath (io/file "."))))
                              (.toPath (io/file dir))))
        c (child/start! rel nil)]
    (try (is (= dir (:dir c)))
         (finally (child/kill! c)))))

(deftest start-timeout-kills-and-restores-test
  (let [dir (temp-project)
        _ (spit (io/file dir ".nrepl-port") "9999\n")
        real-process p/process
        captured (atom nil)
        log (atom nil)]
    (try
      (with-redefs [child/started? (constantly false)
                    child/ready-deadline-ms 500
                    p/process (fn [& args]
                                (let [proc (apply real-process args)]
                                  (reset! captured proc)
                                  proc))]
        (let [ex (try (child/start! dir nil) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "start! throws")
          (reset! log (:log (ex-data ex)))))
      (is (and @log (.exists (io/file @log))) "the log stays as the post-mortem")
      (let [pid (.pid ^java.lang.Process (:proc @captured))]
        (is (wait-until #(not (.isPresent (java.lang.ProcessHandle/of pid))) 3000)
            "the child is dead within about 3 s of the throw"))
      (is (= "9999\n" (slurp (io/file dir ".nrepl-port"))) "the seeded .nrepl-port is back, byte-identical")
      (finally (force-kill! @captured)
               (when @log (io/delete-file @log true))))))

(deftest start-exited-restores-test
  (let [dir (temp-project)
        _ (spit (io/file dir ".nrepl-port") "5555\n")
        real-process p/process
        captured (atom nil)
        log (atom nil)]
    (try
      (with-redefs [p/process (fn [& args]
                                (let [proc (apply real-process args)]
                                  (reset! captured proc)
                                  proc))
                    ;; Simulates an unexpected exit: the real child starts and
                    ;; answers normally, but wait-ready is told it exited, on
                    ;; a path that isn't the bind-failure retry.
                    child/wait-ready (fn [_proc _log _port _deadline-ms] :exited)]
        (let [ex (try (child/start! dir nil) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "start! throws")
          (is (str/includes? (str (ex-message ex)) "jolt nrepl-server exited"))
          ;; The log name only: wait-ready answers :exited before the
          ;; child has even opened it, so the file can appear after this
          ;; throw. It's deleted in the finally, once the child is dead.
          (reset! log (:log (ex-data ex)))))
      (is (wait-until #(not (p/alive? @captured)) 3000) "the child is dead")
      (is (= "5555\n" (slurp (io/file dir ".nrepl-port"))) "the seeded .nrepl-port is back")
      (finally (force-kill! @captured)
               (when @log (io/delete-file @log true))))))
