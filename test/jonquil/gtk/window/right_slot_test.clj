(ns jonquil.gtk.window.right-slot-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.plugin-history-pane :as plugin-history-pane]
            [jonquil.gtk.plugin-list-pane :as plugin-list-pane]
            [jonquil.gtk.review-pane :as review-pane]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.gtk.window.places :as win-places]
            [jonquil.gtk.window.plugin-agent :as win-plugin-agent]
            [jonquil.gtk.window.right-slot :as win-right-slot]))

(deftest right-roots-test
  (is (= {:inspector ::i :source ::s} (#'win-right-slot/right-roots {:inspector-root ::i :source-root ::s})))
  (is (= {:inspector ::i :source ::s :review ::r :plugins ::p :history ::h :trace ::t}
         (#'win-right-slot/right-roots {:inspector-root ::i :source-root ::s :review-root ::r :plugins-root ::p
                                        :history-root ::h :part-panels* (atom {:trace {:root ::t}})}))))

(deftest show-and-hide-right-panel-test
  (let [vis (atom {::i 0 ::s 0 ::h 0}) current (atom {}) cancelled (atom [])
        shown* @#'win-plugin-agent/plugin-history-shown old @shown*
        ;; the source panel is at the bottom, the others on the right
        right {:win ::win :h-paned ::hp :places {:left ::left :right ::right :bottom ::bottom}
               :place-of* (atom {:source :bottom})
               :inspector-root ::i :source-root ::s
               :history-root ::h :source-pane {:scroll-cancelled? ::flag} :recent-panels (atom [])}]
    (try
      (with-redefs [g/gtk-widget-get-visible (fn [w] (get @vis w 0))
                    g/gtk-widget-set-visible (fn [w v] (swap! vis assoc w v))
                    win-places/show-page! (fn [nb w] (swap! current assoc nb w))
                    win-places/current? (fn [nb w] (and (= 1 (get @vis w)) (= w (get @current nb))))
                    source-pane/cancel-scroll-settle! (fn [f] (swap! cancelled conj f))]
        (reset! shown* "demo")
        (#'win-right-slot/show-right-panel! right :history)
        (is (= "demo" @shown*))
        (is (= :history (#'win-right-slot/showing-panel right)))
        (#'win-right-slot/show-right-panel! right :source)
        (is (nil? @shown*))
        (is (= {::i 0 ::s 1 ::h 1} @vis) "the source panel's place is another, so History stays")
        (#'win-right-slot/show-right-panel! right :inspector)
        (is (= {::i 1 ::s 1 ::h 0} @vis) "one panel per place: the inspector takes History's place")
        (is (= {::right ::i ::bottom ::s} @current))
        (is (= [:inspector :source :history] @(:recent-panels right)))
        (is (= :inspector (#'win-right-slot/showing-panel right)) "the most recently shown first")
        (#'win-right-slot/hide-right-panel! right :inspector)
        (is (= :source (#'win-right-slot/showing-panel right)))
        (#'win-right-slot/hide-right-panel! right :source)
        (is (= [::flag] @cancelled))
        (is (= {::i 0 ::s 0 ::h 0} @vis))
        (is (nil? (#'win-right-slot/showing-panel right))))
      (finally (reset! shown* old)))))

(deftest place-name-test
  (is (= :left (win-right-slot/place-name {} :file-tree)) "the usual place before the parts are placed")
  (is (= :bottom (win-right-slot/place-name {} :repl)))
  (is (= :right (win-right-slot/place-name {} :trace)))
  (is (= :right (win-right-slot/place-name {:place-of* (atom {:file-tree :right})} :file-tree))))

(deftest build-panels-makes-the-panels-test
  (let [log (atom [])
        mk (fn [k] (fn [] (swap! log conj k) {:root k}))
        w (with-redefs [source-pane/make-pane (mk :source)
                        review-pane/make-pane (mk :review)
                        plugin-list-pane/make-pane (mk :list)
                        plugin-history-pane/make-pane (mk :history)]
            (win-right-slot/build-panels {:x 1}))]
    (is (= [:source :review :list :history] @log))
    (is (= #{:x :sp :rp :lp :hp :right*} (set (keys w))))
    (is (= [{:root :source} {:root :review} {:root :list} {:root :history}] (map w [:sp :rp :lp :hp])))
    (is (nil? @(:right* w)))))

(deftest publish-resets-right-test
  (let [r (atom nil)
        in {:right ::right :right* r :x 1}]
    (is (= in (win-right-slot/publish in)))
    (is (= ::right @r))))

(deftest add-to-place-records-the-page-and-wires-its-tab-test
  (let [log (atom []) menu (atom [])
        right {:places {:left ::left :right ::right :bottom ::bottom}
               :place-of* (atom {:repl :right}) :pages-of* (atom {})
               :tab-menu* (atom (fn [id tab] (swap! menu conj [id tab])))}
        press (atom nil)]
    (with-redefs [g/gtk-label-new (fn [s] [:label s])
                  g/gtk-gesture-click-new (fn [] ::click)
                  g/gtk-gesture-single-set-button (fn [_ _] nil)
                  g/g-signal-connect-data (fn [_ _ cb _ _ _] (reset! press cb) 1)
                  g/gtk-widget-add-controller (fn [w c] (swap! log conj [:controller w c]))
                  win-places/add! (fn [nb w tab] (swap! log conj [:add nb w tab]))
                  win-places/remove! (fn [nb w] (swap! log conj [:remove nb w]))]
      (win-right-slot/add-to-place! right :repl ::scroll "Interactions")
      (is (= [[:controller [:label "Interactions"] ::click] [:add ::right ::scroll [:label "Interactions"]]] @log))
      (is (= {:repl [::scroll]} @(:pages-of* right)))
      (win-right-slot/remove-from-place! right :repl ::scroll)
      (is (= [:remove ::right ::scroll] (last @log)))
      (is (= {:repl []} @(:pages-of* right))))))
