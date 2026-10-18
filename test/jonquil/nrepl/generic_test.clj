(ns jonquil.nrepl.generic-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.dialect :as dialect]
            [jonquil.nrepl.generic :as generic]))

(defn- sel [nrepl dir]
  (cond-> {:profile dialect/nrepl :runtime :attach :project-dir dir}
    nrepl (assoc :nrepl nrepl)))

(deftest endpoint-test
  (is (= {:host "127.0.0.1" :port nil} (generic/endpoint (sel nil "/p"))) "local, port from the port file")
  (is (= {:host "127.0.0.1" :port 1667} (generic/endpoint (sel {:port 1667} "/p"))))
  (is (= {:host "box" :port 1667} (generic/endpoint (sel {:host "box" :port 1667} "/p"))))
  (is (= {:host "box" :port nil} (generic/endpoint (sel {:host "box"} "/p"))) "a remote host's port from the local port file"))

(deftest port-file-test
  (is (= (io/file "/p" ".nrepl-port") (generic/port-file (sel nil "/p"))))
  (is (= (io/file "/p" "tmp/port") (generic/port-file (sel {:port-file "tmp/port"} "/p"))))
  (is (nil? (generic/port-file (sel nil nil))) "no project, no port file"))

(deftest messages-name-the-host-test
  (let [m (:messages (generic/connect-policy (sel {:host "box" :port 1667} "/p")))]
    (is (= "; Connecting to the nREPL server at box:1667..." ((:connecting m) 1667)))
    (is (str/includes? ((:refused m) 1667) "box:1667"))
    (is (str/includes? ((:not-server m) 1667) "box:1667")))
  (testing "the not-running sentence says how to point Jonquil at a server"
    (let [m (:messages (generic/connect-policy (sel nil "/p")))]
      (is (str/includes? ((:not-running m) "/p") "{:dialect :nrepl :nrepl {:port"))
      (is (str/includes? ((:not-running m) "/p") "bb nrepl-server")))))

(deftest limitations-test
  (testing "before connecting: only what's always true"
    (is (= generic/always-limits (generic/limitations #{} false))))
  (testing "babashka's ops (measured): no interrupt, no stdin, completion and lookup through cider's ops"
    (let [ls (generic/limitations #{"clone" "close" "complete" "describe" "eldoc" "eval" "info" "load-file" "lookup"} true)]
      (is (some #(str/includes? % "Stop disconnects") ls))
      (is (some #(str/includes? % "read") ls))
      (is (not-any? #(str/includes? % "completion op") ls))
      (is (not-any? #(str/includes? % "No quick docs") ls))
      (is (not-any? #(str/includes? % "JVM") ls) "nothing JVM-specific")))
  (testing "a server with everything says only what's always true"
    (is (= generic/always-limits
           (generic/limitations #{"eval" "load-file" "interrupt" "stdin" "completions" "lookup"} true))))
  (testing "a bare server"
    (let [ls (generic/limitations #{"eval" "load-file" "clone"} true)]
      (is (some #(str/includes? % "completion op") ls))
      (is (some #(str/includes? % "No quick docs") ls)))))

(deftest only-candidates-with-the-prefix-test
  ;; babashka's complete is fuzzy: "ma" also brings Throwable->map and format.
  (is (= ["map" "mapv"]
         (map :symbol (generic/with-prefix "ma" [{:symbol "map"} {:symbol "Throwable->map"} {:symbol "mapv"}
                                                 {:symbol "format"}])))
      "names that start with the prefix")
  (is (= 2 (count (generic/with-prefix "" [{:symbol "a"} {:symbol "b"}]))) "an empty prefix keeps everything"))

(deftest local-file-test
  (let [f (java.io.File/createTempFile "jonquil-generic" ".clj")]
    (try
      (is (generic/local-file? (.getPath f)))
      (is (generic/local-file? (str "file:" (.getPath f))))
      (is (not (generic/local-file? "file:/no/such/dir/clojure/core.clj")) "babashka's made-up path")
      (is (not (generic/local-file? "")))
      (is (not (generic/local-file? nil)))
      (is (not (generic/local-file? "jar:file:/no/such.jar!/clojure/core.clj")) "a jar that isn't here")
      (finally (.delete f)))))

(deftest label-test
  (is (= "nREPL (not running)" (dialect/label {:profile dialect/nrepl :runtime :attach})))
  (is (= "nREPL (attached)" (dialect/label {:profile dialect/nrepl :runtime :attach} :attached))))

(deftest a-server-with-info-but-no-lookup-has-quick-docs-test
  ;; Basilisp 0.5.1 (measured 2026-10-18): info, no lookup.
  (is (not-any? #(str/includes? % "No quick docs") (generic/limitations #{"eval" "load-file" "info"} true))
      "quick doc falls back to info"))

(deftest local-file-decodes-like-the-source-panel-test
  (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") (str "jonquil generic " (System/nanoTime)))
        f (java.io.File. dir "a.clj")]
    (.mkdirs dir)
    (spit f "(ns a)")
    (try
      (is (generic/local-file? (str (.toURI f))) "a percent-encoded file: URL of a real file")
      (finally (.delete f) (.delete dir)))))

(deftest hosts-test
  (let [w (fn [h] (:warnings (dialect/parse-config (str "{:nrepl {:host " (pr-str h) " :port 1}}") nil)))]
    (is (= [] (w "box")))
    (is (= [] (w "::1")) "an IPv6 literal")
    (is (= 1 (count (w "box:1667"))) "a port in the host"))
  (let [m (:messages (generic/connect-policy {:profile dialect/nrepl :project-dir "/p" :nrepl {:host "::1" :port 1}}))]
    (is (= "; Connecting to the nREPL server at [::1]:1..." ((:connecting m) 1)))))
