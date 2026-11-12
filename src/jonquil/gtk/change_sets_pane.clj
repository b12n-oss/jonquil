(ns jonquil.gtk.change-sets-pane
  "The Change Sets panel in the right-hand slot (LM3): a summary line over
  a read-only jonquil.gtk.editor holding the set's forms as diffs (the
  review panel's way, jonquil.change-sets/set-text), a name entry and Save
  for a set not yet kept, then Apply to REPL, Revert in Tabs, Delete and
  Close, and a footer for what the last step said. The part decides what
  the buttons do; this namespace owns what the panel shows."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]))

(defn report!
  "Puts msg on the panel's footer."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) msg)
  nil)

(defn set-handlers!
  "Registers the buttons' handlers, {:on-save (fn [name]) :on-apply
  :on-revert :on-delete :on-close}, the rest no-arg fns."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn name-text [pane] (or (g/editable-text (:name-entry pane)) ""))

(defn- press!
  "Runs the handler of button k (:save :apply :revert :delete :close), a
  throw reported on the footer. Save's handler takes the name typed."
  [pane k]
  (try (when-let [f (get @(:handlers pane) (keyword (str "on-" (name k))))]
         (if (= k :save) (f (name-text pane)) (f)))
       (catch :default e (report! pane (str "; " (ex-message e)))))
  nil)

(defn set-name! [pane s] (g/gtk-editable-set-text (:name-entry pane) s) nil)
(defn summary-text [pane] (g/label-text (:summary pane)))
(defn diff-text [pane] (editor/editor-text (:editor pane)))
(defn footer-text [pane] (g/label-text (:footer pane)))

(defn click!
  "Presses the button k (:save :apply :revert :delete :close) as a click
  would, for tests: the same handler, none when the button is insensitive."
  [pane k]
  (when (pos? (g/gtk-widget-get-sensitive (get pane (keyword (str (name k) "-btn")))))
    (press! pane k)))

(defn show!
  "Shows text under summary with the caret at its start, an empty footer
  and the buttons for draft? (a set not yet kept: a name and Save, nothing
  else to press but Close) or for a kept set (all but Save). The name entry
  starts as default-name."
  [pane {:keys [summary text draft? default-name]}]
  (g/gtk-label-set-text (:summary pane) summary)
  (editor/set-text! (:editor pane) text)
  (let [buf (:buf (:editor pane))] (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
  (set-name! pane (or default-name ""))
  (report! pane "")
  (doseq [w [(:name-entry pane) (:save-btn pane)]] (g/gtk-widget-set-visible w (if draft? 1 0)))
  (doseq [k [:apply-btn :revert-btn :delete-btn]] (g/gtk-widget-set-visible (get pane k) (if draft? 0 1)))
  nil)

(defn- button! [pane label k]
  (let [b (g/gtk-button-new-with-label label)]
    (g/g-signal-connect-data b "clicked"
                             (callable (fn [_b _d] (press! pane k) nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    b))

(defn- lay-out!
  [{:keys [root summary name-entry footer] ed :editor :as pane} bar]
  (editor/set-read-only! ed true)
  (g/gtk-widget-add-css-class (:view ed) "jonquil-code")
  (g/gtk-label-set-xalign summary 0.0)
  (g/gtk-label-set-wrap summary 1)
  (g/gtk-label-set-xalign footer 0.0)
  (g/gtk-label-set-wrap footer 1)
  (g/gtk-entry-set-placeholder-text name-entry "Name for the change set")
  (g/gtk-widget-set-vexpand (:scroll ed) 1)
  (doseq [k [:save-btn :apply-btn :revert-btn :delete-btn :close-btn]] (g/gtk-box-append bar (get pane k)))
  (g/gtk-box-append root summary)
  (g/gtk-box-append root (:scroll ed))
  (g/gtk-box-append root name-entry)
  (g/gtk-box-append root bar)
  (g/gtk-box-append root footer)
  (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1))

(defn make-pane
  "Builds the panel: {:root :summary :editor :name-entry :save-btn
  :apply-btn :revert-btn :delete-btn :close-btn :footer :handlers (atom {})}.
  :root starts visible; the window hides it."
  []
  (let [bar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        pane {:root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
              :summary (g/gtk-label-new "")
              :editor (editor/make-editor)
              :name-entry (g/gtk-entry-new)
              :footer (g/gtk-label-new "")
              :handlers (atom {})}
        pane (assoc pane
                    :save-btn (button! pane "Save Set" :save)
                    :apply-btn (button! pane "Apply to REPL" :apply)
                    :revert-btn (button! pane "Revert in Tabs" :revert)
                    :delete-btn (button! pane "Delete" :delete)
                    :close-btn (button! pane "Close" :close))]
    (lay-out! pane bar)
    pane))
