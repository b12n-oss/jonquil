(ns gtk-selftest-window.results
  "Live console results (LM1): each result line keeps a handle to its
  value; a click inspects it, and the verbs act on the result at the
  caret. In process, so the values live in this process."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]))

(defn- run-palette! [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn- status [ui] (g/label-text (:status-label ui)))

(defn- console-caret! [ui offset]
  (let [buf (:buf (:console ui))]
    (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))))

(defn- enter!
  "Types code at the console prompt and presses Return; the out offset
  before it."
  [ui code]
  (on-gtk (let [cv (:console ui)
                m (support/out-end-offset ui)]
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) code -1 1)
            (cview/handle-return! cv false)
            m)))

(defn- console-text [ui]
  (let [buf (:buf (:console ui))]
    (g/buffer-text buf)))

(defn- offset-in
  "An offset inside the last occurrence of needle in the console."
  [ui needle]
  (some-> (str/last-index-of (console-text ui) needle) (+ 3)))

(defn row-result-spans
  "Each result line answers its own ref; the prompt answers none; Clear
  Last Output drops the cleared line's span and keeps the earlier one."
  [ui]
  (go-row :result-spans support/check
    (let [cv (:console ui)
          m1 (enter! ui "(+ 1 2)")
          _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m1) "=> 3")))
          m2 (enter! ui "(str \"a\" \"b\")")
          _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m2) "=> \"ab\"")))
          r1 (on-gtk (cview/result-at cv (offset-in ui "=> 3")))
          r2 (on-gtk (cview/result-at cv (offset-in ui "=> \"ab\"")))
          at-prompt (on-gtk (cview/result-at cv (dec (g/gtk-text-buffer-get-char-count (:buf cv)))))
          _ (on-gtk (cview/clear-last-output! cv))
          r1-after (on-gtk (cview/result-at cv (offset-in ui "=> 3")))
          r2-gone? (on-gtk (nil? (offset-in ui "=> \"ab\"")))]
      (on-gtk (support/check :result-spans
                             (and (= :value (:kind r1)) (= :value (:kind r2)) (not= (:id r1) (:id r2))
                                  (nil? at-prompt) (= r1 r1-after) r2-gone?)
                             {:r1 r1 :r2 r2 :at-prompt at-prompt :r1-after r1-after :r2-gone r2-gone?})))))

(defn row-result-click-inspects
  "A click on a result line opens that value in the inspector."
  [ui]
  (go-row :result-click-inspects support/check
    (try
      (let [m (enter! ui "[1 2 3]")
            _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> [1 2 3]")))
            _ (on-gtk ((:result-click!* ui) (offset-in ui "=> [1 2 3]")))
            inspected? (await-until 3000 20 #(str/includes? (g/label-text (:info (:inspector ui))) "3 items"))]
        (on-gtk (support/check :result-click-inspects inspected?
                               {:inspected inspected? :info (g/label-text (:info (:inspector ui)))})))
      (finally (on-gtk ((:close-inspector!* ui)))))))

(defn row-result-verbs
  "With the caret in a result line: Copy says so, Define puts a def of the
  live value at the prompt, Jump refuses a value that isn't a var, and the
  prompt holds no result. The palette finds the commands by their own
  text; the caret goes back to the prompt at the end."
  [ui]
  (go-row :result-verbs support/check
    (let [cv (:console ui)
          to-prompt! (fn [] (console-caret! ui (g/gtk-text-buffer-get-char-count (:buf cv))))]
      (try
        (let [m (enter! ui "(+ 40 2)")
              _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> 42")))
              at (offset-in ui "=> 42")
              ref (on-gtk (cview/result-at cv at))
              _ (on-gtk (console-caret! ui at) (run-palette! ui "copy the console result"))
              copied? (await-until 3000 20 #(= "; Copied the result." (status ui)))
              _ (on-gtk (console-caret! ui at) (run-palette! ui "jump to the definition of the var the console"))
              not-var? (await-until 3000 20 #(= "; That result isn't a var." (status ui)))
              _ (on-gtk (console-caret! ui at) (run-palette! ui "define a var holding the console result"))
              input (on-gtk (cview/input-text cv))
              defined? (= (str "(def  (jonquil.results-rt/value! " (:id ref) "))") input)
              _ (on-gtk (cview/clear-input! cv)
                        (to-prompt!)
                        (run-palette! ui "inspect the console result"))
              none? (await-until 3000 20 #(= "; No result here." (status ui)))]
          (on-gtk (support/check :result-verbs (and ref copied? not-var? defined? none?)
                                 {:ref ref :copied copied? :not-var not-var? :input input :none none? :status (status ui)})))
        (finally (on-gtk (cview/clear-input! cv) (to-prompt!)))))))
