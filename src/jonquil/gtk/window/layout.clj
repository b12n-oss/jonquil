(ns jonquil.gtk.window.layout
  "The window's frame: the toolbar and its buttons, the panes' outer widgets, the window's column and its key controller. build makes them and connect! wires the toolbar and the key controller. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window's activate! unchanged; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [jolt.ffi :as ffi]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.status :as win-status]))

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
