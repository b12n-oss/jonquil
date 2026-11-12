(ns jonquil.gtk.docs-pane
  "The docs panel in the right-hand slot (LM6): one doc page at a time, Back
  and Forward, Bookmark and Close in a bar, the page's name and place in the
  history, the page's text in a read-only view (a click on a symbol in it
  follows that symbol), a ClojureDocs example's Run and Copy buttons below
  it, and a footer. jonquil.gtk.parts.docs decides what a button, a click
  or a key does; this namespace owns the widgets and what they show."
  (:require [jolt.ffi :as ffi]
            [jonquil.docs-panel :as dp]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.source-pane :as source-pane]))

(def hint-text "Click a symbol to follow it · [ back · ] forward")

(def ^:private KEY-BRACKETLEFT 0x5b)
(def ^:private KEY-BRACKETRIGHT 0x5d)

(defn report!
  "Puts msg on the panel's footer."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) msg)
  nil)

(defn set-handlers!
  "Registers {:on-back :on-forward :on-bookmark :on-close (fn []), :on-link
  (fn [symbol]), :on-run :on-copy (fn [code])}."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn- handler
  "Calls the handler k with args, when one is registered; a throw goes to the
  footer, never into GTK."
  [pane k & args]
  (try (when-let [f (get @(:handlers pane) k)] (apply f args))
       (catch :default e (report! pane (str "; " (ex-message e)))))
  nil)

(defn- clear-box!
  "Removes every child of box."
  [box]
  (loop []
    (let [c (g/gtk-widget-get-first-child box)]
      (when-not (ffi/null? c)
        (g/gtk-box-remove box c)
        (recur)))))

(defn set-nav!
  "Sets the bar from {:history h :bookmarked? b}: Back and Forward are
  sensitive when the history has a page that way, the bookmark button reads
  Bookmarked when the shown page is, and the place in the history is on the
  title line."
  [pane {:keys [history bookmarked?]}]
  (g/gtk-widget-set-sensitive (:back-btn pane) (if (dp/back history) 1 0))
  (g/gtk-widget-set-sensitive (:forward-btn pane) (if (dp/forward history) 1 0))
  (g/gtk-button-set-label (:bookmark-btn pane) (if bookmarked? "Bookmarked" "Bookmark"))
  nil)

(defn show!
  "Shows a page: {:title s :text s :examples [example ...] :history h
  :bookmarked? b}, an example being jonquil.doc-examples/split's. The text
  goes in the read-only view, the examples below it with their Run and Copy
  buttons (their clicks call the :on-run and :on-copy handlers), the title and
  the bar are set, and the page is scrolled to its top. Returns nil."
  [pane {:keys [title text examples history] :as page}]
  (let [box (:examples-box pane)
        handlers {:on-run (fn [code] (handler pane :on-run code))
                  :on-copy (fn [code] (handler pane :on-copy code))}]
    (g/gtk-label-set-text (:title pane)
                          (str title (when-let [p (some-> history dp/position-text not-empty)] (str "  (" p ")"))))
    (g/gtk-text-buffer-set-text (:buf pane) (or text "") -1)
    (clear-box! box)
    (reset! (:examples pane)
            (mapv #(help-popover/example-widgets box % handlers) examples))
    (set-nav! pane page)
    (g/gtk-adjustment-set-value (g/gtk-scrolled-window-get-vadjustment (:scroll pane)) 0.0)
    (report! pane hint-text)))

(defn view-text
  "The text the view shows, for rows."
  [pane]
  (g/buffer-text (:buf pane)))

(defn title-text
  "The title line's text, for rows."
  [pane]
  (g/label-text (:title pane)))

(defn bookmark-label
  "The bookmark button's label (Bookmark or Bookmarked), for rows."
  [pane]
  (ffi/ptr->string (g/gtk-button-get-label (:bookmark-btn pane))))

(defn example-buttons
  "[{:n :code :run :copy}] for the examples shown, for rows."
  [pane]
  @(:examples pane))

(defn symbol-at-offset
  "The symbol in the view's text at character offset, nil for none."
  [pane offset]
  (dp/symbol-at (view-text pane) offset))

(defn follow-offset!
  "A click at character offset in the view: calls :on-link with the symbol
  there, when there is one and the view has no selection (a drag that selects
  text isn't a click on a symbol). Returns the symbol followed, else nil."
  [pane offset]
  (let [[s e] (g/selection-offsets (:buf pane))]
    (when (= s e)
      (when-let [sym (symbol-at-offset pane offset)]
        (handler pane :on-link sym)
        sym))))

(defn- click-offset
  "The character offset under widget coordinates wx, wy of the view."
  [view wx wy]
  (let [coords (ffi/alloc 8)
        it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-view-window-to-buffer-coords view g/TEXT-WINDOW-WIDGET (int wx) (int wy) coords (+ coords 4))
      (g/gtk-text-view-get-iter-at-location view it (ffi/read coords :int 0) (ffi/read coords :int 4))
      (g/gtk-text-iter-get-offset it)
      (finally (ffi/free coords) (ffi/free it)))))

(defn handle-key!
  "The panel's own keys: [ goes back and ] forward. Returns 1 when handled,
  0 otherwise (a row can call it with a synthetic keyval)."
  [pane keyval]
  (cond
    (= keyval KEY-BRACKETLEFT) (do (handler pane :on-back) 1)
    (= keyval KEY-BRACKETRIGHT) (do (handler pane :on-forward) 1)
    :else 0))

(defn focus!
  "Gives the view the keyboard focus."
  [pane]
  (g/gtk-widget-grab-focus (:view pane))
  nil)

(defn- connect-clicked!
  "Connects btn's clicked signal to the handler k."
  [pane btn k]
  (g/g-signal-connect-data btn "clicked"
                           (callable (fn [_button _data] (handler pane k) nil) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-input!
  "Connects the bracket keys on the panel's root and the click on the view."
  [{:keys [root view] :as pane}]
  (let [keyctl (g/gtk-event-controller-key-new)
        click (g/gtk-gesture-click-new)]
    (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-BUBBLE)
    (g/g-signal-connect-data keyctl "key-pressed"
                             (callable (fn [_ctl keyval _keycode _state _data] (handle-key! pane keyval))
                                       [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller root keyctl)
    ;; released, not pressed: a drag that selects text ends in a release too,
    ;; and follow-offset! tells the two apart by the selection
    (g/g-signal-connect-data click "released"
                             (callable (fn [_gesture n-press x y _data]
                                         (try (when (= 1 n-press) (follow-offset! pane (click-offset view x y)))
                                              (catch :default e (report! pane (str "; " (ex-message e)))))
                                         nil)
                                       [:pointer :int :double :double :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller view click)))

(defn- lay-out!
  "Sets up the widgets and packs them into the root."
  [{:keys [root bar title scroll content view examples-box footer back-btn forward-btn bookmark-btn close-btn]}]
  (g/gtk-text-view-set-editable view 0)
  (g/gtk-text-view-set-monospace view 1)
  (g/gtk-text-view-set-cursor-visible view 0)
  (g/gtk-text-view-set-wrap-mode view g/GTK-WRAP-WORD-CHAR)
  (g/gtk-widget-add-css-class view "jonquil-code")
  (g/gtk-box-append content view)
  (g/gtk-box-append content examples-box)
  (g/gtk-scrolled-window-set-child scroll content)
  (g/gtk-widget-set-vexpand scroll 1)
  (doseq [l [title footer]]
    (g/gtk-label-set-xalign l 0.0)
    (g/gtk-label-set-wrap l 1))
  (doseq [b [back-btn forward-btn bookmark-btn close-btn]] (g/gtk-box-append bar b))
  (doseq [w [bar title scroll footer]] (g/gtk-box-append root w))
  (g/gtk-widget-set-sensitive back-btn 0)
  (g/gtk-widget-set-sensitive forward-btn 0)
  (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1))

(defn make-pane
  "Builds the panel: {:root :bar :title :scroll :content :view :buf
  :examples-box :footer :back-btn :forward-btn :bookmark-btn :close-btn
  :examples (atom) :handlers (atom)}. :root starts visible; the window hides
  it."
  []
  (let [view (g/gtk-text-view-new)
        pane {:root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
              :bar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
              :title (g/gtk-label-new "Doc pages")
              :scroll (g/gtk-scrolled-window-new)
              :content (g/gtk-box-new g/ORIENTATION-VERTICAL 6)
              :view view :buf (g/gtk-text-view-get-buffer view)
              :examples-box (g/gtk-box-new g/ORIENTATION-VERTICAL 6)
              :footer (g/gtk-label-new "")
              :back-btn (g/gtk-button-new-with-label "Back")
              :forward-btn (g/gtk-button-new-with-label "Forward")
              :bookmark-btn (g/gtk-button-new-with-label "Bookmark")
              :close-btn (g/gtk-button-new-with-label "Close")
              :examples (atom []) :handlers (atom {})}]
    (lay-out! pane)
    (connect-clicked! pane (:back-btn pane) :on-back)
    (connect-clicked! pane (:forward-btn pane) :on-forward)
    (connect-clicked! pane (:bookmark-btn pane) :on-bookmark)
    (connect-clicked! pane (:close-btn pane) :on-close)
    (connect-input! pane)
    (report! pane "Show Doc in Panel (Cmd/Ctrl+Alt+9) puts a symbol's doc here.")
    pane))
