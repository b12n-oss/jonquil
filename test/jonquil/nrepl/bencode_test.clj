(ns jonquil.nrepl.bencode-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.nrepl.bencode :as b]))

(defn- s [^bytes bs] (String. bs "UTF-8"))

(defn- src [text]
  (b/byte-source (java.io.ByteArrayInputStream. (.getBytes ^String text "UTF-8"))))

(deftest encode-test
  (is (= "4:spam" (s (b/encode "spam"))))
  (is (= "0:" (s (b/encode ""))))
  (is (= "i42e" (s (b/encode 42))))
  (is (= "i-3e" (s (b/encode -3))))
  (is (= "l4:spami1ee" (s (b/encode ["spam" 1]))))
  (is (= "le" (s (b/encode []))))
  (testing "keys sorted, keywords as strings, nil values left out"
    (is (= "d2:id1:12:op8:describee" (s (b/encode {:op "describe" :id "1" :session nil})))))
  (is (= "d2:ns16:nrepl.middlewaree" (s (b/encode {:ns 'nrepl.middleware}))))
  (is (= "d8:nrepl/mw1:xe" (s (b/encode {:nrepl/mw "x"}))))
  (testing "the length is in UTF-8 bytes"
    (is (= "3:aλ" (s (b/encode "aλ")))))
  (is (thrown? Exception (b/encode 1.5)))
  (is (thrown? Exception (b/encode {1 "x"}))))

(deftest decode-test
  (is (= "spam" (b/decode (src "4:spam"))))
  (is (= 42 (b/decode (src "i42e"))))
  (is (= -3 (b/decode (src "i-3e"))))
  (is (= ["a" 1 []] (b/decode (src "l1:ai1elee"))))
  (is (= {"op" "eval" "status" ["done"]} (b/decode (src "d2:op4:eval6:statusl4:doneee"))))
  (is (= "aλ" (b/decode (src "3:aλ"))))
  (is (= ::b/eof (b/decode (src ""))))
  (testing "consecutive messages from one source"
    (let [in (src "d2:id1:1ed2:id1:2e")]
      (is (= [{"id" "1"} {"id" "2"} ::b/eof] [(b/decode in) (b/decode in) (b/decode in)]))))
  (testing "input ending inside a value throws ::truncated"
    (doseq [t ["d2:id" "4:sp" "i42" "l1:a"]]
      (is (= ::b/truncated (:type (ex-data (try (b/decode (src t)) (catch Exception e e))))) t)))
  (is (thrown? Exception (b/decode (src "x")))))

(deftest round-trip-test
  (doseq [v [{"op" "completions" "prefix" "ma" "ns" "user"}
             {"completions" [{"candidate" "map" "type" "var"}] "status" ["done"]}
             {"info" {"arglists-str" "([f] [f coll])" "doc" "Returns a lazy sequence…"}}
             ["" 0 {"k" []}]]]
    (is (= v (b/decode (src (s (b/encode v))))))))

(deftest byte-source-buffers-test
  (testing "a value larger than one read still decodes"
    (let [big (apply str (repeat 70000 "x"))]
      (is (= big (b/decode (src (s (b/encode big)))))))))

(deftest too-large-length-test
  (testing "an out-of-range string length throws ex-info with ::too-large, promptly"
    (doseq [input ["999999999999:" "5000000000:"]]
      (let [f (future (b/decode (src input)))
            e (try @f (catch Exception ex ex))]
        (is (= ::b/too-large
               (:type (ex-data (if (instance? java.util.concurrent.ExecutionException e)
                                 (.getCause e)
                                 e))))
            (str "input " input))))))

(deftest malformed-integer-test
  (testing "malformed integers throw ex-info with ::malformed"
    (doseq [input ["i-e" "i--3e" "i1-e" "i-1-2e" "ie" "3-3:abc"]]
      (let [e (try (b/decode (src input)) (catch Exception ex ex))]
        (is (instance? clojure.lang.ExceptionInfo e) (str "input " input " threw " (type e)))
        (is (= ::b/malformed (:type (ex-data e))) (str "input " input))))))
