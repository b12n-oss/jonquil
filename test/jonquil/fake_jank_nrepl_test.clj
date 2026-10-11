(ns jonquil.fake-jank-nrepl-test
  "The fake jank nREPL server answers the way jank (upstream main 38a6382)
  was measured to in the D3 re-check spike."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [fake-jank-nrepl :as fake]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.ops :as ops]))

(defn- strip [msgs] (mapv (fn [m] (dissoc m :id :session)) msgs))

(defn- with-fake [opts f]
  (let [srv (fake/start! opts)
        conn (client/connect (:port srv))]
    (try (f conn)
         (finally (client/close! conn) (fake/stop! srv)))))

(defn- ev [conn code & {:as extra}]
  (strip (client/request-sync conn (merge {:op "eval" :code code} extra) 2000)))

(deftest describe-test
  (with-fake {}
    (fn [conn]
      (let [msgs (client/request-sync conn {:op "describe"} 2000)]
        (is (= (set fake/jank-ops) (ops/op-names msgs)))
        (is (= "jank-0.1-alpha" (get-in (first msgs) [:versions "jank" "version-string"])))))))

(deftest eval-in-unknown-ns-test
  (with-fake {}
    (fn [conn]
      (is (= [{:value "3" :ns "user"} {:status ["done"]}] (ev conn "(+ 1 2)")))
      (is (= [{:value "3" :ns "user"} {:status ["done"]}]
             (ev conn "(+ 1 2)" :ns "demo.nowhere"))
          "an unloaded ns evaluates in user"))))

(deftest find-ns-and-load-file-test
  (with-fake {}
    (fn [conn]
      (is (= "false" (:value (first (ev conn "(some? (find-ns 'demo.core))")))))
      (is (= [{:value "#'demo.core/x" :ns "demo.core"} {:status ["done"]}]
             (strip (client/request-sync conn {:op "load-file" :file "(ns demo.core)\n(def x 1)\n"} 2000))))
      (is (= "true" (:value (first (ev conn "(some? (find-ns 'demo.core))")))))
      (is (= [{:value "3" :ns "demo.core"} {:status ["done"]}] (ev conn "(+ 1 2)" :ns "demo.core"))))))

(def meta-code
  "(let [v (resolve 'greet)] (when v (let [m (meta v)] [(pr-str (:arglists m)) (:doc m)])))")

(deftest metadata-form-test
  (with-fake {:docs {"greet" ["([w])" "Says hi."]}}
    (fn [conn]
      (is (= "[\"([w])\" \"Says hi.\"]" (:value (first (ev conn meta-code)))))
      (is (= "nil" (:value (first (ev conn (str/replace meta-code "greet" "nope")))))))))

(deftest fail-on-test
  (with-fake {:fail-on "nope"}
    (fn [conn]
      (let [msgs (ev conn "(nope)")]
        (is (= ["err" "ex"] (mapv (fn [m] (name (first (remove #{:status} (keys m))))) (butlast msgs))))
        (is (str/includes? (:err (first msgs)) "unresolved-symbol"))
        (is (= {:status ["done" "eval-error"]} (last msgs)))))))

(deftest completions-test
  (with-fake {:completions {"x" [["x" "demo.core" "var"]]}}
    (fn [conn]
      (is (= [{:completions [{"candidate" "x" "ns" "demo.core" "type" "var"}] :status ["done"]}]
             (strip (client/request-sync conn {:op "completions" :prefix "x"} 2000)))))))

(deftest lookup-never-answers-and-unknown-ops-test
  (with-fake {}
    (fn [conn]
      (is (= :timeout (let [p (promise)]
                        (client/request! conn {:op "lookup" :sym "x"} (fn [m] (deliver p m)))
                        (deref p 300 :timeout))))
      (is (= [{:status ["error" "unknown-op" "done"]}]
             (strip (client/request-sync conn {:op "interrupt"} 2000)))))))

(deftest main-writes-port-file-test
  (let [dir (str (System/getProperty "java.io.tmpdir") "/fake-jank-" (System/currentTimeMillis))
        _ (.mkdirs (java.io.File. dir))
        fut (future (fake/-main dir))
        port-file (java.io.File. (str dir "/.nrepl-port"))
        _ (loop [n 0] (when (and (not (.exists port-file)) (< n 200)) (Thread/sleep 25) (recur (inc n))))
        port (Long/parseLong (str/trim (slurp port-file)))
        conn (client/connect port)]
    (try
      (is (= (set fake/jank-ops) (ops/op-names (client/request-sync conn {:op "describe"} 2000))))
      (is (= "[\"([w])\" \"Says hi.\"]" (:value (first (ev conn meta-code)))))
      (finally (client/close! conn) (fake/stop-main!) (future-cancel fut)))))
