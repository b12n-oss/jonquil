(ns jonquil.gtk.window.tabs-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.changed :as changed]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.gutter :as gutter]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.tab :as tab]
            [jonquil.gtk.window.alerts :as win-alerts]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.help :as win-help]
            [jonquil.gtk.window.keys :as win-keys]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.runtime :as win-runtime]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.hints :as hints]
            [jonquil.parts :as parts]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.tabs :as tabs]
            [jonquil.theme :as theme]))

(defn- temp-file
  "A temp file holding text, deleted on exit; answers its path."
  [text]
  (let [f (java.io.File/createTempFile "tabs-test" ".clj")]
    (.deleteOnExit f)
    (spit f text)
    (.getPath f)))

(defn- logging-evaluator
  "An Evaluator that also tracks owners: release-owner! logs [:release id]."
  [log]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify
    ev/Evaluator
    ev/Owners
    (release-owner! [_ owner] (swap! log conj [:release owner]))))

(deftest tab-by-id-test
  (let [tabs* (atom [{:id 1} {:id 2 :x 1}])]
    (is (= {:id 2 :x 1} (#'win-tabs/tab-by-id tabs* 2)))
    (is (nil? (#'win-tabs/tab-by-id tabs* 3)))))

(deftest read-for-tab-test
  (let [log (atom [])]
    (with-redefs [cview/append! (fn [_cv kind text] (swap! log conj [:append kind text]))]
      (is (= {:text "(def a 1)\n"} (#'win-tabs/read-for-tab ::cv (temp-file "(def a 1)\n"))))
      (is (= [] @log))
      (let [path (temp-file "x\ny\u0000")
            m (str path " holds a NUL byte on line 2, which Jonquil can't edit, so it wasn't opened.")]
        (is (= {:error m} (#'win-tabs/read-for-tab ::cv path)))
        (is (= [[:append :error (str "; open: " m "\n")]] @log)))
      (reset! log [])
      (is (= {:text "a�b"} (#'win-tabs/read-for-tab ::cv (temp-file "a�b"))))
      (is (= [[:append :system "This file isn't valid UTF-8; unreadable bytes show as � and saving will replace them.\n"]] @log))
      (reset! log [])
      (let [r (#'win-tabs/read-for-tab ::cv "/no/such/dir/missing.clj")
            m (:error r)]
        (is (string? m))
        (is (seq m))
        (is (= {:error m} r))
        (is (= [[:append :error (str "; open: " m "\n")]] @log))))))

(deftest open-file-decisions-test
  (let [log (atom [])
        read (atom nil)
        ctx-for (fn [ts] {:tabs (atom ts) :cv ::cv :win ::win :active-tab (fn [] (first ts))})
        open! (fn [ts path] (#'win-tabs/open-file! (ctx-for ts) path))]
    (with-redefs [tabs/find-open (fn [ts p] (first (keep-indexed (fn [i x] (when (= p (:path x)) i)) ts)))
                  editor/editor-text (fn [ed] (:text ed))
                  win-tabs/select-tab! (fn [_ id] (swap! log conj [:select id]))
                  win-files/open-path! (fn [ed _cv _path p] (swap! log conj [:open-path ed p]) ::r)
                  win-files/update-title! (fn [ed win _path] (swap! log conj [:title ed win]))
                  win-tabs/refresh-labels! (fn [_] (swap! log conj [:labels]))
                  win-tabs/new-tab! (fn [_ m] (swap! log conj [:new-tab m]))
                  win-tabs/read-for-tab (fn [_cv _path] @read)]
      (is (nil? (open! [{:id 1 :ed {:text "x"} :path (atom "/a.clj")}
                        {:id 2 :ed {:text "y"} :path (atom "/b.clj")}]
                       "/b.clj")))
      (is (= [[:select 2]] @log))
      (reset! log [])
      (is (= ::r (open! [{:id 1 :ed {:text ""} :path (atom nil)}] "/c.clj")))
      (is (= [[:open-path {:text ""} "/c.clj"] [:title {:text ""} ::win] [:labels]] @log))
      (reset! log [])
      (reset! read {:text "T"})
      (is (nil? (open! [{:id 1 :ed {:text "x"} :path (atom nil)}] "/c.clj")))
      (is (= [[:new-tab {:path "/c.clj" :text "T"}]] @log))
      (reset! log [])
      (reset! read {:error "E"})
      (is (= {:error "E"} (open! [{:id 1 :ed {:text "x"} :path (atom nil)}] "/c.clj")))
      (is (= [] @log)))))

(deftest close-tab-test
  (let [log (atom [])
        choice (atom nil)
        mk (fn [id dirty path] {:id id :ed {:dirty (atom dirty)} :path (atom path)})
        ctx-for (fn [ts] {:tabs (atom ts) :win ::win :cv ::cv})]
    (with-redefs [editor/dirty? (fn [ed] @(:dirty ed))
                  g/gtk-window-close (fn [win] (swap! log conj [:close-window win]))
                  win-tabs/remove-tab! (fn [_ id] (swap! log conj [:remove id]))
                  win-alerts/show-alert! (fn [_win m opts on-choice]
                                           (swap! log conj [:alert (:message m) opts])
                                           (reset! choice on-choice))
                  cview/append! (fn [_cv kind text] (swap! log conj [:append kind text]))]
      (try
        (reset! win-alerts/alert-pending? false)
        (#'win-tabs/close-tab! (ctx-for [(mk 1 false nil)]) 1)
        (is (= [[:close-window ::win]] @log))
        (reset! log [])
        (#'win-tabs/close-tab! (ctx-for [(mk 1 false nil) (mk 2 false nil)]) 2)
        (is (= [[:remove 2]] @log))
        (reset! log [])
        (let [ctx (ctx-for [(mk 1 false nil) (mk 2 true "/p/a.clj")])]
          (#'win-tabs/close-tab! ctx 2)
          (is (= [[:alert "Save changes to a.clj before closing?" {:cancel 2 :default 0}]] @log))
          (is (true? @win-alerts/alert-pending?))
          (#'win-tabs/close-tab! ctx 2)
          (is (= 1 (count @log)))
          (@choice {:index 1})
          (is (= [[:alert "Save changes to a.clj before closing?" {:cancel 2 :default 0}] [:remove 2]] @log)))
        (reset! win-alerts/alert-pending? false)
        (reset! log [])
        (with-redefs [win-alerts/show-alert! (fn [& _] (throw (ex-info "boom" {})))]
          (#'win-tabs/close-tab! (ctx-for [(mk 1 false nil) (mk 2 true "/p/a.clj")]) 2)
          (is (false? @win-alerts/alert-pending?))
          (is (= [[:append :error "; close: boom\n"]] @log)))
        (finally (reset! win-alerts/alert-pending? false))))))

(deftest close-tab-choice-test
  (let [log (atom [])
        cb (atom nil)
        mk (fn [path] {:id 2 :ed {:dirty (atom true)} :path (atom path)})
        run! (fn [t result]
               (reset! log [])
               (#'win-tabs/close-tab-choice! {:tabs (atom [{:id 1 :ed {:dirty (atom false)} :path (atom nil)} t])
                                              :cv ::cv :win ::win}
                                             2 result))]
    (with-redefs [editor/dirty? (fn [ed] @(:dirty ed))
                  win-tabs/remove-tab! (fn [_ id] (swap! log conj [:remove id]))
                  win-files/save-to! (fn [ed _cv _path p] (swap! log conj [:save p]) (reset! (:dirty ed) false))
                  win-tabs/refresh-labels! (fn [_] (swap! log conj [:labels]))
                  win-files/file-dialog! (fn [_mode _win f] (reset! cb f))
                  win-files/dialog-dismissed? (fn [r] (= 2 (:code r)))
                  cview/append! (fn [_cv kind text] (swap! log conj [:append kind text]))]
      (run! (mk "/p/a.clj") {:error "x"})
      (is (= [] @log))
      (run! (mk "/p/a.clj") {:index 2})
      (is (= [] @log))
      (run! (mk "/p/a.clj") {:index 1})
      (is (= [[:remove 2]] @log))
      (run! (mk "/p/a.clj") {:index 0})
      (is (= [[:save "/p/a.clj"] [:labels] [:remove 2]] @log))
      (run! (mk nil) {:index 0})
      (is (= [] @log))
      (@cb {:path "/n.clj"})
      (is (= [[:save "/n.clj"] [:labels] [:remove 2]] @log))
      (run! (mk nil) {:index 0})
      (@cb {:error "dismissed" :code 2})
      (is (= [] @log))
      (@cb {:error "full" :code 9})
      (is (= [[:append :error "; save: full\n"]] @log)))))

(deftest select-and-switch-page-test
  (let [log (atom [])
        ctx {:tabs (atom [{:id 1 :root ::r1} {:id 2 :root ::r2}]) :notebook ::nb :active-id (atom 1)}]
    (with-redefs [g/gtk-notebook-page-num (fn [_nb r] [:num r])
                  g/gtk-notebook-set-current-page (fn [nb n] (swap! log conj [:page nb n]))
                  win-tabs/on-switch! (fn [_ t] (swap! log conj [:switch (:id t)]))]
      (#'win-tabs/select-tab! ctx 2)
      (is (= [[:page ::nb [:num ::r2]] [:switch 2]] @log))
      (reset! log [])
      (#'win-tabs/select-tab! ctx 1)
      (is (= [[:page ::nb [:num ::r1]]] @log))
      (reset! log [])
      (#'win-tabs/select-tab! ctx 3)
      (is (= [] @log))
      (is (nil? ((#'win-tabs/switch-page-fn ctx) ::nb ::r2 0 nil)))
      (is (= [[:switch 2]] @log))
      (reset! log [])
      ((#'win-tabs/switch-page-fn ctx) ::nb ::r9 0 nil)
      (is (= [] @log)))))

(deftest on-switch-test
  (let [log (atom [])
        lg (fn [x] (swap! log conj x))
        ctx {:active-id (atom 3) :win ::win :line-col-label ::lc :hint-label ::hint
             :recent-tabs (atom [3 1])
             :close-popovers-fn (fn [] (lg :close))
             :parinfer-label-update! (fn [] (lg :parinfer))}
        t {:id 1 :ed {:buf ::buf} :path ::p}]
    (with-redefs [g/gtk-widget-set-visible (fn [wd v] (lg [:visible wd v]))
                  parts/run-tab-hooks! (fn [_ k t] (lg [:hook k (:id t)]))
                  win-files/update-title! (fn [ed win _path] (lg [:title ed win]))
                  g/cursor-line-col (constantly [2 4])
                  g/gtk-label-set-text (fn [lab s] (lg [:text lab s]))
                  editor/focus! (fn [ed] (lg [:focus ed]))
                  win-session/session-changed! (fn [_] (lg [:session]))]
      (#'win-tabs/on-switch! ctx t)
      (is (= [[:visible ::hint 0] :close [:hook :on-switch 1] [:title {:buf ::buf} ::win]
              [:text ::lc "Ln 3, Col 5"] [:focus {:buf ::buf}] :parinfer [:session]]
             @log))
      (is (= 1 @(:active-id ctx)))
      (is (= [1 3] @(:recent-tabs ctx))))))

(deftest remove-tab-order-test
  (let [log (atom [])
        lg (fn [x] (swap! log conj x))
        mk (fn [n] {:id n :root (keyword "r" (str n))
                    :flash-fns {:cancel! (fn [] (lg [:cancel n]))}
                    :project (atom nil)})
        ctx-for (fn [ts active]
                  {:tabs (atom ts) :active-id (atom active) :notebook ::nb
                   :popovers (atom [::a [:pop 2] ::b]) :sel* (atom ::sel) :sel-ctx ::sc
                   :cv {:replay-tab* (atom {:id 2})} :ev* (atom (logging-evaluator log))
                   :recent-tabs (atom [2 1 3])})]
    (with-redefs [tab/popovers (fn [t] [[:pop (:id t)]])
                  parts/run-tab-hooks! (fn [_ k t] (lg [:hook k (:id t)]))
                  tab/dispose-popovers! (fn [t] (lg [:dispose (:id t)]))
                  g/gtk-notebook-page-num (fn [_ r] r)
                  g/gtk-notebook-remove-page (fn [_ r] (lg [:remove-page r]))
                  g/gtk-notebook-set-current-page (fn [_ r] (lg [:page r]))
                  win-tabs/refresh-labels! (fn [_] (lg [:labels]))
                  win-tabs/on-switch! (fn [_ t] (lg [:switch (:id t)]))
                  cview/append! (fn [_cv kind text] (lg [:append kind text]))]
      (let [t1 (mk 1)
            ctx (ctx-for [t1 (mk 2) (mk 3)] 2)]
        (#'win-tabs/remove-tab! ctx 2)
        (is (= [[:hook :on-tab-removed 2] [:cancel 2] [:release 2] [:dispose 2]
                [:remove-page :r/2] [:labels] [:page :r/3] [:switch 3]]
               @log))
        (is (= [1 3] (mapv :id @(:tabs ctx))))
        (is (= 3 @(:active-id ctx)))
        (is (= [::a ::b] @(:popovers ctx)))
        (is (= [1 3] @(:recent-tabs ctx)))
        (is (nil? @(:replay-tab* (:cv ctx))))
        (with-redefs [tabs/runnable-here? (constantly false)
                      win-runtime/apply-selection! (fn [sc sel] (lg [:apply sc sel]))]
          (reset! (:project t1) ::osel)
          (reset! (:active-id ctx) 1)
          (reset! log [])
          (#'win-tabs/remove-tab! ctx 3)
          (is (= [:apply ::sc ::osel] (last @log))))
        (let [t1 (mk 1)
              ctx (ctx-for [t1 (mk 3)] 1)]
          (reset! (:project t1) ::osel)
          (reset! log [])
          (with-redefs [tabs/runnable-here? (constantly false)
                        win-runtime/apply-selection! (fn [_ _] (throw (ex-info "boom" {})))]
            (#'win-tabs/remove-tab! ctx 3)
            (is (= [:append :error "; project: boom\n"] (last @log)))))))))

(deftest refresh-labels-test
  (let [log (atom [])
        lg (fn [x] (swap! log conj x))
        t1 {:id 1 :ed {:dirty false} :path (atom "/a/x.clj")}
        t2 {:id 2 :ed {:dirty true} :path (atom "/b/y.clj")}]
    (with-redefs [editor/dirty? (fn [ed] (:dirty ed))
                  tab/set-label! (fn [t s] (lg [:label (:id t) s]))
                  g/gtk-notebook-set-show-tabs (fn [nb v] (lg [:show nb v]))
                  win-session/session-changed! (fn [_] (lg [:session]))]
      (let [want (tabs/labels [{:path "/a/x.clj" :dirty? false} {:path "/b/y.clj" :dirty? true}])]
        (#'win-tabs/refresh-labels! {:tabs (atom [t1 t2]) :notebook ::nb})
        (is (= [[:label 1 (first want)] [:label 2 (second want)] [:show ::nb 1] [:session]] @log)))
      (reset! log [])
      (#'win-tabs/refresh-labels! {:tabs (atom [t1]) :notebook ::nb})
      (is (= [:show ::nb 0] (nth @log (- (count @log) 2))))
      (is (= [:session] (last @log))))))

(deftest add-and-new-tab-test
  (let [log (atom [])
        lg (fn [x] (swap! log conj x))
        made (atom nil)
        ctx-for (fn [] {:tabs (atom []) :next-id (atom 0) :notebook ::nb :theme-name (atom :dark)
                        :completion-opts ::co :settings-path* (atom "/s")
                        :sel* (atom {:profile ::window-profile})})]
    (with-redefs [win-runtime/select-for (fn [p _] {:profile [:pp p]})
                  theme/theme (fn [n] [:theme n])
                  tab/make-tab! (fn [opts]
                                  (reset! made opts)
                                  {:id (:id opts) :root ::root :label-box ::lb :project (atom nil)})
                  g/gtk-notebook-append-page (fn [_nb r lb] (lg [:append-page r lb]))
                  win-tabs/wire-tab! (fn [_ t] (lg [:wire (:id t)]) (assoc t :wired? true))]
      (let [ctx (ctx-for)
            r (#'win-tabs/add-tab! ctx {:path "/a.clj" :text "x"})]
        (is (true? (:wired? r)))
        (is (= {:id 1 :path "/a.clj" :text "x" :theme [:theme :dark] :profile [:pp "/a.clj"]
                :completion-opts ::co}
               @made))
        (is (= {:profile [:pp "/a.clj"]} @(:project r)))
        (is (= [[:append-page ::root ::lb] [:wire 1]] @log))
        (is (= [r] @(:tabs ctx))))
      (let [ctx (ctx-for)
            r (#'win-tabs/add-tab! ctx {:path nil :text ""})]
        (is (= ::window-profile (:profile @made)))
        (is (nil? @(:project r)))))
    (reset! log [])
    (with-redefs [win-tabs/add-tab! (fn [c m]
                                      (lg [:add m])
                                      (let [t {:id 9 :root ::r9}] (swap! (:tabs c) conj t) t))
                  g/gtk-notebook-reorder-child (fn [_nb r i] (lg [:reorder r i]))
                  win-tabs/refresh-labels! (fn [_] (lg [:labels]))
                  win-tabs/select-tab! (fn [_ id] (lg [:select id]))
                  cview/append! (fn [_cv kind text] (lg [:append kind text]))]
      (let [ctx {:tabs (atom [{:id 1} {:id 2}]) :notebook ::nb :active-id (atom 1) :cv ::cv}]
        (is (= {:id 9 :root ::r9} (#'win-tabs/new-tab! ctx {:path "/n.clj"})))
        (is (= [1 9 2] (mapv :id @(:tabs ctx))))
        (is (= [[:add {:path "/n.clj" :text ""}] [:reorder ::r9 1] [:labels] [:select 9]] @log)))
      (reset! log [])
      (with-redefs [win-tabs/add-tab! (fn [_ _] (throw (ex-info "boom" {})))]
        (let [ctx {:tabs (atom [{:id 1} {:id 2}]) :notebook ::nb :active-id (atom 1) :cv ::cv}]
          (is (nil? (#'win-tabs/new-tab! ctx {:path "/n.clj"})))
          (is (= [[:append :error "; new tab: boom\n"]] @log))
          (is (= [1 2] (mapv :id @(:tabs ctx)))))))))

(deftest build-adds-the-tab-fns-test
  (let [log (atom [])
        tc (atom ::tc1)
        active-id (atom 2)
        ts (atom [{:id 1} {:id 2} {:id 3}])]
    (with-redefs [win-tabs/close-tab! (fn [c id] (swap! log conj [:close c id]))
                  win-tabs/new-tab! (fn [c m] (swap! log conj [:new c m]))
                  win-tabs/select-tab! (fn [c id] (swap! log conj [:select c id]))]
      (let [w (win-tabs/build {:tabs ts :active-id active-id :active-tab (fn [] {:id 2}) :tab-ctx* tc :x 1})]
        (reset! tc ::tc2)
        (is (= #{:tabs :active-id :active-tab :tab-ctx* :x :close-fn :new-tab-fn :step-tab-fn :digit-tab-fn}
               (set (keys w))))
        ((:close-fn w))
        ((:new-tab-fn w))
        ((:step-tab-fn w) 1)
        ((:step-tab-fn w) -1)
        ((:digit-tab-fn w) 1)
        ((:digit-tab-fn w) 9)
        ((:digit-tab-fn w) 5)
        (is (= [[:close ::tc2 2] [:new ::tc2 {}] [:select ::tc2 3] [:select ::tc2 1] [:select ::tc2 1] [:select ::tc2 3]]
               @log))))))

;; --- wire-tab! ---------------------------------------------------------------

(defn- wire-ctx
  "A wire-tab! ctx whose callbacks log into log; over overrides keys."
  [log over]
  (merge {:win ::win :cv ::cv :ev* (atom ::ev) :status-label ::l :line-col-label ::lc
          :close-popovers-fn (fn [& _] (swap! log conj :close-popovers))
          :register-popover-fn (fn [p] (swap! log conj [:register p]))
          :active? (fn [_] true) :source-ctx (fn [t] {:src (:id t)})
          :sel-ctx ::sel-ctx :sel* (atom ::sel) :tabs (atom []) :settings-path* (atom "/s.edn")
          :inspect-last-fn ::ilf :inspect-form-fn ::iff
          :tree-saved! (fn [t] (swap! log conj [:tree (:id t)]))
          :open-find-fn ::of :open-replace-fn ::or :window-key-args {} :repl-complete-fn ::rc
          :hint-label ::hint :hints-on? (atom true)
          :parinfer-mode-read (fn [] :indent) :parinfer-status-fn ::ps}
         over))

(defn- wire-t
  "A tab map for wire-tab!, its editor's :changed set to changed."
  [changed]
  {:id 7
   :ed {:buf ::buf :view ::view :destroyed? (atom false) :changed changed}
   :fb ::fb :help-pop ::hp :full-doc-pop ::fdp :rename-pop {:entry ::entry}
   :completion ::c :palette ::pal :last-shown (atom nil) :path (atom "/a.clj")
   :discard-warned? (atom true) :close-btn ::close :project (atom nil)})

(defn- wire-env
  "Runs f with every editor, find bar, palette and GTK call wire-tab! makes
  redefined to log into log and keep the fns it is handed in caps."
  [log caps f]
  (let [l (fn [x] (swap! log conj x))
        keep-fn (fn [k] (fn [_ x] (swap! caps assoc k x) (l k)))
        append (fn [k tag] (fn [_ x] (swap! caps update k (fnil conj []) x) (l tag)))]
    (with-redefs [parts/run-tab-hooks! (fn [_ k t] (l [:hooks k (:id t)]))
                  win-run/make-flash-fn (fn [_] {:flash! ::flash :cancel! ::cancel})
                  cmd-registry/menu-items (fn [_ ctx] (swap! caps assoc :menu-ctx ctx) [:items])
                  win-keys/install-context-menu! (fn [v _ on-err]
                                                   (swap! caps assoc :menu-error on-err)
                                                   (l [:menu v])
                                                   ::group)
                  editor/set-error-fn! (keep-fn :error-fn)
                  editor/set-parinfer-fns! (fn [_ a b] (swap! caps assoc :parinfer-fns [a b]) (l :parinfer-fns))
                  editor/set-format-style-fn! (keep-fn :format-style-fn)
                  find-bar/set-error-fn! (fn [fb f] (swap! caps assoc :fb-error f) (l [:fb-error-fn fb]))
                  editor/on-dirty-changed! (append :dirty :on-dirty)
                  editor/on-cursor-moved! (append :moved :on-moved)
                  editor/on-focus-lost! (append :lost :on-focus-lost)
                  g/g-signal-connect-data (fn [o s & _] (l [:connect o s]) 1)
                  win-status/contain-click (fn [label where f]
                                             (swap! caps assoc :close-btn [label where f])
                                             (fn [& _]))
                  g/gtk-scrollable-get-vadjustment (constantly ::vadj)
                  g/gtk-scrollable-get-hadjustment (constantly ::hadj)
                  editor/set-quick-doc-fn! (keep-fn :quick-doc-fn)
                  editor/set-jump-fn! (keep-fn :jump-fn)
                  palette/set-handlers! (fn [p m] (swap! caps assoc :pal m) (l [:palette-handlers p]))
                  editor/set-rename-fn! (keep-fn :rename-fn)
                  editor/set-run-form-fn! (keep-fn :run-form-fn)
                  editor/set-flash-cancel-fn! (fn [_ f] (l [:flash-cancel f]))
                  win-help/wire-completion! (fn [c complete-fn label register-fn & _]
                                              (swap! caps assoc :completion [complete-fn label register-fn])
                                              (l [:completion c]))]
      (f))))

(def ^:private wired-in-order
  [[:hooks :on-tab-wired 7] [:menu ::view] :error-fn :parinfer-fns :format-style-fn [:fb-error-fn ::fb]
   :on-dirty [:connect ::close "clicked"] :on-dirty :on-moved [:register ::hp] [:register ::fdp]
   [:connect ::vadj "value-changed"] [:connect ::hadj "value-changed"] :quick-doc-fn :jump-fn
   :on-moved :on-moved :on-focus-lost :on-moved :on-focus-lost [:register {:entry ::entry}]
   [:register ::pal] [:palette-handlers ::pal] :rename-fn :run-form-fn [:flash-cancel ::cancel]
   [:connect ::entry "activate"] [:completion ::c]])

(defn- wire-log
  "The log of wiring (wire-t changed) under ctx-over."
  [changed ctx-over]
  (let [log (atom []) caps (atom {})]
    (wire-env log caps #(#'win-tabs/wire-tab! (wire-ctx log ctx-over) (wire-t changed)))
    @log))

(deftest wire-tab-wires-in-order-test
  (is (= wired-in-order (wire-log nil {})))
  (testing "no hint label: LM6's cursor handler is gone"
    (is (= (let [[a b] (split-at 17 wired-in-order)] (vec (concat a (rest b))))
           (wire-log nil {:hint-label nil}))))
  (testing "no parinfer-mode-read: no :parinfer-fns"
    (is (= (vec (remove #{:parinfer-fns} wired-in-order))
           (wire-log nil {:parinfer-mode-read nil}))))
  (testing "a gutter: the buffer's changed handler connects second, refresh is set"
    (let [changed {:ran (atom nil) :refresh (atom nil) :gutter ::gutter}]
      (is (= (into [(first wired-in-order) [:connect ::buf "changed"]] (rest wired-in-order))
             (wire-log changed {})))
      (is (fn? @(:refresh changed))))))

(deftest wire-tab-returns-its-actions-test
  (let [log (atom []) caps (atom {}) quick (atom nil) run (atom nil)
        ctx (wire-ctx log {})
        t (wire-t nil)
        r (with-redefs [win-help/do-quick-doc! (fn [m] (reset! quick m))
                        win-help/do-jump! (fn [c] (swap! log conj [:jump c]))
                        win-help/do-rename! (fn [& args] (swap! log conj (into [:rename] args)))
                        win-help/do-rename-to! (fn [& args] (swap! log conj (into [:rename-to] args)))
                        win-help/do-complete! (fn [& args] (swap! log conj (into [:complete] args)))
                        win-run/do-run-form! (fn [m] (reset! run m))]
            (let [r (wire-env log caps (fn [] (#'win-tabs/wire-tab! ctx t)))
                  a (:actions r)]
              (reset! log [])
              ((:quick-doc! a))
              ((:jump! a))
              ((:rename! a))
              ((:rename-to! a) "n")
              ((:complete! a))
              ((:run-form! a))
              r))
        a (:actions r)
        mc (:menu-ctx @caps)
        ed (:ed t)]
    (is (= (assoc t :flash-fns {:flash! ::flash :cancel! ::cancel} :menu-group ::group :actions a) r))
    (is (= #{:quick-doc! :jump! :rename! :rename-to! :complete! :run-form!} (set (keys a))))
    (is (= #{:run-form! :jump! :rename! :quick-doc! :inspect-last-fn :inspect-form-fn :complete!
             :toggle-comment! :format-all! :format-selection! :indent! :unindent! :open-find-fn :open-replace-fn}
           (set (keys mc))))
    (doseq [k [:jump! :rename! :quick-doc! :complete! :run-form!]]
      (is (identical? (k a) (k mc)) (str k)))
    (doseq [[cap k] {:quick-doc-fn :quick-doc! :jump-fn :jump! :rename-fn :rename! :run-form-fn :run-form!}]
      (is (identical? (cap @caps) (k a)) (str cap)))
    (let [[complete-fn label register-fn] (:completion @caps)]
      (is (identical? complete-fn (:complete! a)))
      (is (= ::l label))
      (is (identical? register-fn (:register-popover-fn ctx))))
    (is (= ::ilf (:inspect-last-fn mc)))
    (is (= ::iff (:inspect-form-fn mc)))
    (is (= ::of (:open-find-fn mc)))
    (is (= ::or (:open-replace-fn mc)))
    (is (= [[:jump {:src 7}]
            [:rename ed {:entry ::entry} ::l (:close-popovers-fn ctx)]
            [:rename-to ed {:entry ::entry} "n" ::l]
            [:complete ed ::c ::l]]
           @log))
    (is (= #{:ed :ev* :status-label :help-pop :full-doc-pop :last-shown :example-ctx :close-popovers-fn :win :active?}
           (set (keys @quick))))
    (is (= #{:tab :cv :ev* :sel* :status-label :flash-fns} (set (keys @run))))
    (is (= {:flash! ::flash :cancel! ::cancel} (:flash-fns @run)))))

(deftest wire-tab-handlers-test
  (let [log (atom []) caps (atom {}) l (fn [x] (swap! log conj x))
        active (atom true)
        ctx (wire-ctx log {:active? (fn [_] @active)})
        t (wire-t nil)
        ed (:ed t)]
    (with-redefs [win-tabs/refresh-labels! (fn [_] (l [:labels]))
                  win-files/update-title! (fn [e w p] (l [:title e w p]))
                  win-tabs/close-tab! (fn [_ id] (l [:close-tab id]))
                  g/gtk-label-set-text (fn [lab s] (l [:text lab s]))
                  popover/open? (constantly true)
                  popover/hide! (fn [p] (l [:hide p]))
                  help-popover/hide-unless-focused! (fn [p] (l [:hide-unless p]))
                  win-status/set-status! (fn [lab s] (l [:status lab s]))
                  g/g-timeout-add (fn [ms _ _] (l [:timeout ms]) 1)]
      (wire-env log caps #(#'win-tabs/wire-tab! ctx t))
      (let [[d1 d2] (:dirty @caps)
            [m1 m2 m3 m4] (:moved @caps)
            [f1 f2] (:lost @caps)
            path (:path t)
            fresh (fn [thunk] (reset! log []) (thunk) @log)]
        (is (= 2 (count (:dirty @caps))))
        (is (= 4 (count (:moved @caps))))
        (is (= 2 (count (:lost @caps))))
        (is (= [[:labels] [:title ed ::win path] [:tree 7]] (fresh #(d1 false))))
        (is (= [[:labels] [:title ed ::win path]] (fresh #(d1 true))))
        (reset! (:discard-warned? t) true)
        (d2 true)
        (is (true? @(:discard-warned? t)))
        (d2 false)
        (is (false? @(:discard-warned? t)))
        (is (= [[:close-tab 7]] (fresh #((nth (:close-btn @caps) 2) nil))))
        (is (= [::l "close tab"] (vec (take 2 (:close-btn @caps)))))
        (is (= [[:text ::lc "Ln 3, Col 4"]] (fresh #(m1 [2 3]))))
        (is (= [[:hide ::hp]] (fresh #(m2 [2 3]))))
        (is (= [[:timeout 200]] (fresh #(m3 [2 3]))))
        (is (= [[:hide ::fdp]] (fresh #(m4 [2 3]))))
        (reset! active false)
        (is (= [] (fresh #(m1 [2 3]))))
        (reset! active true)
        (is (= [[:hide ::hp]] (fresh f1)))
        (is (= [[:hide-unless ::fdp]] (fresh f2)))
        (is (= [[:status ::l "m"]] (fresh #((:error-fn @caps) "m"))))
        (is (= [[:status ::l "m"]] (fresh #((:fb-error @caps) "m"))))
        (is (= [[:status ::l "m"]] (fresh #((:menu-error @caps) "m"))))))))

(deftest wire-tab-palette-test
  (let [log (atom []) caps (atom {}) l (fn [x] (swap! log conj x))
        ctx (wire-ctx log {:window-key-args {:switch-tab-fn (fn [x] (l [:tab x]))
                                             :panel-focus-fn (fn [x] (l [:panel x]))
                                             :layout-use-fn (fn [x] (l [:layout x]))
                                             :world-restore-from-fn (fn [_] (throw (ex-info "boom" {})))}})]
    (with-redefs [win-keys/window-key-ctx (fn [_ a b] [:wkc a b])
                  editor/key-ctx (constantly [:kc])
                  plugin-registry/all-commands (constantly ::cmds)
                  cmd-registry/run-command-in (fn [cs id m] (l [:cmd cs id m]))
                  win-status/set-status! (fn [lab s] (l [:status lab s]))]
      (wire-env log caps #(#'win-tabs/wire-tab! ctx (wire-t nil)))
      (reset! log [])
      (let [{:keys [on-run on-error]} (:pal @caps)]
        (on-run [:tab/switch 3])
        (on-run [:panel/switch :trace])
        (on-run [:layout/use "a"])
        (on-run [:world/restore-from "/w"])
        (on-run :edit/comment)
        (on-error "m")
        (is (= [[:tab 3] [:panel :trace] [:layout "a"] [:status ::l "; palette: boom"]
                [:cmd ::cmds :edit/comment {:window [:wkc 0 0] :editor [:kc] :console {:complete! ::rc}}]
                [:status ::l "m"]]
               @log))))))

(deftest wire-tab-path-watch-test
  (let [run (fn [{:keys [two? keeps throw? same?]}]
              (let [log (atom []) caps (atom {}) l (fn [x] (swap! log conj x))
                    t (wire-t nil)
                    ctx (wire-ctx log {:parinfer-label-update! (fn [] (l :pl))
                                       :tabs (atom (if two? [t {:id 8}] [t]))})]
                (with-redefs [win-runtime/select-for (fn [p _] {:profile [:p p]})
                              tabs/keeps-window-jolt? (fn [& _] keeps)
                              win-runtime/apply-selection! (fn [sc s]
                                                             (l [:apply sc s])
                                                             (when throw? (throw (ex-info "boom" {}))))
                              editor/set-profile! (fn [_ p] (l [:profile p]))
                              win-tabs/refresh-labels! (fn [_] (l [:labels]))
                              cview/append! (fn [_ kind text] (l [:append kind text]))]
                  (wire-env log caps #(#'win-tabs/wire-tab! ctx t))
                  (reset! log [])
                  (reset! (:path t) (if same? "/a.clj" "/b.clj"))
                  [@log @(:project t)])))]
    (let [[log project] (run {})]
      (is (= {:profile [:p "/b.clj"]} project))
      (is (= [[:apply ::sel-ctx {:profile [:p "/b.clj"]}] [:profile [:p "/b.clj"]] [:labels] :pl [:tree 7]] log)))
    (is (= [] (first (run {:same? true}))))
    (is (= [[:profile [:p "/b.clj"]] [:labels] :pl [:tree 7]] (first (run {:two? true}))))
    (is (= [[:profile [:p "/b.clj"]] [:labels] :pl [:tree 7]] (first (run {:keeps true}))))
    (is (= [[:apply ::sel-ctx {:profile [:p "/b.clj"]}]]
           (take 1 (first (run {:throw? true})))))
    (is (= [[:append :error "; project: boom\n"] [:profile [:p "/b.clj"]] [:labels] :pl [:tree 7]]
           (rest (first (run {:throw? true})))))))

(deftest wire-tab-changed-marks-test
  (let [run (fn [destroyed?]
              (let [log (atom []) caps (atom {})
                    changed {:ran (atom nil) :refresh (atom nil) :gutter ::gutter}
                    t (wire-t changed)]
                (with-redefs [jonquil.gtk.callbacks/on-gui! (fn [f] (f))
                              gutter/set-ranges! (fn [g rs] (swap! log conj [:ranges g rs]))]
                  (wire-env log caps #(#'win-tabs/wire-tab! (wire-ctx log {}) t))
                  (reset! log [])
                  (reset! (:destroyed? (:ed t)) destroyed?)
                  (@(:refresh changed))
                  @log)))]
    (is (= [[:ranges ::gutter []]] (run false)))
    (is (= [] (run true)))))

(deftest hint-tick-test
  (let [log (atom []) l (fn [x] (swap! log conj x))
        answer (atom "Tab: indent") throw? (atom false)
        m (fn [over]
            (merge {:n 1 :pending (atom 1) :failed? (atom false) :hints-on? (atom true)
                    :active? (constantly true) :t ::t
                    :ed {:buf ::buf :destroyed? (atom false)} :hint-label ::hint :cv ::cv}
                   over))
        tick (fn [mm] (reset! log []) [(#'win-tabs/hint-tick! mm) @log])]
    (with-redefs [g/cursor-offset (constantly 5)
                  g/gtk-text-buffer-get-char-count (constantly 20)
                  g/buffer-text-range (fn [& _] (if @throw? (throw (ex-info "boom" {})) "(inc 1)"))
                  hints/line-at (fn [_ p] ["l" p])
                  hints/at-caret (fn [line a] [:at line a])
                  hints/hint (fn [_] @answer)
                  plugin-registry/editor-bindings (constantly {})
                  plugin-registry/window-bindings (constantly {})
                  g/gtk-label-set-text (fn [lab s] (l [:text lab s]))
                  g/gtk-widget-set-visible (fn [wd v] (l [:visible wd v]))
                  cview/append! (fn [_ kind text] (l [:append kind text]))]
      (is (= [0 [[:text ::hint "Tab: indent"] [:visible ::hint 1]]] (tick (m {}))))
      (reset! answer nil)
      (is (= [0 [[:text ::hint ""] [:visible ::hint 0]]] (tick (m {}))))
      (is (= [0 []] (tick (m {:pending (atom 2)}))))
      (is (= [0 []] (tick (m {:hints-on? (atom false)}))))
      (is (= [0 []] (tick (m {:active? (constantly false)}))))
      (is (= [0 []] (tick (m {:ed {:buf ::buf :destroyed? (atom true)}}))))
      (reset! throw? true)
      (let [mm (m {})]
        (is (= [0 [[:visible ::hint 0] [:append :error "; key hints: boom\n"]]] (tick mm)))
        (is (= [0 [[:visible ::hint 0]]] (tick mm)))
        (is (true? @(:failed? mm)))))))

(deftest changed-marks-steps-test
  (let [log (atom []) text* (atom "t") throw? (atom false)
        ed {:destroyed? (atom false)}]
    (with-redefs [jonquil.gtk.callbacks/on-gui! (fn [f] (f))
                  gutter/set-ranges! (fn [g rs] (swap! log conj [:ranges g rs]))
                  editor/editor-text (fn [_] @text*)
                  changed/changed (fn [_ _] (if @throw? (throw (ex-info "boom" {})) [{:start 1 :end 3}]))]
      (let [r #{:x} ran (atom r)
            draw (fn [] (reset! log []) (#'win-tabs/draw-changed! {:ed ed :ran ran :gutter ::g :r r :text "t"}) @log)]
        (is (= [[:ranges ::g [[1 3]]]] (draw)))
        (reset! text* "u")
        (is (= [] (draw)))
        (reset! text* "t")
        (reset! ran #{:y})
        (is (= [] (draw)))
        (reset! ran r)
        (reset! throw? true)
        (is (= [] (draw))))
      (reset! throw? false)
      (reset! log [])
      (#'win-tabs/refresh-changed! {:ed ed :ran (atom nil) :gutter ::g})
      (is (= [[:ranges ::g []]] @log))
      (reset! log [])
      (#'win-tabs/refresh-changed! {:ed {:destroyed? (atom true)} :ran (atom nil) :gutter ::g})
      (is (= [] @log)))))
