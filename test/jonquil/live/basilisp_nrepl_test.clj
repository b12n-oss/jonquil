(ns jonquil.live.basilisp-nrepl-test
  "The nREPL dialect against a real Basilisp nREPL server, which each test
  starts in its own temp project and stops again. Run with
  `bb test:basilisp`; it skips when there's no basilisp (JONQUIL_BASILISP,
  else basilisp on PATH)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.process :as p]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.jvm]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def basilisp (or (System/getenv "JONQUIL_BASILISP") "basilisp"))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort s)))

(defn- listening? [port]
  (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true)
       (catch Exception _ false)))

(defn- with-basilisp
  "Starts basilisp nrepl-server on a free port in a temp project whose
  .jonquil.edn is (config port), waits until it listens (Basilisp takes a
  few seconds to start), calls (f dir port), then stops that server (only
  the one it started). Basilisp writes .nrepl-port in its working
  directory, the project. proc-opts merge into jolt.process's options
  (:in, for the server's own stdin)."
  ([config f] (with-basilisp config {} f))
  ([config proc-opts f]
   (let [dir (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-basilisp-nrepl-")))
         port (free-port)
         _ (spit (io/file dir ".jonquil.edn") (config port))
         _ (spit (io/file dir "a.lpy") "(ns demo.a)\n")
         proc (p/process (merge {:dir dir :out (io/file dir "basilisp.log") :err :out} proc-opts)
                         basilisp "nrepl-server" "--port" (str port))]
     (try
       (loop [n 0] (when (and (< n 400) (not (listening? port))) (Thread/sleep 50) (recur (inc n))))
       (f dir port)
       (finally
         (p/destroy proc)
         (when (= ::alive (deref proc 2000 ::alive))
           (.destroyForcibly ^java.lang.Process (:proc proc))))))))

(defn- make [dir]
  (let [path (str dir "/a.lpy")]
    (ev/make (dialect/select dialect/profiles (dialect/find-project path) path nil))))

(defn- run [e code run? path]
  (let [p (promise) out (atom [])]
    (ev/eval! e code {:run? run? :path path :on-output (fn [k s] (swap! out conj [k s]))
                      :on-complete (fn [r] (deliver p r))})
    [(deref p 10000 :timeout) @out]))

(deftest found-through-its-port-file-test
  (with-basilisp (fn [_] "{:dialect :nrepl}")
    (fn [dir port]
      (is (= (str port) (str/trim (slurp (io/file dir ".nrepl-port")))) "Basilisp wrote .nrepl-port")
      (let [e (make dir) path (str dir "/a.lpy")]
        (try
          (is (= :nrepl (:id (:profile (:selection e)))) "a .lpy file in the project gets the nREPL dialect")
          (is (= {:status :ok :printed "7"}
                 (select-keys (first (run e "(ns demo.a) (defn f [] 7) (f)" true path)) [:status :printed])))
          (is (= "7" (:printed (first (run e "(f)" false nil)))) "the REPL follows the Run's namespace")
          (finally (ev/stop! e)))))))

(deftest error-lines-test
  (with-basilisp (fn [port] (str "{:dialect :nrepl :nrepl {:port " port "}}"))
    (fn [dir _port]
      (let [e (make dir)]
        (try
          (testing "a runtime error: the traceback's last line"
            (is (= "Error: ZeroDivisionError: Fraction(1, 0)" (:error-text (first (run e "(/ 1 0)" false nil))))))
          (testing "a compiler error: the message line"
            (is (= "Error: unable to resolve symbol 'undefined-thing' in this context"
                   (:error-text (first (run e "(undefined-thing 1)" false nil))))))
          (testing "ex-info"
            (is (str/includes? (str (:error-text (first (run e "(throw (ex-info \"boom\" {:a 1}))" false nil))))
                               "boom")))
          (finally (ev/stop! e)))))))

(deftest help-test
  (with-basilisp (fn [port] (str "{:dialect :nrepl :nrepl {:port " port "}}"))
    (fn [dir _port]
      (let [e (make dir)]
        (try
          (run e "(+ 1 1)" false nil)
          (testing "quick docs through info, with Basilisp's own core.lpy as the file"
            (let [d (ev/lookup e "map" "(map" 4)]
              (is (str/includes? (str (:doc d)) "lazy sequence"))
              (is (str/ends-with? (str (:file d)) "basilisp/core.lpy") (pr-str (dissoc d :doc)))
              (is (:location d))))
          (testing "completion through complete, prefix only"
            (let [names (map :symbol (ev/complete e "map" "(map" 4 nil))]
              (is (some #{"mapv"} names))
              (is (every? #(str/starts-with? % "map") names))))
          (testing "the limitations"
            (let [ls (ev/limitations e)]
              (is (some #(str/includes? % "no interrupt op") ls))
              (is (some #(str/includes? % "no stdin op") ls))
              (is (not-any? #(str/includes? % "No quick docs") ls))
              (is (not-any? #(str/includes? % "completion op") ls))))
          (finally (ev/stop! e)))))))

(deftest a-read-says-where-to-type-and-reads-the-servers-stdin-test
  ;; Basilisp's read-line reads the server process's own stdin; closed here,
  ;; so it gets "" at once.
  (with-basilisp (fn [port] (str "{:dialect :nrepl :nrepl {:port " port "}}"))
    {:in (io/file "/dev/null")}
    (fn [dir _port]
      (let [e (make dir)]
        (try
          (run e "(+ 1 1)" false nil)
          (let [[r out] (run e "(read-line)" false nil)]
            (is (some #(str/includes? (second %) "on Basilisp it reads the server's own stdin") out) "said first")
            (is (= {:status :ok :printed "\"\""} (select-keys r [:status :printed]))))
          (finally (ev/stop! e)))))))

(deftest stop-detaches-and-the-next-run-reconnects-test
  (with-basilisp (fn [port] (str "{:dialect :nrepl :nrepl {:host \"localhost\" :port " port "}}"))
    (fn [dir _port]
      (let [e (make dir)]
        (try
          (run e "(+ 1 1)" false nil)
          (let [p (promise)]
            (ev/eval! e "(loop [] (recur))" {:on-output (fn [_ _]) :on-complete #(deliver p %)})
            (Thread/sleep 400)
            (ev/interrupt! e)
            (is (= :interrupted (:status (deref p 5000 :timeout))) "no interrupt op: Stop detaches"))
          (is (= "3" (:printed (first (run e "(+ 1 2)" false nil)))) "a fresh session answers")
          (finally (ev/stop! e)))))))
