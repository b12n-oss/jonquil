(ns ^{:clj-kondo/config '{:lint-as {jonquil.live.jank-live-test/with-jank clojure.core/let}}}
 jonquil.live.jank-live-test
  "Drives jonquil.nrepl.jank against a REAL jank: each evaluator launches
  its own `jank repl` in a private run dir, over one temp project. Run with
  `bb test:jank`, not `bb test`: it needs a jank binary (JONQUIL_JANK, PATH,
  /opt/homebrew/bin or ~/.local/bin). The runner skips with exit 0 when
  there is none.

  Every test stops its evaluator in a finally, so no jank outlives it.
  Nothing here sends the lookup op: ev/lookup goes through an eval of the
  var's metadata (jank issue 998)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jolt.process :as p]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.jank :as jank]
            [temp-dirs]))

(def util-text "(ns demo.util)\n(defn twice [x] (* 2 x))\n")

(def core-text
  ;; :refer as well as :as, so `twice` resolves from demo.core's own buffer.
  "(ns demo.core\n  (:require [demo.util :as u :refer [twice]]))\n\n(defn go [] (u/twice 4))\n")

(def busy-loop
  ;; Not (loop [] (recur)): an unconditional recur in a loop fails to
  ;; compile on both jank builds ('break' statement not in loop, jank core.jank
  ;; line 3594; re-checked 2026-10-12), so it never runs. This one does.
  "(loop [i 0] (when true (recur (inc i))))")

(def ^:dynamic *dir* nil)

(defn- project-fixture [f]
  (let [dir (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-jank-live-")))]
    (.mkdirs (io/file dir "src/demo"))
    (spit (io/file dir "src/demo/util.jank") util-text)
    (spit (io/file dir "src/demo/core.jank") core-text)
    (try (binding [*dir* dir] (f))
         (finally (temp-dirs/delete-made!)))))

(use-fixtures :once project-fixture)

(defn- make []
  (jank/make-jank {:profile dialect/jank :runtime :launch :project-dir *dir*}))

(defmacro ^:private with-jank
  "Binds sym to a fresh evaluator and stops it afterwards. Written as
  (with-jank [e (make)] ...), which clj-kondo reads as a let (see the ns config)."
  [[sym] & body]
  `(let [~sym (make)]
     (try ~@body (finally (ev/stop! ~sym)))))

(defn- run
  "Evaluates and waits. {:result r :out [[kind text]...]}."
  [e code opts]
  (let [done (promise) out (atom [])]
    (ev/eval! e code (assoc opts
                            :on-output (fn [k s] (swap! out conj [k s]))
                            :on-complete (fn [r] (deliver done r))))
    {:result (deref done 30000 :timeout) :out @out}))

(defn- core-path [] (str *dir* "/src/demo/core.jank"))

(defn- run-core! [e]
  (run e core-text {:run? true :path (core-path)}))

(defn- pid-alive? [pid]
  (let [r (deref (p/process "ps" "-p" (str pid)) 5000 :timeout)]
    (zero? (:exit r))))

(deftest run-requires-across-files-live
  (with-jank [e (make)]
    (let [{:keys [result out]} (run-core! e)]
      (is (= :ok (:status result)) (pr-str [result out])))
    (let [{:keys [result out]} (run e "(u/twice 21)" {:run? false})]
      (is (= :ok (:status result)) (pr-str [result out]))
      (is (= "42" (:printed result))))))

(deftest stop-then-fresh-live
  (with-jank [e (make)]
    (is (= :ok (:status (:result (run-core! e)))))
    (let [done (promise)
          pid (jank/child-pid e)]
      (ev/eval! e busy-loop {:run? false :on-complete (fn [r] (deliver done r))})
      (Thread/sleep 500)
      (ev/interrupt! e)
      (is (= :interrupted (:status (deref done 3000 :timeout))))
      (is (some? pid))
      (let [{:keys [result]} (run e "(some? (find-ns 'demo.core))" {:run? false})]
        (is (= :ok (:status result)) (pr-str result))
        (is (= "false" (:printed result)) "a fresh process: the Run's namespace is gone")))))

(deftest reset-live
  (with-jank [e (make)]
    (ev/set-mode! e :reset)
    (is (= :ok (:status (:result (run-core! e)))))
    (let [pid1 (jank/child-pid e)]
      (is (= :ok (:status (:result (run-core! e)))))
      (let [pid2 (jank/child-pid e)]
        (is (and pid1 pid2 (not= pid1 pid2)) (pr-str [pid1 pid2]))))))

(deftest docs-live
  (with-jank [e (make)]
    (is (= :ok (:status (:result (run-core! e)))))
    (let [d (ev/lookup e "twice" core-text 0)]
      (is (= :found (:status d)) (pr-str d))
      (is (= "([x])" (:arglists d)) (pr-str d)))
    (let [d (ev/lookup e "no-such-thing-here" core-text 0)]
      (is (str/includes? (str (:doc d)) "No docs for") (pr-str d)))))

(deftest completions-live
  ;; The buffer names twice, so the offline candidates cover it too: this is
  ;; what a person sees in a core.jank window.
  (with-jank [e (make)]
    (is (= :ok (:status (:result (run-core! e)))))
    (let [cands (ev/complete e "tw" core-text 3 {})]
      (is (some #(= "twice" (:symbol %)) cands) (pr-str cands)))))

(deftest completions-from-jank-live
  ;; A buffer that never mentions twice, so only jank's completions op can
  ;; supply it. Homebrew jank 0.1's op answers builtins only (measured
  ;; 2026-10-12); the upstream build lists loaded user vars.
  (with-jank [e (make)]
    (is (= :ok (:status (:result (run-core! e)))))
    (let [cands (ev/complete e "tw" "(tw" 3 {})]
      (is (some #(= "twice" (:symbol %)) cands) (pr-str cands)))))

(deftest error-live
  (with-jank [e (make)]
    (let [{:keys [result out]} (run e "(nope 1)" {:run? false})]
      (is (= :error (:status result)) (pr-str [result out]))
      (is (str/includes? (str (:error-text result) (apply str (map second out))) "nope")
          (pr-str [result out])))))

(deftest project-port-file-untouched-live
  (let [dir (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-jank-live-port-")))]
    (.mkdirs (io/file dir "src/demo"))
    (spit (io/file dir "src/demo/util.jank") util-text)
    (spit (io/file dir ".nrepl-port") "12345")
    (let [e (jank/make-jank {:profile dialect/jank :runtime :launch :project-dir dir})]
      (try
        (is (= :ok (:status (:result (run e util-text {:run? true :path (str dir "/src/demo/util.jank")})))))
        (finally (ev/stop! e))))
    (is (= "12345" (slurp (io/file dir ".nrepl-port"))))
    (let [ports (filter #(= ".nrepl-port" (.getName %)) (file-seq (io/file dir)))]
      (is (= [(str dir "/.nrepl-port")] (map #(.getPath %) ports))))))

(deftest no-leftover-live
  (let [e (make)]
    (try
      (is (= :ok (:status (:result (run e "(+ 1 2)" {:run? false})))))
      (let [pid (jank/child-pid e) d (jank/run-dir e)]
        (is (some? pid))
        (is (pid-alive? pid))
        (ev/stop! e)
        (is (not (pid-alive? pid)) "the child process is gone")
        (is (not (.exists (io/file d))) "the run dir is gone"))
      (finally (ev/stop! e)))))
