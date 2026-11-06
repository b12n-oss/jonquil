(ns jonquil.gtk.gutter
  "A left gutter for the Definitions editor: a GtkDrawingArea set as the text
  view's GTK_TEXT_WINDOW_LEFT gutter, drawing a bar beside the lines of each
  range it's given (LM3's changed forms), and, while it has some, a line
  count label right-aligned before the bar on each counted line (LM4), the
  gutter widening to fit them. The ranges and labels live in atoms that
  set-ranges! and set-counts! replace; a draw only reads them, and a
  vertical scroll redraws. GTK thread only."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
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
         ;; the bar keeps the gutter's right edge's WIDTH, after any labels
         [(- @(:width gt) WIDTH) (to-gutter-y gt y0) BAR (- (+ y1 h1) y0)])))

(def ^:private PAD
  ;; px, between a count label and the bar
  4)

(defn- label-width
  "label's pixel width in the gutter's own font."
  [{:keys [area]} label]
  (let [lay (g/gtk-widget-create-pango-layout area label)
        out (ffi/alloc 8)]
    (try (g/pango-layout-get-pixel-size lay out (+ out 4))
         (ffi/read out :int 0)
         (finally (ffi/free out) (g/g-object-unref lay)))))

(defn count-width
  "The gutter's width now: the bar's alone with no counts, else room for
  the widest label too."
  [gt]
  @(:width gt))

(defn label-spots
  "[[label x y]] in the gutter's coordinates: each count label, right-
  aligned to PAD before the bar, at the top of its line."
  [gt]
  (let [right (- (count-width gt) WIDTH PAD)]
    (vec (for [[off label w] @(:counts gt)]
           [label (- right w) (to-gutter-y gt (first (line-yrange gt off)))]))))

(defn- draw! [gt cr]
  (let [[r gr b] @(:color gt)]
    (g/cairo-set-source-rgb cr r gr b)
    (doseq [[x y w h] (bar-rects gt)]
      (g/cairo-rectangle cr (double x) (double y) (double w) (double h)))
    (g/cairo-fill cr))
  (when (seq @(:counts gt))
    (let [[r gr b] @(:count-color gt)]
      (g/cairo-set-source-rgb cr r gr b)
      (doseq [[label x y] (label-spots gt)]
        (let [lay (g/gtk-widget-create-pango-layout (:area gt) label)]
          (try (g/cairo-move-to cr (double x) (double y))
               (g/pango-cairo-show-layout cr lay)
               (finally (g/g-object-unref lay)))))))
  (swap! (:draws gt) inc))

(defn set-ranges!
  "Replaces the ranges, [[start end] ...] offsets, and redraws."
  [gt ranges]
  (reset! (:ranges gt) (vec ranges))
  (g/gtk-widget-queue-draw (:area gt))
  nil)

(defn set-counts!
  "Replaces the count labels, {line label} with 0-based buffer lines (a
  line past the end is dropped), resizes the gutter to fit them and
  redraws. The labels sit at the lines' offsets now, so a caller hides them
  (set-counts! gt {}) once the text changes."
  [gt m]
  (let [text (g/buffer-text (:buf gt))
        starts (loop [i 0 line 0 acc [0]]
                 (if-let [j (str/index-of text "\n" i)]
                   (recur (inc j) (inc line) (conj acc (inc j)))
                   acc))
        cs (vec (for [[line label] (sort-by key m)
                      :when (< -1 line (count starts))]
                  [(nth starts line) label (label-width gt label)]))
        width (if (seq cs) (+ WIDTH PAD (apply max (map #(nth % 2) cs))) WIDTH)]
    (reset! (:counts gt) cs)
    (reset! (:width gt) width)
    (g/gtk-widget-set-size-request (:area gt) width -1)
    (g/gtk-widget-queue-draw (:area gt))
    nil))

(defn apply-theme!
  "The bar in theme t's :changed-mark, the count labels in its comment
  colour."
  [gt t]
  (reset! (:color gt) (rgb (:changed-mark t)))
  (reset! (:count-color gt) (rgb (get-in t [:tokens :comment])))
  (g/gtk-widget-queue-draw (:area gt))
  nil)

(defn attach!
  "Sets a gutter as ed's view's left gutter: {:area :view :buf :ranges
  :counts :width :color :count-color :draws}, :draws counting the draws
  (for rows)."
  [ed]
  (let [area (g/gtk-drawing-area-new)
        gt {:area area :view (:view ed) :buf (:buf ed)
            :ranges (atom []) :draws (atom 0) :counts (atom []) :width (atom WIDTH)
            :color (atom (rgb (:changed-mark (theme/theme theme/default-theme))))
            :count-color (atom (rgb (get-in (theme/theme theme/default-theme) [:tokens :comment])))}]
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
