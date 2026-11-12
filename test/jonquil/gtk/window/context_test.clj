(ns jonquil.gtk.window.context-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.parts :as gtk-parts]
            [jonquil.gtk.window.context :as win-context]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.inspector :as win-inspector]
            [jonquil.gtk.window.keys :as win-keys]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.parts :as parts]))

(deftest build-key-fns-makes-the-command-fns-test
  (let [log (atom []) l (fn [x] (swap! log conj x))
        tab (atom {:ed ::ed :path ::path :fb ::fb})
        ctx* (atom ::ctx)
        in {:cv ::cv :win ::win :active-tab (fn [] @tab) :ev* ::ev* :sel* ::sel* :status-label ::sl
            :right ::right :pane ::pane :inspector-state ::state :inspecting? ::inspecting? :inspect-queued ::queued
            :inspector-last-args ::last-args :on-view ::on-view :tab-ctx* ctx* :x 1}
        w (win-context/build-key-fns in)]
    (is (= (into (set (keys in))
                 #{:open-fn :save-fn :save-as-fn :inspect-last-ctx :inspect-last-fn :inspect-form-fn
                   :close-inspector-fn :toggle-focus-fn :clear-console-fn :clear-last-fn :open-find-fn
                   :open-replace-fn})
           (set (keys w))))
    (is (= {:ev* ::ev* :right ::right :pane ::pane :inspector-state ::state :inspecting? ::inspecting?
            :inspector-last-args ::last-args :on-view ::on-view :status-label ::sl :start [:start {}]}
           (:inspect-last-ctx w)))
    (with-redefs [win-files/do-open! (fn [cv win open!] (l [:open cv win]) (open! "/p.clj"))
                  win-tabs/open-file! (fn [c p] (l [:open-file c p]))
                  win-files/do-save! (fn [ed cv win path] (l [:save ed cv win path]))
                  win-files/do-save-as! (fn [ed cv win path] (l [:save-as ed cv win path]))
                  win-inspector/inspect-last! (fn [c] (l [:inspect-last (identical? c (:inspect-last-ctx w))]))
                  win-inspector/inspect-form! (fn [m] (l [:inspect-form (dissoc m :inspect-last-fn)
                                                          (identical? (:inspect-last-fn m) (:inspect-last-fn w))]))
                  win-inspector/close-inspector-panel! (fn [r ed] (l [:close-inspector r ed]))
                  win-keys/do-toggle-focus! (fn [win ed cv] (l [:toggle-focus win ed cv]))
                  win-keys/do-clear-console! (fn [cv] (l [:clear-console cv]))
                  win-keys/do-clear-last-output! (fn [cv] (l [:clear-last cv]))
                  find-bar/open! (fn [fb mode] (l [:find fb mode]))]
      ((:open-fn w))
      (reset! ctx* ::ctx2)
      (reset! tab {:ed ::ed2 :path ::path2 :fb ::fb2})
      ((:open-fn w))
      ((:save-fn w))
      ((:save-as-fn w))
      ((:inspect-last-fn w))
      ((:inspect-form-fn w))
      ((:close-inspector-fn w))
      ((:toggle-focus-fn w))
      ((:clear-console-fn w))
      ((:clear-last-fn w))
      ((:open-find-fn w))
      ((:open-replace-fn w)))
    (is (= [[:open ::cv ::win] [:open-file ::ctx "/p.clj"]
            [:open ::cv ::win] [:open-file ::ctx2 "/p.clj"]
            [:save ::ed2 ::cv ::win ::path2] [:save-as ::ed2 ::cv ::win ::path2]
            [:inspect-last true]
            [:inspect-form {:tab {:ed ::ed2 :path ::path2 :fb ::fb2} :cv ::cv :ev* ::ev* :sel* ::sel* :status-label ::sl
                            :inspecting? ::inspecting? :inspect-queued ::queued}
             true]
            [:close-inspector ::right ::ed2] [:toggle-focus ::win ::ed2 ::cv] [:clear-console ::cv] [:clear-last ::cv]
            [:find ::fb2 :find] [:find ::fb2 :replace]]
           @log))))

(deftest build-parts-installs-the-parts-with-wctx-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) got (atom nil)
        ctx* (atom ::ctx) panels* (atom nil)
        in {:win ::win :status-label ::sl :cv ::cv :ev* ::ev* :sel* ::sel* :tabs ::tabs :active-tab ::active-tab
            :settings-path* ::settings :bang* ::bang :recent-tabs ::recent :right ::right :tree-pane ::tree-pane
            :tree-paned ::tree-paned :paned ::paned :files-btn ::files-btn :panel-focus-fn ::panel-focus
            :unread-label ::unread :plugins-on* ::plugins-on :rp ::rp :lp ::lp :hp ::hp :pending-label ::pending
            :inspect-last-ctx {:start [:start {}] :ev* ::ev*} :source-ctx ::source-ctx :part-panels* panels*
            :tab-ctx* ctx* :x 1}
        w (with-redefs [parts/install (fn [installs wctx err opts]
                                        (reset! got {:installs installs :wctx wctx :err err :opts opts})
                                        {:panels {:p ::panel} :fns {}})]
            (win-context/build-parts in))
        {:keys [installs wctx err opts]} @got]
    (is (= (into (set (keys in)) #{:part-error! :part-contribution}) (set (keys w))))
    (is (= {:panels {:p ::panel} :fns {}} (:part-contribution w)))
    (is (= {:p ::panel} @panels*))
    (is (identical? gtk-parts/installs installs))
    (is (= {:specs parts/specs} opts))
    (is (identical? (:part-error! w) err))
    (is (= #{:active-tab :alert-pending? :ask-lookup! :bang* :contain-click :cv :ev* :file-name :files-btn
             :hide-right-panel! :inspect! :jump-to! :make-eval-opts :mcp-eval! :new-tab! :on-saved :open-file!
             :open-in-tab! :paned :panel-focus-fn :pending-label :place-caret-and-scroll! :plugin-console
             :plugin-history-pane :plugin-history-shown :plugin-list-pane :plugin-reviews-changed :plugin-step!
             :plugins-on* :recent-tabs :refresh-labels! :refuse-foreign! :register-status-log! :rerun-failure!
             :review-pane :right :sel* :select-tab! :set-status! :settings-path* :show-alert! :show-right-panel!
             :show-source! :showing-panel :source-ctx :status-label :tab-by-id :tab-ctx* :tabs :tree-pane
             :tree-paned :unread-label :win}
           (set (keys wctx))))
    (is (= [::win ::sl ::cv ::ev* ::sel* ::tabs ::active-tab ::settings ::bang ::recent ::right ::tree-pane
            ::tree-paned ::paned ::files-btn ::panel-focus ::unread ::plugins-on ::rp ::lp ::hp ::pending
            ::source-ctx]
           (map wctx [:win :status-label :cv :ev* :sel* :tabs :active-tab :settings-path* :bang* :recent-tabs :right
                      :tree-pane :tree-paned :paned :files-btn :panel-focus-fn :unread-label :plugins-on* :review-pane
                      :plugin-list-pane :plugin-history-pane :pending-label :source-ctx])))
    (is (identical? ctx* (:tab-ctx* wctx)))
    (is (and (identical? win-tabs/tab-by-id (:tab-by-id wctx)) (identical? win-tabs/select-tab! (:select-tab! wctx))
             (identical? win-files/on-saved (:on-saved wctx)) (identical? win-run/mcp-eval! (:mcp-eval! wctx))))
    (with-redefs [win-status/set-status! (fn [lab s] (l [:status lab s]))
                  win-status/contain-click (fn [lab where f] (l [:contain lab where]) f)
                  win-status/register-status-log! (fn [lab f] (l [:log lab (fn? f)]))
                  win-tabs/open-file! (fn [c p] (l [:open-file c p]))
                  win-tabs/refresh-labels! (fn [c] (l [:refresh c]))
                  win-inspector/inspect-last! (fn [c] (l [:inspect c]))
                  win-run/rerun-failure! (fn [m] (l [:rerun m]))]
      (err :p (ex-info "boom" {}))
      ((:set-status! wctx) "hi")
      ((:contain-click wctx) "run" identity)
      ((:register-status-log! wctx) identity)
      (reset! ctx* ::ctx2)
      ((:open-file! wctx) "/p.clj")
      ((:refresh-labels! wctx))
      ((:inspect! wctx) [:last {}])
      ((:rerun-failure! wctx) ::failure ::tab))
    (is (= [[:status ::sl "; part :p: boom"] [:status ::sl "hi"] [:contain ::sl "run"] [:log ::sl true]
            [:open-file ::ctx2 "/p.clj"] [:refresh ::ctx2] [:inspect {:start [:last {}] :ev* ::ev*}]
            [:rerun {:failure ::failure :tab ::tab :cv ::cv :ev* ::ev* :sel* ::sel* :status-label ::sl}]]
           @log))))

(deftest build-console-menu-makes-the-menu-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) got (atom nil)
        complete (fn [] :complete) clear-last (fn [] :clear-last)
        in {:cv {:view ::view} :repl-complete-fn complete :clear-last-fn clear-last :status-label ::sl
            :part-contribution {:fns {:result-inspect-fn ::ri :result-copy-fn ::rc :result-define-fn ::rd
                                      :result-jump-fn ::rj :result-doc-fn ::rdoc :other-fn ::other}}
            :x 1}
        w (with-redefs [cmd-registry/menu-items (fn [menu fns] {:menu menu :fns fns})
                        win-keys/install-context-menu! (fn [view items say] (reset! got [view items say]) ::group)]
            (win-context/build-console-menu in))
        [view items say] @got]
    (is (= (into (set (keys in)) #{:repl-activate-menu-action-fn}) (set (keys w))))
    (is (= ::view view))
    (is (= {:menu cmd-registry/console-menu
            :fns {:complete! complete :clear-last-fn clear-last :result-inspect-fn ::ri :result-copy-fn ::rc
                  :result-define-fn ::rd :result-jump-fn ::rj :result-doc-fn ::rdoc}}
           items))
    (with-redefs [win-status/set-status! (fn [lab s] (l [:status lab s]))
                  g/g-action-group-activate-action (fn [group name _] (l [:activate group name]))]
      (say "; no form")
      ((:repl-activate-menu-action-fn w) "copy-result"))
    (is (= [[:status ::sl "; no form"] [:activate ::group "copy-result"]] @log))))

(deftest build-keys-makes-the-window-key-args-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) got (atom nil)
        ctx* (atom ::ctx) recent (atom [1])
        fns (into {} (for [k [:panel-focus-fn :hints-toggle-fn :run-changed-fn :run-fn :stop-fn :open-fn :save-fn
                              :save-as-fn :close-fn :run-selection-fn :toggle-focus-fn :clear-console-fn :clear-last-fn
                              :zoom-fn :evaluating-fn :open-find-fn :open-replace-fn :cycle-theme-fn :close-popovers-fn
                              :inspect-last-fn :inspect-form-fn :close-inspector-fn :new-tab-fn :step-tab-fn
                              :digit-tab-fn :parinfer-fn]]
                       [k (fn [] k)]))
        in (merge fns {:tabs ::tabs :recent-tabs recent :status-label ::sl :win ::win :active-tab ::active-tab
                       :pane ::pane :source-keys ::source-keys :part-contribution {:fns {:part-fn ::part-fn}}
                       :tab-ctx* ctx* :x 1})
        w (with-redefs [win-keys/on-window-key (fn [a] (reset! got a) ::key-fn)]
            (win-context/build-keys in))
        a (:window-key-args w)]
    (is (= (into (set (keys in)) #{:window-key-args :window-key-fn}) (set (keys w))))
    (is (= ::key-fn (:window-key-fn w)))
    (is (identical? a @got))
    (is (= #{:active-tab :clear-console-fn :clear-last-fn :close-fn :close-inspector-fn :close-popovers-fn
             :digit-tab-fn :evaluating? :hints-toggle-fn :inspect-form-fn :inspect-last-fn :new-tab-fn :open-find-fn
             :open-fn :open-replace-fn :pane :panel-focus-fn :parinfer-fn :run-changed-fn :run-fn :run-selection-fn
             :save-as-fn :save-fn :source-keys :status-label :step-tab-fn :stop-fn :switch-tab-fn :tab-back-fn
             :theme-cycle-fn :toggle-focus-fn :win :zoom-fn :part-fn}
           (set (keys a))))
    (is (every? (fn [k] (identical? (fns k) (a k)))
                (remove #{:evaluating-fn :cycle-theme-fn} (keys fns))))
    (is (and (identical? (:evaluating-fn fns) (:evaluating? a)) (identical? (:cycle-theme-fn fns) (:theme-cycle-fn a))))
    (is (= [::win ::active-tab ::pane ::sl ::source-keys ::part-fn]
           (map a [:win :active-tab :pane :status-label :source-keys :part-fn])))
    (with-redefs [win-tabs/tab-by-id (fn [tabs id] (l [:by-id tabs id]) (= 2 id))
                  win-tabs/select-tab! (fn [c id] (l [:select c id]))
                  win-status/set-status! (fn [lab s] (l [:status lab s]))]
      ((:switch-tab-fn a) 2)
      ((:switch-tab-fn a) 3)
      ((:tab-back-fn a))
      (reset! recent [1 2])
      (reset! ctx* ::ctx2)
      ((:tab-back-fn a)))
    (is (= [[:by-id ::tabs 2] [:select ::ctx 2] [:by-id ::tabs 3] [:status ::sl "; Only one tab is open."]
            [:select ::ctx2 2]]
           @log))))
