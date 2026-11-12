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
            [jonquil.clj :as clj]
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

(defn- clear-window!
  "Takes every dot in the window away, saying why: arming pending
  breakpoints failed at the first evaluation (a Jolt child without
  jolt.debug)."
  [{:keys [tabs set-status!]} err]
  (doseq [t @tabs] (gbp/clear! (:ed t)))
  (set-status! (str "; Breakpoints are off: " err)))

(defn- arm-with!
  "Arms evaluator e when the window has breakpoints, else disarms it; (then
  r) on the GUI thread with set-breakpoints!' answer. Pending breakpoints (a
  Jolt child not running yet) are armed by its first evaluation; when that
  fails the answer comes again, and every dot goes."
  [ctx e then]
  (ev/set-breakpoints! e (any-dots? ctx)
                       (fn [r] (on-gui! (fn [] (if (:pending-failed? r)
                                                 (clear-window! ctx (:error r))
                                                 (then r)))))))

(defn- arm!
  "arm-with! on the window's evaluator, or (then {:error}) when it can't
  take breakpoints."
  [{:keys [ev*] :as ctx} then]
  (let [e @ev*]
    (if (gbp/supported? e)
      (arm-with! ctx e then)
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

(def ^:private NO-HEADS
  ;; said after a toggle the runtime couldn't answer about macros for
  " The runtime didn't say which names are macros, so code inside forms it doesn't know holds no breakpoint.")

(defn- disk-text
  "tab's file as it is on disk, or nil without one (or when it can't be
  read)."
  [tab]
  (when-let [p @(:path tab)]
    (try (slurp p) (catch Throwable _ nil))))

(def ^:private NS-NOT-LOADED
  ;; said when the runtime hasn't loaded the buffer's namespace yet
  " The buffer's namespace isn't loaded yet, so a name it refers can't be told from a macro: Run it, then toggle again.")

(defn- toggled!
  "After the dot changed: arms or disarms, then sends the form again when
  it only defines code and has no edits that haven't run, then says how it
  went. A runtime that can't take breakpoints takes the new dot away
  again."
  [{:keys [set-status!] :as ctx} {:keys [tab mark note] :as change}]
  (arm! ctx
        (fn [r]
          (if-let [err (:error r)]
            (do (when mark (gbp/remove! (:ed tab) mark))
                (set-status! (str "; " err)))
            (let [ed (:ed tab)
                  text (editor/editor-text ed)
                  rng (bp/resend-range text (:line change))
                  say! (fn [sent error]
                         (set-status! (str (bp/toggle-message (assoc change :sent sent :error error
                                                                     :debug-on? (:debug-turned-on? r)))
                                           note)))]
              (if (and rng (bp/edited? text (some-> ed :changed :ran deref) rng
                                       {:dirty? (editor/dirty? ed) :disk-text (disk-text tab)}))
                (say! :edited nil)
                (resend! ctx tab (if rng [rng] []) say!)))))))

(defn- with-heads!
  "Asks the runtime what text's heads name, keeps the answer on ed, then
  (then opts note) on the GUI thread: opts for jonquil.breakpoints, and
  note, a sentence for the status bar when the answer is partial (the
  namespace isn't loaded) or missing, else nil."
  [{:keys [ev*]} ed text then]
  (let [ns (or (clj/buffer-ns text) "user")]
    (ev/macro-heads! @ev* ns (bp/head-symbols text)
                     (fn [r]
                       (on-gui! (fn []
                                  (if (:heads r)
                                    (do (gbp/set-heads! ed ns r)
                                        (then (gbp/heads ed text) (when (false? (:ns-found? r)) NS-NOT-LOADED)))
                                    (then {} NO-HEADS))))))))

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
          ;; which heads are macros decides where the breakpoint can go
          (with-heads! ctx ed text
            (fn [opts note]
              (if (not= text (editor/editor-text ed))
                (set-status! "; The text changed while the runtime answered: toggle again.")
                (let [{placed :line :keys [moved? error]} (bp/place text line opts)]
                  (cond
                    error (set-status! (str "; " error note))
                    (gbp/mark-on ed text placed) (set-status! (str "; A breakpoint is already at line " placed "."))
                    :else (toggled! ctx {:tab tab :line placed :moved? moved? :added? true :note note
                                         :mark (gbp/add! ed text placed)})))))))))))

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

(defn- refresh-heads!
  "After a Run of tab that ended well, when the tab has dots or an answer
  kept from a toggle: asks the runtime about its heads again (the Run may
  have loaded its namespace or defined macros), and says which dots that
  leaves inside a macro."
  [{:keys [ev* set-status!] :as ctx} {:keys [ed]}]
  (when (and (or (seq (gbp/marks ed)) (some-> (get-in ed [:changed :bp-heads]) deref))
             (gbp/supported? @ev*) (not @(:destroyed? ed)))
    (let [text (editor/editor-text ed)]
      (with-heads! ctx ed text
        (fn [opts _]
          (when-let [msg (bp/dropped-message (bp/dropped-lines text (gbp/lines ed text) opts))]
            (set-status! msg)))))))

(defn- tab-wired!
  "Redraws the tab's gutter when its text changes while it has dots: the
  marks move with the text, and the gutter only redraws when told. And
  installs the refresh a Run that ended well calls (refresh-heads!)."
  [ctx {:keys [ed] :as t}]
  (when-let [r (get-in ed [:changed :bp-refresh])]
    (reset! r (fn [] (on-gui! (fn [] (refresh-heads! ctx t))))))
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
  [{:keys [ev* set-status!] :as wctx}]
  (add-watch ev* ::breakpoints
             (fn [_ _ old new]
               (when-not (identical? old new)
                 ;; the old runtime's hook goes with it
                 (when (gbp/supported? old) (ev/set-breakpoints! old false (fn [_])))
                 ;; the new one is armed the same way, pending while its
                 ;; child isn't running yet
                 (when (gbp/supported? new)
                   (on-gui! (fn [] (when (any-dots? wctx)
                                     (arm-with! wctx new
                                                (fn [r] (when-let [err (:error r)]
                                                          (set-status! (str "; Breakpoints are off: " err))))))))))))
  {:fns {:toggle-breakpoint-fn (fn [] (toggle! wctx))
         :clear-breakpoints-fn (fn [] (clear-all! wctx))}
   :hooks {:on-tab-wired (fn [_ t] (tab-wired! wctx t))
           ;; called while the tab and its buffer are still there: its
           ;; dots go, and with none left anywhere the runtime is disarmed
           :on-tab-removed (fn [_ t]
                             (when (pos? (gbp/clear! (:ed t)))
                               (when-not (any-dots? wctx) (arm! wctx (fn [_])))))}})

(def part
  "The breakpoints part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :breakpoints}))
