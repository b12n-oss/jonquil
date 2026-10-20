(ns ^{:clj-kondo/config '{:lint-as {jonquil.live.cljs-node-live-test/with-cljs clojure.core/let}}}
 jonquil.live.cljs-node-live-test
  "Drives jonquil.nrepl.cljs-node against REAL ClojureScript on node: each
  evaluator launches its own clojure + node prepl in a private run dir, over
  one temp project. Run with `bb test:cljs-node`, not `bb test`: it needs the
  clojure CLI and node (ClojureScript comes from ~/.m2 or the network). The
  runner skips with exit 0 when either is missing.

  Every test stops its evaluator in a finally, so no child outlives it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jolt.process :as p]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.cljs-node :as cljs-node]
            [temp-dirs]))

(def deps-text "{:paths [\"src\"]\n :deps {org.clojure/clojurescript {:mvn/version \"1.12.42\"}}}\n")

(def util-text "(ns demo.util)\n(defn greet [x] (str \"hi \" x))\n")

(def ^:dynamic *dir* nil)

(defn- project-fixture [f]
  (let [dir (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-cljs-node-live-")))]
    (.mkdirs (io/file dir "src/demo"))
    (spit (io/file dir "deps.edn") deps-text)
    (spit (io/file dir "src/demo/util.cljs") util-text)
    (try (binding [*dir* dir] (f))
         (finally (temp-dirs/delete-made!)))))

(use-fixtures :once project-fixture)

(defn- make []
  (cljs-node/make-cljs-node {:profile dialect/cljs :runtime :launch :project-dir *dir*}))

(defmacro ^:private with-cljs
  "Binds sym to a fresh evaluator and stops it afterwards. Written as
  (with-cljs [e (make)] ...), which clj-kondo reads as a let (see the ns config)."
  [[sym init] & body]
  `(let [~sym ~init]
     (try ~@body (finally (ev/stop! ~sym)))))

(defn- run
  "Evaluates and waits. {:result r :out [[kind text]...]}."
  [e code]
  (let [done (promise) out (atom [])]
    (ev/eval! e code {:run? false
                      :on-output (fn [k s] (swap! out conj [k s]))
                      :on-complete (fn [r] (deliver done r))})
    {:result (deref done 60000 :timeout) :out @out}))

(defn- sh-out [& args]
  (let [r @(apply p/process {:out :string} args)]
    (str (:out r))))

(defn- process-tree
  "pid and every descendant pid, as strings (pgrep -P, recursively)."
  [pid]
  (let [kids (str/split-lines (str/trim (sh-out "pgrep" "-P" (str pid))))
        kids (remove str/blank? kids)]
    (into [(str pid)] (mapcat process-tree) kids)))

(defn- pid-alive? [pid]
  (let [r (deref (p/process "ps" "-p" (str pid)) 5000 :timeout)]
    (or (= :timeout r) (zero? (:exit r)))))

(deftest defs-persist-within-a-run-live
  (with-cljs [e (make)]
    (let [{:keys [result out]} (run e "(def a 1) (def b 2) (+ a b)")]
      (is (= :ok (:status result)) (pr-str [result out]))
      (is (= "3" (:printed result)) (pr-str result)))))

(deftest println-streams-live
  (with-cljs [e (make)]
    (let [{:keys [result out]} (run e "(println \"hello\")")]
      (is (= :ok (:status result)) (pr-str [result out]))
      (is (some #(= [:out "hello\n"] %) out) (pr-str out)))))

(deftest error-then-recover-live
  (with-cljs [e (make)]
    (let [{:keys [result out]} (run e "(throw (js/Error. \"boom\"))")]
      (is (= :error (:status result)) (pr-str [result out]))
      (is (str/includes? (str (:error-text result) (apply str (map second out))) "boom")
          (pr-str [result out])))
    (let [{:keys [result]} (run e "(+ 2 3)")]
      (is (= "5" (:printed result)) (pr-str result)))))

(deftest require-project-namespace-live
  (with-cljs [e (make)]
    (let [{:keys [result out]} (run e "(require 'demo.util)")]
      (is (= :ok (:status result)) (pr-str [result out])))
    (let [{:keys [result out]} (run e "(demo.util/greet \"x\")")]
      (is (= :ok (:status result)) (pr-str [result out]))
      (is (= "\"hi x\"" (:printed result)) (pr-str result)))))

(defn- node-pids
  "The pids among pids whose command's first word ends with node."
  [pids]
  (filter (fn [pid]
            (let [cmd (str/trim (sh-out "ps" "-o" "command=" "-p" pid))
                  word (first (str/split cmd #"\s+"))]
              (str/ends-with? (str word) "node")))
          pids))

(defn- listen-rows [pid]
  (rest (str/split-lines (sh-out "lsof" "-nP" "-a" "-p" pid "-iTCP" "-sTCP:LISTEN"))))

(deftest stop-kills-the-tree-live
  (with-cljs [e (make)]
    (is (= "1" (:printed (:result (run e "1")))) "the first eval starts the child")
    (let [child (cljs-node/child-pid e)
          _ (is (some? child))
          pids (process-tree child)
          nodes (node-pids pids)
          done (promise)]
      (is (seq nodes) (str "node is among the child's tree: " (pr-str pids)))
      (ev/eval! e "(loop [] (recur))" {:run? false :on-complete (fn [r] (deliver done r))})
      (Thread/sleep 1000)
      (let [t0 (System/currentTimeMillis)]
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 5000 :timeout))))
        (is (< (- (System/currentTimeMillis) t0) 5000)))
      (let [{:keys [result]} (run e "(+ 4 5)")]
        (is (= "9" (:printed result)) (pr-str result)))
      (is (not-any? pid-alive? pids) (str "alive: " (pr-str (filter pid-alive? pids)))))))

(deftest listeners-are-loopback-only-live
  (with-cljs [e (make)]
    (is (= "2" (:printed (:result (run e "(+ 1 1)")))) "the first eval starts the child")
    (let [child (cljs-node/child-pid e)
          _ (is (some? child))
          pids (process-tree child)
          nodes (node-pids pids)]
      (is (seq nodes) (str "node is among the child's tree: " (pr-str pids)))
      (is (some #(seq (listen-rows %)) nodes) "node owns a listening socket")
      (doseq [pid pids
              row (listen-rows pid)]
        (let [name-col (nth (reverse (str/split (str/trim row) #"\s+")) 1)]
          (is (str/starts-with? name-col "127.0.0.1:") row))))))
