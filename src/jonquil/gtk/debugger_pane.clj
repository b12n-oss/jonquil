(ns jonquil.gtk.debugger-pane
  "The Frames panel in the right-hand slot: the last error's title, one row
  per frame and per local (jonquil.debugger/rows), a field that evaluates a
  form in the selected row's frame, a footer, and Close.
  jonquil.debugger owns the data; jonquil.gtk.window decides what a frame
  or a local row does."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
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
  local-name]) :on-eval (fn [frame-index-or-nil code]) :on-close (fn [])}.
  :on-eval gets nil when no frame or local row is selected."
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

(def ^:private RESTART-IDS
  ;; :continue is Break on error's: the bar holds it alone while paused,
  ;; with :step beside it at a breakpoint (LM5's stepping)
  [:retry :edit :define :continue :step])

(defn show-restarts!
  "Shows the restart buttons options names (jonquil.restarts/options), in
  order, with their labels; none hides the bar."
  [pane options]
  (let [by-id (into {} (map (juxt :id identity) options))]
    (doseq [id RESTART-IDS]
      (let [btn (get (:restart-btns pane) id)]
        (if-let [{:keys [label]} (by-id id)]
          (do (g/gtk-button-set-label btn label) (g/gtk-widget-set-visible btn 1))
          (g/gtk-widget-set-visible btn 0))))
    (reset! (:restarts pane) (vec (keep (fn [id] (:label (by-id id))) RESTART-IDS)))
    (g/gtk-widget-set-visible (:restart-box pane) (if (seq options) 1 0))
    nil))

(defn restart-labels
  "The restart buttons shown, by label, in order."
  [pane]
  @(:restarts pane))

(defn set-restart-handler!
  "Registers (fn [id]) for a restart button, id :retry, :edit, :define,
  :continue or :step."
  [pane f]
  (reset! (:on-restart pane) f)
  nil)

(defn press-restart!
  "Runs restart id as its button does: for rows."
  [pane id]
  (when-let [f @(:on-restart pane)] (f id)))

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

(defn field-text [pane] (or (g/editable-text (:field pane)) ""))

(defn- selected-frame
  "The selected row's frame index (a frame row's own, a local row's frame),
  or nil."
  [pane]
  (let [r (g/gtk-list-box-get-selected-row (:list pane))]
    (when-not (ffi/null? r)
      (:frame (get @(:rows pane) (g/gtk-list-box-row-get-index r))))))

(defn- evaluate-field!
  "Enter in the field: the code goes to :on-eval with the selected frame,
  and the field clears. Blank code does nothing."
  [pane]
  (let [code (field-text pane)]
    (when-not (str/blank? code)
      (g/gtk-editable-set-text (:field pane) "")
      (when-let [f (:on-eval @(:handlers pane))]
        (f (selected-frame pane) code)))))

(defn eval-text!
  "Puts code in the field and evaluates it as Enter does: for rows."
  [pane code]
  (g/gtk-editable-set-text (:field pane) code)
  (evaluate-field! pane))

(defn focus!
  "Gives the rows keyboard focus."
  [pane]
  (g/gtk-widget-grab-focus (:list pane))
  nil)

(defn- connect-restart-btns!
  "Adds each restart button to the restart row and connects its clicked
  signal to press-restart!."
  [pane restart-box restart-btns]
  (doseq [id RESTART-IDS]
    (let [btn (get restart-btns id)]
      (g/gtk-box-append restart-box btn)
      (g/g-signal-connect-data btn "clicked"
                               (callable (fn [_button _data]
                                           (try (press-restart! pane id)
                                                (catch :default e (report! pane (str "; " (ex-message e)))))
                                           nil)
                                         [:pointer :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT))))

(defn- lay-out!
  "Hides the restart row, sets up the list, the field and the labels, and
  packs the widgets into the root."
  [{:keys [root title restart-box scroll field close-btn footer] lb :list}]
  (g/gtk-widget-set-visible restart-box 0)
  (g/gtk-entry-set-placeholder-text field "Evaluate in the selected frame")
  (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
  (g/gtk-scrolled-window-set-child scroll lb)
  (g/gtk-widget-set-vexpand scroll 1)
  (doseq [l [title footer]]
    (g/gtk-label-set-xalign l 0.0)
    (g/gtk-label-set-wrap l 1))
  (doseq [w [title restart-box scroll field close-btn footer]] (g/gtk-box-append root w))
  (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1))

(defn- connect-row-activated!
  "Connects the list's row-activated signal to activate!."
  [{lb :list :as pane}]
  (g/g-signal-connect-data lb "row-activated"
                           (callable (fn [_box row _data]
                                       (try (activate! pane (g/gtk-list-box-row-get-index row))
                                            (catch :default e (report! pane (str "; " (ex-message e)))))
                                       nil)
                                     [:pointer :pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-list-field-close!
  "Connects the list's row-activated, the field's activate and the Close
  button's clicked to their handlers."
  [{:keys [field close-btn] :as pane}]
  (connect-row-activated! pane)
  (g/g-signal-connect-data field "activate"
                           (callable (fn [_entry _data]
                                       (try (evaluate-field! pane)
                                            (catch :default e (report! pane (str "; " (ex-message e)))))
                                       nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/g-signal-connect-data close-btn "clicked"
                           (callable (fn [_button _data]
                                       (try (when-let [f (:on-close @(:handlers pane))] (f))
                                            (catch :default e (report! pane (str "; " (ex-message e)))))
                                       nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn make-pane
  "Builds the panel: {:root :title :restart-box :restart-btns :list :scroll
  :field :close-btn :footer :rows
  (atom []) :handlers (atom {})}. :root starts visible; the window hides
  it."
  []
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        title (g/gtk-label-new "Frames")
        lb (g/gtk-list-box-new)
        scroll (g/gtk-scrolled-window-new)
        close-btn (g/gtk-button-new-with-label "Close")
        field (g/gtk-entry-new)
        footer (g/gtk-label-new "")
        ;; made once and shown or hidden: no new signal callbacks per error
        restart-box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 4)
        restart-btns (into {} (for [id RESTART-IDS] [id (g/gtk-button-new-with-label (name id))]))
        pane {:root root :title title :list lb :scroll scroll :close-btn close-btn
              :field field :footer footer :rows (atom []) :handlers (atom {})
              :restart-box restart-box :restart-btns restart-btns :restarts (atom [])
              :on-restart (atom nil)}]
    (connect-restart-btns! pane restart-box restart-btns)
    (lay-out! pane)
    (connect-list-field-close! pane)
    pane))
