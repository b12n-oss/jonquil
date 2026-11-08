(ns jonquil.gtk.parts.trace
  "LM4's Trace panel as a window part (the commands and the label are
  jonquil.parts.trace/spec). Moved out of jonquil.gtk.window: the pane, its
  handlers, the four trace fns and the after-evaluation refresh."
  (:require [clojure.string :as str]
            [jonquil.clj :as clj]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.trace-pane :as trace-pane]
            [jonquil.help :as help]
            [jonquil.inspect :as inspect]))

(defn- trace-ev
  "The evaluator when it can trace, else nil. Read from ev* on every call."
  [{:keys [ev*]}]
  (let [e @ev*] (when (and e (satisfies? ev/Trace e)) e)))

(defn- need-jolt!
  [{:keys [set-status!]}]
  (set-status! "; Trace needs Jolt."))

(defn- trace-said
  "Says msg in the status bar, from any thread."
  [{:keys [set-status!]} msg]
  (on-gui! (fn [] (set-status! msg))))

(defn- apply-log!
  "On the GUI thread: shows a :log answer's rows, opening the panel when show?."
  [{:keys [set-status! tracep right show-right-panel!]} show? r]
  (if-let [err (:error r)]
    (when show? (set-status! (str "; " err)))
    (do (trace-pane/show! tracep (:entries r))
        (when show? (show-right-panel! right :trace)))))

;; Tracing (LM4, jonquil.trace-rt through ev/Trace): the panel's
;; rows are the whole log, fetched again when shown and after every
;; evaluation while it's showing
(defn- trace-refresh!
  [ctx show?]
  (if-let [e (trace-ev ctx)]
    (ev/trace-op! e :log {:since -1}
                  (fn [r] (on-gui! (fn [] (apply-log! ctx show? r)))))
    (when show? (need-jolt! ctx))))

(defn- untrace!
  "Stops tracing the target, saying so."
  [ctx {:keys [e sym n]}]
  (ev/trace-op! e :untrace {:sym sym :ns n}
                (fn [u] (trace-said ctx (if (:untraced u)
                                          (str "; Stopped tracing " (:untraced u) ".")
                                          (str "; " (:error u)))))))

(defn- toggled!
  "The :trace answer r: already traced means untrace it."
  [ctx target r]
  (cond
    (:already r) (untrace! ctx target)
    (:traced r) (trace-said ctx (str "; Tracing " (:traced r) "."))
    :else (trace-said ctx (str "; " (:error r)))))

(defn- trace-sym!
  "Traces sym in the buffer's namespace, or untraces it when it already is."
  [ctx e snap sym]
  (let [n (str (or (clj/buffer-ns (:text snap)) (ev/prompt-ns e)))]
    (ev/trace-op! e :trace {:sym sym :ns n}
                  (fn [r] (toggled! ctx {:e e :sym sym :n n} r)))))

(defn- trace-toggle!
  [{:keys [active-tab set-status!] :as ctx}]
  (if-let [e (trace-ev ctx)]
    (let [snap (editor/snapshot (:ed (active-tab)))
          sym (:symbol (help/target snap))]
      (if (or (nil? sym) (str/starts-with? (str sym) ":"))
        (set-status! "; No function at the caret.")
        (trace-sym! ctx e snap sym)))
    (need-jolt! ctx)))

(defn- trace-clear!
  [ctx]
  (if-let [e (trace-ev ctx)]
    (ev/trace-op! e :clear {} (fn [_] (trace-said ctx "; Cleared the recorded calls.")
                                (trace-refresh! ctx false)))
    (need-jolt! ctx)))

(defn- untraced-all!
  "Says how many functions the :untrace-all answer r stopped tracing."
  [ctx r]
  (trace-said ctx (if-let [n (:count r)]
                    (str "; Stopped tracing " n " function" (when (not= 1 n) "s") ".")
                    (str "; " (:error r)))))

(defn- trace-untrace-all!
  [ctx]
  (if-let [e (trace-ev ctx)]
    (ev/trace-op! e :untrace-all {} (fn [r] (untraced-all! ctx r)))
    (need-jolt! ctx)))

(defn- refresh-when-showing!
  "After an evaluation: refreshes the rows while the panel shows."
  [{:keys [tracep right] :as ctx}]
  (when (and (pos? (g/gtk-widget-get-visible (:root tracep)))
             (pos? (g/gtk-widget-get-visible (:slot right))))
    (trace-refresh! ctx false)))

(defn- set-handlers!
  "Wires the rows' Enter and the close button."
  [{:keys [tracep right hide-right-panel! inspect!]}]
  ;; The Trace panel's rows: Enter inspects that call's value (its
  ;; arguments, for a call that threw)
  (trace-pane/set-handlers!
   tracep {:on-activate (fn [entry]
                          (inspect! [:start-trace {:id (:id entry) :part (if (:threw entry) :args :ret)
                                                   :label (inspect/root-label (:args entry))}]))
           :on-close (fn [] (hide-right-panel! right :trace))}))

(defn- install
  "The Trace part for one window: builds the pane, wires its handlers, resets
  the console's after-evaluation hook and answers the trace fns. The
  evaluator is read from ev* on every call."
  [{:keys [cv] :as wctx}]
  (let [tracep (trace-pane/make-pane)
        ctx (assoc wctx :tracep tracep)
        show-fn (fn [] (trace-refresh! ctx true))]
    (reset! (:after-eval* cv)
            (fn [] (on-gui! (fn [] (refresh-when-showing! ctx)))))
    (set-handlers! ctx)
    {:fns {:trace-toggle-fn (fn [] (trace-toggle! ctx)) :trace-show-fn show-fn
           :trace-clear-fn (fn [] (trace-clear! ctx))
           :trace-untrace-all-fn (fn [] (trace-untrace-all! ctx))}
     :ui {:trace-pane tracep}
     :panels {:trace {:root (:root tracep)
                      :focus! (fn [] (g/gtk-widget-grab-focus (:list tracep)))
                      :open! show-fn}}}))

(def part
  "The Trace part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :trace}))
