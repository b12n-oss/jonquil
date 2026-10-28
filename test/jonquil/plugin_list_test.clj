(ns jonquil.plugin-list-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.plugin-history :as history]
            [jonquil.plugin-list :as plugin-list]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def ^:private now (constantly "T"))

(defn- accepted! [d id text]
  (history/record! {:plugins-dir d :id id :text text :source :user :reason "edited by hand" :now now}))

(defn- propose! [d id text & {:as more}]
  (history/record-proposal! (merge {:plugins-dir d :id id :text text :reason "r" :status :pending
                                    :check {:unchecked? true} :now now}
                                   more)))

(defn- dir [] (temp-dirs/temp-dir! "jonquil-plist-"))

(defn- state [d & {:as more}]
  (merge {:plugins-dir d :registry {:plugins {}} :disabled #{}} more))

(defn- three-plugins
  "a: accepted v1 and a pending v2, with a file; b and c: files only."
  []
  (let [d (dir)]
    (accepted! d "a" "(a1)")
    (propose! d "a" "(a2)" :based-on 1)
    (spit (io/file d "a.clj") "(a1)")
    (spit (io/file d "b.clj") "(b)")
    (spit (io/file d "c.clj") "(c)")
    d))

(def ^:private reg-a {:plugins {:a {:source "(a1)" :definition {:doc "Wraps." :effects #{:eval :console}}}}})

(deftest rows-test
  (let [rs (plugin-list/rows (state (three-plugins) :registry reg-a :disabled #{:c}))]
    (is (= ["a" "b" "c"] (map :id rs)))
    (is (= "a: live v1, loaded, effects :console :eval, pending v2. Wraps." (:line (first rs))))
    (is (= "c: no live version, disabled, effects unknown, no pending proposal." (:line (last rs))))
    (is (= [false false true] (map :disabled? rs)))
    (is (= [2 nil nil] (map :pending-n rs)))
    (is (every? #(str/ends-with? (:file %) (str "/" (:id %) ".clj")) rs))))

(deftest a-history-only-plugin-has-no-file-test
  (let [d (dir)]
    (propose! d "h" "(h1)")
    (is (= [{:id "h" :file nil :disabled? false :pending-n 1}]
           (map #(select-keys % [:id :file :disabled? :pending-n]) (plugin-list/rows (state d)))))))

(deftest an-unreadable-history-has-no-pending-n-test
  (let [d (dir)]
    (propose! d "a" "(a1)")
    (spit (io/file (history/history-dir d "a") "history.edn") "{:not")
    (is (= [nil] (map :pending-n (plugin-list/rows (state d)))))))

(deftest no-plugins-is-no-rows-test
  (is (= [] (plugin-list/rows (state (dir))))))

(deftest version-rows-test
  (let [d (dir)]
    (accepted! d "a" "(a1)")
    (propose! d "a" "(a2)" :based-on 1)
    (let [{:keys [live-n rows]} (plugin-list/version-rows d "a")]
      (is (= 1 live-n))
      (is (= [2 1] (map :n rows)))
      (is (= "v1 accepted (user, T): edited by hand" (:line (last rows))))
      (is (str/starts-with? (:line (first rows)) "v2 pending (agent, T): r")))))

(deftest version-rows-of-no-history-test
  (is (= {:live-n nil :rows []} (plugin-list/version-rows (dir) "a"))))

(deftest version-rows-of-an-unreadable-history-test
  (let [d (dir)]
    (propose! d "a" "(a1)")
    (spit (io/file (history/history-dir d "a") "history.edn") "{:not")
    (is (str/starts-with? (:error (plugin-list/version-rows d "a")) "a's history can't be read: "))))

(deftest a-deleted-plugin-says-file-deleted-test
  (let [d (dir)]
    (accepted! d "h" "(h1)")
    (is (= "h: live v1, not loaded, file deleted, effects unknown, no pending proposal."
           (:line (first (plugin-list/rows (state d))))))))

(deftest a-new-proposal-isnt-file-deleted-test
  (let [d (dir)]
    (propose! d "h" "(h1)")
    (is (not (str/includes? (:line (first (plugin-list/rows (state d)))) "file deleted")))))
