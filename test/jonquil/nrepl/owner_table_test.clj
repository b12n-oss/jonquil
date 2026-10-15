(ns jonquil.nrepl.owner-table-test
  "The per-directory .nrepl-port owner table in jonquil.nrepl.child. No
  child processes are spawned here."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.nrepl.child :as child]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- port-file-text [dir]
  (let [f (io/file dir ".nrepl-port")]
    (when (.exists f) (str/trim (slurp f)))))

(defn- empty-dir [] (temp-dirs/temp-dir! "jonquil-owner-test-"))

(deftest owner-table-restores-saved-port-file-test
  (let [dir (empty-dir)]
    (spit (io/file dir ".nrepl-port") "1234")
    (let [t (child/claim-dir! dir)]
      (child/owner-ready! dir t 7950)
      (is (= "7950" (port-file-text dir)))
      (child/release-dir! dir t)
      (is (= "1234" (port-file-text dir))))))

(deftest owner-table-deletes-its-own-port-file-test
  (let [dir (empty-dir)
        t (child/claim-dir! dir)]
    (child/owner-ready! dir t 7950)
    (child/release-dir! dir t)
    (is (nil? (port-file-text dir)))))

(deftest owner-table-a-b-a-test
  (let [dir (empty-dir)
        t1 (child/claim-dir! dir)]
    (child/owner-ready! dir t1 7950)
    (is (= "7950" (port-file-text dir)))
    (let [t2 (child/claim-dir! dir)]
      (child/owner-ready! dir t2 7951)
      (is (= "7951" (port-file-text dir)))
      ;; Jolt's stop deletes the file when the first child exits.
      (.delete (io/file dir ".nrepl-port"))
      (child/release-dir! dir t1)
      (is (= "7951" (port-file-text dir)))
      (child/release-dir! dir t2)
      (is (nil? (port-file-text dir))))))

(deftest owner-table-release-without-a-port-keeps-the-others-test
  (let [dir (empty-dir)
        t1 (child/claim-dir! dir)
        t2 (child/claim-dir! dir)]
    (child/owner-ready! dir t2 7951)
    (child/release-dir! dir t1)
    (is (= "7951" (port-file-text dir)))
    (child/release-dir! dir t2)))

(deftest owner-table-no-ports-yet-leaves-the-file-test
  (let [dir (empty-dir)
        t1 (child/claim-dir! dir)
        t2 (child/claim-dir! dir)]
    (spit (io/file dir ".nrepl-port") "5555")
    (child/release-dir! dir t1)
    (is (= "5555" (port-file-text dir)))
    (child/release-dir! dir t2)))


(deftest start-failure-releases-the-claim-test
  ;; launch-args throws after start! has claimed the dir and found a port,
  ;; before any process exists.
  (let [dir (empty-dir)]
    (spit (io/file dir ".nrepl-port") "1234")
    (with-redefs [child/launch-args (fn [& _] (throw (ex-info "boom" {})))]
      (is (thrown? clojure.lang.ExceptionInfo (child/start! dir nil))))
    (is (= "1234" (port-file-text dir)) "the seeded file is intact")
    ;; A leaked claim would keep :saved = "1234" and make this the second owner.
    (spit (io/file dir ".nrepl-port") "4321")
    (let [t (child/claim-dir! dir)]
      (child/owner-ready! dir t 7950)
      (child/release-dir! dir t)
      (is (= "4321" (port-file-text dir)) "a fresh claim saw the file as it is now"))))
