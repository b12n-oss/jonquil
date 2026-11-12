(ns jonquil.compare-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

;; --- one form (LM3 single forms) -------------------------------------------

(def ^:private buf "(ns a)\n\n(defn f [x]\n  (inc x))\n\n(def y 1)\n\n(defn g [] 2)\n")
(def ^:private head-text "(ns a)\n\n(def y 0)\n\n(defn f [x]\n  (dec x))\n\n(defn h [] 3)\n")

(deftest form-key-test
  (is (= {:sig ["defn" "f"] :nth 0 :name "defn f"}
         (select-keys (c/form-key buf 12) [:sig :nth :name])))
  (is (= ["def" "y"] (:sig (c/form-key buf (str/index-of buf "1)")))) "anywhere inside the form")
  (is (nil? (c/form-key buf 7)) "between forms: none")
  (testing "the second of two same-headed forms is told apart by its place"
    (let [t "(comment 1)\n(comment 2)\n"]
      (is (= {:sig ["comment"] :nth 0} (select-keys (c/form-key t 3) [:sig :nth])))
      (is (= {:sig ["comment"] :nth 1} (select-keys (c/form-key t 14) [:sig :nth])))
      (is (= 1 (:nth (c/form-key "(foo [a])\n(foo [a])\n" 12)))))))

(deftest form-key-edges-test
  (testing "defmethod forms are told apart by their dispatch value"
    (let [t "(defmethod area :circle [s] 1)\n(defmethod area :square [s] 2)\n"]
      (is (= ["defmethod" "area" ":square"] (:sig (c/form-key t 40))))
      (let [disk "(defmethod area :square [s] 3)\n"
            {:keys [text]} (c/splice-form t disk (c/form-key t 40) "disk")]
        (is (= "(defmethod area :circle [s] 1)\n(defmethod area :square [s] 3)\n" text)
            "the buffer's :square is matched with disk's :square, not by place"))))
  (testing "a comment block is keyed by its place, not its first form's text"
    (let [k (c/form-key "(comment\n  (f 1))\n" 3)]
      (is (= ["comment"] (:sig k)))
      (is (= {:text "(comment\n  (f 2))\n"}
             (c/splice-form "(comment\n  (f 1))\n" "(comment\n  (f 2))\n" k "HEAD"))
          "editing its first form doesn't lose it")))
  (testing "an empty list is keyed by its text"
    (is (= ["()"] (:sig (c/form-key "()\n(def a 1)\n" 0)))))
  (testing "the caret right after a form's closing bracket is in it, as Run Form takes it"
    (is (= ["def" "a"] (:sig (c/form-key "(def a 1)" 9)))))
  (testing "a second form with the same signature says which one"
    (is (= "comment (2nd)" (:name (c/form-key "(comment 1)\n(comment 2)\n" 14))))
    (is (= {:error "HEAD has no (comment ...), the 2nd."}
           (c/splice-form "(comment 1)\n(comment 2)\n" "(comment 1)\n" (c/form-key "(comment 1)\n(comment 2)\n" 14) "HEAD")))))

(deftest splice-form-test
  (testing "the buffer with only its form swapped for the other's"
    (let [{:keys [text]} (c/splice-form buf head-text (c/form-key buf 12) "HEAD")]
      (is (= "(ns a)\n\n(defn f [x]\n  (dec x))\n\n(def y 1)\n\n(defn g [] 2)\n" text))
      (is (= [{:line 3 :here ["  (inc x))"] :there ["  (dec x))"]}]
             (map #(select-keys % [:line :here :there]) (c/hunks text buf)))
          "so only that form's lines differ")))
  (testing "a form the other version doesn't have"
    (is (= {:error "HEAD has no (defn g ...)."}
           (c/splice-form buf head-text (c/form-key buf (str/index-of buf "(defn g")) "HEAD"))))
  (testing "a form gone from the buffer since the compare"
    (is (= {:error "The last Run has no (defn f ...)."}
           (c/splice-form buf "(ns a)\n" (c/form-key buf 12) "the last Run")))
    (is (= {:error "This tab has no (defn f ...) any more."}
           (c/splice-form "(ns a)\n" head-text {:sig ["defn" "f"] :nth 0 :name "defn f"} "HEAD")))))
