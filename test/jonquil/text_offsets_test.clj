(ns jonquil.text-offsets-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.text-offsets :as offsets]))

;; "a😀b": characters a 0, 😀 1, b 2; string indexes a 0, 😀 1-2, b 3.

(deftest astral-test
  (is (offsets/astral? "a😀b"))
  (is (not (offsets/astral? "abc")))
  (is (not (offsets/astral? "안녕")) "BMP characters take one index each")
  (is (not (offsets/astral? ""))))

(deftest char-count-test
  (is (= 3 (offsets/char-count "a😀b")))
  (is (= 4 (count "a😀b"))))

(deftest round-trip-test
  (let [s "a😀b"]
    (is (= [0 1 3 4] (mapv #(offsets/to-index s %) [0 1 2 3])))
    (is (= [0 1 2 3] (mapv #(offsets/to-char s %) [0 1 3 4])))
    (testing "outside the text, the offset moves by the same distance"
      (is (= -1 (offsets/to-index s -1)))
      (is (= 6 (offsets/to-index s 5)))
      (is (= -1 (offsets/to-char s -1)))
      (is (= 5 (offsets/to-char s 6))))))

(deftest snapshot-and-keys-test
  (is (= {:text "😀ab" :caret 3 :sel-start 2 :sel-end 4}
         (offsets/snapshot->indexes {:text "😀ab" :caret 2 :sel-start 1 :sel-end 3})))
  (is (= {:text "😀" :caret nil} (offsets/snapshot->indexes {:text "😀" :caret nil}))
      "a nil offset stays nil")
  (is (= {:start 1 :end 2 :other 9}
         (offsets/keys->chars "😀ab" {:start 2 :end 3 :other 9} [:start :end :missing]))))
