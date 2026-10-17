(ns jonquil.fake-shadow-nrepl-test
  "The fake shadow-cljs nREPL server answers the way shadow-cljs 3.2.0 was
  measured to, so the cljs evaluator's tests rest on the right shapes."
  (:require [clojure.test :refer [deftest is testing]]
            [fake-shadow-nrepl :as fake]
            [jonquil.nrepl.bencode :as bencode]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.ops :as ops]))

(defn- strip [msgs] (mapv (fn [m] (dissoc m :id :session)) msgs))

(defn- clone! [conn]
  (:new-session (first (client/request-sync conn {:op "clone"} 2000))))

(defn- ev [conn sid code]
  (client/request-sync conn {:op "eval" :code code :session sid} 2000))

(def select-app "(shadow.cljs.devtools.api/nrepl-select :app)")

(deftest select-test
  (let [srv (fake/start! {:build :app})
        conn (client/connect (:port srv))]
    (try
      (is (= (set fake/shadow-ops) (ops/op-names (client/request-sync conn {:op "describe"} 2000))))
      (let [sid (clone! conn)]
        (testing "the configured build selects"
          (let [msgs (ev conn sid select-app)]
            (is (= [{:out "To quit, type: :cljs/quit\n"}
                    {:ns "cljs.user" :value "[:selected :app]"}
                    {:status ["done"]}]
                   (strip msgs)))
            (is (every? #(= sid (:session %)) msgs) "every reply carries the session")))
        (testing "another build fails the way shadow does"
          (let [msgs (strip (ev conn (clone! conn) "(shadow.cljs.devtools.api/nrepl-select :other)"))]
            (is (re-find #"watch for build not running" (:err (first msgs))))
            (is (= [{:ex "class clojure.lang.ExceptionInfo" :root-ex "class clojure.lang.ExceptionInfo" :status ["eval-error"]}
                    {:status ["done"]}]
                   (rest msgs))))))
      (finally (client/close! conn) (fake/stop! srv))))
  (let [srv (fake/start! {:build :app :watch-running? false})
        conn (client/connect (:port srv))]
    (try
      (is (re-find #"watch for build not running" (:err (first (ev conn (clone! conn) select-app)))))
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest multi-form-eval-test
  (let [srv (fake/start!)
        conn (client/connect (:port srv))
        sid (clone! conn)]
    (try
      (ev conn sid select-app)
      (is (= [{:ns "my.app" :value "nil"}
              {:ns "my.app" :value "#'my.app/f"}
              {:err "\nExecution error (Error) at (<cljs repl>:1).\nboom\n"}
              {:ns "my.app" :value ":repl/exception!"}
              {:ns "my.app" :value "42"}
              {:status ["done"]}]
             (strip (ev conn sid "(ns my.app)\n(defn f [] 1)\n(throw (js/Error. \"boom\"))\n(f)")))
          "a throw keeps going; the stale noise carries another id, so the client drops it")
      (testing "a session's first eval has no previous id, so no noise"
        (let [sid2 (clone! conn)
              msgs (ev conn sid2 select-app)]
          (is (not-any? #(= ["state"] (:status %)) msgs))))
      (testing "unreadable code"
        (is (= [{:err "Syntax error reading source"} {:status ["done"]}]
               (strip (ev conn sid "(unbalanced")))))
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest stale-state-noise-test
  (let [srv (fake/start!)]
    (try
      (with-open [sock (java.net.Socket. "127.0.0.1" (int (:port srv)))]
        (let [src (bencode/byte-source (.getInputStream sock))
              out (.getOutputStream sock)
              send! (fn [req]
                      (.write out ^bytes (bencode/encode req))
                      (.flush out)
                      (loop [acc []]
                        (let [m (bencode/decode src)
                              acc (conj acc m)]
                          (if (some #{"done"} (get m "status")) acc (recur acc)))))
              sid (get (first (send! {"op" "clone" "id" "c1"})) "new-session")]
          (is (not-any? #(= ["state"] (get % "status"))
                        (send! {"op" "eval" "id" "e1" "session" sid "code" select-app}))
              "a session's first eval has no previous id to carry")
          (let [msgs (send! {"op" "eval" "id" "e2" "session" sid "code" "1"})
                noise (first msgs)]
            (is (= {"id" "e1" "repl-type" "cljs" "status" ["state"] "changed-namespaces" {} "session" sid} noise)
                "the noise carries the previous request's id")
            (is (= ["e2" "e2"] (mapv #(get % "id") (rest msgs)))))))
      (finally (fake/stop! srv)))))

(deftest no-runtime-test
  (let [srv (fake/start! {:no-runtime? true})
        conn (client/connect (:port srv))
        sid (clone! conn)]
    (try
      (ev conn sid select-app)
      (is (= [{:err fake/no-runtime-err}
              {:ns "cljs.user" :printed-value 1 :value "nil"}
              {:status ["done"]}]
             (strip (ev conn sid "(+ 1 2)"))))
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest quit-then-clj-test
  (let [srv (fake/start!)
        conn (client/connect (:port srv))
        sid (clone! conn)]
    (try
      (ev conn sid select-app)
      (let [msgs (strip (ev conn sid ":repl/quit"))]
        (is (re-find #"^Exited CLJS session\. You are now in CLJ again\." (:err (first msgs))))
        (is (= [{:ns "shadow.user" :printed-value 1 :value ":repl/quit"} {:status ["done"]}] (rest msgs))))
      (is (= [{:ns "shadow.user" :value "42"} {:status ["done"]}] (strip (ev conn sid "(+ 1 2)")))
          "later evals answer as CLJ, no state noise")
      (ev conn sid select-app)
      (is (= 4 (count (filter #{"eval"} (fake/ops-seen srv)))))
      (is (= 2 (count (filter #(= select-app (get % "code")) (fake/requests srv)))) "select was sent twice")
      (is (not-any? #{"interrupt"} (fake/ops-seen srv)))
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest complete-and-eldoc-test
  (let [srv (fake/start!)
        conn (client/connect (:port srv))
        sid (clone! conn)]
    (try
      (is (= [{:completions [{"candidate" "map" "ns" "cljs.core" "type" "function"}
                             {"candidate" "mapv" "ns" "cljs.core" "type" "function"}
                             {"candidate" "map?" "ns" "cljs.core" "type" "function"}]
               :status ["done"]}]
             (strip (client/request-sync conn {:op "complete" :prefix "ma" :session sid} 2000))))
      (is (= [{:completions [{"candidate" "mapv" "ns" "cljs.core" "type" "function"}] :status ["done"]}]
             (strip (client/request-sync conn {:op "complete" :prefix "mapv" :session sid} 2000))))
      (is (= [{:ns "cljs.core" :name "map" :type "function" :eldoc [["f"] ["f" "coll"]]
               :docstring "Returns a lazy sequence consisting of the result of applying f to the set of first items of each coll."
               :status ["done"]}]
             (strip (client/request-sync conn {:op "eldoc" :sym "map" :session sid} 2000))))
      (is (= [{:status ["done" "no-eldoc"]}]
             (strip (client/request-sync conn {:op "eldoc" :sym "nope" :session sid} 2000))))
      (finally (client/close! conn) (fake/stop! srv)))))

(deftest bare-ops-test
  (let [srv (fake/start! {:bare-ops? true})
        conn (client/connect (:port srv))]
    (try
      (is (= #{"eval" "load-file" "clone" "describe"} (ops/op-names (client/request-sync conn {:op "describe"} 2000))))
      (finally (client/close! conn) (fake/stop! srv)))))
