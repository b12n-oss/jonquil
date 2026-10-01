(ns jonquil.clj-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.clj :as clj]))

(deftest sentences-test
  (is (= "; This file isn't in a project, so there's no .nrepl-port to read. Save it inside the project that has .jonquil.edn." clj/no-project-message))
  (is (= "; No nREPL server here. Start one in /p with clojure -M:serve (clojure-cli.repl), or any nREPL server that writes .nrepl-port." (clj/not-running-message "/p")))
  (is (= "; .nrepl-port doesn't hold a port number, so Jonquil can't reach the server." clj/bad-port-file-message))
  (is (= "; .nrepl-port says port 7, but nothing answers there. The server may have stopped." (clj/refused-message 7)))
  (is (= "; Port 7 isn't an nREPL server Jonquil can use." (clj/not-server-message 7)))
  (is (= "; Connecting to the nREPL server on port 7..." (clj/connecting-message 7)))
  (is (= "; demo.core isn't loaded in the server yet. Run the file first." (clj/not-loaded-message "demo.core")))
  (is (= "No docs: demo.core isn't loaded in the server yet. Run the file first." (clj/not-loaded-doc "demo.core")))
  (is (= "Nothing to inspect yet: Jonquil isn't attached to the server. Run something first." clj/inspect-not-attached))
  (is (= "; The server didn't stop the evaluation, so Jonquil detached. It may still be running." clj/interrupt-failed-message))
  (is (= "Lost the connection to the nREPL server." clj/lost-connection))
  (is (= ["Jonquil attaches to a server you started: it never starts or stops the JVM."
          "Reset on Run isn't available: this is your own JVM, so Jonquil doesn't unload namespaces in it."
          "Stdin can't be closed: an empty stdin fails on nREPL 1.7.0, so a read waits for a line."
          "Output printed from other threads after an evaluation finishes isn't shown."]
         clj/limitations)))

(deftest policy-test
  (is (= {:session-ns nil :inspect-registered? false :prompt-ns 'user} (:on-connect clj/connect-policy)))
  (is (= clj/no-project-message (get-in clj/connect-policy [:messages :no-project])))
  (is (= (clj/refused-message 9) ((get-in clj/connect-policy [:messages :refused]) 9))))

(deftest buffer-ns-test
  (is (= "demo.core" (clj/buffer-ns "(ns demo.core\n  (:require [clojure.string :as s]))\n(defn f [])")))
  (is (nil? (clj/buffer-ns "(defn f [])")))
  (is (nil? (clj/buffer-ns "(ns demo.core"))))

(deftest help-ns-test
  (is (= "demo.core" (clj/help-ns "(ns demo.core)\n(greet 1)" 'user)))
  (is (= "user" (clj/help-ns "(greet 1)" 'user))))

(deftest ns-not-found-test
  (is (clj/ns-not-found? [{:ns "demo.core" :status ["namespace-not-found" "done" "error"]}]))
  (is (not (clj/ns-not-found? [{:ns "user" :value "3"} {:status ["done"]}]))))

(deftest eval-result-test
  (is (= {:status :ok :printed "3" :ns 'user} (clj/eval-result [{:ns "user" :value "3"} {:status ["done"]}] false)))
  (is (= {:status :error :error-text "Lost the connection to the nREPL server."}
         (clj/eval-result [{:status ["done" "jonquil-disconnected"]}] false)))
  (is (= :interrupted (:status (clj/eval-result [{:status ["done" "interrupted"]}] false))))
  (is (= "Error: class java.lang.ArithmeticException"
         (:error-text (clj/eval-result [{:err "Execution error ...\nDivide by zero\n"}
                                        {:ex "class java.lang.ArithmeticException" :status ["eval-error"]}
                                        {:status ["done"]}] false)))))

(deftest prompt-after-run-test
  (let [text "(ns demo.core)\n(def x 1)"]
    (is (= 'demo.core (clj/prompt-after-run text {:status :ok :printed "#'demo.core/x"} 'user)))
    (is (= 'user (clj/prompt-after-run text {:status :error :error-text "Error: x"} 'user)))
    (is (= 'user (clj/prompt-after-run "(def x 1)" {:status :ok :printed "#'user/x"} 'user)))))

(deftest stdin-test
  (is (clj/need-input? {:status ["need-input"]}))
  (is (not (clj/need-input? {:ns "user" :value "3"})))
  (is (= "typed\n" (clj/stdin-line "typed")))
  (is (= "typed\n" (clj/stdin-line "typed\n"))))
