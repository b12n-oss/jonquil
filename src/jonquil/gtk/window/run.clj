(ns jonquil.gtk.window.run
  "The window's evaluation: Run and Stop, a console entry and its history, Run
  Selection, Run Form and Run Changed, a ClojureDocs example's Run and Copy, a
  failure's retry, and the MCP server's notifying Run, eval and Run Selection,
  with the opts every evaluation shares (make-eval-opts) and the hooks chained
  onto them (hooked-opts). Internal to the window, as jonquil.gtk.window.status
  is. Moved out of jonquil.gtk.window unchanged, then hooked-opts' completion
  and do-run!'s branches split into named steps; the lock caller rule in its
  docstring applies here. build makes activate!'s Run, Stop, Run Selection and
  Run Changed fns, in its order."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.changed :as changed]
            [jonquil.clj :as clj]
            [jonquil.cljd :as cljd]
            [jonquil.cljs :as cljs]
            [jonquil.commands :as commands]
            [jonquil.console :as console]
            [jonquil.doc-examples :as doc-examples]
            [jonquil.docs :as docs]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.runtime :as win-runtime]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.nrepl.attach]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.shadow]
            [jonquil.palette :as pal-data]
            [jonquil.plugin-hooks :as plugin-hooks]
            [jonquil.restarts :as restarts]
            [jonquil.session]
            [jonquil.tabs :as tabs]))

;; --- Run / Stop ------------------------------------------------------------

(defn- unclosed-bracket-hint!
  "After a Run that hit EOF while reading, names the unclosed bracket in
  ed's text. editor/editor-text calls GTK text-buffer functions, a real
  GTK-thread requirement, and on-complete runs on the eval worker thread,
  so the read (and the append! that depends on it) goes through on-gui!."
  [ed cv]
  (on-gui! (fn []
             ;; The Run's tab may have closed while it ran;
             ;; its buffer is gone then.
             (when-let [msg (when-not @(:destroyed? ed)
                              (console/unclosed-bracket-message (editor/editor-text ed)))]
               (cview/append! cv :system (str msg "\n"))))))

(defn- complete-eval!
  "make-eval-opts' :on-complete, on the eval worker thread."
  [{:keys [ed cv ev* status-label run?]} result]
  ;; docs/symbol-doc memoises on [ns sym text], so a name looked up before
  ;; this evaluation defined (or removed) it would keep serving that stale
  ;; answer forever. Clearing here covers every completed evaluation, Run
  ;; and REPL entry alike. A plain atom reset, safe to call from this
  ;; worker thread with no on-gui! (unlike everything else here).
  (docs/clear-doc-cache!)
  (when-let [f (some-> (:after-eval* cv) deref)]
    (try (f) (catch :default _ nil)))
  (win-status/set-status! status-label (if (= :interrupted (:status result)) "Stopped" "Ready"))
  (cview/reset-busy-hint! cv)
  (on-gui! (fn [] (cview/note-run-end! cv)))
  (win-runtime/refresh-prompt! cv ev*)
  (let [[kind text] (console/result-line result)]
    ;; LM1: a result that kept a handle to its value
    (if-let [ref (:ref result)]
      (cview/append-result! cv kind text ref)
      (cview/append! cv kind text)))
  (when (and run?
             (= :error (:status result))
             (str/includes? (console/error-text result) "EOF while reading"))
    (unclosed-bracket-hint! ed cv)))

(defn make-eval-opts
  "The opts run-definitions!/submit-repl! share (the lock-caller rule:
  every one of these runs on the eval worker thread and must only
  enqueue).

  The console's :evaluating?/:waiting? are wired at construction to read
  live from the engine (see activate!), not mirrored by callbacks here, so
  a fast eval's on-complete has no separate flag to race against and
  strand.

  :on-complete sets the status label FIRST, before result-line/
  format-error run: a throwing result-line or format-error (both call
  user-supplied formatting logic) can then never leave the status label
  stuck on \"Running…\"; the same goes for reset-busy-hint!, which
  must re-arm for the next run whether or not the append below throws.

  ctx holds :ed, :cv, :ev*, :status-label and :run?, true for
  run-definitions! and false for submit-repl!:
  the EOF-unclosed-bracket hint reads the DEFINITIONS editor's own text,
  which is only relevant to a Run's own EOF -- a REPL entry's EOF (e.g. an
  unbalanced string argument to a nested load-string, since a genuinely
  unbalanced top-level REPL entry never reaches :eval at all per
  console/input-action) has nothing to do with whatever the Definitions
  pane currently holds, and must not name it as the culprit.

  :on-complete also refreshes the console's prompt from ev*, since the
  evaluation may have moved the session's namespace; every evaluator
  updates its prompt-ns before it calls on-complete."
  [{:keys [cv status-label] :as ctx}]
  {:on-output (fn [kind s] (cview/append! cv kind s))
   :on-wait-input (fn [waiting?]
                    (win-status/set-status! status-label (if waiting? "Waiting for input" "Running…")))
   :on-status-change (fn [status]
                       (win-status/set-status! status-label
                                               (case status
                                                 :running "Running…"
                                                 :waiting-input "Waiting for input"
                                                 :idle "Ready")))
   :on-complete (fn [result] (complete-eval! ctx result))})

(defn refuse-foreign!
  "The refusal message (truthy), after printing it, when
  tab belongs to another project or dialect than the window
  (jonquil.tabs/runnable-here?), so Run, Run Selection and Run Form
  evaluate nothing there."
  [{:keys [tab cv sel*]}]
  (let [tsel @(:project tab)]
    (when-not (tabs/runnable-here? @sel* tsel)
      (let [msg (tabs/refusal-message {:file (win-files/file-name @(:path tab)) :tab-sel tsel
                                       :window-sel @sel*})]
        (cview/append! cv :system msg)
        msg))))

(defn- notifying-opts
  "base (make-eval-opts' map) with both callbacks chained so an MCP hook
  hears the evaluation: :on-output also collects each chunk into out (an
  atom of strings), and :on-complete, after the base's own handling, calls
  (notify {:done {:result r :output <all collected output>}}). The base's
  :on-output arity is kept ([kind s])."
  [base out notify]
  (assoc base
         :on-output (fn [kind s]
                      ((:on-output base) kind s)
                      (swap! out conj s))
         :on-complete (fn [result]
                        ;; notify in finally: a throw in the base handling (a
                        ;; malformed result map) must not lose the MCP call's
                        ;; only wake-up. The rethrow still reaches the
                        ;; evaluator's own guard.
                        (try ((:on-complete base) result)
                             (finally
                               (notify {:done {:result result :output (apply str @out)}}))))))

(defn- note-ran!
  "LM3 and LM4 after a Run, Run Form or Run Selection: an :ok result marks
  code as ran (jonquil.changed) and refreshes the marks, and either way the
  gutter's line counts refresh. Never throws."
  [{:keys [changed hook-source hook-code]} result]
  ;; LM3: what ran :ok is no longer changed (jonquil.changed)
  (when (and changed (= :ok (:status result)) (#{:run :form :selection} hook-source))
    (try (swap! (:ran changed) changed/record hook-code (if (= :run hook-source) :run :form))
         ((deref (:refresh changed)))
         (catch :default _ nil)))
  ;; LM4: the lines that ran, whether it ended well or not
  (when (and changed (#{:run :form :selection} hook-source))
    (try ((deref (:counts-refresh changed)) hook-source hook-code) (catch :default _ nil))))

(defn- record-replay!
  "The replay recorder (LM7) hears record and result, when both a record and
  a recorder are set. Never throws."
  [{:keys [recorder record]} result]
  (when-let [f (and record (some-> recorder deref))]
    (try (f record result)
         (catch :default _ nil))))

(defn- after-hooked-eval!
  "hooked-opts' completion for an eval that had a :hook-source: the eval's
  own on-complete, then the restarts' failure, the changed marks and counts,
  the plugins' hooks and the replay recorder, in that order. hooks holds
  hooked-opts' keys, :record as hooked-opts completed it."
  [{:keys [on-complete failure* failure-kind hook-source hook-code hook-tab] :as hooks} result]
  (when on-complete (on-complete result))
  ;; after the eval's own completion, and never throwing: the
  ;; window's last failure, for the restarts (jonquil.restarts)
  (when failure*
    (restarts/record-failure! failure* {:kind (or failure-kind hook-source) :code hook-code :tab-id hook-tab}
                              result console/error-text))
  (note-ran! hooks result)
  (plugin-hooks/after-eval! {:source hook-source :tab hook-tab :result result})
  ;; LM7: what the user ran, for the last session's replay.
  ;; Last, and never throwing into the evaluation: the recorder
  ;; hands its work to the GTK thread and says its own failures
  (record-replay! hooks result))

(defn- hooked-opts
  "opts without :hook-source (:run :selection :form :repl), :failure-kind
  (the kind the restarts record when it differs from what plugins see, as an
  example Run's :example), :hook-tab,
  :hook-code, :failure*, :changed, :record, :recorder and :replay-root, and, when it had a source, its :on-complete
  chained so the plugins' :after-run and :on-error hooks hear how the eval
  ended, and the window's failure* atom records an error (or clears on
  success) for the restarts. With a :record ({:source :code :path :ns}) and
  the :recorder atom, the window's recorder (LM7) hears it too, after the
  rest and inside a try, with the tab and the tree's root (from the
  :replay-root atom's fn) as it started. Only
  opts handed to ev/eval! come here, so an eval that never started fires
  nothing. after-eval! only starts futures and reports its own throws, so
  the eval's own completion is never lost to it."
  [{:keys [hook-source failure-kind hook-tab hook-code failure* changed record recorder replay-root on-complete] :as opts}]
  (let [opts (dissoc opts :hook-source :failure-kind :hook-tab :hook-code :failure* :changed :record :recorder :replay-root)
        ;; LM7: the tree's root now, as the eval starts (the GTK thread), so
        ;; a re-root before it ends doesn't move it into another project's record
        record (when record
                 (assoc record :tab-id hook-tab
                        :root (when-let [f (some-> replay-root deref)] (f))))]
    (if-not hook-source
      opts
      (let [hooks {:on-complete on-complete :failure* failure* :failure-kind failure-kind :hook-source hook-source
                   :hook-code hook-code :hook-tab hook-tab :changed changed :recorder recorder :record record}]
        (assoc opts :on-complete (fn [result] (after-hooked-eval! hooks result)))))))

(defn- before-run-stopped
  "Asks the :before-run hooks about a Run of kind sending text
  from tab. nil to go ahead; when a plugin stops it, the line saying so,
  after putting it on the console and the status bar. Call only when the
  Run would otherwise start (the tab is ours, the evaluator idle, the
  bracket check clean, the text there), since a Run that wouldn't start
  asks nothing. GTK thread; waits for the hooks up to their budget."
  [{:keys [cv status-label]} kind tab text]
  (when-let [line (plugin-hooks/before-run! {:kind kind :text text :path @(:path tab) :tab (:id tab)})]
    (cview/append! cv :system (str "; " line "\n"))
    (win-status/set-status! status-label (str "; " line))
    line))

(defn- run-record-ns
  "The ns a Run's forms start in, for the replay record (LM7): user for the
  in-process session, which runs a Run there, else the evaluator's prompt
  ns. Read as the Run is submitted."
  [ev]
  (if (instance? jonquil.session.InProcess ev) "user" (str (ev/prompt-ns ev))))

(defn- say-busy!
  "A Run's busy refusal: when ev/eval! answered warning instead of starting,
  it goes on the console and, with notify, {:busy warning} reports it."
  [cv notify warning]
  (when warning
    (cview/append! cv :system warning)
    (when notify (notify {:busy warning}))))

(defn- run-opts
  "do-run!'s opts for ev/eval!, built as the Run is submitted: make-eval-opts'
  map for tab's Run, with the replay tab's and LM4's changes, notifying-opts
  when notify is given (collecting into out), then hooked-opts with the Run's
  hook keys and, for a Run that isn't MCP's, its replay record."
  [{:keys [tab cv ev* status-label]} notify out]
  (let [{ed :ed path-atom :path id :id} tab
        base (cond-> (assoc (make-eval-opts {:ed ed :cv cv :ev* ev* :status-label status-label :run? true})
                            :run? true :path @path-atom :owner id)
               ;; LM7: the replay tab's Run adds to the session, and
               ;; what it defines is the REPL's, as if typed there
               (= id (:id @(:replay-tab* cv))) (assoc :reset? false :owner :repl)
               ;; LM4: a Run counts its own lines alone
               (some-> (:changed ed) :counting? deref (apply []))
               (assoc :clear-line-counts? true))]
    (hooked-opts (assoc (if notify (notifying-opts base out notify) base)
                        :hook-source :run :hook-tab id
                        :hook-code (editor/editor-text ed) :failure* (:failure* cv)
                        :changed (:changed ed) :recorder (:recorder* cv)
                        :replay-root (:replay-root* cv)
                        ;; MCP's Run (notify) is never recorded
                        :record (when-not notify
                                  {:source :run :code (editor/editor-text ed)
                                   :path @path-atom :ns (run-record-ns @ev*)})))))

(defn- run-idle!
  "do-run!'s branch for an idle engine: the bracket check, then the plugins'
  :before-run, then the entry mark and the banner, then the problem (Ready,
  nothing evaluated) or the eval. opts is do-run!'s thunk."
  [{:keys [tab cv ev* status-label] :as ctx} notify opts]
  (let [{ed :ed path-atom :path} tab
        text (editor/editor-text ed)
        problem (commands/pre-run-problem text)
        ;; A plugin may stop a Run that would start; nothing then
        ;; shows but its line, not even the banner
        stopped (when-not problem (before-run-stopped ctx :run tab text))]
    (if stopped
      (when notify (notify {:refused stopped}))
      (do
        (cview/mark-entry-start! cv)
        ;; A saved tab's banner names its file.
        (cview/append! cv :system (tabs/run-banner @path-atom))
        (if problem
          (do (cview/append! cv :error (str problem "\n"))
              (win-status/set-status! status-label "Ready")
              (when notify (notify {:problem problem})))
          (let [_ (when-not (ev/evaluating? @ev*) (cview/note-run-start! cv))
                warning (ev/eval! @ev* text (opts))]
            (say-busy! cv notify warning)))))))

(defn do-run!
  "The upstream banner, printed ONLY when the Run actually starts.
  ev/evaluating? is checked BEFORE ev/eval! and the banner (when printed)
  goes first, in that order, so the program's own output can never land
  ahead of a banner that hasn't been enqueued yet. When the engine is
  already busy, ev/eval! returns its warning string instead of starting
  anything, and only that line is appended -- no banner.

  commands/pre-run-problem runs against the editor's own text, but ONLY
  when the engine is idle. When it names a bracket problem, Run never
  reaches ev/eval! at all -- nothing is evaluated, even a form before the
  broken one, so an earlier form's own unresolved-symbol error can never
  hide the bracket message. The banner still prints before the problem is
  appended as :error, and the status is set to Ready.

  A Run while one is ALREADY running: ev/evaluating? is checked FIRST,
  before the bracket check, so a second Run never sets the status to Ready
  (the first program is still running) and never appends a bracket :error
  into the middle of its output; only ev/eval!'s own busy-refusal warning
  is appended, with no banner and no status change (eval/eval-async's busy
  branch returns its warning string without calling on-status-change).

  Cmd/Ctrl+Shift+L's choke point for the banner (it doesn't go through
  echo-entry!, since only Run Selection/Run Form do):
  cview/mark-entry-start! is queued right before the banner itself, so it
  resolves (on the console's next drain) right where the banner's text
  lands -- never on the busy path above, which prints no banner.

  With notify (a fn of one map), exactly one call reports how the Run
  went -- {:refused msg}, {:busy warning}, {:problem text}, or, from the
  eval worker, {:done {:result r :output s}} -- and the return value is a
  reader, (fn [] output-so-far); without it this returns nil as before."
  {:arglists '([ctx] [{:keys [tab cv ev* status-label] :as ctx} notify])}
  ([ctx] (do-run! ctx nil))
  ([{:keys [tab cv ev*] :as ctx} notify]
   (let [{ed :ed} tab
         out (atom [])
         refused (refuse-foreign! ctx)
         opts (fn [] (run-opts ctx notify out))]
     (cond
       refused (when notify (notify {:refused refused}))
       (ev/evaluating? @ev*)
       (let [warning (ev/eval! @ev* (editor/editor-text ed) (opts))]
         (say-busy! cv notify warning))
       :else (run-idle! ctx notify opts))
     (when notify (fn [] (apply str @out))))))

(defn- do-stop!
  "A running Stop's detached line comes from that evaluation's own
  on-output (attach/interrupt! calls it directly). An idle Stop has no
  evaluation and so no on-output to print through, and on-connection!
  isn't wired to the console anywhere (watch-connection! only sets the
  dialect label), so this checks, before interrupt! runs, whether the
  evaluator is an attach one that's connected and idle, and prints the
  same line cv would otherwise never see. (Widening the shared Evaluator
  protocol's interrupt! return value for one runtime's idle notice would
  touch jonquil.session and jonquil.nrepl.evaluator for no reason of
  theirs.) Checked before interrupt! runs, since that's what closes the
  connection; connected? would read false after."
  [ev* cv]
  (let [e @ev*
        detached (cond (instance? jonquil.nrepl.attach.Attach e) cljd/detached-message
                       (instance? jonquil.nrepl.shadow.Shadow e) cljs/detached-message)
        idle-detach? (and detached
                          (connection/connected? e)
                          (not (ev/evaluating? e)))]
    (ev/interrupt! e)
    (when idle-detach?
      (cview/append! cv :system (str detached "\n"))))
  nil)

;; --- REPL: the console's on-submit, and the history adapters ---------------

(defn- do-repl-eval!
  "submit-repl! with the same opts run uses, minus the banner -- and the
  same warning-append bookkeeping, since submit-repl! can return the same
  busy warning run-definitions! can. extra-opts is merged in last: Run
  Selection and Run Form pass {:ns-source buffer-text}, which the attach
  evaluator uses to switch the session into the buffer's namespace first;
  the in-process and nREPL evaluators ignore it. ctx is the window's (its
  :cv, :ev* and :status-label)."
  [{:keys [cv ev* status-label]} ed text extra-opts]
  (let [_ (when-not (ev/evaluating? @ev*) (cview/note-run-start! cv))
        warning (ev/eval! @ev* text (hooked-opts (merge (assoc (make-eval-opts {:ed ed :cv cv :ev* ev* :status-label status-label :run? false}) :run? false
                                                               :hook-code text :failure* (:failure* cv)
                                                               :changed (:changed ed) :recorder (:recorder* cv)
                                                               :replay-root (:replay-root* cv))
                                                        extra-opts)))]
    (when warning (cview/append! cv :system warning))))

(defn- tab-record
  "The :record map (LM7) for code that Run Selection or Run Form sends from
  tab: its saved path, and the namespace the buffer's ns form names, else
  the prompt's."
  [tab ev* {:keys [source code buffer-text]}]
  {:source source :code code :path @(:path tab)
   :ns (or (clj/buffer-ns buffer-text) (str (ev/prompt-ns @ev*)))})

(defn- do-run-selection!
  "Cmd/Ctrl+E: commands/run-selection-text on the editor's snapshot
  is nil for a blank line, else the selection or the caret's whole line.
  When non-nil, echoes \"> text\" to the console as if typed, then goes
  through do-repl-eval! -- the SAME evaluator path a typed REPL entry uses
  (history via ev/eval!, the busy-refusal warning, the same make-eval-opts).

  echo-entry!'s mark? is (not (ev/evaluating? @ev*)), read BEFORE
  do-repl-eval! can change it -- mirroring do-run!'s idle/busy split.
  While a program is ALREADY running, this still echoes (do-repl-eval!
  then appends the usual busy-refusal warning) but must NOT steal the
  last-entry mark from that running program: a later Shift+L needs to
  keep clearing the WHOLE run, not stop short at wherever this mid-run
  echo landed."
  [{:keys [tab cv ev*] :as ctx}]
  (let [ed (:ed tab)
        snap (editor/snapshot ed)]
    (when-let [text (commands/run-selection-text snap)]
      (when-not (or (refuse-foreign! ctx)
                    ;; asked only when the Run would start
                    (and (not (ev/evaluating? @ev*)) (before-run-stopped ctx :selection tab text)))
        (cview/echo-entry! cv text (not (ev/evaluating? @ev*)))
        (do-repl-eval! ctx ed text {:ns-source (:text snap) :owner (:id tab)
                                    :hook-source :selection :hook-tab (:id tab)
                                    :record (tab-record tab ev* {:source :selection :code text
                                                                 :buffer-text (:text snap)})})))))

(defn do-run-example!
  "Run in Interactions on a ClojureDocs example's code: evaluated in user at
  the console without moving the prompt (the JVM evaluator evals in
  (clj/buffer-ns ns-source) and leaves the prompt alone for a non-nil
  :ns-source). When the prompt is elsewhere and the Run starts, the console
  says '; in user' first. Echo, busy warning and recording follow
  do-run-selection!. Plugins see a :selection Run on all three hooks;
  :failure-kind :example is the window's own marker so Retry runs it here
  again. No :changed, since the code isn't the tab's."
  [{:keys [tab cv ev*] :as ctx} code]
  (let [idle? (not (ev/evaluating? @ev*))]
    (when-not (or (refuse-foreign! ctx)
                  ;; asked only when the Run would start, as Run Selection does
                  (and idle? (before-run-stopped ctx :selection tab code)))
      (when (and idle? (not= "user" (str (ev/prompt-ns @ev*))))
        (cview/append! cv :system "; in user\n"))
      (cview/echo-entry! cv code idle?)
      (do-repl-eval! ctx (:ed tab) code
                     {:ns-source "(ns user)" :owner (:id tab)
                      :hook-source :selection :failure-kind :example :hook-tab (:id tab)
                      :changed nil
                      :record {:source :example :code code :path nil :ns "user"}}))))

(defn- re-eval-failure!
  "Sends failure f's code through the REPL again, in tab t, as it first went."
  [{:keys [cv ev*] :as ctx} f t]
  (let [code (:code f)
        buffer? (#{:selection :form} (:kind f))]
    (cview/echo-entry! cv code (not (ev/evaluating? @ev*)))
    (do-repl-eval! ctx (:ed t) code
                   (cond-> {:hook-source (:kind f) :hook-tab (:id t)
                            :record (if buffer?
                                      (tab-record t ev* {:source (:kind f) :code code
                                                         :buffer-text (editor/editor-text (:ed t))})
                                      {:source (:kind f) :code code :path nil
                                       :ns (str (ev/prompt-ns @ev*))})}
                     ;; as Run Selection and Run Form send them
                     buffer? (assoc :ns-source (editor/editor-text (:ed t)) :owner (:id t))))))

(defn rerun-failure!
  "Runs failure f again in tab t: the retry restart's dispatch by the
  failure's kind. m is {:failure :tab :cv :ev* :sel* :status-label}. The run
  fns are called through their vars."
  [{f :failure t :tab :keys [cv ev* sel* status-label]}]
  (let [ctx {:tab t :cv cv :ev* ev* :sel* sel* :status-label status-label}
        code (:code f)
        buffer? (#{:selection :form} (:kind f))]
    (cond
      (= :run (:kind f)) (do-run! ctx)
      ;; an example goes back through its own Run, in user
      (= :example (:kind f)) (do-run-example! ctx code)
      ;; the checks Run Selection and Run Form make first
      (and buffer? (or (refuse-foreign! ctx)
                       (and (not (ev/evaluating? @ev*))
                            (before-run-stopped ctx (:kind f) t code))))
      nil
      :else (re-eval-failure! ctx f t))))

(defn do-copy-example!
  "Copy to Definitions: code goes into the tab's editor after the form at
  the caret (jonquil.doc-examples/insert-op), exactly as written (Parinfer
  skipped, so it matches what Run runs), one undo step. A read-only editor
  refuses it: the status says so and the popover stays open. Otherwise the
  full-doc popover closes and the editor takes focus back, posted since GTK
  moves focus as the popover unmaps."
  [{:keys [tab full-doc-pop status-label]} code]
  (let [ed (:ed tab)]
    (if (editor/apply-op-plain! ed (doc-examples/insert-op (editor/snapshot ed) code))
      (do (popover/hide! full-doc-pop)
          (on-gui! #(when-not @(:destroyed? ed) (editor/focus! ed))))
      (win-status/set-status! status-label "; This tab is read-only."))))

;; --- MCP hooks: run/eval/run-selection that report back -----------------------

(defn mcp-eval!
  "A REPL entry at the console for the MCP server: echoes text, then
  evaluates it like do-repl-eval! (run? false, owner :repl unless
  extra-opts says otherwise), with notifying-opts chained on. notify gets
  exactly one map: {:busy warning} (the warning is also appended after the
  echo, as for a typed entry) or, later, {:done ...}. Returns the output
  reader, (fn [] output-so-far)."
  [{:keys [tab cv ev* status-label]} text extra-opts notify]
  (let [ed (:ed tab)
        out (atom [])
        opts (hooked-opts (merge (notifying-opts (assoc (make-eval-opts {:ed ed :cv cv :ev* ev* :status-label status-label :run? false}) :run? false
                                                        :hook-code text :failure* (:failure* cv)
                                                        :changed (:changed ed))
                                                 out notify)
                                 extra-opts))]
    (cview/echo-entry! cv text (not (ev/evaluating? @ev*)))
    (let [_ (when-not (ev/evaluating? @ev*) (cview/note-run-start! cv))
          warning (ev/eval! @ev* text opts)]
      (when warning
        (cview/append! cv :system warning)
        (notify {:busy warning})))
    (fn [] (apply str @out))))

(defn mcp-run-selection!
  "Selects start..end (clamped to the buffer) in the active tab, then takes
  the do-run-selection! path: {:none s} for a blank range, {:refused msg}
  for a foreign tab, else echo + eval with :ns-source and the tab's :id as
  :owner. Returns the output reader, (fn [] \"\") on the no-eval paths."
  [{:keys [tab] :as ctx} start end notify]
  (let [ed (:ed tab)
        buf (:buf ed)
        n (g/gtk-text-buffer-get-char-count buf)
        clamp (fn [x] (max 0 (min n x)))
        a (clamp start)
        b (clamp end)
        _ (g/with-iters* buf a b (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
        snap (editor/snapshot ed)]
    (if-let [text (commands/run-selection-text snap)]
      (if-let [msg (or (refuse-foreign! ctx)
                       (and (not (ev/evaluating? @(:ev* ctx))) (before-run-stopped ctx :selection tab text)))]
        (do (notify {:refused msg}) (fn [] ""))
        (mcp-eval! ctx text {:ns-source (:text snap) :owner (:id tab)
                             :hook-source :selection :hook-tab (:id tab)}
                   notify))
      (do (notify {:none (str "Nothing to run: the range " a ".." b " is blank.")})
          (fn [] "")))))

;; --- Run Form -----------------------------------------------------------------

(def ^:private FLASH-MS
  ;; estimate: long enough to see which form ran, short enough not to be in
  ;; the way of the next keystroke.
  250)

(defn make-flash-fn
  "{:flash! (fn [start end caret]) :cancel! (fn [] -> boolean)}. :flash!
  selects start..end in ed's buffer, then FLASH-MS later puts the caret
  back where it was, unless the selection changed meanwhile (the user
  moved on) -- the same restore check :cancel! below uses. :cancel!
  restores AT ONCE instead of waiting for the timeout, for
  editor.clj's own key dispatch to call ahead of every other key clause:
  a printable key, Backspace or Tab landing while a flash is still
  showing would otherwise act on the flash's own selection, since the
  flash is a real one. Returns true when a flash was actually pending.

  A second :flash! call while the first's own selection is STILL exactly
  the live one (nothing else -- a keystroke's cancel!, a set-text!, a
  click elsewhere -- has touched it since) carries that first call's own
  saved caret over, rather than its own caret argument, which is really
  just that first selection's own start (select-range's mark convention),
  not a real caret the user left behind. Anything else touching the
  selection since (including a whole different buffer arriving) makes
  this call's own caret argument the right one again -- the same
  liveness check restore! uses to decide whether to act at all.

  One foreign-callable per window, reused by every flash: jonquil.gtk.
  callbacks retains every callable for the process's life, so a fresh one
  per flash would leak. GTK thread only."
  [ed]
  (let [pending (atom nil)
        buf (:buf ed)
        restore! (fn []
                   (boolean
                    (when-let [{:keys [caret sel]} (first (reset-vals! pending nil))]
                      (when (= sel (g/selection-offsets buf))
                        (g/with-iters* buf caret caret (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
                      true)))
        restore-cb (callable (fn [_data]
                               (try (restore!) (catch :default _ nil))
                                ;; G_SOURCE_REMOVE: a one-shot timeout.
                               0)
                             [:pointer] :int)]
    {:flash! (fn [start end caret]
               (let [p @pending
                     saved (if (and p (= (:sel p) (g/selection-offsets buf))) (:caret p) caret)]
                 (g/with-iters* buf start end (fn [a b] (g/gtk-text-buffer-select-range buf a b)))
                 (reset! pending {:caret saved :sel [start end]})
                 (g/g-timeout-add FLASH-MS restore-cb ffi/null))
               nil)
     :cancel! restore!}))

(defn do-run-form!
  "Cmd/Ctrl+Enter and the editor menu's Run Form: commands/run-form on the
  editor's snapshot. No form at the caret sets the status \"No form at the
  caret.\" and sends nothing. Otherwise it flashes the form's range, echoes
  the form at the prompt and sends it the way Run Selection does, so an
  attach evaluator switches to the buffer's namespace first.

  echo-entry!'s mark? is (not (ev/evaluating? @ev*)), the same idle/busy
  split as do-run-selection! above and for the same reason: a mid-run
  Cmd+Enter must still echo and flash, but must never steal the mark from
  a program that's still running."
  [{:keys [tab cv ev* status-label flash-fns] :as ctx}]
  (let [ed (:ed tab)
        snap (editor/snapshot ed)]
    (if-let [{:keys [text start end]} (commands/run-form snap)]
      (when-not (or (refuse-foreign! ctx)
                    (and (not (ev/evaluating? @ev*)) (before-run-stopped ctx :form tab text)))
        ((:flash! flash-fns) start end (:caret snap))
        (cview/echo-entry! cv text (not (ev/evaluating? @ev*)))
        (do-repl-eval! ctx ed text {:ns-source (:text snap) :owner (:id tab)
                                    :hook-source :form :hook-tab (:id tab)
                                    :record (tab-record tab ev* {:source :form :code text
                                                                 :buffer-text (:text snap)})}))
      (win-status/set-status! status-label "No form at the caret."))))

(defn- send-changed!
  "do-run-changed!'s send of forms, the changed forms of the tab's text,
  joined by newlines."
  [{:keys [tab cv ev*] :as ctx} text forms]
  (let [code (str/join "\n" (map (fn [{:keys [start end]}] (subs text start end)) forms))]
    (when-not (or (refuse-foreign! ctx)
                  (and (not (ev/evaluating? @ev*)) (before-run-stopped ctx :selection tab code)))
      ;; echoed as an entry, as Run Form's is, so Clear Last Output
      ;; finds it and its Running line
      (cview/echo-entry! cv code (not (ev/evaluating? @ev*)))
      (cview/append! cv :system (str "; Running " (count forms) " changed form"
                                     (when (> (count forms) 1) "s") "\n"))
      (do-repl-eval! ctx (:ed tab) code {:ns-source text :owner (:id tab)
                                         :hook-source :selection :hook-tab (:id tab)
                                         :record (tab-record tab ev* {:source :form :code code
                                                                      :buffer-text text})}))))

(defn- do-run-changed!
  "Run the forms changed since they last ran (LM3): the tab's changed
  top-level forms, in buffer order and joined by newlines, sent once the
  way Run Selection sends code. Says so instead when the tab never ran or
  nothing changed."
  [{:keys [tab status-label] :as ctx}]
  (let [ed (:ed tab)
        ran (some-> (:changed ed) :ran deref)]
    (if (nil? ran)
      (win-status/set-status! status-label "; Run the tab once first.")
      (let [text (editor/editor-text ed)
            forms (changed/changed text ran)]
        (if (empty? forms)
          (win-status/set-status! status-label "; Nothing has changed since the last run.")
          (send-changed! ctx text forms))))))

(defn make-on-submit
  "on-submit is built and handed to make-console-view BEFORE the console
  view it will act on exists, so it takes an atom and derefs it lazily, at
  call time -- by then jonquil.gtk.window.console's build has reset! it to
  the real cv (the only forward reference; every other closure in
  activate! is built after its pieces already exist)."
  [tab-fn *cv ev* status-label bang*]
  (fn [{:keys [action text]}]
    (case action
      ;; LM1: a line starting with ! runs a palette command (@bang*)
      :eval (if (pal-data/bang? text)
              (@bang* text)
              (let [tab (tab-fn)]
                (do-repl-eval! {:cv @*cv :ev* ev* :status-label status-label} (:ed tab) text
                               {:hook-source :repl :hook-tab (:id tab)
                                ;; the namespace it runs in, read before it can move
                                :record {:source :repl :code text :path nil :ns (str (ev/prompt-ns @ev*))}})))
      :send-stdin (ev/send-input! @ev* text)
      :eof (ev/send-eof! @ev*))))

(defn history-prev-fn [ev*]
  (fn [current-text]
    (let [h (ev/history @ev*)
          [new-hist text] (eval/history-prev @h current-text)]
      (reset! h new-hist)
      text)))

(defn history-next-fn
  "eval/history-next returns \"\" both when there's no history at all
  and when the browsing index is already at the draft (not currently
  browsing) -- console/handle-history! treats any non-nil result as a real
  replacement, so passing that \"\" straight through erased whatever the
  user had typed. Only call through to eval/history-next (and only reset!
  the history atom) when the index is actually behind the item count, i.e.
  there is somewhere for Down to move to; otherwise nil leaves the input
  untouched. jonquil.eval's own contract is unchanged -- this is purely the
  adapter's job of telling \"nothing to move to\" apart from \"moved to an
  empty draft\"."
  [ev*]
  (fn []
    (let [h (ev/history @ev*)
          hist @h]
      (when (< (:index hist) (count (:items hist)))
        (let [[new-hist text] (eval/history-next hist)]
          (reset! h new-hist)
          text)))))

(defn build
  "activate!'s Run, Stop, Run Selection and Run Changed fns, in activate!'s
  order. w holds what they read: :active-tab (activate!'s fn answering the
  active tab, called each time a fn runs) :cv :ev* :sel* :status-label.
  Returns w with :run-fn :stop-fn :run-selection-fn and :run-changed-fn
  added."
  [{:keys [active-tab cv ev* sel* status-label] :as w}]
  (let [run-fn (fn [] (do-run! {:tab (active-tab) :cv cv :ev* ev* :sel* sel* :status-label status-label}))
        stop-fn (fn [] (do-stop! ev* cv))
        run-selection-fn (fn [] (do-run-selection! {:tab (active-tab) :cv cv :ev* ev* :sel* sel*
                                                    :status-label status-label}))
        run-changed-fn (fn [] (do-run-changed! {:tab (active-tab) :cv cv :ev* ev* :sel* sel*
                                                :status-label status-label}))]
    (assoc w :run-fn run-fn :stop-fn stop-fn :run-selection-fn run-selection-fn :run-changed-fn run-changed-fn)))
