(ns jonquil.gtk.window.help-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.edit :as edit]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.gtk.window.help :as win-help]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.help :as help]
            [jonquil.source :as source]))

(defn- logging-evaluator
  "An Evaluator whose lookup logs [:lookup sym text pos] into log and
  answers {:doc sym}."
  [log]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ev/Evaluator
    (lookup [_ sym text pos]
      (swap! log conj [:lookup sym text pos])
      {:doc sym})))

(defn- plain-evaluator
  "An Evaluator with no methods; only its identity matters."
  []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ev/Evaluator))

(deftest popover-registry-test
  (let [ps (atom [])
        open (atom #{::a ::b})
        log (atom [])]
    (with-redefs [popover/open? (fn [p] (contains? @open p))
                  popover/hide! (fn [p] (swap! log conj [:hide p]))]
      (is (nil? (#'win-help/register-popover! ps ::a)))
      (#'win-help/register-popover! ps ::b)
      (#'win-help/register-popover! ps ::c)
      (is (= [::a ::b ::c] @ps))
      (is (true? (#'win-help/close-popovers! ps ::a)))
      (is (= [[:hide ::b]] @log))
      (is (true? (#'win-help/close-popovers! ps)))
      (is (= [[:hide ::b] [:hide ::a] [:hide ::b]] @log))
      (reset! open #{::a})
      (reset! log [])
      (is (false? (#'win-help/close-popovers! ps ::a)))
      (is (= [] @log)))))

(deftest small-helpers-test
  (is (= "1 occurrence" (#'win-help/occurrence-count-phrase 1)))
  (is (= "0 occurrences" (#'win-help/occurrence-count-phrase 0)))
  (is (= "2 occurrences" (#'win-help/occurrence-count-phrase 2)))
  (is (true? (#'win-help/live-editor? {:destroyed? (atom false)})))
  (is (false? (#'win-help/live-editor? {:destroyed? (atom true)})))
  (with-redefs [help/target (fn [s] (:t s))]
    (is (true? (#'win-help/same-target? {:symbol "a" :start 1} {:t {:symbol "a" :start 1 :end 2}})))
    (is (false? (#'win-help/same-target? {:symbol "a" :start 1} {:t {:symbol "a" :start 4 :end 5}})))
    (is (false? (#'win-help/same-target? {:symbol "a" :start 1} {:t nil})))))

(deftest ask-lookup-plain-evaluator-test
  (let [log (atom [])
        e (logging-evaluator log)]
    (#'win-help/ask-lookup! {:ev* (atom e) :status-label ::l} "map" "(map)" 1 (constantly true) "quick doc"
                            (fn [d] (swap! log conj [:doc d])))
    (is (= [[:lookup "map" "(map)" 1] [:doc {:doc "map"}]] @log))))

(deftest ask-lookup-server-evaluator-test
  (let [log (atom [])
        shown (atom nil)
        later (atom nil)
        answer (atom nil)
        e (plain-evaluator)
        ev* (atom e)
        on-doc (fn [d] (swap! log conj [:doc d]))
        ask! (fn [still?]
               (#'win-help/ask-lookup! {:ev* ev* :status-label ::l} "map" "(map)" 1 still? "quick doc" on-doc))]
    (with-redefs [ev/server-help? (constantly true)
                  ev/ask-help! (fn [_ms _f on-later] (reset! later on-later) @answer)
                  jonquil.gtk.callbacks/on-gui! (fn [f] (f))
                  g/label-text (fn [_] @shown)
                  win-status/set-status! (fn [l text] (reset! shown text) (swap! log conj [l text]))]
      (reset! answer [:now {:doc 1}])
      (ask! (constantly true))
      (is (= [[:doc {:doc 1}]] @log))
      (reset! log [])
      (reset! answer [:now {::ev/error "boom"}])
      (ask! (constantly true))
      (is (= [[::l "; quick doc: boom"]] @log))
      (reset! log [])
      (reset! shown nil)
      (reset! answer [:later])
      (ask! (constantly true))
      (is (= [[::l "Looking up map..."]] @log))
      (@later {:doc 2})
      (is (= [[::l "Looking up map..."] [::l "Ready"] [:doc {:doc 2}]] @log))
      (reset! log [])
      (ask! (constantly false))
      (@later {:doc 3})
      (is (= [[::l "Looking up map..."] [::l "Ready"]] @log))
      (reset! log [])
      (ask! (constantly true))
      (reset! ev* (plain-evaluator))
      (@later {:doc 4})
      (is (= [[::l "Looking up map..."] [::l "Ready"]] @log)))))

(deftest do-rename-test
  (let [log (atom [])
        close (fn [] (swap! log conj :close))]
    (with-redefs [editor/snapshot (fn [ed] (:snap ed))
                  help/target (fn [s] (:t s))
                  help-popover/show-rename! (fn [pop sym] (swap! log conj [:show pop sym]))
                  win-status/set-status! (fn [l text] (swap! log conj [l text]))]
      (#'win-help/do-rename! {:snap {:t {:symbol "foo"}}} ::pop ::l close)
      (is (= [:close [:show ::pop "foo"]] @log))
      (reset! log [])
      (#'win-help/do-rename! {:snap {}} ::pop ::l close)
      (is (= [[::l "Put the caret on a symbol first."]] @log))
      (reset! log [])
      (with-redefs [help-popover/show-rename! (fn [_ _] (throw (ex-info "boom" {})))]
        (#'win-help/do-rename! {:snap {:t {:symbol "foo"}}} ::pop ::l close))
      (is (= [:close [::l "; rename: boom"]] @log)))))

(deftest do-rename-to-test
  (let [log (atom [])
        ed {:snap {:t {:symbol "foo"}}}]
    (with-redefs [editor/snapshot (fn [ed] (:snap ed))
                  help/target (fn [s] (:t s))
                  help/rename-problem (fn [_ n] (when (= n "1x") "Not a valid name."))
                  help-popover/show-rename-problem! (fn [pop msg] (swap! log conj [:problem pop msg]))
                  edit/rename (fn [_ n] {:edits [:e1 :e2] :n n})
                  editor/apply-op! (fn [ed r] (swap! log conj [:apply ed r]))
                  popover/hide! (fn [p] (swap! log conj [:hide p]))
                  win-status/set-status! (fn [l text] (swap! log conj [l text]))]
      (is (= "Not a valid name." (#'win-help/do-rename-to! ed ::pop "1x" ::l)))
      (is (= [[:problem ::pop "Not a valid name."]] @log))
      (reset! log [])
      (is (nil? (#'win-help/do-rename-to! ed ::pop "bar" ::l)))
      (is (= [[:apply ed {:edits [:e1 :e2] :n "bar"}]
              [:hide ::pop]
              [::l "Renamed 2 occurrences of 'foo' to 'bar'."]]
             @log))
      (reset! log [])
      (with-redefs [edit/rename (fn [_ _] (throw (ex-info "boom" {})))]
        (is (= "; rename: boom" (#'win-help/do-rename-to! ed ::pop "bar" ::l))))
      (is (= [[::l "; rename: boom"]] @log)))))

(deftest complete-and-repl-complete-test
  (let [log (atom [])
        snap* (atom nil)
        e (let [hist (atom {:items ["(a)" "(b)"]})]
            #_{:clj-kondo/ignore [:missing-protocol-method]}
            (reify ev/Evaluator
              (history [_] hist)))]
    (with-redefs [editor/snapshot (fn [ed] [:snap ed])
                  cview/input-snapshot (fn [_cv] @snap*)
                  completion/open! (fn [c snap] (swap! log conj [:open c snap]))
                  win-status/set-status! (fn [l text] (swap! log conj [l text]))]
      (#'win-help/do-complete! ::ed ::c ::l)
      (is (= [[:open ::c [:snap ::ed]]] @log))
      (reset! log [])
      (is (nil? (#'win-help/do-repl-complete! ::cv (atom e) ::rc ::l)))
      (is (= [] @log))
      (reset! snap* {:text "x" :caret 1})
      (#'win-help/do-repl-complete! ::cv (atom e) ::rc ::l)
      (is (= [[:open ::rc {:text "x" :caret 1 :extra-context "(a)\n(b)"}]] @log))
      (reset! log [])
      (with-redefs [completion/open! (fn [_ _] (throw (ex-info "boom" {})))]
        (#'win-help/do-complete! ::ed ::c ::l)
        (#'win-help/do-repl-complete! ::cv (atom e) ::rc ::l))
      (is (= [[::l "; complete: boom"] [::l "; complete: boom"]] @log)))))

(deftest wire-completion-test
  (let [log (atom [])
        fns (atom nil)
        moved (atom nil)
        lost (atom nil)
        cf (fn [] :complete)]
    (with-redefs [completion/handle-key! (fn [c k s]
                                           (if (= 1 k)
                                             (throw (ex-info "boom" {}))
                                             [:handled c k s]))
                  completion/refine! (fn [_] (throw (ex-info "late" {})))
                  completion/close! (fn [c] (swap! log conj [:close c]))
                  win-status/set-status! (fn [l text] (swap! log conj [l text]))]
      (#'win-help/wire-completion! ::c cf ::l
                                   (fn [p] (swap! log conj [:register p]))
                                   (fn [m] (reset! fns m))
                                   (fn [f] (reset! moved f))
                                   (fn [f] (reset! lost f)))
      (is (= [[:register ::c]] @log))
      (is (identical? cf (:complete! @fns)))
      (is (= [:handled ::c 2 0] ((:handle-key! @fns) 2 0)))
      (is (= 1 ((:handle-key! @fns) 1 0)))
      (@moved :any :args)
      (@lost)
      (is (= [[:register ::c] [::l "; complete: boom"] [::l "; complete: late"] [:close ::c]] @log)))))

(deftest jump-to-decisions-test
  (let [log (atom [])
        seen (atom nil)
        ctx {:ed ::ed :status-label ::l :help-pop ::hp
             :close-popovers-fn (fn [p] (swap! log conj [:close p]))
             :path (atom "/p.clj") :sel* (atom {:project-dir "/proj"})
             :open-file! (fn [_])}
        jump! (fn [target]
                (reset! log [])
                (#'win-help/jump-to! ctx "f" target)
                @log)]
    (with-redefs [win-help/place-caret-and-scroll! (fn [ed off] (swap! log conj [:caret ed off]))
                  win-help/open-in-tab! (fn [_ctx sym loc] (swap! log conj [:in-tab sym loc]))
                  win-help/show-source! (fn [_ctx loc] (swap! log conj [:source loc]))
                  help-popover/show-doc! (fn [pop text] (swap! log conj [:show-doc pop text]))
                  help/doc-text (fn [s d] [:doc s d])
                  source/location (fn [doc opts] (reset! seen opts) (:loc doc))
                  source/same-path? =
                  editor/editor-text (constantly "a\nb\nc")
                  win-status/set-status! (fn [l text] (swap! log conj [l text]))]
      (is (= [[:caret ::ed 5] [::l "Jumped to f."]]
             (jump! {:kind :buffer :offset 5})))
      (is (= [[:caret ::ed 2] [::l "Jumped to f."]]
             (jump! {:kind :external :doc {:loc {:path "/p.clj" :line 2}}})))
      (is (= {:project-dir "/proj"} @seen))
      (let [loc {:path "/q.clj" :line 1 :editable? true}]
        (is (= [[:close ::hp] [:in-tab "f" loc]]
               (jump! {:kind :external :doc {:loc loc}}))))
      (let [loc {:path "/r.jar" :line 1}]
        (is (= [[:close ::hp] [:source loc]]
               (jump! {:kind :external :doc {:loc loc}}))))
      (is (= [[:close ::hp] [:show-doc ::hp [:doc "f" {:arglists "x"}]]]
             (jump! {:kind :external :doc {:arglists "x"}})))
      (is (= [[::l "No definition found for f."]]
             (jump! nil))))))

(deftest jump-back-test
  (let [log (atom [])]
    (with-redefs [win-help/place-caret-and-scroll! (fn [ed off] (swap! log conj [:caret ed off]))
                  editor/editor-text (constantly "abcde")
                  editor/focus! (fn [ed] (swap! log conj [:focus ed]))
                  win-status/set-status! (fn [l text] (swap! log conj [l text]))]
      (is (false? (#'win-help/jump-back! {:jump-back* (atom []) :status-label ::l})))
      (is (= [] @log))
      (let [jump-back* (atom [{:id 1 :path "/a.clj" :offset 9} {:id 2 :path nil :offset 3}])]
        (is (true? (#'win-help/jump-back! {:jump-back* jump-back*
                                           :tab-open? #(= % 1)
                                           :select-tab! (fn [id] (swap! log conj [:select id]))
                                           :active-tab (fn [] {:ed ::ed})
                                           :status-label ::l})))
        (is (= [[:select 1] [:caret ::ed 5] [:focus ::ed] [::l "Back."]] @log))
        (is (= [] @jump-back*)))
      (reset! log [])
      (let [jump-back* (atom [{:id 3 :path "/gone.clj" :offset 0}])]
        (is (true? (#'win-help/jump-back! {:jump-back* jump-back*
                                           :tab-open? (constantly false)
                                           :open-file! (fn [p] (swap! log conj [:open p]))
                                           :active-tab (fn [] {:path (atom nil)})
                                           :status-label ::l})))
        (is (= [[:open "/gone.clj"] [::l "Nothing to go back to: those tabs are closed."]] @log))))))

(deftest build-adds-the-help-bindings-test
  (let [ev* (atom #_{:clj-kondo/ignore [:missing-protocol-method]}
             (reify ev/Evaluator
               (prompt-ns [_] 'user.x)
               (complete [_ p t pos x] [:complete p t pos x])
               (lookup [_ s t p] [:lookup s t p])))
        r (atom ::r1)
        tc (atom ::tc1)
        opened (atom [])
        in {:ev* ev* :status-label ::l :sp ::sp :sel* ::sel
            :active? (fn [t] [:active t]) :active-tab ::at
            :open-file! (fn [c p] (swap! opened conj [c p]))
            :tab-ctx* tc :right* r :x 1}
        w (win-help/build in)]
    (is (= (into (set (keys in))
                 #{:popovers :close-popovers-fn :register-popover-fn :source-ctx
                   :server-ev :completion-ns-fn :completion-opts})
           (set (keys w))))
    (is (= [] @(:popovers w)))
    ((:register-popover-fn w) ::p)
    (is (= [::p] @(:popovers w)))
    (let [log (atom [])]
      (with-redefs [popover/open? #(= % ::p)
                    popover/hide! (fn [p] (swap! log conj [:hide p]))]
        (is (true? ((:close-popovers-fn w))))
        (is (= [[:hide ::p]] @log))
        (is (false? ((:close-popovers-fn w) ::p)))))
    (reset! r ::r2)
    (reset! tc ::tc2)
    (let [ctx ((:source-ctx w) {:ed ::ed :help-pop ::hp :path ::path})]
      (is (= {:ed ::ed :ev* ev* :status-label ::l :help-pop ::hp
              :close-popovers-fn (:close-popovers-fn w) :sp ::sp :right ::r2
              :path ::path :sel* ::sel :active-tab ::at}
             (dissoc ctx :active? :open-file! :jump-back*)))
      (is (= [:active {:ed ::ed :help-pop ::hp :path ::path}] ((:active? ctx))))
      ((:open-file! ctx) "/p")
      (is (= [[::tc2 "/p"]] @opened))
      (is (= [] @(:jump-back* ctx)))
      (is (identical? (:jump-back* ctx) (:jump-back* ((:source-ctx w) {})))))
    (is (= 'user.x ((:completion-ns-fn w))))
    (is (nil? ((:server-ev w))))
    (let [opts (:completion-opts w)
          log (atom [])]
      (is (= #{:ns-fn :candidates-fn :doc-fn :server-ev :close-others! :on-error}
             (set (keys opts))))
      (is (identical? (:completion-ns-fn w) (:ns-fn opts)))
      (is (identical? (:server-ev w) (:server-ev opts)))
      (is (identical? (:close-popovers-fn w) (:close-others! opts)))
      (is (= [:complete "ma" "t" 1 "x"] ((:candidates-fn opts) "ma" "t" 1 "x")))
      (is (= [:lookup "map" "t" 1] ((:doc-fn opts) "map" "t" 1)))
      (with-redefs [win-status/set-status! (fn [l m] (swap! log conj [l m]))]
        ((:on-error opts) "m"))
      (is (= [[::l "m"]] @log)))))

(deftest build-source-keys-wires-its-fns-test
  (let [log (atom [])
        lg (fn [e] (swap! log conj e))
        at (fn [] {:ed ::ed :id 3})
        tc (atom ::tc1)
        in {:source-ctx (fn [t] {:ctx-of t}) :active-tab at :right ::right
            :sp ::sp :win ::win :tabs ::tabs
            :open-file! (fn [c p] (lg [:open c p]))
            :select-tab! (fn [c id] (lg [:select c id]))
            :tab-by-id (fn [ts id] (lg [:by-id ts id]) (= id 3))
            :tab-ctx* tc :x 1}]
    (with-redefs [win-help/do-source-jump! (fn [ctx] (lg [:jump ctx]))
                  win-help/close-source-panel! (fn [r ed] (lg [:close r ed]))
                  win-help/do-source-open! (fn [m]
                                             (lg [:src-open (dissoc m :open-file!)])
                                             ((:open-file! m) "/p"))
                  win-help/jump-back! (fn [ctx]
                                        (lg [:back (dissoc ctx :select-tab! :tab-open?)])
                                        ((:select-tab! ctx) 3)
                                        ((:tab-open? ctx) 3))
                  source-pane/back! (fn [sp] (lg [:panel-back sp]))
                  source-pane/focused? (fn [sp win] (lg [:focused sp win]))]
      (let [w (win-help/build-source-keys in)]
        (reset! tc ::tc2)
        (is (= (into (set (keys in))
                     #{:source-jump-fn :source-close-fn :source-back-fn
                       :source-open-fn :jump-back-fn :source-keys})
               (set (keys w))))
        ((:source-jump-fn w))
        ((:source-close-fn w))
        ((:source-back-fn w))
        ((:source-open-fn w))
        ((:jump-back-fn w))
        ((:focused? (:source-keys w)))
        (is (= [[:jump {:ctx-of {:ed ::ed :id 3}}]
                [:close ::right ::ed]
                [:panel-back ::sp]
                [:src-open {:sp ::sp :right ::right :active-tab at}]
                [:open ::tc2 "/p"]
                [:back {:ctx-of {:ed ::ed :id 3}}]
                [:select ::tc2 3]
                [:by-id ::tabs 3]
                [:focused ::sp ::win]]
               @log))
        (is (= {:close! (:source-close-fn w) :back! (:source-back-fn w)
                :open! (:source-open-fn w) :jump-back! (:jump-back-fn w)}
               (dissoc (:source-keys w) :focused?)))))))
