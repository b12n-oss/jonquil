(ns jonquil.gtk.plugin-history-pane
  "A plugin's history: its versions (jonquil.plugin-list
  version rows, newest first) in the right-hand slot the plugin list uses,
  one wrapped label per version, then a reason entry, the Revert, Back and
  Close buttons and a footer for what the last step said.
  jonquil.gtk.window decides where it shows and what the buttons do; this
  namespace owns what it shows."
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
  "Registers the buttons' handlers, {:on-revert :on-back :on-close}, each
  a no-arg fn."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn show!
  "Shows id's versions ({:live-n :rows} or {:error}, from
  jonquil.plugin-list/version-rows) and selects selected-n's row, or the
  first when it's gone or nil. With no rows, the list hides and the error,
  or a sentence saying there are none, shows in its place. The reason and
  the footer are left as they were."
  [pane {:keys [id live-n rows error]} selected-n]
  (let [lb (:list pane)
        i (or (first (keep-indexed (fn [i r] (when (= selected-n (:n r)) i)) rows)) 0)]
    (reset! (:rows pane) (vec rows))
    (g/gtk-label-set-text (:title pane) (str "History of " id ": " (if live-n (str "live v" live-n) "no live version")))
    (g/gtk-list-box-remove-all lb)
    (doseq [{:keys [line]} rows]
      ;; plain text, never markup: a reason may be an agent's
      (let [label (g/gtk-label-new line)]
        (g/gtk-label-set-xalign label 0.0)
        (g/gtk-label-set-wrap label 1)
        (g/gtk-widget-set-halign label g/ALIGN-START)
        (g/gtk-list-box-append lb label)))
    (g/gtk-label-set-text (:empty pane) (or error (str "plugin " id " has no versions yet.")))
    (g/gtk-widget-set-visible (:empty pane) (if (seq rows) 0 1))
    (g/gtk-widget-set-visible (:scroll pane) (if (seq rows) 1 0))
    (when (seq rows)
      (g/gtk-list-box-select-row lb (g/gtk-list-box-get-row-at-index lb i)))
    nil))

(defn selected-row
  "The version row selected, or nil."
  [pane]
  (let [r (g/gtk-list-box-get-selected-row (:list pane))]
    (when-not (ffi/null? r)
      (get @(:rows pane) (g/gtk-list-box-row-get-index r)))))

(defn select-n!
  "Selects version n's row; false when there's none."
  [pane n]
  (if-let [i (first (keep-indexed (fn [i r] (when (= n (:n r)) i)) @(:rows pane)))]
    (do (g/gtk-list-box-select-row (:list pane) (g/gtk-list-box-get-row-at-index (:list pane) i)) true)
    false))

(defn row-texts
  "The shown rows' lines, in order."
  [pane]
  (mapv :line @(:rows pane)))

(defn title-text [pane] (g/label-text (:title pane)))

(defn empty-text [pane]
  (when (pos? (g/gtk-widget-get-visible (:empty pane))) (g/label-text (:empty pane))))

(defn reason-text [pane] (or (g/editable-text (:reason pane)) ""))
(defn set-reason! [pane s] (g/gtk-editable-set-text (:reason pane) s) nil)
(defn footer-text [pane] (g/label-text (:footer pane)))

(defn- lay-out!
  "Sets up the list, the labels and the reason field, and packs the widgets
  into the root, the buttons into bar first."
  [{:keys [root title scroll empty reason footer revert-btn back-btn close-btn] lb :list} bar]
  (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
  (g/gtk-scrolled-window-set-child scroll lb)
  (g/gtk-widget-set-vexpand scroll 1)
  (doseq [l [title empty footer]]
    (g/gtk-label-set-xalign l 0.0)
    (g/gtk-label-set-wrap l 1))
  (g/gtk-widget-set-visible empty 0)
  (g/gtk-entry-set-placeholder-text reason "Reason for Revert (optional)")
  (doseq [b [revert-btn back-btn close-btn]] (g/gtk-box-append bar b))
  (doseq [w [title scroll empty reason bar footer]] (g/gtk-box-append root w))
  (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1))

(defn- connect-buttons!
  "Connects each button's clicked signal to its handler."
  [{:keys [revert-btn back-btn close-btn] :as pane}]
  (doseq [[b k] [[revert-btn :on-revert] [back-btn :on-back] [close-btn :on-close]]]
    (g/g-signal-connect-data b "clicked" (callable (handler-click pane k) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)))

(defn make-pane
  "Builds the panel: {:root :title :list :scroll :empty :reason :revert-btn
  :back-btn :close-btn :footer :rows (atom []) :handlers (atom {})}. :root
  starts visible; the window hides it."
  []
  (let [bar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        pane {:root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
              :title (g/gtk-label-new "History")
              :list (g/gtk-list-box-new) :scroll (g/gtk-scrolled-window-new)
              :empty (g/gtk-label-new "") :reason (g/gtk-entry-new)
              :revert-btn (g/gtk-button-new-with-label "Revert")
              :back-btn (g/gtk-button-new-with-label "Back")
              :close-btn (g/gtk-button-new-with-label "Close")
              :footer (g/gtk-label-new "")
              :rows (atom []) :handlers (atom {})}]
    (lay-out! pane bar)
    (connect-buttons! pane)
    pane))
