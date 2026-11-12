(ns jonquil.gtk.parts.time-form
  "Time Form (time the form at the caret) as a window part: it evaluates
  jonquil.timing/code around the form in the window's runtime, behind an
  alert that Don't Ask Again silences for the window (the command is
  jonquil.parts.time-form/spec). Moved out of jonquil.gtk.window with no
  change beyond reading the window's bindings from wctx."
  (:require [jonquil.commands :as commands]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.timing :as timing]))

(def ^:private TIME-ALERT {:cancel 2 :default 0})

(defn- start-timing!
  "Evaluates the timing harness around text (the form snap had at the
  caret) in the window's runtime as Inspect Form evaluates, so the console
  gets its ; Time: line and the map it answers. A tab that closed while
  its alert was up times nothing."
  [{:keys [tab tabs cv ev* status-label make-eval-opts]} snap text]
  (when (some #(= (:id tab) (:id %)) @tabs)
    (let [{:keys [ed]} tab
          opts (make-eval-opts {:ed ed :cv cv :ev* ev* :status-label status-label :run? false})]
      (cview/append! cv :system (str "; Timing " (timing/form-label text) " for about a second. It runs over and over, so any side effect repeats too.\n"))
      (let [_ (when-not (ev/evaluating? @ev*) (cview/note-run-start! cv))
            warning (ev/eval! @ev* (timing/code text 1000)
                              (assoc opts :run? false :ns-source (:text snap) :owner (:id tab)))]
        (when warning (cview/append! cv :system warning))))))

(defn- time-choice!
  "The timing alert's decision: index 0 Time It, 1 Time It and stop asking
  in this window (time-confirmed?), 2 Cancel; an :error means the alert
  was dismissed. Cancel and a dismissal time nothing."
  [{:keys [time-confirmed?] :as ctx} snap text {:keys [index error]}]
  (cond
    error nil
    (= index 0) (start-timing! ctx snap text)
    (= index 1) (do (reset! time-confirmed? true) (start-timing! ctx snap text))
    :else nil))

(defn- time-form!
  "Time the form at the caret: Run Form's form, wrapped in
  jonquil.timing/code. The form runs many times, side effects and all, so
  an alert asks first (ask-time!, which rows can replace) until
  Don't Ask Again sets time-confirmed? for this window. The alert shares
  the other alerts' alert-pending? guard, as the tree's trash alert does,
  so it never opens over a close alert. Dialects without the harness's
  System/nanoTime say so and evaluate nothing."
  [{:keys [tab cv ev* sel* ask-time! time-confirmed? set-status! alert-pending? refuse-foreign!] :as ctx}]
  (let [{:keys [ed]} tab]
    (cond
      (refuse-foreign! ctx) nil
      (not (timing/supported? (:id (:profile @sel*))))
      (set-status! "; Timing needs Jolt or JVM Clojure.")
      :else
      (let [snap (editor/snapshot ed)]
        (if-let [{:keys [text]} (commands/run-form snap)]
          (cond
            @time-confirmed? (start-timing! ctx snap text)
            @alert-pending? (set-status! "Another alert is open.")
            :else
            (do (reset! alert-pending? true)
                ;; an older message (a No form at the caret. from a miss, say)
                ;; shouldn't sit under the alert; a running eval keeps its own
                (when-not (ev/evaluating? @ev*) (set-status! "Ready"))
                ;; complete-alert! releases the guard for the real alert; this
                ;; also releases it for a stand-in, and a throw while building
                ;; the alert releases it too
                (try
                  (ask-time! (timing/confirm-alert text)
                             (fn [result] (reset! alert-pending? false) (time-choice! ctx snap text result)))
                  (catch :default e
                    (reset! alert-pending? false)
                    (cview/append! cv :error (str "; time: " (ex-message e) "\n"))))))
          (set-status! "No form at the caret."))))))

(defn- install
  "The time-form part for one window. Answers :time-form-fn, and the ui
  entries :time-ask* and :time-confirmed? (both made per install, so a
  window's Don't Ask Again never reaches another window)."
  [{:keys [win tabs active-tab cv ev* sel* status-label set-status! alert-pending?
           show-alert! make-eval-opts refuse-foreign!]}]
  (let [;; Time the form's alert: once Don't Ask Again is chosen, this
        ;; window times without asking. ask-time* shows the real alert;
        ;; the self-test swaps it for a stand-in (nil puts the real one back).
        time-confirmed? (atom false)
        ask-time* (atom nil)
        ask-time! (fn [alert on-choice]
                    (if-let [f @ask-time*]
                      (f alert on-choice)
                      (show-alert! win alert TIME-ALERT on-choice)))
        time-form-fn (fn [] (time-form! {:tab (active-tab) :tabs tabs :cv cv :ev* ev* :sel* sel* :status-label status-label
                                         :ask-time! ask-time! :time-confirmed? time-confirmed?
                                         :set-status! set-status! :alert-pending? alert-pending?
                                         :make-eval-opts make-eval-opts :refuse-foreign! refuse-foreign!}))]
    {:fns {:time-form-fn time-form-fn}
     :ui {:time-ask* ask-time* :time-confirmed? time-confirmed?}}))

(def part
  "The time-form part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :time-form}))
