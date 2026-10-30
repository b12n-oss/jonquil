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
  ;; the reply's :cursor (relative) selects the row entered.
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
  (is (= "11-20 of 25 · ↑↓ move · ⏎ in · ← out · d def · m meta · v table"
         (inspect/footer (inspect/open (page-view 10 10 25)))))
  (is (= "← out · d def · m meta · v table"
         (inspect/footer (inspect/open {:path "*1" :info "String" :header "" :leaf "\"x\""}))))
  (is (= {:path "*1" :info "Long" :header "" :leaf "3"}
         (inspect/parse-view "{:path \"*1\" :info \"Long\" :header \"\" :leaf \"3\"}")))
  (is (= "The server's view couldn't be read." (:note (inspect/parse-view "{:path")))))

(deftest footer-empty-collection-test
  ;; Minor: an empty collection's own start=0/total=0/entries=[] used to
  ;; read "1-0 of 0" ((inc start) with nothing subtracted back out) --
  ;; "0 of 0" instead.
  (is (= "0 of 0 · ↑↓ move · ⏎ in · ← out · d def · m meta · v table"
         (inspect/footer (inspect/open (page-view 0 0 0))))))

(deftest footer-and-open-on-error-test
  (testing "footer never throws on a view with no :start"
    (is (= "" (inspect/footer (inspect/open {:error "x"}))))
    (is (= "" (inspect/footer (inspect/open (inspect/parse-view "")))))
    (is (= "" (inspect/footer (inspect/on-reply (inspect/open (page-view 0 3 3))
                                                (inspect/parse-view "junk{"))))))
  (testing "a first, nil-state :error reply opens onto an empty view with the message in :note"
    (let [s (inspect/open {:error "Nothing to inspect yet: Jonquil isn't attached to the server. Run something first."})]
      (is (= "Nothing to inspect yet: Jonquil isn't attached to the server. Run something first." (:note s)))
      (is (not (:disabled? s)))
      (is (= "" (inspect/footer s)))))
  (testing "a :lost? true error also disables the actions"
    (let [s (inspect/open {:error "Lost the connection to the nREPL server." :lost? true})]
      (is (:disabled? s))
      (is (= "Lost the connection to the nREPL server." (:note s))))))

(deftest disabled-suppresses-requests-test
  ;; Minor: key-step used to ignore :disabled?, so Return/m/v/Left/paging
  ;; kept sending requests a lost connection can't answer.
  (let [s (assoc (inspect/open (page-view 10 10 25)) :selected 3 :disabled? true)]
    (is (nil? (:request (inspect/key-step s :in))))
    (is (nil? (:request (inspect/key-step s :meta))))
    (is (nil? (:request (inspect/key-step s :table))))
    (is (nil? (:request (inspect/key-step s :out))))
    (is (nil? (:request (inspect/key-step s :page-down))))
    (is (nil? (:request (inspect/key-step s :page-up))))
    (is (= s (:state (inspect/key-step s :down))) "no local move either, while disabled")
    (is (= s (:state (inspect/key-step s :up))))))

(deftest reply-edges-test
  (let [s (inspect/open (page-view 0 3 3))
        noted (inspect/on-reply s (assoc (page-view 0 3 3) :note "not tabular" :defd "#'user/x"))]
    (testing "a later reply drops the earlier note and def message"
      (let [next (inspect/on-reply noted (page-view 0 3 3))]
        (is (nil? (:note next)))
        (is (nil? (:defd next)))))
    (testing "an empty collection or a leaf never selects row -1"
      (is (= 0 (:selected (inspect/on-reply (assoc s :pending :last) (page-view 0 0 0)))))
      (is (= 0 (:selected (inspect/on-reply (assoc s :pending :cursor)
                                            {:path "*1" :info "String" :header "" :leaf "\"x\"" :cursor 3})))))
    (testing "in on an empty collection sends nothing"
      (is (nil? (:request (inspect/key-step (inspect/open (page-view 0 0 0)) :in)))))
    (testing "page up selects the first row, as page down does"
      (is (= [:page {:delta -1}] (:request (inspect/key-step (inspect/open (page-view 10 10 25)) :page-up))))
      (is (= :first (:pending (:state (inspect/key-step (inspect/open (page-view 10 10 25)) :page-up))))))
    (testing "a blank or non-map view is unreadable"
      (is (= "The server's view couldn't be read." (:note (inspect/parse-view ""))))
      (is (= "The server's view couldn't be read." (:note (inspect/parse-view nil))))
      (is (= "The server's view couldn't be read." (:note (inspect/parse-view "42")))))))
