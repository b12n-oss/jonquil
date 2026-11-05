(ns jonquil.compare-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.compare :as c]))

(def other "a\nb\nc\nd\ne\n")
(def buffer "a\nB\nc\nd\nx\ny\ne\n")

(deftest hunks-test
  (is (= [{:line 1 :here ["B"] :there ["b"]}
          {:line 4 :here ["x" "y"] :there []}]
         (c/hunks other buffer))))

(deftest take-edit-test
  (let [[h1 h2] (c/hunks other buffer)]
    (is (= [2 4 "b\n"] (c/take-edit buffer h1)))
    (is (= "a\nb\nc\nd\nx\ny\ne\n" (let [[s e r] (c/take-edit buffer h1)] (str (subs buffer 0 s) r (subs buffer e)))))
    (is (= [8 12 ""] (c/take-edit buffer h2)) "lines only here are removed")
    (is (= [2 2 "b\n"] (c/take-edit "a\nc\n" (first (c/hunks "a\nb\nc\n" "a\nc\n")))) "lines only there go in")
    (is (= "a\nb" (let [b "a\nB" [s e r] (c/take-edit b (first (c/hunks "a\nb" b)))] (str (subs b 0 s) r (subs b e))))
        "no newline at the end stays that way")))

(deftest location-list-test
  (let [l (c/location-list "disk" other buffer)]
    (is (= "Differences from disk" (:title l)))
    (is (= [{:start 2 :end 4 :line 2 :text "1 line here, 1 line on disk: b"}
            {:start 8 :end 12 :line 5 :text "2 lines here, none on disk: x"}]
           (mapv #(select-keys % [:start :end :line :text]) (:entries l))))))

(deftest at-line-test
  (is (= {:line 4 :here ["x" "y"] :there []} (c/hunk-at (c/hunks other buffer) 5 buffer)) "inside the second")
  (is (nil? (c/hunk-at (c/hunks other buffer) 2 buffer))))

(defn- taken [other buffer]
  (let [h (first (c/hunks other buffer))
        [s e r] (c/take-edit buffer h)]
    (str (subs buffer 0 s) r (subs buffer e))))

(deftest review-edges-test
  (is (= "a\nb\nc" (taken "a\nb\nc\n" "a\nb")) "an insertion at the end of a buffer with no final newline")
  (is (= "a\nb\n" (taken "a\nb\n" "")) "an empty buffer keeps the newline")
  (is (= "a\r\nb\r\nc\r\n" (taken "a\r\nb\r\nc\r\n" "a\r\nX\r\nc\r\n")) "a CRLF buffer stays CRLF")
  (let [big (apply str (map #(str "line " % "\n") (range 1500)))]
    (is (= [] (c/hunks big big)) "identical, past the LCS cap")
    (is (= [{:line 700 :here ["changed"] :there ["line 700"]}]
           (c/hunks big (str/replace big "line 700\n" "changed\n")))
        "one local change in a big file is one small hunk")))

(deftest insertion-entry-spans-a-line-test
  (let [l (c/location-list "disk" "a\nb\nc\n" "a\nc\n")
        {:keys [start end line]} (first (:entries l))]
    (is (= 2 line))
    (is (< start end) "a place the list can step to")
    (is (= {:line 1 :here [] :there ["b"]} (c/hunk-at (c/hunks "a\nb\nc\n" "a\nc\n") 1 "a\nc\n")))))

(deftest insertion-past-the-end-test
  (is (= {:line 2 :here [] :there ["c"]} (c/hunk-at (c/hunks "a\nb\nc\n" "a\nb") 1 "a\nb"))
      "the caret on the last line reaches a difference just past it"))
