(ns jonquil.plugin-review-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.plugin-review :as review]))

(deftest line-diff-test
  (is (= [[:same "a"] [:del "b"] [:add "x"] [:same "c"]] (review/line-diff "a\nb\nc" "a\nx\nc")))
  (is (= [[:add "a"] [:add "b"]] (review/line-diff nil "a\nb\n")))
  (is (= [[:add "a"]] (review/line-diff "" "a")))
  (is (= [[:del "a"]] (review/line-diff "a" "")))
  (is (= [[:same "a"] [:same "b"]] (review/line-diff "a\r\nb" "a\nb"))))

(deftest a-big-diff-is-whole-files-test
  (with-redefs [review/DIFF-MAX-CELLS 4]
    (is (= [[:del "a"] [:del "b"] [:del "c"] [:add "a"] [:add "x"]] (review/line-diff "a\nb\nc" "a\nx")))))

(deftest diff-text-test
  (let [old (str/join "\n" (map #(str "l" %) (range 20)))
        new (str/replace old "l10" "L10")]
    (is (= (str/join "\n" ["  … 7 unchanged lines" "  l7" "  l8" "  l9" "- l10" "+ L10"
                           "  l11" "  l12" "  l13" "  … 6 unchanged lines"])
           (review/diff-text old new))))
  (is (= "  a\n- b\n+ c" (review/diff-text "a\nb" "a\nc")) "near the start nothing collapses")
  (is (= "No change from live." (review/diff-text "a\nb" "a\nb\n"))))

(def ^:private p
  {:n 4 :reason "wrap faster" :based-on 3
   :check {:plugin {:effects #{:console :eval}} :warnings ["w1" "w2"] :timings {:fmt/b 3 :fmt/a 12}
           :unchecked? false}})

(def ^:private in
  {:id "fmt" :proposal p :source "x\ny" :live-n 3 :live-source "x" :live-effects #{:console}})

(defn- lines [m] (:lines (review/review m)))

(deftest review-test
  (let [r (review/review in)]
    (is (= ["fmt v4, proposed by an agent: wrap faster"
            "Based on v3, the live version."
            "Effects, as its check reported: :console, :eval (new)"
            "Warnings, as its check reported: w1; w2"
            "Soak timings, as its check reported: :fmt/a 12 ms, :fmt/b 3 ms"
            "Pressing Accept runs this code in Jonquil."]
           (:lines r)))
    (is (= "  x\n+ y" (:diff r)))
    (is (= {:id "fmt" :n 4} (select-keys r [:id :n])))))

(deftest review-based-on-test
  (is (= "Based on v3, live is v5: the diff is against live." (second (lines (assoc in :live-n 5)))))
  (is (= "Based on no version, live is v5: the diff is against live."
         (second (lines (-> in (assoc :live-n 5) (assoc-in [:proposal :based-on] nil)))))))

(deftest review-new-plugin-test
  (let [r (review/review (assoc in :live-n nil :live-source nil :live-effects nil))]
    (is (= "A new plugin, so the whole file is shown." (second (:lines r))))
    (is (some #{"Effects, as its check reported: :console (new), :eval (new)"} (:lines r)))
    (is (= "+ x\n+ y" (:diff r)))))

(deftest review-no-effects-test
  (is (some #{"Effects, as its check reported: none"}
            (lines (assoc-in in [:proposal :check :plugin :effects] #{})))))

(deftest review-no-warnings-or-timings-test
  (let [ls (lines (update-in in [:proposal :check] assoc :warnings [] :timings {}))]
    (is (not-any? #(str/starts-with? % "Warnings") ls))
    (is (not-any? #(str/starts-with? % "Soak timings") ls))))

(deftest review-unchecked-test
  (let [ls (lines (assoc-in in [:proposal :check] {:unchecked? true}))]
    (is (= "Unchecked: there was no jolt to check it in, so its checks run when you press Accept." (first ls)))
    (is (some #{"Effects: not known until its checks run at Accept."} ls))
    (is (not-any? #(or (str/starts-with? % "Warnings") (str/starts-with? % "Soak timings")) ls))))

(deftest review-missing-source-test
  (let [r (review/review (assoc in :source nil))]
    (is (= "Its source is missing from its history, so it can't be accepted." (second (:lines r))))
    (is (= "" (:diff r)))))

(deftest summary-text-test
  (let [r (review/review in)]
    (is (= (str/join "\n" (:lines r)) (review/summary-text r 0 1)))
    (is (str/ends-with? (review/summary-text r 0 2) "\nProposal 1 of 2."))))
