(ns jonquil.locations-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.locations :as loc]
            [jonquil.syntax :as syntax]))

(def ^:private src
  (str "(defmulti area :shape)\n"
       "(defmethod area :sq [s] (* (:w s) (:w s)))\n"
       "(defmethod area :ci [c] (* 3 (:r c)))\n"
       "(area {:shape :sq :w 2})"))

(deftest find-definitions-test
  (let [ds (syntax/find-definitions src "area")]
    (is (= ["defmulti" "defmethod" "defmethod"] (map :form ds)))
    (is (= [1 2 3] (map :line ds)))
    (is (every? #(= "area" (subs src (:start %) (:end %))) ds)))
  (testing "find-definition still stops at the first"
    (is (= 1 (:line (syntax/find-definition src "area")))))
  (is (= [] (syntax/find-definitions src "perimeter"))))

(deftest uses-test
  (let [{:keys [title entries index]} (loc/uses src "area")]
    (is (= "Uses of area" title))
    (is (nil? index))
    (is (= [1 2 3 4] (map :line entries)))
    (is (= "(area {:shape :sq :w 2})" (:text (last entries))))
    (is (every? #(= "area" (subs src (:start %) (:end %))) entries))))

(deftest definitions-test
  (let [{:keys [title entries]} (loc/definitions src "area")]
    (is (= "Definitions of area" title))
    (is (= [1 2 3] (map :line entries)))
    (is (= "(defmulti area :shape)" (:text (first entries))))))

(deftest matches-test
  (let [{:keys [title entries]} (loc/matches src "shape")]
    (is (= "Matches for shape" title))
    (is (= [1 4] (map :line entries))))
  (testing "case-insensitive, as the find bar searches"
    (is (= 2 (count (:entries (loc/matches "Shape shape" "shape")))))))

(deftest entries-text-test
  (testing "a long line is trimmed and cut to 120 characters"
    (let [line (str "    " (apply str (repeat 200 "x")) "  ")
          [e] (loc/entries line [[4 5]])]
      (is (= 120 (count (:text e))))
      (is (= \x (first (:text e)))))))

(deftest long-list-test
  (testing "a list stops at LIST-MAX places and its title says so"
    (let [text (apply str (repeat 2500 "x\n"))
          {:keys [title entries]} (loc/matches text "x")]
      (is (= loc/LIST-MAX (count entries)))
      (is (= "Matches for x (the first 1000 of 2500)" title))
      (testing "and every line number is right, counted in one pass"
        (is (= (range 1 (inc loc/LIST-MAX)) (map :line entries))))))
  (testing "a list under the cap keeps its plain title"
    (is (= "Matches for x" (:title (loc/matches "x\nx" "x")))))
  (testing "a big file's one-letter search stays fast (was 18 s)"
    (let [text (apply str (repeat 4000 "(defn e [e] (e e))\n"))
          t0 (System/nanoTime)
          _ (loc/matches text "e")
          ms (/ (- (System/nanoTime) t0) 1e6)]
      (is (< ms 2000) (str ms " ms")))))

(deftest step-test
  (let [l (loc/uses src "area")]
    (is (= 0 (get-in (loc/step l 1) [:list :index])))
    (is (= 3 (get-in (loc/step l -1) [:list :index])))
    (let [at-last (assoc l :index 3)]
      (is (= {:list at-last :moved? false} (loc/step at-last 1))))
    (let [at-first (assoc l :index 0)]
      (is (false? (:moved? (loc/step at-first -1))))
      (is (= 1 (get-in (loc/step at-first 1) [:list :index])))))
  (testing "an empty list never moves"
    (let [e (loc/uses src "perimeter")]
      (is (= [] (:entries e)))
      (is (false? (:moved? (loc/step e 1)))))))

(deftest position-text-test
  (let [l (loc/uses src "area")]
    (is (= "4 places" (loc/position-text l)))
    (is (= "2 of 4" (loc/position-text (assoc l :index 1))))
    (is (= "1 place" (loc/position-text (loc/definitions "(def x 1)" "x"))))
    (is (= "No places" (loc/position-text (loc/uses src "perimeter"))))))
