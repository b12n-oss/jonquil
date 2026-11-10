(ns jonquil.gtk.window.status-bar-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.dialect :as dialect]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.gtk.window.status-bar :as win-status-bar]))

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
