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

(defn- install
  "The Trace part for one window: builds the pane, wires its handlers, resets
  the console's after-evaluation hook and answers the trace fns. The
  evaluator is read from ev* on every call."
  [{:keys [ev* cv right set-status! active-tab show-right-panel! hide-right-panel! inspect!]}]
  (let [tracep (trace-pane/make-pane)
        slot (:slot right)
        ;; Tracing (LM4, jonquil.trace-rt through ev/Trace): the panel's
        ;; rows are the whole log, fetched again when shown and after every
        ;; evaluation while it's showing
        trace-ev (fn [] (let [e @ev*] (when (and e (satisfies? ev/Trace e)) e)))
        trace-refresh! (fn [show?]
                         (if-let [e (trace-ev)]
                           (ev/trace-op! e :log {:since -1}
                                         (fn [r]
                                           (on-gui! (fn []
                                                      (if-let [err (:error r)]
                                                        (when show? (set-status! (str "; " err)))
                                                        (do (trace-pane/show! tracep (:entries r))
                                                            (when show? (show-right-panel! right :trace))))))))
                           (when show? (set-status! "; Trace needs Jolt."))))
        trace-said (fn [msg] (on-gui! (fn [] (set-status! msg))))
        trace-toggle-fn (fn []
                          (if-let [e (trace-ev)]
                            (let [snap (editor/snapshot (:ed (active-tab)))
                                  sym (:symbol (help/target snap))]
                              (if (or (nil? sym) (str/starts-with? (str sym) ":"))
                                (set-status! "; No function at the caret.")
                                (let [n (str (or (clj/buffer-ns (:text snap)) (ev/prompt-ns e)))]
                                  (ev/trace-op! e :trace {:sym sym :ns n}
                                                (fn [r]
                                                  (cond
                                                    (:already r)
                                                    (ev/trace-op! e :untrace {:sym sym :ns n}
                                                                  (fn [u] (trace-said (if (:untraced u)
                                                                                        (str "; Stopped tracing " (:untraced u) ".")
                                                                                        (str "; " (:error u))))))
                                                    (:traced r) (trace-said (str "; Tracing " (:traced r) "."))
                                                    :else (trace-said (str "; " (:error r)))))))))
                            (set-status! "; Trace needs Jolt.")))
        trace-show-fn (fn [] (trace-refresh! true))
        trace-clear-fn (fn []
                         (if-let [e (trace-ev)]
                           (ev/trace-op! e :clear {} (fn [_] (trace-said "; Cleared the recorded calls.")
                                                       (trace-refresh! false)))
                           (set-status! "; Trace needs Jolt.")))
        trace-untrace-all-fn (fn []
                               (if-let [e (trace-ev)]
                                 (ev/trace-op! e :untrace-all {}
                                               (fn [r] (trace-said (if-let [n (:count r)]
                                                                     (str "; Stopped tracing " n " function" (when (not= 1 n) "s") ".")
                                                                     (str "; " (:error r))))))
                                 (set-status! "; Trace needs Jolt.")))]
    (reset! (:after-eval* cv)
            (fn [] (on-gui! (fn [] (when (and (pos? (g/gtk-widget-get-visible (:root tracep)))
                                              (pos? (g/gtk-widget-get-visible slot)))
                                     (trace-refresh! false))))))
    ;; The Trace panel's rows: Enter inspects that call's value (its
    ;; arguments, for a call that threw)
    (trace-pane/set-handlers!
     tracep {:on-activate (fn [entry]
                            (inspect! [:start-trace {:id (:id entry) :part (if (:threw entry) :args :ret)
                                                     :label (inspect/root-label (:args entry))}]))
             :on-close (fn [] (hide-right-panel! right :trace))})
    {:fns {:trace-toggle-fn trace-toggle-fn :trace-show-fn trace-show-fn
           :trace-clear-fn trace-clear-fn :trace-untrace-all-fn trace-untrace-all-fn}
     :ui {:trace-pane tracep}
     :panels {:trace {:root (:root tracep)
                      :focus! (fn [] (g/gtk-widget-grab-focus (:list tracep)))
                      :open! trace-show-fn}}}))

(def part
  "The Trace part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :trace}))
