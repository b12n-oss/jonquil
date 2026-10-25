(ns jonquil.menu-oracle-test
  "Slice S7's differential gate (plugins design, Part 1): the registry's
  context menus must show what the literal vectors they replaced in
  jonquil.gtk.window showed, label and action name in order, and each
  item must call the fn its old item called.

  legacy-editor-menu is transcribed from wire-tab!'s install-context-menu!
  call at 3ade734 and legacy-console-menu from the window's let at the same
  commit. Each old fn becomes the ctx entry it is now: run-form-fn
  :run-form!, jump-fn :jump!, rename-fn :rename!, quick-doc-fn :quick-doc!,
  inspect-last-fn :inspect-last-fn, inspect-form-fn :inspect-form-fn,
  complete-fn :complete!, the four editor/do-* calls :toggle-comment!,
  :format-all!, :format-selection!, :indent! and :unindent!, open-find-fn
  :open-find-fn and open-replace-fn :open-replace-fn; on the console,
  repl-complete-fn :complete! and clear-last-fn :clear-last-fn."
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]))

(def ^:private legacy-editor-menu
  [["Run Form" "run-form" :run-form!]
   ["Jump to Definition" "jump" :jump!]
   ["Rename Symbol" "rename" :rename!]
   ["Quick Documentation" "quick-doc" :quick-doc!]
   ["Inspect Last Result" "inspect-last" :inspect-last-fn]
   ["Inspect Form" "inspect-form" :inspect-form-fn]
   ["Complete" "complete" :complete!]
   ["Toggle Comment" "toggle-comment" :toggle-comment!]
   ["Format All" "format-all" :format-all!]
   ["Format Selection" "format-selection" :format-selection!]
   ["Indent" "indent" :indent!]
   ["Unindent" "unindent" :unindent!]
   ["Find" "find" :open-find-fn]
   ["Replace" "replace" :open-replace-fn]])

(def ^:private legacy-console-menu
  [["Complete" "complete" :complete!]
   ["Clear Last Output" "clear-last-output" :clear-last-fn]])

(defn- check-menu [menu legacy]
  (let [calls (atom [])
        ;; every recorder answers 0, so an edit command's :to-gtk is
        ;; exercised and ignored, as the old fns' 1/0 was
        ctx (into {} (for [k (distinct (map #(nth % 2) legacy))]
                       [k (fn [& _] (swap! calls conj k) 0)]))
        items (registry/menu-items menu ctx)]
    (is (= (map (fn [[l a _]] [l a]) legacy) (map (juxt first second) items)))
    (doseq [[[label _ k] [_ _ thunk]] (map vector legacy items)]
      (reset! calls [])
      (thunk)
      (is (= [k] @calls) label))))

(deftest menu-matches-legacy-test
  (testing "the editor's menu" (check-menu registry/editor-menu legacy-editor-menu))
  (testing "the console's menu" (check-menu registry/console-menu legacy-console-menu)))
