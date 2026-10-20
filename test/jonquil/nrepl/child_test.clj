(ns jonquil.nrepl.child-test
  "Spawns real Jolt nREPL server children (jolt on PATH; the injected
  middleware from child/middleware-root). Every child a test starts, the
  test kills by its own pid."
  (:require [clojure.java.io :as io]
            [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jolt.process :as p]
            [jonquil.login-path :as lp]
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

(defn- with-login-path
  "Runs f with login-path answering {:path path :source :process}: the PATH
  holds a well-known dir (the fake home's .local/bin), so start! needs no
  shell. Resets login-path before and after."
  [path f]
  (lp/reset-for-test!)
  (try (lp/start! {:env {"PATH" path} :home "/fake-home-for-test"})
       (f)
       (finally (lp/reset-for-test!))))

(deftest launch-args-test
  (with-login-path "/nonexistent-jonquil:/fake-home-for-test/.local/bin"
    (fn []
      (is (= ["jolt" "-Sdeps"
              "{:deps {nrepl/nrepl {:local/root \"/r/nrepl\"}}}"
              "-e" (child/server-form 7950 ["nrepl.middleware/default-middleware"])]
             (child/launch-args 7950 {:local "/r/nrepl"})))
      (is (= ["jolt" "-Sdeps"
              "{:deps {nrepl/nrepl {:git/url \"https://github.com/jolt-lang/nrepl.git\", :git/tag \"v0.4.0\", :git/sha \"7efdd6c\"}}}"
              "-e" (child/server-form 7952 ["nrepl.middleware/default-middleware"])]
             (child/launch-args 7952 {:git child/nrepl-git-coord})))
      (is (= ["jolt" "-e" (child/server-form 7951 [])] (child/launch-args 7951 nil))))))

(deftest server-form-test
  (let [form (read-string (child/server-form 7950 ["a/b" "c/d"]))]
    (is (= 'do (first form)) "it reads as one form")
    (is (str/includes? (child/server-form 7950 ["a/b" "c/d"])
                       "(jolt.nrepl/start 7950 '[a/b c/d] {:port-file nil})"))
    (is (str/includes? (child/server-form 7950 []) "System/in") "the parent-death watcher")))

(deftest closing-stdin-ends-the-child-test
  ;; What the OS does to the pipe when Jonquil dies without a hook.
  (let [c (child/start! (temp-project) nil)]
    (try
      (is (child/alive? c))
      (.close ^java.io.OutputStream (:in (:proc c)))
      (is (wait-until #(not (child/alive? c)) 5000) "the child exits on its own")
      (finally (child/kill! c)))))

(deftest a-killed-parent-takes-its-child-test
  ;; The real case: a Jolt process that started a child is SIGKILLed (no
  ;; hook can run), and the child goes too.
  (let [dir (temp-project)
        parent (p/process {:dir (.getCanonicalPath (io/file ".")) :out :pipe :err :out}
                          "jolt" "-e"
                          (str "(do (require 'jonquil.nrepl.child)"
                               " (let [c (jonquil.nrepl.child/start! " (pr-str dir) " nil)]"
                               " (println :child-pid (:pid c)) (flush))"
                               " (jolt.host/park-until-interrupt))"))
        rdr (io/reader (:out parent))
        line (loop [n 0] (let [l (.readLine ^java.io.BufferedReader rdr)]
                           (cond (nil? l) nil
                                 (str/starts-with? l ":child-pid") l
                                 (< n 200) (recur (inc n)))))
        pid (some-> line (str/split #" ") second parse-long)]
    (try
      (is pid (str "the parent printed its child's pid: " line))
      (when pid
        (is (.isPresent (java.lang.ProcessHandle/of pid)) "the child runs")
        (.destroyForcibly ^java.lang.Process (:proc parent))
        (is (wait-until #(not (.isPresent (java.lang.ProcessHandle/of pid))) 10000)
            "the child is gone after its parent's SIGKILL"))
      (finally
        (force-kill! parent)
        (when (and pid (.isPresent (java.lang.ProcessHandle/of pid)))
          (clojure.java.shell/sh "kill" "-9" (str pid)))))))

(deftest launch-args-with-the-inspector-test
  (with-login-path "/nonexistent-jonquil:/fake-home-for-test/.local/bin"
    (fn []
      (is (= ["jolt" "-Sdeps"
              "{:deps {nrepl/nrepl {:local/root \"/r/nrepl\"} jonquil/nrepl-middleware {:local/root \"/c/ins\"}}}"
              "-e" (child/server-form 7950 ["jonquil.nrepl.stdin-middleware/stdin" "nrepl.middleware/default-middleware"
                                            "jonquil.nrepl.inspect-middleware/inspect" "jonquil.nrepl.info-middleware/jonquil-info"])]
             (child/launch-args 7950 {:local "/r/nrepl"} "/c/ins")))
      (is (= ["jolt" "-Sdeps"
              "{:deps {jonquil/nrepl-middleware {:local/root \"/c/ins\"}}}"
              "-e" (child/server-form 7951 ["jonquil.nrepl.inspect-middleware/inspect" "jonquil.nrepl.info-middleware/jonquil-info"])]
             (child/launch-args 7951 nil "/c/ins"))
          "a plain server still gets the inspector and jonquil-info, but not stdin")
      (is (= (child/launch-args 7952 {:local "/r/nrepl"}) (child/launch-args 7952 {:local "/r/nrepl"} nil))))))

(deftest child-uses-resolved-jolt-test
  (let [bin (io/file (temp-dirs/temp-dir! "jonquil-nrepl-test-") "bin")
        jolt (io/file bin "jolt")]
    (.mkdirs bin)
    (spit jolt "#!/bin/sh\n")
    (.setExecutable jolt true)
    (let [path (str (.getPath bin) ":/fake-home-for-test/.local/bin")]
      (with-login-path path
        (fn []
          (is (= [(.getPath jolt) "-e" (child/server-form 7951 [])] (child/launch-args 7951 nil)))
          (is (= {"PATH" path} (:extra-env (child/process-options {:dir "/d" :log "/l"})))))))))

(deftest middleware-root-test
  (is (some? (child/middleware-root)) "these tests need the jolt-lang/nrepl checkout (JONQUIL_JOLT_NREPL or ~/dev/jolt-lang/nrepl)"))

(deftest middleware-source-test
  (is (= {:local (child/middleware-root)} (child/middleware-source)) "the checkout first")
  (with-redefs [child/middleware-root (constantly nil)]
    (is (= {:git child/nrepl-git-coord} (child/middleware-source)) "else the pinned git dependency")))

(defn- hold-free-port
  "A ServerSocket the OS bound to a free port on addr, so no test names a
  fixed port that a just-exited child might still hold."
  [addr]
  (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName addr)))

(deftest port-free-test
  (with-open [ss (hold-free-port "0.0.0.0")]
    (is (not (child/port-free? (.getLocalPort ss))) "held on the wildcard address"))
  (with-open [ss (hold-free-port "127.0.0.1")]
    (is (not (child/port-free? (.getLocalPort ss))) "held on loopback")))

(deftest start-answers-and-kill-cleans-up-test
  (let [dir (temp-project)
        _ (spit (io/file dir ".nrepl-port") "4242\n")
        c (child/start! dir (child/middleware-source))]
    (try
      (is (child/alive? c))
      (is (= "4242\n" (slurp (io/file dir ".nrepl-port"))) "the child writes no port file over the user's")
      (let [conn (client/connect (:port c))]
        (is (contains? (set (keys (:ops (first (client/request-sync conn {:op "describe"} 5000))))) "interrupt"))
        (client/close! conn))
      (finally
        (is (= 143 (child/kill! c)) "SIGTERM")))
    (is (not (child/alive? c)))
    (is (= "4242\n" (slurp (io/file dir ".nrepl-port"))) "and the user's .nrepl-port is still there")
    (is (not (.exists (io/file (:log c)))) "the log is deleted")))

(deftest kill-escalates-on-a-stopped-child-test
  (let [c (child/start! (temp-project) nil)]
    (clojure.java.shell/sh "kill" "-STOP" (str (:pid c)))
    (is (= 137 (child/kill! c)) "SIGKILL after the SIGTERM grace")
    (is (not (child/alive? c)))
    (is (not (.exists (io/file (:dir c) ".nrepl-port"))) "no port file at all")))

(deftest a-failed-bind-moves-to-the-next-port-test
  ;; The scan starts at a port this test holds (one the OS picked, never
  ;; the real 7950), so the first bind always fails.
  (let [held (hold-free-port "127.0.0.1")
        base (.getLocalPort held)]
    (try
      ;; Pretend the scan's probe missed the holder, so the child's own bind
      ;; is the one that fails.
      (with-redefs [child/port-free? (constantly true)
                    child/port-base base]
        (let [c (child/start! (temp-project) nil)]
          (try (is (< base (:port c) (+ base child/port-span))
                   "the child moved past the held port, inside the scan's span")
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
      (is (= "9999\n" (slurp (io/file dir ".nrepl-port"))) "the seeded .nrepl-port is untouched, byte-identical")
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
      (is (= "5555\n" (slurp (io/file dir ".nrepl-port"))) "the seeded .nrepl-port is untouched")
      (finally (force-kill! @captured)
               (when @log (io/delete-file @log true))))))

(deftest process-options-without-a-path-test
  (with-redefs [lp/path (fn [] nil)]
    (is (not (contains? (child/process-options {:dir "/d" :log "/l"}) :extra-env)))))

(defn- port-file-text [dir]
  (let [f (io/file dir ".nrepl-port")]
    (when (.exists f) (str/trim (slurp f)))))

(deftest two-children-in-one-dir-test
  (let [dir (temp-project)
        started (atom [])
        start! (fn [] (let [c (child/start! dir nil)] (swap! started conj c) c))]
    (spit (io/file dir ".nrepl-port") "1234")
    (try
      (let [c1 (start!)
            c2 (start!)]
        (is (not= (:port c1) (:port c2)))
        (is (= "1234" (port-file-text dir)) "neither child writes the file")
        (child/kill! c1)
        (child/kill! c2)
        (is (= "1234" (port-file-text dir)) "nor deletes it"))
      (finally (doseq [c @started] (force-kill! (:proc c)))))))
