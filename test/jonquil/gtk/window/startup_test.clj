(ns jonquil.gtk.window.startup-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.evaluator :as ev]
            [jonquil.file-tree :as ft]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.file-tree-pane :as file-tree-pane]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.quit :as win-quit]
            [jonquil.gtk.window.reopen :as win-reopen]
            [jonquil.gtk.window.runtime :as win-runtime]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.gtk.window.startup :as win-startup]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.gtk.window.theme :as win-theme]
            [jonquil.login-path :as login-path]
            [jonquil.parts :as parts]
            [jonquil.runtime-registry :as registry]
            [jonquil.settings :as settings]
            [jonquil.tabs :as tabs]))

(deftest connect-quit-hands-quit-the-close-map-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) got (atom nil)
        live? (atom true) restoring? (atom false)
        ctx* (atom {:id ::ctx :session-live? live? :session-restoring? restoring?})
        in {:tabs ::tabs :cv ::cv :win ::win :app ::app :confirmed-close? ::confirmed :suppress-close? ::suppress
            :closing? ::closing :popovers ::popovers :ev* ::ev* :part-contribution ::contribution
            :part-error! ::part-error :tab-ctx* ctx* :x 1}]
    (with-redefs [win-quit/connect! (fn [m] (reset! got m))
                  win-tabs/select-tab! (fn [c id] (l [:select (:id c) id]))
                  parts/run-hooks! (fn [c k args err] (l [:hooks c k args err]))
                  win-session/save-session! (fn [c] (l [:save (:id c)]))]
      (win-startup/connect-quit! in)
      (is (= #{:tabs :select! :cv :win :app :confirmed-close? :suppress-close? :closing? :popovers :ev* :save-session!}
             (set (keys @got))))
      (is (= (select-keys in [:tabs :cv :win :app :confirmed-close? :suppress-close? :closing? :popovers :ev*])
             (dissoc @got :select! :save-session!)))
      ((:select! @got) {:id 7})
      ((:save-session! @got))
      (is (and (false? @live?) (false? @restoring?)))
      (reset! restoring? true)
      ((:save-session! @got)))
    (is (= [[:select ::ctx 7] [:hooks ::contribution :on-quit [] ::part-error] [:save ::ctx]
            [:hooks ::contribution :on-quit [] ::part-error]]
           @log))))

(defn- startup-in
  "A window map for startup!: every key it reads, plain values and atoms."
  [l]
  {:part-contribution {:fns {:tree-start! (fn [m] (l [:tree-start m])) :plugins-start! (fn [] (l [:plugins-start]))}}
   :cv ::cv :status-label ::sl :dialect-label ::dl :sel* (atom ::sel) :ev* (atom ::ev) :reset-chk ::reset
   :reset-state ::reset-state :right ::right :inspector-state ::istate :active-tab (fn [] {:ed ::ed :path ::path})
   :file nil :dir "/d" :cwd "/c" :tree-pane {:root ::tree-root :state (atom {:root "/r"})} :active? (fn [_] true)
   :win ::win :line-col-label ::lc :close-popovers-fn ::cp :register-popover-fn ::rp :source-ctx ::sc
   :settings-path* (atom "/s.edn") :inspect-last-fn ::il :inspect-form-fn ::if :open-find-fn ::of
   :open-replace-fn ::or :tabs (atom []) :next-id (atom 0) :notebook ::notebook :theme-name ::tn
   :completion-opts ::co :active-id (atom nil) :popovers ::popovers :window-key-args ::wka :repl-complete-fn ::rc
   :parinfer-mode* (atom :off) :parinfer-status-fn ::ps :update-parinfer-label! (fn [] (l [:parinfer-label]))
   :part-error! ::pe :recent-tabs (atom []) :hint-label ::hl :hints-on? ::ho :folder-result* (atom nil)
   :tab-ctx* (atom nil) :current-report (atom nil) :current-open-paths (atom nil) :files-btn ::files
   :open-folder* (atom nil) :theme-ctx ::theme-ctx})

(deftest startup-runs-its-steps-in-order-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) ran (promise)
        w (startup-in l)]
    (with-redefs [win-tabs/add-tab! (fn [c m] (l [:add-tab (map? c) m]) {:id 1})
                  settings/load-settings (fn [p] (l [:settings p]) {:file-tree ::tree-settings})
                  ft/root-for (fn [m] [:root m])
                  ft/load-model (fn [r] [:model r])
                  file-tree-pane/show-model! (fn [_ m] (l [:show-model m]))
                  win-tabs/switch-page-fn (fn [c] (l [:switch-page-fn (map? c)]) (fn [& _]))
                  g/g-signal-connect-data (fn [o s & _] (l [:connect o s]) 1)
                  win-theme/load-and-apply-theme! (fn [c p] (l [:theme c p]))
                  win-runtime/show-selection! (fn [cv dl sel] (l [:show-selection cv dl sel]))
                  win-runtime/watch-connection! (fn [& args] (l [:watch (count args)]))
                  win-runtime/apply-reset-toggle! (fn [c s sel] (l [:reset-toggle c s sel]))
                  win-runtime/dialect-text (fn [sel ev] [:dialect sel ev])
                  g/gtk-label-set-text (fn [o t] (l [:label o t]))
                  win-runtime/refresh-prompt! (fn [cv _] (l [:prompt cv]))
                  win-runtime/start-and-report! (fn [cv sl ev sel] (l [:start cv sl ev sel]))
                  win-reopen/session-entry! (fn [c m] (l [:entry (map? c) m]) {:tabs []})
                  login-path/console-line (fn [] (deliver ran true) nil)
                  win-files/update-title! (fn [ed win path] (l [:title ed win path]))
                  g/gtk-window-present (fn [win] (l [:present win]))
                  editor/focus! (fn [ed] (l [:focus ed]))
                  win-session/session-snapshot (fn [_] ::snapshot)
                  parts/run-hooks! (fn [_ k args _] (l [:hooks k args]))]
      (win-startup/startup! w)
      (is (true? (deref ran 5000 :timeout))))
    (let [ctx @(:tab-ctx* w)]
      (is (= [[:add-tab true {:path nil :text ""}] [:settings "/s.edn"]
              [:parinfer-label]
              [:show-model [:model [:root {:dir "/d" :file nil :cwd "/c"}]]]
              [:tree-start {:tree-settings ::tree-settings :dir "/d"}]
              [:switch-page-fn true] [:connect ::notebook "switch-page"] [:theme ::theme-ctx "/s.edn"]
              [:show-selection ::cv ::dl ::sel] [:watch 6] [:reset-toggle ::reset ::reset-state ::sel]
              [:label ::dl [:dialect ::sel ::ev]] [:prompt ::cv] [:start ::cv ::sl ::ev ::sel]
              [:entry true {:reroot? true}] [:title ::ed ::win ::path] [:present ::win] [:focus ::ed]
              [:hooks :on-start []] [:plugins-start]]
             @log))
      (is (= 1 @(:active-id w)))
      (is (= [1] @(:recent-tabs w)))
      (is (fn? @(:folder-result* w)))
      (is (fn? @(:open-folder* w)))
      (is (fn? @(:current-report w)))
      (is (fn? @(:current-open-paths w)))
      (is (true? @(:session-live? ctx)))
      (is (= ::snapshot @(:session-last ctx))))))

(deftest startup-tab-ctx-keys-test
  (let [w (startup-in (fn [_]))]
    (with-redefs [win-tabs/add-tab! (fn [_ _] {:id 1})
                  settings/load-settings (fn [_] {})]
      (let [s (#'win-startup/build-first-tab (#'win-startup/build-tab-ctx w))
            ctx (:tab-ctx s)]
        (is (= (into (set (keys w)) #{:sel-ctx :text :file-nul :tab-ctx :first-tab :tree-settings :folder-result!})
               (set (keys s))))
        (is (= #{:win :cv :ev* :sel* :status-label :line-col-label :close-popovers-fn :register-popover-fn :active?
                 :source-ctx :sel-ctx :settings-path* :inspect-last-fn :inspect-form-fn :open-find-fn :open-replace-fn
                 :tabs :next-id :notebook :theme-name :completion-opts :active-id :active-tab :popovers :tree-pane
                 :tree-saved! :folder-result* :session-live? :session-last :session-error :session-restoring?
                 :window-key-args :repl-complete-fn :parinfer-mode-read :parinfer-status-fn :parinfer-label-update!
                 :part-contribution :part-error! :recent-tabs :hint-label :hints-on?}
               (set (keys ctx))))
        (is (identical? (:sel-ctx s) (:sel-ctx ctx)))
        (is (identical? (:folder-result* w) (:folder-result* ctx)))
        (is (= #{:cv :status-label :dialect-label :sel* :ev* :reset-chk :reset-state :right :inspector-state
                 :active-ed :mode-fn}
               (set (keys (:sel-ctx s)))))
        (is (= :off ((:parinfer-mode-read ctx))))))))

(deftest startup-folder-result-and-opens-test
  (let [log (atom []) l (fn [x] (swap! log conj x))
        w (assoc (startup-in l) :tabs (atom [{:path (atom nil) :ed ::ed}]))]
    (with-redefs [win-tabs/add-tab! (fn [_ _] {:id 1})
                  settings/load-settings (fn [_] {:dialect :clj})
                  ft/load-model (fn [p] [:model p])
                  file-tree-pane/show-model! (fn [_ m] (l [:show-model m]))
                  editor/editor-text (fn [_] "")
                  tabs/untouched-untitled? (fn [_] true)
                  ft/project-selection (fn [p d] [:selection p d])
                  win-runtime/apply-selection! (fn [_ sel] (l [:apply sel]))
                  parts/run-hooks! (fn [_ k args _] (l [:hooks k args]))
                  win-files/dialog-dismissed? (fn [r] (= 2 (:code r)))
                  cview/append! (fn [_ k t] (l [:append k t]))
                  win-status/set-status! (fn [_ t] (l [:status t]))
                  g/gtk-toggle-button-set-active (fn [o v] (l [:toggle o v]))
                  win-tabs/open-file! (fn [c p] (l [:open c p]) (when (= p "/bad.clj") {:error "no"}))]
      (let [s (#'win-startup/build-first-tab (#'win-startup/build-tab-ctx w))
            folder-result! (:folder-result! s)]
        (folder-result! {:path "/p"})
        (folder-result! {:error "dismissed" :code 2})
        (folder-result! {:error "gone" :code 9})
        (reset! (:tab-ctx* w) ::ctx)
        (#'win-startup/accept-opens! s)
        (@(:current-report w) "hi")
        (let [dir (str (System/getProperty "java.io.tmpdir"))]
          (@(:current-open-paths w) [dir "/a.clj" "/bad.clj"])
          (is (= [[:show-model [:model "/p"]] [:apply [:selection "/p" :clj]] [:hooks :on-reroot [{:path "/p"}]]
                  [:append :error "; open folder: gone\n"] [:status "hi"]
                  [:show-model [:model dir]] [:apply [:selection dir :clj]] [:hooks :on-reroot [{:path dir}]]
                  [:toggle ::files 1] [:open ::ctx "/a.clj"] [:open ::ctx "/bad.clj"] [:status "Couldn't open bad.clj: no"]]
                 @log)))))))

(deftest build-makes-the-first-bindings-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) n (atom 0)
        mk (fn [& args] (let [k (keyword (str "w" (swap! n inc)))] (l (into [k] args)) k))
        in {:app ::app :file "/f.clj" :dir nil :settings-path "/s.edn" :plugins? nil :x 1}
        w (with-redefs [win-runtime/select-for (fn [f p] (l [:select-for f p]) ::sel)
                        ev/make (fn [sel] (l [:make sel]) ::ev)
                        registry/register! (fn [e] (l [:register e]))
                        ev/evaluating? (fn [e] (l [:evaluating? e]) false)
                        g/gtk-notebook-new (fn [] (mk :notebook))
                        g/gtk-application-window-new (fn [app] (mk :window app))]
            (win-startup/build in))]
    (is (= (into (set (keys in)) #{:settings-path* :plugins-on* :sel* :ev* :session :evaluating-fn :tabs :active-id
                                   :next-id :active-tab :active? :notebook :tab-ctx* :recent-tabs :win})
           (set (keys w))))
    (is (= ["/s.edn" true ::sel ::ev] (map (comp deref w) [:settings-path* :plugins-on* :sel* :ev*])))
    (is (= [[] nil 0 nil []] (map (comp deref w) [:tabs :active-id :next-id :tab-ctx* :recent-tabs])))
    (is (= [[:select-for "/f.clj" "/s.edn"] [:make ::sel] [:register ::ev] [:w1 :notebook] [:w2 :window ::app]] @log))
    (is (= [::ev :w1 :w2] [(:session w) (:notebook w) (:win w)]))
    (reset! (:tabs w) [{:id 1} {:id 2}])
    (reset! (:active-id w) 2)
    (is (= [] @(:recent-tabs w)))
    (reset! (:recent-tabs w) [7])
    (is (= [{:id 1} {:id 2}] @(:tabs w)))
    (is (= {:id 2} ((:active-tab w))))
    (is (true? ((:active? w) {:id 2})))
    (with-redefs [ev/evaluating? (fn [e] [:evaluating? e])]
      (is (= [:evaluating? ::ev] ((:evaluating-fn w)))))))

(deftest build-reads-a-directory-launch-and-no-plugins-test
  (let [w (with-redefs [settings/settings-path (fn [] "/default.edn")
                        settings/load-settings (fn [p] {:dialect [:from p]})
                        ft/project-selection (fn [d dialect] [:project d dialect])
                        ev/make (fn [sel] [:ev sel])
                        registry/register! (fn [_])
                        g/gtk-notebook-new (fn [] ::notebook)
                        g/gtk-application-window-new (fn [_] ::win)]
            (win-startup/build {:app ::app :dir "/d" :plugins? false}))]
    (is (= "/default.edn" @(:settings-path* w)))
    (is (false? @(:plugins-on* w)))
    (is (= [:project "/d" [:from "/default.edn"]] @(:sel* w)))))
