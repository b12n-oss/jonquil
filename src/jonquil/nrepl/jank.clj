(ns jonquil.nrepl.jank
  "The jank evaluator: a window on a jank file owns a jank child process,
  started lazily on the first evaluation, and talks to it over nREPL
  through the shared connection layer (jonquil.nrepl.connection).

  The shape follows jonquil.nrepl.jvm, minus what jank doesn't have: no
  interrupt op (Stop kills the child instead, so earlier definitions are
  gone afterwards), no stdin, no inspector, and no lookup op (it wedges the
  connection, jank issue 998, measured 2026-10-12). Run is
  load-file; a REPL line, Run Selection and Run Form are eval with an
  explicit :ns. jank has no namespace-not-found status, so a selection
  first asks whether its namespace is loaded and says so, sending nothing
  else, when it isn't.

  Everything mutable lives in the record's state atom, the child included.
  The child is killed on Stop, on a reset-mode Run that follows an earlier
  request, when the connection dies, and when the window stops the
  evaluator.

  Help never asks jank while an evaluation runs, because jank serves one
  request at a time and the question would queue behind it. Docs come from
  an eval of the var's metadata, not the lookup op."
  (:require [clojure.java.io :as io]
            [jonquil.clj :as clj]
            [jonquil.cljd :as cljd]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.jank :as jank]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.jank-child :as jank-child]
            [jonquil.nrepl.ops :as ops]))

(def default-timings
  {;; estimate: connection.clj's own describe and clone timeouts, as
   ;; jonquil.nrepl.jvm uses them; jank answers both at once.
   :describe-timeout-ms 5000
   :clone-timeout-ms 10000
   :still-waiting-ms 30000
   ;; estimate: jonquil.nrepl.jvm's GTK-thread help bound.
   :help-timeout-ms 300
   ;; estimate: a popover wait off the GTK thread, shorter than jvm's.
   :full-doc-timeout-ms 5000
   ;; nil: jank-child/start! applies its own default.
   :start-timeout-ms nil})

(def busy-warning
  ;; jonquil.eval/eval-async's own wording, so the console reads the same.
  "; [Warning: Evaluation already in progress. Press Stop to cancel.]\n")

(defn- stopping?
  "True when Stop landed on the running evaluation or the window has
  stopped this evaluator."
  [{:keys [state] :as ev}]
  (or (connection/stop-requested? ev) (boolean (:stopped? @state))))

(defn- finish!
  "Ends the running evaluation: clears :running, then idle, then
  on-complete, each in its own try so a throwing caller can't swallow the
  other. An interrupted result then says why the child is gone."
  [{:keys [state] :as ev} {:keys [on-output on-status-change on-complete]} result]
  (let [stopped? (and (= :interrupted (:status result)) (stopping? ev))]
    (swap! state assoc :running nil)
    (try (when on-status-change (on-status-change :idle)) (catch Throwable _ nil))
    (try (when on-complete (on-complete result)) (catch Throwable _ nil))
    ;; After on-complete: the window prints its "interrupted" line from
    ;; there, and the sentence explaining the restart follows it.
    (when stopped?
      (connection/say! on-output :system (str jank/stopped-message "\n")))))

(defn- kill-child!
  "Drops the connection (pending requests fail as disconnected), clears the
  child from state and kills its process tree. Idempotent. With
  :background? (Stop, on the GTK thread) the kill runs in a future;
  :killing counts kills in flight and settled? waits for them, so quitting
  never cuts one off. Disconnected is said at once either way, so a kill
  ending after the next child connected can't contradict it."
  ([ev] (kill-child! ev {}))
  ([{:keys [state hooks] :as ev} {:keys [background?]}]
   (let [[old _] (swap-vals! state (fn [s]
                                     (cond-> (assoc s :child nil :used? false)
                                       (and background? (:child s)) (update :killing inc))))
         child (:child old)]
     (connection/detach! ev)
     (when child
       (connection/notify! ev false)
       (if background?
         (future (try ((:kill hooks) child)
                      (catch Throwable _ nil)
                      (finally (swap! state update :killing dec))))
         ((:kill hooks) child)))
     nil)))

(defn- ensure-child!
  "nil once a live child is stored in state, else the result to finish the
  evaluation with. A stored child that died is killed first. A Stop that
  lands during the launch, or after it returned, kills what the launch
  produced: the child is stored before the check, so either this check or
  the Stop's own kill-child! sees it."
  [{:keys [state hooks selection] :as ev} on-output run-path run-ns]
  (let [{:keys [alive?]} hooks
        child (:child @state)]
    (if (and child (alive? child))
      nil
      (do
        (when child (kill-child! ev))
        (if-let [binary ((:find-binary hooks))]
          (do
            (connection/say! on-output :system (str (jank/starting-message binary) "\n"))
            (let [started ((:launch hooks)
                           {:binary binary
                            :module-path (jank-child/module-path
                                          {:project-dir (:project-dir selection)
                                           :file-dir (some-> run-path io/file .getParent)
                                           :file-path run-path
                                           :ns-name run-ns})
                            :cancelled? (fn [] (stopping? ev))})]
              (cond
                (= :cancelled (:error started)) {:status :interrupted}
                (:error started) {:status :notice :text (jank/start-failed-message (:error started))}
                :else (do (swap! state assoc :child started)
                          (when (stopping? ev) (kill-child! ev))
                          nil))))
          {:status :notice :text jank/no-binary-message})))))

(defn- connect-policy [child ev]
  (assoc jank/connect-policy
         :port-file (fn [_] (io/file (:run-dir child) ".nrepl-port"))
         :stop-requested? (fn [_] (stopping? ev))))

(defn- settle
  "The result for a request's replies. A lost connection without a Stop
  means the child died: kill it so the run dir goes and the next
  evaluation starts a fresh one."
  [ev msgs]
  (let [stop? (stopping? ev)
        result (jank/eval-result msgs stop?)]
    (when (and (not stop?) ((ops/statuses msgs) ops/disconnected))
      (kill-child! ev))
    result))

(defn- request-used!
  "Sends msg on the connection and marks the child as having run something."
  [{:keys [state] :as ev} on-output msg]
  (swap! state assoc :used? true)
  (connection/request! ev msg {:on-output on-output :still-waiting nil}))

(defn- reset-used-child!
  "Reset: a child that already ran something is replaced, so the Run starts
  from a clean jank."
  [{:keys [state] :as ev} {:keys [run? on-output]}]
  ;; Reset: a child that already ran something is replaced, so the
  ;; Run starts from a clean jank.
  (when (and run? (= :reset (:mode @state)) (:used? @state))
    (kill-child! ev)
    (connection/say! on-output :system (str jank/reset-message "\n"))))

(defn- start-and-connect!
  "nil once the child runs and the connection is open, else the result to
  finish the evaluation with."
  [{:keys [state] :as ev} code {:keys [run? path on-output]}]
  (or (ensure-child! ev on-output (when run? path) (when run? (clj/buffer-ns code)))
      (if (stopping? ev)
        {:status :interrupted}
        (connection/connect! ev on-output (connect-policy (:child @state) ev)))))

(defn- run-file!
  "Run: loads the buffer as a file, and a successful load moves the prompt
  to the namespace it ends in."
  [{:keys [state] :as ev} code {:keys [path on-output] :as opts}]
  (let [f (when path (io/file path))
        msgs (request-used! ev on-output
                            {:op "load-file" :file code
                             :file-path (if f (.getAbsolutePath f) "untitled.jank")
                             :file-name (if f (.getName f) "untitled.jank")})
        result (settle ev msgs)]
    (when (= :ok (:status result))
      (swap! state assoc :prompt-ns (clj/prompt-after-run code result (:prompt-ns @state))))
    (finish! ev opts result)))

(defn- finish-unloaded!
  "A selection's namespace isn't loaded: a lost connection settles as the
  dead child, anything else is the not-loaded notice."
  [ev {:keys [buffer-ns asked]} opts]
  (if ((ops/statuses asked) ops/disconnected)
    (finish! ev opts (settle ev asked))
    (finish! ev opts {:status :notice :text (jank/not-loaded-message buffer-ns)})))

(defn- eval-in-repl!
  "A REPL line or selection: asks first whether the selection's namespace
  is loaded, then evaluates in it (the prompt's namespace otherwise)."
  [{:keys [state] :as ev} code {:keys [ns-source on-output] :as opts}]
  (let [buffer-ns (when ns-source (clj/buffer-ns ns-source))
        ns (or buffer-ns (str (:prompt-ns @state)))
        asked (when buffer-ns
                (request-used! ev on-output {:op "eval" :code (jank/find-ns-code buffer-ns) :ns "user"}))]
    (if (and asked (not (jank/loaded? asked)))
      (finish-unloaded! ev {:buffer-ns buffer-ns :asked asked} opts)
      (let [result (settle ev (request-used! ev on-output {:op "eval" :code code :ns ns}))]
        (when (and (nil? ns-source) (= :ok (:status result)) (:ns result))
          (swap! state assoc :prompt-ns (:ns result)))
        (finish! ev opts result)))))

(defn- run-worker!
  [{:keys [state] :as ev} code {:keys [run? on-status-change] :as opts}]
  (try
    (when on-status-change (on-status-change :running))
    (reset-used-child! ev opts)
    (let [problem (start-and-connect! ev code opts)]
      (cond
        ;; A notice after a launch (refused, not a server, bad port file):
        ;; the child is no use, and keeping it would fail the same way on
        ;; every later evaluation.
        problem (do (when (:child @state) (kill-child! ev))
                    (finish! ev opts problem))
        (stopping? ev) (finish! ev opts {:status :interrupted})
        run? (run-file! ev code opts)
        :else (eval-in-repl! ev code opts)))
    (catch Throwable e
      (try (kill-child! ev) (catch Throwable _ nil))
      (finish! ev opts {:status :error :error-text (str "Error: " (ex-message e))}))))

;; --- help ---

(def not-running-doc "No docs: jank isn't running yet. Run something first.")
(def timeout-doc "No docs: jank didn't answer in time.")

(defn- help-state
  "The state to ask jank with, or nil when asking would hang or fail: the
  connection isn't open, or an evaluation is running."
  [ev]
  (let [s @(:state ev)]
    (when (and (connection/live? ev) (not (:running s))) s)))

(defn- meta-lookup
  "Docs for sym from an eval of its metadata, bounded by timeout-ms. The
  namespace comes from the text, and jank falls back to user for one it
  hasn't loaded (measured 2026-10-12). Never the lookup op."
  [ev sym text timeout-ms]
  (let [{:keys [state timings]} ev
        {:keys [running conn session prompt-ns]} @state]
    (cond
      (not (connection/live? ev)) (cljd/no-docs sym not-running-doc)
      running (cljd/no-docs sym jank/busy-doc)
      :else
      (let [r (client/request-sync conn {:op "eval" :code (jank/meta-doc-code sym)
                                         :ns (clj/help-ns text prompt-ns) :session session}
                                   (or timeout-ms (ev/help-timeout (:help-timeout-ms timings))))]
        (if (vector? r)
          (jank/meta-doc sym r)
          (cljd/no-docs sym timeout-doc))))))

(defrecord Jank [selection state history listeners timings hooks]
  ev/Evaluator
  ;; The child starts on the first evaluation: nothing to start here.
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
    ;; Idle Stop does nothing. A running one marks the stop and kills the
    ;; child: the in-flight request fails as disconnected, which the worker
    ;; reads as :interrupted. Runs on the GTK thread: no socket writes, only
    ;; a close, and the process kill goes to the background.
    (let [[old _] (swap-vals! state (fn [s] (cond-> s (:running s) (assoc-in [:running :stop-requested?] true))))]
      (when (:running old)
        (kill-child! this {:background? true})))
    nil)
  (evaluating? [_] (some? (:running @state)))
  (waiting-for-input? [_] false)
  (send-input! [_ _] nil)
  (send-eof! [_] nil)
  (history [_] history)
  (prompt-ns [_] (:prompt-ns @state))
  (set-mode! [_ mode] (swap! state assoc :mode mode) nil)
  ;; Offline candidates, plus jank's own when the connection is live and
  ;; nothing is running, bounded by :help-timeout-ms since this runs on the
  ;; GTK thread. A timeout or a throw falls back to offline alone.
  (complete [this prefix text pos extra-context]
    (let [offline (try (cljd/candidates (:profile selection) prefix text pos extra-context)
                       (catch Throwable _ []))]
      (try
        (if-let [{:keys [conn session prompt-ns]} (help-state this)]
          (let [r (client/request-sync conn {:op "completions" :prefix prefix
                                             :ns (clj/help-ns text prompt-ns)
                                             :session session}
                                       (ev/help-timeout (:help-timeout-ms timings)))]
            (if (vector? r)
              (ops/merge-candidates prefix offline (ops/candidates r))
              offline))
          offline)
        (catch Throwable _ offline))))
  (lookup [this sym text _pos]
    (try (meta-lookup this sym text nil)
         (catch Throwable _ (cljd/no-docs sym timeout-doc))))
  (limitations [_] jank/limitations)

  ev/ServerHelp
  (offline-candidates [_ prefix text pos extra-context]
    (try (cljd/candidates (:profile selection) prefix text pos extra-context)
         (catch Throwable _ [])))

  ev/FullDoc
  ;; Off the GTK thread, with the longer bound; on-result is called once.
  (full-doc [this sym text on-result]
    (future
      (on-result (try (meta-lookup this sym text (:full-doc-timeout-ms timings))
                      (catch Throwable _ (cljd/no-docs sym timeout-doc)))))
    nil)

  connection/Attached
  (connected? [this] (connection/live? this))
  (on-connection! [this f] (connection/add-listener! this f)))

(defn make-jank
  "A jank evaluator for a jonquil.dialect/select result, with no child yet.
  opts: :timings (merged over default-timings) and the hooks tests fake:
  :launch (fn [{:keys [binary module-path cancelled?]}] child-or-{:error}),
  :find-binary, :alive?, :kill."
  ([selection] (make-jank selection {}))
  ([selection {:keys [timings launch find-binary alive? kill]}]
   (let [timings (merge default-timings timings)]
     (->Jank selection
             (atom {:running nil :conn nil :connecting nil :session nil :ops #{}
                    :session-ns nil :prompt-ns 'user :mode :reset
                    :child nil :used? false :stopped? false :killing 0})
             (atom (eval/create-history))
             (atom [])
             timings
             {:launch (or launch
                          (fn [o] (jank-child/start! (assoc o :timeout-ms (:start-timeout-ms timings)))))
              :find-binary (or find-binary jank-child/find-binary)
              :alive? (or alive? jank-child/alive?)
              :kill (or kill jank-child/kill!)}))))

(def settle-deadline-ms
  ;; estimate: a launch notices a Stop within its 50 ms poll, then kills the
  ;; tree (jank-child/kill-wait-ms, 1 s at most); the rest is slack.
  5000)

(defn settled?
  "True once no evaluation, and so no launch, is in flight. A Stop doesn't
  wait for a launch that is still polling for the port file (it only marks
  the evaluator stopped); the launch then kills what it spawned and
  deletes its run dir itself. jonquil.runtime-registry waits on this
  before the process exits."
  [ev]
  (let [s @(:state ev)]
    (and (nil? (:running s)) (zero? (:killing s)))))

(defn child-pid
  "The pid of ev's current child, or nil."
  [ev]
  (:pid (:child @(:state ev))))

(defn run-dir
  "The private run directory of ev's current child, or nil."
  [ev]
  (:run-dir (:child @(:state ev))))

(defmethod ev/make-launched :jank [selection] (make-jank selection))
