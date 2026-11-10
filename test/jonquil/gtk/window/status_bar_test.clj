(ns jonquil.gtk.window.status-bar-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.dialect :as dialect]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.gtk.window.status-bar :as win-status-bar]
            [jonquil.settings :as settings]))

(deftest build-makes-the-labels-and-the-hints-toggle-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) n (atom 0)
        in {:sel* (atom ::sel) :x 1}
        w (with-redefs [g/gtk-label-new (fn [t] (let [k (keyword (str "l" (swap! n inc)))] (l [:new k t]) k))
                        g/gtk-widget-add-css-class (fn [o c] (l [:css o c]))
                        g/gtk-label-set-ellipsize (fn [o m] (l [:ellipsize o m]))
                        g/gtk-label-set-max-width-chars (fn [o c] (l [:max-width o c]))
                        dialect/label (fn [s] (l [:dialect s]) "Clojure")]
            (win-status-bar/build in))]
    (is (= (into (set (keys in))
                 #{:status-label :line-col-label :hint-label :hints-on? :parinfer-label :pending-label
                   :dialect-label :unread-label :hints-toggle-fn})
           (set (keys w))))
    (is (= [[:new :l1 "Ready"] [:new :l2 "Ln 1, Col 1"] [:new :l3 ""] [:css :l3 "dim-label"]
            [:ellipsize :l3 g/PANGO-ELLIPSIZE-END] [:max-width :l3 44] [:new :l4 ""] [:new :l5 ""]
            [:dialect ::sel] [:new :l6 "Clojure"] [:new :l7 ""]]
           @log))
    (is (= [:l1 :l2 :l3 :l4 :l5 :l6 :l7]
           (map w [:status-label :line-col-label :hint-label :parinfer-label :pending-label
                   :dialect-label :unread-label])))
    (is (true? @(:hints-on? w)))
    (reset! log [])
    (with-redefs [g/gtk-widget-set-visible (fn [o v] (l [:visible o v]))
                  win-status/set-status! (fn [lab s] (l [:status lab s]))]
      ((:hints-toggle-fn w))
      (is (false? @(:hints-on? w)))
      ((:hints-toggle-fn w))
      (is (true? @(:hints-on? w))))
    (is (= [[:visible :l3 0] [:status :l1 "; Key hints off."]
            [:status :l1 "; Key hints on: the status bar names the keys for the symbol at the caret."]]
           @log))))

(deftest place-fills-the-status-row-test
  (let [log (atom []) l (fn [x] (swap! log conj x))]
    (with-redefs [g/gtk-box-new (fn [o s] (l [:box o s]) ::box)
                  g/gtk-widget-set-hexpand (fn [o v] (l [:hexpand o v]))
                  g/gtk-box-append (fn [b c] (l [:append b c]))
                  g/gtk-widget-set-visible (fn [o v] (l [:visible o v]))
                  g/gtk-widget-set-margin-start (fn [o m] (l [:margin-start o m]))
                  g/gtk-widget-set-margin-end (fn [o m] (l [:margin-end o m]))]
      (win-status-bar/place! {:vbox ::vbox :status-label ::status :hint-label ::hint :pending-label ::pending
                              :unread-label ::unread :dialect-label ::dialect :parinfer-label ::parinfer
                              :line-col-label ::line-col :update-parinfer-label! (fn [] (l :parinfer-update))}))
    (is (= [[:box g/ORIENTATION-HORIZONTAL 6] [:hexpand ::status 1]
            [:append ::box ::status] [:append ::box ::hint] [:visible ::hint 0]
            [:append ::box ::pending] [:visible ::pending 0] [:append ::box ::unread] [:visible ::unread 0]
            [:append ::box ::dialect] [:append ::box ::parinfer] :parinfer-update [:append ::box ::line-col]
            [:margin-start ::box 8] [:margin-end ::box 8] [:append ::vbox ::box]]
           @log))))

(deftest build-parinfer-mode-label-status-and-command-test
  (let [log (atom []) l (fn [x] (swap! log conj x))
        busy (atom false) save-err (atom nil)
        tab (atom {:project (atom {:parinfer :paren})})
        in {:settings-path* (atom "/cfg/settings.edn") :active-tab (fn [] @tab) :parinfer-label ::pl
            :evaluating-fn (fn [] @busy) :status-label ::sl :cv ::cv :x 1}
        w (with-redefs [settings/load-settings (fn [p] (l [:load p]) {:parinfer :smart})]
            (win-status-bar/build-parinfer in))]
    (is (= (into (set (keys in)) #{:parinfer-mode* :update-parinfer-label! :parinfer-status-fn :parinfer-fn})
           (set (keys w))))
    (is (= :smart @(:parinfer-mode* w)))
    (is (= [[:load "/cfg/settings.edn"]] @log))
    (reset! log [])
    (with-redefs [g/gtk-label-set-text (fn [o t] (l [:text o t]))
                  g/gtk-widget-set-visible (fn [o v] (l [:visible o v]))
                  win-status/set-status! (fn [o s] (l [:status o s]))
                  settings/save-settings! (fn [p m] (l [:save p m]) @save-err)
                  cview/append! (fn [c k s] (l [:append c k s]))]
      ((:update-parinfer-label! w))
      (reset! tab nil)
      ((:update-parinfer-label! w))
      (reset! (:parinfer-mode* w) :off)
      ((:update-parinfer-label! w))
      (reset! (:parinfer-mode* w) :smart)
      (is (= [[:text ::pl "Parinfer: Paren (project)"] [:visible ::pl 1]
              [:text ::pl "Parinfer: Smart"] [:visible ::pl 1]
              [:visible ::pl 0]]
             @log))
      (reset! log [])
      ((:parinfer-status-fn w) "form too long")
      ((:parinfer-status-fn w) nil)
      ((:parinfer-status-fn w) nil)
      (reset! busy true)
      ((:parinfer-status-fn w) "x")
      ((:parinfer-status-fn w) nil)
      (reset! busy false)
      ((:parinfer-status-fn w) nil)
      (is (= [[:status ::sl "Parinfer: Smart (form too long)"] [:status ::sl "Ready"]] @log))
      (reset! log [])
      (reset! tab {:project (atom {:parinfer :paren})})
      ((:parinfer-status-fn w) "y")
      ((:parinfer-fn w) :indent)
      ((:parinfer-status-fn w) nil)
      (is (= :indent @(:parinfer-mode* w)))
      (reset! save-err "disk full")
      (reset! busy true)
      ((:parinfer-fn w) :next)
      (reset! busy false)
      (reset! tab nil)
      ((:parinfer-fn w) :off)
      (is (= [[:status ::sl "Parinfer: Smart (y)"]
              [:text ::pl "Parinfer: Paren (project)"] [:visible ::pl 1]
              [:save "/cfg/settings.edn" {:parinfer :indent}]
              [:status ::sl "Parinfer: Indent (this project says Paren)"]
              [:text ::pl "Parinfer: Paren (project)"] [:visible ::pl 1]
              [:save "/cfg/settings.edn" {:parinfer :paren}]
              [:append ::cv :system "; settings: disk full\n"]
              [:visible ::pl 0]
              [:save "/cfg/settings.edn" {:parinfer :off}]
              [:append ::cv :system "; settings: disk full\n"]
              [:status ::sl "Parinfer: off"]]
             @log)))))
