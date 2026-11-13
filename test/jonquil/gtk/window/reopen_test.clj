(ns jonquil.gtk.window.reopen-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.ffi :as ffi]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.reopen :as win-reopen]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.last-session :as last-session]))

(deftest session-entry-test
  (let [log (atom []) on (atom false) loaded (atom nil)
        dir (doto (java.io.File/createTempFile "jonquil-reopen" "") (.delete) (.mkdirs) (.deleteOnExit))
        d (.getPath dir)
        state (atom {:root "/r"})
        ctx {:cv ::cv :tree-pane {:state state} :settings-path* (atom "/cfg/settings.edn")
             :folder-result* (atom (fn [r] (swap! log conj [:folder r])))}]
    (with-redefs [win-session/reopen-tabs? (fn [p] (swap! log conj [:on? p]) @on)
                  last-session/load-sessions (fn [p] (swap! log conj [:load p]) @loaded)
                  cview/append! (fn [_ k s] (swap! log conj [:append k s]))]
      (is (nil? (#'win-reopen/session-entry! ctx {:reroot? true})))
      (is (= [[:on? "/cfg/settings.edn"]] @log))
      (reset! on true)
      (reset! log [])
      (reset! loaded {:sessions [{:root "/q" :tabs ["/q/a"]} {:root "/r" :tabs ["/r/b"]}]})
      (is (= {:root "/r" :tabs ["/r/b"]} (#'win-reopen/session-entry! ctx {:reroot? true})))
      (is (= [[:on? "/cfg/settings.edn"] [:load "/cfg/sessions.edn"]] @log))
      (reset! log [])
      (reset! loaded {:sessions [] :warning "W"})
      (is (nil? (#'win-reopen/session-entry! ctx {})))
      (is (= [[:on? "/cfg/settings.edn"] [:load "/cfg/sessions.edn"] [:append :system "; W\n"]] @log))
      (reset! state {:root nil})
      (reset! loaded {:sessions [{:root d :tabs ["/x"]} {:root "/q"}]})
      (reset! log [])
      (is (= {:root d :tabs ["/x"]} (#'win-reopen/session-entry! ctx {:reroot? true})))
      (is (= [:folder {:path d}] (last @log)))
      (reset! loaded {:sessions [{:root "/no/such/dir" :tabs ["/x"]}]})
      (reset! log [])
      (is (= {:root "/no/such/dir" :tabs ["/x"]} (#'win-reopen/session-entry! ctx {:reroot? true})))
      (is (= [[:on? "/cfg/settings.edn"] [:load "/cfg/sessions.edn"]] @log))
      (reset! loaded {:sessions [{:root d :tabs ["/x"]}]})
      (reset! log [])
      (is (= {:root d :tabs ["/x"]} (#'win-reopen/session-entry! ctx {:reroot? false})))
      (is (= [[:on? "/cfg/settings.edn"] [:load "/cfg/sessions.edn"]] @log)))))

(deftest reopen-failed-test
  (let [log (atom []) ans (atom nil)
        f (doto (java.io.File/createTempFile "jonquil-reopen" ".clj") (.deleteOnExit))
        p (.getPath f)]
    (with-redefs [win-tabs/open-file! (fn [c q] (swap! log conj [:open c q]) (if (= ::throw @ans) (throw (ex-info "boom" {})) @ans))]
      (is (false? (#'win-reopen/reopen-failed? ::ctx p)))
      (reset! ans {:error "E"})
      (is (true? (#'win-reopen/reopen-failed? ::ctx p)))
      (reset! ans ::throw)
      (is (true? (#'win-reopen/reopen-failed? ::ctx p)))
      (is (= [[:open ::ctx p] [:open ::ctx p] [:open ::ctx p]] @log))
      (is (true? (#'win-reopen/reopen-failed? ::ctx "/no/such/file.clj")))
      (is (true? (#'win-reopen/reopen-failed? ::ctx (.getParent f))))
      (is (= 3 (count @log))))))

(deftest finish-reopen-test
  (let [log (atom [])
        ctx {:cv ::cv :tabs (atom [{:id 1 :path (atom "/a")} {:id 2 :path (atom "/b")}])
             :tree-saved! (fn [t] (swap! log conj [:saved (:id t)]))}]
    (with-redefs [win-tabs/select-tab! (fn [_ id] (swap! log conj [:select id]))
                  cview/append! (fn [_ k s] (swap! log conj [:append k s]))]
      (#'win-reopen/finish-reopen! ctx {:active "/b"} ["x.clj"])
      (is (= [[:select 2] [:saved 2] [:append :system (last-session/missing-line ["x.clj"])]] @log))
      (reset! log [])
      (#'win-reopen/finish-reopen! ctx {:active "/zz"} [])
      (is (= [] @log))
      (#'win-reopen/finish-reopen! ctx {} ["a.clj" "b.clj"])
      (is (= [[:append :system (last-session/missing-line ["a.clj" "b.clj"])]] @log)))))

(deftest reopen-entry-and-restore-test
  (let [log (atom []) e (atom nil)]
    (with-redefs [win-reopen/reopen-failed? (fn [_ p] (swap! log conj [:try p]) (= p "/gone/x.clj"))
                  win-reopen/finish-reopen! (fn [_ en m] (swap! log conj [:finish en m]))]
      (#'win-reopen/reopen-entry! ::ctx {:tabs ["/a.clj" "/gone/x.clj"]})
      (is (= [[:try "/a.clj"] [:try "/gone/x.clj"] [:finish {:tabs ["/a.clj" "/gone/x.clj"]} ["x.clj"]]] @log)))
    (reset! log [])
    (with-redefs [win-reopen/session-entry! (fn [c opts] (swap! log conj [:entry c opts]) @e)
                  win-reopen/reopen-entry! (fn [c en] (swap! log conj [:reopen c en]))]
      (is (nil? (#'win-reopen/restore-session! ::ctx {:reroot? false})))
      (is (= [[:entry ::ctx {:reroot? false}]] @log))
      (reset! e {:tabs ["/a"]})
      (is (nil? (#'win-reopen/restore-session! ::ctx {:reroot? false})))
      (is (= [[:entry ::ctx {:reroot? false}] [:entry ::ctx {:reroot? false}] [:reopen ::ctx {:tabs ["/a"]}]] @log)))))

(deftest after-first-frame-wiring-test
  (let [log (atom []) clock (atom ::clk)]
    (with-redefs [g/gtk-widget-get-frame-clock (fn [win] (swap! log conj [:clock win]) @clock)
                  g/g-signal-connect-data (fn [o s cb & _] (swap! log conj [:connect o s (some? cb)]) 42)
                  g/g-timeout-add (fn [ms cb _] (swap! log conj [:timeout ms (some? cb)]) 7)]
      (is (= 7 (#'win-reopen/after-first-frame! ::win (fn []))))
      (is (= [[:clock ::win] [:connect ::clk "after-paint" true] [:timeout 1000 true]] @log))
      (reset! log [])
      (reset! clock ffi/null)
      (is (= 7 (#'win-reopen/after-first-frame! ::win (fn []))))
      (is (= [[:clock ::win] [:timeout 1000 true]] @log)))))

(deftest reopen-later-test
  (let [log (atom []) later (atom nil) throw? (atom false)
        r (atom false)
        pending (atom nil)
        ctx {:cv ::cv :win ::win :session-restoring? r :session-pending pending}
        done! (fn [] (swap! log conj :done))]
    (with-redefs [win-reopen/after-first-frame! (fn [win f] (swap! log conj [:frame win]) (reset! later f))
                  win-reopen/reopen-entry! (fn [c en] (swap! log conj [:reopen (= c ctx) en]) (when @throw? (throw (ex-info "boom" {}))))
                  cview/append! (fn [_ k s] (swap! log conj [:append k s]))]
      (#'win-reopen/reopen-later! ctx {:tabs ["/a"]} done!)
      (is (true? @r))
      (is (= [[:frame ::win]] @log))
      (@later)
      (is (false? @r))
      (is (= [[:frame ::win] [:reopen true {:tabs ["/a"]}] :done] @log))
      (reset! log [])
      (reset! throw? true)
      (#'win-reopen/reopen-later! ctx {:tabs ["/b"]} done!)
      (@later)
      (is (= [[:frame ::win] [:reopen true {:tabs ["/b"]}] [:append :error "; reopen tabs: boom\n"] :done] @log))
      (reset! log [])
      (#'win-reopen/reopen-later! ctx {:tabs ["/c"]} done!)
      (reset! r false)
      (@later)
      (is (= [[:frame ::win]] @log))
      (testing "the entry is read when the frame comes, so a folder that arrived meanwhile decides it"
        (reset! log [])
        (reset! throw? false)
        (#'win-reopen/reopen-later! ctx {:tabs ["/last-project"]} done!)
        (reset! pending {:tabs ["/arrived"]})
        (@later)
        (is (= [[:frame ::win] [:reopen true {:tabs ["/arrived"]}] :done] @log))
        (is (nil? @pending)))
      (testing "an arrived folder with nothing remembered opens nothing"
        (reset! log [])
        (#'win-reopen/reopen-later! ctx {:tabs ["/last-project"]} done!)
        (reset! pending nil)
        (@later)
        (is (= [[:frame ::win] :done] @log))))))

(deftest folder-arrived-test
  (let [log (atom [])
        tab (fn [path] {:path (atom path) :ed ::ed})
        ctx (fn [restoring? tabs] {:session-restoring? (atom restoring?) :session-pending (atom ::old) :tabs (atom tabs)})]
    (with-redefs [editor/editor-text (fn [_] "")
                  win-reopen/session-entry! (fn [_ opts] (swap! log conj [:entry opts]) ::theirs)
                  win-reopen/restore-session! (fn [_ opts] (swap! log conj [:restore opts]))]
      (testing "a reopen is pending: the folder's entry takes its place, nothing opens yet"
        (let [c (ctx true [(tab nil)])]
          (win-reopen/folder-arrived! c)
          (is (= ::theirs @(:session-pending c)))
          (is (= [[:entry {:reroot? false}]] @log))))
      (testing "an unused window: the folder's tabs open now"
        (reset! log [])
        (let [c (ctx false [(tab nil)])]
          (win-reopen/folder-arrived! c)
          (is (= [[:restore {:reroot? false}]] @log))
          (is (= ::old @(:session-pending c)))))
      (testing "a window with the user's tabs: nothing"
        (reset! log [])
        (win-reopen/folder-arrived! (ctx false [(tab "/mine.clj")]))
        (win-reopen/folder-arrived! (ctx false [(tab nil) (tab nil)]))
        (is (= [] @log))))))
