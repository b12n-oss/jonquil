(ns jonquil.plugin-history-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.plugin-history :as history]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def ^:private now (constantly "2026-10-26T00:00:00Z"))

(defn- record [d text]
  (history/record! {:plugins-dir d :id "wrap" :text text :source :user
                    :reason "edited by hand" :now now}))

(defn- hdir [d] (history/history-dir d "wrap"))

(deftest history-dir-test
  (is (= "/p/.history/wrap" (history/history-dir "/p" "wrap"))))

(deftest next-n-test
  (is (= 1 (history/next-n [] [])))
  (is (= 3 (history/next-n [{:n 1} {:n 2}] [])))
  (is (= 6 (history/next-n [{:n 1}] [1 2 5]))))

(deftest latest-accepted-test
  (is (= {:n 2 :status :accepted}
         (history/latest-accepted [{:n 1 :status :accepted} {:n 2 :status :accepted}])))
  (is (nil? (history/latest-accepted []))))

(deftest entry-test
  (is (= {:n 2 :at "t" :source :revert :reason "r" :status :accepted}
         (history/entry {:n 2 :at "t" :source :revert :reason "r"}))))

(deftest record-first-version-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")]
    (is (= {:n 1} (record d "(a)")))
    (is (= {:entries [{:n 1 :at "2026-10-26T00:00:00Z" :source :user
                       :reason "edited by hand" :status :accepted}]}
           (history/read-entries (hdir d))))
    (is (= "(a)" (history/version-source (hdir d) 1)))))

(deftest record-same-text-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")]
    (is (= {:n 1} (record d "(a)")))
    (is (= {:same 1} (record d "(a)")))
    (is (= 1 (count (:entries (history/read-entries (hdir d))))))))

(deftest record-changed-text-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")]
    (is (= {:n 1} (record d "(a)")))
    (is (= {:n 2} (record d "(b)")))
    (is (= {:n 3} (record d "(a)")))))

(deftest record-refuses-unreadable-history-test
  (doseq [bad ["{:not" "{:a 1}"]]
    (let [d (temp-dirs/temp-dir! "jonquil-history-")
          f (io/file (hdir d) "history.edn")]
      (io/make-parents f)
      (spit f bad)
      (let [r (record d "(a)")]
        (is (re-find #"history\.edn" (str (:error r))) (pr-str r)))
      (is (= bad (slurp f)))
      (is (not (.exists (io/file (hdir d) "1.clj")))))))

(deftest record-after-history-deleted-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")]
    (record d "(a)")
    (record d "(b)")
    (io/delete-file (io/file (hdir d) "history.edn"))
    (is (= {:n 3} (record d "(c)")))
    (is (= "(b)" (slurp (io/file (hdir d) "2.clj"))))))

(deftest read-entries-missing-test
  (is (= {:entries []} (history/read-entries (temp-dirs/temp-dir! "jonquil-history-")))))

(deftest version-source-missing-test
  (is (nil? (history/version-source (temp-dirs/temp-dir! "jonquil-history-") 4))))
