(ns jonquil.gtk.gutter
  "A left gutter for the Definitions editor: a GtkDrawingArea set as the text
  view's GTK_TEXT_WINDOW_LEFT gutter, drawing a bar beside the lines of each
  range it's given (LM3's changed forms). The ranges live in an atom that
  set-ranges! replaces; a draw only reads them, and a vertical scroll
  redraws. GTK thread only."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.theme :as theme]))

(def WIDTH
  ;; px, the gutter
  6)

(def ^:private BAR
  ;; px, the bar, at the gutter's left edge
  3)

(defn- rgb
  "#rrggbb as [r g b] doubles in 0..1."
  [hex]
  (let [n (Long/parseLong (subs hex 1) 16)]
    (mapv #(/ (bit-and (bit-shift-right n %) 255) 255.0) [16 8 0])))

(defn line-yrange
  "[y height] of the line holding offset, in buffer coordinates."
  [{:keys [view buf]} offset]
  (let [out (ffi/alloc 8)]
    (try
      (g/with-iters* buf offset offset
        (fn [it _] (g/gtk-text-view-get-line-yrange view it out (+ out 4))))
      [(ffi/read out :int 0) (ffi/read out :int 4)]
      (finally (ffi/free out)))))

(defn- to-gutter-y
  "Buffer y as the gutter's own y."
  [{:keys [view]} y]
  (let [out (ffi/alloc 8)]
    (try
      (g/gtk-text-view-buffer-to-window-coords view g/TEXT-WINDOW-LEFT 0 y out (+ out 4))
      (ffi/read out :int 4)
      (finally (ffi/free out)))))

(defn bar-rects
  "[[x y w h]] in the gutter's coordinates, one per range [start end):
  from the top of start's line to the bottom of the line holding end's last
  character."
  [{:keys [ranges] :as gt}]
  (vec (for [[s e] @ranges
             :let [[y0 _] (line-yrange gt s)
                   [y1 h1] (line-yrange gt (max s (dec e)))]]
         [0 (to-gutter-y gt y0) BAR (- (+ y1 h1) y0)])))

(defn- draw! [gt cr]
  (let [[r gr b] @(:color gt)]
    (g/cairo-set-source-rgb cr r gr b)
    (doseq [[x y w h] (bar-rects gt)]
      (g/cairo-rectangle cr (double x) (double y) (double w) (double h)))
    (g/cairo-fill cr))
  (swap! (:draws gt) inc))

(defn set-ranges!
  "Replaces the ranges, [[start end] ...] offsets, and redraws."
  [gt ranges]
  (reset! (:ranges gt) (vec ranges))
  (g/gtk-widget-queue-draw (:area gt))
  nil)

(defn apply-theme!
  "The bar in theme t's :changed-mark."
  [gt t]
  (reset! (:color gt) (rgb (:changed-mark t)))
  (g/gtk-widget-queue-draw (:area gt))
  nil)

(defn attach!
  "Sets a gutter as ed's view's left gutter: {:area :view :buf :ranges
  :color :draws}, :draws counting the draws (for rows)."
  [ed]
  (let [area (g/gtk-drawing-area-new)
        gt {:area area :view (:view ed) :buf (:buf ed)
            :ranges (atom []) :draws (atom 0)
            :color (atom (rgb (:changed-mark (theme/theme theme/default-theme))))}]
    (g/gtk-widget-set-size-request area WIDTH -1)
    (g/gtk-drawing-area-set-draw-func area
                                      (callable (fn [_area cr _w _h _data] (draw! gt cr) nil)
                                                [:pointer :pointer :int :int :pointer] :void)
                                      ffi/null ffi/null)
    (g/gtk-text-view-set-gutter (:view ed) g/TEXT-WINDOW-LEFT area)
    (g/g-signal-connect-data (g/gtk-scrollable-get-vadjustment (:view ed)) "value-changed"
                             (callable (fn [_adj _data] (g/gtk-widget-queue-draw area) nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    gt))
