(ns jonquil.mcp.window-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.window :as gw]
            [jonquil.help :as help]
            [jonquil.mcp.tools :as tools]
            [jonquil.mcp.window :as w]))

(defn- sync-gui [f] (f))

(defn- tools-for
  "The window table over a fake ui map, thunks run synchronously."
  [ui & {:as opts}]
  (w/make-window-tools (merge {:ui ui :dir "/tmp" :gui sync-gui :grace-ms 100} opts)))

(defn- call [t name args] ((:call t) name args))

(defn- evaluator
  "An Evaluator stub: evaluating? and waiting-for-input? read their args
  (a value or a fn); lookup and complete return canned values."
  [{:keys [evaluating? waiting? lookup complete]}]
  (let [v (fn [x] (if (fn? x) (x) x))]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify ev/Evaluator
      (evaluating? [_] (v evaluating?))
      (waiting-for-input? [_] (v waiting?))
      (lookup [_ _ _ _] lookup)
      (complete [_ _ _ _ _] complete))))

(defn- millis-since [t0] (/ (- (System/nanoTime) t0) 1000000.0))

(deftest defs-are-the-nineteen
  (is (= ["jonquil_eval" "jonquil_run" "jonquil_run_selection" "jonquil_stop"
          "jonquil_open" "jonquil_tabs" "jonquil_select_tab" "jonquil_read_tab"
          "jonquil_replace_text" "jonquil_save" "jonquil_console" "jonquil_doc"
          "jonquil_complete" "jonquil_check_brackets" "jonquil_project"
          "jonquil_plugins_list" "jonquil_plugin_read" "jonquil_plugin_propose" "jonquil_plugin_status"]
         (map #(get % "name") (:defs (tools-for {}))))))

(defn- plugin-ui
  "A fake ui over a plugins folder that doesn't exist, its propose hook
  recording each call and answering pending v1."
  [calls & {:keys [plugins?] :or {plugins? true}}]
  {:plugin-state* (fn [] {:plugins? plugins? :plugins-dir "/nonexistent/jonquil-pmcp" :registry {:plugins {}} :disabled #{}})
   :plugin-propose!* (fn [m] (swap! calls conj m) {:status :pending :n 1 :problems [] :lines []})})

(deftest plugin-tools-over-a-fake-ui
  (let [calls (atom [])
        t (tools-for (plugin-ui calls))]
    (is (= {:error? false
            :text "status: pending\nproposal: v1\nThe user reviews it in the window. Call jonquil_plugin_status with id fmt and n 1 to see what they decide."}
           (call t "jonquil_plugin_propose" {"id" "fmt" "source" "(ns x)" "reason" "faster"})))
    (is (= [{:id "fmt" :source "(ns x)" :reason "faster"}] @calls))
    (let [r (call t "jonquil_plugin_propose" {"id" "fmt" "source" "(ns x)" "reason" " "})]
      (is (:error? r))
      (is (str/starts-with? (:text r) "status: refused\nproblems:\n- a proposal needs a reason")))
    (is (:error? (call t "jonquil_plugin_propose" {"id" "fmt" "source" "(ns x)"})))
    (is (= 1 (count @calls)) "a refusal never reaches the hook")
    (is (= {:text "No plugins. Call jonquil_plugin_propose to propose one." :error? false}
           (call t "jonquil_plugins_list" {})))
    (is (= {:text "fmt has no proposals. Call jonquil_plugin_propose to make one." :error? true}
           (call t "jonquil_plugin_status" {"id" "fmt"})))
    (is (= {:text "There's no plugin fmt. Call jonquil_plugins_list to see them." :error? true}
           (call t "jonquil_plugin_read" {"id" "fmt"})))
    (is (thrown-with-msg? Throwable #"must be a number" (call t "jonquil_plugin_read" {"id" "fmt" "version" "2"})))
    (is (thrown-with-msg? Throwable #"must be a number" (call t "jonquil_plugin_status" {"id" "fmt" "n" "2"}))))
  (let [calls (atom [])
        r (call (tools-for (plugin-ui calls :plugins? false)) "jonquil_plugin_propose"
                {"id" "fmt" "source" "(ns x)" "reason" "faster"})]
    (is (str/includes? (:text r) "plugins are off in this window"))
    (is (= [] @calls))))

(deftest tab-line-format
  (is (= "3 * dirty /x/y.clj" (w/tab-line {:id 3 :active? true :dirty? true :path "/x/y.clj"})))
  (is (= "4 - saved /a.clj" (w/tab-line {:id 4 :active? false :dirty? false :path "/a.clj"})))
  (is (= "1 * saved Untitled" (w/tab-line {:id 1 :active? true :dirty? false :path nil})))
  (is (= "2 - dirty Untitled" (w/tab-line {:id 2 :active? false :dirty? true :path nil}))))

(deftest clamp-since-bounds
  (is (= 0 (w/clamp-since nil 10)))
  (is (= 0 (w/clamp-since -5 10)))
  (is (= 10 (w/clamp-since 99 10)))
  (is (= 4 (w/clamp-since 4 10))))

(deftest replace-op-shape
  (is (= {:edits [[0 3 "xy"]] :caret 2} (w/replace-op "abc" "xy"))))

(deftest gui-call-ok
  (is (= 3 (w/gui-call sync-gui (fn [] (+ 1 2)) 1000))))

(deftest gui-call-err
  (is (= "boom" (try (w/gui-call sync-gui (fn [] (throw (ex-info "boom" {}))) 1000)
                     (catch Throwable e (ex-message e))))))

(deftest gui-call-timeout
  (is (= "The window did not respond."
         (try (w/gui-call (fn [_] nil) (fn [] 1) 50)
              (catch Throwable e (ex-message e))))))

(deftest eval-done-maps-to-result-text
  (let [r {:status :ok :printed "3"}
        t (tools-for {:mcp-eval!* (fn [_ notify] (notify {:done {:result r :output "hi\n"}}) (fn [] "hi\n"))
                      :evaluator* (fn [] (evaluator {}))})]
    (is (= {:text (tools/result-text "hi\n" r) :error? false}
           (call t "jonquil_eval" {"code" "x"})))))

(deftest done-error-status-is-error
  (let [t (tools-for {:mcp-run!* (fn [notify] (notify {:done {:result {:status :error :error-text "bad"} :output ""}}) (fn [] ""))
                      :evaluator* (fn [] (evaluator {}))})]
    (is (:error? (call t "jonquil_run" {})))))

(deftest busy-refused-problem-none-map-to-errors
  (doseq [k [:busy :refused :problem :none]]
    (let [t (tools-for {:mcp-eval!* (fn [_ notify] (notify {k (str "msg " (name k))}) (fn [] ""))
                        :evaluator* (fn [] (evaluator {}))})]
      (is (= {:text (str "msg " (name k)) :error? true}
             (call t "jonquil_eval" {"code" "x"}))
          (name k)))))

(deftest run-selection-passes-offsets
  (let [seen (atom nil)
        t (tools-for {:mcp-run-selection!* (fn [s e notify] (reset! seen [s e]) (notify {:none "empty"}) (fn [] ""))
                      :evaluator* (fn [] (evaluator {}))})]
    (call t "jonquil_run_selection" {"start" 2 "end" 5})
    (is (= [2 5] @seen))))

(deftest eval-timeout-stuck
  (let [stopped (atom 0)
        t (tools-for {:mcp-eval!* (fn [_ _] (fn [] "partial"))
                      :evaluator* (fn [] (evaluator {:evaluating? true :waiting? false}))
                      :stop!* (fn [] (swap! stopped inc))})
        t0 (System/nanoTime)
        r (call t "jonquil_eval" {"code" "x" "timeout_ms" 100})]
    (is (:error? r))
    (is (str/starts-with? (:text r) "partial"))
    (is (str/includes? (:text r) "Timed out after 100 ms; interrupted, but the evaluation has not stopped."))
    (is (= 1 @stopped))
    (is (< (millis-since t0) 2000))))

(deftest eval-timeout-interrupted-in-grace
  (let [p (promise)
        t (tools-for {:mcp-eval!* (fn [_ notify] (deliver p notify) (fn [] "p"))
                      :evaluator* (fn [] (evaluator {:evaluating? true :waiting? false}))
                      :stop!* (fn [] ((deref p 1000 identity)
                                      {:done {:result {:status :interrupted} :output "p"}}))})
        r (call t "jonquil_eval" {"code" "x" "timeout_ms" 100})]
    (is (:error? r))
    (is (str/includes? (:text r) "Timed out after 100 ms; interrupted."))
    (is (not (str/includes? (:text r) "has not stopped")))))

(deftest eval-waiting-input-returns-early
  (let [stopped (atom 0)
        t (tools-for {:mcp-eval!* (fn [_ _] (fn [] "so far"))
                      :evaluator* (fn [] (evaluator {:evaluating? true :waiting? true}))
                      :stop!* (fn [] (swap! stopped inc))})
        t0 (System/nanoTime)
        r (call t "jonquil_eval" {"code" "x" "timeout_ms" 10000})]
    (is (< (millis-since t0) 2000))
    (is (= {:text "so far\n; waiting for console input (a person can type in the window, or call jonquil_stop)"
            :error? false}
           r))
    (is (zero? @stopped))))

(deftest stop-idle-does-not-interrupt
  (let [stopped (atom 0)
        t (tools-for {:evaluator* (fn [] (evaluator {:evaluating? false}))
                      :stop!* (fn [] (swap! stopped inc))})]
    (is (= {:text "Nothing is running." :error? false} (call t "jonquil_stop" {})))
    (is (zero? @stopped))))

(deftest stop-running-reports
  (let [running (atom true)
        t (tools-for {:evaluator* (fn [] (evaluator {:evaluating? (fn [] @running)}))
                      :stop!* (fn [] (reset! running false))})]
    (is (= {:text "Stopped." :error? false} (call t "jonquil_stop" {})))))

(deftest stop-not-yet-stopped
  (let [t (tools-for {:evaluator* (fn [] (evaluator {:evaluating? true}))
                      :stop!* (fn [] nil)})]
    (is (= {:text "Interrupt requested; the evaluation has not stopped yet." :error? false}
           (call t "jonquil_stop" {})))))

(deftest select-unknown-id-errors
  (let [t (tools-for {:tabs* (fn [] [{:id 1}]) :select-tab! (fn [_] (throw (ex-info "no" {})))})]
    (is (= {:text "No tab with id 9." :error? true}
           (call t "jonquil_select_tab" {"id" 9})))))

(deftest required-args-throw
  (let [t (tools-for {})]
    (doseq [[n args] [["jonquil_eval" {}]
                      ["jonquil_open" {}]
                      ["jonquil_run_selection" {"end" 3}]
                      ["jonquil_run_selection" {"start" 3}]
                      ["jonquil_replace_text" {}]]]
      (is (thrown? Throwable (call t n args)) (str n " " args)))))

(deftest doc-and-complete-use-window-evaluator
  (let [t (tools-for {:evaluator* (fn [] (evaluator {:lookup {:name "map" :doc "Maps."}
                                                     :complete (map str (range 60))}))})
        none (tools-for {:evaluator* (fn [] (evaluator {:complete []}))})]
    (is (= (help/doc-text "map" {:name "map" :doc "Maps."})
           (:text (call t "jonquil_doc" {"symbol" "map"}))))
    (is (= 50 (count (str/split-lines (:text (call t "jonquil_complete" {"prefix" "m"}))))))
    (is (= {:text "No completions for zz." :error? false}
           (call none "jonquil_complete" {"prefix" "zz"})))))

(deftest project-reports-live-selection
  (let [sel {:dialect :clojure :runtime :jolt :project-dir "/p" :source :auto :warnings ["w"]}
        t (tools-for {:selection* (fn [] sel)})]
    (is (= {:text (tools/selection-text sel) :error? false}
           (call t "jonquil_project" {})))))

(deftest check-brackets-uses-text
  (let [t (tools-for {})]
    (is (= {:text "ok" :error? false} (call t "jonquil_check_brackets" {"text" "(a)"})))
    (is (thrown? Throwable (call t "jonquil_check_brackets" {})))))

(deftest serve-window-eof-closes
  (let [closes (atom 0) choices (atom [])
        log (java.io.StringWriter.)]
    (binding [*err* log]
      (w/serve-window! {:ui {:tabs* (fn [] [])
                             :request-close!* (fn [] (swap! closes inc))
                             :close-choice! (fn [r] (swap! choices conj r))}
                        :dir "/some/dir" :read-line (fn [] nil) :write-line (fn [_] nil)
                        :gui sync-gui}))
    (is (= 1 @closes))
    (is (empty? @choices))
    (is (str/includes? (str log) "/some/dir"))))

(deftest no-active-tab-is-a-plain-error
  (let [t (tools-for {:tabs* (fn [] []) :active-tab* (fn [] nil)})]
    (is (= {:text "No active tab." :error? true} (call t "jonquil_save" {})))
    (is (= {:text "No active tab." :error? true} (call t "jonquil_read_tab" {})))
    (is (= {:text "No active tab." :error? true} (call t "jonquil_replace_text" {"text" "x"})))))

(deftest serve-window-quit-failure-is-logged
  (let [log (java.io.StringWriter.)]
    (binding [*err* log]
      (w/serve-window! {:ui {:tabs* (fn [] (throw (ex-info "tabs broke" {})))
                             :request-close!* (fn [] nil)}
                        :dir "/d" :read-line (fn [] nil) :write-line (fn [_] nil)
                        :gui sync-gui}))
    (is (str/includes? (str log) "tabs broke"))))

(deftest serve-window-loop-failure-is-logged-and-quits
  (let [closes (atom 0)
        log (java.io.StringWriter.)]
    (binding [*err* log]
      (w/serve-window! {:ui {:tabs* (fn [] [])
                             :request-close!* (fn [] (swap! closes inc))
                             :close-choice! (fn [_] nil)}
                        :dir "/d" :read-line (fn [] (throw (ex-info "stdin broke" {})))
                        :write-line (fn [_] nil)
                        :gui sync-gui}))
    (is (str/includes? (str log) "stdin broke"))
    (is (= 1 @closes))))

(deftest eval-timeout-already-idle-does-not-interrupt
  (let [stopped (atom 0)
        calls (atom 0)
        ;; busy for the waiting check, idle by the time the stop turn looks
        t (tools-for {:mcp-eval!* (fn [_ _] (fn [] "late"))
                      :evaluator* (fn [] (evaluator {:evaluating? (fn [] (< (swap! calls inc) 1))
                                                     :waiting? false}))
                      :stop!* (fn [] (swap! stopped inc))})
        r (call t "jonquil_eval" {"code" "x" "timeout_ms" 100})]
    (is (zero? @stopped))
    (is (:error? r))))

(deftest notifying-opts-notifies-when-base-on-complete-throws
  (let [out (atom [])
        got (atom nil)
        base {:on-output (fn [_ _] nil)
              :on-complete (fn [_] (throw (ex-info "malformed" {})))}
        opts (#'gw/notifying-opts base out (fn [m] (reset! got m)))]
    ((:on-output opts) :out "hi")
    (is (thrown? Throwable ((:on-complete opts) {:status :weird})))
    (is (= {:done {:result {:status :weird} :output "hi"}} @got))))
