(ns jonquil.nrepl.jvm-frames-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.nrepl.jvm-frames :as jf]))

(defn- forms [s]
  (let [r (java.io.PushbackReader. (java.io.StringReader. s))]
    (loop [acc []]
      (let [f (read {:eof ::eof} r)]
        (if (= ::eof f) acc (recur (conj acc f)))))))

(deftest code-test
  (let [c (jf/code "TOK123")]
    (testing "one form, for one eval"
      (is (= 1 (count (forms c)))))
    (testing "it reads the session's *e and keeps *1 *2 *3"
      (doseq [part ["clojure.core/*e" ".getStackTrace" "clojure.lang.Compiler/demunge"
                    "set! clojure.core/*1" "set! clojure.core/*2" "TOK123"]]
        (is (str/includes? c part) part)))))

(deftest parse-test
  (is (= {:note "No error yet."} (jf/parse "noise\nTOK{:note \"No error yet.\"}" "TOK")))
  (is (= {:error "The server didn't send the frames."} (jf/parse "" "TOK")))
  (is (= {:error "The server didn't send the frames."} (jf/parse nil "TOK")))
  (is (= {:title "b"} (jf/parse "TOK{:title \"a\"}\nTOK{:title \"b\"}" "TOK")) "the last token's data")
  (is (= {:error "The server didn't send the frames."} (jf/parse "TOK{:title" "TOK")) "unreadable data"))
