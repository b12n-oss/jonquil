(ns jonquil.gtk.namespaces-pane
  "The Namespaces panel's widgets in the right-hand slot: a filter entry
  and ⟳, the crumb line, the list (one row per jonquil.namespaces-view
  row: a name and dimmer text beside it), a footer, and Close. The part
  (jonquil.gtk.parts.namespaces) decides what the rows are and what each
  key does; this namespace only shows them and reports keys."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]))

(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-KP-ENTER 0xff8d)
(def ^:private KEY-BACKSPACE 0xff08)
(def ^:private KEY-DOWN 0xff54)

(defn list-key
  "What a key pressed in the list means: :back for Backspace, :inspect for
  Shift+Return, else nil (plain Return is the list's own row-activated)."
  [keyval state]
  (cond
    (= keyval KEY-BACKSPACE) :back
    (and (#{KEY-RETURN KEY-KP-ENTER} keyval) (pos? (bit-and state g/SHIFT-MASK))) :inspect))

(defn filter-key
  "What a key pressed in the filter means: :down for Down, else nil."
  [keyval]
  (when (= keyval KEY-DOWN) :down))

(defn row-label
  "The text of a row's first label: the name, or the group's arrow, label
  and count."
  [{:keys [kind name label count expanded? text]}]
  (case kind
    :group (str (if expanded? "▾ " "▸ ") label " (" count ")")
    :more text
    name))

(defn row-meta
  "The dimmer text beside a row's name."
  [{:keys [kind count meta]}]
  (case kind
    :ns (str count)
    :var (or meta "")
    ""))

(defn report!
  "Puts msg on the panel's footer."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) (str msg))
  nil)

(defn set-handlers!
  "Registers {:on-activate (fn [row shift?]) :on-back (fn []) :on-filter
  (fn [text]) :on-refresh (fn []) :on-select (fn [row]) :on-close (fn [])}."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn- call!
  "Runs handler k with args; a throw goes to the footer."
  [pane k & args]
  (try (when-let [f (k @(:handlers pane))] (apply f args))
       (catch :default e (report! pane (str "; " (ex-message e))))))

(defn- row-widget
  "A row's box: the name label, then the dim label, both plain text."
  [{:keys [kind private?] :as row}]
  (let [box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 8)
        name-l (g/gtk-label-new (row-label row))
        meta-l (g/gtk-label-new (row-meta row))]
    (g/gtk-label-set-xalign name-l 0.0)
    (g/gtk-label-set-xalign meta-l 0.0)
    (g/gtk-widget-set-hexpand meta-l 1)
    (g/gtk-label-set-ellipsize meta-l g/PANGO-ELLIPSIZE-END)
    (g/gtk-widget-add-css-class meta-l "dim-label")
    (when (or private? (#{:group :more} kind)) (g/gtk-widget-add-css-class name-l "dim-label"))
    (g/gtk-box-append box name-l)
    (g/gtk-box-append box meta-l)
    box))

(defn selected-row
  "The selected row's data, or nil."
  [pane]
  (let [r (g/gtk-list-box-get-selected-row (:list pane))]
    (when-not (ffi/null? r) (get @(:rows pane) (g/gtk-list-box-row-get-index r)))))

(defn select!
  "Selects the row at index i (nil: none)."
  [pane i]
  (when i
    (let [r (g/gtk-list-box-get-row-at-index (:list pane) i)]
      (when-not (ffi/null? r) (g/gtk-list-box-select-row (:list pane) r)))))

(defn show!
  "Rebuilds the list from view {:rows :crumb :footer :selected}: :crumb
  nil hides the crumb line, :selected is the index to select."
  [pane {:keys [rows crumb footer selected]}]
  (let [lb (:list pane)]
    (reset! (:showing? pane) true)
    (try
      (reset! (:rows pane) (vec rows))
      (g/gtk-list-box-remove-all lb)
      (doseq [r rows] (g/gtk-list-box-append lb (row-widget r)))
      (select! pane selected)
      (finally (reset! (:showing? pane) false))))
  (g/gtk-label-set-text (:crumb pane) (or crumb ""))
  (g/gtk-widget-set-visible (:crumb pane) (if crumb 1 0))
  (report! pane footer))

(defn set-filter-text!
  "Puts text in the filter entry without reporting it as typed."
  [pane text]
  (when (not= text (g/editable-text (:filter pane)))
    (reset! (:showing? pane) true)
    (try (g/gtk-editable-set-text (:filter pane) text)
         (finally (reset! (:showing? pane) false)))))

(defn focus-list! [pane] (g/gtk-widget-grab-focus (:list pane)))

(defn row-texts
  "The shown rows' first labels, in order."
  [pane]
  (mapv row-label @(:rows pane)))

(defn crumb-text [pane] (g/label-text (:crumb pane)))

(defn footer-text [pane] (g/label-text (:footer pane)))

(defn- connect2!
  "Connects signal on w to (f a b), a two-pointer void handler."
  [w signal f]
  (g/g-signal-connect-data w signal (callable f [:pointer :pointer] :void) ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect3!
  "Connects signal on w to (f a b c), a three-pointer void handler."
  [w signal f]
  (g/g-signal-connect-data w signal (callable f [:pointer :pointer :pointer] :void) ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-key! [w f]
  (let [kc (g/gtk-event-controller-key-new)]
    (g/g-signal-connect-data kc "key-pressed"
                             (callable (fn [_c keyval _kc state _d] (if (f keyval state) 1 0))
                                       [:pointer :uint :uint :uint :pointer] :int :on-throw 0)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller w kc)))

(defn- on-list-key
  "The list's key handler: true when the key was handled. Activation runs
  on the next main-loop turn, since it rebuilds the rows."
  [pane keyval state]
  (case (list-key keyval state)
    :back (do (on-gui! #(call! pane :on-back)) true)
    :inspect (when-let [row (selected-row pane)] (on-gui! #(call! pane :on-activate row true)) true)
    false))

(defn- first-row
  "The first namespace or var row shown, or nil."
  [pane]
  (first (filter #(#{:ns :var} (:kind %)) @(:rows pane))))

(defn- connect-all!
  "Wires the list, the filter, ⟳ and Close to the handlers."
  [{lb :list :keys [filter refresh close] :as pane}]
  (connect3! lb "row-activated"
            (fn [_b row _d]
              (when-let [r (get @(:rows pane) (g/gtk-list-box-row-get-index row))]
                (on-gui! #(call! pane :on-activate r false)))
              nil))
  (connect3! lb "row-selected"
             (fn [_b _row _d] (when-not @(:showing? pane) (call! pane :on-select (selected-row pane))) nil))
  (connect-key! lb (fn [k s] (on-list-key pane k s)))
  (connect2! filter "search-changed"
             (fn [e _] (when-not @(:showing? pane) (call! pane :on-filter (g/editable-text e))) nil))
  (connect2! filter "activate"
             (fn [_ _] (when-let [r (first-row pane)] (on-gui! #(call! pane :on-activate r false))) nil))
  (connect-key! filter (fn [k _] (when (filter-key k) (focus-list! pane) (select! pane 0) true)))
  (connect2! refresh "clicked" (fn [_ _] (call! pane :on-refresh) nil))
  (connect2! close "clicked" (fn [_ _] (call! pane :on-close) nil)))

(defn- lay-out!
  [{:keys [root top filter refresh crumb scroll footer close] lb :list}]
  (g/gtk-search-entry-set-placeholder-text filter "Filter")
  (g/gtk-widget-set-hexpand filter 1)
  (g/gtk-box-append top filter)
  (g/gtk-box-append top refresh)
  (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
  (g/gtk-scrolled-window-set-child scroll lb)
  (g/gtk-widget-set-vexpand scroll 1)
  (doseq [l [crumb footer]]
    (g/gtk-label-set-xalign l 0.0)
    (g/gtk-label-set-wrap l 1))
  (g/gtk-widget-add-css-class crumb "dim-label")
  (doseq [w [top crumb scroll footer close]] (g/gtk-box-append root w))
  (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1))

(defn make-pane
  "Builds the panel: {:root :filter :refresh :crumb :list :scroll :footer
  :close :rows :handlers :showing?}. :root starts visible; the window
  hides it."
  []
  (let [pane {:root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
              :top (g/gtk-box-new g/ORIENTATION-HORIZONTAL 4)
              :filter (g/gtk-search-entry-new)
              :refresh (g/gtk-button-new-with-label "⟳")
              :crumb (g/gtk-label-new "")
              :list (g/gtk-list-box-new) :scroll (g/gtk-scrolled-window-new)
              :footer (g/gtk-label-new "")
              :close (g/gtk-button-new-with-label "Close")
              :rows (atom []) :handlers (atom {}) :showing? (atom false)}]
    (lay-out! pane)
    (connect-all! pane)
    pane))
