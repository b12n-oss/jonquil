(ns jonquil.session
  "One window's evaluation session: Run and the REPL prompt over one eval
  context. Run resets by default. Every namespace an
  evaluation creates, except libraries, belongs to that evaluation's
  :owner (a tab id, :run or :repl), and a reset-mode Run removes only its
  own owner's namespaces, the REPL's, and `user`, then evaluates into a
  fresh `user`. Other owners' namespaces stay.

  A session is the InProcess record, the in-process implementation of
  jonquil.evaluator/Evaluator. It keeps the :ctx and :state keys a plain
  map had, so every caller that reads them is unchanged."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [jonquil.clj :as clj]
            [jonquil.debugger :as debugger]
            [jonquil.fibers-rt :as fibers-rt]
            [jonquil.line-counts :as line-counts]
            [jonquil.results-rt :as results-rt]
            [jonquil.trace-rt :as trace-rt]
            [jonquil.world-rt :as world-rt]
            [jonquil.dialect :as dialect]
            [jonquil.docs :as docs]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.inspect.view :as view]
            [jonquil.project-roots :as roots]
            [jonquil.source :as source]))

(declare run-definitions! submit-repl! quiet-eval! set-mode!)

(defn release-owner-in!
  "Moves owner's namespaces under :repl in state's :created map (an atom
  holding {:created {owner #{ns}}}). Shared by the in-process session and
  the nREPL evaluator (jonquil.evaluator/Owners)."
  [state owner]
  (swap! state update :created
         (fn [c] (if-let [nss (get c owner)]
                   (-> c (update :repl (fnil into #{}) nss) (dissoc owner))
                   c))))

(defn- inspect-local-value
  "The :start-local value for an inspect request: the paused break's local,
  else the last error's, or ::stale / ::debugger/missing."
  [state op args]
  (let [paused (when (= op :start-local)
                 (debugger/paused-local (:break @state) (:error-id args) (:frame args) (:local args)))]
    (when (= op :start-local)
      (if-let [e (when (= ::debugger/not-paused paused) (:last-error @state))]
        (if (debugger/stale? e (:error-id args))
          ::stale
          (debugger/local-value e (:frame args) (:local args) {:frames-fn (debugger/frames-fn)}))
        (if (= ::debugger/not-paused paused) ::debugger/missing paused)))))

(defn- inspect-traced-value
  "The :start-trace / :start-result value, ::trace-rt/gone when it was dropped."
  [op args]
  (case op
    :start-trace (trace-rt/value (:id args) (:part args))
    :start-result (let [v (results-rt/value (:id args) (:epoch args))]
                    (if (= v ::results-rt/gone) ::trace-rt/gone v))
    nil))

(defn- inspect-request
  "[op args] to hand to the view, the value filled in for the :start ops."
  [state op args {:keys [local traced]}]
  (case op
    :start [:start (assoc args :value (:last-value @state))]
    :start-local [:start (assoc args :value local)]
    :start-trace [:start (assoc args :value traced)]
    :start-result [:start (assoc args :value traced)]
    :def [:def (assoc args :target-ns (the-ns (:prompt-ns @state)))]
    [op args]))

(defn- inspect-view
  "What the panel shows: an error map for a missing value, else the view's."
  [state op op' args' local]
  (case local
    ::debugger/missing {:error "That local is gone."}
    ::stale {:error debugger/STALE}
    ::gone {:error (if (= op :start-result) "That result is gone." "That call is gone.")}
    ::lazy {:error "That value was a lazy seq nothing had realized, so it wasn't kept."}
    (view/run-op (:inspect-view @state) op' args')))

(defn- inspect-run!
  "The body of InProcess inspect!, on its future: resolves the value, runs the
  view op and hands the result to on-view; a failure goes to on-view as an
  error map, and a failing on-view is ignored."
  [state op args on-view]
  (try
    (let [local (inspect-local-value state op args)
          traced (inspect-traced-value op args)
          local (cond (= traced ::trace-rt/gone) ::gone
                      (= traced ::trace-rt/lazy) ::lazy
                      :else local)
          [op' args'] (inspect-request state op args {:local local :traced traced})]
      (try (on-view (inspect-view state op op' args' local))
           (catch Throwable _)))
    (catch Throwable e
      (try (on-view {:error (or (ex-message e) (str (class e)))})
           (catch Throwable _)))))

(defrecord InProcess [ctx state]
  ev/Evaluator
  (start! [_] nil)
  ;; The in-process engine lives and dies with Jonquil's own process, as it
  ;; did before the Evaluator protocol existed: nothing to end.
  ;; a paused break (Break on error) is let go, so closing the window or
  ;; switching runtimes never leaves its worker blocked
  (stop! [_] (debugger/continue! (:break @state)) nil)
  (eval! [this code opts]
    (cond
      (:quiet opts) (quiet-eval! this code (dissoc opts :run?))
      (:run? opts) (run-definitions! this code (dissoc opts :run?))
      :else (submit-repl! this code (dissoc opts :run?))))
  (interrupt! [_]
    (eval/stop-eval! ctx)
    ;; Stop also reaches a form evaluated in a frame (jonquil.debugger)
    (some-> (:frame-token @state) debugger/interrupt-token!)
    ;; and a paused break (Break on error)
    (debugger/break-stop! (:break @state))
    nil)
  (evaluating? [_] (eval/evaluating? ctx))
  (waiting-for-input? [_] (eval/waiting-for-input? ctx))
  (send-input! [_ text] (eval/push-stdin! ctx text))
  (send-eof! [_] (eval/push-stdin-eof! ctx))
  (history [_] (:history ctx))
  (prompt-ns [_] (:prompt-ns @state))
  (set-mode! [this mode] (set-mode! this mode))
  (complete [_ prefix text pos extra-context]
    (docs/candidates (:prompt-ns @state) prefix text pos extra-context))
  (lookup [_ sym text pos]
    ;; a newer Jolt records a required namespace's :file as its classpath
    ;; name, which resolves on the roots project-roots set
    (some-> (docs/symbol-doc (:prompt-ns @state) sym text pos) source/resolve-file))
  (limitations [_] [])
  ev/QuietEval
  (quiet-eval? [_] true)
  ev/Inspector
  (inspect! [_ op args on-view]
    (future (inspect-run! state op args on-view))
    nil)
  (inspectable? [_] true)
  ev/Frames
  (error-frames! [_ on-data]
    (future
      (let [d (try (if-let [e (:last-error @state)]
                     (debugger/frames-data e {:frames-fn (debugger/frames-fn)
                                              :resolve-file (fn [f] (:file (source/resolve-file {:file f})))})
                     {:note "No error yet."})
                   (catch Throwable e {:error (or (ex-message e) (str (class e)))}))]
        (try (on-data d) (catch Throwable _))))
    nil)
  (toggle-debug-mode! [_ on-done]
    (future
      (let [r (try (debugger/toggle-debug-mode!)
                   (catch Throwable e {:error (or (ex-message e) (str (class e)))}))]
        (try (on-done r) (catch Throwable _))))
    nil)
  (eval-in-frame! [_ i code error-id on-result]
    (future
      (let [token (debugger/make-token)
            r (try (let [e (:last-error @state)]
                     (cond (nil? e) {:error "No error yet."}
                           (debugger/stale? e error-id) {:error debugger/STALE}
                           :else (do (swap! state assoc :frame-token token)
                                     (debugger/eval-in-frame e i code {:frames-fn (debugger/frames-fn) :token token}))))
                   (catch Throwable e {:error (or (ex-message e) (str (class e)))})
                   (finally (swap! state (fn [st] (cond-> st (identical? token (:frame-token st)) (dissoc :frame-token))))))]
        (try (on-result r) (catch Throwable _))))
    nil)
  ev/Fibers
  (fibers-op! [_ op args on-result]
    (future (let [r (fibers-rt/op op args)] (try (on-result r) (catch Throwable _))))
    nil)
  ev/LineCounts
  (toggle-line-counts! [_ on-done]
    (future (let [r (line-counts/toggle!)] (try (on-done r) (catch Throwable _))))
    nil)
  (line-counts! [_ path on-data]
    (future (let [r (line-counts/fetch path)] (try (on-data r) (catch Throwable _))))
    nil)
  (clear-line-counts! [_ on-done]
    (future (let [r (line-counts/clear!)] (try (on-done r) (catch Throwable _))))
    nil)
  ev/Break
  (set-break-handlers! [_ handlers] (swap! state assoc :break-handlers handlers) nil)
  (toggle-break! [_ on-done]
    (future
      (let [r (if (debugger/break-available?)
                {:on? (:break-on? (swap! state update :break-on? not))}
                {:error "Frames need a Jolt with jolt.debug (newer than this one)."})]
        (try (on-done r) (catch Throwable _))))
    nil)
  (break-eval! [_ i code on-result]
    (debugger/break-eval! (:break @state) i code (fn [r] (try (on-result r) (catch Throwable _)))))
  (continue-break! [_] (debugger/continue! (:break @state)))
  ev/Trace
  (trace-op! [_ op args on-result]
    (future
      (let [r (try (trace-rt/op op args)
                   (catch Throwable e {:error (or (ex-message e) (str (class e)))}))]
        (try (on-result r) (catch Throwable _))))
    nil)
  ev/World
  (world-op! [_ op args on-result]
    ;; only the namespaces this session's Runs created, and user: in
    ;; process every other loaded one is Jonquil's own or a library's
    (future
      (let [nss (into ["user"] (comp (mapcat val) (map str) (distinct) (remove #{"user"}))
                      (:created @state))
            r (try (world-rt/op op (assoc args :namespaces nss))
                   (catch Throwable e {:error (or (ex-message e) (str (class e)))}))]
        (try (on-result r) (catch Throwable _))))
    nil)
  ev/Owners
  (release-owner! [_ owner] (release-owner-in! state owner) nil))

(defn make-session
  "A session in reset mode with the prompt in `user`."
  []
  (->InProcess (eval/make-eval-context)
               (atom {:mode :reset :prompt-ns 'user :created {}
                      :inspect-view (atom nil)
                      ;; Break on error: off until toggled
                      :break (debugger/make-break) :break-on? false})))

(defmethod ev/make :in-process [selection]
  ;; The project's source folders, so a project kept in process can require
  ;; its own namespaces (jonquil.project-roots). :paths only here, with no
  ;; network; each evaluation resolves :deps on its worker (evaluate!).
  (roots/use-project! (:project-dir selection))
  (doto (make-session)
    (-> :state (swap! assoc :project-dir (:project-dir selection)
                      :auto-require (when (:project-dir selection) (seq (:auto-require selection)))
                      :auto-require-dir (:project-dir selection)))))

(defn- ns-names [] (set (map ns-name (all-ns))))

(defn namespaces-created
  "Namespaces present after an evaluation that were not present before it,
  minus loaded libraries. A library a Run requires for the first time must
  survive a reset: once removed, `require` does not bring it back."
  [before after libs]
  (set/difference (set after) (set before) (set libs)))

(defn set-mode!
  "mode is :reset or :accumulate."
  [session mode]
  (swap! (:state session) assoc :mode mode))

(defn reset-namespaces!
  "Removes what owner's earlier evaluations created, what REPL entries
  created, and `user`, then recreates `user` at once so a doc lookup never
  finds it missing. Other owners' namespaces stay."
  [{:keys [state]} owner]
  (let [created (:created @state)]
    (doseq [n (concat (get created owner) (get created :repl) ['user])]
      (when (find-ns n) (remove-ns n))))
  (create-ns 'user)
  (swap! state (fn [s] (-> s
                           (update :created dissoc owner :repl)
                           (assoc :prompt-ns 'user)))))

(def auto-require-retry
  "When an in-process :auto-require list that failed runs again."
  "It runs again in a new window.")

(defn auto-require!
  "Once per session, before its first evaluation in the window's own
  project: says so, then requires .jonquil.edn's :auto-require list, on
  the eval worker after the project's roots and :deps are set (so a
  dependency's namespace can be in it). The namespaces are libraries, so
  Reset on Run keeps them. A failure prints its error and says so, and
  isn't tried again in this session; a Stop stops the evaluation and the
  next one tries again. Flagged
  first, so a list that throws isn't resent on every evaluation."
  [ctx state dir]
  (let [{nss :auto-require ar-dir :auto-require-dir done? :auto-required?} @state
        ;; Stop reaches a require here as a plain thread interrupt (this runs
        ;; outside the interruptible section), so ask the engine's own flag.
        stopped? (fn [e] (or (eval/interrupted? e)
                             (boolean (some-> (:running-state ctx) deref :stop-requested deref))))]
    (when (and (seq nss) (not done?) (= dir ar-dir))
      (swap! state assoc :auto-required? true)
      (println (clj/auto-require-message nss))
      (try
        (apply require nss)
        (catch Throwable e
          ;; Stopped: not loaded, so the next evaluation tries again
          (when (stopped? e)
            (swap! state assoc :auto-required? false)
            (throw e))
          (binding [*out* *err*]
            (println (eval/format-error e))
            (println (clj/auto-require-failed auto-require-retry))))))))

(defn- eval-tell-fn
  "The call for a break handler k (:on-eval-start, :on-eval-end) of this
  evaluation; a no-op for a quiet one (eval-id nil), which calls neither."
  [state eval-id]
  (fn [k] (when eval-id
            (when-let [f (k (:break-handlers @state))]
              (try (f eval-id) (catch Throwable _ nil))))))

(defn- eval-auto-require!
  [ctx state dir opts]
  (if (and (:quiet opts) (:on-output opts))
    ;; a quiet eval's *out* is the plugin's:
    ;; the auto-require lines are the person's
    (let [s (with-out-str (binding [*err* *out*]
                            (auto-require! ctx state dir)))]
      (when (seq s) ((:on-output opts) :system s)))
    (auto-require! ctx state dir)))

(defn- eval-before-fn
  "The :before-eval step: the break handlers hear a person's evaluation
  start. Then the project's :paths and :deps as Jolt's roots, resolved on
  the worker: a first resolve can fetch dependencies, which mustn't freeze
  the window."
  [{:keys [ctx state tell dir opts]}]
  (fn []
    (tell :on-eval-start)
    (when dir
      (roots/use-project-deps! dir)
      (eval-auto-require! ctx state dir opts))))

(defn- eval-wrapper
  "Break on error; a quiet eval (completion, help, a plugin's) never pauses"
  [state opts]
  (when (and (:break-on? @state) (not (:quiet opts)))
    (debugger/break-wrapper
     (:break @state)
     (assoc (:break-handlers @state)
            :resolve-file (fn [f] (:file (source/resolve-file {:file f})))))))

(defn- keep-result-ref
  "LM1: the console's line keeps a handle to this value"
  [opts result]
  (when (and (not (:quiet opts)) (#{:ok :error} (:status result)))
    (try (results-rt/keep! (if (= :ok (:status result)) (:value result) (:error result)))
         (catch Throwable _ nil))))

(defn- record-result!
  [{:keys [state opts before result]}]
  (swap! state
         (fn [s]
           (cond-> (-> s
                       (update-in [:created (:owner opts)] (fnil into #{})
                                  (namespaces-created before (ns-names) (loaded-libs)))
                       (cond-> (not (:quiet opts)) (assoc :prompt-ns (:ns result))))
             (and (= :ok (:status result)) (not (:quiet opts)))
             (assoc :last-value (:value result))
             ;; the Frames panel's error; a quiet eval's
             ;; (completion, help) never replaces it
             (and (= :error (:status result)) (not (:quiet opts)))
             (assoc :last-error (:error result))))))

(defn- eval-complete-fn
  [{:keys [state opts tell user-complete] :as step}]
  (fn [result]
    ;; the backstop for a pause whose cleanup a Stop skipped
    (debugger/end-stale-pause! (:break @state) (:break-handlers @state))
    (let [ref (keep-result-ref opts result)
          result (cond-> result ref (assoc :ref ref))]
      (record-result! (assoc step :result result))
      ;; the end after on-complete, which records the failure
      (try (when user-complete (user-complete result))
           (finally (tell :on-eval-end))))))

(defn- evaluate!
  [{:keys [ctx state]} code ns-sym opts]
  (let [step {:ctx ctx :state state :opts opts
              :before (ns-names)
              :user-complete (:on-complete opts)
              :dir (:project-dir @state)
              ;; this evaluation, for the break handlers' :on-eval-start and
              ;; :on-eval-end; a quiet one calls neither
              :tell (eval-tell-fn state (when-not (:quiet opts) (Object.)))}]
    (eval/eval-async ctx code
                     (assoc opts
                            :before-eval (eval-before-fn step)
                            :ns-name ns-sym
                            :wrap-eval (eval-wrapper state opts)
                            :on-complete (eval-complete-fn step)))))

(defn- program-vars
  "{var meta} for every var interned in a namespace that isn't a loaded
  library: the program's own namespaces (Jonquil's and Jolt's are libraries)."
  []
  (let [libs (set (loaded-libs))]
    (into {}
          (for [n (all-ns)
                :when (not (libs (ns-name n)))
                v (vals (ns-interns n))]
            [v (meta v)]))))

(defn saved-file
  "path's canonical path when it names a file on disk holding exactly code,
  else nil. A buffer with unsaved edits doesn't match its file, and its
  vars' lines would point into the wrong text there. The Jolt child's Run
  uses it too (jonquil.nrepl.evaluator)."
  [path code]
  (when path
    (try (let [f (io/file path)]
           (when (and (.isFile f) (= code (slurp f))) (.getCanonicalPath f)))
         (catch Throwable _ nil))))

(defn mark-run-file!
  "Gives file to every var a Run defined: one whose metadata isn't what it
  was before the Run (before, program-vars' map; a def replaces a var's
  metadata), with a :line and no :file. On a Jolt whose eval-string takes no
  source path a Run's text has no file: Jolt turns the reader's file off there on purpose
  (an evaluated string isn't the file that called it), so these vars had a
  line but nowhere to jump to. A var a REPL entry defined earlier keeps its
  metadata, so it isn't marked; one a require loaded already has a :file.
  What else changes a var's metadata during the Run is marked too: an
  alter-meta! the program does, a load-file of another file, a future the
  program started (all are the Run's own doing)."
  [file before]
  ;; the fallback for a Jolt whose eval-string takes no source path
  ;; (before jolt-lang/jolt#1476): run-definitions! passes the path there
  (doseq [[v m] (program-vars)
          :when (and (:line m) (nil? (:file m)) (not= m (get before v)))]
    (try (alter-meta! v assoc :file file) (catch Throwable _ nil))))

(defn run-definitions!
  "Runs the Definitions text. In :reset mode, first removes what earlier
  evaluations created. Returns nil when started, or the engine's warning
  string when a program is already running (and then resets nothing). With
  :path naming a file that holds exactly code, the vars the Run defines get
  it as their :file (eval-string's source path, or mark-run-file! on a Jolt
  without one), so jump to definition from another tab can open it."
  ([session code] (run-definitions! session code {}))
  ([{:keys [ctx state] :as session} code opts]
   (let [owner (:owner opts :run)]
     ;; A Run of a file in a project uses that project's source folders and
     ;; dependencies (evaluate!'s :before-eval), and so do the REPL entries
     ;; after it: a window keeps one in-process evaluator when it opens a
     ;; file from another in-process project (selection-key leaves the
     ;; project out), so make's own project isn't enough. A loose file leaves them as they are.
     (when-let [dir (and (not (eval/evaluating? ctx))
                         (some-> (:path opts) dialect/find-project :dir))]
       (swap! state assoc :project-dir dir))
     (when (and (not (eval/evaluating? ctx)) (= :reset (:mode @state))
                (not (false? (:reset? opts))))
       (reset-namespaces! session owner))
     (create-ns 'user)
     ;; A Jolt whose eval-string takes a source path evaluates the text as
     ;; the file, so its vars get it from the reader. Elsewhere they're
     ;; marked in before-idle, on the Run's own worker before the context
     ;; goes idle, so a REPL entry or another Run can't define a var in
     ;; between and get this file.
     ;; LM4: a Run counts its own lines alone. Cleared here, before the
     ;; worker starts, so it can't race the Run (an evaluation already
     ;; running is refused below, and its counts are left alone)
     (when (and (:clear-line-counts? opts) (not (eval/evaluating? ctx)))
       (line-counts/clear!))
     (let [file (when-not (eval/evaluating? ctx) (saved-file (:path opts) code))
           before (when (and file (not (eval/source-path?))) (program-vars))
           opts (cond-> (assoc opts :owner owner)
                  file (assoc :source-path file)
                  before (assoc :before-idle
                                (fn [_] (try (mark-run-file! file before) (catch Throwable _ nil)))))]
       (evaluate! session code 'user opts)))))

(defn quiet-eval!
  "eval!'s :quiet path: code in the namespace of :ns-source's
  ns form when that exists, else the prompt's, with no history entry and
  the prompt namespace, *1 *2 *3 and *e left as they were."
  [{:keys [state] :as session} code opts]
  (let [b (some-> (:ns-source opts) clj/buffer-ns symbol)]
    (evaluate! session code (if (and b (find-ns b)) b (:prompt-ns @state))
               (assoc opts :owner (:owner opts :repl)))))

(defn submit-repl!
  "Evaluates one REPL entry in the namespace the last evaluation left, and
  records it in the history. Returns nil or the engine's warning string."
  ([session code] (submit-repl! session code {}))
  ([{:keys [ctx state] :as session} code opts]
   (swap! (:history ctx) eval/history-add code)
   (evaluate! session code (:prompt-ns @state) (assoc opts :owner (:owner opts :repl)))))
