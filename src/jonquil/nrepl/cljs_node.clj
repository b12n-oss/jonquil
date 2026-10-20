(ns jonquil.nrepl.cljs-node
  "The ClojureScript-on-node evaluator: a window on a .cljs file owns a
  ClojureScript child (clojure launching cljs.main's node prepl), started
  lazily on the first evaluation and spoken to over a line-EDN prepl
  (jonquil.nrepl.prepl).

  The shape follows jonquil.nrepl.jank. The prepl has no request ids, so
  each evaluation sends its code followed by a sentinel form and reads
  replies until the sentinel's ret. Stop kills the child (no interrupt), so
  earlier definitions are gone afterwards. Help evaluates (cljs.repl/doc x)
  only while idle; a help that times out leaves its sentinel in :drain, and
  replies up to its ret are dropped before the next evaluation's are read.

  Replies reach the state through one handler per connection. It drops
  anything after that connection's :closed (the prepl client may deliver a
  few buffered replies late) and touches shared state only through atoms,
  so overlapping calls from the reader thread and close! are safe."
  (:require [clojure.string :as str]
            [jonquil.clj :as clj]
            [jonquil.cljd :as cljd]
            [jonquil.cljs-node :as cljs-node]
            [jonquil.commands :as commands]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.cljs-child :as cljs-child]
            [jonquil.nrepl.cljs-node-files :as files]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.prepl :as prepl]))

(def default-timings
  {;; estimate: a cold clojure start resolves ClojureScript; the setup
   ;; require comes after the ready line, so this is slack.
   :setup-timeout-ms 30000
   ;; estimate: off the GTK thread (ask-help! binds the longer bound).
   :help-timeout-ms 3000
   :full-doc-timeout-ms 5000
   ;; nil: cljs-child/start! applies its own default.
   :start-timeout-ms nil})

(def busy-warning
  "; [Warning: Evaluation already in progress. Press Stop to cancel.]\n")

(def not-running-doc "No docs: ClojureScript isn't running yet. Run something first.")
(def busy-doc "No docs: ClojureScript is busy evaluating.")
(def timeout-doc "No docs: ClojureScript didn't answer in time.")

(def exited-text "Error: the ClojureScript process exited")

(defn- stopping? [{:keys [state] :as ev}]
  (or (connection/stop-requested? ev) (boolean (:stopped? @state))))

(defn- finish!
  "Ends the running evaluation: clears :running, then idle, then
  on-complete, each in its own try. An interrupted result then says why the
  child is gone."
  [{:keys [state] :as ev} {:keys [on-output on-status-change on-complete]} result]
  (let [stopped? (and (= :interrupted (:status result)) (stopping? ev))]
    (swap! state assoc :running nil)
    (try (when on-status-change (on-status-change :idle)) (catch Throwable _ nil))
    (try (when on-complete (on-complete result)) (catch Throwable _ nil))
    (when stopped?
      (connection/say! on-output :system (str cljs-node/stopped-message "\n")))))

(defn- live? [{:keys [state]}]
  (let [{:keys [conn ready?]} @state]
    (boolean (and ready? (prepl/open? conn)))))

(defn- kill-child!
  "Closes the connection (an evaluation waiting on it sees :closed), clears
  the child and kills its process tree. Idempotent."
  [{:keys [state hooks] :as ev}]
  (let [[old _] (swap-vals! state assoc :child nil :conn nil :ready? false
                            :used? false :prompt-ns 'cljs.user :drain nil)]
    (when (:conn old) (try (prepl/close! (:conn old)) (catch Throwable _ nil)))
    (when (:child old)
      ((:kill hooks) (:child old))
      (connection/notify! ev false))
    nil))

(defn- ensure-child!
  "nil once a live child is stored in state, else the result to finish the
  evaluation with. A stored child that died, or lost its connection, is
  killed first. A Stop during the launch kills what it produced."
  [{:keys [state hooks selection] :as ev} on-output]
  (let [child (:child @state)]
    (if (and child ((:alive? hooks) child) (prepl/open? (:conn @state)))
      nil
      (do
        (when child (kill-child! ev))
        (if-let [clojure ((:find-clojure hooks))]
          (let [launcher (try ((:ensure-launcher hooks)) (catch Throwable e e))]
            (if (instance? Throwable launcher)
              {:status :notice :text (cljs-node/start-failed-message (ex-message launcher))}
              (do
                (connection/say! on-output :system (str (cljs-node/starting-message clojure) "\n"))
                (let [started ((:launch hooks)
                               {:clojure clojure
                                :launcher launcher
                                :project-dir (:project-dir selection)
                                :cancelled? (fn [] (stopping? ev))})]
                  (cond
                    (= :cancelled (:error started)) {:status :interrupted}
                    (:error started) {:status :notice :text (cljs-node/start-failed-message (:error started))}
                    :else (do (swap! state assoc :child started)
                              (when (stopping? ev) (kill-child! ev))
                              nil))))))
          {:status :notice :text cljs-node/no-clojure-message})))))

(defn- dispatch!
  "Routes one reply of connection gen to the current sink. closed? is that
  connection's own flag: a reply after its :closed is dropped, and :closed
  itself is delivered once."
  [{:keys [state]} closed? r]
  (cond
    (= :closed (:tag r))
    (when (compare-and-set! closed? false true)
      (swap! state assoc :drain nil)
      (when-let [f (:sink @state)] (f r)))

    @closed? nil

    :else
    (let [{:keys [drain sink]} @state]
      (if drain
        (when (and (= :ret (:tag r)) (= drain (:form r)))
          (swap! state (fn [s] (cond-> s (= drain (:drain s)) (assoc :drain nil)))))
        (when sink (sink r))))))

(defn- exchange!
  "Sends text and collects the replies until (done? reply) or :closed or
  timeout-ms. [outcome replies], outcome :done, :closed or :timeout. Output
  replies go to on-output as they arrive when it is given."
  [{:keys [state]} text done? {:keys [on-output timeout-ms]}]
  (let [p (promise)
        replies (atom [])
        sink (fn [r]
               (if (= :closed (:tag r))
                 (deliver p :closed)
                 (do (swap! replies conj r)
                     (when on-output
                       (when-let [[k t] (cljs-node/output-of r)]
                         (connection/say! on-output k t)))
                     (when (done? r) (deliver p :done)))))]
    (swap! state assoc :sink sink)
    (let [outcome (try (prepl/send! (:conn @state) text)
                       (if timeout-ms (deref p timeout-ms :timeout) (deref p))
                       (catch Throwable _ :closed))]
      (swap! state assoc :sink nil)
      [outcome @replies])))

(defn- next-sentinel! [{:keys [state]}]
  (cljs-node/sentinel (:n (swap! state update :n inc))))

(defn- ret? [r] (= :ret (:tag r)))

(defn- connect!
  "Connects the prepl to the stored child and runs the setup form. nil when
  ready, else the result to finish with."
  [{:keys [state timings] :as ev}]
  (let [child (:child @state)
        closed? (atom false)
        conn (try (prepl/connect! "127.0.0.1" (:port child) (fn [r] (dispatch! ev closed? r)))
                  (catch Throwable e e))]
    (if (instance? Throwable conn)
      {:status :notice
       :text (cljs-node/start-failed-message
              (str "couldn't connect to port " (:port child) ": " (ex-message conn)
                   (when (:log child) (str ". The log: " (:log child)))))}
      (do
        (swap! state assoc :conn conn)
        (if (stopping? ev)
          {:status :interrupted}
          (let [[outcome replies] (exchange! ev cljs-node/setup-form ret?
                                             {:timeout-ms (:setup-timeout-ms timings)})]
            (cond
              (stopping? ev) {:status :interrupted}
              (and (= :done outcome) (not-any? :exception replies))
              (do (swap! state assoc :ready? true)
                  (connection/notify! ev true)
                  nil)
              :else
              {:status :notice
               :text (cljs-node/start-failed-message
                      (str "the session setup " (if (= :timeout outcome) "timed out" "failed")
                           (when (:log child) (str ". The log: " (:log child)))))})))))))

(defn- wait-not-helping! [{:keys [state]}]
  (loop [n 0]
    (when (and (:helping @state) (< n 700))
      (Thread/sleep 10)
      (recur (inc n)))))

(defn- eval-text [prompt-ns {:keys [run? ns-source]} code sentinel-form]
  (let [buffer-ns (when (and (not run?) ns-source) (clj/buffer-ns ns-source))
        prefix (when (and buffer-ns (not= buffer-ns (str prompt-ns)))
                 (str "(in-ns '" buffer-ns ")\n"))]
    (str prefix code "\n" sentinel-form)))

(defn- run-worker!
  [{:keys [state] :as ev} code {:keys [run? on-output on-status-change] :as opts}]
  (try
    (when on-status-change (on-status-change :running))
    (if-let [problem (commands/pre-run-problem code)]
      (finish! ev opts {:status :error :error-text problem})
      (let [_ (when (and run? (= :reset (:mode @state)) (:used? @state))
                (kill-child! ev)
                (connection/say! on-output :system (str cljs-node/reset-message "\n")))
            problem (or (ensure-child! ev on-output)
                        (when-not (live? ev)
                          (or (when (stopping? ev) {:status :interrupted})
                              (connect! ev))))]
        (cond
          problem (do (when (:child @state) (kill-child! ev))
                      (finish! ev opts problem))
          (stopping? ev) (finish! ev opts {:status :interrupted})
          :else
          (do
            (wait-not-helping! ev)
            (let [sentinel-form (next-sentinel! ev)
                  text (eval-text (:prompt-ns @state) opts code sentinel-form)
                  _ (swap! state assoc :used? true)
                  [outcome replies] (exchange! ev text
                                               (fn [r] (and (ret? r) (= sentinel-form (:form r))))
                                               {:on-output on-output})]
              (case outcome
                :done (let [result (cljs-node/fold-replies replies sentinel-form)]
                        (when (:ns result) (swap! state assoc :prompt-ns (:ns result)))
                        (finish! ev opts result))
                (if (stopping? ev)
                  (finish! ev opts {:status :interrupted})
                  (do (kill-child! ev)
                      (finish! ev opts {:status :error :error-text exited-text})))))))))
    (catch Throwable e
      (try (kill-child! ev) (catch Throwable _ nil))
      (finish! ev opts {:status :error :error-text (str "Error: " (ex-message e))}))))

;; --- help ---

(def ^:private plain-symbol #"[^\s()\[\]{}\"';,@^`~\\]+")

(defn- doc-text [replies]
  (str/trim (apply str (keep #(when (= :out (:tag %)) (:val %)) replies))))

(defn- meta-lookup
  "Docs for sym from (cljs.repl/doc sym), bounded by timeout-ms; only when
  the child is live and nothing is running."
  [{:keys [state timings] :as ev} sym timeout-ms]
  (let [[old _] (swap-vals! state (fn [s] (if (or (:running s) (:helping s)) s (assoc s :helping true))))]
    (cond
      (not (live? ev)) (do (when-not (or (:running old) (:helping old)) (swap! state assoc :helping false))
                           (cljd/no-docs sym not-running-doc))
      (or (:running old) (:helping old)) (cljd/no-docs sym busy-doc)
      (not (re-matches plain-symbol (str sym)))
      (do (swap! state assoc :helping false) (cljd/no-docs sym (str "No docs for " sym)))
      :else
      (try
        (let [sentinel-form (next-sentinel! ev)
              [outcome replies] (exchange! ev (str "(cljs.repl/doc " sym ")\n" sentinel-form)
                                           (fn [r] (and (ret? r) (= sentinel-form (:form r))))
                                           {:timeout-ms (or timeout-ms (ev/help-timeout (:help-timeout-ms timings)))})]
          (if (= :done outcome)
            (let [t (doc-text replies)]
              (cljd/no-docs sym (if (str/blank? t) (str "No docs for " sym) t)))
            (do (when (= :timeout outcome) (swap! state assoc :drain sentinel-form))
                (cljd/no-docs sym timeout-doc))))
        (finally (swap! state assoc :helping false))))))

(defrecord CljsNode [selection state history listeners timings hooks]
  ev/Evaluator
  (start! [_] nil)
  (stop! [this]
    (swap! state (fn [s] (cond-> (assoc s :stopped? true)
                           (:running s) (assoc-in [:running :stop-requested?] true))))
    (kill-child! this)
    nil)
  (eval! [this code opts]
    (when-not (:run? opts) (swap! history eval/history-add code))
    (let [[old _] (swap-vals! state (fn [s] (if (:running s) s (assoc s :running {:opts opts}))))]
      (if (:running old)
        busy-warning
        (do (future (run-worker! this code opts))
            nil))))
  (interrupt! [this]
    (let [[old _] (swap-vals! state (fn [s] (cond-> s (:running s) (assoc-in [:running :stop-requested?] true))))]
      (when (:running old)
        (kill-child! this)))
    nil)
  (evaluating? [_] (some? (:running @state)))
  (waiting-for-input? [_] false)
  (send-input! [_ _] nil)
  (send-eof! [_] nil)
  (history [_] history)
  (prompt-ns [_] (:prompt-ns @state))
  (set-mode! [_ mode] (swap! state assoc :mode mode) nil)
  (complete [_ prefix text pos extra-context]
    (try (cljd/candidates (:profile selection) prefix text pos extra-context)
         (catch Throwable _ [])))
  (lookup [this sym _text _pos]
    (try (meta-lookup this sym nil)
         (catch Throwable _ (cljd/no-docs sym timeout-doc))))
  (limitations [_] cljs-node/limitations)

  ev/ServerHelp
  (offline-candidates [_ prefix text pos extra-context]
    (try (cljd/candidates (:profile selection) prefix text pos extra-context)
         (catch Throwable _ [])))

  ev/FullDoc
  (full-doc [this sym _text on-result]
    (future
      (on-result (try (meta-lookup this sym (:full-doc-timeout-ms timings))
                      (catch Throwable _ (cljd/no-docs sym timeout-doc)))))
    nil)

  connection/Attached
  (connected? [this] (live? this))
  (on-connection! [this f] (connection/add-listener! this f)))

(defn make-cljs-node
  "A ClojureScript-on-node evaluator for a jonquil.dialect/select result,
  with no child yet. opts: :timings (merged over default-timings) and the
  hooks tests fake: :launch (fn [{:keys [clojure launcher project-dir
  cancelled?]}] child-or-{:error}), :find-clojure, :ensure-launcher,
  :alive?, :kill."
  ([selection] (make-cljs-node selection {}))
  ([selection {:keys [timings launch find-clojure alive? kill ensure-launcher]}]
   (let [timings (merge default-timings timings)]
     (->CljsNode selection
                 (atom {:running nil :conn nil :child nil :ready? false :sink nil
                        :drain nil :helping false :n 0
                        :prompt-ns 'cljs.user :mode :reset :used? false :stopped? false})
                 (atom (eval/create-history))
                 (atom [])
                 timings
                 {:launch (or launch
                              (fn [o] (cljs-child/start! (assoc o :timeout-ms (:start-timeout-ms timings)))))
                  :find-clojure (or find-clojure cljs-child/find-clojure)
                  :ensure-launcher (or ensure-launcher files/ensure-launcher!)
                  :alive? (or alive? cljs-child/alive?)
                  :kill (or kill cljs-child/kill!)}))))

(def settle-deadline-ms
  ;; estimate: a launch notices a Stop within its poll, then kills the tree;
  ;; the rest is slack (same as jonquil.nrepl.jank).
  5000)

(defn settled?
  "True once no evaluation, and so no launch, is in flight."
  [ev]
  (nil? (:running @(:state ev))))

(defn child-pid [ev] (:pid (:child @(:state ev))))

(defn run-dir [ev] (:run-dir (:child @(:state ev))))

(defmethod ev/make-launched :cljs [selection] (make-cljs-node selection))
