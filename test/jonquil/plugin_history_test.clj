(ns jonquil.plugin-history-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

(deftest record-refuses-a-bad-n-test
  ;; a vector of maps whose :n isn't a number is left as it is
  (let [d (temp-dirs/temp-dir! "jonquil-history-")
        f (io/file (hdir d) "history.edn")
        bad "[{:n \"x\" :status :accepted}]"]
    (io/make-parents f)
    (spit f bad)
    (is (:error (history/read-entries (hdir d))))
    (is (re-find #"history\.edn" (str (:error (record d "(a)")))))
    (is (= bad (slurp f)))))

(deftest write-atomic-keeps-a-symlink-and-its-mode-test
  ;; the link survives, its target gets the text, the mode stays
  (let [real-dir (temp-dirs/temp-dir! "jonquil-history-real-")
        d (temp-dirs/temp-dir! "jonquil-history-link-")
        target (io/file real-dir "wrap.clj")
        link (io/file d "wrap.clj")]
    (spit target "(old)")
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath target) (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
    (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath target)
                                            (make-array java.nio.file.attribute.FileAttribute 0))
    (is (nil? (history/write-atomic! (str link) "(new)")))
    (is (java.nio.file.Files/isSymbolicLink (.toPath link)))
    (is (= "(new)" (slurp target)))
    (is (= "rw-------" (java.nio.file.attribute.PosixFilePermissions/toString
                        (java.nio.file.Files/getPosixFilePermissions
                         (.toPath target) (make-array java.nio.file.LinkOption 0)))))))

(def ^:private now27 (constantly "2026-10-27T00:00:00Z"))

(def ^:private chk {:warnings [] :timings {} :plugin {:id :wrap} :unchecked? false})

(defn- propose [d id status & {:as more}]
  (history/record-proposal! (merge {:plugins-dir d :id id :text (str "(" id ")") :reason "r" :status status
                                    :based-on nil :now now27}
                                   (if (= :pending status) {:check chk} {:problems ["p"]})
                                   more)))

(defn- entries-of [d id] (:entries (history/read-entries (history/history-dir d id))))

(deftest proposal-recorded-pending-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")]
    (is (= {:n 1} (propose d "wrap" :pending)))
    (let [es (entries-of d "wrap")]
      (is (= [{:n 1 :at "2026-10-27T00:00:00Z" :source :agent :reason "r" :status :pending :based-on nil :check chk}] es))
      (is (= "(wrap)" (history/version-source (hdir d) 1)))
      (is (nil? (history/latest-accepted es)))
      (is (= (first es) (history/pending es)))
      (is (= :pending (history/version-status es 1))))))

(deftest pending-supersedes-pending-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")]
    (propose d "wrap" :pending)
    (is (= {:n 2 :superseded 1} (propose d "wrap" :pending)))
    (let [es (entries-of d "wrap")]
      (is (= :superseded (history/version-status es 1)))
      (is (= :pending (history/version-status es 2)))
      (is (= 2 (:n (history/pending es)))))))

(deftest rejected-by-checks-supersedes-nothing-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")]
    (propose d "wrap" :pending)
    (is (= {:n 2} (propose d "wrap" :rejected-by-checks)))
    (let [es (entries-of d "wrap")
          v2 (first (filter #(= 2 (:n %)) es))]
      (is (= :pending (history/version-status es 1)))
      (is (= ["p"] (:problems v2)))
      (is (not (contains? v2 :check))))))

(deftest record-status-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")]
    (propose d "wrap" :pending)
    (is (nil? (history/record-status! {:plugins-dir d :id "wrap" :n 1 :status :rejected :note "too broad" :now now27})))
    (is (= {:n 1 :at "2026-10-27T00:00:00Z" :status :rejected :note "too broad"} (last (entries-of d "wrap"))))
    (propose d "wrap" :pending)
    (history/record-status! {:plugins-dir d :id "wrap" :n 2 :status :rejected :now now27})
    (is (not (contains? (last (entries-of d "wrap")) :note)))))

(deftest accepted-status-is-latest-accepted-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")]
    (propose d "wrap" :pending)
    (history/record-status! {:plugins-dir d :id "wrap" :n 1 :status :accepted :now now27})
    (is (= 1 (:n (history/latest-accepted (entries-of d "wrap")))))
    (is (= {:same 1} (record d "(wrap)")))))

(deftest history-prints-plain-maps-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")]
    (propose d "wrap" :pending :check (assoc chk :timings {:x/w 3}))
    (let [text (slurp (io/file (hdir d) "history.edn"))]
      (is (str/includes? text "{:x/w 3}"))
      (is (not (str/includes? text "#:x"))))))

(deftest all-pending-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")]
    (propose d "b" :pending)
    (history/record! {:plugins-dir d :id "a" :text "(a1)" :source :user :reason "edited by hand" :now now27})
    (propose d "a" :pending :based-on 1)
    (history/record! {:plugins-dir d :id "c" :text "(c1)" :source :user :reason "edited by hand" :now now27})
    (let [f (io/file (history/history-dir d "d") "history.edn")] (io/make-parents f) (spit f "nope"))
    (is (= [(assoc (history/pending (entries-of d "a")) :id "a")
            (assoc (history/pending (entries-of d "b")) :id "b")]
           (history/all-pending d)))
    (is (= [2 1] (mapv :n (history/all-pending d))))))

(deftest record-proposal-bad-history-test
  (let [d (temp-dirs/temp-dir! "jonquil-history-")
        f (io/file (hdir d) "history.edn")]
    (io/make-parents f)
    (spit f "{:not :a-vector}")
    (is (:error (propose d "wrap" :pending)))
    (is (= "{:not :a-vector}" (slurp f)))))
