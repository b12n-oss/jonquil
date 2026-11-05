(ns jonquil.layouts
  "LM8's named layouts: the window's arrangement (whether the file tree
  shows and how wide, which right-hand panel shows and how wide, where the
  editor and console split), saved under a name made from what it shows
  and brought back from the palette. No GTK here; the window measures and
  applies."
  (:require [jonquil.palette :as palette]))

(defn layout-name
  "A layout's name, from what shows: Editor only, File tree, Trace panel,
  File tree and Trace panel. A panel goes by the palette's label for it."
  [{:keys [tree? panel]}]
  (let [p (when panel (str (get palette/PANEL-LABELS panel (name panel)) " panel"))]
    (cond (and tree? p) (str "File tree and " p)
          tree? "File tree"
          p p
          :else "Editor only")))

(defn save
  "layouts ({name layout}) with layout under its name, replacing one with
  the same parts showing."
  [layouts layout]
  (assoc layouts (layout-name layout) layout))

(defn entries
  "The palette's entries for layouts, sorted by name: {:id [:layout/use
  name] :doc \"Use layout: name\" :chords [] :source \"layout\"}. The window
  runs these itself; they aren't registry commands."
  [layouts]
  (vec (for [n (sort (keys layouts))]
         {:id [:layout/use n] :doc (str "Use layout: " n) :chords [] :source "layout"})))
