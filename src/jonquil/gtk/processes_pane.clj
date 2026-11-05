(ns jonquil.gtk.processes-pane
  "The Processes panel in the right-hand slot (LM4): the fibers a program
  spawned (jonquil.fibers-rt), newest first, with Interrupt, Kill and
  Forget finished, a footer and Close. jonquil.gtk.window runs the ops and
  refreshes the list while the panel shows."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]))

(defn age-text
  "An age in ms as 12s, 4m or 3h, whole units, truncated."
  [ms]
  (cond (< ms 60000) (str (quot ms 1000) "s")
        (< ms 3600000) (str (quot ms 60000) "m")
        :else (str (quot ms 3600000) "h")))

(defn row-text
  "A fiber as a row: #id, its state, its label and its age."
  [{:keys [id state label age-ms]}]
  (str "#" id "  " (name state) "  " label "  " (age-text age-ms)))

(defn footer-text
  "The footer under fibers."
  [fibers]
  (if (empty? fibers)
    "No fibers yet: fibers spawned from now on show here."
    (let [n (count fibers)
          live (count (remove #(#{:done :dead} (:state %)) fibers))]
      (str n (if (= 1 n) " fiber, " " fibers, ") live
           " still running. Fibers spawned before the panel first opened aren't listed."))))

(defn selected-index
  "The row of the fiber with id in fibers, else 0; nil with no fibers."
  [fibers id]
  (when (seq fibers)
    (or (first (keep-indexed (fn [i f] (when (= id (:id f)) i)) fibers)) 0)))

(defn report!
  "Puts msg on the panel's footer."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) msg)
  nil)

(defn set-handlers!
  "Registers {:on-interrupt (fn [id]) :on-kill (fn [id]) :on-forget (fn [])
  :on-close (fn [])}."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn selected-id
  "The selected fiber's id, or nil."
  [pane]
  (let [row (g/gtk-list-box-get-selected-row (:list pane))]
    (when-not (ffi/null? row)
      (:id (get @(:fibers pane) (g/gtk-list-box-row-get-index row))))))

(defn show!
  "Shows fibers (fibers-rt's :list), newest first, keeping the selected
  fiber selected; rebuilds only when a row changed."
  [pane fibers]
  (let [lb (:list pane)
        rows (mapv row-text fibers)
        keep-id (selected-id pane)]
    (when (not= rows @(:rows pane))
      (reset! (:fibers pane) (vec fibers))
      (reset! (:rows pane) rows)
      (g/gtk-list-box-remove-all lb)
      (doseq [r rows]
        (let [label (g/gtk-label-new r)]
          (g/gtk-label-set-xalign label 0.0)
          (g/gtk-widget-set-halign label g/ALIGN-START)
          (g/gtk-list-box-append lb label)))
      (when-let [i (selected-index fibers keep-id)]
        (g/gtk-list-box-select-row lb (g/gtk-list-box-get-row-at-index lb i))))
    (report! pane (footer-text fibers))))

(defn row-texts
  "The shown rows, in order."
  [pane]
  @(:rows pane))

(defn- button! [label f]
  (let [b (g/gtk-button-new-with-label label)]
    (g/g-signal-connect-data b "clicked" (callable (fn [_b _d] (f) nil) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    b))

(defn make-pane
  "Builds the panel: {:root :title :list :scroll :footer :rows :fibers
  :handlers}. :root starts visible; the window hides it."
  []
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        title (g/gtk-label-new "Fibers your program spawned")
        lb (g/gtk-list-box-new)
        scroll (g/gtk-scrolled-window-new)
        bar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 4)
        footer (g/gtk-label-new "")
        pane {:root root :title title :list lb :scroll scroll :footer footer
              :rows (atom []) :fibers (atom []) :handlers (atom {})}
        call (fn [k & args]
               (try (when-let [f (k @(:handlers pane))] (apply f args))
                    (catch :default e (report! pane (str "; " (ex-message e))))))
        on-selected (fn [k] (if-let [id (selected-id pane)] (call k id) (report! pane "Select a fiber first.")))]
    (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
    (g/gtk-scrolled-window-set-child scroll lb)
    (g/gtk-widget-set-vexpand scroll 1)
    (doseq [l [title footer]]
      (g/gtk-label-set-xalign l 0.0)
      (g/gtk-label-set-wrap l 1))
    (doseq [b [(button! "Interrupt" #(on-selected :on-interrupt))
               (button! "Kill" #(on-selected :on-kill))
               (button! "Forget finished" #(call :on-forget))
               (button! "Close" #(call :on-close))]]
      (g/gtk-box-append bar b))
    (doseq [w [title scroll bar footer]] (g/gtk-box-append root w))
    (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1)
    pane))
