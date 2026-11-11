(ns jonquil.live.bb-nrepl-test
  "The nREPL dialect against a real babashka nREPL server, which each test
  starts on a free port in its own temp project and stops again. Run with
  `jolt test:bb`; it skips when no bb is on PATH."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.process :as p]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.jvm]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort s)))

(defn- listening? [port]
  (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true)
       (catch Exception _ false)))

(defn- with-bb
  "Starts bb nrepl-server on a free port in a temp project whose
  .jonquil.edn is (config port), waits until it listens, calls (f dir
  port), then stops that server (only the one it started)."
  [config f]
  (let [dir (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-bb-nrepl-")))
        port (free-port)
        _ (spit (io/file dir "bb.edn") "{}")
        _ (spit (io/file dir ".jonquil.edn") (config port))
        _ (spit (io/file dir "a.clj") "(ns demo.a)\n")
        proc (p/process {:dir dir :out (io/file dir "bb.log") :err :out} "bb" "nrepl-server" (str port))]
    (try
      (loop [n 0] (when (and (< n 100) (not (listening? port))) (Thread/sleep 50) (recur (inc n))))
      (f dir port)
      (finally
        (p/destroy proc)
        (when (= ::alive (deref proc 2000 ::alive))
          (.destroyForcibly ^java.lang.Process (:proc proc)))))))

(defn- make [dir]
  (let [path (str dir "/a.clj")]
    (ev/make (dialect/select dialect/profiles (dialect/find-project path) path nil))))

(defn- run [e code run? path]
  (let [p (promise) out (atom [])]
    (ev/eval! e code {:run? run? :path path :on-output (fn [k s] (swap! out conj [k s]))
                      :on-complete (fn [r] (deliver p r))})
    [(deref p 10000 :timeout) @out]))

(deftest eval-run-and-help-test
  (with-bb (fn [port] (str "{:dialect :nrepl :nrepl {:port " port "}}"))
    (fn [dir port]
      (let [e (make dir) path (str dir "/a.clj")]
        (try
          (let [[r out] (run e "(ns demo.a) (defn f [] 7) (f)" true path)]
            (is (= {:status :ok :printed "7"} (select-keys r [:status :printed])))
            (is (some #(str/includes? (second %) (str "at 127.0.0.1:" port)) out) "the connecting line names the host"))
          (is (= "7" (:printed (first (run e "(f)" false nil)))) "the REPL follows the Run's namespace")
          (testing "the error line has the message, not just the class"
            (is (str/starts-with? (str (:error-text (first (run e "(/ 1 0)" false nil))))
                                  "Error: java.lang.ArithmeticException: Divide by zero")))
          (testing "completion through babashka's complete, prefix only"
            (let [names (map :symbol (ev/complete e "map" "(map" 4 nil))]
              (is (some #{"mapv"} names))
              (is (every? #(str/starts-with? % "map") names) (pr-str (remove #(str/starts-with? % "map") names)))))
          (testing "lookup: docs, and no :file that isn't on this disk"
            ;; after the Run, babashka names file:<project>/clojure/core.clj
            (let [d (ev/lookup e "map" "(map" 4)]
              (is (str/includes? (str (:doc d)) "lazy sequence"))
              (is (not (contains? d :file)) (pr-str (dissoc d :doc :arglists)))))
          (testing "the limitations name what this server lacks, and nothing about a JVM"
            (let [ls (ev/limitations e)]
              (is (some #(str/includes? % "no interrupt op") ls))
              (is (some #(str/includes? % "no stdin op") ls))
              (is (not-any? #(str/includes? % "JVM") ls))))
          (finally (ev/stop! e)))))))

(deftest stop-detaches-and-the-next-run-reconnects-test
  (with-bb (fn [port] (str "{:dialect :nrepl :nrepl {:host \"localhost\" :port " port "}}"))
    (fn [dir _port]
      (let [e (make dir)]
        (try
          (run e "(+ 1 1)" false nil)
          (let [p (promise)]
            (ev/eval! e "(loop [] (recur))" {:on-output (fn [_ _]) :on-complete #(deliver p %)})
            (Thread/sleep 400)
            (ev/interrupt! e)
            (is (= :interrupted (:status (deref p 5000 :timeout))) "babashka has no interrupt op: Stop detaches"))
          (is (= "3" (:printed (first (run e "(+ 1 2)" false nil)))) "a fresh session answers")
          (finally (ev/stop! e)))))))

(deftest a-read-says-it-waits-and-stop-frees-the-window-test
  (with-bb (fn [port] (str "{:dialect :nrepl :nrepl {:port " port "}}"))
    (fn [dir _port]
      (let [e (make dir)]
        (try
          (run e "(+ 1 1)" false nil)
          (let [p (promise) out (atom [])]
            (ev/eval! e "(read-line)" {:on-output (fn [k s] (swap! out conj [k s])) :on-complete #(deliver p %)})
            (Thread/sleep 400)
            (is (some #(str/includes? (second %) "no stdin") @out) "said before it blocks")
            (ev/interrupt! e)
            (is (= :interrupted (:status (deref p 5000 :timeout)))))
          (is (= "3" (:printed (first (run e "(+ 1 2)" false nil)))) "the next eval gets a fresh session")
          (finally (ev/stop! e)))))))

(deftest the-port-file-is-read-when-no-port-is-given-test
  (with-bb (fn [_] "{:dialect :nrepl}")
    (fn [dir port]
      (spit (io/file dir ".nrepl-port") (str port))
      (let [e (make dir)]
        (try
          (is (= "4" (:printed (first (run e "(+ 2 2)" false nil)))))
          (finally (ev/stop! e)))))))

(deftest nothing-listening-says-where-it-looked-test
  (let [dir (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-bb-nrepl-")))
        port (free-port)]
    (spit (io/file dir ".jonquil.edn") (str "{:dialect :nrepl :nrepl {:port " port "}}"))
    (let [e (make dir)
          [r _] (run e "(+ 1 1)" false nil)]
      (is (= :notice (:status r)))
      (is (str/includes? (str (:text r)) (str "127.0.0.1:" port)))
      (ev/stop! e))))
