(ns jonquil.gtk.window.inspector-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.window.inspector :as win-inspector]
            [jonquil.gtk.window.right-slot :as win-right-slot]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.inspect :as inspect]))

(defn- inspector-ev
  "An Evaluator and Inspector stub: inspectable? answers ok?; inspect! logs
  [:inspect! id op args on-view] into log, and throws \"boom\" when ok? is
  :throw."
  [id ok? log]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify
    ev/Evaluator
    ev/Inspector
    (inspectable? [_] ok?)
    (inspect! [_ op args on-view]
      (swap! log conj [:inspect! id op args on-view])
      (when (= :throw ok?) (throw (ex-info "boom" {}))))))

(defn- inspect-last-via
  "Calls inspect-last! with its positional arguments: ev*, ::right, ::pane,
  the :state, :inspecting? and :last-args atoms, ::on-view and ::l, plus
  :start when given."
  [{:keys [ev* start state inspecting? last-args]}]
  (if start
    (#'win-inspector/inspect-last! ev* ::right ::pane state inspecting? last-args ::on-view ::l start)
    (#'win-inspector/inspect-last! ev* ::right ::pane state inspecting? last-args ::on-view ::l)))

(defn- with-panel-stubs
  "Runs f with the panel's show, focus and measure, and the status line,
  logging into log; the measure's callback is kept in ready."
  [log ready f]
  (with-redefs [win-right-slot/show-right-panel! (fn [right k] (swap! log conj [:show right k]))
                inspector-pane/focus! (fn [pane] (swap! log conj [:focus pane]))
                inspector-pane/measure-when-ready! (fn [_pane f] (reset! ready f))
                win-status/set-status! (fn [l text] (swap! log conj [l text]))]
    (f)))

(deftest try-inspect-runs-one-at-a-time-test
  (let [a (atom false)]
    (is (= :sent (#'win-inspector/try-inspect! a (fn [] :sent))))
    (is (true? @a))
    (let [ran (atom false)]
      (is (nil? (#'win-inspector/try-inspect! a (fn [] (reset! ran true)))))
      (is (false? @ran)))
    (reset! a false)
    (is (thrown? Exception (#'win-inspector/try-inspect! a (fn [] (throw (ex-info "boom" {}))))))
    (is (false? @a))))

(deftest on-inspect-view-opens-then-folds-test
  (let [log (atom [])
        inspecting? (atom true)
        state (atom nil)]
    (with-redefs [inspect/open (fn [r] [:open r])
                  inspect/on-reply (fn [s r] [:reply s r])
                  inspector-pane/show-state! (fn [pane s] (swap! log conj [:show-state pane s]))
                  cview/append! (fn [cv kind text] (swap! log conj [cv kind text]))]
      (#'win-inspector/on-inspect-view! ::pane state inspecting? (atom nil) ::cv ::l {:v 1 :defd "user/a"})
      (is (false? @inspecting?))
      (is (= [:open {:v 1 :defd "user/a"}] @state))
      (is (= [[:show-state ::pane [:open {:v 1 :defd "user/a"}]]
              [::cv :system "; Defined user/a\n"]]
             @log))
      (reset! log [])
      (#'win-inspector/on-inspect-view! ::pane state inspecting? (atom nil) ::cv ::l {:v 2})
      (is (= [:reply [:open {:v 1 :defd "user/a"}] {:v 2}] @state))
      (is (= [[:show-state ::pane @state]] @log)))))

(deftest on-inspect-view-contains-and-runs-the-queued-open-test
  (let [log (atom [])
        inspecting? (atom true)
        queued (atom (fn [] (swap! log conj :queued)))]
    (with-redefs [inspect/open (fn [r] [:open r])
                  inspector-pane/show-state! (fn [_ _] (throw (ex-info "boom" {})))
                  win-status/set-status! (fn [l text] (swap! log conj [l text]))]
      (#'win-inspector/on-inspect-view! ::pane (atom nil) inspecting? queued ::cv ::l {:v 1})
      (is (= [[::l "; inspect: boom"] :queued] @log))
      (is (false? @inspecting?))
      (is (nil? @queued))
      (reset! log [])
      (reset! queued (fn [] (throw (ex-info "late" {}))))
      (#'win-inspector/on-inspect-view! ::pane (atom nil) inspecting? queued ::cv ::l {:v 1})
      (is (= [[::l "; inspect: boom"] [::l "; inspect: late"]] @log)))))

(deftest inspect-last-unavailable-and-pending-test
  (let [log (atom [])
        ready (atom nil)]
    (with-panel-stubs log ready
      (fn []
        (inspect-last-via {:ev* (atom (inspector-ev :e1 false log)) :state (atom nil)
                           :inspecting? (atom false) :last-args (atom nil)})
        (is (= [[::l inspect/unavailable-message]] @log))
        (is (nil? @ready))
        (reset! log [])
        (inspect-last-via {:ev* (atom (inspector-ev :e1 true log)) :state (atom nil)
                           :inspecting? (atom true) :last-args (atom nil)})
        (is (= [] @log))))))

(deftest inspect-last-sends-from-the-press-evaluator-test
  (let [log (atom [])
        ready (atom nil)
        state (atom ::old)
        inspecting? (atom false)
        last-args (atom nil)
        ev* (atom (inspector-ev :e1 true log))]
    (with-panel-stubs log ready
      (fn []
        (inspect-last-via {:ev* ev* :state state :inspecting? inspecting? :last-args last-args})
        (is (= [[:show ::right :inspector] [:focus ::pane]] @log))
        (is (nil? @state))
        (is (true? @inspecting?))
        (reset! log [])
        (reset! ev* (inspector-ev :e2 true log))
        (@ready {:rows 10 :cols 40})
        (is (= [[:inspect! :e1 :start {:rows 10 :cols 40} ::on-view]] @log))
        (is (= {:rows 10 :cols 40} @last-args))
        (reset! log [])
        (reset! inspecting? false)
        (reset! ev* (inspector-ev :e3 true log))
        (inspect-last-via {:ev* ev* :state state :inspecting? inspecting? :last-args last-args
                           :start [:start-local {:frame 1 :local "x"}]})
        (@ready {:rows 10 :cols 40})
        (is (= [:inspect! :e3 :start-local {:rows 10 :cols 40 :frame 1 :local "x"} ::on-view]
               (last @log)))))))

(deftest inspect-last-throw-releases-the-flag-test
  (let [log (atom [])
        ready (atom nil)
        inspecting? (atom false)]
    (with-panel-stubs log ready
      (fn []
        (inspect-last-via {:ev* (atom (inspector-ev :e1 :throw log)) :state (atom nil)
                           :inspecting? inspecting? :last-args (atom nil)})
        (is (thrown? Exception (@ready {:rows 1 :cols 1})))
        (is (false? @inspecting?))))))

(deftest build-adds-the-inspector-bindings-test
  (let [made (atom nil)
        ev* (atom nil)
        r (atom ::r)
        af (fn [] {:ed ::ed})]
    (with-redefs [g/gtk-paned-new (fn [_] ::h-paned)
                  inspector-pane/make-pane (fn [opts] (reset! made opts) {:root ::root})]
      (let [w (win-inspector/build {:active-tab af :cv ::cv :ev* ev* :status-label ::l :right* r :x 1})]
        (is (= #{:active-tab :cv :ev* :status-label :right* :x
                 :h-paned :inspector-state :inspecting? :inspect-queued :inspector-last-args
                 :on-view :pane :on-inspector-close}
               (set (keys w))))
        (is (= 1 (:x w)))
        (is (identical? af (:active-tab w)))
        (is (= ::h-paned (:h-paned w)))
        (is (= {:root ::root} (:pane w)))
        (is (nil? @(:inspector-state w)))
        (is (false? @(:inspecting? w)))
        (is (identical? (:inspector-state w) (:state @made)))
        (is (identical? (:on-inspector-close w) (:on-close @made)))))))

(deftest build-closures-read-the-forward-atoms-test
  (let [made (atom nil)
        log (atom [])
        ev* (atom (inspector-ev :e1 true log))
        r (atom ::r1)
        af (fn [] {:ed ::ed})]
    (with-redefs [g/gtk-paned-new (fn [_] ::h-paned)
                  inspector-pane/make-pane (fn [opts] (reset! made opts) {:root ::root})
                  jonquil.gtk.callbacks/on-gui! (fn [f] (f))
                  inspector-pane/show-state! (fn [pane s] (swap! log conj [:show pane s]))
                  inspect/open (fn [reply] [:open reply])
                  win-right-slot/hide-right-panel! (fn [right k] (swap! log conj [:hide right k]))
                  editor/focus! (fn [ed] (swap! log conj [:focus ed]))]
      (let [w (win-inspector/build {:active-tab af :cv ::cv :ev* ev* :status-label ::l :right* r})]
        (reset! r ::r2)
        ((:on-close @made))
        (is (= [[:hide ::r2 :inspector] [:focus ::ed]] @log))
        (reset! log [])
        (is (true? ((:on-request @made) [:in {:i 0}])))
        (is (= [[:inspect! :e1 :in {:i 0} (:on-view w)]] @log))
        (is (= {:i 0} @(:inspector-last-args w)))
        (is (false? ((:on-request @made) [:in {:i 1}])))
        (is (= 1 (count @log)))
        (reset! log [])
        ((:on-view w) {:v 1})
        (is (= [[:show {:root ::root} [:open {:v 1}]]] @log))
        (is (false? @(:inspecting? w)))
        (reset! log [])
        (reset! (:inspector-state w) nil)
        ((:on-def @made) "a")
        (is (= [[:inspect! :e1 :def {:name "a"} (:on-view w)]] @log))
        (is (= :keep (:pending @(:inspector-state w))))))))
