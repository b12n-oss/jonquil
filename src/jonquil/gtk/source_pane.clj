(ns jonquil.gtk.source-pane
  "The source panel: a definition outside the buffer, shown read-only on
  the right where the inspector sits. A header names the file (and its
  jar), a read-only jonquil.gtk.editor shows the text with the caret on
  the definition's line, buttons offer Open in window and Close, and a
  footer holds key hints, notes and errors. The panel keeps its own
  history, so F12 inside it can follow definitions and Cmd/Ctrl+[ can come
  back. jonquil.gtk.window decides where it shows and owns its keys; this
  namespace owns what it shows."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.source :as source]))

(def WIDTH-REQUEST
  ;; jonquil.gtk.inspector-pane's own WIDTH-REQUEST, so the right-hand slot
  ;; keeps its width when one panel replaces the other.
  360)

(def hint-text "F12 follow · Cmd/Ctrl+[ back · Esc close")

(defn read-only-reason
  "Why loc can't be opened for editing: Open in window's tooltip, and the
  footer's answer to Cmd/Ctrl+Shift+O."
  [loc]
  (if (:jar loc)
    "A definition inside a jar opens read-only."
    "This file is outside the project, so it opens read-only."))

(defn report!
  "Puts msg on the panel's footer."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) msg)
  nil)

(defn current
  "{:loc :caret} for what the panel shows now, or nil before anything loaded."
  [pane]
  (when-let [loc (:loc @(:state pane))]
    {:loc loc :caret (g/cursor-offset (:buf (:editor pane)))}))

(defn- place-caret!
  "Puts the panel editor's caret at offset and scrolls it about a third of
  the way down. scroll_to_mark, not scroll_to_iter: the text was usually
  just loaded, and GTK computes line heights later, which scroll_to_mark
  waits for."
  [pane offset]
  (let [{:keys [buf view]} (:editor pane)]
    (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
    (g/gtk-text-view-scroll-to-mark view (g/gtk-text-buffer-get-insert buf) 0.0 1 0.0 0.3)))

(defn- show-header! [pane loc]
  (g/gtk-label-set-text (:header pane) (str (:label loc) (when-not (:editable? loc) "  · read-only")))
  (g/gtk-widget-set-sensitive (:open-btn pane) (if (:editable? loc) 1 0))
  (g/gtk-widget-set-tooltip-text (:open-btn pane)
                                 (if (:editable? loc)
                                   "Open this file in the window for editing (Cmd/Ctrl+Shift+O)"
                                   (read-only-reason loc))))

(defn- load!
  "Shows loc with the caret on :line (clamped) or at :caret. Re-reads only
  when loc isn't the file already showing. Returns nil, or {:error msg}
  with the footer saying so and the panel keeping what it had."
  [pane loc {:keys [line caret]}]
  (let [ed (:editor pane)
        same? (source/same-file? loc (:loc @(:state pane)))
        read (when-not same? (source/read-text loc))]
    (if-let [err (:error read)]
      (do (report! pane err) {:error err})
      (let [text (if same? (editor/editor-text ed) (:text read))
            {cl :line moved? :moved?} (when line (source/clamp-line text line))
            offset (if line (source/line-offset text cl) (min (or caret 0) (count text)))]
        (when-not same? (editor/set-text! ed text))
        (swap! (:state pane) assoc :loc loc)
        (show-header! pane loc)
        (place-caret! pane offset)
        (when-not same? (editor/paint-async! ed))
        (report! pane (if moved? source/moved-note hint-text))
        nil))))

(defn open-location!
  "Opens loc on its :line, pushing what showed before onto the panel's
  history once the load succeeded. Returns nil, or {:error msg} with the
  history unchanged."
  [pane loc]
  (let [before (current pane)
        r (load! pane loc {:line (:line loc)})]
    (when (and (nil? r) before)
      (swap! (:state pane) update :history source/push before))
    r))

(defn move-to-offset!
  "A definition inside the panel's own file: pushes where the caret was,
  then moves it to offset."
  [pane offset]
  (when-let [before (current pane)]
    (swap! (:state pane) update :history source/push before)
    (place-caret! pane offset)
    (report! pane hint-text))
  nil)

(defn back!
  "Steps back through the history: shows the previous entry's file with
  its caret. true when it moved, false when the history is empty. An entry
  whose file can't be read any more is dropped, with the footer saying so."
  [pane]
  (if-let [[entry rest-history] (source/back (:history @(:state pane)))]
    (do (swap! (:state pane) assoc :history rest-history)
        (load! pane (:loc entry) {:caret (:caret entry)})
        true)
    false))

(defn focused?
  "True when keyboard focus is anywhere inside the panel (the root's focus
  widget is a descendant of :root, the find bar's own test)."
  [pane win]
  (let [f (g/gtk-root-get-focus win)]
    (and (not (ffi/null? f)) (not (zero? (g/gtk-widget-is-ancestor f (:root pane)))))))

(defn focus! [pane] (editor/focus! (:editor pane)))

(defn apply-theme! [pane t] (editor/apply-theme! (:editor pane) t))

(defn set-profile!
  "Paints the panel with a jonquil.dialect profile's sets (the window's
  current one), from the next load on."
  [pane profile]
  (reset! (:profile (:editor pane)) profile)
  nil)

(defn set-handlers!
  "Registers the window's {:on-open f :on-close g}, called by the Open in
  window and Close buttons. A single slot, set once the window has built
  what they need."
  [pane handlers]
  (reset! (:handlers pane) handlers)
  nil)

(defn- handler-click
  "A clicked handler running the registered handler k, reporting a throw
  on the footer."
  [pane k]
  (fn [_button _data]
    (try (when-let [f (get @(:handlers pane) k)] (f))
         (catch :default e (report! pane (str "; " (ex-message e)))))
    nil))

(defn make-pane
  "Builds the panel. Returns {:root :header :editor :footer :open-btn
  :close-btn :state (atom {:loc nil :history []}) :handlers (atom {})}.
  :root starts visible; the window hides it. The editor is read-only and
  carries the jonquil-code CSS class, so the window's zoom reaches it."
  []
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        header (g/gtk-label-new "")
        ed (editor/make-editor)
        bar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        open-btn (g/gtk-button-new-with-label "Open in window")
        close-btn (g/gtk-button-new-with-label "Close")
        footer (g/gtk-label-new hint-text)
        pane {:root root :header header :editor ed :footer footer
              :open-btn open-btn :close-btn close-btn
              :state (atom {:loc nil :history []})
              :handlers (atom {})}]
    (editor/set-read-only! ed true)
    (g/gtk-widget-add-css-class (:view ed) "jonquil-code")
    (g/gtk-label-set-xalign header 0.0)
    (g/gtk-label-set-ellipsize header g/PANGO-ELLIPSIZE-START)
    (g/gtk-label-set-xalign footer 0.0)
    (g/gtk-widget-set-vexpand (:scroll ed) 1)
    (g/gtk-widget-set-sensitive open-btn 0)
    (g/gtk-box-append bar open-btn)
    (g/gtk-box-append bar close-btn)
    (g/gtk-box-append root header)
    (g/gtk-box-append root (:scroll ed))
    (g/gtk-box-append root bar)
    (g/gtk-box-append root footer)
    (g/gtk-widget-set-size-request root WIDTH-REQUEST -1)
    (g/g-signal-connect-data open-btn "clicked" (callable (handler-click pane :on-open) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data close-btn "clicked" (callable (handler-click pane :on-close) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    pane))
