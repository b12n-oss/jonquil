(ns jonquil.gtk.window.ui-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.ui :as win-ui]))

(def ^:private ui-keys
  "The ui map's 118 core keys (start!'s docstring names them with the parts' :ui keys)."
  #{:activate-menu-action!* :activate-quit-action!* :active-tab* :alert-dialog-count :alert-pending? :alert-release!
    :app :clear-console!* :clear-last-output!* :close-choice! :close-inspector!* :close-popovers!* :close-tab!
    :close-tab-choice! :closing? :code-css* :complete!* :complete-alert-for-test! :completion :console :cycle-theme!*
    :dialect-label :dismiss-alerts!* :editor :evaluator* :file-tree :file-tree-visible?* :files-btn :find-bar
    :full-doc-popover :h-paned :handle-window-key! :help-popover :hint-label :inspect-form!* :inspect-last!* :inspector
    :inspector-last-args* :inspector-state* :jump!* :jump-back!* :line-col-label :load-settings!* :mcp-eval!* :mcp-run!*
    :mcp-run-selection!* :new-tab!* :notebook :open-file! :open-path! :open-paths! :palette :palette-ctx-keys
    :panel-focus!* :parinfer!* :parinfer-label :part-owners* :pending-alerts* :pending-label :plugin-history-pane
    :plugin-list-pane :poll-appearance!* :poll-arm-count* :poll-armed?* :quick-doc!* :recent-panels*
    :register-alert-choice-for-test! :register-popover!* :rename!* :rename-popover :rename-to!*
    :repl-activate-menu-action!* :repl-complete!* :repl-completion :request-close!* :reset-check :restore-session!*
    :review-pane :right-slot :run!* :run-form!* :run-selection!* :save-session!* :save-to! :select-tab! :selection*
    :session :session-live!* :set-theme!* :settings-path!* :settings-path* :simulate-folder-result!
    :simulate-open-result! :simulate-save-result! :source-back!* :source-close!* :source-jump!* :source-open!*
    :source-pane :status-label :stop!* :suppress-close? :system-dark-fn!* :tabs* :theme-choice* :theme-name*
    :toggle-file-tree!* :toggle-focus!* :toolbar :trash-choice! :tree-menu!* :tree-menu-state* :tree-paned
    :tree-press!* :tree-submit!* :unread-label :window :zoom!*})

(defn- window-map
  "A window map for ui-map: every key it reads, as plain values and atoms."
  []
  {:tabs (atom [{:ed ::ed :fb ::fb :help-pop ::help-pop :full-doc-pop ::full-doc-pop :rename-pop ::rename-pop
                 :completion ::completion :palette ::palette :menu-group ::menu-group
                 :actions {:run-form! ::run-form :quick-doc! ::quick-doc :jump! ::jump :rename! ::rename
                           :rename-to! ::rename-to :complete! ::complete}}])
   :part-contribution {:ui {:part-key ::part} :owners {:ui {:part-key :p}}}
   :active-tab ::active-tab :app ::app :arm-count (atom 0) :clear-console-fn ::clear-console :clear-last-fn ::clear-last
   :close-popovers-fn ::close-popovers :closing? ::closing :confirmed-close? ::confirmed :current-open-paths (atom nil)
   :cv ::cv :cycle-theme-fn ::cycle-theme :dialect-label ::dialect-label :ev* (atom ::ev) :files-btn ::files-btn
   :folder-result* (atom nil) :h-paned ::h-paned :hint-label ::hint-label :hp ::hp :inspect-form-fn ::inspect-form
   :inspect-last-fn ::inspect-last :inspector-last-args (atom nil) :inspector-state (atom nil) :jump-back-fn ::jump-back
   :line-col-label ::line-col :lp ::lp :notebook ::notebook :on-inspector-close ::on-inspector-close :open-btn ::open-btn
   :pane ::pane :panel-focus-fn ::panel-focus :parinfer-fn ::parinfer :parinfer-label ::parinfer-label
   :pending-label ::pending :poll-armed? (atom false) :provider ::provider :register-popover-fn ::register-popover
   :repl-activate-menu-action-fn ::repl-activate :repl-complete-fn ::repl-complete :repl-completion ::repl-completion
   :reset-chk ::reset-chk :right {:recent-panels ::recent} :rp ::rp :run-btn ::run-btn :run-fn ::run
   :run-selection-fn ::run-selection :save-btn ::save-btn :sel* (atom ::sel) :session ::session :set-theme-fn ::set-theme
   :settings-path* (atom "/s.edn") :slot ::slot :source-back-fn ::source-back :source-close-fn ::source-close
   :source-jump-fn ::source-jump :source-open-fn ::source-open :sp ::sp :status-label ::status-label :stop-btn ::stop-btn
   :stop-fn ::stop :suppress-close? ::suppress :system-dark-fn* (atom nil) :tab-ctx* (atom nil) :theme-choice (atom :auto)
   :theme-ctx ::theme-ctx :theme-name (atom :light) :toggle-focus-fn ::toggle-focus :tree-pane ::tree-pane
   :tree-paned ::tree-paned :unread-label ::unread-label :win ::win :window-key-args {:a 1 :b 2}
   :window-key-fn ::window-key :zoom-fn ::zoom})

(deftest ui-topics-are-disjoint-and-pinned-test
  (let [w (window-map)
        u (merge w (select-keys (first @(:tabs w)) [:ed :fb :help-pop :full-doc-pop :rename-pop :completion :palette
                                                    :actions]))
        topics [#'win-ui/window-keys #'win-ui/tree-keys #'win-ui/tab-keys #'win-ui/run-keys #'win-ui/theme-keys
                #'win-ui/session-keys #'win-ui/help-keys #'win-ui/panel-keys #'win-ui/close-keys]
        key-sets (map (fn [f] (set (keys (f u)))) topics)]
    (is (= (count ui-keys) (reduce + (map count key-sets))))
    (is (= ui-keys (reduce into #{} key-sets)))))

(deftest ui-map-merges-the-topics-and-the-parts-test
  (let [log (atom [])
        w (window-map)
        ui (win-ui/ui-map w)]
    (is (= (conj ui-keys :part-key) (set (keys ui))))
    (is (= ::part (:part-key ui)))
    (is (= [::ed ::cv ::session ::status-label ::win ::fb ::palette ::completion ::run-form]
           (map ui [:editor :console :session :status-label :window :find-bar :palette :completion :run-form!*])))
    (is (= #{:a :b} (:palette-ctx-keys ui)))
    (is (= ::recent ((:recent-panels* ui))))
    (reset! (:folder-result* w) (fn [r] (swap! log conj [:folder r])))
    ((:simulate-folder-result! ui) ::r)
    (with-redefs [g/g-action-group-activate-action (fn [group n p] (swap! log conj [:activate group n (= ffi/null p)]))]
      ((:activate-menu-action!* ui) "jump"))
    (is (= [[:folder ::r] [:activate ::menu-group "jump" true]] @log))
    (is (thrown? Exception (win-ui/ui-map (assoc w :part-contribution {:ui {:editor ::other}
                                                                       :owners {:ui {:editor :p}}}))))))
