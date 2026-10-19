(ns jonquil.session
  "One window's evaluation session: Run and the REPL prompt over one eval
  context. Run resets by default (spec decision O1). Every namespace an
  evaluation creates, except libraries, belongs to that evaluation's
  :owner (a tab id, :run or :repl), and a reset-mode Run removes only its
  own owner's namespaces, the REPL's, and `user`, then evaluates into a
  fresh `user`. Other owners' namespaces stay (tabs spec).

  A session is the InProcess record, the in-process implementation of
  jonquil.evaluator/Evaluator. It keeps the :ctx and :state keys a plain
  map had, so every caller that reads them is unchanged."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [jonquil.dialect :as dialect]
            [jonquil.docs :as docs]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.inspect.view :as view]
            [jonquil.project-roots :as roots]
            [jonquil.source :as source]))

(declare run-definitions! submit-repl! set-mode!)

(defn release-owner-in!
  "Moves owner's namespaces under :repl in state's :created map (an atom
  holding {:created {owner #{ns}}}). Shared by the in-process session and
  the nREPL evaluator (jonquil.evaluator/Owners)."
  [state owner]
  (swap! state update :created
         (fn [c] (if-let [nss (get c owner)]
                   (-> c (update :repl (fnil into #{}) nss) (dissoc owner))
                   c))))

(defrecord InProcess [ctx state]
  ev/Evaluator
  (start! [_] nil)
  ;; The in-process engine lives and dies with Jonquil's own process, as it
  ;; did before the Evaluator protocol existed: nothing to end.
  (stop! [_] nil)
  (eval! [this code opts]
    (if (:run? opts)
      (run-definitions! this code (dissoc opts :run?))
      (submit-repl! this code (dissoc opts :run?))))
  (interrupt! [_] (eval/stop-eval! ctx) nil)
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
  ev/Inspector
  (inspect! [_ op args on-view]
    (future
      (try
        (let [args' (case op
                      :start (assoc args :value (:last-value @state))
                      :def (assoc args :target-ns (the-ns (:prompt-ns @state)))
                      args)]
          (try (on-view (view/run-op (:inspect-view @state) op args'))
               (catch Throwable _)))
        (catch Throwable e
          (try (on-view {:error (or (ex-message e) (str (class e)))})
               (catch Throwable _)))))
    nil)
  (inspectable? [_] true)
  ev/Owners
  (release-owner! [_ owner] (release-owner-in! state owner) nil))

(defn make-session
  "A session in reset mode with the prompt in `user`."
  []
  (->InProcess (eval/make-eval-context)
               (atom {:mode :reset :prompt-ns 'user :created {}
                      :inspect-view (atom nil)})))

(defmethod ev/make :in-process [selection]
  ;; The project's source folders, so a project kept in process can require
  ;; its own namespaces (jonquil.project-roots).
  (roots/use-project! (:project-dir selection))
  (make-session))

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

(defn- evaluate!
  [{:keys [ctx state]} code ns-sym opts]
  (let [before (ns-names)
        user-complete (:on-complete opts)]
    (eval/eval-async ctx code
                     (assoc opts
                            :ns-name ns-sym
                            :on-complete
                            (fn [result]
                              (swap! state
                                     (fn [s]
                                       (cond-> (-> s
                                                   (update-in [:created (:owner opts)] (fnil into #{})
                                                              (namespaces-created before (ns-names) (loaded-libs)))
                                                   (assoc :prompt-ns (:ns result)))
                                         (= :ok (:status result)) (assoc :last-value (:value result)))))
                              (when user-complete (user-complete result)))))))

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

(defn- saved-file
  "path's canonical path when it names a file on disk holding exactly code,
  else nil. A buffer with unsaved edits doesn't match its file, and its
  vars' lines would point into the wrong text there (review finding 2)."
  [path code]
  (when path
    (try (let [f (io/file path)]
           (when (and (.isFile f) (= code (slurp f))) (.getCanonicalPath f)))
         (catch Throwable _ nil))))

(defn mark-run-file!
  "Gives file to every var a Run defined: one whose metadata isn't what it
  was before the Run (before, program-vars' map; a def replaces a var's
  metadata), with a :line and no :file. A Run evaluates the buffer's text
  with load-string, and Jolt turns the reader's file off there on purpose
  (an evaluated string isn't the file that called it), so these vars had a
  line but nowhere to jump to. A var a REPL entry defined earlier keeps its
  metadata, so it isn't marked; one a require loaded already has a :file.
  What else changes a var's metadata during the Run is marked too: an
  alter-meta! the program does, a load-file of another file, a future the
  program started (review finding 3, accepted: all are the Run's own
  doing)."
  [file before]
  (doseq [[v m] (program-vars)
          :when (and (:line m) (nil? (:file m)) (not= m (get before v)))]
    (try (alter-meta! v assoc :file file) (catch Throwable _ nil))))

(defn run-definitions!
  "Runs the Definitions text. In :reset mode, first removes what earlier
  evaluations created. Returns nil when started, or the engine's warning
  string when a program is already running (and then resets nothing). With
  :path naming a file that holds exactly code, the vars the Run defines get
  it as their :file
  (mark-run-file!), so jump to definition from another tab can open it."
  ([session code] (run-definitions! session code {}))
  ([{:keys [ctx state] :as session} code opts]
   (let [owner (:owner opts :run)]
     ;; A Run of a file in a project uses that project's source folders: a
     ;; window keeps one in-process evaluator when it opens a file from
     ;; another in-process project (selection-key leaves the project out),
     ;; so make's own call isn't enough (review finding 1). A loose file
     ;; leaves them as they are.
     (when-let [dir (some-> (:path opts) dialect/find-project :dir)]
       (roots/use-project! dir))
     (when (and (not (eval/evaluating? ctx)) (= :reset (:mode @state)))
       (reset-namespaces! session owner))
     (create-ns 'user)
     ;; Marked in before-idle, on the Run's own worker before the context
     ;; goes idle, so a REPL entry or another Run can't define a var in
     ;; between and get this file (review finding 1).
     (let [file (when-not (eval/evaluating? ctx) (saved-file (:path opts) code))
           before (when file (program-vars))
           opts (cond-> (assoc opts :owner owner)
                  before (assoc :before-idle
                                (fn [_] (try (mark-run-file! file before) (catch Throwable _ nil)))))]
       (evaluate! session code 'user opts)))))

(defn submit-repl!
  "Evaluates one REPL entry in the namespace the last evaluation left, and
  records it in the history. Returns nil or the engine's warning string."
  ([session code] (submit-repl! session code {}))
  ([{:keys [ctx state] :as session} code opts]
   (swap! (:history ctx) eval/history-add code)
   (evaluate! session code (:prompt-ns @state) (assoc opts :owner (:owner opts :repl)))))
