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
  (:require [clojure.set :as set]
            [jonquil.docs :as docs]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.inspect.view :as view]
            [jonquil.project-roots :as roots]))

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
    (docs/symbol-doc (:prompt-ns @state) sym text pos))
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

(defn run-definitions!
  "Runs the Definitions text. In :reset mode, first removes what earlier
  evaluations created. Returns nil when started, or the engine's warning
  string when a program is already running (and then resets nothing)."
  ([session code] (run-definitions! session code {}))
  ([{:keys [ctx state] :as session} code opts]
   (let [owner (:owner opts :run)]
     (when (and (not (eval/evaluating? ctx)) (= :reset (:mode @state)))
       (reset-namespaces! session owner))
     (create-ns 'user)
     (evaluate! session code 'user (assoc opts :owner owner)))))

(defn submit-repl!
  "Evaluates one REPL entry in the namespace the last evaluation left, and
  records it in the history. Returns nil or the engine's warning string."
  ([session code] (submit-repl! session code {}))
  ([{:keys [ctx state] :as session} code opts]
   (swap! (:history ctx) eval/history-add code)
   (evaluate! session code (:prompt-ns @state) (assoc opts :owner (:owner opts :repl)))))
