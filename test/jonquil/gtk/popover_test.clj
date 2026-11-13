(ns jonquil.gtk.popover-test
  "popover/refocus!, the step posted when a popover closes, with GTK stubbed:
  which widget holds the focus decides whether the editor view takes it."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.ffi :as ffi]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]))

(defn- refocus
  "Runs refocus! with the root's focus on `focus` and the popover open or
  closed. Returns the widgets grab-focus was called on."
  [{:keys [focus open? rooted?] :or {rooted? true}}]
  (let [grabbed (atom [])
        pp {:view ::view :popover ::pop :open? (atom open?)}]
    (with-redefs [ffi/null? nil?
                  g/gtk-widget-get-root (fn [w] (when (and rooted? (= ::view w)) ::root))
                  g/gtk-root-get-focus (fn [_] focus)
                  g/gtk-widget-get-parent (fn [w] (when (= ::view w) ::scroller))
                  g/gtk-widget-is-ancestor (fn [w anc] (if (and (= ::button w) (= ::pop anc)) 1 0))
                  g/gtk-widget-grab-focus (fn [w] (swap! grabbed conj w) 1)]
      (#'popover/refocus! pp)
      @grabbed)))

(deftest refocus-test
  (testing "the view takes the focus when nothing else has it"
    (is (= [::view] (refocus {:focus nil :open? false})) "focus is nowhere")
    (is (= [::view] (refocus {:focus ::scroller :open? false}))
        "on the view's container, where GTK leaves it once the popover unmaps"))
  (testing "X11: GTK hasn't moved focus out of the closed popover yet"
    (is (= [::view] (refocus {:focus ::button :open? false})) "on a button inside it")
    (is (= [::view] (refocus {:focus ::pop :open? false})) "on the popover itself"))
  (testing "the focus is left where it is"
    (is (= [] (refocus {:focus ::button :open? true})) "the popover was opened again since")
    (is (= [] (refocus {:focus ::elsewhere :open? false})) "another widget took it")
    (is (= [] (refocus {:focus ::view :open? false})) "the view already has it")
    (is (= [] (refocus {:focus nil :open? false :rooted? false})) "the view left its window")))
