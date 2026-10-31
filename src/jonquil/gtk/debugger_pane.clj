(ns jonquil.gtk.debugger-pane
  "The Frames panel in the right-hand slot: the last error's title, one row
  per frame and per local (jonquil.debugger/rows), a footer, and Close.
  jonquil.debugger owns the data; jonquil.gtk.window decides what a frame
  or a local row does."
  (:require [jolt.ffi :as ffi]
            [jonquil.debugger :as debugger]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]))

(defn report!
  "Puts msg on the panel's footer."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) msg)
  nil)

(defn set-handlers!
  "Registers {:on-frame (fn [frame-index]) :on-local (fn [frame-index
  local-name]) :on-close (fn [])}."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn show!
  "Shows data (jonquil.debugger/frames-data's map, or a {:note}): its
  title, one row per jonquil.debugger/rows entry, the first row selected."
  [pane data]
  (let [lb (:list pane)
        rows (debugger/rows data)]
    (reset! (:rows pane) rows)
    (g/gtk-label-set-text (:title pane) (or (:title data) "Frames"))
    (g/gtk-list-box-remove-all lb)
    (doseq [{:keys [text]} rows]
      ;; plain text, never markup: a row carries the program's own values
      (let [label (g/gtk-label-new text)]
        (g/gtk-label-set-xalign label 0.0)
        (g/gtk-widget-set-halign label g/ALIGN-START)
        (g/gtk-list-box-append lb label)))
    (when (seq rows)
      (g/gtk-list-box-select-row lb (g/gtk-list-box-get-row-at-index lb 0)))
    (report! pane (if (some #(= :frame (:kind %)) rows)
                    "Enter on a frame opens its file; on a local, the inspector."
                    ""))))

(defn row-texts
  "The shown rows' texts, in order."
  [pane]
  (mapv :text @(:rows pane)))

(defn title-text [pane] (g/label-text (:title pane)))

(defn footer-text [pane] (g/label-text (:footer pane)))

(defn activate!
  "Runs what row index i does: a frame row's :on-frame, a local row's
  :on-local; a note row does nothing."
  [pane i]
  (let [{:keys [kind frame name]} (get @(:rows pane) i)
        {:keys [on-frame on-local]} @(:handlers pane)]
    (case kind
      :frame (when on-frame (on-frame frame))
      :local (when on-local (on-local frame name))
      nil)))

(defn focus!
  "Gives the rows keyboard focus."
  [pane]
  (g/gtk-widget-grab-focus (:list pane))
  nil)

(defn make-pane
  "Builds the panel: {:root :title :list :scroll :close-btn :footer :rows
  (atom []) :handlers (atom {})}. :root starts visible; the window hides
  it."
  []
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        title (g/gtk-label-new "Frames")
        lb (g/gtk-list-box-new)
        scroll (g/gtk-scrolled-window-new)
        close-btn (g/gtk-button-new-with-label "Close")
        footer (g/gtk-label-new "")
        pane {:root root :title title :list lb :scroll scroll :close-btn close-btn
              :footer footer :rows (atom []) :handlers (atom {})}]
    (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
    (g/gtk-scrolled-window-set-child scroll lb)
    (g/gtk-widget-set-vexpand scroll 1)
    (doseq [l [title footer]]
      (g/gtk-label-set-xalign l 0.0)
      (g/gtk-label-set-wrap l 1))
    (doseq [w [title scroll close-btn footer]] (g/gtk-box-append root w))
    (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1)
    (g/g-signal-connect-data lb "row-activated"
                             (callable (fn [_box row _data]
                                         (try (activate! pane (g/gtk-list-box-row-get-index row))
                                              (catch :default e (report! pane (str "; " (ex-message e)))))
                                         nil)
                                       [:pointer :pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data close-btn "clicked"
                             (callable (fn [_button _data]
                                         (try (when-let [f (:on-close @(:handlers pane))] (f))
                                              (catch :default e (report! pane (str "; " (ex-message e)))))
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    pane))
