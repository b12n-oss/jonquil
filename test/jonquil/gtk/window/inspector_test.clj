(ns jonquil.gtk.window.inspector-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.commands :as commands]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.window.inspector :as win-inspector]
            [jonquil.gtk.window.right-slot :as win-right-slot]
            [jonquil.gtk.window.run :as win-run]
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
  "Calls inspect-last! with its map: ev*, ::right, ::pane,
  the :state, :inspecting? and :last-args atoms, ::on-view and ::l, plus
  :start when given."
  [{:keys [ev* start state inspecting? last-args]}]
  (#'win-inspector/inspect-last! {:ev* ev* :right ::right :pane ::pane :inspector-state state
                                  :inspecting? inspecting? :inspector-last-args last-args
                                  :on-view ::on-view :status-label ::l
                                  :start (or start [:start {}])}))

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
      (#'win-inspector/on-inspect-view! {:pane ::pane :inspector-state state :inspecting? inspecting?
                                         :inspect-queued (atom nil) :cv ::cv :status-label ::l}
                                        {:v 1 :defd "user/a"})
      (is (false? @inspecting?))
      (is (= [:open {:v 1 :defd "user/a"}] @state))
      (is (= [[:show-state ::pane [:open {:v 1 :defd "user/a"}]]
              [::cv :system "; Defined user/a\n"]]
             @log))
      (reset! log [])
      (#'win-inspector/on-inspect-view! {:pane ::pane :inspector-state state :inspecting? inspecting?
                                         :inspect-queued (atom nil) :cv ::cv :status-label ::l}
                                        {:v 2})
      (is (= [:reply [:open {:v 1 :defd "user/a"}] {:v 2}] @state))
      (is (= [[:show-state ::pane @state]] @log)))))

(deftest on-inspect-view-contains-and-runs-the-queued-open-test
  (let [log (atom [])
        inspecting? (atom true)
        queued (atom (fn [] (swap! log conj :queued)))]
    (with-redefs [inspect/open (fn [r] [:open r])
                  inspector-pane/show-state! (fn [_ _] (throw (ex-info "boom" {})))
                  win-status/set-status! (fn [l text] (swap! log conj [l text]))]
      (#'win-inspector/on-inspect-view! {:pane ::pane :inspector-state (atom nil) :inspecting? inspecting?
                                         :inspect-queued queued :cv ::cv :status-label ::l}
                                        {:v 1})
      (is (= [[::l "; inspect: boom"] :queued] @log))
      (is (false? @inspecting?))
      (is (nil? @queued))
      (reset! log [])
      (reset! queued (fn [] (throw (ex-info "late" {}))))
      (#'win-inspector/on-inspect-view! {:pane ::pane :inspector-state (atom nil) :inspecting? inspecting?
                                         :inspect-queued queued :cv ::cv :status-label ::l}
                                        {:v 1})
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

(deftest inspect-last-takes-one-map-test
  (let [log (atom [])
        ready (atom nil)
        state (atom nil)
        la (atom nil)
        f (fn [] (win-inspector/inspect-last!
                  {:ev* (atom (inspector-ev :e1 true log)) :right ::right :pane ::pane
                   :inspector-state state :inspecting? (atom false) :inspector-last-args la
                   :on-view ::on-view :status-label ::l
                   :start [:start-trace {:id 3 :part :ret}]}))]
    (with-panel-stubs log ready f)
    (reset! log [])
    (@ready {:rows 2 :cols 3})
    (is (= [[:inspect! :e1 :start-trace {:rows 2 :cols 3 :id 3 :part :ret} ::on-view]] @log))
    (is (= 1 (count (:arglists (meta #'win-inspector/inspect-last!)))))))

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

(defn- form-env
  "Runs f with inspect-form!'s collaborators stubbed, logging into log:
  refuse-foreign! answers refused, the editor snapshot is {:text :caret},
  make-eval-opts logs [:opts ed cv ev* status-label run?] from its ctx and answers a
  base opts map whose :on-complete logs [:base r], and on-gui! logs :gui
  and runs its thunk. The evaluator (evaluating? is busy, inspectable? is
  ok?) keeps the opts eval! got in sent and answers warning."
  [log sent {:keys [refused ok? busy warning text caret]} f]
  (let [e #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify
          ev/Evaluator
          (evaluating? [_] busy)
          (eval! [_ code opts]
            (reset! sent opts)
            (swap! log conj [:eval code (dissoc opts :on-complete)])
            warning)
          ev/Inspector
          (inspectable? [_] ok?))
        ev* (atom e)]
    (with-redefs [win-run/refuse-foreign! (fn [_] refused)
                  editor/snapshot (fn [_] {:text text :caret caret})
                  win-run/make-eval-opts (fn [{:keys [ed cv ev* status-label run?]}]
                                           (swap! log conj [:opts ed cv ev* status-label run?])
                                           {:k 1 :on-complete (fn [r] (swap! log conj [:base r]))})
                  cview/echo-entry! (fn [_ t fresh?] (swap! log conj [:echo t fresh?]))
                  cview/note-run-start! (fn [_] (swap! log conj [:note-start]))
                  cview/append! (fn [cv kind t] (swap! log conj [cv kind t]))
                  win-status/set-status! (fn [l t] (swap! log conj [l t]))
                  jonquil.gtk.callbacks/on-gui! (fn [g] (swap! log conj :gui) (g))]
      (f ev*))))

(defn- form-ctx
  "inspect-form!'s ctx around ev*, with ilf as inspect-last-fn."
  [log ev* ilf]
  {:tab {:id 7 :ed ::ed
         :flash-fns {:flash! (fn [s e c] (swap! log conj [:flash s e c]))}}
   :cv ::cv :ev* ev* :sel* ::sel :status-label ::l
   :inspecting? (atom false) :inspect-queued (atom nil) :inspect-last-fn ilf})

(deftest inspect-form-refusals-test
  (let [run (fn [env mutate]
              (let [log (atom [])
                    sent (atom nil)]
                (form-env log sent env
                          (fn [ev*]
                            (let [ctx (mutate (form-ctx log ev* (fn [] nil)))]
                              (win-inspector/inspect-form! ctx)
                              @log)))))
        ok {:ok? true :text "(+ 1 2)" :caret 3}]
    (is (= [] (run (assoc ok :refused "no") identity)))
    (is (= [] (run (assoc ok :refused "no" :ok? false) identity)))
    (is (= [[::l inspect/unavailable-message]] (run (assoc ok :ok? false) identity)))
    (is (= [] (run ok #(assoc % :inspecting? (atom true)))))
    (is (= [[::l "No form at the caret."]] (run (assoc ok :text "  ") identity)))
    (is (= '([{:keys [tab cv ev* status-label inspecting? inspect-queued inspect-last-fn] :as ctx}])
           (:arglists (meta #'win-inspector/inspect-form!))))))

(deftest inspect-form-refuses-a-non-inspector-evaluator-test
  (let [log (atom [])
        plain #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify
          ev/Evaluator
          (evaluating? [_] (swap! log conj :evaluating?) false)
          (eval! [_ code _] (swap! log conj [:eval code]) nil))]
    (with-redefs [win-run/refuse-foreign! (fn [_] nil)
                  editor/snapshot (fn [_] {:text "(+ 1 2)" :caret 3})
                  win-run/make-eval-opts (fn [& _] (swap! log conj :opts) {})
                  win-status/set-status! (fn [l t] (swap! log conj [l t]))
                  jonquil.gtk.callbacks/on-gui! (fn [g] (swap! log conj :gui) (g))]
      (win-inspector/inspect-form! (form-ctx log (atom plain) (fn [] nil)))
      (is (= [[::l inspect/unavailable-message]] @log)))))

(deftest inspect-form-evaluates-like-run-form-test
  (let [log (atom [])
        sent (atom nil)
        form (commands/run-form {:text "(+ 1 2)" :caret 3})]
    (form-env log sent {:ok? true :busy false :text "(+ 1 2)" :caret 3}
              (fn [ev*]
                (is (nil? (win-inspector/inspect-form! (form-ctx log ev* (fn [] nil)))))
                (is (= [[:opts ::ed ::cv ev* ::l false]
                        [:flash (:start form) (:end form) 3]
                        [:echo "(+ 1 2)" true]
                        [:note-start]
                        [:eval "(+ 1 2)" {:k 1 :run? false :ns-source "(+ 1 2)" :owner 7}]]
                       @log))))))

(deftest inspect-form-busy-test
  (let [log (atom [])
        sent (atom nil)]
    (form-env log sent {:ok? true :busy true :warning "; busy\n" :text "(+ 1 2)" :caret 3}
              (fn [ev*]
                (win-inspector/inspect-form! (form-ctx log ev* (fn [] nil)))
                (is (some #{[:echo "(+ 1 2)" false]} @log))
                (is (not-any? #{[:note-start]} @log))
                (is (= [::cv :system "; busy\n"] (last @log)))))))

(deftest inspect-form-opens-on-ok-test
  (let [run (fn [result inspecting]
              (let [log (atom [])
                    sent (atom nil)
                    ilf (fn [] (swap! log conj :inspect-last))]
                (form-env log sent {:ok? true :busy false :text "(+ 1 2)" :caret 3}
                          (fn [ev*]
                            (let [ctx (assoc (form-ctx log ev* ilf) :inspecting? (atom false))]
                              (win-inspector/inspect-form! ctx)
                              (reset! log [])
                              (reset! (:inspecting? ctx) inspecting)
                              ((:on-complete @sent) result)
                              {:log @log :queued @(:inspect-queued ctx) :ilf ilf})))))
        ok (run {:status :ok} false)
        err (run {:status :error} false)
        busy (run {:status :ok} true)]
    (is (= [[:base {:status :ok}] :gui :inspect-last] (:log ok)))
    (is (= [[:base {:status :error}]] (:log err)))
    (is (= [[:base {:status :ok}] :gui] (:log busy)))
    (is (identical? (:ilf busy) (:queued busy)))))

(deftest inspect-form-base-completion-throw-skips-the-open-test
  (let [log (atom [])
        sent (atom nil)]
    (form-env log sent {:ok? true :busy false :text "(+ 1 2)" :caret 3}
              (fn [ev*]
                (with-redefs [win-run/make-eval-opts
                              (fn [& _] {:k 1 :on-complete (fn [_] (throw (ex-info "base" {})))})]
                  (win-inspector/inspect-form! (form-ctx log ev* (fn [] nil))))
                (reset! log [])
                (is (thrown? Exception ((:on-complete @sent) {:status :ok})))
                (is (not-any? #{:gui} @log))))))
