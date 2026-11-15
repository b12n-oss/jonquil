(ns jonquil.gtk.window.quit-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :as callbacks]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.window.alerts :as win-alerts]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.quit :as win-quit]
            [jonquil.runtime-registry :as registry]
            [jonquil.tabs :as tabs]))

(defn- tab [path dirty?] {:ed {:dirty (atom dirty?)} :path (atom path)})

(defn- run-stubbed
  "Runs f with the close path's GTK, editor and file calls recording into
  log. A tab's ed is {:dirty (atom b)}; save-to! of a path in fail-paths
  leaves it dirty; a Save dialog is logged as [:dialog mode win cb]."
  [log fail-paths f]
  (with-redefs [editor/dirty? (fn [ed] @(:dirty ed))
                g/gtk-window-close (fn [win] (swap! log conj [:close win]))
                win-files/save-to! (fn [ed _cv _path p]
                                     (swap! log conj [:save p])
                                     (when-not (contains? fail-paths p) (reset! (:dirty ed) false)))
                win-files/file-dialog! (fn [mode win cb] (swap! log conj [:dialog mode win cb]))
                win-files/dialog-dismissed? (fn [r] (= 2 (:code r)))
                cview/append! (fn [_cv kind text] (swap! log conj [:append kind text]))]
    (f)))

(defn- choice-ctx [log confirmed]
  {:dirty-tabs [] :cv ::cv :win ::win :confirmed-close? confirmed
   :select! (fn [t] (swap! log conj [:select @(:path t)]))})

(deftest close-choice-decisions-test
  (let [log (atom []) confirmed (atom false) ctx (choice-ctx log confirmed)]
    (run-stubbed log #{}
                 (fn []
                   (is (nil? (#'win-quit/close-choice! ctx {:error "dismissed"})))
                   (is (nil? (#'win-quit/close-choice! ctx {:index 2})))
                   (is (nil? (#'win-quit/close-choice! ctx {:index 3})))
                   (is (= [] @log))
                   (is (false? @confirmed))
                   (#'win-quit/close-choice! ctx {:index 1})
                   (is (true? @confirmed))
                   (reset! confirmed false)
                   (#'win-quit/close-choice! ctx {:index 0})))
    (is (true? @confirmed))
    (is (= [[:close ::win] [:close ::win]] @log))))

(deftest save-all-stops-at-the-first-tab-still-dirty-test
  (let [log (atom []) confirmed (atom false) ctx (choice-ctx log confirmed)
        a (tab "/r/a.clj" true) b (tab "/r/b.clj" true) c (tab "/r/c.clj" true)]
    (run-stubbed log #{"/r/b.clj"} (fn [] (#'win-quit/save-all! ctx [a b c])))
    (is (= [[:save "/r/a.clj"] [:save "/r/b.clj"] [:select "/r/b.clj"]] @log))
    (is (false? @confirmed))
    (reset! log [])
    (run-stubbed log #{} (fn [] (#'win-quit/save-all! ctx [b c])))
    (is (= [[:save "/r/b.clj"] [:save "/r/c.clj"] [:close ::win]] @log))
    (is (true? @confirmed))))

(deftest save-all-asks-where-an-untitled-tab-goes-test
  (let [log (atom []) confirmed (atom false) ctx (choice-ctx log confirmed) u (tab nil true)
        answer! (fn [r] (let [[_ _ _ cb] (first (filter #(= :dialog (first %)) @log))] (cb r)))]
    (run-stubbed log #{}
                 (fn []
                   (#'win-quit/save-all! ctx [u])
                   (answer! {:error "dismissed" :code 2})
                   (answer! {:error "full" :code 9})
                   (answer! {:path "/r/u.clj"})))
    (is (= [[:select nil] [:dialog :save ::win] [:append :error "; save: full\n"] [:save "/r/u.clj"] [:close ::win]]
           (mapv (fn [e] (if (= :dialog (first e)) (vec (take 3 e)) e)) @log)))
    (is (true? @confirmed))))

(deftest unparent-hides-an-open-popover-first-test
  (let [log (atom []) pops (atom [{:id 1 :popover ffi/null} {:id 2 :popover ffi/null}])]
    (with-redefs [popover/open? (fn [p] (= 1 (:id p)))
                  popover/hide! (fn [p] (swap! log conj [:hide (:id p)]))
                  g/gtk-widget-get-parent (fn [_] ffi/null)
                  g/gtk-widget-unparent (fn [_] (swap! log conj [:unparent]))]
      (#'win-quit/unparent-registered-popovers! pops))
    (is (= [[:hide 1]] @log))))

(defn- close-ctx
  "close-request-fn's map over tabs ts with the given flags; ev* holds an
  evaluator whose stop! logs, and throws \"dead\" when stop-throws?."
  [log ts {:keys [confirmed? suppress? save-session! stop-throws? terminal-busy?]}]
  {:tabs (atom ts) :cv ::cv :win ::win :app ::app
   :confirmed-close? (atom (boolean confirmed?)) :suppress-close? (atom (boolean suppress?))
   :closing? (atom false) :popovers (atom [])
   :ev* (atom #_{:clj-kondo/ignore [:missing-protocol-method]}
         (reify ev/Evaluator
           (stop! [_] (swap! log conj [:stop]) (when stop-throws? (throw (ex-info "dead" {}))))))
   :select! (fn [t] (swap! log conj [:select @(:path t)]))
   :save-session! save-session!
   :terminal-busy? (when (some? terminal-busy?) (fn [] @terminal-busy?))
   :terminal-end! (fn [] (swap! log conj [:terminal-end]))})

(defn- with-quit-stubs
  "run-stubbed, plus show-alert! (logged as [:alert win spec opts on-choice],
  or a throw of \"boom\" when alert-throws?), the registry and the
  application quit. Puts alert-pending? back as it was."
  [log {:keys [alert-throws?]} f]
  (let [old @win-alerts/alert-pending?]
    (try
      (with-redefs [win-alerts/show-alert! (fn [win spec opts on-choice]
                                             (when alert-throws? (throw (ex-info "boom" {})))
                                             (swap! log conj [:alert win spec opts on-choice]))
                    registry/unregister! (fn [_] (swap! log conj [:unregister]))
                    g/g-application-quit (fn [app] (swap! log conj [:quit app]))]
        (run-stubbed log #{} f))
      (finally (reset! win-alerts/alert-pending? old)))))

(def ^:private pop-1 {:id 1 :popover :pop-widget})

(defn- with-popover-stubs
  "Runs f with popover/open? true, hide! and gtk-widget-unparent logging
  [:hide id] and [:unparent widget] into log, and the widget parented."
  [log f]
  (with-redefs [popover/open? (fn [_] true)
                popover/hide! (fn [p] (swap! log conj [:hide (:id p)]))
                g/gtk-widget-get-parent (fn [_] :parent)
                g/gtk-widget-unparent (fn [w] (swap! log conj [:unparent w]))]
    (f)))

(defn- seed-popover! [ctx] (reset! (:popovers ctx) [pop-1]))

(deftest close-request-asks-once-for-every-dirty-tab-test
  (let [log (atom [])
        ctx (close-ctx log [(tab "/r/a.clj" true) (tab "/r/b.clj" false) (tab nil true)] {})
        handler (#'win-quit/close-request-fn ctx)]
    (with-quit-stubs log {}
      (fn []
        (reset! win-alerts/alert-pending? false)
        (is (= 1 (handler ::win nil)))
        (is (true? @win-alerts/alert-pending?))
        (is (= 1 (handler ::win nil)))
        (let [alerts (filter #(= :alert (first %)) @log)
              [_ win spec opts on-choice] (first alerts)]
          (is (= 1 (count alerts)))
          (is (= [::win (tabs/close-alert ["a.clj" "Untitled"]) win-alerts/CLOSE-ALERT] [win spec opts]))
          (on-choice {:index 2})
          (is (false? @(:confirmed-close? ctx)))
          (on-choice {:index 1})
          (is (true? @(:confirmed-close? ctx))))))
    (is (= [:close ::win] (last @log)))
    (is (false? @(:closing? ctx)))))

(deftest close-request-alert-throw-resets-the-guard-test
  (let [log (atom []) ctx (close-ctx log [(tab "/r/a.clj" true)] {})]
    (with-quit-stubs log {:alert-throws? true}
      (fn []
        (reset! win-alerts/alert-pending? false)
        (is (= 1 ((#'win-quit/close-request-fn ctx) ::win nil)))
        (is (false? @win-alerts/alert-pending?))))
    (is (= [[:append :error "; close: boom\n"]] @log))))

(deftest close-request-suppressed-close-test
  (doseq [[ts confirmed?] [[[(tab "/r/a.clj" false)] false] [[(tab "/r/a.clj" true)] true] [[] true]]]
    (let [log (atom []) ctx (close-ctx log ts {:confirmed? confirmed? :suppress? true})]
      (seed-popover! ctx)
      (with-popover-stubs log
        (fn [] (with-quit-stubs log {} (fn [] (is (= 1 ((#'win-quit/close-request-fn ctx) ::win nil)))))))
      (is (true? @(:closing? ctx)))
      (is (false? @(:confirmed-close? ctx)))
      (is (= [] @log)))))

(deftest close-request-real-quit-order-test
  (let [log (atom []) err (java.io.StringWriter.)
        ctx (close-ctx log [(tab "/r/a.clj" false)]
                       {:save-session! (fn [] (swap! log conj [:save-session]) "disk full")})]
    (seed-popover! ctx)
    (with-popover-stubs log
      (fn [] (with-quit-stubs log {}
               (fn [] (binding [*err* err] (is (= 0 ((#'win-quit/close-request-fn ctx) ::win nil))))))))
    (is (= [[:hide 1] [:unparent :pop-widget] [:terminal-end] [:save-session] [:stop] [:unregister] [:quit ::app]] @log))
    (is (true? @(:closing? ctx)))
    (is (= "jonquil: saving the session failed: disk full\n" (str err)))))

(deftest close-request-real-quit-survives-throws-test
  (doseq [[save-session! said] [[nil ""]
                                [(fn [] nil) ""]
                                [(fn [] (throw (ex-info "boom" {}))) "jonquil: saving the session failed: boom\n"]]]
    (let [log (atom []) err (java.io.StringWriter.)
          ctx (close-ctx log [] {:save-session! save-session! :stop-throws? true})]
      (seed-popover! ctx)
      (with-popover-stubs log
        (fn [] (with-quit-stubs log {}
                 (fn [] (binding [*err* err] (is (= 0 ((#'win-quit/close-request-fn ctx) ::win nil))))))))
      (is (= [[:hide 1] [:unparent :pop-widget] [:terminal-end] [:stop] [:unregister] [:quit ::app]] @log))
      (is (= (str said "jonquil: stopping the runtime failed: dead\n") (str err))))))

(deftest close-request-confirmed-dirty-tab-takes-the-real-quit-test
  (let [log (atom [])
        ctx (close-ctx log [(tab "/r/a.clj" true)]
                       {:confirmed? true :save-session! (fn [] (swap! log conj [:save-session]) nil)})]
    (seed-popover! ctx)
    (with-popover-stubs log
      (fn [] (with-quit-stubs log {}
               (fn [] (is (= 0 ((#'win-quit/close-request-fn ctx) ::win nil)))))))
    (is (= [[:hide 1] [:unparent :pop-widget] [:terminal-end] [:save-session] [:stop] [:unregister] [:quit ::app]] @log))
    (is (true? @(:closing? ctx)))))

(deftest close-request-alert-save-choice-reaches-close-choice-test
  (let [log (atom [])
        ctx (close-ctx log [(tab "/r/a.clj" true) (tab "/r/b.clj" true)] {})]
    (with-quit-stubs log {}
      (fn []
        (reset! win-alerts/alert-pending? false)
        (is (= 1 ((#'win-quit/close-request-fn ctx) ::win nil)))
        (let [[_ _ _ _ on-choice] (first (filter #(= :alert (first %)) @log))]
          (reset! log [])
          ;; This save-to! never clears the dirty flag, so a's save fails.
          (with-redefs [win-files/save-to! (fn [_ed _cv _path p] (swap! log conj [:save p]))]
            (on-choice {:index 0}))
          (is (= [[:save "/r/a.clj"] [:select "/r/a.clj"]] @log))
          (is (false? @(:confirmed-close? ctx))))))))

(deftest build-makes-the-close-flags-test
  (let [w (win-quit/build {:x 1})]
    (is (= #{:x :confirmed-close? :suppress-close? :closing?} (set (keys w))))
    (is (= [false false false] (map (comp deref w) [:confirmed-close? :suppress-close? :closing?])))
    (is (not (identical? (:suppress-close? w) (:closing? w))))
    (is (not (identical? (:confirmed-close? w) (:suppress-close? w))))
    (is (not (identical? (:confirmed-close? w) (:closing? w))))))

(deftest close-request-asks-about-a-busy-terminal-test
  ;; a clean window whose terminal is running something: one alert, Cancel
  ;; keeps the window, Close Anyway closes it and the next request goes on
  (let [log (atom []) busy (atom true)
        ctx (close-ctx log [(tab "/r/a.clj" false)] {:suppress? true :terminal-busy? busy})
        handler (#'win-quit/close-request-fn ctx)
        alerts #(filter (fn [e] (= :alert (first e))) @log)]
    (with-quit-stubs log {}
      (fn []
        (reset! win-alerts/alert-pending? false)
        (is (= 1 (handler ::win nil)))
        (is (false? @(:closing? ctx)) "nothing closes while the question is open")
        (is (= 1 (handler ::win nil)) "a second request shows no second alert")
        (let [[_ win spec opts on-choice] (first (alerts))]
          (is (= 1 (count (alerts))))
          (is (= [::win tabs/terminal-alert win-alerts/TERMINAL-ALERT] [win spec opts]))
          ;; Cancel: the alert machinery clears the guard after the handler
          (on-choice {:index 1})
          (reset! win-alerts/alert-pending? false)
          (is (not-any? #(= [:close ::win] %) @log))
          (is (= 1 (handler ::win nil)) "the next request asks again")
          (is (= 2 (count (alerts))))
          ;; Close Anyway closes the window again, and that request goes on
          ((nth (second (alerts)) 4) {:index 0})
          (reset! win-alerts/alert-pending? false)
          (is (= [:close ::win] (last @log)))
          (is (= 1 (handler ::win nil)))
          (is (true? @(:closing? ctx)) "the confirmed request proceeds")
          (is (= 2 (count (alerts))) "without a third alert"))))))

(deftest close-request-skips-an-idle-terminal-test
  (doseq [busy [false nil]]
    (let [log (atom [])
          ctx (close-ctx log [(tab "/r/a.clj" false)] {:suppress? true :terminal-busy? (when (some? busy) (atom busy))})]
      (with-quit-stubs log {}
        (fn [] (is (= 1 ((#'win-quit/close-request-fn ctx) ::win nil)))))
      (is (true? @(:closing? ctx)) (str "terminal busy? " (pr-str busy)))
      (is (= [] (filter #(= :alert (first %)) @log))))))

(deftest close-request-unsaved-text-is-asked-before-the-terminal-test
  ;; Don't Save closes the window from inside the first alert's handler,
  ;; while that alert still counts as pending: the terminal question is
  ;; tried again once the handler has returned
  (let [log (atom []) busy (atom true) queued (atom [])
        ctx (close-ctx log [(tab "/r/a.clj" true)] {:suppress? true :terminal-busy? busy})
        handler (#'win-quit/close-request-fn ctx)
        alerts #(filter (fn [e] (= :alert (first e))) @log)]
    (with-redefs [callbacks/on-gui! (fn [f] (swap! queued conj f))]
      (with-quit-stubs log {}
        (fn []
          (reset! win-alerts/alert-pending? false)
          (is (= 1 (handler ::win nil)))
          (is (= (tabs/close-alert ["a.clj"]) (nth (first (alerts)) 2)) "unsaved text first")
          ;; Don't Save, as the alert's handler: confirmed, and the window is
          ;; closed again while alert-pending? is still true
          (reset! (:confirmed-close? ctx) true)
          (is (= 1 (handler ::win nil)))
          (is (= 1 (count (alerts))) "no terminal alert yet")
          (is (= 1 (count @queued)) "one deferred try is queued")
          ;; the first alert's handler returns and clears the guard
          (reset! win-alerts/alert-pending? false)
          ((first @queued))
          (is (= [:close ::win] (last @log)) "the deferred try closes the window again")
          (is (= 1 (handler ::win nil)))
          (is (= tabs/terminal-alert (nth (second (alerts)) 2)) "and now the terminal is asked about")
          ;; Cancel there takes the confirmed close of unsaved text back
          ((nth (second (alerts)) 4) {:index 1})
          (is (false? @(:confirmed-close? ctx))))))))

(deftest real-quit-ends-the-terminal-test
  (let [log (atom [])
        ctx (close-ctx log [(tab "/r/a.clj" false)] {})]
    (with-quit-stubs log {}
      (fn [] (is (= 0 ((#'win-quit/close-request-fn ctx) ::win nil)))))
    (is (some #{[:terminal-end]} @log))
    (is (< (.indexOf ^java.util.List @log [:terminal-end]) (.indexOf ^java.util.List @log [:quit ::app]))
        "before the application quits")))
