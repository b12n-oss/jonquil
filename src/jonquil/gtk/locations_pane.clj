(ns jonquil.gtk.locations-pane
  "The locations list in the right-hand slot: the list's title, for a
  project list a row of check buttons that filter it by how each place
  uses the name, one row per place (loc/row-text), the current place
  selected, a footer for its position, and Close. jonquil.locations owns the list;
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
  "Registers {:on-activate (fn [index]) :on-close (fn []) :on-hows (fn
  [shown])}: on-hows gets the set of ticked kinds (loc/hows) whenever one
  of the filter's check buttons changes."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn show!
  "Shows lst (a jonquil.locations list): its title, one row per entry,
  its current entry selected, and its position on the footer."
  [pane lst]
  (let [lb (:list pane)
        rows (mapv #(loc/row-text lst %) (:entries lst))
        project? (= :project (:scope lst))]
    (reset! (:rows pane) rows)
    (g/gtk-label-set-text (:title pane) (:title lst))
    (g/gtk-widget-set-visible (:filter-box pane) (if project? 1 0))
    (when project?
      ;; setting a button fires toggled; :syncing? keeps that from
      ;; reaching on-hows
      (reset! (:syncing? pane) true)
      (try
        (doseq [[k btn] (:filter-btns pane)]
          (g/gtk-check-button-set-active btn (if (contains? (:hows lst) k) 1 0)))
        (finally (reset! (:syncing? pane) false))))
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

(defn select!
  "Moves the selection to lst's current entry and its position to the
  footer, without rebuilding the rows: safe inside a row's own
  row-activated signal, which a rebuild would pull the row out of."
  [pane lst]
  (when-let [i (:index lst)]
    (g/gtk-list-box-select-row (:list pane) (g/gtk-list-box-get-row-at-index (:list pane) i)))
  (report! pane (loc/position-text lst)))

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

(defn filter-shown?
  "Whether the filter row is showing."
  [pane]
  (not (zero? (g/gtk-widget-get-visible (:filter-box pane)))))

(defn ticked
  "The set of kinds whose check button is ticked."
  [pane]
  (into #{} (keep (fn [[k btn]] (when-not (zero? (g/gtk-check-button-get-active btn)) k)))
        (:filter-btns pane)))

(defn set-ticked!
  "Ticks exactly the kinds in shown, as a click would, so on-hows runs."
  [pane shown]
  (doseq [[k btn] (:filter-btns pane)]
    (g/gtk-check-button-set-active btn (if (contains? shown k) 1 0))))

(defn footer-text [pane] (g/label-text (:footer pane)))

(defn- connect-filter-btns!
  "Ticks each filter button, adds it to the filter row and connects its
  toggled signal to :on-hows."
  [pane filter-box filter-btns]
  (doseq [[_ btn] filter-btns]
    (g/gtk-check-button-set-active btn 1)
    (g/gtk-box-append filter-box btn)
    (g/g-signal-connect-data btn "toggled"
                             (callable (fn [_btn _data]
                                         (try (when-not @(:syncing? pane)
                                                (when-let [f (:on-hows @(:handlers pane))]
                                                  (f (ticked pane))))
                                              (catch :default e (report! pane (str "; " (ex-message e)))))
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)))

(defn- lay-out!
  "Hides the filter row, sets up the list and the labels, and packs the
  widgets into the root."
  [{:keys [root title filter-box scroll close-btn footer] lb :list}]
  (g/gtk-widget-set-visible filter-box 0)
  (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
  (g/gtk-scrolled-window-set-child scroll lb)
  (g/gtk-widget-set-vexpand scroll 1)
  (doseq [l [title footer]]
    (g/gtk-label-set-xalign l 0.0)
    (g/gtk-label-set-wrap l 1))
  (doseq [w [title filter-box scroll close-btn footer]] (g/gtk-box-append root w))
  (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1))

(defn- connect-list-and-close!
  "Connects the list's row-activated and the Close button's clicked to
  their handlers."
  [{lb :list :keys [close-btn] :as pane}]
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
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn make-pane
  "Builds the panel: {:root :title :filter-box :filter-btns :list :scroll
  :close-btn :footer :rows (atom []) :handlers (atom {}) :syncing? (atom
  false)}. :root starts visible; the window hides it. :filter-btns is
  [[kind button] ...] in loc/hows' order, and the row holding them
  starts hidden."
  []
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        title (g/gtk-label-new "Locations")
        filter-box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 8)
        filter-btns (mapv (fn [k] [k (g/gtk-check-button-new-with-label
                                      ({:call "Calls" :value "Values" :definition "Definitions" :ns "ns"} k))])
                          loc/hows)
        lb (g/gtk-list-box-new)
        scroll (g/gtk-scrolled-window-new)
        close-btn (g/gtk-button-new-with-label "Close")
        footer (g/gtk-label-new "")
        pane {:root root :title title :filter-box filter-box :filter-btns filter-btns
              :list lb :scroll scroll :close-btn close-btn
              :footer footer :rows (atom []) :handlers (atom {}) :syncing? (atom false)}]
    (connect-filter-btns! pane filter-box filter-btns)
    (lay-out! pane)
    (connect-list-and-close! pane)
    pane))
