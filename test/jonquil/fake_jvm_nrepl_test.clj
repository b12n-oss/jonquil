(ns jonquil.fake-jvm-nrepl-test
  "The fake JVM nREPL server answers the way clojure-cli.repl / nREPL 1.7.0
  was measured to (.superpowers/sdd/clj/reply-shapes.md), so the :clj
  evaluator's and self-test's tests rest on the right shapes."
  (:require [clojure.test :refer [deftest is]]
            [fake-jvm-nrepl :as fake]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.ops :as ops]))

(defn- strip [msgs] (mapv (fn [m] (dissoc m :id :session)) msgs))

(defn- clone! [conn]
  (:new-session (first (client/request-sync conn {:op "clone"} 2000))))

(deftest describe-and-eval-test
  ;; jvm-ops; (+ 1 2) => [{:ns "user" :value "3"} {:status ["done"]}]
  (let [srv (fake/start!)
        conn (client/connect (:port srv))]
    (try
      (is (= (set fake/jvm-ops) (ops/op-names (client/request-sync conn {:op "describe"} 2000))))
      (let [sid (clone! conn)]
        (is (= [{:ns "user" :value "3"} {:status ["done"]}]
               (strip (client/request-sync conn {:op "eval" :code "(+ 1 2)" :session sid} 2000)))))
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest namespace-not-found-test
  ;; eval :ns "demo.core" before load => exactly one message,
  ;; status ["namespace-not-found" "done" "error"]
  (let [srv (fake/start!)
        conn (client/connect (:port srv))
        sid (clone! conn)]
    (try
      (is (= [{:ns "demo.core" :status ["namespace-not-found" "done" "error"]}]
             (strip (client/request-sync conn {:op "eval" :code "1" :ns "demo.core" :session sid} 2000))))
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest load-file-test
  ;; one :value, no :ns; afterwards :ns "demo.core" evaluates
  (let [srv (fake/start!)
        conn (client/connect (:port srv))
        sid (clone! conn)]
    (try
      (is (= [{:value "3"} {:status ["done"]}]
             (strip (client/request-sync conn {:op "load-file" :file "(ns demo.core)\n(+ 1 2)"
                                               :file-path "/p/demo/core.clj" :file-name "core.clj"
                                               :session sid} 2000)))
          "the file's last form's value, no :ns, marking demo.core loaded")
      (is (= [{:ns "demo.core" :value ":ok"} {:status ["done"]}]
             (strip (client/request-sync conn {:op "eval" :code "1" :ns "demo.core" :session sid} 2000)))
          "demo.core is loaded now; the session's own ns is still user, unaffected")
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest stdin-test
  ;; (read-line) -> need-input; stdin "typed\n" -> value "\"typed\""
  (let [srv (fake/start!)
        conn (client/connect (:port srv))
        sid (clone! conn)
        need-input (promise)
        done (promise)
        replies (atom [])]
    (try
      (client/request! conn {:op "eval" :code "(read-line)" :session sid}
                       (fn [m]
                         (swap! replies conj m)
                         (when (= ["need-input"] (:status m)) (deliver need-input true))
                         (when (client/done? m) (deliver done true))))
      (is (true? (deref need-input 2000 :timeout)))
      (client/request-sync conn {:op "stdin" :stdin "typed\n" :session sid} 2000)
      (is (true? (deref done 2000 :timeout)))
      (is (= [{:status ["need-input"]} {:ns "user" :value "\"typed\""} {:status ["done"]}]
             (strip @replies)))
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest interrupt-test
  ;; :slow {"(slow)" 5000}; interrupt after 100 ms -> ["done" "interrupted"] in < 1000 ms;
  ;; with :ignore-interrupt? the eval is still pending 300 ms after the interrupt
  (let [srv (fake/start! {:slow {"(slow)" 5000}})
        conn (client/connect (:port srv))
        sid (clone! conn)
        got-reply (promise)
        replies (atom [])]
    (try
      (client/request! conn {:op "eval" :code "(slow)" :session sid}
                       (fn [m] (swap! replies conj m) (deliver got-reply true)))
      (Thread/sleep 100)
      (let [start (System/nanoTime)]
        (is (= [{:status ["done"]}] (strip (client/request-sync conn {:op "interrupt" :session sid} 2000))))
        (is (true? (deref got-reply 2000 :timeout)))
        (is (= [{:status ["done" "interrupted"]}] (strip @replies)))
        (is (< (/ (- (System/nanoTime) start) 1e6) 1000.0) "interrupted well under 1000 ms"))
      (finally (client/close! conn) (fake/stop! srv))))
  (let [srv (fake/start! {:slow {"(slow)" 5000} :ignore-interrupt? true})
        conn (client/connect (:port srv))
        sid (clone! conn)
        replies (atom [])]
    (try
      (client/request! conn {:op "eval" :code "(slow)" :session sid} (fn [m] (swap! replies conj m)))
      (Thread/sleep 100)
      (client/request-sync conn {:op "interrupt" :session sid} 2000)
      (Thread/sleep 300)
      (is (empty? @replies) "the eval is still pending 300 ms after an ignored interrupt")
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest help-ops-test
  ;; completions, lookup found / {"info" []}, doc text and the "No doc for" default
  (let [srv (fake/start! {:completions {"fo" [["foo" "user"] ["foobar" "user"]]}
                          :docs {"where" {"ns" "a.b" "name" "where"}}
                          :full-docs {"where" "Where the deck is."}})
        conn (client/connect (:port srv))]
    (try
      (is (= [{:completions [{"candidate" "foo" "ns" "user" "type" "function"}
                             {"candidate" "foobar" "ns" "user" "type" "function"}]
               :status ["done"]}]
             (strip (client/request-sync conn {:op "completions" :prefix "fo"} 2000))))
      (is (= [{:completions [] :status ["done"]}]
             (strip (client/request-sync conn {:op "completions" :prefix "zz"} 2000))))
      (is (= [{:info {"ns" "a.b" "name" "where"} :status ["done"]}]
             (strip (client/request-sync conn {:op "lookup" :sym "where"} 2000))))
      (is (= [{:info [] :status ["done"]}]
             (strip (client/request-sync conn {:op "lookup" :sym "nope"} 2000))))
      (is (= [{:doc "Where the deck is." :status ["done"]}]
             (strip (client/request-sync conn {:op "doc" :sym "where"} 2000))))
      (is (= [{:doc "No doc for nope" :status ["done"]}]
             (strip (client/request-sync conn {:op "doc" :sym "nope"} 2000))))
      (finally (client/close! conn) (fake/stop! srv)))))
