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

(deftest no-shell-env-on-linux-test
  (let [sh (fake-shell! "printf '\\n__JONQUIL_PATH__/a:/b__END__\\n'")
        passwd (io/file (temp-dirs/temp-dir! "jonquil-login-path-test-") "passwd")
        uid 4242]
    (spit passwd (str "x:x:" uid ":" uid "::/h:" sh "\n"))
    (with-redefs [lp/os-name (fn [] "Linux")
                  lp/passwd-file (.getPath passwd)
                  lp/current-uid (fn [] uid)]
      (is (= :started (start-with nil {})))
      (is (= {:path "/a:/b" :source :shell :shell "shell"} (lp/answer))))))

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

(deftest hung-answer-is-stored-test
  ;; The lookup itself never returns until the test releases it, so answer
  ;; always reaches its late branch (no race with a shell's start-up); that
  ;; fallback must then be stored. Against code that doesn't store it, the
  ;; second answer would wait another full grace period and fail the 50 ms bound.
  (let [gate (promise)]
    (with-redefs [lp/lookup (fn [_] @gate {:path "/never" :source :shell :shell "sh"})]
      (start-with "/bin/sh" {:timeout-ms 300})
      (try
        (let [first-ms (elapsed-ms (fn []
                                     (let [a (lp/answer)]
                                       (is (= :fallback (:source a)))
                                       (is (= "timed out" (:reason a))))))]
          (is (<= 700 first-ms 2500) (str "first answer took " first-ms " ms"))
          (is (< (elapsed-ms lp/answer) 50))
          (is (< (elapsed-ms lp/path) 50))
          (is (= :fallback (:source (lp/answer)))))
        (finally (deliver gate :released))))))

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

(deftest terminal-launch-test
  ;; A PATH that already has a well-known dir: no lookup, so the line the
  ;; window appends is nil and it prints nothing extra.
  (is (= :not-needed (start-with "/bin/sh" {:env {"PATH" "/usr/bin:/opt/homebrew/bin"}})))
  (is (nil? (lp/console-line)))
  (is (= "/usr/bin:/opt/homebrew/bin" (lp/path))))

(defn- running? [pattern]
  (zero? (:exit @(p/process {:out :string} "pgrep" "-f" pattern))))

(deftest term-ignoring-tree-test
  ;; A .zshrc that ignores TERM hands that to every child it starts, so on a
  ;; timeout the children must be killed too, not just the shell.
  (let [sh (fake-shell! "trap '' TERM\nsleep 86491 &\nsleep 86492")]
    (try
      (start-with sh {:timeout-ms 1000})
      (is (= "timed out" (:reason (lp/answer))))
      (Thread/sleep 300)
      (is (not (running? "sleep 86491")) "background child gone")
      (is (not (running? "sleep 86492")) "foreground child gone")
      (finally @(p/process {} "pkill" "-9" "-f" "sleep 8649[12]")))))

(deftest printed-then-hung-test
  ;; A shell that printed its PATH and then hung while ignoring TERM: the
  ;; printed PATH is used, so killing it must fit inside answer's wait.
  (let [sh (fake-shell! (str "trap '' TERM\n"
                             "printf '\\n__JONQUIL_PATH__/a:/b__END__\\n'\n"
                             "sleep 86493"))]
    (try
      (start-with sh {:timeout-ms 1000})
      (is (= {:path "/a:/b" :source :shell :shell "shell"} (lp/answer)))
      (finally @(p/process {} "pkill" "-9" "-f" "sleep 86493")))))

(deftest endless-output-is-capped-test
  ;; Output past the read cap stops the shell at once instead of filling the
  ;; disk until the deadline.
  (let [sh (fake-shell! "exec yes jonquil-86494")
        dt (elapsed-ms (fn []
                         (start-with sh {:timeout-ms 4000})
                         (is (= :fallback (:source (lp/answer))))))]
    (is (< dt 2000) (str "answered after " dt " ms"))
    (Thread/sleep 200)
    (is (not (running? "jonquil-86494")))))
