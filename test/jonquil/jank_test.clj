(ns jonquil.jank-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.jank :as jank]
            [jonquil.nrepl.ops :as ops]))

(deftest sentences-test
  (is (= "; Starting jank (/opt/jank)..." (jank/starting-message "/opt/jank")))
  (is (= "; jank isn't installed. Install it (brew install jank-lang/jank/jank) or set JONQUIL_JANK to its path." jank/no-binary-message))
  (is (= "; jank didn't start: boom" (jank/start-failed-message "boom")))
  (is (= "; Reset: restarted jank." jank/reset-message))
  (is (= "; Stopped. jank restarted, so earlier definitions are gone." jank/stopped-message))
  (is (= "jank exited. The next evaluation starts it again." jank/lost-connection))
  (is (= "; demo.core isn't loaded in jank yet. Run the file first." (jank/not-loaded-message "demo.core")))
  (is (= "No docs while jank is running." jank/busy-doc))
  (is (= ["jank is experimental, and Jonquil starts and stops its own jank process."
          "Stop restarts jank, so earlier definitions are gone afterwards."
          "Programs can't read input: jank hasn't implemented read-line yet."
          "Jump to definition outside this file and the inspector aren't available."]
         jank/limitations)))

(deftest find-ns-test
  (is (= "(some? (find-ns 'demo.core))" (jank/find-ns-code "demo.core")))
  (is (true? (jank/loaded? [{:value "true"}])))
  (is (false? (jank/loaded? [{:value "false"}])))
  (is (false? (jank/loaded? [{:status ["done"]}]))))

(deftest meta-doc-test
  (is (= "(let [v (resolve 'greet)] (when v (let [m (meta v)] [(pr-str (:arglists m)) (:doc m)])))"
         (jank/meta-doc-code 'greet)))
  (is (= {:status :found :symbol 'greet :name 'greet :arglists "([w])" :doc "Says hi."}
         (jank/meta-doc 'greet [{:value "[\"([w])\" \"Says hi.\"]"} {:status ["done"]}])))
  (is (nil? (:arglists (jank/meta-doc 'greet [{:value "[\"nil\" \"Says hi.\"]"}]))))
  (doseq [msgs [[{:value "nil"}]
                [{:value "#object[x]"}]
                [{:value "x" :status ["done" "eval-error"]}]
                [{:status ["done"]}]]]
    (is (= {:status :no-docs :symbol 'greet :name 'greet :doc "No docs for greet"}
           (jank/meta-doc 'greet msgs))
        (pr-str msgs))))

(deftest eval-result-test
  (is (= {:status :error :error-text "Error: The symbol `nope` could not be resolved."}
         (jank/eval-result [{:value "The symbol `nope` could not be resolved." :status ["done" "eval-error"]}
                            {:err ""}]
                           false))
      "Homebrew jank 0.1: the message is the value, :err is blank")
  (let [msgs [{:err "─ analyze/unresolved-symbol ─\nerror: The symbol `nope` could not be resolved.\n"}
              {:ex "─ analyze/unresolved-symbol ─\nerror: The symbol `nope` could not be resolved.\n"}
              {:status ["done" "eval-error"]}]]
    (is (= (ops/eval-result msgs false jank/lost-connection) (jank/eval-result msgs false))))
  (is (= {:status :ok :printed "3" :ns 'user} (jank/eval-result [{:value "3" :ns "user"} {:status ["done"]}] false)))
  (is (= {:status :error :error-text jank/lost-connection}
         (jank/eval-result [{:status [ops/disconnected]}] false))))

(deftest policy-test
  (is (= {:session-ns nil :prompt-ns 'user} (:on-connect jank/connect-policy)))
  (is (= #{:no-project :not-running :bad-port-file :refused :not-server :connecting}
         (set (keys (:messages jank/connect-policy)))))
  (is (= "; Connecting to jank on port 7..." ((get-in jank/connect-policy [:messages :connecting]) 7)))
  (doseq [[k m] (:messages jank/connect-policy)]
    (is (re-find #"jank" (if (string? m) m (m 7))) (str k))))
