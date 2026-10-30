(ns jonquil.gtk.locations-pane
  "The locations list in the right-hand slot: the list's title, one row
  per place (line: text), the current place selected, a footer for its
  position, and Close. jonquil.locations owns the list;
  jonquil.gtk.window decides where it shows and what a row does."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.locations :as loc]))

(defn report!
  "Puts msg on the panel's footer."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) msg)
  nil)

(defn set-handlers!
  "Registers {:on-activate (fn [index]) :on-close (fn [])}."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn show!
  "Shows lst (a jonquil.locations list): its title, one row per entry,
  its current entry selected, and its position on the footer."
  [pane lst]
  (let [lb (:list pane)
        rows (mapv (fn [{:keys [line text]}] (str line ": " text)) (:entries lst))]
    (reset! (:rows pane) rows)
    (g/gtk-label-set-text (:title pane) (:title lst))
    (g/gtk-list-box-remove-all lb)
    (doseq [r rows]
      ;; plain text, never markup: a row carries the buffer's own text
      (let [label (g/gtk-label-new r)]
        (g/gtk-label-set-xalign label 0.0)
        (g/gtk-widget-set-halign label g/ALIGN-START)
        (g/gtk-list-box-append lb label)))
    (when-let [i (:index lst)]
      (g/gtk-list-box-select-row lb (g/gtk-list-box-get-row-at-index lb i)))
    (report! pane (loc/position-text lst))))

(defn row-texts
  "The shown rows, in order."
  [pane]
  @(:rows pane))

(defn selected-index
  "The selected row's index, or nil."
  [pane]
  (let [r (g/gtk-list-box-get-selected-row (:list pane))]
    (when-not (ffi/null? r) (g/gtk-list-box-row-get-index r))))

(defn title-text [pane] (g/label-text (:title pane)))

(defn footer-text [pane] (g/label-text (:footer pane)))

(defn make-pane
  "Builds the panel: {:root :title :list :scroll :close-btn :footer :rows
  (atom []) :handlers (atom {})}. :root starts visible; the window hides
  it."
  []
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        title (g/gtk-label-new "Locations")
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
                                         (try (when-let [f (:on-activate @(:handlers pane))]
                                                (f (g/gtk-list-box-row-get-index row)))
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
