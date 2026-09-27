(ns jonquil.fake-cljd-nrepl-test
  "The fake server answers the way the fork was measured to, so the attach
  evaluator's tests rest on the right shapes."
  (:require [clojure.test :refer [deftest is testing]]
            [fake-cljd-nrepl :as fake]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.ops :as ops]))

(defn- strip [msgs] (mapv (fn [m] (dissoc m :id :session)) msgs))

(deftest fake-answers-like-the-fork-test
  (let [srv (fake/start! {:answers {"(where)" "{:slide 21, :of 54}"} :fail-on "boom"})
        conn (client/connect (:port srv))]
    (try
      (is (= (set fake/fork-ops) (ops/op-names (client/request-sync conn {:op "describe"} 2000))))
      (let [sid (:new-session (first (client/request-sync conn {:op "clone"} 2000)))]
        (testing "a new session: the banner, then cljd.user, then the value"
          (is (= [{:out fake/banner} {:ns "cljd.user"} {:value "3"} {:ns "cljd.user"} {:status ["done"]}]
                 (strip (client/request-sync conn {:op "eval" :code "(+ 1 2)" :session sid} 2000)))))
        (testing "the request's :ns is ignored; an ns form switches the session"
          (is (= [{:value "{:slide 21, :of 54}"} {:ns "cljd.user"} {:status ["done"]}]
                 (strip (client/request-sync conn {:op "eval" :code "(where)" :ns "a.b" :session sid} 2000))))
          (is (= [{:ns "a.b"} {:status ["done"]}]
                 (strip (client/request-sync conn {:op "eval" :code "(ns a.b)" :session sid} 2000)))))
        (testing "load-file: a value per form, and a stop at the failing one"
          (is (= [{:ns "c.d"}
                  {:value "Closure: () => dynamic from Function 'f': static."} {:ns "c.d"}
                  {:err fake/fail-err} {:ns "c.d"} {:status ["done" "eval-error"]}]
                 (strip (client/request-sync conn {:op "load-file" :file "(ns c.d)\n(defn f [])\n(boom)\n(never)"
                                                   :file-path "/p/c/d.cljd" :file-name "d.cljd" :session sid} 2000)))))
        (testing "in-ns is refused"
          (is (= [{:err fake/in-ns-err :status ["done" "eval-error"]}]
                 (strip (client/request-sync conn {:op "eval" :code "(in-ns 'x)" :session sid} 2000)))))
        (testing "defn in cljd.user is refused; defn in another namespace still answers the closure string"
          (let [sid2 (:new-session (first (client/request-sync conn {:op "clone"} 2000)))]
            (is (= [{:out fake/banner} {:ns "cljd.user"} {:err fake/defn-in-cljd-user-err :status ["done" "eval-error"]}]
                   (strip (client/request-sync conn {:op "eval" :code "(defn g [])" :session sid2} 2000))))
            (is (= [{:err fake/defn-in-cljd-user-err :status ["done" "eval-error"]}]
                   (strip (client/request-sync conn {:op "eval" :code "(defn- g [])" :session sid2} 2000))))
            (client/request-sync conn {:op "eval" :code "(ns c.d)" :session sid2} 2000)
            (is (= [{:value "Closure: () => dynamic from Function 'g': static."} {:ns "c.d"} {:status ["done"]}]
                   (strip (client/request-sync conn {:op "eval" :code "(defn g [])" :session sid2} 2000)))))))
      (is (= ["describe" "clone" "eval" "eval" "eval" "load-file" "eval" "clone" "eval" "eval" "eval" "eval"]
             (fake/ops-seen srv)))
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest fake-prints-and-looks-up-test
  (let [srv (fake/start! {:prints {"println" ":side-effect\n"}})
        conn (client/connect (:port srv))
        sid (:new-session (first (client/request-sync conn {:op "clone"} 2000)))]
    (try
      (client/request-sync conn {:op "eval" :code "1" :session sid} 2000)
      (is (= [{:out ":side-effect\n"} {:value ":ok"} {:ns "cljd.user"} {:status ["done"]}]
             (strip (client/request-sync conn {:op "eval" :code "(println :side-effect)" :session sid} 2000)))
          "fork 8aaa4628: out before value")
      (is (= [{:status ["done" "lookup-error"]}]
             (strip (client/request-sync conn {:op "lookup" :sym "map" :ns "cljd.user" :session sid} 2000)))
          "the older fork's answer")
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest fake-drops-and-stops-test
  (let [srv (fake/start! {:drop-on "(drop)"})
        conn (client/connect (:port srv))
        sid (:new-session (first (client/request-sync conn {:op "clone"} 2000)))]
    (is (= [{:out fake/banner} {:ns "cljd.user"} {:status ["done" "jonquil-disconnected"]}]
           (strip (client/request-sync conn {:op "eval" :code "(drop)" :session sid} 2000)))
        "the client sees the drop as its own disconnected status")
    (is (not (client/open? conn)))
    (fake/stop! srv)
    (is (thrown? java.io.IOException (client/connect (:port srv))) "stopped: the port refuses")))

(deftest fake-drop-connections-test
  (let [srv (fake/start!)
        conn (client/connect (:port srv))
        sid (:new-session (first (client/request-sync conn {:op "clone"} 2000)))]
    (fake/drop-connections! srv)
    (is (= [{:status ["done" "jonquil-disconnected"]}]
           (strip (client/request-sync conn {:op "eval" :code "1" :session sid} 2000)))
        "the server dropping every client reads the same way as an app that quits")
    (fake/stop! srv)))

(deftest fake-ops-override-test
  (let [srv (fake/start! {:ops ["describe" "clone"]})
        conn (client/connect (:port srv))]
    (is (= #{"describe" "clone"} (ops/op-names (client/request-sync conn {:op "describe"} 2000))))
    (client/close! conn)
    (fake/stop! srv)))

(deftest fake-slow-test
  (let [srv (fake/start! {:slow {"(slow)" 200}})
        conn (client/connect (:port srv))
        sid (:new-session (first (client/request-sync conn {:op "clone"} 2000)))
        _ (client/request-sync conn {:op "eval" :code "1" :session sid} 2000)
        start (System/currentTimeMillis)]
    (is (= [{:value ":ok"} {:ns "cljd.user"} {:status ["done"]}]
           (strip (client/request-sync conn {:op "eval" :code "(slow)" :session sid} 2000))))
    (is (<= 200 (- (System/currentTimeMillis) start)) "the reply is delayed by :slow's ms")
    (client/close! conn)
    (fake/stop! srv)))

(deftest fake-asleep-for-test
  (let [srv (fake/start! {:asleep-for 1})
        conn (client/connect (:port srv))
        sid (:new-session (first (client/request-sync conn {:op "clone"} 2000)))]
    (is (= [{:out fake/banner} {:ns "cljd.user"} {:value "nil"} {:ns "cljd.user"} {:status ["done"]}]
           (strip (client/request-sync conn {:op "eval" :code "(+ 1 2)" :session sid} 2000)))
        "the first request answers nil without evaluating")
    (is (= [{:value "3"} {:ns "cljd.user"} {:status ["done"]}]
           (strip (client/request-sync conn {:op "eval" :code "(+ 1 2)" :session sid} 2000)))
        "the next request evaluates for real")
    (client/close! conn)
    (fake/stop! srv)))

(deftest fake-mute-test
  (let [srv (fake/start! {:mute? true})
        conn (client/connect (:port srv))]
    (client/request! conn {:op "describe"} (fn [_]))
    (Thread/sleep 200)
    (is (client/open? conn) "a muted connection stays open; it never answers")
    (is (= ["describe"] (fake/ops-seen srv)) "the request is still logged")
    (client/close! conn)
    (fake/stop! srv)))
