(ns jonquil.parts-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]
            [jonquil.parts :as parts]))

(defn- call [k & args]
  (fn [ctx _args] (apply (get ctx k) args) :handled))

(def ^:private go-spec
  {:id :p :commands {:p/go {:doc "Go." :scope :window :call [:go-fn 1]}}})

(deftest merge-commands-adds-a-spec-command
  (let [seen (atom nil)
        merged (parts/merge-commands {} [go-spec] :window call)
        cmd (:p/go merged)]
    (is (= "Go." (:doc cmd)))
    (is (not (contains? cmd :call)))
    (is (= :handled ((:run cmd) {:go-fn (fn [n] (reset! seen n))} nil)))
    (is (= 1 @seen))))

(deftest merge-commands-filters-by-scope
  (let [spec {:id :p :commands {:p/ed {:doc "Ed." :scope :editor :call [:ed-fn]}}}]
    (is (not (contains? (parts/merge-commands {} [spec] :window call) :p/ed)))
    (is (contains? (parts/merge-commands {} [spec] :editor call) :p/ed))))

(deftest merge-commands-refuses-a-duplicate
  (testing "a spec id already in core"
    (let [msg (try (parts/merge-commands {:p/go {:doc "x" :scope :window}} [go-spec] :window call)
                   nil
                   (catch Exception e (ex-message e)))]
      (is (string? msg))
      (is (re-find #":p/go" msg))
      (is (re-find #":core" msg))
      (is (re-find #":p\b" msg))))
  (testing "two specs"
    (let [other {:id :q :commands {:p/go {:doc "Again." :scope :window :call [:go-fn 2]}}}
          msg (try (parts/merge-commands {} [go-spec other] :window call)
                   nil
                   (catch Exception e (ex-message e)))]
      (is (string? msg))
      (is (re-find #":p/go" msg))
      (is (re-find #":p\b" msg))
      (is (re-find #":q\b" msg)))))

(deftest registry-tables-unchanged-with-no-specs
  (is (empty? parts/specs))
  (is (= (count registry/commands)
         (+ (count registry/window-commands)
            (count registry/editor-commands)
            (count registry/console-commands)))))
