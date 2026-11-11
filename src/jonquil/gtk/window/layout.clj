(ns jonquil.gtk.window.layout
  "The window's frame: the toolbar and its buttons, the panes' outer widgets, the window's column and its key controller. build makes them, place! puts every widget in its place and connect! wires the toolbar and the key controller. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window's activate! unchanged; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [jolt.ffi :as ffi]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.gtk.window.status-bar :as win-status-bar]))

(defn build
  "activate!'s frame widgets, in activate!'s order: the Files toggle, the
  tree's outer paned and the editor and console paned, then the toolbar,
  its four buttons, the Reset on Run check with reset-state, the
  window's column and its key controller. Reads nothing from w. Returns
  w with :files-btn :tree-paned :paned :toolbar :run-btn :stop-btn
  :open-btn :save-btn :reset-chk :reset-state :vbox and :keyctl added."
  [w]
  (let [files-btn (g/gtk-toggle-button-new-with-label "☰ Files")
        tree-paned (g/gtk-paned-new g/ORIENTATION-HORIZONTAL)
        paned (g/gtk-paned-new g/ORIENTATION-VERTICAL)
        toolbar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        run-btn (g/gtk-button-new-with-label "Run (F5)")
        stop-btn (g/gtk-button-new-with-label "Stop (Esc)")
        open-btn (g/gtk-button-new-with-label "Open…")
        save-btn (g/gtk-button-new-with-label "Save…")
        reset-chk (g/gtk-check-button-new-with-label "Reset on Run")
        ;; The tick an attach runtime took down (dialect/reset-toggle).
        reset-state (atom {})
        vbox (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        keyctl (g/gtk-event-controller-key-new)]
    (assoc w :files-btn files-btn :tree-paned tree-paned :paned paned :toolbar toolbar :run-btn run-btn
           :stop-btn stop-btn :open-btn open-btn :save-btn save-btn :reset-chk reset-chk :reset-state reset-state
           :vbox vbox :keyctl keyctl)))

(defn- pack-toolbar!
  "place!'s first step: the Files toggle, the four buttons and Reset on Run (ticked) in the toolbar."
  [{:keys [toolbar files-btn run-btn stop-btn open-btn save-btn reset-chk]}]
  (g/gtk-box-append toolbar files-btn)
  (g/gtk-box-append toolbar run-btn)
  (g/gtk-box-append toolbar stop-btn)
  (g/gtk-box-append toolbar open-btn)
  (g/gtk-box-append toolbar save-btn)
  (g/gtk-box-append toolbar reset-chk)
  (g/gtk-check-button-set-active reset-chk 1))

(defn- pack-editor!
  "place!'s second step: the tabs' notebook over the console in the vertical paned."
  [{:keys [paned notebook cv]}]
  (g/gtk-widget-set-vexpand paned 1)
  ;; A notebook of tabs is the paned's start child; each page is a tab's
  ;; own find bar over its editor (jonquil.gtk.tab). The strip stays
  ;; hidden while there's one tab.
  (g/gtk-notebook-set-show-tabs notebook 0)
  (g/gtk-notebook-set-show-border notebook 0)
  (g/gtk-notebook-set-scrollable notebook 1)
  (g/gtk-widget-set-vexpand notebook 1)
  (g/gtk-paned-set-start-child paned notebook)
  (g/gtk-paned-set-end-child paned (:scroll cv))
  (g/gtk-paned-set-position paned 430))

(defn- pack-right-slot!
  "place!'s third step: the vertical paned beside the right-hand slot in h-paned, each panel in the slot, and the panels and the slot hidden until one opens."
  [{:keys [h-paned paned pane sp slot rp lp hp part-panels*]}]
  ;; The editor/console paned is h-paned's start child; the
  ;; inspector panel is its end child, hidden until the first open
  ;; (show-inspector-panel!), and kept at a fixed width when the window
  ;; resizes (never grows/shrinks with it, never squeezed below its own
  ;; WIDTH-REQUEST).
  (g/gtk-widget-set-vexpand h-paned 1)
  (g/gtk-paned-set-start-child h-paned paned)
  (g/gtk-widget-set-vexpand (:root pane) 1)
  (g/gtk-widget-set-vexpand (:root sp) 1)
  (g/gtk-box-append slot (:root pane))
  (g/gtk-box-append slot (:root sp))
  (g/gtk-widget-set-vexpand (:root rp) 1)
  (g/gtk-box-append slot (:root rp))
  (g/gtk-widget-set-vexpand (:root lp) 1)
  (g/gtk-box-append slot (:root lp))
  (g/gtk-widget-set-vexpand (:root hp) 1)
  (g/gtk-box-append slot (:root hp))
  (doseq [[_ p] @part-panels*]
    (g/gtk-widget-set-vexpand (:root p) 1)
    (g/gtk-box-append slot (:root p)))
  (g/gtk-paned-set-end-child h-paned slot)
  (g/gtk-paned-set-resize-end-child h-paned 0)
  (g/gtk-paned-set-shrink-end-child h-paned 0)
  (g/gtk-widget-set-visible (:root pane) 0)
  (g/gtk-widget-set-visible (:root sp) 0)
  (g/gtk-widget-set-visible (:root rp) 0)
  (g/gtk-widget-set-visible (:root lp) 0)
  (doseq [[_ p] @part-panels*]
    (g/gtk-widget-set-visible (:root p) 0))
  (g/gtk-widget-set-visible (:root hp) 0)
  (g/gtk-widget-set-visible slot 0))

(defn- pack-window!
  "place!'s last step: the toolbar, the tree's paned and the status bar in the window's column, which becomes the window's child at its default size."
  [{:keys [vbox toolbar tree-paned tree-pane h-paned win status-label hint-label pending-label unread-label dialect-label parinfer-label line-col-label update-parinfer-label!]}]
  (g/gtk-box-append vbox toolbar)
  (g/gtk-paned-set-start-child tree-paned (:root tree-pane))
  (g/gtk-paned-set-end-child tree-paned h-paned)
  (g/gtk-paned-set-resize-start-child tree-paned 0)
  (g/gtk-paned-set-shrink-start-child tree-paned 0)
  (g/gtk-widget-set-vexpand tree-paned 1)
  (g/gtk-box-append vbox tree-paned)
  (win-status-bar/place! {:vbox vbox :status-label status-label :hint-label hint-label :pending-label pending-label
                          :unread-label unread-label :dialect-label dialect-label :parinfer-label parinfer-label
                          :line-col-label line-col-label :update-parinfer-label! update-parinfer-label!})
  (g/gtk-window-set-child win vbox)
  (g/gtk-window-set-default-size win 900 700))

(defn place!
  "activate!'s widget placement, in activate!'s order: the toolbar, the
  editor and console paned, the right-hand slot, then the window's
  column with the status bar. m holds the widgets they place, under
  activate!'s local names; each step reads its own keys. Called once per
  window; activate! reads nothing it returns."
  [m]
  (pack-toolbar! m)
  (pack-editor! m)
  (pack-right-slot! m)
  (pack-window! m))

(defn connect!
  "activate!'s toolbar and key controller connects, in activate!'s order:
  Run, Stop, Open and Save's clicked handlers and Reset on Run's toggled
  one, each contained so a throw is said on the status line, then the
  key controller, in the capture phase with window-key-fn, added to the
  window. Called once per window; activate! reads nothing it returns. m
  holds the widgets build made, :win :status-label :ev*, the four
  toolbar fns and :window-key-fn."
  [{:keys [run-btn stop-btn open-btn save-btn reset-chk keyctl win status-label run-fn stop-fn open-fn save-fn ev*
           window-key-fn]}]
  (g/g-signal-connect-data run-btn "clicked"
                           (callable (win-status/contain-click status-label "run" (fn [_] (run-fn))) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/g-signal-connect-data stop-btn "clicked"
                           (callable (win-status/contain-click status-label "stop" (fn [_] (stop-fn))) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/g-signal-connect-data open-btn "clicked"
                           (callable (win-status/contain-click status-label "open" (fn [_] (open-fn))) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/g-signal-connect-data save-btn "clicked"
                           (callable (win-status/contain-click status-label "save" (fn [_] (save-fn))) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/g-signal-connect-data reset-chk "toggled"
                           (callable (win-status/contain-click status-label "reset on run"
                                                               (fn [btn]
                                                                 (ev/set-mode! @ev*
                                                                               (if (zero? (g/gtk-check-button-get-active btn))
                                                                                 :accumulate :reset))))
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
  (g/g-signal-connect-data keyctl "key-pressed"
                           (callable window-key-fn [:pointer :uint :uint :uint :pointer] :int)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-widget-add-controller win keyctl))
