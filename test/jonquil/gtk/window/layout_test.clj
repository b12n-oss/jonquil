(ns jonquil.gtk.window.layout-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.layout :as win-layout]
            [jonquil.gtk.window.status-bar :as win-status-bar]))

(deftest build-makes-the-frame-widgets-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) n (atom 0)
        mk (fn [& args] (let [k (keyword (str "w" (swap! n inc)))] (l (into [k] args)) k))
        in {:x 1}
        mac? (= "Mac OS X" (System/getProperty "os.name"))
        w (with-redefs [g/gtk-toggle-button-new-with-label (fn [t] (mk :toggle t))
                        g/gtk-paned-new (fn [o] (mk :paned o))
                        g/gtk-box-new (fn [o s] (mk :box o s))
                        g/gtk-header-bar-new (fn [] (mk :header))
                        g/gtk-button-new-with-label (fn [t] (mk :button t))
                        g/gtk-label-new (fn [t] (mk :label t))
                        g/gtk-menu-button-new (fn [] (mk :menu-button))
                        g/gtk-check-button-new-with-label (fn [t] (mk :check t))
                        g/gtk-event-controller-key-new (fn [] (mk :keys))]
            (win-layout/build in))
        made (map second @log)]
    (is (= #{:x :files-btn :tree-paned :paned :toolbar :run-btn :stop-btn :open-btn :save-btn :new-tab-btn
             :title-label :subtitle-label :menu-btn :reset-chk :reset-state :vbox :keyctl}
           (set (keys w))))
    ;; the menu button exists only where there is no system menu bar
    (is (= (cond-> [:toggle :paned :paned :header :button :button :button :button :button :label :label]
             (not mac?) (conj :menu-button)
             true (into [:check :box :keys]))
           made))
    (is (= [[:toggle "☰ Files"] [:header] [:button "▶ Run"] [:button "Stop"] [:button "Open…"] [:button "Save…"]
            [:button "+"] [:label "Untitled"] [:label ""] [:check "Reset on Run"]]
           (mapv (fn [k] (vec (rest (some #(when (= (get w k) (first %)) %) @log))))
                 [:files-btn :toolbar :run-btn :stop-btn :open-btn :save-btn :new-tab-btn :title-label
                  :subtitle-label :reset-chk])))
    (is (= mac? (nil? (:menu-btn w))))
    (is (= {} @(:reset-state w)))))

(deftest connect-wires-the-toolbar-and-the-key-controller-test
  (let [log (atom []) l (fn [x] (swap! log conj x))]
    (with-redefs [g/g-signal-connect-data (fn [o s cb & _] (l [:connect o s (some? cb)]) 1)
                  g/gtk-event-controller-set-propagation-phase (fn [o p] (l [:phase o p]))
                  g/gtk-widget-add-controller (fn [w c] (l [:add w c]))]
      (win-layout/connect! {:run-btn ::run :stop-btn ::stop :open-btn ::open :save-btn ::save :new-tab-btn ::new
                            :reset-chk ::reset
                            :keyctl ::keyctl :win ::win :status-label ::sl :run-fn (fn []) :stop-fn (fn [])
                            :open-fn (fn []) :save-fn (fn []) :new-tab-fn (fn []) :ev* (atom nil)
                            :window-key-fn (fn [& _] 0)}))
    (is (= [[:connect ::run "clicked" true] [:connect ::stop "clicked" true] [:connect ::open "clicked" true]
            [:connect ::save "clicked" true] [:connect ::new "clicked" true] [:connect ::reset "toggled" true]
            [:phase ::keyctl g/GTK-PHASE-CAPTURE] [:connect ::keyctl "key-pressed" true] [:add ::win ::keyctl]]
           @log))))

(deftest place-puts-each-widget-in-its-place-test
  (let [log (atom []) l (fn [x] (swap! log conj x))
        m {:toolbar ::toolbar :files-btn ::files :run-btn ::run :stop-btn ::stop :open-btn ::open :save-btn ::save
           :new-tab-btn ::new :title-label ::title :subtitle-label ::subtitle :menu-btn ::menu
           :reset-chk ::reset :paned ::paned :notebook ::notebook :cv {:scroll ::scroll} :h-paned ::h-paned
           :pane {:root ::pane} :sp {:root ::sp} :slot ::slot :rp {:root ::rp} :lp {:root ::lp} :hp {:root ::hp}
           :part-panels* (atom {:a {:root ::a} :b {:root ::b}}) :vbox ::vbox :tree-paned ::tree-paned
           :tree-pane {:root ::tree} :win ::win :status-label ::status :hint-label ::hint :pending-label ::pending
           :unread-label ::unread :dialect-label ::dialect :parinfer-label ::parinfer :line-col-label ::line-col
           :update-parinfer-label! ::update}]
    (with-redefs [g/gtk-box-append (fn [b c] (l [:append b c]))
                  g/gtk-box-new (fn [_ _] ::titles)
                  g/gtk-header-bar-pack-start (fn [h c] (l [:start-of h c]))
                  g/gtk-header-bar-pack-end (fn [h c] (l [:end-of h c]))
                  g/gtk-header-bar-set-title-widget (fn [h c] (l [:title-of h c]))
                  g/gtk-header-bar-set-use-native-controls (fn [h v] (l [:native h v]))
                  g/gtk-window-set-titlebar (fn [w h] (l [:titlebar w h]))
                  g/gtk-menu-button-set-label (fn [_ _] nil)
                  g/gtk-widget-set-tooltip-text (fn [_ _] nil)
                  g/gtk-widget-add-css-class (fn [_ _] nil)
                  g/gtk-label-set-ellipsize (fn [_ _] nil)
                  g/gtk-widget-set-valign (fn [_ _] nil)
                  g/g-object-ref-sink (fn [o] (l [:keep o]) o)
                  win-files/register-title-labels! (fn [w a b] (l [:titles w a b]))
                  g/gtk-check-button-set-active (fn [o v] (l [:active o v]))
                  g/gtk-widget-set-vexpand (fn [o v] (l [:vexpand o v]))
                  g/gtk-notebook-set-show-tabs (fn [o v] (l [:show-tabs o v]))
                  g/gtk-notebook-set-show-border (fn [o v] (l [:show-border o v]))
                  g/gtk-notebook-set-scrollable (fn [o v] (l [:scrollable o v]))
                  g/gtk-paned-set-start-child (fn [p c] (l [:start p c]))
                  g/gtk-paned-set-end-child (fn [p c] (l [:end p c]))
                  g/gtk-paned-set-position (fn [p v] (l [:position p v]))
                  g/gtk-paned-set-resize-end-child (fn [p v] (l [:resize-end p v]))
                  g/gtk-paned-set-shrink-end-child (fn [p v] (l [:shrink-end p v]))
                  g/gtk-paned-set-resize-start-child (fn [p v] (l [:resize-start p v]))
                  g/gtk-paned-set-shrink-start-child (fn [p v] (l [:shrink-start p v]))
                  g/gtk-widget-set-visible (fn [o v] (l [:visible o v]))
                  g/gtk-window-set-child (fn [w c] (l [:child w c]))
                  g/gtk-window-set-default-size (fn [w x y] (l [:size w x y]))
                  win-status-bar/place! (fn [sm] (l [:status-bar sm]))]
      (win-layout/place! m))
    (is (= [[:start-of ::toolbar ::files] [:start-of ::toolbar ::open] [:start-of ::toolbar ::new]
            [:append ::titles ::title] [:append ::titles ::subtitle] [:title-of ::toolbar ::titles]
            ;; pack_end fills from the edge inwards: the menu button, then Save, Stop, Run
            [:end-of ::toolbar ::menu] [:end-of ::toolbar ::save] [:end-of ::toolbar ::stop] [:end-of ::toolbar ::run]]
           (take 10 (remove #{[:visible ::subtitle 0]} @log))))
    (is (some #{[:visible ::subtitle 0]} @log) "the folder line starts hidden, for a buffer with no file")
    (is (= (if (= "Mac OS X" (System/getProperty "os.name")) [[:native ::toolbar 1]] [])
           (filter #(= :native (first %)) @log)))
    (is (= [[:keep ::reset] [:active ::reset 1]
            [:vexpand ::paned 1] [:show-tabs ::notebook 0] [:show-border ::notebook 0] [:scrollable ::notebook 1]
            [:vexpand ::notebook 1] [:start ::paned ::notebook] [:end ::paned ::scroll] [:position ::paned 430]
            [:vexpand ::h-paned 1] [:start ::h-paned ::paned] [:vexpand ::pane 1] [:vexpand ::sp 1]
            [:append ::slot ::pane] [:append ::slot ::sp] [:vexpand ::rp 1] [:append ::slot ::rp]
            [:vexpand ::lp 1] [:append ::slot ::lp] [:vexpand ::hp 1] [:append ::slot ::hp]
            [:vexpand ::a 1] [:append ::slot ::a] [:vexpand ::b 1] [:append ::slot ::b]
            [:end ::h-paned ::slot] [:resize-end ::h-paned 0] [:shrink-end ::h-paned 0]
            [:visible ::pane 0] [:visible ::sp 0] [:visible ::rp 0] [:visible ::lp 0] [:visible ::a 0] [:visible ::b 0]
            [:visible ::hp 0] [:visible ::slot 0]
            [:titlebar ::win ::toolbar] [:titles ::win ::title ::subtitle]
            [:start ::tree-paned ::tree] [:end ::tree-paned ::h-paned]
            [:resize-start ::tree-paned 0] [:shrink-start ::tree-paned 0] [:vexpand ::tree-paned 1]
            [:append ::vbox ::tree-paned]
            [:status-bar {:vbox ::vbox :status-label ::status :hint-label ::hint :pending-label ::pending
                          :unread-label ::unread :dialect-label ::dialect :parinfer-label ::parinfer
                          :line-col-label ::line-col :update-parinfer-label! ::update}]
            [:child ::win ::vbox] [:size ::win 900 700]]
           (drop-while #(not= [:keep ::reset] %) (remove #(= :native (first %)) @log))))))
