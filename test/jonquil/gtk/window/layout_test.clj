(ns jonquil.gtk.window.layout-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.layout :as win-layout]))

(deftest build-makes-the-frame-widgets-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) n (atom 0)
        mk (fn [& args] (let [k (keyword (str "w" (swap! n inc)))] (l (into [k] args)) k))
        in {:x 1}
        w (with-redefs [g/gtk-toggle-button-new-with-label (fn [t] (mk :toggle t))
                        g/gtk-paned-new (fn [o] (mk :paned o))
                        g/gtk-box-new (fn [o s] (mk :box o s))
                        g/gtk-button-new-with-label (fn [t] (mk :button t))
                        g/gtk-check-button-new-with-label (fn [t] (mk :check t))
                        g/gtk-event-controller-key-new (fn [] (mk :keys))]
            (win-layout/build in))]
    (is (= #{:x :files-btn :tree-paned :paned :toolbar :run-btn :stop-btn :open-btn :save-btn :reset-chk
             :reset-state :vbox :keyctl}
           (set (keys w))))
    (is (= [[:w1 :toggle "☰ Files"] [:w2 :paned g/ORIENTATION-HORIZONTAL] [:w3 :paned g/ORIENTATION-VERTICAL]
            [:w4 :box g/ORIENTATION-HORIZONTAL 6] [:w5 :button "Run (F5)"] [:w6 :button "Stop (Esc)"]
            [:w7 :button "Open…"] [:w8 :button "Save…"] [:w9 :check "Reset on Run"]
            [:w10 :box g/ORIENTATION-VERTICAL 0] [:w11 :keys]]
           @log))
    (is (= [:w1 :w2 :w3 :w4 :w5 :w6 :w7 :w8 :w9 :w10 :w11]
           (map w [:files-btn :tree-paned :paned :toolbar :run-btn :stop-btn :open-btn :save-btn :reset-chk :vbox
                   :keyctl])))
    (is (= {} @(:reset-state w)))))

(deftest connect-wires-the-toolbar-and-the-key-controller-test
  (let [log (atom []) l (fn [x] (swap! log conj x))]
    (with-redefs [g/g-signal-connect-data (fn [o s cb & _] (l [:connect o s (some? cb)]) 1)
                  g/gtk-event-controller-set-propagation-phase (fn [o p] (l [:phase o p]))
                  g/gtk-widget-add-controller (fn [w c] (l [:add w c]))]
      (win-layout/connect! {:run-btn ::run :stop-btn ::stop :open-btn ::open :save-btn ::save :reset-chk ::reset
                            :keyctl ::keyctl :win ::win :status-label ::sl :run-fn (fn []) :stop-fn (fn [])
                            :open-fn (fn []) :save-fn (fn []) :ev* (atom nil) :window-key-fn (fn [& _] 0)}))
    (is (= [[:connect ::run "clicked" true] [:connect ::stop "clicked" true] [:connect ::open "clicked" true]
            [:connect ::save "clicked" true] [:connect ::reset "toggled" true]
            [:phase ::keyctl g/GTK-PHASE-CAPTURE] [:connect ::keyctl "key-pressed" true] [:add ::win ::keyctl]]
           @log))))
