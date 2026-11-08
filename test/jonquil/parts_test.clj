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

(defn- message [f]
  (try (f) nil (catch Exception e (ex-message e))))

(deftest merge-commands-refuses-a-duplicate
  (testing "a spec id already in core"
    (is (= "duplicate command :p/go: :core and :p"
           (message #(parts/merge-commands {:p/go {:doc "x" :scope :window}} [go-spec] :window call)))))
  (testing "two specs"
    (let [other {:id :q :commands {:p/go {:doc "Again." :scope :window :call [:go-fn 2]}}}]
      (is (= "duplicate command :p/go: :p and :q"
             (message #(parts/merge-commands {} [go-spec other] :window call)))))))

(deftest check-clashes-refuses-a-cross-scope-duplicate
  (let [cores {:window {:run/run {:doc "x" :scope :window}}
               :editor {:edit/redo {:doc "y" :scope :editor}}}
        ed-spec (fn [id owner] {:id owner :commands {id {:doc "z" :scope :editor :call [:f]}}})]
    (testing "a spec :editor command reusing a core :window id"
      (is (= "duplicate command :run/run: :core and :p"
             (message #(parts/check-clashes cores [(ed-spec :run/run :p)])))))
    (testing "two specs in different scopes"
      (let [win {:id :q :commands {:x/y {:doc "w" :scope :window :call [:f]}}}
            ed {:id :p :commands {:x/y {:doc "z" :scope :editor :call [:f]}}}]
        (is (= "duplicate command :x/y: :q and :p"
               (message #(parts/check-clashes cores [win ed]))))))
    (testing "no clash passes"
      (is (nil? (parts/check-clashes cores [(ed-spec :p/new :p)]))))))

(deftest registry-tables-unchanged-with-no-specs
  (is (empty? parts/specs))
  (is (= (count registry/commands)
         (+ (count registry/window-commands)
            (count registry/editor-commands)
            (count registry/console-commands)))))
