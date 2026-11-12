(ns jonquil.gtk.window.console-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.console :as win-console]
            [jonquil.gtk.window.help :as win-help]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.status :as win-status]))

(defn- evaluator
  "An Evaluator named k that answers waiting-for-input?, complete and lookup
  with what it was asked, tagged with k."
  [k]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ev/Evaluator
    (waiting-for-input? [_] [:waiting k])
    (complete [_ p t pos x] [:complete k p t pos x])
    (lookup [_ s t p] [:lookup k s t p])))

(deftest build-makes-the-console-test
  (let [ev* (atom (evaluator ::e1)) opts (atom nil) submit (atom nil)
        at (fn [] ::tab) busy (fn [] ::busy)
        in {:active-tab at :ev* ev* :status-label ::sl :evaluating-fn busy :x 1}
        w (with-redefs [cview/make-console-view (fn [o] (reset! opts o) {:view ::view :scroll ::scroll})
                        win-run/make-on-submit (fn [{a :tab-fn cv* :*cv e :ev* sl :status-label bang* :bang*}]
                                                 (reset! submit [a cv* e sl bang*])
                                                 ::on-submit)
                        win-run/history-prev-fn (fn [e] [:prev e])
                        win-run/history-next-fn (fn [e] [:next e])]
            (win-console/build in))
        cv (:cv w)
        [a cv* e sl bang*] @submit]
    (is (= (into (set (keys in)) #{:bang* :cv}) (set (keys w))))
    (is (= #{:view :scroll :failure* :after-eval* :recorder* :replay-root* :replay-tab*} (set (keys cv))))
    (is (every? #(nil? @(% cv)) [:failure* :after-eval* :recorder* :replay-root* :replay-tab*]))
    (is (= 5 (count (set (map #(System/identityHashCode (% cv)) [:failure* :after-eval* :recorder* :replay-root* :replay-tab*])))))
    (is (nil? (@(:bang* w) "x")))
    (is (and (identical? at a) (identical? ev* e) (= ::sl sl) (identical? (:bang* w) bang*)))
    (is (identical? cv @cv*))
    (is (= #{:on-submit :history-prev :history-next :evaluating? :waiting?} (set (keys @opts))))
    (is (= ::on-submit (:on-submit @opts)))
    (is (= [:prev ev*] (:history-prev @opts)))
    (is (= [:next ev*] (:history-next @opts)))
    (is (identical? busy (:evaluating? @opts)))
    (reset! ev* (evaluator ::e2))
    (is (= [:waiting ::e2] ((:waiting? @opts))))))

(deftest build-completion-wires-the-console-completion-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) made (atom nil)
        ev* (atom (evaluator ::e1))
        ns-fn (fn [] 'user) server (fn [] nil) close (fn [& _])
        in {:cv {:view ::view} :ev* ev* :status-label ::sl :completion-ns-fn ns-fn :server-ev server
            :close-popovers-fn close :x 1}
        w (with-redefs [completion/make-completion (fn [v o] (reset! made [v o]) ::rc)
                        g/gtk-scrollable-get-vadjustment (fn [v] (l [:vadj v]) [:v v])
                        g/gtk-scrollable-get-hadjustment (fn [v] (l [:hadj v]) [:h v])
                        g/g-signal-connect-data (fn [o s cb & _] (l [:connect o s (some? cb)]) 1)]
            (win-console/build-completion in))
        [view o] @made]
    (is (= (into (set (keys in)) #{:repl-completion :repl-complete-fn}) (set (keys w))))
    (is (= ::rc (:repl-completion w)))
    (is (= ::view view))
    (is (= [[:vadj ::view] [:hadj ::view] [:connect [:v ::view] "value-changed" true]
            [:connect [:h ::view] "value-changed" true]]
           @log))
    (is (= #{:ns-fn :candidates-fn :doc-fn :server-ev :close-others! :on-error :position :snapshot-fn :apply-op!}
           (set (keys o))))
    (is (and (identical? ns-fn (:ns-fn o)) (identical? server (:server-ev o)) (identical? close (:close-others! o))))
    (is (= :top (:position o)))
    (reset! ev* (evaluator ::e2))
    (is (= [:complete ::e2 "ma" "t" 1 "x"] ((:candidates-fn o) "ma" "t" 1 "x")))
    (is (= [:lookup ::e2 "map" "t" 1] ((:doc-fn o) "map" "t" 1)))
    (reset! log [])
    (with-redefs [win-status/set-status! (fn [lab s] (l [:status lab s]))
                  cview/input-snapshot (fn [c] [:snap c])
                  cview/apply-input-op! (fn [c r] (l [:apply c r]))
                  win-help/do-repl-complete! (fn [c e rc sl] (l [:complete c (identical? e ev*) rc sl]))]
      ((:on-error o) "m")
      (is (= [:snap {:view ::view}] ((:snapshot-fn o))))
      ((:apply-op! o) ::r)
      ((:repl-complete-fn w)))
    (is (= [[:status ::sl "m"] [:apply {:view ::view} ::r] [:complete {:view ::view} true ::rc ::sl]] @log))))
