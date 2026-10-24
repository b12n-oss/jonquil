(ns jonquil.command-registry-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as registry]))

(def ^:private ctx-fns
  [:run-fn :open-fn :save-as-fn :save-fn :close-fn :toggle-tree-fn :run-selection-fn
   :clear-last-fn :clear-console-fn :open-find-fn :open-replace-fn :zoom-fn
   :digit-tab-fn :new-tab-fn :theme-cycle-fn :inspect-form-fn :inspect-last-fn :step-tab-fn])

(defn- recording-ctx
  "A ctx whose every fn records [fn-key & args] into calls."
  [calls]
  (into {} (for [k ctx-fns] [k (fn [& args] (swap! calls conj (into [k] args)) nil)])))

(def ^:private expected
  {:run/run [:run-fn] :file/open [:open-fn] :file/save-as [:save-as-fn] :file/save [:save-fn]
   :tab/close [:close-fn] :tree/toggle [:toggle-tree-fn] :run/selection [:run-selection-fn]
   :console/clear-last-output [:clear-last-fn] :console/clear [:clear-console-fn]
   :find/open [:open-find-fn] :find/open-replace [:open-replace-fn]
   :view/zoom-in [:zoom-fn :in] :view/zoom-out [:zoom-fn :out] :view/zoom-reset [:zoom-fn :reset]
   :tab/new [:new-tab-fn] :view/cycle-theme [:theme-cycle-fn]
   :inspect/form [:inspect-form-fn] :inspect/last [:inspect-last-fn]
   :tab/next [:step-tab-fn 1] :tab/previous [:step-tab-fn -1]})

(deftest each-command-calls-its-fn-test
  (is (= (into (conj (set (keys expected)) :tab/select)
               [:find/next :find/previous :focus/toggle :source/open-here])
         (set (keys registry/window-commands)))
      "exactly the S1 and S2 commands")
  (doseq [[id call] expected]
    (let [calls (atom [])]
      (is (= :handled (registry/run-command! registry/window-commands {:command id} (recording-ctx calls))) (str id))
      (is (= [call] @calls) (str id))))
  (let [calls (atom [])]
    (is (= :handled (registry/run-command! registry/window-commands {:command :tab/select :args {:n 9}}
                                           (recording-ctx calls))))
    (is (= [[:digit-tab-fn 9]] @calls))))

(deftest every-command-is-documented-test
  (doseq [[id c] registry/window-commands]
    (is (and (string? (:doc c)) (seq (:doc c))) (str id))
    (is (= :window (:scope c)) (str id))))

(deftest s2-commands-call-their-ctx-fns-test
  (let [calls (atom [])
        rec (fn [k] (fn [& _] (swap! calls conj k) nil))
        ctx {:find-next! (rec :find-next!) :find-prev! (rec :find-prev!)
             :toggle-focus-fn (rec :toggle-focus-fn)
             :source-keys {:open! (rec :open!)}}]
    (doseq [[id k] [[:find/next :find-next!] [:find/previous :find-prev!]
                    [:focus/toggle :toggle-focus-fn] [:source/open-here :open!]]]
      (reset! calls [])
      (is (= :handled (registry/run-command! registry/window-commands {:command id} ctx)) (str id))
      (is (= [k] @calls) (str id)))))

(deftest window-conditions-test
  (let [ctx (fn [nav? focused?] {:find-bar-navigable? (constantly nav?)
                                 :source-keys {:focused? (constantly focused?)}})]
    (is (true? ((registry/holds-fn registry/window-conditions (ctx true false)) :find-bar-navigable)))
    (is (false? ((registry/holds-fn registry/window-conditions (ctx false true)) :find-bar-navigable)))
    (is (true? ((registry/holds-fn registry/window-conditions (ctx false true)) :source-focused)))
    (is (false? ((registry/holds-fn registry/window-conditions (ctx true true)) :no-such-condition))
        "an unknown condition is false")))

(deftest unknown-command-runs-nothing-test
  (is (nil? (registry/run-command! registry/window-commands {:command :no/such} {}))))
