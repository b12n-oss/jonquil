(ns jonquil.gtk.plugin-list-pane
  "The plugin list (plugins P11): every plugin's row (jonquil.plugin-list)
  in the right-hand slot the source panel uses, one wrapped label per row
  in a list, a disabled plugin's dimmed, then the Enable, Disable, Open,
  Review Proposals and Close buttons and a footer for what the last step
  said. jonquil.gtk.window decides where it shows and what the buttons do;
  this namespace owns what it shows."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]))

(defn report!
  "Puts msg on the panel's footer."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) msg)
  nil)

(defn- handler-click
  "A clicked handler running the registered handler k, reporting a throw
  on the footer."
  [pane k]
  (fn [_button _data]
    (try (when-let [f (get @(:handlers pane) k)] (f))
         (catch :default e (report! pane (str "; " (ex-message e)))))
    nil))

(defn set-handlers!
  "Registers the buttons' handlers, {:on-enable :on-disable :on-open
  :on-review :on-close}, each a no-arg fn."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn show!
  "Shows rows (jonquil.plugin-list rows) and selects selected-id's row, or
  the first when it's gone or nil. With no rows, the list hides and
  empty-text shows in its place. The footer is left as it was."
  [pane rows selected-id empty-text]
  (let [lb (:list pane)
        i (or (first (keep-indexed (fn [i r] (when (= selected-id (:id r)) i)) rows)) 0)]
    (reset! (:rows pane) (vec rows))
    (g/gtk-list-box-remove-all lb)
    (doseq [{:keys [line disabled?]} rows]
      ;; plain text, never markup: a row carries a plugin's own doc
      (let [label (g/gtk-label-new line)]
        (g/gtk-label-set-xalign label 0.0)
        (g/gtk-label-set-wrap label 1)
        (g/gtk-widget-set-halign label g/ALIGN-START)
        (when disabled? (g/gtk-widget-add-css-class label "dim-label"))
        (g/gtk-list-box-append lb label)))
    (g/gtk-label-set-text (:empty pane) (or empty-text ""))
    (g/gtk-widget-set-visible (:empty pane) (if (seq rows) 0 1))
    (g/gtk-widget-set-visible (:scroll pane) (if (seq rows) 1 0))
    (when (seq rows)
      (g/gtk-list-box-select-row lb (g/gtk-list-box-get-row-at-index lb i)))
    nil))

(defn selected-row
  "The row whose line is selected, or nil."
  [pane]
  (let [r (g/gtk-list-box-get-selected-row (:list pane))]
    (when-not (ffi/null? r)
      (get @(:rows pane) (g/gtk-list-box-row-get-index r)))))

(defn select-id!
  "Selects id's row; false when there's none."
  [pane id]
  (if-let [i (first (keep-indexed (fn [i r] (when (= id (:id r)) i)) @(:rows pane)))]
    (do (g/gtk-list-box-select-row (:list pane) (g/gtk-list-box-get-row-at-index (:list pane) i)) true)
    false))

(defn row-texts
  "The shown rows' lines, in order."
  [pane]
  (mapv :line @(:rows pane)))

(defn empty-text [pane]
  (when (pos? (g/gtk-widget-get-visible (:empty pane))) (g/label-text (:empty pane))))

(defn footer-text [pane] (g/label-text (:footer pane)))

(defn make-pane
  "Builds the panel: {:root :list :scroll :empty :enable-btn :disable-btn
  :open-btn :review-btn :close-btn :footer :rows (atom []) :handlers (atom
  {})}. :root starts visible; the window hides it."
  []
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        title (g/gtk-label-new "Plugins")
        lb (g/gtk-list-box-new)
        scroll (g/gtk-scrolled-window-new)
        empty (g/gtk-label-new "")
        bar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        enable-btn (g/gtk-button-new-with-label "Enable")
        disable-btn (g/gtk-button-new-with-label "Disable")
        open-btn (g/gtk-button-new-with-label "Open")
        review-btn (g/gtk-button-new-with-label "Review Proposals")
        close-btn (g/gtk-button-new-with-label "Close")
        footer (g/gtk-label-new "")
        pane {:root root :list lb :scroll scroll :empty empty :enable-btn enable-btn
              :disable-btn disable-btn :open-btn open-btn :review-btn review-btn
              :close-btn close-btn :footer footer :rows (atom []) :handlers (atom {})}]
    (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
    (g/gtk-scrolled-window-set-child scroll lb)
    (g/gtk-widget-set-vexpand scroll 1)
    (doseq [l [title empty footer]]
      (g/gtk-label-set-xalign l 0.0)
      (g/gtk-label-set-wrap l 1))
    (g/gtk-widget-set-visible empty 0)
    (doseq [b [enable-btn disable-btn open-btn review-btn close-btn]] (g/gtk-box-append bar b))
    (doseq [w [title scroll empty bar footer]] (g/gtk-box-append root w))
    (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1)
    (doseq [[b k] [[enable-btn :on-enable] [disable-btn :on-disable] [open-btn :on-open]
                   [review-btn :on-review] [close-btn :on-close]]]
      (g/g-signal-connect-data b "clicked" (callable (handler-click pane k) [:pointer :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT))
    pane))
