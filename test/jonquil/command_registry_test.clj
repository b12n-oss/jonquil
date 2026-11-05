(ns jonquil.command-registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]))

(def ^:private ctx-fns
  [:run-fn :open-fn :save-as-fn :save-fn :close-fn :toggle-tree-fn :run-selection-fn
   :clear-last-fn :clear-console-fn :open-find-fn :open-replace-fn :zoom-fn
   :digit-tab-fn :new-tab-fn :theme-cycle-fn :inspect-form-fn :inspect-last-fn :step-tab-fn
   :open-palette-fn :reload-plugins-fn :review-proposals-fn :plugins-list-fn :parinfer-fn
   :describe-key-fn :key-bindings-fn :recent-keys-fn
   :locations-uses-fn :locations-definitions-fn :locations-matches-fn :locations-step-fn
   :locations-project-usages-fn :world-save-fn :world-restore-fn :world-restore-earlier-fn
   :time-form-fn :tab-back-fn :show-frames-fn :locals-mode-fn
   :retry-fn :edit-retry-fn :define-retry-fn :break-fn :continue-fn :run-changed-fn :trace-toggle-fn :trace-show-fn :trace-clear-fn :trace-untrace-all-fn :result-inspect-fn :result-copy-fn :result-define-fn :result-jump-fn :panel-focus-fn])

(defn- recording-ctx
  "A ctx whose every fn records [fn-key & args] into calls."
  [calls]
  (into {} (for [k ctx-fns] [k (fn [& args] (swap! calls conj (into [k] args)) nil)])))

(def ^:private expected
  {:run/run [:run-fn] :file/open [:open-fn] :file/save-as [:save-as-fn] :file/save [:save-fn]
   :tab/close [:close-fn] :tree/toggle [:toggle-tree-fn] :run/selection [:run-selection-fn]
   :console/clear-last-output [:clear-last-fn] :console/clear [:clear-console-fn]
   :find/open [:open-find-fn] :find/open-replace [:open-replace-fn]
   :view/zoom-in [:zoom-fn :in] :view/zoom-out [:zoom-fn :out] :view/zoom-reset [:zoom-fn :reset]
   :tab/new [:new-tab-fn] :view/cycle-theme [:theme-cycle-fn]
   :inspect/form [:inspect-form-fn] :inspect/last [:inspect-last-fn]
   :tab/next [:step-tab-fn 1] :tab/previous [:step-tab-fn -1]
   :palette/open [:open-palette-fn]
   :plugins/reload [:reload-plugins-fn]
   :plugins/review [:review-proposals-fn]
   :plugins/list [:plugins-list-fn]
   :parinfer/smart [:parinfer-fn :smart] :parinfer/indent [:parinfer-fn :indent]
   :parinfer/paren [:parinfer-fn :paren] :parinfer/off [:parinfer-fn :off]
   :parinfer/next-mode [:parinfer-fn :next]
   :help/describe-key [:describe-key-fn] :help/key-bindings [:key-bindings-fn]
   :help/recent-keys [:recent-keys-fn]
   :locations/uses [:locations-uses-fn] :locations/definitions [:locations-definitions-fn]
   :locations/matches [:locations-matches-fn]
   :locations/usages-in-project [:locations-project-usages-fn]
   :world/save [:world-save-fn] :world/restore [:world-restore-fn]
   :world/restore-earlier [:world-restore-earlier-fn]
   :locations/next [:locations-step-fn 1] :locations/previous [:locations-step-fn -1]
   :run/time-form [:time-form-fn] :tab/back [:tab-back-fn]
   :debug/show-frames [:show-frames-fn] :debug/locals-mode [:locals-mode-fn]
   :debug/break-on-error [:break-fn] :debug/continue [:continue-fn]
   :run/changed [:run-changed-fn]
   :trace/toggle [:trace-toggle-fn] :trace/show [:trace-show-fn]
   :trace/clear [:trace-clear-fn] :trace/untrace-all [:trace-untrace-all-fn]
   :result/inspect [:result-inspect-fn] :result/copy [:result-copy-fn]
   :result/define [:result-define-fn] :result/jump [:result-jump-fn]
   :restart/retry [:retry-fn] :restart/edit [:edit-retry-fn] :restart/define [:define-retry-fn]
   :panel/inspector [:panel-focus-fn :inspector] :panel/source [:panel-focus-fn :source]
   :panel/locations [:panel-focus-fn :locations] :panel/frames [:panel-focus-fn :debugger]
   :panel/trace [:panel-focus-fn :trace] :panel/plugins [:panel-focus-fn :plugins]})

(deftest each-command-calls-its-fn-test
  (is (= (into (conj (set (keys expected)) :tab/select)
               [:find/next :find/previous :focus/toggle :source/open-here
                :find/bar-key :popover/close-all :inspector/close :source/close
                :tree/close-menu :tree/leave :run/stop :source/back :source/jump-back])
         (set (keys registry/window-commands)))
      "exactly the shipped window commands, the palette, plugin reload, review and list, Parinfer's five, the three key-describing commands the six locations commands and the three world commands")
  (doseq [[id call] expected]
    (let [calls (atom [])]
      (is (= :handled (registry/run-command! registry/window-commands {:command id} (recording-ctx calls))) (str id))
      (is (= [call] @calls) (str id))))
  (let [calls (atom [])]
    (is (= :handled (registry/run-command! registry/window-commands {:command :tab/select :args {:n 9}}
                                           (recording-ctx calls))))
    (is (= [[:digit-tab-fn 9]] @calls))))

(deftest every-command-is-documented-test
  (doseq [[id c] registry/window-commands]
    (is (and (string? (:doc c)) (seq (:doc c))) (str id))
    (is (= :window (:scope c)) (str id))))

(deftest commands-call-their-ctx-fns-test
  (let [calls (atom [])
        rec (fn [k] (fn [& _] (swap! calls conj k) nil))
        ctx {:find-next! (rec :find-next!) :find-prev! (rec :find-prev!)
             :toggle-focus-fn (rec :toggle-focus-fn)
             :source-keys {:open! (rec :open!)}}]
    (doseq [[id k] [[:find/next :find-next!] [:find/previous :find-prev!]
                    [:focus/toggle :toggle-focus-fn] [:source/open-here :open!]]]
      (reset! calls [])
      (is (= :handled (registry/run-command! registry/window-commands {:command id} ctx)) (str id))
      (is (= [k] @calls) (str id)))))

(deftest window-conditions-test
  (let [ctx (fn [nav? focused?] {:find-bar-navigable? (constantly nav?)
                                 :source-keys {:focused? (constantly focused?)}})]
    (is (true? ((registry/holds-fn registry/window-conditions (ctx true false)) :find-bar-navigable)))
    (is (false? ((registry/holds-fn registry/window-conditions (ctx false true)) :find-bar-navigable)))
    (is (true? ((registry/holds-fn registry/window-conditions (ctx false true)) :source-focused)))
    (is (false? ((registry/holds-fn registry/window-conditions (ctx true true)) :no-such-condition))
        "an unknown condition is false")))

(deftest unknown-command-runs-nothing-test
  (is (nil? (registry/run-command! registry/window-commands {:command :no/such} {}))))

(deftest escape-commands-answer-outcomes-test
  (let [calls (atom [])
        rec (fn [k answer] (fn [& _] (swap! calls conj k) answer))
        ctx (fn [bar popovers]
              {:find-bar-key! (rec :find-bar-key! bar)
               :close-popovers-fn (rec :close-popovers-fn popovers)
               :close-inspector-fn (rec :close-inspector-fn nil)
               :source-keys {:close! (rec :close! nil)}
               :tree-keys {:close-menu! (rec :close-menu! nil) :leave! (rec :leave! nil)}
               :stop-fn (rec :stop-fn nil)})
        run (fn [id c] (reset! calls []) [(registry/run-command! registry/window-commands {:command id} c) @calls])]
    (is (= [:handled [:find-bar-key!]] (run :find/bar-key (ctx 1 nil))) "the bar's 1 is :handled")
    (is (= [:to-gtk [:find-bar-key!]] (run :find/bar-key (ctx 0 nil))) "the bar's 0 goes to GTK")
    (is (= [:handled [:close-popovers-fn]] (run :popover/close-all (ctx nil true))))
    (is (= [:next [:close-popovers-fn]] (run :popover/close-all (ctx nil nil))) "closed nothing: the chain goes on")
    (doseq [[id k] [[:inspector/close :close-inspector-fn] [:source/close :close!]
                    [:tree/close-menu :close-menu!] [:tree/leave :leave!] [:run/stop :stop-fn]]]
      (is (= [:handled [k]] (run id (ctx nil nil))) (str id)))))

(deftest escape-conditions-test
  (let [holds (fn [ctx id] ((registry/holds-fn registry/window-conditions ctx) id))
        tree (fn [focused? menu?] {:focused? (constantly focused?) :menu-open? (constantly menu?)})]
    (is (true? (holds {:find-bar-focused? (constantly true)} :find-bar-focused)))
    (is (false? (holds {:find-bar-focused? (constantly false)} :find-bar-focused)))
    (is (true? (holds {:inspector-focused? (constantly true)} :inspector-focused)))
    (is (false? (holds {:inspector-focused? (constantly nil)} :inspector-focused)))
    (is (true? (holds {:evaluating? (constantly true)} :evaluating)))
    (is (false? (holds {:evaluating? (constantly false)} :evaluating)))
    (is (true? (holds {:tree-keys (tree true false)} :tree-focused)))
    (is (false? (holds {:tree-keys (tree false true)} :tree-focused)))
    (is (true? (holds {:tree-keys (tree false true)} :tree-menu-open)))
    (is (false? (holds {:tree-keys (tree true false)} :tree-menu-open)))
    ;; a window with no tree
    (is (false? (holds {:tree-keys nil} :tree-focused)) "no tree-keys: not focused, no throw")
    (is (false? (holds {:tree-keys nil} :tree-menu-open)) "no tree-keys: no menu, no throw")))

(deftest source-commands-test
  (let [calls (atom [])
        rec (fn [k answer] (fn [& _] (swap! calls conj k) answer))
        run (fn [id ctx] (reset! calls []) [(registry/run-command! registry/window-commands {:command id} ctx) @calls])]
    (is (= [:handled [:back!]] (run :source/back {:source-keys {:back! (rec :back! nil)}})))
    (is (= [:handled [:jump-back!]] (run :source/jump-back {:source-keys {:jump-back! (rec :jump-back! true)}})))
    (is (= [:next [:jump-back!]] (run :source/jump-back {:source-keys {:jump-back! (rec :jump-back! nil)}}))
        "nothing to go back from: the key passes on")
    ;; a window with no jump-back fn
    (is (= [:next []] (run :source/jump-back {:source-keys {}})) "no :jump-back!: :next, no throw")
    (is (= [:next []] (run :source/jump-back {})) "no source-keys at all")))

(deftest rename-condition-test
  (let [holds (fn [v] ((registry/holds-fn registry/window-conditions {:entry-focused? (constantly v)})
                       :entry-focused))]
    (is (true? (holds true)))
    (is (false? (holds nil)))))

(deftest window-answer-test
  (let [asked (atom []) ran (atom [])
        holds? (fn [rename?] (fn [id] (swap! asked conj id) (and rename? (= id :entry-focused))))
        run! (fn [answer] (fn [b] (swap! ran conj (:command b)) answer))
        bs [{:key "r" :mods #{:cmd} :command :x} {:key "Escape" :command :esc}
            {:key "Escape" :outcome :to-gtk}]
        ans (fn [kv st rename? answer]
              (reset! asked []) (reset! ran [])
              (registry/window-answer bs kv st {:holds? (holds? rename?) :run! (run! answer)}))]
    (is (= 0 (ans 0x72 4 true :handled)) "rename focused: Cmd+R is the entry's")
    (is (= [] @ran) "and nothing ran")
    (is (= [:entry-focused] @asked) "only the entry condition was asked")
    (is (= 1 (ans 0xff1b 0 true :handled)) "Escape during a rename goes on to the keymap")
    (is (= [:esc] @ran))
    (is (= [] @asked) "Escape never asks the entry condition")
    (is (= 1 (ans 0x72 4 false :handled)) ":handled is 1")
    (is (= 0 (ans 0x72 4 false :to-gtk)) ":to-gtk is 0")
    (is (= 0 (ans 0xff1b 0 false :next)) "the fixed :to-gtk is 0")
    (is (= 0 (ans 0x61 0 false :handled)) ":unmatched is 0")))

;; --- the editor's commands -------- ---------------------------------------

(deftest editor-commands-answer-outcomes-test
  (let [calls (atom [])
        rec (fn [k answer] (fn [] (swap! calls conj k) answer))
        ctx (fn [answer] {:complete! (rec :complete! answer) :toggle-comment! (rec :toggle-comment! answer)
                          :format-all! (rec :format-all! answer)
                          :format-selection! (rec :format-selection! answer)
                          :redo! (rec :redo! answer) :quick-doc! (rec :quick-doc! answer)
                          :jump! (rec :jump! answer) :rename! (rec :rename! answer)
                          :run-form! (rec :run-form! answer)
                          :indent! (rec :indent! answer) :unindent! (rec :unindent! answer)})
        run (fn [id c] (reset! calls []) [(registry/run-command! registry/editor-commands {:command id} c) @calls])]
    (is (= #{:completion/open :edit/toggle-comment :edit/format-all :edit/format-selection :edit/redo
             :help/quick-doc :help/jump :edit/rename :run/form :edit/indent :edit/unindent}
           (set (keys registry/editor-commands))))
    (doseq [[id k] [[:edit/toggle-comment :toggle-comment!] [:edit/format-all :format-all!]
                    [:edit/format-selection :format-selection!] [:edit/redo :redo!]]]
      (is (= [:handled [k]] (run id (ctx 1))) (str id " 1 is :handled"))
      (is (= [:to-gtk [k]] (run id (ctx 0))) (str id " 0 is GTK's")))
    ;; these answer :handled whatever their fn returns, nil
    ;; included (no completion or quick-doc fn registered)
    (doseq [[id k] [[:completion/open :complete!] [:help/quick-doc :quick-doc!]
                    [:help/jump :jump!] [:edit/rename :rename!] [:run/form :run-form!]
                    [:edit/indent :indent!] [:edit/unindent :unindent!]]]
      (is (= [:handled [k]] (run id (ctx nil))) (str id)))))

(deftest every-editor-command-is-documented-test
  (doseq [[id c] registry/editor-commands]
    (is (and (string? (:doc c)) (seq (:doc c))) (str id))
    (is (= :editor (:scope c)) (str id))))

(deftest editor-writable-condition-test
  (let [holds (fn [v] ((registry/holds-fn registry/editor-conditions {:writable? (constantly v)}) :writable))]
    (is (true? (holds true)))
    (is (false? (holds false)))))

;; --- Run Form and the editor's fixed stages ----------------------

(deftest editor-preedit-condition-test
  (let [holds (fn [v] ((registry/holds-fn registry/editor-conditions {:preedit-active? (constantly v)})
                       :preedit-active))]
    (is (true? (holds true)))
    (is (false? (holds false)))))

(deftest editor-answer-test
  (let [asked (atom []) ran (atom [])
        bs [{:key "r" :mods #{:cmd} :command :x}]
        ans (fn [kv {:keys [preedit? popup answer typing]}]
              (reset! asked []) (reset! ran [])
              (registry/editor-answer bs kv 4
                                      {:holds? (fn [id] (swap! asked conj id) (and preedit? (= id :preedit-active)))
                                       :run! (fn [b] (swap! ran conj (:command b)) answer)
                                       :popup-key! (fn [] (swap! ran conj :popup) popup)
                                       :typing-key! (fn [] (swap! ran conj :typing) typing)}))]
    (testing "a composing preedit yields every key, asking nothing else"
      (is (= 0 (ans 0x72 {:preedit? true :popup 1 :answer :handled :typing 1})))
      (is (= [] @ran))
      (is (= [:preedit-active] @asked)))
    (testing "the popup's 1 is final"
      (is (= 1 (ans 0x72 {:popup 1 :answer :handled :typing 1})))
      (is (= [:popup] @ran)))
    (testing "the popup's 0 or nil goes on to the keymap"
      (doseq [p [0 nil]]
        (is (= 1 (ans 0x72 {:popup p :answer :handled :typing 0})))
        (is (= [:popup :x] @ran))
        (is (= 0 (ans 0x72 {:popup p :answer :to-gtk :typing 1})))
        (is (= [:popup :x] @ran))))
    (testing "no binding matches: the typing stages answer"
      (is (= 1 (ans 0x61 {:popup 0 :typing 1})))
      (is (= [:popup :typing] @ran))
      (is (= 0 (ans 0x61 {:popup 0 :typing 0}))))))

;; --- the context menus -------- -------------------------------------------

(deftest console-commands-test
  (let [calls (atom [])
        ctx {:complete! (fn [] (swap! calls conj :complete!) nil)}]
    (is (= #{:console/complete} (set (keys registry/console-commands))))
    (is (= :handled (registry/run-command! registry/console-commands {:command :console/complete} ctx)))
    (is (= [:complete!] @calls))))

(deftest commands-ids-are-disjoint-test
  (is (= (count registry/commands)
         (+ (count registry/window-commands) (count registry/editor-commands)
            (count registry/console-commands))))
  (doseq [[id c] registry/commands]
    (is (and (string? (:doc c)) (seq (:doc c))) (str id))))

(deftest every-menu-item-is-registered-test
  (doseq [menu [registry/editor-menu registry/console-menu]]
    (doseq [{:keys [command label]} menu]
      (is (contains? registry/commands command) (str label " names " command)))
    (is (apply distinct? (map :label menu)))
    (is (apply distinct? (map :action menu)))))

;; --- commands that can pass the key on ------------------------

(deftest passes-flag-test
  ;; the commands that can answer :next or :to-gtk, so a window binding to
  ;; one may let the key reach the editor (the plugin validator reads it)
  (is (= #{:popover/close-all :source/jump-back :find/bar-key}
         (set (keep (fn [[id c]] (when (:passes? c) id)) registry/commands)))))

;; --- the palette -------- -------------------------------------------------

(deftest palette-hidden-commands-test
  (is (= #{:find/bar-key :popover/close-all :source/open-here :source/close :source/back
           :source/jump-back :tree/close-menu :tree/leave :tab/select :palette/open :console/complete}
         (set (keep (fn [[id c]] (when (false? (:palette? c)) id)) registry/commands)))))

(deftest run-command-in-test
  (let [calls (atom [])
        rec (fn [scope k] (fn [& _] (swap! calls conj [scope k]) nil))
        ctxs {:window {:run-fn (rec :window :run-fn)}
              :editor {:indent! (rec :editor :indent!)}
              :console {:complete! (rec :console :complete!)}}
        run (fn [id] (reset! calls []) [(registry/run-command-in registry/commands id ctxs) @calls])]
    (is (= [:handled [[:window :run-fn]]] (run :run/run)))
    (is (= [:handled [[:editor :indent!]]] (run :edit/indent)))
    (is (= [:handled [[:console :complete!]]] (run :console/complete)))
    (is (= [nil []] (run :no/such)))))
