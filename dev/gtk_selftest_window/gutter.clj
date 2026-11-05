(ns gtk-selftest-window.gutter
  "The Definitions editor's left gutter (jonquil.gtk.gutter): its bars
  follow the lines of the ranges they mark, through scrolling."
  (:require [clojure.core.async :refer [<! timeout]]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.gutter :as gutter]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]))

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

;; --- marks on forms changed since they last ran (LM3) ----------------------

(defn- run-palette!
  "Opens the palette, filters it to query and runs the first entry, then
  closes it. GTK-thread only."
  [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn- status [ui] (g/label-text (:status-label ui)))

(defn- ranges [ui] @(:ranges (:gutter (:changed (:editor ui)))))

(defn- form-range [text form]
  (let [i (str/index-of text form)] [i (+ i (count form))]))

(defn- forget-runs!
  "The active tab as if it never ran: earlier rows ran code in it."
  [ui]
  (on-gtk (reset! (:ran (:changed (:editor ui))) nil)
          ((deref (:refresh (:changed (:editor ui)))))))

(defn row-changed-not-before-first-run
  "A tab that never ran marks nothing, and Run the changed forms says to
  run it once first."
  [ui]
  (go-row :changed-not-before-first-run support/check
    (let [_ (forget-runs! ui)
          _ (on-gtk (editor/set-text! (:editor ui) "(defn cn [] 1)"))
          _ (on-gtk (run-palette! ui "run the forms changed"))
          said? (await-until 3000 20 #(= "; Run the tab once first." (status ui)))
          clear? (await-until 1000 20 #(empty? (ranges ui)))]
      (on-gtk (support/check :changed-not-before-first-run (and said? clear?)
                             {:said said? :status (status ui) :ranges (ranges ui)})))))

(defn row-changed-marks-after-edit
  "After a Run, editing one form marks that form's lines only, and Run
  Selection on it clears the mark."
  [ui]
  (go-row :changed-marks-after-edit support/check
    (let [_ (forget-runs! ui)
          code "(defn ca [] 1)\n\n(defn cb [] 2)"
          [ran? _] (support/run-and-await ui code 5000 #(str/includes? % "#'user/cb"))
          clean? (await-until 2000 20 #(empty? (ranges ui)))
          edited "(defn ca [] 1)\n\n(defn cb [] 3)"
          _ (on-gtk (editor/set-text! (:editor ui) edited))
          marked? (await-until 3000 20 #(= [(form-range edited "(defn cb [] 3)")] (ranges ui)))
          ;; the caret inside cb, then Run Selection runs the form there
          _ (on-gtk (let [buf (:buf (:editor ui))
                          at (+ 2 (str/index-of edited "(defn cb"))]
                      (g/with-iters* buf at at (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                      (run-palette! ui "run the selection, or the form")))
          cleared? (await-until 5000 20 #(and (support/idle? ui) (empty? (ranges ui))))]
      (on-gtk (support/check :changed-marks-after-edit (and ran? clean? marked? cleared?)
                             {:ran ran? :clean clean? :marked marked? :cleared cleared? :ranges (ranges ui)})))))

(defn row-run-changed-runs-only-those
  "Run the changed forms runs just the edited forms, as one evaluation,
  and clears their marks; with nothing changed it says so."
  [ui]
  (go-row :run-changed-runs-only-those support/check
    (let [_ (forget-runs! ui)
          code "(def hits (atom 0))\n(defn cc [] 1)"
          [ran? _] (support/run-and-await ui code 5000 #(str/includes? % "#'user/cc"))
          _ (on-gtk (swap! @(resolve 'user/hits) inc))
          edited "(def hits (atom 0))\n(defn cc [] 10)"
          _ (on-gtk (editor/set-text! (:editor ui) edited))
          marked? (await-until 3000 20 #(= 1 (count (ranges ui))))
          mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk (run-palette! ui "run the forms changed"))
          said? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "; Running 1 changed form")
                                           ;; echoed as an entry, so Clear Last Output finds it
                                           (str/includes? (support/since ui mark) "(defn cc [] 10)")))
          redefined? (= 10 ((resolve 'user/cc)))
          kept? (= 1 @@(resolve 'user/hits))
          cleared? (await-until 3000 20 #(empty? (ranges ui)))
          _ (on-gtk (run-palette! ui "run the forms changed"))
          nothing? (await-until 3000 20 #(= "; Nothing has changed since the last run." (status ui)))]
      (on-gtk (support/check :run-changed-runs-only-those
                             (and ran? marked? said? redefined? kept? cleared? nothing?)
                             {:ran ran? :marked marked? :said said? :redefined redefined? :kept kept?
                              :cleared cleared? :nothing nothing? :status (status ui)})))))

;; --- what keys do at the caret (LM6) ----------------------------------------

(defn- hint [ui]
  (let [l (:hint-label ui)] (when (pos? (g/gtk-widget-get-visible l)) (g/label-text l))))

(defn row-key-hints-follow-the-caret
  "The caret on a symbol names its keys in the status bar, from the live
  keymap; on blank space the line goes; the palette toggle turns it off."
  [ui]
  (go-row :key-hints-follow-the-caret support/check
    (let [ed (:editor ui)
          _ (on-gtk (editor/set-text! ed "(defn hinted [x] (inc x))\n\n")
                    (g/with-iters* (:buf ed) 18 18 (fn [a _] (g/gtk-text-buffer-place-cursor (:buf ed) a))))
          on-symbol? (await-until 3000 20 #(= "Ctrl+Q doc · F12 jump · Shift+F6 rename" (hint ui)))
          ;; a switch to another tab takes the old tab's hint away
          _ (on-gtk ((:new-tab!* ui)))
          switched? (await-until 3000 20 #(nil? (hint ui)))
          _ (on-gtk ((:close-tab! ui) (:id ((:active-tab* ui)))))
          _ (on-gtk (g/with-iters* (:buf ed) 27 27 (fn [a _] (g/gtk-text-buffer-place-cursor (:buf ed) a))))
          gone? (await-until 3000 20 #(nil? (hint ui)))
          _ (on-gtk (run-palette! ui "show what keys do at the caret"))
          off? (await-until 3000 20 #(= "; Key hints off." (status ui)))
          _ (on-gtk (g/with-iters* (:buf ed) 18 18 (fn [a _] (g/gtk-text-buffer-place-cursor (:buf ed) a))))
          _ (<! (timeout 400))
          stays-off? (on-gtk (nil? (hint ui)))
          _ (on-gtk (run-palette! ui "show what keys do at the caret"))
          back? (await-until 3000 20 #(str/starts-with? (status ui) "; Key hints on"))]
      (on-gtk (support/check :key-hints-follow-the-caret (and on-symbol? switched? gone? off? stays-off? back?)
                             {:on-symbol on-symbol? :switched switched? :gone gone? :off off? :stays-off stays-off? :back back? :hint (hint ui)})
              (editor/set-text! ed "")))))

