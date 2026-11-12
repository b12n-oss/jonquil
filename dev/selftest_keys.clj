(ns selftest-keys
  "Counts real key presses that reach a self-test window. The rows call
  Jonquil's handlers directly and never send key events, so any press seen
  here came from a person typing while the window had focus."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.ffi :as g]))

(defonce ^:private presses (atom 0))

(defn watch!
  "Adds a capture-phase key controller to win (GTK thread) that counts
  presses and lets them propagate untouched."
  [win]
  (let [ctl (g/gtk-event-controller-key-new)]
    (g/g-signal-connect-data ctl "key-pressed"
                             (cb/callable (fn [_ctl _keyval _keycode _state _data]
                                            (try (swap! presses inc)
                                                 (catch :default _ nil))
                                            0)
                                          [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-event-controller-set-propagation-phase ctl g/GTK-PHASE-CAPTURE)
    (g/gtk-widget-add-controller win ctl)
    nil))

(defn warning-line
  "The warning text when a press was counted, else nil."
  []
  (let [n @presses]
    (when (pos? n)
      (str "WARNING " n " real key press(es) reached the self-test window during this run "
           "(someone typed while it had focus); failures above may come from that, not from Jonquil"))))

(defn print-warning!
  "Prints the warning line, if any. Call right before the SUMMARY line."
  []
  (when-let [line (warning-line)]
    (println line)))
