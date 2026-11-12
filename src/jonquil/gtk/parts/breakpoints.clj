(ns jonquil.gtk.parts.breakpoints
  "LM5's breakpoints as a window part (the commands are
  jonquil.parts.breakpoints/spec). Toggle Breakpoint puts a dot on the
  caret's line, or the next one that can hold a breakpoint
  (jonquil.breakpoints/place), arms the runtime while any tab has a dot
  (ev/set-breakpoints!, which turns debug mode on), and, when the top-level
  form there only defines code, sends that form again (the core's
  :run-ranges!), so the breakpoint is live at once. Clear All Breakpoints
  takes a tab's dots away and sends the forms that held them again
  without. The runs themselves put the dots in (jonquil.gtk.window.run)."
  (:require [jolt.ffi :as ffi]
            [jonquil.breakpoints :as bp]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.breakpoints :as gbp]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(def ^:private NEED-JOLT "; Breakpoints need Jolt.")

(defn- any-dots?
  "True when a tab in the window has a breakpoint."
  [{:keys [tabs]}]
  (boolean (some #(seq (gbp/marks (:ed %))) @tabs)))

(defn- caret-line
  "The caret's 1-based line in ed."
  [ed]
  (inc (first (g/cursor-line-col (:buf ed)))))

(defn- arm!
  "Arms the runtime when the window has breakpoints, else disarms it; (then
  r) on the GUI thread with set-breakpoints!' answer."
  [{:keys [ev*] :as ctx} then]
  (let [e @ev*]
    (if (gbp/supported? e)
      (ev/set-breakpoints! e (any-dots? ctx) (fn [r] (on-gui! (fn [] (then r)))))
      (then {:error (subs NEED-JOLT 2)}))))

(defn- resend!
  "Sends tab's forms at ranges again; (say! sent error) on the GUI thread
  once it's known how that went, sent being :ok, :failed, :busy or
  :refused, or nil when there is nothing to send."
  [{:keys [run-ranges!]} tab ranges say!]
  (if (empty? ranges)
    (say! nil nil)
    (let [r (run-ranges! tab ranges
                         (fn [result]
                           (on-gui! (fn []
                                      (if (= :ok (:status result))
                                        (say! :ok nil)
                                        (say! :failed nil))))))]
      (when (not= :started r) (say! r nil)))))

(defn- toggled!
  "After the dot changed: arms or disarms, then sends the form again when
  it only defines code, then says how it went. A runtime that can't take
  breakpoints takes the new dot away again."
  [{:keys [set-status!] :as ctx} {:keys [tab mark] :as change}]
  (arm! ctx
        (fn [r]
          (if-let [err (:error r)]
            (do (when mark (gbp/remove! (:ed tab) mark))
                (set-status! (str "; " err)))
            (let [text (editor/editor-text (:ed tab))
                  rng (bp/resend-range text (:line change))]
              (resend! ctx tab (if rng [rng] [])
                       (fn [sent error]
                         (set-status! (bp/toggle-message (assoc change :sent sent :error error
                                                                :debug-on? (:debug-turned-on? r)))))))))))

(defn- toggle!
  "Toggle Breakpoint on the active tab's caret line."
  [{:keys [ev* active-tab set-status!] :as ctx}]
  (let [tab (active-tab)
        ed (:ed tab)]
    (if-not (gbp/supported? @ev*)
      (set-status! NEED-JOLT)
      (let [text (editor/editor-text ed)
            line (caret-line ed)]
        (if-let [m (gbp/mark-on ed text line)]
          (do (gbp/remove! ed m)
              (toggled! ctx {:tab tab :line line :added? false}))
          (let [{placed :line :keys [moved? error]} (bp/place text line)]
            (cond
              error (set-status! (str "; " error))
              (gbp/mark-on ed text placed) (set-status! (str "; A breakpoint is already at line " placed "."))
              :else (toggled! ctx {:tab tab :line placed :moved? moved? :added? true
                                   :mark (gbp/add! ed text placed)}))))))))

(defn- clear-all!
  "Clear All Breakpoints in the active tab: the dots go, the runtime is
  disarmed when no tab has one left, and the forms that held them and
  only define code are sent again without them."
  [{:keys [active-tab set-status!] :as ctx}]
  (let [tab (active-tab)
        ed (:ed tab)
        text (editor/editor-text ed)
        ranges (->> (gbp/lines ed text) (keep #(bp/resend-range text %)) distinct (sort-by :start) vec)
        n (gbp/clear! ed)]
    (if (zero? n)
      (set-status! "; This tab has no breakpoints.")
      (let [said (str "; Cleared " n " breakpoint" (when (> n 1) "s"))]
        (arm! ctx (fn [_]
                    (resend! ctx tab ranges
                             (fn [sent _]
                               (set-status! (str said (case sent
                                                        :ok "; the forms run without them now."
                                                        nil "."
                                                        (str "; " bp/NEXT-RUN))))))))))))

(defn- tab-wired!
  "Redraws the tab's gutter when its text changes while it has dots: the
  marks move with the text, and the gutter only redraws when told."
  [{:keys [ed]}]
  (when-let [gt (get-in ed [:changed :gutter])]
    (g/g-signal-connect-data (:buf ed) "changed"
                             (callable (fn [_buf _data]
                                         (when (seq @(:dots gt)) (g/gtk-widget-queue-draw (:area gt)))
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)))

(defn- install
  "The breakpoints part for one window: the two command fns, the tab hooks,
  and a watch that arms a new evaluator (a dialect switch) while a tab has
  breakpoints."
  [{:keys [ev*] :as wctx}]
  (add-watch ev* ::breakpoints
             (fn [_ _ old new]
               (when (and (not (identical? old new)) (gbp/supported? new))
                 (on-gui! (fn [] (when (any-dots? wctx)
                                   (ev/set-breakpoints! new true (fn [_]))))))))
  {:fns {:toggle-breakpoint-fn (fn [] (toggle! wctx))
         :clear-breakpoints-fn (fn [] (clear-all! wctx))}
   :hooks {:on-tab-wired (fn [_ t] (tab-wired! t))
           ;; called while the tab and its buffer are still there: its
           ;; dots go, and with none left anywhere the runtime is disarmed
           :on-tab-removed (fn [_ t]
                             (when (pos? (gbp/clear! (:ed t)))
                               (when-not (any-dots? wctx) (arm! wctx (fn [_])))))}})

(def part
  "The breakpoints part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :breakpoints}))
