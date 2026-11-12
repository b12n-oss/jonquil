(ns jonquil.gtk.trace-pane
  "The Trace panel in the right-hand slot (LM4): the calls of the traced
  functions (jonquil.trace-rt), one row each, indented by depth, a footer,
  and Close. jonquil.gtk.window decides what a row does (Enter inspects
  that call's value)."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]))

(defn row-text
  "An entry as a row: two spaces per depth, the call, then => its result,
  threw and the error, or (running) for a call that hasn't returned."
  [{:keys [depth args ret threw pending]}]
  (str (apply str (repeat (or depth 0) "  ")) args
       (cond threw (str " threw " threw)
             pending " (running)"
             :else (str " => " ret))))

(defn report!
  "Puts msg on the panel's footer."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) msg)
  nil)

(defn set-handlers!
  "Registers {:on-activate (fn [entry]) :on-close (fn [])}."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn show!
  "Shows entries (jonquil.trace-rt/log-since's), oldest first, the newest
  selected."
  [pane entries]
  (let [lb (:list pane)
        rows (mapv row-text entries)]
    (reset! (:entries pane) (vec entries))
    (reset! (:rows pane) rows)
    (g/gtk-list-box-remove-all lb)
    (doseq [r rows]
      ;; plain text, never markup: a row carries the program's own values
      (let [label (g/gtk-label-new r)]
        (g/gtk-label-set-xalign label 0.0)
        (g/gtk-widget-set-halign label g/ALIGN-START)
        (g/gtk-list-box-append lb label)))
    (when (seq rows)
      (g/gtk-list-box-select-row lb (g/gtk-list-box-get-row-at-index lb (dec (count rows)))))
    (report! pane (if (seq rows)
                    "Enter on a call inspects what it returned (its arguments, when it threw)."
                    "No calls yet: trace a function, then call it."))))

(defn row-texts
  "The shown rows, in order."
  [pane]
  @(:rows pane))

(defn activate!
  "Runs what row i does, as Enter on it: :on-activate with its entry."
  [pane i]
  (when-let [e (get @(:entries pane) i)]
    (when-let [f (:on-activate @(:handlers pane))] (f e))))

(defn- lay-out!
  "Sets up the list and the labels and packs the widgets into the root."
  [{:keys [root title scroll close-btn footer] lb :list}]
  (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
  (g/gtk-scrolled-window-set-child scroll lb)
  (g/gtk-widget-set-vexpand scroll 1)
  (doseq [l [title footer]]
    (g/gtk-label-set-xalign l 0.0)
    (g/gtk-label-set-wrap l 1))
  (doseq [w [title scroll close-btn footer]] (g/gtk-box-append root w))
  (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1))

(defn- connect-list-and-close!
  "Connects the list's row-activated and the Close button's clicked to their
  handlers."
  [{:keys [close-btn] lb :list :as pane}]
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
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn make-pane
  "Builds the panel: {:root :title :list :scroll :close-btn :footer :rows
  :entries :handlers}. :root starts visible; the window hides it."
  []
  (let [pane {:root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
              :title (g/gtk-label-new "Calls of traced functions")
              :list (g/gtk-list-box-new) :scroll (g/gtk-scrolled-window-new)
              :close-btn (g/gtk-button-new-with-label "Close")
              :footer (g/gtk-label-new "")
              :rows (atom []) :entries (atom []) :handlers (atom {})}]
    (lay-out! pane)
    (connect-list-and-close! pane)
    pane))
