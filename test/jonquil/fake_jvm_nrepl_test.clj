(ns jonquil.fake-jvm-nrepl-test
  "The fake JVM nREPL server answers the way clojure-cli.repl / nREPL 1.7.0
  was measured to, so the :clj
  evaluator's and self-test's tests rest on the right shapes."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
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

(deftest describe-versions-test
  ;; default 1.7.0, a chosen version, and :none (no "versions" at all)
  (doseq [[opts expected] [[nil [1 7]] [{:nrepl-version [1 8 0]} [1 8]] [{:nrepl-version :none} nil]]]
    (let [srv (if opts (fake/start! opts) (fake/start!))
          conn (client/connect (:port srv))]
      (try
        (is (= expected (ops/nrepl-version (client/request-sync conn {:op "describe"} 2000))))
        (finally (client/close! conn) (fake/stop! srv))))))

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

(deftest eval-future-guards-a-bad-read-test
  ;; malformed code (unbalanced "(+ 1") answers an error plus done instead
  ;; of throwing inside the eval future; stop! (in finally, below) must
  ;; return without rethrowing whatever the future would otherwise have
  ;; captured.
  (let [srv (fake/start!)
        conn (client/connect (:port srv))
        sid (clone! conn)]
    (try
      (is (= [{:err "EOF while reading\n"}
              {:ex fake/fail-ex-class :root-ex fake/fail-ex-class :status ["eval-error"]}
              {:status ["done"]}]
             (strip (client/request-sync conn {:op "eval" :code "(+ 1" :session sid} 2000))))
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest interrupt-with-no-delay-test
  ;; interrupt sent immediately after eval, no Thread/sleep at all, so it
  ;; can beat the eval future to registering its own :interrupt promise --
  ;; still ends up ["done" "interrupted"], never a full 5000 ms wait.
  (let [srv (fake/start! {:slow {"(slow)" 5000}})
        conn (client/connect (:port srv))
        sid (clone! conn)
        got-reply (promise)
        replies (atom [])]
    (try
      (client/request! conn {:op "eval" :code "(slow)" :session sid}
                       (fn [m] (swap! replies conj m) (deliver got-reply true)))
      (is (= [{:status ["done"]}] (strip (client/request-sync conn {:op "interrupt" :session sid} 2000))))
      (is (true? (deref got-reply 2000 :timeout)))
      (is (= [{:status ["done" "interrupted"]}] (strip @replies)))
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

(deftest inspect-registration-test
  ;; spec probe 1: load-file as a session's first request leaves it unregistered
  ;; -> inspect-start sees nil; any eval registers it.
  (let [srv (fake/start! {:answers {"{:a 1}" "{:a 1}"}})
        conn (client/connect (:port srv))
        sid (clone! conn)]
    (try
      (client/request-sync conn {:op "load-file" :file "1" :file-path "/p/demo/core.clj"
                                 :file-name "core.clj" :session sid} 2000)
      (let [r1 (client/request-sync conn {:op "inspect-start" :rows 10 :cols 40 :session sid} 2000)
            v1 (edn/read-string (some :view r1))]
        (is (= "nil" (:leaf v1)) "load-file alone never registers the session"))
      (client/request-sync conn {:op "eval" :code "{:a 1}" :session sid} 2000)
      (let [r2 (client/request-sync conn {:op "inspect-start" :rows 10 :cols 40 :session sid} 2000)
            v2 (edn/read-string (some :view r2))]
        (is (= "PersistentArrayMap · 1 items" (:info v2))))
      (finally (client/close! conn) (fake/stop! srv)))))
