(ns gtk-selftest-window.results
  "Live console results (LM1): each result line keeps a handle to its
  value; a click inspects it, and the verbs act on the result at the
  caret. In process, so the values live in this process."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]))

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
