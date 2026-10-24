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
               [:find/next :find/previous :focus/toggle :source/open-here
                :find/bar-key :popover/close-all :inspector/close :source/close
                :tree/close-menu :tree/leave :run/stop])
         (set (keys registry/window-commands)))
      "exactly the S1, S2 and S3 commands")
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

(deftest s3-escape-commands-answer-outcomes-test
  (let [calls (atom [])
        rec (fn [k answer] (fn [& _] (swap! calls conj k) answer))
        ctx (fn [bar popovers]
              {:find-bar-key! (rec :find-bar-key! bar)
               :close-popovers-fn (rec :close-popovers-fn popovers)
               :close-inspector-fn (rec :close-inspector-fn nil)
               :source-keys {:close! (rec :close! nil)}
               :tree-keys {:close-menu! (rec :close-menu! nil) :leave! (rec :leave! nil)}
               :stop-fn (rec :stop-fn nil)})
        run (fn [id c] (reset! calls []) [(registry/run-command! registry/window-commands {:command id} c) @calls])]
    (is (= [:handled [:find-bar-key!]] (run :find/bar-key (ctx 1 nil))) "the bar's 1 is :handled")
    (is (= [:to-gtk [:find-bar-key!]] (run :find/bar-key (ctx 0 nil))) "the bar's 0 goes to GTK")
    (is (= [:handled [:close-popovers-fn]] (run :popover/close-all (ctx nil true))))
    (is (= [:next [:close-popovers-fn]] (run :popover/close-all (ctx nil nil))) "closed nothing: the chain goes on")
    (doseq [[id k] [[:inspector/close :close-inspector-fn] [:source/close :close!]
                    [:tree/close-menu :close-menu!] [:tree/leave :leave!] [:run/stop :stop-fn]]]
      (is (= [:handled [k]] (run id (ctx nil nil))) (str id)))))

(deftest s3-escape-conditions-test
  (let [holds (fn [ctx id] ((registry/holds-fn registry/window-conditions ctx) id))
        tree (fn [focused? menu?] {:focused? (constantly focused?) :menu-open? (constantly menu?)})]
    (is (true? (holds {:find-bar-focused? (constantly true)} :find-bar-focused)))
    (is (false? (holds {:find-bar-focused? (constantly false)} :find-bar-focused)))
    (is (true? (holds {:inspector-focused? (constantly true)} :inspector-focused)))
    (is (false? (holds {:inspector-focused? (constantly nil)} :inspector-focused)))
    (is (true? (holds {:evaluating? (constantly true)} :evaluating)))
    (is (false? (holds {:evaluating? (constantly false)} :evaluating)))
    (is (true? (holds {:tree-keys (tree true false)} :tree-focused)))
    (is (false? (holds {:tree-keys (tree false true)} :tree-focused)))
    (is (true? (holds {:tree-keys (tree false true)} :tree-menu-open)))
    (is (false? (holds {:tree-keys (tree true false)} :tree-menu-open)))
    ;; Review Focus 5: a window with no tree
    (is (false? (holds {:tree-keys nil} :tree-focused)) "no tree-keys: not focused, no throw")
    (is (false? (holds {:tree-keys nil} :tree-menu-open)) "no tree-keys: no menu, no throw")))
