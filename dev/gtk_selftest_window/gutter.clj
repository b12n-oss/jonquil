(ns gtk-selftest-window.gutter
  "The Definitions editor's left gutter (jonquil.gtk.gutter): its bars
  follow the lines of the ranges they mark, through scrolling."
  (:require [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.gutter :as gutter]))

(defn- line-start [text n]
  ;; offset of line n (0-based)
  (loop [i 0 line 0]
    (if (= line n) i (recur (inc (.indexOf ^String text "\n" (int i))) (inc line)))))

(defn row-gutter-bars-follow-lines
  "A gutter on a fresh editor in its own small window: one range over lines
  3 to 5 gives one bar three lines tall, drawn after set-ranges!, and
  scrolling down moves it up by the scrolled amount."
  [_ui]
  (go-row :gutter-bars-follow-lines support/check
    (let [text (apply str (map #(str "line " % "\n") (range 60)))
          {:keys [win ed gt]} (on-gtk (let [win (g/gtk-window-new)
                                            ed (editor/make-editor)
                                            gt (gutter/attach! ed)]
                                        (g/gtk-window-set-default-size win 300 160)
                                        (g/gtk-window-set-child win (:scroll ed))
                                        (g/gtk-window-present win)
                                        (editor/set-text! ed text)
                                        {:win win :ed ed :gt gt}))]
      (try
        (let [shown? (await-until 3000 20 #(pos? (g/gtk-widget-get-height (:view ed))))
              width (on-gtk (g/gtk-widget-get-width (:area gt)))
              draws0 (on-gtk @(:draws gt))
              _ (on-gtk (gutter/set-ranges! gt [[(line-start text 3) (dec (line-start text 6))]]))
              drawn? (await-until 3000 20 #(> @(:draws gt) draws0))
              line-h (on-gtk (second (gutter/line-yrange gt 0)))
              ;; where line 3's text starts in the text window: the bar lines
              ;; up with it (the view's top margin included)
              text-y (on-gtk (let [[by _] (gutter/line-yrange gt (line-start text 3))
                                   out (ffi/alloc 8)]
                               (try (g/gtk-text-view-buffer-to-window-coords (:view ed) g/TEXT-WINDOW-TEXT 0 by out (+ out 4))
                                    (ffi/read out :int 4)
                                    (finally (ffi/free out)))))
              [r1] (on-gtk (gutter/bar-rects gt))
              adj (on-gtk (g/gtk-scrollable-get-vadjustment (:view ed)))
              _ (on-gtk (g/gtk-adjustment-set-value adj (* 2.0 line-h)))
              moved? (await-until 3000 20 #(= (- (second r1) (* 2 line-h)) (second (first (gutter/bar-rects gt)))))
              [r2] (on-gtk (gutter/bar-rects gt))]
          (on-gtk (support/check :gutter-bars-follow-lines
                                 (and shown? drawn? (= 6 width) (pos? line-h)
                                      (= (* 3 line-h) (nth r1 3)) (= text-y (second r1)) moved?)
                                 {:shown shown? :drawn drawn? :width width :line-h line-h
                                  :text-y text-y :r1 r1 :r2 r2 :moved moved?})))
        (finally (on-gtk (g/gtk-window-close win)))))))
