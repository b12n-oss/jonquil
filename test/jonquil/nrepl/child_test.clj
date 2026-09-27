(ns jonquil.nrepl.child-test
  "Spawns real `jolt nrepl-server` children (jolt on PATH; the injected
  middleware from child/middleware-root). Every child a test starts, the
  test kills by its own pid."
  (:require [clojure.java.io :as io]
            [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.nrepl.child :as child]
            [jonquil.nrepl.client :as client]))

(defn temp-project
  "A throwaway project: deps.edn {:paths [\"src\"]} and src/demo/core.clj."
  []
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "jonquil-nrepl-test-" (System/nanoTime)))]
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
