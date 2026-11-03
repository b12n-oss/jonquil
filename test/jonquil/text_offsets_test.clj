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

(deftest astral-starts-test
  (is (= [1] (offsets/astral-starts "a😀b")))
  (is (= [0 2 5] (offsets/astral-starts "😀😀b😀")))
  (is (= [] (offsets/astral-starts "abc"))))

(deftest round-trip-test
  (let [st (offsets/astral-starts "a😀b")]
    (is (= [0 1 3 4] (mapv #(offsets/to-index st %) [0 1 2 3])))
    (is (= [0 1 2 3] (mapv #(offsets/to-char st %) [0 1 3 4])))
    (is (= 1 (offsets/to-char st 2)) "between an emoji's halves: the emoji's offset")
    (testing "outside the text, the offset moves by the same distance"
      (is (= -1 (offsets/to-index st -1)))
      (is (= 6 (offsets/to-index st 5)))
      (is (= -1 (offsets/to-char st -1)))
      (is (= 5 (offsets/to-char st 6)))))
  (testing "several emoji, against the JVM's own code point methods"
    (let [s "😀😀b😀cd" st (offsets/astral-starts s)]
      (doseq [n (range (inc (offsets/char-count s)))]
        (is (= (.offsetByCodePoints s 0 n) (offsets/to-index st n)) (str "char " n))
        (is (= n (offsets/to-char st (offsets/to-index st n))) (str "round trip " n))))))

(deftest snapshot-and-keys-test
  (is (= {:text "😀ab" :caret 3 :sel-start 2 :sel-end 4}
         (offsets/snapshot->indexes {:text "😀ab" :caret 2 :sel-start 1 :sel-end 3})))
  (is (= {:text "😀" :caret nil} (offsets/snapshot->indexes {:text "😀" :caret nil}))
      "a nil offset stays nil")
  (is (= {:start 1 :end 2 :other 9}
         (offsets/keys->chars "😀ab" {:start 2 :end 3 :other 9} [:start :end :missing]))))
