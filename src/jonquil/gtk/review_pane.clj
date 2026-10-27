(ns jonquil.gtk.review-pane
  "The review panel (plugins P9b): an agent's pending proposal shown in the
  right-hand slot the source panel uses. A wrapped summary (jonquil.plugin-
  review's lines) sits over a read-only jonquil.gtk.editor holding the diff
  against live, then a note entry for Reject, the Accept, Reject, Next and
  Close buttons, and a footer for what the last step said.
  jonquil.gtk.window decides where it shows and what the buttons do; this
  namespace owns what it shows."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.plugin-review :as plugin-review]))

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
  "Registers the buttons' handlers, {:on-accept :on-reject :on-next
  :on-close}, each a no-arg fn."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn show!
  "Shows review (a jonquil.plugin-review review), the index-th of count
  pending: the summary, the diff with the caret at its start, an empty
  note and footer, and Next only when there's more than one."
  [pane review index count]
  (g/gtk-label-set-text (:summary pane) (plugin-review/summary-text review index count))
  (editor/set-text! (:editor pane) (:diff review))
  (let [buf (:buf (:editor pane))] (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
  (g/gtk-editable-set-text (:note pane) "")
  (report! pane "")
  (g/gtk-widget-set-sensitive (:next-btn pane) (if (> count 1) 1 0))
  nil)

(defn update-summary!
  "Shows review's summary as the index-th of count, keeping the diff's
  caret, the note and the footer: for a refresh that leaves the same
  proposal shown (review I2)."
  [pane review index count]
  (g/gtk-label-set-text (:summary pane) (plugin-review/summary-text review index count))
  (g/gtk-widget-set-sensitive (:next-btn pane) (if (> count 1) 1 0))
  nil)

(defn note-text [pane] (or (g/editable-text (:note pane)) ""))
(defn set-note! [pane s] (g/gtk-editable-set-text (:note pane) s) nil)
(defn summary-text [pane] (g/label-text (:summary pane)))
(defn diff-text [pane] (editor/editor-text (:editor pane)))
(defn footer-text [pane] (g/label-text (:footer pane)))

(defn make-pane
  "Builds the panel: {:root :summary :editor :note :accept-btn :reject-btn
  :next-btn :close-btn :footer :handlers (atom {})}. :root starts visible;
  the window hides it. The editor is read-only and carries the
  jonquil-code CSS class, so the window's zoom reaches it."
  []
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        summary (g/gtk-label-new "")
        ed (editor/make-editor)
        note (g/gtk-entry-new)
        bar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        accept-btn (g/gtk-button-new-with-label "Accept")
        reject-btn (g/gtk-button-new-with-label "Reject")
        next-btn (g/gtk-button-new-with-label "Next")
        close-btn (g/gtk-button-new-with-label "Close")
        footer (g/gtk-label-new "")
        pane {:root root :summary summary :editor ed :note note :accept-btn accept-btn
              :reject-btn reject-btn :next-btn next-btn :close-btn close-btn :footer footer
              :handlers (atom {})}]
    (editor/set-read-only! ed true)
    (g/gtk-widget-add-css-class (:view ed) "jonquil-code")
    (g/gtk-label-set-xalign summary 0.0)
    (g/gtk-label-set-wrap summary 1)
    (g/gtk-label-set-xalign footer 0.0)
    (g/gtk-label-set-wrap footer 1)
    (g/gtk-entry-set-placeholder-text note "Note for Reject (optional)")
    (g/gtk-widget-set-vexpand (:scroll ed) 1)
    (doseq [b [accept-btn reject-btn next-btn close-btn]] (g/gtk-box-append bar b))
    (g/gtk-box-append root summary)
    (g/gtk-box-append root (:scroll ed))
    (g/gtk-box-append root note)
    (g/gtk-box-append root bar)
    (g/gtk-box-append root footer)
    (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1)
    (doseq [[b k] [[accept-btn :on-accept] [reject-btn :on-reject] [next-btn :on-next] [close-btn :on-close]]]
      (g/g-signal-connect-data b "clicked" (callable (handler-click pane k) [:pointer :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT))
    pane))
