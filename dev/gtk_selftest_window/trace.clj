(ns gtk-selftest-window.trace
  "Tracing a function into the Trace panel (LM4): toggle at the caret, the
  calls as rows, Enter inspects a call's value, a throw, and a name that
  isn't a function. In process, so the trace runs in this process."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.trace-pane :as trace-pane]))

(defn- run-palette!
  "support/run-palette!: the active tab's palette."
  [ui query]
  (support/run-palette! ui query))

(defn- status [ui] (g/label-text (:status-label ui)))

(defn- enter!
  "Types code at the console prompt and presses Return; the out offset
  before it."
  [ui code]
  (on-gtk (let [cv (:console ui)
                m (support/out-end-offset ui)]
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) code -1 1)
            (cview/handle-return! cv false)
            m)))

(defn- caret-on! [ui needle]
  (on-gtk (let [buf (:buf (:editor ui))
                at (+ 1 (str/index-of (editor/editor-text (:editor ui)) needle))]
            (g/with-iters* buf at at (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))))

(defn- untrace-all! [ui]
  (let [p (promise)]
    (ev/trace-op! (:session ui) :untrace-all {} (fn [r] (deliver p r)))
    (deref p 2000 nil)
    (ev/trace-op! (:session ui) :clear {} (fn [_]))))

(defn- rows [ui] (trace-pane/row-texts (:trace-pane ui)))

(defn row-trace-toggle-and-show
  "Trace the function at the caret records its calls through the function
  that calls it; Show lists them; Enter on a row inspects what that call
  returned; tracing it again stops it."
  [ui]
  (go-row :trace-toggle-and-show support/check
    (try
      (untrace-all! ui)
      (let [code "(defn tpair [x] [x (* x x)])\n(defn tall [n] (mapv tpair (range n)))"
            [ran? _] (support/run-and-await ui code 5000 #(str/includes? % "#'user/tall"))
            _ (caret-on! ui "tpair [x]")
            _ (on-gtk (run-palette! ui "trace the function at the caret"))
            traced? (await-until 3000 20 #(= "; Tracing user/tpair." (status ui)))
            m (enter! ui "(tall 3)")
            called? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "[2 4]")))
            _ (on-gtk (run-palette! ui "show the calls of the traced"))
            want ["(user/tpair 0) => [0 0]" "(user/tpair 1) => [1 1]" "(user/tpair 2) => [2 4]"]
            shown? (await-until 3000 20 #(= want (rows ui)))
            _ (on-gtk (trace-pane/activate! (:trace-pane ui) 2))
            inspected? (await-until 3000 20 #(str/includes? (g/label-text (:info (:inspector ui))) "2 items"))
            _ (on-gtk (run-palette! ui "trace the function at the caret"))
            stopped? (await-until 3000 20 #(= "; Stopped tracing user/tpair." (status ui)))
            m2 (enter! ui "(tall 3)")
            _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m2) "[2 4]")))
            same? (await-until 1000 20 #(= want (rows ui)))]
        (on-gtk (support/check :trace-toggle-and-show
                               (and ran? traced? called? shown? inspected? stopped? same?)
                               {:ran ran? :traced traced? :called called? :shown shown? :rows (rows ui)
                                :inspected inspected? :stopped stopped? :same same? :status (status ui)})))
      (finally
        (untrace-all! ui)
        (on-gtk ((:close-inspector!* ui))
                (when-let [f (:on-close @(:handlers (:trace-pane ui)))] (f)))))))

(defn row-trace-throw
  "A traced call that throws reads as threw and the error."
  [ui]
  (go-row :trace-throw support/check
    (try
      (untrace-all! ui)
      (let [[ran? _] (support/run-and-await ui "(defn tdiv [x] (/ 1 x))" 5000 #(str/includes? % "#'user/tdiv"))
            _ (caret-on! ui "tdiv [x]")
            _ (on-gtk (run-palette! ui "trace the function at the caret"))
            traced? (await-until 3000 20 #(= "; Tracing user/tdiv." (status ui)))
            m (enter! ui "(tdiv 0)")
            _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "Divide by zero")))
            _ (on-gtk (run-palette! ui "show the calls of the traced"))
            shown? (await-until 3000 20 #(= ["(user/tdiv 0) threw ArithmeticException: Divide by zero"] (rows ui)))]
        (on-gtk (support/check :trace-throw (and ran? traced? shown?)
                               {:ran ran? :traced traced? :shown shown? :rows (rows ui)})))
      (finally
        (untrace-all! ui)
        (on-gtk (when-let [f (:on-close @(:handlers (:trace-pane ui)))] (f)))))))

(defn row-trace-not-a-function
  "A keyword at the caret, or a var that holds no function, isn't traced."
  [ui]
  (go-row :trace-not-a-function support/check
    (let [[ran? _] (support/run-and-await ui "(def tnum 1)\n:tk" 5000 #(str/includes? % ":tk"))
          _ (caret-on! ui ":tk")
          _ (on-gtk (run-palette! ui "trace the function at the caret"))
          kw? (await-until 3000 20 #(= "; No function at the caret." (status ui)))
          _ (caret-on! ui "tnum")
          _ (on-gtk (run-palette! ui "trace the function at the caret"))
          num? (await-until 3000 20 #(= "; user/tnum isn't a function." (status ui)))]
      (on-gtk (support/check :trace-not-a-function (and ran? kw? num?)
                             {:ran ran? :kw kw? :num num? :status (status ui)})))))
