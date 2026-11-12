(ns jonquil.gtk.parts.debugger
  "The debugger as a window part (the commands and the label are
  jonquil.parts.debugger/spec). Moved out of jonquil.gtk.window: the Frames
  panel, line counts, the restarts and Break on error, and the hook that
  wires a tab's gutter counts. Retrying a failure goes through the window's
  :rerun-failure!, since the evaluation machinery stays in the core."
  (:require [jolt.ffi :as ffi]
            [jonquil.breakpoints :as bp]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.debugger-pane :as debugger-pane]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.gutter :as gutter]
            [jonquil.line-counts :as line-counts]
            [jonquil.restarts :as restarts]
            [jonquil.source :as source]))

(defn- frames-ev
  "The evaluator when it can answer frames, else nil. Read from ev* on every call."
  [{:keys [ev*]}]
  (let [e @ev*] (when (satisfies? ev/Frames e) e)))

;; An evaluator's answer arrives on its worker; f runs on the GUI thread.
(defn- landing
  [f]
  (fn [r] (on-gui! (fn [] (f r)))))

;; --- Frames ---------------------------------------------------------------------

(defn- show-note!
  "Shows d in the Frames panel with the restarts for failure f."
  [{:keys [frames-state dbgp right show-right-panel!]} d f]
  (reset! frames-state d)
  (debugger-pane/show! dbgp d)
  (debugger-pane/show-restarts! dbgp (restarts/options f))
  (show-right-panel! right :debugger))

(defn- frames-landed!
  "On the GUI thread: shows the frames answer d."
  [{:keys [cv dbgp set-status!] :as ctx} d]
  (if-let [err (:error d)]
    (if-let [f @(:failure* cv)]
      ;; no frames, but the restarts still apply
      (show-note! ctx {:note err} f)
      (set-status! (str "; " err)))
    (do (show-note! ctx d @(:failure* cv))
        (debugger-pane/focus! dbgp))))

(defn- show-frames!
  [{:keys [cv set-status!] :as ctx}]
  (if-let [e (frames-ev ctx)]
    (ev/error-frames! e (fn [d] (on-gui! (fn [] (frames-landed! ctx d)))))
    ;; no frames here, but the restarts work in every dialect
    (if-let [f @(:failure* cv)]
      (show-note! ctx {:note "Frames need Jolt or JVM Clojure; the restarts above work here."} f)
      (set-status! "; The debugger needs Jolt."))))

;; --- Line counts and debug mode -------------------------------------------------

(defn- hide-line-counts!
  [{:keys [tabs]}]
  (doseq [t @tabs] (some-> t :ed :changed :gutter (gutter/set-counts! {}))))

(defn- line-counts-toggled!
  [{:keys [line-counting? set-status!] :as ctx} r]
  (when-not (:error r) (reset! line-counting? (boolean (:on? r))))
  (when (and (not (:error r)) (not (:on? r))) (hide-line-counts! ctx))
  (set-status!
   (cond (:error r) (str "; " (:error r))
         (:was-debug? r) "; Line counts on, so debug mode is off: Run your file to count its lines."
         (:on? r) "; Line counts on: Run your file to count its lines."
         :else "; Line counts off.")))

(defn- toggle-line-counts!
  [{:keys [ev* set-status!] :as ctx}]
  (let [e @ev*]
    (if (satisfies? ev/LineCounts e)
      (ev/toggle-line-counts! e (landing #(line-counts-toggled! ctx %)))
      (set-status! "; Line counts need Jolt."))))

(defn- line-counts-cleared!
  [{:keys [set-status!] :as ctx} r]
  (hide-line-counts! ctx)
  (set-status! (if (:error r) (str "; " (:error r)) "; Line counts cleared.")))

(defn- clear-line-counts!
  [{:keys [ev* set-status!] :as ctx}]
  (let [e @ev*]
    (if (satisfies? ev/LineCounts e)
      (ev/clear-line-counts! e (landing #(line-counts-cleared! ctx %)))
      (set-status! "; Line counts need Jolt."))))

(defn- debug-mode-toggled!
  [{:keys [line-counting? set-status!] :as ctx} r]
  ;; Jolt has one mode: debug on ends counting
  (let [was-counting? @line-counting?]
    (when (:on? r) (reset! line-counting? false) (hide-line-counts! ctx))
    (set-status!
     (cond (:error r) (str "; " (:error r))
           (and (:on? r) was-counting?) "; Debug mode on: code you load from now on keeps its locals. Line counts are off."
           (:on? r) "; Debug mode on: code you load from now on keeps its locals."
           :else "; Debug mode off."))))

(defn- toggle-locals-mode!
  [{:keys [set-status!] :as ctx}]
  (if-let [e (frames-ev ctx)]
    (ev/toggle-debug-mode! e (landing #(debug-mode-toggled! ctx %)))
    (set-status! "; The debugger needs Jolt.")))

;; --- The panel's handlers -------------------------------------------------------

(defn- frame-clicked!
  [{:keys [frames-state sel* set-status! source-ctx active-tab open-in-tab! show-source!]} i]
  (let [{f :fn :keys [file line]} (get (:frames @frames-state) i)
        loc (when file (source/location {:file file :line line}
                                        {:project-dir (:project-dir @sel*)}))]
    (cond
      (nil? file) (set-status! "; This frame has no file.")
      (nil? loc) (set-status! (str "; Can't open " file "."))
      (:editable? loc) (open-in-tab! (source-ctx (active-tab)) f loc)
      :else (show-source! (source-ctx (active-tab)) loc))))

(defn- local-clicked!
  [{:keys [frames-state inspect!]} i local-name]
  (inspect! [:start-local {:frame i :local local-name :label local-name
                           :error-id (:error-id @frames-state)}]))

(defn- frame-eval-landed!
  [{:keys [cv]} label r]
  (if-let [err (:error r)]
    (cview/append! cv :error (str "; in " label ": " err "\n"))
    (do (when (seq (:out r)) (cview/append! cv :out (:out r)))
        (cview/append! cv :result (str "=> " (:text r) "\n")))))

(defn- eval-in-frame!
  [{:keys [frames-state cv set-status!] :as ctx} i code]
  (let [e (frames-ev ctx)
        label (:fn (get (:frames @frames-state) i))]
    (cond
      (or (nil? i) (nil? label)) (set-status! "; Select a frame first.")
      (nil? e) (set-status! "; The debugger needs Jolt.")
      :else
      ;; the code at once, so a slow form shows it's running
      ;; (Stop stops it), then what it printed and its value;
      ;; while paused, on the paused thread (Break on error)
      (do (cview/append! cv :system (str "; in " label ": " code "\n"))
          ((if (and (:paused? @frames-state) (satisfies? ev/Break e))
             (fn [on-result] (ev/break-eval! e i code on-result))
             (fn [on-result] (ev/eval-in-frame! e {:frame-i i :code code :error-id (:error-id @frames-state)} on-result)))
           (landing #(frame-eval-landed! ctx label %)))))))

(defn- set-handlers!
  [{:keys [dbgp right hide-right-panel!] :as ctx}]
  (debugger-pane/set-handlers!
   dbgp {:on-frame (fn [i] (frame-clicked! ctx i))
         :on-local (fn [i local-name] (local-clicked! ctx i local-name))
         :on-eval (fn [i code] (eval-in-frame! ctx i code))
         :on-close (fn [] (hide-right-panel! right :debugger))}))

;; --- The restarts (jonquil.restarts) for cv's last failure -----------------------

(def ^:private nothing-failed "; Nothing has failed since the last success.")
(def ^:private edit-the-file "; Edit the file and Run it again.")

(defn- retry!
  [{:keys [cv tabs tab-by-id active-tab rerun-failure! set-status!]}]
  (if-let [f @(:failure* cv)]
    ;; a REPL entry doesn't belong to its tab: any tab will do
    (if-let [t (or (tab-by-id tabs (:tab-id f)) (when (= :repl (:kind f)) (active-tab)))]
      (rerun-failure! f t)
      (set-status! "; The tab that failed is closed."))
    (set-status! nothing-failed)))

(defn- edit-retry!
  [{:keys [cv set-status!]}]
  (let [f @(:failure* cv)]
    (cond (nil? f) (set-status! nothing-failed)
          (= :run (:kind f)) (set-status! edit-the-file)
          :else (cview/set-input! cv (:code f) (count (:code f))))))

(defn- define-retry!
  [{:keys [cv set-status!]}]
  (let [f @(:failure* cv)
        n (some-> f :error-text restarts/missing-name)]
    (cond (nil? f) (set-status! nothing-failed)
          (= :run (:kind f)) (set-status! edit-the-file)
          (nil? n) (set-status! "; The last error wasn't an unresolved name.")
          :else (let [{:keys [text caret]} (restarts/define-input n (:code f))]
                  (cview/set-input! cv text caret)))))

;; --- Break on error (jonquil.debugger's break) ------------------------------------
;; The pause comes from the worker; the panel shows it with Continue in the
;; restart bar. After Continue the error is recorded as a failure, and that
;; refreshes the panel with its frames. break-resumed holds the id of the
;; evaluation that was continued, and ends with that evaluation
;; (:on-eval-end): Inspect Form and Time Form record no failure, and
;; a later, unrelated one mustn't open the panel. An id, not a flag,
;; because the evaluators go idle before on-complete, so the next
;; evaluation can start before this one's failure is recorded.

(defn- clear-arrow!
  "Takes the paused line's arrow away, from the tab that showed it."
  [{:keys [arrow-tab tabs tab-by-id]}]
  (when-let [id @arrow-tab]
    (reset! arrow-tab nil)
    (when-let [gt (some-> (tab-by-id tabs id) :ed :changed :gutter)]
      (gutter/set-arrow! gt nil))))

(defn- show-arrow!
  "LM5's stepping: an arrow in the gutter of the tab the paused code came
  from (:tab), on the paused line (:breakpoint), when the tab still holds
  the text it ran (jonquil.breakpoints/arrow-line). Answers false when it
  can't place one."
  [{:keys [arrow-tab tabs tab-by-id] :as ctx} {n :breakpoint id :tab}]
  (clear-arrow! ctx)
  (boolean
   (when-let [{:keys [ed]} (and n (some? id) (tab-by-id tabs id))]
     (when-not @(:destroyed? ed)
       (let [text (editor/editor-text ed)]
         (when-let [line (bp/arrow-line {:sent (some-> ed :changed :bp-sent deref) :now text :line n})]
           (when-let [gt (:gutter (:changed ed))]
             (gutter/set-arrow! gt (nth (bp/line-starts text) (dec line)))
             (reset! arrow-tab id)
             true)))))))

(defn- show-paused!
  "A pause in the Frames panel: an error's titled \"Paused: <error>\", a
  breakpoint's (LM5) \"Breakpoint at line N\" or a step's \"Stepped to
  line N\" as it came, with Step beside Continue and the gutter's arrow."
  [{:keys [frames-state dbgp right show-right-panel! cv set-status!] :as ctx} d]
  (let [n (:breakpoint d)
        shown (if n d (assoc d :title (str "Paused: " (:title d))))]
    (reset! frames-state (assoc shown :paused? true))
    (debugger-pane/show! dbgp shown)
    (debugger-pane/show-restarts! dbgp (cond-> [{:id :continue :label "Continue"}]
                                         n (into [{:id :step :label "Step Into"}
                                                  {:id :step-over :label "Step Over"}
                                                  {:id :step-out :label "Step Out"}])))
    (show-right-panel! right :debugger)
    (debugger-pane/focus! dbgp)
    (if n
      (let [where (if (:step? d) (str "Stepped to line " n) (str "Paused at the breakpoint on line " n))
            arrow? (show-arrow! ctx d)]
        (cview/append! cv :system (str "; " where ".\n"))
        (set-status! (str "; " where ". Step, Continue or Stop."
                          (when-not arrow? " The tab changed since it ran, so the gutter shows no arrow."))))
      (do (cview/append! cv :system (str "; Paused at " (:title d) ".\n"))
          (set-status! (str "; Paused: " (:title d) ". Continue or Stop."))))))

(defn- end-paused!
  [{:keys [frames-state dbgp] :as ctx} {:keys [continued?]}]
  (clear-arrow! ctx)
  (when (:paused? @frames-state)
    (let [d {:note (cond (not continued?) "Stopped while paused."
                         (:breakpoint @frames-state) "Continued: the code runs on."
                         :else "Continued: the error unwinds.")}]
      (reset! frames-state d)
      (debugger-pane/show! dbgp d)
      (debugger-pane/show-restarts! dbgp []))))

(defn- register-break!
  [{:keys [current-eval break-resumed paused-breakpoint?] :as ctx} e]
  (when (and e (satisfies? ev/Break e))
    (ev/set-break-handlers! e {:on-pause (fn [d]
                                           (reset! paused-breakpoint? (boolean (:breakpoint d)))
                                           (on-gui! (fn [] (show-paused! ctx d))))
                               ;; the flag on the worker, before the error
                               ;; it lets through is recorded as the failure;
                               ;; a breakpoint (LM5) lets no error through
                               :on-resume (fn [how]
                                            (reset! break-resumed (when (and (:continued? how) (not @paused-breakpoint?))
                                                                    @current-eval))
                                            (on-gui! (fn [] (end-paused! ctx how))))
                               :on-eval-start (fn [id] (reset! current-eval id))
                               :on-eval-end (fn [id] (compare-and-set! break-resumed id nil))})))

(defn- watch-break!
  "Registers Break on the current evaluator and on every one a dialect switch
  puts in ev* later, and refreshes the panel when a continued evaluation's
  failure is recorded."
  [{:keys [ev* cv break-resumed] :as ctx}]
  (register-break! ctx @ev*)
  (add-watch ev* ::break (fn [_ _ old new] (when-not (identical? old new) (register-break! ctx new))))
  (add-watch (:failure* cv) ::break-refresh
             (fn [_ _ _ new]
               (let [r @break-resumed]
                 (when (and r (compare-and-set! break-resumed r nil))
                   (when new (on-gui! (fn [] (show-frames! ctx)))))))))

(defn- break-toggled!
  [{:keys [set-status!]} r]
  (set-status!
   (cond (:error r) (str "; " (:error r))
         (:on? r) "; Break on error on: an error pauses where it was thrown."
         :else "; Break on error off.")))

(defn- toggle-break!
  [{:keys [ev* set-status!] :as ctx}]
  (let [e @ev*]
    (if (and e (satisfies? ev/Break e))
      (ev/toggle-break! e (landing #(break-toggled! ctx %)))
      (set-status! "; Break on error needs Jolt."))))

(defn- continue!
  [{:keys [ev* set-status!]}]
  (let [e @ev*]
    (when-not (and e (satisfies? ev/Break e) (ev/continue-break! e))
      (set-status! "; Nothing is paused."))))

(defn- step!
  "Step (LM5) in mode :step (Step Into), :over (Step Over) or :out (Step
  Out): runs the paused breakpoint on to the next line that mode stops at.
  At Break on error's pause it says Step works at a breakpoint, and does
  nothing."
  [{:keys [ev* set-status! frames-state]} mode]
  (let [e @ev*
        fs @frames-state]
    (cond
      (and (:paused? fs) (not (:breakpoint fs)))
      (set-status! "; Step works at a breakpoint: Continue or Stop this pause.")
      (and e (satisfies? ev/Breakpoints e))
      (ev/step-break! e mode (fn [ok?] (when-not ok? (set-status! "; Nothing is paused at a breakpoint."))))
      :else (set-status! "; Nothing is paused at a breakpoint."))))

(defn- set-restart-handler!
  [{:keys [dbgp] :as ctx}]
  (debugger-pane/set-restart-handler!
   dbgp (fn [id]
          (case id
            :retry (retry! ctx)
            :edit (edit-retry! ctx)
            :define (define-retry! ctx)
            :continue (continue! ctx)
            :step (step! ctx :step)
            :step-over (step! ctx :over)
            :step-out (step! ctx :out)))))

;; --- The tab hook (LM4) ---------------------------------------------------------

(defn- counts-landed!
  "On the GUI thread: draws the line counts answer r for ed's gutter, unless
  the buffer has changed since the fetch started from text."
  [{:keys [cv line-counting?]} ed text r]
  (let [gutter (:gutter (:changed ed))]
    (cond
      (:error r) (cview/append! cv :system (str "; " (:error r) "\n"))
      ;; the runtime isn't counting: a restart, or a
      ;; toggle elsewhere
      (not= :counts (:mode r))
      (when @line-counting?
        (reset! line-counting? false)
        (gutter/set-counts! gutter {})
        (cview/append! cv :system "; Line counts went off (the runtime restarted).\n"))
      (and (not @(:destroyed? ed)) (= text (editor/editor-text ed)))
      (gutter/set-counts! gutter (into {} (for [[line hits] (:counts r) :when line]
                                            [(dec line) (line-counts/label hits)]))))))

(defn- fetch-counts!
  "Fetches t's line counts when it's a saved, clean tab still holding the text
  of its last full Run."
  [{:keys [ev* cv run-text] :as ctx} {:keys [ed path]} source]
  (let [e @ev* path @path]
    (cond
      (not (satisfies? ev/LineCounts e)) nil
      ;; on the console: the Run's own Ready lands on the
      ;; status bar after this. Only for a Run: a Run Form
      ;; on an edited tab is the usual case, not news
      (or (nil? path) (editor/dirty? ed))
      (when (= :run source)
        (cview/append! cv :system "; Line counts show for a saved file.\n"))
      :else
      (let [text (editor/editor-text ed)]
        (when (= text @run-text)
          (ev/line-counts! e path (landing #(counts-landed! ctx ed text %))))))))

(defn- refresh-counts!
  "After a run, the file's line counts in the gutter, while counting is on;
  drawn only for a saved, clean tab whose text is still what it was when the
  fetch started, and hidden at the first edit. ctx's run-text holds the text
  the tab's last full Run ran: counts are drawn only while the buffer still
  holds it, so a Run Form or Run Selection never shows counts for lines that
  have moved since."
  [{:keys [line-counting? run-text] :as ctx} {:keys [ed] :as t} source code]
  (on-gui! (fn []
             (when (= :run source) (reset! run-text code))
             (when (and @line-counting? (not @(:destroyed? ed)))
               (fetch-counts! ctx t source)))))

(defn- tab-wired!
  "The tab hook: sets the editor's counting? and counts-refresh (read by the
  core's do-run! and hooked-opts) and hides the gutter's counts at the first
  edit. Runs first when a tab is wired, so its \"changed\" handler connects
  before the later ones."
  [{:keys [line-counting?] :as ctx} {:keys [ed] :as t}]
  (when-let [{:keys [counts-refresh counting? gutter]} (:changed ed)]
    (reset! counting? (fn [] (boolean @line-counting?)))
    (let [ctx (assoc ctx :run-text (atom nil))]
      (reset! counts-refresh (fn [source code] (refresh-counts! ctx t source code))))
    (g/g-signal-connect-data (:buf ed) "changed"
                             (callable (fn [_buf _data]
                                         (when (seq @(:counts gutter)) (gutter/set-counts! gutter {}))
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)))

(defn- debugger-fns
  [ctx]
  {:show-frames-fn (fn [] (show-frames! ctx))
   :locals-mode-fn (fn [] (toggle-locals-mode! ctx))
   :line-counts-fn (fn [] (toggle-line-counts! ctx))
   :clear-line-counts-fn (fn [] (clear-line-counts! ctx))
   :retry-fn (fn [] (retry! ctx))
   :edit-retry-fn (fn [] (edit-retry! ctx))
   :define-retry-fn (fn [] (define-retry! ctx))
   :break-fn (fn [] (toggle-break! ctx))
   :continue-fn (fn [] (continue! ctx))
   :step-fn (fn [] (step! ctx :step))
   :step-over-fn (fn [] (step! ctx :over))
   :step-out-fn (fn [] (step! ctx :out))
   ;; F10's :when: a breakpoint's pause is showing (GTK thread)
   :breakpoint-paused? (fn [] (let [fs @(:frames-state ctx)] (boolean (and (:paused? fs) (:breakpoint fs)))))})

(defn- install
  "The debugger part for one window: builds the Frames pane, wires its
  handlers, registers Break on error on the evaluator now and after a dialect
  switch, and answers its fns and the tab-wired hook.

  frames-state is what the Frames panel shows (jonquil.debugger), GTK thread
  only. Debug mode has no flag here: the toggle reads the runtime's own mode.
  line-counting? is whether Jolt is counting lines (LM4), from each toggle's
  answer."
  [wctx]
  (let [dbgp (debugger-pane/make-pane)
        ctx (assoc wctx :dbgp dbgp :frames-state (atom nil) :line-counting? (atom false)
                   :current-eval (atom nil) :break-resumed (atom nil)
                   :paused-breakpoint? (atom false)
                   ;; the tab whose gutter shows the paused line's arrow
                   :arrow-tab (atom nil))]
    (set-handlers! ctx)
    (watch-break! ctx)
    (set-restart-handler! ctx)
    {:fns (debugger-fns ctx)
     :ui {:debugger-pane dbgp}
     :hooks {:on-tab-wired (fn [_ t] (tab-wired! ctx t))}
     :panels {:debugger {:root (:root dbgp)
                         :focus! (fn [] (debugger-pane/focus! dbgp))
                         :open! (fn [] (show-frames! ctx))}}}))

(def part
  "The debugger part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :debugger}))
