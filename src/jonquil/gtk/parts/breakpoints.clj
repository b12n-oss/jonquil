(ns jonquil.gtk.parts.breakpoints
  "LM5's breakpoints as a window part (the commands are
  jonquil.parts.breakpoints/spec). Toggle Breakpoint puts a dot on the
  caret's line, or the next one that can hold a breakpoint
  (jonquil.breakpoints/place), arms the runtime while any tab has a dot
  (ev/set-breakpoints!, which turns debug mode on), and, when the top-level
  form there only defines code, sends that form again (the core's
  :run-ranges!), so the breakpoint is live at once. Clear All Breakpoints
  takes a tab's dots away and sends the forms that held them again
  without. Instrument this tab for stepping sends every defining form
  again with step points, and every later Run keeps them, until Stop
  instrumenting, Clear All Breakpoints or the tab closes; neither ever
  sends a form with edits that haven't run. The runs themselves put the
  dots in (jonquil.gtk.window.run)."
  (:require [jolt.ffi :as ffi]
            [jonquil.breakpoints :as bp]
            [jonquil.clj :as clj]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.breakpoints :as gbp]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(def ^:private NEED-JOLT "; Breakpoints need Jolt.")

(defn- armed?
  "True when the runtime should take breakpoints: a tab in the window has
  a breakpoint or is instrumented for stepping (the step points name
  jonquil.debugger's vars, so they must never reach an unarmed runtime)."
  [{:keys [tabs]}]
  (boolean (some #(or (seq (gbp/marks (:ed %))) (gbp/step-all? (:ed %))) @tabs)))

(defn- caret-line
  "The caret's 1-based line in ed."
  [ed]
  (inc (first (g/cursor-line-col (:buf ed)))))

(defn- clear-window!
  "Takes every dot in the window away, saying why: arming pending
  breakpoints failed at the first evaluation (a Jolt child without
  jolt.debug)."
  [{:keys [tabs set-status!]} err]
  (doseq [t @tabs] (gbp/clear! (:ed t)) (gbp/set-step-all! (:ed t) false))
  (set-status! (str "; Breakpoints are off: " err)))

(defn- arm-with!
  "Arms evaluator e when the window has breakpoints, else disarms it; (then
  r) on the GUI thread with set-breakpoints!' answer. Pending breakpoints (a
  Jolt child not running yet) are armed by its first evaluation; when that
  fails the answer comes again, and every dot goes."
  [ctx e then]
  (ev/set-breakpoints! e (armed? ctx)
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

(def ^:private DISK-CAP
  ;; bytes: a file bigger than this isn't read on a toggle, and its form
  ;; counts as edited (never sent)
  (* 2 1024 1024))

(defn- disk-text
  "tab's file as it is on disk, :too-big over DISK-CAP, or nil without one
  (or when it can't be read)."
  [tab]
  (when-let [p @(:path tab)]
    (try (if (> (.length (java.io.File. ^String p)) DISK-CAP)
           :too-big
           (slurp p))
         (catch Throwable _ nil))))

(defn- split-edited
  "ranges of tab's text as {:send :skipped}: :skipped the ones with edits
  that haven't run (jonquil.breakpoints/edited?), which a command never
  sends."
  [tab text ranges]
  (let [ed (:ed tab)
        opts {:dirty? (editor/dirty? ed) :disk-text (disk-text tab)}
        ran (some-> ed :changed :ran deref)
        edited? #(bp/edited? text ran % opts)]
    {:send (vec (remove edited? ranges)) :skipped (vec (filter edited? ranges))}))

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
                  ;; nothing to say once a failed arm took the dot away:
                  ;; its own line says why
                  say! (fn [sent error]
                         (when (bp/still-set? change (gbp/marks ed))
                           (set-status! (str (bp/toggle-message (assoc change :sent sent :error error
                                                                       :debug-on? (:debug-turned-on? r)))
                                             note))))]
              (if (and rng (bp/edited? text (some-> ed :changed :ran deref) rng
                                       {:dirty? (editor/dirty? ed) :disk-text (disk-text tab)}))
                (say! :edited nil)
                (resend! ctx tab (if rng [rng] []) say!)))))))

(defn- with-heads!
  "Asks the runtime what text's heads name, keeps the answer on ed, then
  (then opts note r) on the GUI thread: opts for jonquil.breakpoints, note
  a sentence for the status bar when the answer is partial (the namespace
  isn't loaded) or missing, else nil, and r the answer itself."
  [{:keys [ev*]} ed text then]
  (let [ns (or (clj/buffer-ns text) "user")]
    (ev/macro-heads! @ev* ns (bp/head-symbols text)
                     (fn [r]
                       (on-gui! (fn []
                                  (if (:heads r)
                                    (do (gbp/set-heads! ed (bp/ns-form-text text) r)
                                        (then (gbp/heads ed text) (when (false? (:ns-found? r)) NS-NOT-LOADED) r))
                                    (then {} NO-HEADS r))))))))

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
            (fn [opts note _]
              (if (not= text (editor/editor-text ed))
                (set-status! "; The text changed while the runtime answered: toggle again.")
                (let [{placed :line :keys [moved? error]} (bp/place text line opts)]
                  (cond
                    error (set-status! (str "; " error note))
                    (gbp/mark-on ed text placed) (set-status! (str "; A breakpoint is already at line " placed "."))
                    :else (toggled! ctx {:tab tab :line placed :moved? moved? :added? true :note note
                                         :mark (gbp/add! ed text placed)})))))))))))

(defn- arm-note
  "The note on an arm answer r that failed, nil when it didn't."
  [r]
  (when-let [err (:error r)] (str " Breakpoints: " err)))

(defn- clear-ranges
  "The ranges Clear All Breakpoints sends again from tab's text: the forms
  holding a dot that only define code, and, when all? (the tab is
  instrumented), every defining form without edits that haven't run."
  [tab text all?]
  (->> (concat (keep #(bp/resend-range text %) (gbp/lines (:ed tab) text))
               (when all? (:send (split-edited tab text (bp/step-all-ranges text)))))
       distinct (sort-by :start) vec))

(defn- cleared-head
  "The start of Clear All's status line, after n dots went (all?: the tab
  was instrumented too)."
  [n all?]
  (cond-> (if (pos? n) (str "; Cleared " n " breakpoint" (when (> n 1) "s")) "; Stopped instrumenting this tab")
    (and all? (pos? n)) (str " and stopped instrumenting this tab")))

(defn- cleared-tail
  "The rest of Clear All's status line: how the re-send went (sent), and a
  failed disarm's error (r)."
  [sent r]
  (str (case sent
         :ok "; the forms run without them now."
         nil "."
         (str "; " bp/NEXT-RUN))
       (arm-note r)))

(defn- disarm-and-resend!
  "Arms or disarms the runtime as the window now needs, sends tab's forms
  at ranges again, then says head and how that went."
  [{:keys [set-status!] :as ctx} tab ranges head]
  (arm! ctx (fn [r] (resend! ctx tab ranges (fn [sent _] (set-status! (str head (cleared-tail sent r))))))))

(defn- clear-all!
  "Clear All Breakpoints in the active tab: the dots go, and so does
  instrumenting for stepping; the runtime is disarmed when no tab needs
  it any more, and the forms clear-ranges names are sent again without
  them."
  [{:keys [active-tab set-status!] :as ctx}]
  (let [tab (active-tab)
        ed (:ed tab)
        text (editor/editor-text ed)
        all? (gbp/step-all? ed)
        ranges (clear-ranges tab text all?)
        n (gbp/clear! ed)]
    (gbp/set-step-all! ed false)
    (if (and (zero? n) (not all?))
      (set-status! "; This tab has no breakpoints.")
      (disarm-and-resend! ctx tab ranges (cleared-head n all?)))))

(defn- step-all-plan
  "tab's defining forms as {:send :skipped} (split-edited), read from its
  text as it is now: what the two instrument commands send again, and
  what they leave for a Run."
  [tab]
  (let [text (editor/editor-text (:ed tab))]
    (split-edited tab text (bp/step-all-ranges text))))

(defn- say-instrumented!
  "The status line after an instrument command (on? for Instrument) sent
  plan's forms with outcome sent, then suffix (nil for none)."
  [{:keys [set-status!]} {:keys [on? plan sent suffix]}]
  (set-status! (str (bp/instrument-message {:on? on? :n (count (:send plan)) :skipped (count (:skipped plan))
                                            :sent sent})
                    suffix)))

(defn- resend-plan!
  "Sends tab's defining forms again (step-all-plan, read now, so text
  typed while the runtime answered can't shift them), then says how it
  went (on? for Instrument), with suffix."
  [ctx tab on? suffix]
  (let [plan (step-all-plan tab)]
    (resend! ctx tab (:send plan)
             (fn [sent _] (say-instrumented! ctx {:on? on? :plan plan :sent sent :suffix suffix})))))

(defn- send-instrumented!
  "Once arming tab's runtime answered r: the forms are sent again with
  step points, or, when arming failed, the tab's flag goes off again.
  note is with-heads!' sentence about a partial answer, nil for none."
  [ctx tab note r]
  (cond
    @(:destroyed? (:ed tab)) nil
    (:error r) (do (gbp/set-step-all! (:ed tab) false)
                   ((:set-status! ctx) (str "; " (:error r))))
    :else (resend-plan! ctx tab true note)))

(defn- instrument-tab!
  "Instruments tab: the runtime is asked which heads are macros (so step
  points go wherever they can, dot or not), then, with a defining form in
  the tab, its flag goes on (every later Run fills its defining forms with
  step points too), the runtime is armed, and the forms without edits
  that haven't run are sent again."
  [ctx tab]
  (let [ed (:ed tab)]
    (with-heads! ctx ed (editor/editor-text ed)
      (fn [_ note _]
        (cond
          @(:destroyed? ed) nil
          (empty? (bp/step-all-ranges (editor/editor-text ed))) (say-instrumented! ctx {:on? true :plan {}})
          :else (do (gbp/set-step-all! ed true)
                    (arm! ctx #(send-instrumented! ctx tab note %))))))))

(defn- instrument!
  "Instrument this tab for stepping, on the active tab: Step Into goes
  into any function it defines."
  [{:keys [ev* active-tab set-status!] :as ctx}]
  (if (gbp/supported? @ev*)
    (instrument-tab! ctx (active-tab))
    (set-status! NEED-JOLT)))

(defn- uninstrument-tab!
  "Stops instrumenting tab: the flag goes off, the runtime is disarmed when
  no tab needs it any more, and the defining forms without edits that
  haven't run are sent again without step points (a dot's form keeps its
  dot)."
  [ctx tab]
  (gbp/set-step-all! (:ed tab) false)
  (arm! ctx #(resend-plan! ctx tab false (arm-note %))))

(defn- uninstrument!
  "Stop instrumenting this tab, on the active tab."
  [{:keys [active-tab set-status!] :as ctx}]
  (let [tab (active-tab)]
    (if (gbp/step-all? (:ed tab))
      (uninstrument-tab! ctx tab)
      (set-status! "; This tab isn't instrumented."))))

(defn- refresh-heads!
  "After a Run of tab that ended well, when the tab has dots or an answer
  kept from a toggle: asks the runtime about its heads again (the Run may
  have loaded its namespace or defined macros), and says which dots that
  leaves inside a macro."
  [{:keys [ev* set-status!] :as ctx} {:keys [ed]}]
  (when (and (or (seq (gbp/marks ed)) (gbp/step-all? ed) (some-> (get-in ed [:changed :bp-heads]) deref))
             (gbp/supported? @ev*) (not @(:destroyed? ed)))
    (let [text (editor/editor-text ed)]
      (with-heads! ctx ed text
        (fn [_ _ r]
          ;; only from an answer, and about the text it was asked for
          (when-not @(:destroyed? ed)
            (let [now (editor/editor-text ed)]
              (when-let [msg (bp/refresh-report {:asked text :now now :answer r :lines (gbp/lines ed now)})]
                (set-status! msg)))))))))

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

(defn- say-error!
  "A set-breakpoints! answer handler: says r's error, with prefix, when it
  has one."
  [{:keys [set-status!]} prefix]
  (fn [r] (when-let [err (:error r)] (set-status! (str prefix err)))))

(defn- runtime-switched!
  "A dialect switch from evaluator old to new: the old runtime's hook goes
  with it, and the new one is armed the same way, pending while its child
  isn't running yet, when a tab has breakpoints."
  [wctx old new]
  (when (gbp/supported? old) (ev/set-breakpoints! old false (fn [_])))
  (when (gbp/supported? new)
    (on-gui! (fn [] (when (armed? wctx)
                      (arm-with! wctx new (say-error! wctx "; Breakpoints are off: ")))))))

(defn- tab-removed!
  "The tab hook for a tab about to close, while it and its buffer are still
  there: its dots and its instrumenting go, and with nothing left that
  needs it the runtime is disarmed."
  [wctx t]
  (let [all? (gbp/step-all? (:ed t))
        n (gbp/clear! (:ed t))]
    (gbp/set-step-all! (:ed t) false)
    (when (and (or (pos? n) all?) (not (armed? wctx)))
      (arm! wctx (say-error! wctx "; Breakpoints: ")))))

(defn- install
  "The breakpoints part for one window: the four command fns, the tab hooks,
  and a watch that arms a new evaluator (a dialect switch) while a tab has
  breakpoints."
  [{:keys [ev*] :as wctx}]
  (add-watch ev* ::breakpoints
             (fn [_ _ old new] (when-not (identical? old new) (runtime-switched! wctx old new))))
  {:fns {:toggle-breakpoint-fn (fn [] (toggle! wctx))
         :clear-breakpoints-fn (fn [] (clear-all! wctx))
         :instrument-tab-fn (fn [] (instrument! wctx))
         :uninstrument-tab-fn (fn [] (uninstrument! wctx))}
   :hooks {:on-tab-wired (fn [_ t] (tab-wired! wctx t))
           :on-tab-removed (fn [_ t] (tab-removed! wctx t))}})

(def part
  "The breakpoints part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :breakpoints}))
