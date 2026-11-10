(ns jonquil.gtk.window.tabs-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.tab :as tab]
            [jonquil.gtk.window.alerts :as win-alerts]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.runtime :as win-runtime]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.parts :as parts]
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
