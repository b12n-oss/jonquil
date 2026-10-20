(ns jonquil.nrepl.cljs-child-test
  "The ClojureScript child against dev/fake-cljs-clojure (ignores its args,
  serves a fake prepl, prints the ready line), plus find-clojure."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jolt.process :as p]
            [jonquil.cljs-node :as cljs-node]
            [jonquil.nrepl.cljs-child :as child]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def fake-clojure (.getCanonicalPath (io/file "dev/fake-cljs-clojure")))

(defn- run-dirs []
  (set (filter #(re-matches #"jonquil-cljs-run-.+" %)
               (seq (.list (io/file (System/getProperty "java.io.tmpdir")))))))

(defn- pgrep? [pattern]
  (let [r (p/sh "pgrep" "-f" pattern)]
    (boolean (and (zero? (:exit r)) (not (str/blank? (:out r)))))))

(defn- connects? [port]
  (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true)
       (catch Exception _ false)))

(deftest find-clojure-test
  (is (= "/x/clj" (child/find-clojure {:env {"JONQUIL_CLOJURE" "/x/clj"}
                                       :exists? (constantly true)})))
  (is (= "/b/clojure" (child/find-clojure {:env {} :path "/a:/b"
                                           :exists? #(= "/b/clojure" %)})))
  (is (nil? (child/find-clojure {:env {} :path "/a:/b" :exists? (constantly false)}))))

(defn- owner-only? [dir]
  (= #{"OWNER_READ" "OWNER_WRITE" "OWNER_EXECUTE"}
     (set (map str (java.nio.file.Files/getPosixFilePermissions
                    (.toPath (io/file dir))
                    (make-array java.nio.file.LinkOption 0))))))

(deftest start-and-kill-test
  (let [c (child/start! {:clojure fake-clojure :launcher "l.clj"})]
    (try
      (is (nil? (:error c)) (str c))
      (is (connects? (:port c)))
      (is (child/alive? c))
      (is (.exists (io/file (:log c))))
      (is (contains? (run-dirs) (.getName (io/file (:run-dir c)))) "run-dirs sees a live run dir")
      (is (owner-only? (:run-dir c)) "the run dir is created owner-only")
      (finally (child/kill! c)))
    (is (not (child/alive? c)))
    (is (not (.exists (io/file (:run-dir c)))))
    (is (nil? (child/kill! c)) "idempotent")
    (is (nil? (child/kill! nil)))))

(deftest project-with-clojurescript-gets-no-sdeps-test
  (let [with (temp-dirs/temp-dir! "jonquil-cljs-test-")
        without (temp-dirs/temp-dir! "jonquil-cljs-test-")
        seen (atom [])
        spy (fn [opts] (swap! seen conj opts) (cljs-node/launch-args opts))]
    (spit (io/file with "deps.edn")
          "{:deps {org.clojure/clojurescript {:mvn/version \"1.11.132\"}}}")
    (doseq [d [with without]]
      (let [c (child/start! {:clojure fake-clojure :launcher "l.clj" :project-dir d :args-fn spy})]
        (try (is (nil? (:error c)) (str c))
             (finally (child/kill! c)))))
    (is (= [false true] (mapv :add-dep? @seen)))))

(deftest exits-at-once-test
  (let [r (child/start! {:clojure "/usr/bin/false" :launcher "l.clj"})]
    (try
      (is (string? (:error r)) (str r))
      (is (str/includes? (:error r) "exit 1"))
      (is (str/includes? (:error r) "The log: "))
      (let [log (second (re-find #"The log: (.+)$" (:error r)))]
        (is (.exists (io/file log)) "a failed start keeps the log"))
      (finally
        (when-let [log (some->> (:error r) (re-find #"The log: (.+)$") second)]
          (#'child/delete-tree! (.getParent (io/file log))))))))

(deftest cancelled-test
  (let [slow (str (temp-dirs/temp-dir! "jonquil-cljs-test-") "/slow-clojure")
        before (run-dirs)
        t0 (System/currentTimeMillis)]
    (spit slow "#!/usr/bin/env bash\nsleep 61\n")
    (.setExecutable (io/file slow) true)
    (is (= {:error :cancelled}
           (child/start! {:clojure slow :launcher "l.clj"
                          :cancelled? #(> (- (System/currentTimeMillis) t0) 200)})))
    (is (not (pgrep? "slow-clojure")))
    (is (= before (run-dirs)))))

(defn- fake-script!
  "An executable script in a fresh temp dir with the given body."
  [body]
  (let [f (io/file (temp-dirs/temp-dir! "jonquil-cljs-fake-") "fake-clojure")]
    (spit f (str "#!/usr/bin/env bash\n" body "\n"))
    (.setExecutable f true)
    (.getCanonicalPath f)))

(deftest target-and-http-port-reach-args-fn-test
  (let [seen (atom nil)
        spy (fn [opts] (reset! seen opts) (cljs-node/launch-args opts))
        c (child/start! {:clojure fake-clojure :launcher "l.clj" :args-fn spy
                         :target :browser :http-port 9123})]
    (try (is (nil? (:error c)) (str c))
         (is (= [:browser 9123] ((juxt :target :http-port) @seen)))
         (is (nil? (:url c)) "the node-shaped fake prints no URL")
         (finally (child/kill! c)))))

(deftest browser-ready-line-gives-url-test
  (let [script (fake-script!
                "echo 'jonquil-cljs-prepl 4455 http://localhost:9123/'\ncat >/dev/null")
        c (child/start! {:clojure script :launcher "l.clj"
                         :target :browser :http-port 9123})]
    (try (is (nil? (:error c)) (str c))
         (is (= 4455 (:port c)))
         (is (= "http://localhost:9123/" (:url c)))
         (finally (child/kill! c)))))

(deftest http-port-taken-test
  (let [script (fake-script!
                "echo 'java.net.BindException: Address already in use'\nexit 1")
        before (run-dirs)
        r (child/start! {:clojure script :launcher "l.clj"
                         :target :browser :http-port 9123})]
    (is (= {:error :http-port-taken} r))
    (is (= before (run-dirs)) "the run dir is removed")))

(deftest bind-failure-text-is-an-ordinary-failure-for-node-test
  (let [script (fake-script! "echo 'Address already in use'\nexit 1")
        r (child/start! {:clojure script :launcher "l.clj" :target :node})]
    (try (is (string? (:error r)) (str r))
         (finally
           (when-let [log (some->> (:error r) (re-find #"The log: (.+)$") second)]
             (#'child/delete-tree! (.getParent (io/file log))))))))
