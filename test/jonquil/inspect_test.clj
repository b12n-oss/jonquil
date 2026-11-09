(ns jonquil.inspect-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest history-requests-test
  (is (= {:op "inspect-earlier"} (inspect/request :earlier {})))
  (is (= {:op "inspect-later"} (inspect/request :later {}))))

(deftest history-keys-and-footer-test
  (let [s (inspect/open (assoc (page-view 0 3 3) :history {:at 2 :count 3}))]
    (is (= [:earlier] (:request (inspect/key-step s :earlier))))
    (is (= [:later] (:request (inspect/key-step s :later))))
    (is (= :first (:pending (:state (inspect/key-step s :earlier)))))
    (is (= "1-3 of 3 · ↑↓ move · ⏎ in · ← out · d def · m meta · v table · object 2 of 3 · [ ] earlier/later"
           (inspect/footer s))))
  (testing "one object: no history in the footer"
    (is (not (str/includes? (inspect/footer (inspect/open (assoc (page-view 0 3 3) :history {:at 1 :count 1})))
                            "object"))))
  (testing "a leaf keeps the history hint too"
    (is (= "← out · d def · m meta · v table · object 1 of 2 · [ ] earlier/later"
           (inspect/footer (inspect/open {:path "*1" :info "String" :header "" :leaf "\"x\""
                                          :history {:at 1 :count 2}}))))))

(deftest history-ends-answered-locally-test
  ;; at the oldest or newest object, [ and ] send nothing and keep the
  ;; selection; the note says why
  (let [oldest (assoc (inspect/open (assoc (page-view 0 3 3) :history {:at 1 :count 3})) :selected 2)
        newest (assoc (inspect/open (assoc (page-view 0 3 3) :history {:at 3 :count 3})) :selected 2)]
    (let [{:keys [state request]} (inspect/key-step oldest :earlier)]
      (is (nil? request))
      (is (= 2 (:selected state)))
      (is (= "No earlier object." (:note state))))
    (let [{:keys [state request]} (inspect/key-step newest :later)]
      (is (nil? request))
      (is (= "No later object." (:note state))))
    (testing "away from the ends it still asks"
      (is (= [:later] (:request (inspect/key-step oldest :later)))))))

(deftest start-requests-carry-label-test
  (is (= "x" (:label (inspect/request :start-result {:id 1 :epoch "e" :label "x" :rows 1 :cols 2}))))
  (is (= "xs" (:label (inspect/request :start-local {:frame 0 :local "xs" :label "xs" :rows 1 :cols 2}))))
  (is (= "(f 1)" (:label (inspect/request :start-trace {:id 1 :part :ret :label "(f 1)" :rows 1 :cols 2})))))

(deftest root-label-test
  (is (= "[1 2 3]" (inspect/root-label "[1 2 3]")))
  (is (= "{:a 1" (inspect/root-label "{:a 1\n :b 2}")) "the first line only")
  (is (= (str (apply str (repeat 40 "x")) "…") (inspect/root-label (apply str (repeat 50 "x")))))
  (is (= "*1" (inspect/root-label "")) "nothing to show: the last value's name"))

(deftest key-step-row-keys-characterization-test
  (let [s (inspect/open (page-view 10 10 25))
        leaf (inspect/open {:path "*1" :info "String" :header "" :leaf "\"x\""})
        empty-coll (inspect/open (page-view 0 0 0))]
    (testing "page-down asks for the next page and selects its first row"
      (is (= {:state (assoc s :pending :first) :request [:page {:delta 1}]}
             (inspect/key-step s :page-down))))
    (testing "up with a selection above moves locally"
      (let [s3 (assoc s :selected 3)]
        (is (= {:state (assoc s3 :selected 2) :request nil} (inspect/key-step s3 :up)))))
    (testing "up at the top of a later page asks for the previous page, landing on its last row"
      (is (= {:state (assoc s :pending :last) :request [:page {:delta -1}]}
             (inspect/key-step s :up))))
    (testing "an unknown key is a no-op"
      (is (= {:state s :request nil} (inspect/key-step s :bogus))))
    (testing "row keys do nothing on a leaf or an empty collection"
      (doseq [k [:down :up :page-down :page-up :in]]
        (is (= {:state leaf :request nil} (inspect/key-step leaf k)))
        (is (= {:state empty-coll :request nil} (inspect/key-step empty-coll k)))))
    (testing "out, meta and table still work on a leaf"
      (is (= [:out] (:request (inspect/key-step leaf :out))))
      (is (= [:meta {:cursor 0}] (:request (inspect/key-step leaf :meta)))))
    (testing "history keys with no :history in the view ask for the earlier/later object"
      (is (= [:later] (:request (inspect/key-step s :later))))
      (is (= [:earlier] (:request (inspect/key-step s :earlier)))))))
