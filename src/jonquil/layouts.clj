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

(defn- width? [x] (or (nil? x) (and (int? x) (<= 0 x))))

(defn valid?
  "Whether layout can be applied: :tree? a boolean or absent, :panel nil
  or one the palette can bring back (palette/PANEL-LABELS), widths and the
  split non-negative whole numbers or absent."
  [{:keys [tree? panel tree-width panel-width split] :as l}]
  (boolean (and (map? l)
                (or (nil? tree?) (boolean? tree?))
                (or (nil? panel) (contains? palette/PANEL-LABELS panel))
                (width? tree-width) (width? panel-width) (width? split))))

(defn save
  "layouts ({name layout}) with layout under its name, replacing one with
  the same parts showing. A panel the palette can't bring back (Review,
  History) isn't kept."
  [layouts layout]
  (let [l (cond-> layout
            (not (contains? palette/PANEL-LABELS (:panel layout))) (assoc :panel nil)
            (not (contains? palette/PANEL-LABELS (:panel layout))) (dissoc :panel-width))]
    (assoc layouts (layout-name l) l)))

(defn entries
  "The palette's entries for layouts, sorted by name: {:id [:layout/use
  name] :doc \"Use layout: name\" :chords [] :source \"layout\"}. The window
  runs these itself; they aren't registry commands."
  [layouts]
  (vec (for [n (sort (keys layouts))]
         {:id [:layout/use n] :doc (str "Use layout: " n) :chords [] :source "layout"})))
