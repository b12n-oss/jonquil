(ns jonquil.mcp.json-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.mcp.json :as json]))

(deftest decode-surrogate-pairs
  (is (= {"a" "x😀y"} (json/decode "{\"a\":\"x\\ud83d\\ude00y\"}")))
  (is (= {"a" "x😀y"} (json/decode "{\"a\":\"x\\uD83D\\uDE00y\"}"))))

(deftest decode-escaped-backslash-before-u
  (testing "escaped backslash, literal ud83d, then a lone low surrogate"
    (is (= {"b" "\\ud83d\ufffd"}
           (json/decode "{\"b\":\"\\\\ud83d\\ude00\"}"))))
  (testing "escaped backslash, then a real pair"
    (is (= {"c" "\\😀"}
           (json/decode "{\"c\":\"\\\\\\ud83d\\ude00\"}")))))

(deftest decode-lone-high-surrogate
  (is (= {"d" "\ufffdz"} (json/decode "{\"d\":\"\\ud83dz\"}"))))

(deftest round-trip
  (let [x {"a" [1 2.5 nil true] "s" "line1\n\"q\" é 😀"}]
    (is (= x (json/decode (json/encode x))))))

(deftest encode-writes-non-ascii-raw
  (is (= "{\"s\":\"é😀/\"}" (json/encode {"s" "é😀/"}))))

(deftest encode-one-line
  (is (not (str/includes? (json/encode {"s" "a\nb"}) "\n"))))

(deftest decode-invalid-throws
  (is (thrown? Exception (json/decode "{not json"))))
