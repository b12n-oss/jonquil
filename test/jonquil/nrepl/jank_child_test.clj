(ns jonquil.nrepl.jank-child-test
  "The jank launcher against dev/fake-jank (an executable that ignores its
  args and serves a fake jank nREPL in its cwd), plus the pure helpers.
  Every child a test starts is killed by the test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jolt.process :as p]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.jank-child :as jank]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def fake-jank (.getCanonicalPath (io/file "dev/fake-jank")))

(defn- pgrep? [pattern]
  (let [r (p/sh "pgrep" "-f" pattern)]
    (boolean (and (zero? (:exit r)) (not (str/blank? (:out r)))))))

(defn- wait-until [pred ms]
  (loop [n 0] (cond (pred) true (> (* n 50) ms) false :else (do (Thread/sleep 50) (recur (inc n))))))

(defn- run-dirs []
  (set (filter #(re-matches #"jonquil-jank-\d+" %)
               (seq (.list (io/file (System/getProperty "java.io.tmpdir")))))))

(defn- slow-binary
  "A jank stand-in that stays up and never writes a port file."
  []
  (let [slow (str (temp-dirs/temp-dir! "jonquil-jank-test-") "/slow-jank")]
    (spit slow "#!/usr/bin/env bash\nsleep 60\n")
    (.setExecutable (io/file slow) true)
    slow))

(deftest find-binary-test
  (let [on (fn [& present] (fn [f] (boolean (some #{f} present))))
        opts (fn [env exists?] {:env env :path-dirs ["/x/bin" "/y/bin"] :exists? exists?})]
    (is (= "/mine/jank" (jank/find-binary (opts {"JONQUIL_JANK" "/mine/jank"}
                                                (on "/mine/jank" "/x/bin/jank")))))
    (is (= "/x/bin/jank" (jank/find-binary (opts {} (on "/x/bin/jank" "/y/bin/jank")))))
    (is (= "/y/bin/jank" (jank/find-binary (opts {} (on "/y/bin/jank" "/opt/homebrew/bin/jank")))))
    (is (= "/opt/homebrew/bin/jank"
           (jank/find-binary (opts {} (on "/opt/homebrew/bin/jank")))))
    (let [home (str (System/getProperty "user.home") "/.local/bin/jank")]
      (is (= home (jank/find-binary (opts {} (on home))))))
    (is (nil? (jank/find-binary (opts {"JONQUIL_JANK" "/mine/jank"} (constantly false)))))))

(deftest module-path-test
  (let [root (temp-dirs/temp-dir! "jonquil-jank-test-")
        with-src (str root "/with")
        without (str root "/without")]
    (.mkdirs (io/file with-src "src"))
    (.mkdirs (io/file without))
    (is (= (str with-src "/src") (jank/module-path {:project-dir with-src :file-dir "/f"})))
    (is (= without (jank/module-path {:project-dir without :file-dir "/f"})))
    (is (= "/f" (jank/module-path {:project-dir nil :file-dir "/f"})))
    (is (nil? (jank/module-path {})))))

(deftest launch-command-test
  (is (= ["/a b/it's/jank" "--module-path" "/p q/src" "repl"]
         (jank/launch-command "/a b/it's/jank" "/p q/src")))
  (is (= ["/b/jank" "repl"] (jank/launch-command "/b/jank" nil))))

(deftest start-and-kill-test
  (let [child (jank/start! {:binary fake-jank})]
    (try
      (is (nil? (:error child)) (str child))
      (is (.exists (io/file (:run-dir child) ".nrepl-port")))
      (is (contains? (run-dirs) (.getName (io/file (:run-dir child)))) "run-dirs sees a live run dir")
      (let [conn (client/connect (:port child))]
        (try (is (some :versions (client/request-sync conn {:op "describe"} 2000)))
             (finally (client/close! conn))))
      (is (jank/alive? child))
      (finally (jank/kill! child)))
    (is (not (jank/alive? child)))
    (is (not (pgrep? (:run-dir child))))
    (is (not (.exists (io/file (:run-dir child)))))
    (is (nil? (jank/kill! child)) "idempotent")
    (is (nil? (jank/kill! nil)))))

(deftest kill-tree-test
  (let [child (jank/start! {:binary fake-jank})
        pid (str (:pid child))]
    (jank/kill! child)
    (is (not (zero? (:exit (p/sh "kill" "-0" pid)))) "the pid is gone")
    (is (not (zero? (:exit (p/sh "pgrep" "-P" pid)))) "no descendants remain")
    (is (not (.exists (io/file (:run-dir child)))))))

(deftest dies-on-its-own-test
  (let [bin (str (temp-dirs/temp-dir! "jonquil-jank-test-") "/dying-jank")]
    (spit bin "#!/usr/bin/env bash\necho 51234 > .nrepl-port\nsleep 0.3\n")
    (.setExecutable (io/file bin) true)
    (let [child (jank/start! {:binary bin})]
      (try (is (nil? (:error child)) (str child))
           (is (wait-until #(not (jank/alive? child)) 2000))
           (finally (jank/kill! child))))))

(deftest kill-during-start-test
  (let [t0 (System/currentTimeMillis)
        child (jank/start! {:binary (slow-binary)
                            :timeout-ms 10000
                            :cancelled? #(> (- (System/currentTimeMillis) t0) 100)})]
    (is (= {:error :cancelled} child))
    (is (not (pgrep? "slow-jank")))))

(deftest start-quoted-paths-test
  (let [d (str (temp-dirs/temp-dir! "jonquil-jank-test-") "/a b'c")
        bin (str d "/fake-jank")]
    (.mkdirs (io/file d))
    ;; dev/fake-jank finds its sources relative to itself, so the copy
    ;; with the odd name is a wrapper that execs it (cwd is kept).
    (spit bin (str "#!/usr/bin/env bash\nexec " (str "'" fake-jank "'") " \"$@\"\n"))
    (.setExecutable (io/file bin) true)
    (let [child (jank/start! {:binary bin})]
      (try (is (nil? (:error child)) (str child))
           (is (pos? (or (:port child) 0)))
           (finally (jank/kill! child))))))

(deftest start-failure-test
  (let [before (run-dirs)
        t0 (System/currentTimeMillis)
        r (jank/start! {:binary "/usr/bin/false"})]
    (is (string? (:error r)) (str r))
    (is (str/includes? (:error r) "exit"))
    (is (< (- (System/currentTimeMillis) t0) 2000))
    (is (= before (run-dirs)) "the failed start left no run dir")))

(deftest start-missing-binary-test
  (let [before (run-dirs)
        r (jank/start! {:binary "/nonexistent/jank"})]
    (is (string? (:error r)) (str r))
    (is (str/starts-with? (:error r) "couldn't start jank: "))
    (is (= before (run-dirs)) "no run dir left")))

(deftest throwing-cancelled-kills-child-test
  (let [before (run-dirs)]
    (is (thrown? Exception
                 (jank/start! {:binary (slow-binary) :cancelled? (fn [] (throw (ex-info "boom" {})))})))
    (is (= before (run-dirs)))
    (is (not (pgrep? "slow-jank")))))

(deftest two-children-test
  (let [a (jank/start! {:binary fake-jank})
        b (jank/start! {:binary fake-jank})]
    (try
      (is (not= (:port a) (:port b)))
      (is (not= (:run-dir a) (:run-dir b)))
      (finally (jank/kill! a) (jank/kill! b)))))
