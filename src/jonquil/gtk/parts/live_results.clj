(ns jonquil.gtk.parts.live-results
  "LM1's live console results as a window part (the commands are
  jonquil.parts.live-results/spec). Moved out of jonquil.gtk.window: the four
  result verbs, the right-click caret move and the click-to-inspect gesture."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.inspect :as inspect]
            [jonquil.results :as results]))

(defn- install
  "The live-results part for one window: answers the four result fns and the
  three gesture fns, and adds the right-press and click gestures to the
  console's view, each callable built once."
  [{:keys [cv ev* set-status! active-tab inspect! ask-lookup! jump-to! source-ctx status-label]}]
  (let [;; Live console results (LM1): a click on a result line inspects
        ;; its value; the verbs act on the result at the console's caret
        ;; (a right-click moves the caret there first)
        result-at-caret (fn [] (let [off (g/cursor-offset (:buf cv))]
                                 [(cview/result-at cv off) (cview/result-text-at cv off)]))
        ;; labelled by its line, since *1 names only the latest result
        inspect-result! (fn [ref text]
                          (inspect! [:start-result {:id (:id ref) :epoch (:epoch ref)
                                                    :label (inspect/root-label (results/copy-text (or text "")))}]))
        no-result "; No result here."
        ;; each verb asks the kind's table (jonquil.results) before acting
        with-result (fn [verb not-offered f]
                      (let [[ref text] (result-at-caret)]
                        (cond (nil? ref) (set-status! no-result)
                              (not (results/offered? verb (:kind ref))) (set-status! not-offered)
                              :else (f ref text))))
        result-inspect-fn (fn [] (with-result :inspect no-result (fn [ref text] (inspect-result! ref text))))
        result-copy-fn (fn [] (with-result :copy no-result
                                (fn [_ text]
                                  (g/gdk-clipboard-set-text (g/gtk-widget-get-clipboard (:view cv)) (results/copy-text text))
                                  (set-status! "; Copied the result."))))
        result-define-fn (fn [] (with-result :define no-result
                                  (fn [ref _]
                                    (let [{:keys [text caret]} (results/define-input ref)]
                                      (cview/set-input! cv text caret)))))
        result-jump-fn (fn [] (with-result :jump "; That result isn't a var."
                                (fn [_ text]
                                  (if-let [sym (results/var-name text)]
                                    (ask-lookup! {:ev* ev* :status-label status-label} sym "" 0 (constantly true) "jump"
                                                 (fn [doc]
                                                   (if doc
                                                     (jump-to! (source-ctx (active-tab)) sym {:kind :external :doc doc})
                                                     (set-status! (str "No definition found for " sym ".")))))
                                    (set-status! "; That result isn't a var.")))))
        ;; GTK's own right-click opens the menu without moving the caret
        ;; (gtk_text_view_click_gesture_pressed: a context-menu press goes
        ;; to do_popup), so this moves it to the click first, in the capture
        ;; phase before GTK's gesture claims the press; a right-click inside
        ;; a selection leaves it, for GTK's own Copy
        console-right-press! (fn [x y]
                               (let [view (:view cv) buf (:buf cv)
                                     out (ffi/alloc 8) it (ffi/alloc g/ITER-BYTES)]
                                 (try
                                   (g/gtk-text-view-window-to-buffer-coords view g/TEXT-WINDOW-WIDGET (int x) (int y) out (+ out 4))
                                   (g/gtk-text-view-get-iter-at-location view it (ffi/read out :int 0) (ffi/read out :int 4))
                                   (let [off (g/gtk-text-iter-get-offset it)
                                         [a b] (g/selection-offsets buf)]
                                     (when-not (and (< a b) (<= a off) (< off b))
                                       (g/with-iters* buf off off (fn [i _] (g/gtk-text-buffer-place-cursor buf i)))))
                                   (finally (ffi/free out) (ffi/free it)))))
        _ (let [rclick (g/gtk-gesture-click-new)]
            (g/gtk-gesture-single-set-button rclick 3)
            (g/gtk-event-controller-set-propagation-phase rclick g/GTK-PHASE-CAPTURE)
            (g/g-signal-connect-data rclick "pressed"
                                     (callable (fn [_gesture _n x y _data] (console-right-press! x y) nil)
                                               [:pointer :int :double :double :pointer] :void)
                                     ffi/null ffi/null g/CONNECT-DEFAULT)
            (g/gtk-widget-add-controller (:view cv) rclick))
        result-click! (fn [offset] (when-let [ref (cview/result-at cv offset)]
                                     (inspect-result! ref (cview/result-text-at cv offset))))
        ;; a single primary click, with no text selected by it, on a result
        ;; line. It waits out GTK's gtk-double-click-time, which on macOS
        ;; follows the system's double-click speed, and a second press
        ;; cancels it, so a double-click that selects a word never opens the
        ;; inspector first.
        click-gen (atom 0)
        result-release! (fn [off]
                          (let [[a b] (g/selection-offsets (:buf cv))
                                ;; the result clicked, resolved now: output
                                ;; trimmed while the click waits moves every span
                                ref (cview/result-at cv off)
                                text (cview/result-text-at cv off)
                                n (swap! click-gen inc)
                                wait (g/double-click-time (g/gtk-settings-get-default))]
                            (when (and ref (= a b))
                              (future
                                (Thread/sleep wait)
                                (on-gui! (fn []
                                           (let [[a2 b2] (g/selection-offsets (:buf cv))]
                                             ;; still the last click, nothing selected
                                             ;; since, and its line still on screen
                                             (when (and (= n @click-gen) (= a2 b2)
                                                        (some #(= ref (:ref %)) @(:results cv)))
                                               (inspect-result! ref text)))))))))
        _ (let [click (g/gtk-gesture-click-new)]
            (g/g-signal-connect-data click "pressed"
                                     (callable (fn [_gesture n-press _x _y _data]
                                                 (when (> n-press 1) (swap! click-gen inc))
                                                 nil)
                                               [:pointer :int :double :double :pointer] :void)
                                     ffi/null ffi/null g/CONNECT-DEFAULT)
            (g/g-signal-connect-data click "released"
                                     (callable (fn [_gesture n-press _x _y _data]
                                                 (when (= 1 n-press)
                                                   (result-release! (g/cursor-offset (:buf cv))))
                                                 nil)
                                               [:pointer :int :double :double :pointer] :void)
                                     ffi/null ffi/null g/CONNECT-DEFAULT)
            (g/gtk-widget-add-controller (:view cv) click))]
    {:fns {:result-inspect-fn result-inspect-fn :result-copy-fn result-copy-fn
           :result-define-fn result-define-fn :result-jump-fn result-jump-fn}
     :ui {:result-click!* result-click! :result-release!* result-release! :console-right-press!* console-right-press!}}))

(def part
  "The live-results part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :live-results}))
