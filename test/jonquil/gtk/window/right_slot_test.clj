(ns jonquil.gtk.window.right-slot-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.gtk.window.plugin-agent :as win-plugin-agent]
            [jonquil.gtk.window.right-slot :as win-right-slot]))

(deftest right-roots-test
  (is (= {:inspector ::i :source ::s} (#'win-right-slot/right-roots {:inspector-root ::i :source-root ::s})))
  (is (= {:inspector ::i :source ::s :review ::r :plugins ::p :history ::h :trace ::t}
         (#'win-right-slot/right-roots {:inspector-root ::i :source-root ::s :review-root ::r :plugins-root ::p
                                        :history-root ::h :part-panels* (atom {:trace {:root ::t}})}))))

(deftest show-and-hide-right-panel-test
  (let [vis (atom {::slot 0 ::i 0 ::s 0 ::h 0}) pos (atom []) cancelled (atom [])
        shown* @#'win-plugin-agent/plugin-history-shown old @shown*
        right {:win ::win :h-paned ::hp :slot ::slot :inspector-root ::i :source-root ::s
               :history-root ::h :source-pane {:scroll-cancelled? ::flag} :recent-panels (atom [])}]
    (try
      (with-redefs [g/gtk-widget-get-visible (fn [w] (get @vis w 0))
                    g/gtk-widget-set-visible (fn [w v] (swap! vis assoc w v))
                    g/gtk-widget-get-width (fn [_] 1000)
                    g/gtk-paned-set-position (fn [_ p] (swap! pos conj p))
                    source-pane/cancel-scroll-settle! (fn [f] (swap! cancelled conj f))]
        (reset! shown* "demo")
        (#'win-right-slot/show-right-panel! right :history)
        (is (= "demo" @shown*))
        (is (= :history (#'win-right-slot/showing-panel right)))
        (#'win-right-slot/show-right-panel! right :source)
        (is (nil? @shown*))
        (is (= {::slot 1 ::i 0 ::s 1 ::h 0} @vis))
        (is (= [580] @pos))
        (is (= [:source :history] @(:recent-panels right)))
        (#'win-right-slot/hide-right-panel! right :source)
        (is (= [::flag] @cancelled))
        (is (= {::slot 0 ::i 0 ::s 0 ::h 0} @vis))
        (is (nil? (#'win-right-slot/showing-panel right))))
      (finally (reset! shown* old)))))
