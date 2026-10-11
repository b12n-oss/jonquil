(ns jonquil.login-path-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.process :as p]
            [jonquil.login-path :as lp]
            [jonquil.tool-path :as tp]
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

(defn- elapsed-ms [f]
  (let [t0 (System/currentTimeMillis)]
    (f)
    (- (System/currentTimeMillis) t0)))

(deftest backgrounded-child-test
  (testing "a shell that answers then leaves a child holding stdout open"
    (let [sh (fake-shell! (str "printf '\\n__JONQUIL_PATH__/a:/b__END__\\n'\n"
                               "sleep 86477 &\nexit 0"))
          dt (elapsed-ms (fn []
                           (start-with sh {:timeout-ms 1000})
                           (is (= {:path "/a:/b" :source :shell :shell "shell"} (lp/answer)))))]
      (is (< dt 2000) (str "answered after " dt " ms"))
      @(p/process {:out :string} "pkill" "-f" "sleep 86477"))))

(deftest endless-output-test
  ;; Regression guard: the old code also fell back here, so this cannot tell it apart.
  (let [sh (fake-shell! "while :; do echo spam; done")
        dt (elapsed-ms (fn []
                         (start-with sh {:timeout-ms 1000})
                         (let [a (lp/answer)]
                           (is (= :fallback (:source a)))
                           (is (= "timed out" (:reason a))))))]
    (is (< dt 2500) (str "answered after " dt " ms"))
    (Thread/sleep 200)
    (is (not (zero? (:exit @(p/process {:out :string} "pgrep" "-f" (str "^/bin/sh " (subs sh 0 (.lastIndexOf ^String sh "/")))))))
        "nothing left running")))

(deftest hung-answer-is-stored-test
  ;; The shell ignores TERM, so the lookup outlives the 500 ms grace and
  ;; answer reaches its late branch; that fallback must then be stored.
  (start-with (fake-shell! "trap '' TERM; while :; do sleep 1; done # jonquil-hung-86479")
              {:timeout-ms 300})
  (try
    (let [first-ms (elapsed-ms (fn []
                                 (let [a (lp/answer)]
                                   (is (= :fallback (:source a)))
                                   (is (= "timed out" (:reason a))))))]
      (is (< 600 first-ms 2500) (str "first answer took " first-ms " ms"))
      (is (< (elapsed-ms lp/answer) 50))
      (is (< (elapsed-ms lp/path) 50)))
    (finally
      @(p/process {:out :string} "pkill" "-9" "-f" "jonquil-hung-86479"))))

(deftest start-catch-delivers-test
  ;; An exception after the once-guard is set must not leave answer waiting.
  (with-redefs [tp/needs-lookup? (fn [& _] (throw (ex-info "boom" {})))]
    (start-with (fake-shell! "exit 1") {}))
  (let [dt (elapsed-ms (fn [] (is (= :fallback (:source (lp/answer))))))]
    (is (< dt 1000) (str "answer took " dt " ms"))))

(deftest throwing-exists-test
  (start-with (fake-shell! "exit 1") {:exists? (fn [_] (throw (ex-info "boom" {})))})
  (let [dt (elapsed-ms (fn []
                         (is (= :fallback (:source (lp/answer))))
                         (is (= "/usr/bin:/bin:/usr/sbin:/sbin" (lp/path)))))]
    (is (< dt 1000))))
