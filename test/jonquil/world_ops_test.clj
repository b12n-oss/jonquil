(ns jonquil.world-ops-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.evaluator :as ev]
            [jonquil.world :as world]
            [jonquil.world-ops :as ops]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- fake-ev
  "A World evaluator: :save writes the file and answers save-answer,
  :restore answers restore-answer. ops records each [op path]."
  [ops {:keys [save-answer restore-answer]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ev/World
    (world-op! [_ op {:keys [path]} on-result]
      (swap! ops conj [op path])
      (future
        (on-result (case op
                     :save (do (when-not (:error save-answer) (spit path "w")) save-answer)
                     :restore restore-answer)))
      nil)))

(defn- call-sync [f & args]
  (let [p (promise)]
    (apply f (concat args [(fn [r] (deliver p r))]))
    (deref p 5000 :timeout)))

(deftest save-records-and-lists-test
  (let [dir (str (temp-dirs/temp-dir! "jonquil-world-ops-"))
        e (fake-ev (atom []) {:save-answer {:saved 2 :placeholders []}})
        r (call-sync ops/save! {:ev e :dir dir :kind :agent :protect #{}})]
    (is (= 2 (:saved r)))
    (is (= (world/entry-id r) (world/entry-id (first (world/entries dir)))))
    (is (= :agent (:kind (first (world/entries dir)))))))

(deftest failed-save-leaves-nothing-test
  (let [dir (str (temp-dirs/temp-dir! "jonquil-world-ops-"))
        e (fake-ev (atom []) {:save-answer {:error "can't write it" :unwritable [{:var "user/f" :kind "file"}]}})
        r (call-sync ops/save! {:ev e :dir dir :kind :agent :protect #{}})]
    (is (= "can't write it" (:error r)))
    (is (empty? (world/entries dir)))
    (is (empty? (.listFiles (io/file dir))))))

(deftest restore-saves-first-test
  (let [dir (str (temp-dirs/temp-dir! "jonquil-world-ops-"))
        seen (atom [])
        e (fake-ev seen {:save-answer {:saved 1 :placeholders []} :restore-answer {:restored 3 :placeholders []}})
        target (:path (call-sync ops/save! {:ev e :dir dir :kind :agent :protect #{}}))
        {:keys [first restore]} (call-sync ops/restore! {:ev e :dir dir :path target})]
    (is (= {:restored 3 :placeholders []} restore))
    (is (= :before-restore (:kind (world/agent-entry (world/entries dir) (world/entry-id first)))))
    (is (= [:save :save :restore] (map clojure.core/first @seen)))
    (is (= target (second (last @seen))) "the picked save, never pruned before it's read")))

(deftest restore-stops-when-the-first-save-fails-test
  (let [dir (str (temp-dirs/temp-dir! "jonquil-world-ops-"))
        seen (atom [])
        e (fake-ev seen {:save-answer {:error "busy file"}})
        r (call-sync ops/restore! {:ev e :dir dir :path (str dir "/0000000000001.world")})]
    (is (= "busy file" (:error (:first r))))
    (is (nil? (:restore r)))
    (testing "never restore over data that couldn't be kept"
      (is (not-any? #(= :restore (clojure.core/first %)) @seen)))))

(deftest restore-names-its-safety-save-first-test
  ;; review #2 and #3: the agent's kind, and the path known before the op
  (let [dir (str (temp-dirs/temp-dir! "jonquil-world-ops-"))
        e (fake-ev (atom []) {:save-answer {:saved 1 :placeholders []} :restore-answer {:restored 1 :placeholders []}})
        early (atom nil)
        {s :first} (call-sync ops/restore! {:ev e :dir dir :path (str dir "/0000000000001.world")
                                            :first-kind :agent-before-restore
                                            :on-first-path (fn [p] (reset! early p))})]
    (is (= (:path s) @early))
    (is (= :agent-before-restore (:kind (first (world/entries dir)))))))
