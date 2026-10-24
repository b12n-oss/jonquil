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
  (is (= (conj (set (keys expected)) :tab/select) (set (keys registry/window-commands)))
      "exactly the S1 commands")
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

(deftest unknown-command-runs-nothing-test
  (is (nil? (registry/run-command! registry/window-commands {:command :no/such} {}))))
