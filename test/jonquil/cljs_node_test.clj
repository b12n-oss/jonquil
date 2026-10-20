(ns jonquil.cljs-node-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.cljs-node :as cljs-node]))

(deftest launch-args-test
  (is (= ["/c/clojure" "-Sdeps" cljs-node/default-deps "-M" "-i" "/l.clj" "-m" "jonquil.cljs-node-launch"]
         (cljs-node/launch-args {:clojure "/c/clojure" :launcher "/l.clj" :add-dep? true})))
  (is (= ["/c/clojure" "-M" "-i" "/l.clj" "-m" "jonquil.cljs-node-launch"]
         (cljs-node/launch-args {:clojure "/c/clojure" :launcher "/l.clj" :add-dep? false}))))

(deftest needs-cljs-dep-test
  (is (true? (cljs-node/needs-cljs-dep? nil)))
  (is (true? (cljs-node/needs-cljs-dep? "{:deps {}}")))
  (is (true? (cljs-node/needs-cljs-dep? "{:deps")) "unreadable")
  (is (false? (cljs-node/needs-cljs-dep? "{:deps {org.clojure/clojurescript {:mvn/version \"1.11.4\"}}}"))))

(deftest ready-port-test
  (is (= 55145 (cljs-node/ready-port "Downloading: x\njonquil-cljs-prepl 55145\n")))
  (is (nil? (cljs-node/ready-port "Downloading: x\n"))))

(deftest error-line-test
  (is (= "Error: boom"
         (cljs-node/error-line {:tag :ret :exception true
                                :val "{:via [{:type clojure.lang.ExceptionInfo :message \"Execution error (Error) at (<cljs repl>:1).\\nboom\\n\" :data {:repl-env #cljs.repl.node.NodeEnv{:socket #object[clojure.lang.Atom 0x1 {}]}}}] :cause \"Execution error (Error) at (<cljs repl>:1).\\nboom\\n\"}"})))
  (is (= "Error: let requires an even number of forms in binding vector"
         (cljs-node/error-line {:tag :ret :exception true
                                :val "{:via [{:message \"x\"} {:message \"let requires an even number of forms in binding vector\"}] :cause \"let requires an even number of forms in binding vector\"}"})))
  (is (= "Error: evaluation failed" (cljs-node/error-line {:tag :ret :exception true :val "not edn {"}))))

(deftest fold-replies-test
  (let [s (cljs-node/sentinel 3)
        ret (fn [v form & kv] (apply assoc {:tag :ret :val v :ns "cljs.user" :form form} kv))]
    (is (= {:status :ok :printed "3" :ns 'cljs.user}
           (cljs-node/fold-replies [(ret "#'cljs.user/a" "(def a 1)") (ret "3" "(+ a 2)") (ret s s)] s)))
    (is (= {:status :ok :printed "nil" :ns 'cljs.user}
           (cljs-node/fold-replies [(ret s s)] s)) "an empty buffer")
    (testing "every form ran; the first failure names the result"
      (let [r (cljs-node/fold-replies [(ret "{:cause \"first\"}" "(f)" :exception true)
                                       (ret "2" "(+ 1 1)")
                                       (ret "{:cause \"second\"}" "(g)" :exception true)
                                       (ret s s)] s)]
        (is (= :error (:status r)))
        (is (= "Error: first" (:error-text r)))))
    (testing "the ns follows the last ret"
      (is (= 'demo.core (:ns (cljs-node/fold-replies [(ret "nil" "(ns demo.core)" :ns "demo.core") (ret s s :ns "demo.core")] s)))))))

(deftest output-of-test
  (is (= [:out "hi\n"] (cljs-node/output-of {:tag :out :val "hi\n"})))
  (is (= [:err "w\n"] (cljs-node/output-of {:tag :err :val "w\n"})))
  (is (= [:out "tap> 42\n"] (cljs-node/output-of {:tag :tap :val "42"})))
  (is (nil? (cljs-node/output-of {:tag :ret :val "1"}))))

(deftest parse-reply-test
  (is (= {:tag :ret :val "3" :ns "cljs.user" :ms 1 :form "(+ 1 2)"}
         (cljs-node/parse-reply "{:tag :ret, :val \"3\", :ns \"cljs.user\", :ms 1, :form \"(+ 1 2)\"}")))
  (is (nil? (cljs-node/parse-reply "garbage {")))
  (is (nil? (cljs-node/parse-reply "[1 2]"))))

(deftest reset-message-test
  (is (not= cljs-node/reset-message cljs-node/stopped-message)))

(deftest fold-replies-no-ns-test
  (let [s (cljs-node/sentinel 4)
        ret (fn [v form & kv] (apply assoc {:tag :ret :val v :form form} kv))
        ok (cljs-node/fold-replies [(ret "1" "(+ 0 1)") (ret s s)] s)
        err (cljs-node/fold-replies [(ret "{:cause \"bad\"}" "(f)" :exception true) (ret s s)] s)]
    (is (= {:status :ok :printed "1"} ok))
    (is (not (contains? ok :ns)))
    (is (= {:status :error :error-text "Error: bad"} err))
    (is (not (contains? err :ns)))))

(deftest limitations-test
  (is (= 4 (count cljs-node/limitations)))
  (is (= "Jonquil starts and stops its own ClojureScript process."
         (first cljs-node/limitations)))
  (is (not-any? #(re-find #"experimental" %) cljs-node/limitations)))
