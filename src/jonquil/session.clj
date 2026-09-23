(ns jonquil.session
  "One window's evaluation session: Run and the REPL prompt over one eval
  context. Run resets by default (spec decision O1): it removes every
  namespace earlier Runs and REPL entries created, except libraries, plus
  `user`, then evaluates into a fresh `user`."
  (:require [clojure.set :as set]
            [jonquil.eval :as eval]))

(defn make-session
  "A session in reset mode with the prompt in `user`."
  []
  {:ctx (eval/make-eval-context)
   :state (atom {:mode :reset :prompt-ns 'user :created #{}})})

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
  "Removes what earlier evaluations created, plus `user`, then recreates
  `user` at once so a doc lookup never finds it missing."
  [{:keys [state]}]
  (doseq [n (conj (:created @state) 'user)]
    (when (find-ns n) (remove-ns n)))
  (create-ns 'user)
  (swap! state assoc :created #{} :prompt-ns 'user))

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
                                       (-> s
                                           (update :created into
                                                   (namespaces-created before (ns-names) (loaded-libs)))
                                           (assoc :prompt-ns (:ns result)))))
                              (when user-complete (user-complete result)))))))

(defn run-definitions!
  "Runs the Definitions text. In :reset mode, first removes what earlier
  evaluations created. Returns nil when started, or the engine's warning
  string when a program is already running (and then resets nothing)."
  ([session code] (run-definitions! session code {}))
  ([{:keys [ctx state] :as session} code opts]
   (when (and (not (eval/evaluating? ctx)) (= :reset (:mode @state)))
     (reset-namespaces! session))
   (create-ns 'user)
   (evaluate! session code 'user opts)))

(defn submit-repl!
  "Evaluates one REPL entry in the namespace the last evaluation left, and
  records it in the history. Returns nil or the engine's warning string."
  ([session code] (submit-repl! session code {}))
  ([{:keys [ctx state] :as session} code opts]
   (swap! (:history ctx) eval/history-add code)
   (evaluate! session code (:prompt-ns @state) opts)))
