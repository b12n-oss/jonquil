(ns jonquil.inspect-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.inspect :as inspect]))

(defn- page-view [start n total]
  {:path "*1" :info "x" :header "" :start start :total total
   :entries (mapv #(str % " · " %) (range start (+ start n)))})

(deftest page-edge-test
  (let [s (inspect/open (page-view 0 10 25))
        s9 (reduce (fn [st _] (:state (inspect/key-step st :down))) s (range 9))]
    (is (= 9 (:selected s9)))
    (is (= [:page {:delta 1}] (:request (inspect/key-step s9 :down))))
    (let [p2 (inspect/on-reply (:state (inspect/key-step s9 :down)) (page-view 10 10 25))]
      (is (= 0 (:selected p2)))
      (is (= [:page {:delta -1}] (:request (inspect/key-step p2 :up))))
      (is (= 9 (:selected (inspect/on-reply (:state (inspect/key-step p2 :up)) (page-view 0 10 25)))))))
  (testing "no request past either end"
    (is (nil? (:request (inspect/key-step (inspect/open (page-view 0 10 25)) :up))))
    (let [last-page (assoc (inspect/open (page-view 20 5 25)) :selected 4)]
      (is (nil? (:request (inspect/key-step last-page :down))))
      (is (= 4 (:selected (:state (inspect/key-step last-page :down))))))))

(deftest in-and-meta-requests-test
  (let [s (assoc (inspect/open (page-view 10 10 25)) :selected 3)]
    (is (= [:in {:idx 13}] (:request (inspect/key-step s :in))))
    (is (= [:meta {:cursor 3}] (:request (inspect/key-step s :meta))))
    (is (= [:table] (:request (inspect/key-step s :table))))
    (is (nil? (:request (inspect/key-step (inspect/open {:path "*1" :info "String" :header "" :leaf "\"x\""}) :in))))))

(deftest out-reselects-test
  ;; Review focus 3: the reply's :cursor (relative) selects the row entered.
  (let [s (:state (inspect/key-step (inspect/open (page-view 0 3 3)) :out))]
    (is (= 3 (:selected (inspect/on-reply s (assoc (page-view 10 10 25) :cursor 3)))))
    (is (= 9 (:selected (inspect/on-reply s (assoc (page-view 10 10 25) :cursor 42)))) "clamped")))

(deftest reply-notes-and-errors-test
  (let [s (inspect/open (page-view 0 3 3))]
    (is (= "not tabular" (:note (inspect/on-reply s (assoc (page-view 0 3 3) :note "not tabular")))))
    (is (= "#'user/x" (:defd (inspect/on-reply s (assoc (page-view 0 3 3) :defd "#'user/x")))))
    (let [e (inspect/on-reply s {:error "Lost the connection to the nREPL server." :lost? true})]
      (is (= (:view s) (:view e)))
      (is (:disabled? e))
      (is (= "Lost the connection to the nREPL server." (:note e))))))

(deftest footer-and-parse-test
  (is (= "11-20 of 25" (inspect/footer (inspect/open (page-view 10 10 25)))))
  (is (= "" (inspect/footer (inspect/open {:path "*1" :info "String" :header "" :leaf "\"x\""}))))
  (is (= {:path "*1" :info "Long" :header "" :leaf "3"}
         (inspect/parse-view "{:path \"*1\" :info \"Long\" :header \"\" :leaf \"3\"}")))
  (is (= "The server's view couldn't be read." (:note (inspect/parse-view "{:path")))))
