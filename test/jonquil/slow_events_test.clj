(ns jonquil.slow-events-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.slow-events :as se]))

(defn- event [at ms site] {:at at :ms ms :site site})

(deftest add-keeps-the-newest-test
  (let [log (reduce se/add se/empty-log (for [i (range (+ se/capacity 5))] (event i 60 "a:1")))]
    (is (= se/capacity (count (:events log))))
    (is (= (+ se/capacity 5) (:total log)) "the dropped ones still count")
    (is (= 5 (:at (first (:events log)))) "the oldest five are gone")
    (is (= (+ se/capacity 4) (:at (peek (:events log)))))))

(deftest check-keeps-only-a-slow-callback-test
  (try
    (se/clear!)
    (testing "a callback that just returned isn't kept"
      (se/check! "fast:1" (System/nanoTime))
      (is (= se/empty-log @se/log)))
    (testing "one that began threshold-ms ago is, with its site and how long it took"
      (se/check! "slow:2" (- (System/nanoTime) (* (+ se/threshold-ms 30) 1000000)))
      (let [[e] (:events @se/log)]
        (is (= 1 (:total @se/log)))
        (is (= "slow:2" (:site e)))
        (is (<= (+ se/threshold-ms 30) (:ms e) (+ se/threshold-ms 1030)))
        (is (pos? (:at e)))))
    (finally (se/clear!))))

(deftest by-site-test
  (is (= [{:site "b:2" :count 1 :total-ms 400 :worst-ms 400}
          {:site "a:1" :count 2 :total-ms 150 :worst-ms 90}]
         (se/by-site [(event 1 60 "a:1") (event 2 400 "b:2") (event 3 90 "a:1")]))
      "the site that cost the most comes first")
  (is (= [] (se/by-site []))))

(deftest report-lines-test
  (testing "nothing slow"
    (is (= ["Jonquil x" (str "No callback has taken " se/threshold-ms " ms or longer.")]
           (se/report-lines se/empty-log ["Jonquil x"] 20))))
  (testing "the facts, the sites by cost, then the newest events"
    (let [log (reduce se/add se/empty-log [(event 1000 60 "a:1") (event 2000 400 "b:2") (event 3000 90 "a:1")])
          lines (se/report-lines log ["Jonquil x"] 2)
          text (str/join "\n" lines)]
      (is (= "Jonquil x" (first lines)))
      (is (str/includes? text "3 slow events"))
      (is (str/includes? text "  400 ms in 1 event, worst 400 ms: b:2"))
      (is (str/includes? text "  150 ms in 2 events, worst 90 ms: a:1"))
      (is (< (str/index-of text "b:2") (str/index-of text "150 ms in 2 events")))
      (is (str/includes? text "The newest 2:"))
      (is (= 2 (count (filter #(re-find #"^  \d\d:\d\d:\d\d\.\d\d\d  \d+ ms  " %) lines))))
      (is (not (str/includes? text "kept")) "nothing was dropped")))
  (testing "a full log says how many it kept"
    (let [log (reduce se/add se/empty-log (for [i (range (+ se/capacity 1))] (event i 60 "a:1")))]
      (is (some #(str/includes? % (str "the newest " se/capacity " kept")) (se/report-lines log [] 5)))))
  (testing "the file ends in a newline"
    (is (str/ends-with? (se/file-text se/empty-log ["x"]) "longer.\n"))))
