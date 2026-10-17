(ns jonquil.nrepl.shadow-test
  "The shadow-cljs attach evaluator against dev/fake_shadow_nrepl.clj's fake
  server. Every test owns its temp project and its fake server, and stops
  both."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [fake-shadow-nrepl :as fake]
            [jonquil.cljs :as cljs]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.shadow :as shadow]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def shadow-edn "{:builds {:app {:target :browser}}}\n")

(defn- temp-project
  "A throwaway shadow-cljs project: shadow-cljs.edn, src/demo/core.cljs and
  .shadow-cljs/nrepl.port holding port-text (none when nil). [dir file]."
  [port-text]
  (let [d (io/file (temp-dirs/temp-dir! "jonquil-shadow-test-"))
        f (io/file d "src/demo/core.cljs")]
    (.mkdirs (.getParentFile f))
    (.mkdirs (io/file d ".shadow-cljs"))
    (spit (io/file d "shadow-cljs.edn") shadow-edn)
    (spit f "(ns demo.core)\n")
    (when port-text (spit (io/file d ".shadow-cljs/nrepl.port") port-text))
    [(.getCanonicalPath d) (.getCanonicalPath f)]))

(defn- make [dir]
  (shadow/make-shadow {:profile dialect/cljs :runtime :attach :project-dir dir}
                      {:describe-timeout-ms 500 :clone-timeout-ms 500 :still-waiting-ms 200 :help-timeout-ms 500}))

(defn- run
  "Evaluates and waits. [result output] where output is every on-output
  call as [kind text]."
  [e code opts]
  (let [done (promise) out (atom [])]
    (ev/eval! e code (assoc opts
                            :on-output (fn [k s] (swap! out conj [k s]))
                            :on-complete (fn [r] (deliver done r))))
    [(deref done 10000 :timeout) @out]))

(defn- texts [out kind] (keep (fn [[k s]] (when (= k kind) s)) out))

(defn- until
  "True once (pred) is, within two seconds."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 10) (recur))))))

(defn- with-project
  "Runs (f srv dir e) against a fake server (opts) whose port is in the
  project's nrepl.port and a fresh evaluator, then cleans up."
  [opts f]
  (let [srv (fake/start! opts)
        [dir _] (temp-project (str (:port srv) "\n"))
        e (make dir)]
    (try (f srv dir e)
         (finally (ev/stop! e) (fake/stop! srv) (temp-dirs/delete-tree! dir)))))

(def run1 "(ns probe.run1) (def x 41) (defn bump [n] (inc n)) (bump x)")

(deftest make-dispatches-on-dialect-test
  (let [e (ev/make {:profile dialect/cljs :runtime :attach :project-dir "/nowhere"})]
    (is (instance? jonquil.nrepl.shadow.Shadow e))
    (is (not (connection/connected? e)))
    (is (nil? (ev/start! e)))
    (is (= 'cljs.user (ev/prompt-ns e)))
    (is (satisfies? ev/FullDoc e))))

(deftest connect-rules-test
  (testing "no port file: the not-running notice"
    (let [[dir _] (temp-project nil) e (make dir)]
      (try
        (is (= {:status :notice :text (cljs/not-running-message dir)} (first (run e "1" {:run? false}))))
        (finally (temp-dirs/delete-tree! dir)))))
  (testing "no project"
    (let [e (shadow/make-shadow {:profile dialect/cljs :runtime :attach :project-dir nil})]
      (is (= {:status :notice :text cljs/no-project-message} (first (run e "1" {:run? false}))))))
  (testing "several builds and none named: the notice, and no connection"
    (let [srv (fake/start!)
          [dir _] (temp-project (str (:port srv) "\n"))
          e (make dir)]
      (try
        (spit (io/file dir "shadow-cljs.edn") "{:builds {:app {} :admin {}}}")
        (let [[r _] (run e "1" {:run? false})]
          (is (= :notice (:status r)))
          (is (str/includes? (:text r) "Several shadow-cljs builds")))
        (is (empty? (fake/ops-seen srv)))
        (testing "the file is re-read on the next connect"
          (spit (io/file dir "shadow-cljs.edn") shadow-edn)
          (is (= :ok (:status (first (run e "1" {:run? false}))))))
        (finally (ev/stop! e) (fake/stop! srv) (temp-dirs/delete-tree! dir))))))

(deftest run-happy-path-test
  (with-project {}
    (fn [srv _ e]
      (let [[r _] (run e run1 {:run? true})]
        (is (= {:status :notice :text "; Ran probe.run1 (4 forms)"} r))
        (is (connection/connected? e))
        (is (= 'probe.run1 (ev/prompt-ns e)))
        (is (= 1 (count (filter #(= (cljs/select-code :app) (get % "code")) (fake/requests srv)))))
        (testing "the buffer's own ns decides: Run sends no :ns"
          (is (not-any? #(contains? % "ns") (filter #(= "eval" (get % "op")) (fake/requests srv)))))
        (testing "a second Run doesn't select again; the stale state noise broke nothing"
          (is (= "; Ran probe.run1 (4 forms)" (:text (first (run e run1 {:run? true})))))
          (is (= 1 (count (filter #(= (cljs/select-code :app) (get % "code")) (fake/requests srv))))))))))

(deftest run-with-a-throw-test
  (with-project {}
    (fn [_ _ e]
      (let [code "(ns probe.run2) (def x 1) (throw (js/Error. \"boom\")) (def y 2) (inc x)"
            [r out] (run e code {:run? true})]
        (is (= {:status :notice
                :text "; Ran probe.run2 (5 forms, 1 error; an error doesn't stop the rest of the file)"}
               r))
        (is (some #(str/includes? % "boom") (texts out :err)) "the error text reached on-output")
        (is (not-any? #(str/includes? (str (second %)) ":repl/exception!") out))
        (is (not (str/includes? (str (:text r) (:printed r)) ":repl/exception!")))))))

(deftest no-runtime-test
  (with-project {:no-runtime? true}
    (fn [_ _ e]
      (is (= {:status :notice :text cljs/no-runtime-message} (first (run e run1 {:run? true}))))
      (is (= {:status :notice :text cljs/no-runtime-message} (first (run e "(+ 1 2)" {:run? false})))))))

(deftest select-failure-test
  (with-project {:watch-running? false}
    (fn [srv _ e]
      (let [[r _] (run e "(+ 1 2)" {:run? false})]
        (is (= :notice (:status r)))
        (is (str/includes? (:text r) "watch for build not running"))
        (is (connection/connected? e) "the CLJ session is fine, so the connection stays")
        (is (false? (:selected? @(:state e))))
        (run e "(+ 1 2)" {:run? false})
        (is (= 2 (count (filter #(= (cljs/select-code :app) (get % "code")) (fake/requests srv))))
            "the next evaluation tries to select again")))))

(deftest repl-entry-test
  (with-project {:value "7"}
    (fn [srv _ e]
      (let [[r _] (run e "(+ 3 4)" {:run? false})]
        (is (= {:status :ok :printed "7" :ns 'cljs.user} r))
        (is (= "cljs.user" (get (last (fake/requests srv)) "ns")) "a REPL entry names the prompt ns"))
      (testing "a selection names the buffer's ns"
        (run e "(+ 3 4)" {:run? false :ns-source "(ns some.where)\n"})
        (is (= "some.where" (get (last (fake/requests srv)) "ns"))))
      (testing "a throw is an error result built from :err"
        (let [[r out] (run e "(throw (js/Error. \"boom\"))" {:run? false})]
          (is (= {:status :error :error-text "Error: boom"} r))
          (is (some #(str/includes? % "boom") (texts out :err))))))))

(deftest quit-reselects-test
  (with-project {}
    (fn [srv _ e]
      (run e "(+ 1 2)" {:run? false})
      (is (true? (:selected? @(:state e))))
      (let [[r _] (run e ":repl/quit" {:run? false})]
        (is (= :ok (:status r))))
      (is (false? (:selected? @(:state e))))
      (is (= 'shadow.user (ev/prompt-ns e)))
      (is (= :ok (:status (first (run e "(+ 1 2)" {:run? false})))))
      (is (true? (:selected? @(:state e))))
      (is (= 2 (count (filter #(= (cljs/select-code :app) (get % "code")) (fake/requests srv))))
          "the fake observed a second select"))))

(deftest stop-detaches-test
  (with-project {}
    (fn [srv _ e]
      (run e "(+ 1 2)" {:run? false})
      (is (connection/connected? e))
      (ev/interrupt! e)
      (is (until #(not (connection/connected? e))))
      (is (false? (:selected? @(:state e))))
      (is (not-any? #{"interrupt"} (fake/ops-seen srv)))
      (testing "the next evaluation reconnects and selects again"
        (is (= :ok (:status (first (run e "(+ 1 2)" {:run? false})))))
        (is (= 2 (count (filter #(= (cljs/select-code :app) (get % "code")) (fake/requests srv)))))))))

(deftest completion-and-docs-test
  (with-project {}
    (fn [_ _ e]
      (testing "offline until connected: never connects on its own"
        (is (not-any? #(= "mapv" (:symbol %)) (ev/complete e "mapv" "" 0 "")))
        (is (= :no-docs (:status (ev/lookup e "map" "" 0))))
        (let [got (promise)]
          (ev/full-doc e "map" "" (fn [r] (deliver got r)))
          (is (= {:status :not-attached} (deref got 2000 :timeout)))))
      (run e "(+ 1 2)" {:run? false})
      (testing "the server's candidates merge into the offline ones"
        (is (some #(= "mapv" (:symbol %)) (ev/complete e "map" "" 0 ""))))
      (testing "lookup reads eldoc"
        (is (= {:status :found :symbol "map" :ns "cljs.core" :name "map" :arglists "([f] [f coll])"
                :doc fake/map-doc}
               (ev/lookup e "map" "" 0)))
        (is (= :no-docs (:status (ev/lookup e "nope" "" 0)))))
      (testing "full doc"
        (let [got (promise)]
          (ev/full-doc e "map" "" (fn [r] (deliver got r)))
          (let [r (deref got 2000 :timeout)]
            (is (= :ok (:status r)))
            (is (str/includes? (:text r) fake/map-doc))))
        (let [got (promise)]
          (ev/full-doc e "nope" "" (fn [r] (deliver got r)))
          (is (= {:status :not-found} (deref got 2000 :timeout))))))))

(deftest bare-ops-test
  (with-project {:bare-ops? true}
    (fn [srv _ e]
      (run e "(+ 1 2)" {:run? false})
      (is (connection/connected? e))
      (testing "completion and docs stay offline when the server lacks the ops"
        (let [before (count (fake/ops-seen srv))]
          (is (vector? (ev/complete e "map" "" 0 "")))
          (is (= :no-docs (:status (ev/lookup e "map" "" 0))))
          (let [got (promise)]
            (ev/full-doc e "map" "" (fn [r] (deliver got r)))
            (is (= {:status :no-op} (deref got 2000 :timeout))))
          (is (= before (count (fake/ops-seen srv))) "nothing was sent"))))))
