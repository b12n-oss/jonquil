(ns jonquil.login-path-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.process :as p]
            [jonquil.login-path :as lp]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)
(use-fixtures :each (fn [t] (lp/reset-for-test!) (t) (lp/reset-for-test!)))

(def ^:private bare-env {"PATH" "/usr/bin:/bin:/usr/sbin:/sbin"})

(defn- fake-shell!
  "Writes a #!/bin/sh script with body into a fresh temp dir, makes it
  executable, and returns its path."
  [body]
  (let [f (io/file (temp-dirs/temp-dir! "jonquil-login-path-test-") "shell")]
    (spit f (str "#!/bin/sh\n" body "\n"))
    (.setExecutable f true)
    (.getPath f)))

(defn- start-with [shell opts]
  (lp/start! (merge {:env bare-env :home "/Users/t" :shell shell
                     :exists? (constantly false)}
                    opts)))

(deftest answering-shell-test
  (let [sh (fake-shell! "printf '\\n__JONQUIL_PATH__/a:/b__END__\\n'")]
    (is (= :started (start-with sh {})))
    (is (= {:path "/a:/b" :source :shell :shell "shell"} (lp/answer)))
    (is (= "/a:/b" (lp/path)))))

(deftest noisy-shell-test
  (let [sh (fake-shell! (str "echo banner; echo oops >&2\n"
                             "printf '\\n__JONQUIL_PATH__/a:/b__END__\\n'\n"
                             "echo bye; echo done >&2"))]
    (start-with sh {})
    (is (= "/a:/b" (lp/path)))
    (is (= :shell (:source (lp/answer))))))

(deftest hanging-shell-test
  (let [sh (fake-shell! "sleep 86413")
        t0 (System/currentTimeMillis)]
    (start-with sh {:timeout-ms 1000 :exists? (fn [d] (= d "/usr/local/bin"))})
    (let [a (lp/answer)
          dt (- (System/currentTimeMillis) t0)]
      (is (= :fallback (:source a)))
      (is (= "timed out" (:reason a)))
      (is (= "/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin" (:path a)))
      (is (< dt 2000) (str "answered after " dt " ms")))
    (Thread/sleep 200)
    (testing "the sleeping process is gone"
      (is (not (zero? (:exit @(p/process {:out :string} "pgrep" "-f" "sleep 86413"))))))))

(deftest failing-shell-test
  (start-with (fake-shell! "exit 1") {})
  (let [a (lp/answer)]
    (is (= :fallback (:source a)))
    (is (= "failed" (:reason a)))))

(deftest silent-shell-test
  (start-with (fake-shell! "echo hello") {})
  (let [a (lp/answer)]
    (is (= :fallback (:source a)))
    (is (= "printed no PATH" (:reason a)))))

(deftest missing-shell-test
  (start-with "/nonexistent/shell" {})
  (is (= :fallback (:source (lp/answer)))))

(deftest not-needed-test
  (let [env {"PATH" "/usr/bin:/opt/homebrew/bin"}
        marker (io/file (temp-dirs/temp-dir! "jonquil-login-path-test-") "ran")
        sh (fake-shell! (str "touch " (.getPath marker)))]
    (is (= :not-needed (start-with sh {:env env})))
    (Thread/sleep 300)
    (is (= {:path "/usr/bin:/opt/homebrew/bin" :source :process} (lp/answer)))
    (is (not (.exists marker)))))

(deftest second-start-is-a-no-op-test
  (let [sh1 (fake-shell! "printf '\\n__JONQUIL_PATH__/one__END__\\n'")
        sh2 (fake-shell! "printf '\\n__JONQUIL_PATH__/two__END__\\n'")]
    (start-with sh1 {})
    (is (nil? (start-with sh2 {})))
    (is (= "/one" (lp/path)))))

(deftest before-start-test
  (is (= {:path (System/getenv "PATH") :source :process} (lp/answer))))

(deftest console-line-test
  (is (nil? (do (lp/reset-for-test!) (start-with "x" {:env {"PATH" "/opt/homebrew/bin"}}) (lp/console-line))))
  (lp/reset-for-test!)
  (start-with (fake-shell! "printf '\\n__JONQUIL_PATH__/a__END__\\n'") {})
  (is (= "; PATH from your login shell (shell)." (lp/console-line)))
  (lp/reset-for-test!)
  (start-with (fake-shell! "exit 1") {})
  (is (= "; PATH from well-known folders (your login shell failed)." (lp/console-line))))
