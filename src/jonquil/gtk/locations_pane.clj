(ns jonquil.gtk.locations-pane
  "The locations list in the right-hand slot: the list's title, for a
  project list a row of check buttons that filter it by how each place
  uses the name and, when the search started from a var, a second row for
  the places that are another var's or that the file can't decide, one
  row per place (loc/row-text), the current place selected, a footer for
  its position, and Close. jonquil.locations owns the list;
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

(def ^:private match-labels {:other "Other vars" :unresolved "Unresolved"})

(defn- sync-filter!
  "Ticks the filter buttons to lst's kinds and match kinds, and puts each
  match kind's count on its button. Setting a button fires toggled;
  :syncing? keeps that from reaching on-hows."
  [pane lst]
  (reset! (:syncing? pane) true)
  (try
    (doseq [[k btn] (:filter-btns pane)]
      (g/gtk-check-button-set-active btn (if (contains? (into (:hows lst) (:matches lst)) k) 1 0)))
    (doseq [[k btn] (:filter-btns pane)
            :when (contains? (set loc/match-kinds) k)]
      (g/gtk-check-button-set-label btn (str (match-labels k) " (" (get (:match-counts lst) k 0) ")")))
    (finally (reset! (:syncing? pane) false))))

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
    (g/gtk-widget-set-visible (:match-box pane) (if (and project? (:target-var? lst)) 1 0))
    (when project? (sync-filter! pane lst))
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

(defn match-filter-shown?
  "Whether the match kinds' row (other vars, unresolved) is showing."
  [pane]
  (not (zero? (g/gtk-widget-get-visible (:match-box pane)))))

(defn match-labels-text
  "The match kinds' button labels, in loc/match-kinds' order."
  [pane]
  (vec (keep (fn [[k btn]] (when (contains? (set loc/match-kinds) k) (g/check-button-label btn)))
             (:filter-btns pane))))

(defn ticked
  "The set of kinds (loc/hows and loc/match-kinds) whose check button is ticked."
  [pane]
  (into #{} (keep (fn [[k btn]] (when-not (zero? (g/gtk-check-button-get-active btn)) k)))
        (:filter-btns pane)))

(defn set-ticked!
  "Ticks exactly the kinds in shown, as a click would, so on-hows runs."
  [pane shown]
  (doseq [[k btn] (:filter-btns pane)]
    (g/gtk-check-button-set-active btn (if (contains? shown k) 1 0))))

(defn footer-text [pane] (g/label-text (:footer pane)))

(defn- notify-hows!
  "Passes the ticked kinds to :on-hows, unless the buttons are being synced
  by show!."
  [pane]
  (when-not @(:syncing? pane)
    (when-let [f (:on-hows @(:handlers pane))]
      (f (ticked pane)))))

(defn- connect-filter-btns!
  "Ticks each how button, adds it to the filter row (a match kind's to the
  match row, unticked) and connects its toggled signal to :on-hows."
  [pane filter-box match-box filter-btns]
  (doseq [[k btn] filter-btns
          :let [match? (contains? (set loc/match-kinds) k)]]
    (g/gtk-check-button-set-active btn (if match? 0 1))
    (g/gtk-box-append (if match? match-box filter-box) btn)
    (g/g-signal-connect-data btn "toggled"
                             (callable (fn [_btn _data]
                                         (try (notify-hows! pane)
                                              (catch :default e (report! pane (str "; " (ex-message e)))))
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)))

(defn- lay-out!
  "Hides the filter row, sets up the list and the labels, and packs the
  widgets into the root."
  [{:keys [root title filter-box match-box scroll close-btn footer] lb :list}]
  (g/gtk-widget-set-visible filter-box 0)
  (g/gtk-widget-set-visible match-box 0)
  (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
  (g/gtk-scrolled-window-set-child scroll lb)
  (g/gtk-widget-set-vexpand scroll 1)
  (doseq [l [title footer]]
    (g/gtk-label-set-xalign l 0.0)
    (g/gtk-label-set-wrap l 1))
  (doseq [w [title filter-box match-box scroll close-btn footer]] (g/gtk-box-append root w))
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
  "Builds the panel: {:root :title :filter-box :match-box :filter-btns
  :list :scroll :close-btn :footer :rows (atom []) :handlers (atom {})
  :syncing? (atom false)}. :root starts visible; the window hides it.
  :filter-btns is [[kind button] ...] in loc/hows' then loc/match-kinds'
  order; the rows holding them start hidden."
  []
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        title (g/gtk-label-new "Locations")
        filter-box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 8)
        match-box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 8)
        filter-btns (mapv (fn [k] [k (g/gtk-check-button-new-with-label
                                      ({:call "Calls" :value "Values" :definition "Definitions" :ns "ns"
                                        :other "Other vars" :unresolved "Unresolved"} k))])
                          (concat loc/hows loc/match-kinds))
        lb (g/gtk-list-box-new)
        scroll (g/gtk-scrolled-window-new)
        close-btn (g/gtk-button-new-with-label "Close")
        footer (g/gtk-label-new "")
        pane {:root root :title title :filter-box filter-box :match-box match-box :filter-btns filter-btns
              :list lb :scroll scroll :close-btn close-btn
              :footer footer :rows (atom []) :handlers (atom {}) :syncing? (atom false)}]
    (connect-filter-btns! pane filter-box match-box filter-btns)
    (lay-out! pane)
    (connect-list-and-close! pane)
    pane))
