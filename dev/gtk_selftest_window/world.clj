(ns gtk-selftest-window.world
  "Save World and Restore World (LM7) in the real window, in process: a
  var's data saved, changed at the prompt, and brought back. The window's
  settings path is the harness's temp one, so the world file is too."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]))

(defn- run-palette!
  "support/run-palette!: the active tab's palette."
  [ui query]
  (support/run-palette! ui query))

(defn- status [ui] (g/label-text (:status-label ui)))

(defn- enter!
  "Types code at the console prompt and presses Return; the out offset
  before it."
  [ui code]
  (on-gtk (let [cv (:console ui)
                m (support/out-end-offset ui)]
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) code -1 1)
            (cview/handle-return! cv false)
            m)))

(defn row-world-save-restore
  "Save World after defining wcounter as (atom 41), then (reset! wcounter
  0) at the prompt, then Restore World: @wcounter is 41 again, and the 0
  was saved first. Restore an Earlier World, picking the save made before
  that restore, brings the 0 back. Restore World again brings back 41:
  the save made on purpose, never one made before a restore."
  [ui]
  (go-row :world-save-restore support/check
    (let [[ran? _] (support/run-and-await ui "(def wcounter (atom 41))" 5000 #(str/includes? % "#'user/wcounter"))
          _ (on-gtk (run-palette! ui "save world"))
          saved? (await-until 5000 20 #(str/starts-with? (status ui) "; Saved "))
          saved-status (on-gtk (status ui))
          m (enter! ui "(reset! wcounter 0)")
          reset? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "0")))
          _ (on-gtk (run-palette! ui "restore world"))
          restored? (await-until 5000 20 #(str/starts-with? (status ui) "; Restored "))
          restored-status (on-gtk (status ui))
          m2 (enter! ui "@wcounter")
          back? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m2) "41")))
          ;; the save made before that restore holds the 0
          _ (on-gtk (g/gtk-label-set-text (:status-label ui) "; row: waiting for the second restore")
                    (run-palette! ui "earlier world")
                    (let [p (:palette ((:active-tab* ui)))]
                      (palette/set-query! p "before a restore")
                      (palette/run-selected! p)
                      (when (popover/open? p) (popover/hide! p))))
          undone? (await-until 5000 20 #(str/starts-with? (status ui) "; Restored "))
          m3 (enter! ui "@wcounter")
          zero? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m3) "=> 0")))
          ;; Restore World again: the save made on purpose, 41, not the
          ;; ones made before each restore
          _ (on-gtk (g/gtk-label-set-text (:status-label ui) "; row: waiting for the third restore")
                    (run-palette! ui "restore world"))
          again? (await-until 5000 20 #(str/starts-with? (status ui) "; Restored "))
          m4 (enter! ui "@wcounter")
          deliberate? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m4) "=> 41")))]
      (on-gtk (support/check :world-save-restore
                             (and ran? saved? reset? restored? back? undone? zero? again? deliberate?)
                             {:ran ran? :saved saved-status :reset reset? :restored restored-status
                              :back back? :undone undone? :zero zero? :again again? :deliberate deliberate?
                              :status (status ui) :tail (support/since ui m4)})))))
