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

(defn rebuild?
  "Whether new fibers need the rows rebuilt: their ids or states differ
  from old's. Only the ages moving is a relabel in place, so the focused
  row and the scroll stay put through the panel's one-second refresh."
  [old new]
  (not= (map (juxt :id :state) old) (map (juxt :id :state) new)))

(defn no-jolt-goes-to
  "Where \"Processes need Jolt.\" goes: the status bar when a person asked
  (shown? true), else the panel's own footer, so the timer's refresh never
  writes a line a second to the status bar and Messages."
  [asked?]
  (if asked? :status :footer))

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

(defn- store-fibers!
  "Records fibers and their row texts as what the list shows."
  [pane fibers rows]
  (reset! (:fibers pane) (vec fibers))
  (reset! (:rows pane) rows))

(defn- rebuild-labels!
  "Replaces the list's rows with one label per row text and records the
  labels."
  [pane rows]
  (let [lb (:list pane)]
    (g/gtk-list-box-remove-all lb)
    (reset! (:labels pane)
            (mapv (fn [r]
                    (let [label (g/gtk-label-new r)]
                      (g/gtk-label-set-xalign label 0.0)
                      (g/gtk-widget-set-halign label g/ALIGN-START)
                      (g/gtk-list-box-append lb label)
                      label))
                  rows))))

(defn show!
  "Shows fibers (fibers-rt's :list), newest first, keeping the selected
  fiber selected; rebuilds only when a row changed."
  [pane fibers]
  (let [lb (:list pane)
        rows (mapv row-text fibers)
        keep-id (selected-id pane)]
    (cond
      (= rows @(:rows pane)) nil
      (rebuild? @(:fibers pane) fibers)
      (do (store-fibers! pane fibers rows)
          (rebuild-labels! pane rows)
          (when-let [i (selected-index fibers keep-id)]
            (g/gtk-list-box-select-row lb (g/gtk-list-box-get-row-at-index lb i))))
      ;; the same fibers in the same states: new ages, same rows
      :else
      (do (store-fibers! pane fibers rows)
          (doseq [[label r] (map vector @(:labels pane) rows)]
            (g/gtk-label-set-text label r))))
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

(defn- action-buttons
  "The four action buttons, Interrupt, Kill, Forget finished and Close, each
  calling its handler (the first two on the selected fiber)."
  [pane]
  (let [call (fn [k & args]
               (try (when-let [f (k @(:handlers pane))] (apply f args))
                    (catch :default e (report! pane (str "; " (ex-message e))))))
        on-selected (fn [k] (if-let [id (selected-id pane)] (call k id) (report! pane "Select a fiber first.")))]
    [(button! "Interrupt" #(on-selected :on-interrupt))
     (button! "Kill" #(on-selected :on-kill))
     (button! "Forget finished" #(call :on-forget))
     (button! "Close" #(call :on-close))]))

(defn- lay-out!
  "Sets up the list and the labels and packs the widgets into the root, the
  buttons into bar first."
  [{:keys [root title scroll footer] lb :list} bar buttons]
  (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
  (g/gtk-scrolled-window-set-child scroll lb)
  (g/gtk-widget-set-vexpand scroll 1)
  (doseq [l [title footer]]
    (g/gtk-label-set-xalign l 0.0)
    (g/gtk-label-set-wrap l 1))
  (doseq [b buttons] (g/gtk-box-append bar b))
  (doseq [w [title scroll bar footer]] (g/gtk-box-append root w))
  (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1))

(defn make-pane
  "Builds the panel: {:root :title :list :scroll :footer :rows :fibers
  :handlers}. :root starts visible; the window hides it."
  []
  (let [bar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 4)
        pane {:root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
              :title (g/gtk-label-new "Fibers your program spawned")
              :list (g/gtk-list-box-new) :scroll (g/gtk-scrolled-window-new)
              :footer (g/gtk-label-new "")
              :rows (atom []) :fibers (atom []) :labels (atom []) :handlers (atom {})}]
    (lay-out! pane bar (action-buttons pane))
    pane))
