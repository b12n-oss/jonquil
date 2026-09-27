(ns jonquil.nrepl.evaluator
  "The out-of-process evaluator: a Jolt child running nREPL, one socket,
  one cloned session. Run is `load-file` (after the Reset on Run form in
  :reset mode), a REPL entry is `eval`, Stop is the session's `interrupt`,
  and help is `completions` and `lookup` merged with Jonquil's own
  offline lookups (buffer definitions, special forms).

  Stop falls back to killing and restarting the child when the server has
  no interrupt op or doesn't answer one within interrupt-timeout-ms. That
  fallback is jank's only Stop (D3), so it is exercised here too.

  Every callback rule in jonquil.evaluator applies: on-output fires from
  the connection's reader thread, on-status-change and on-complete from
  this evaluation's own worker future."
  (:require [clojure.string :as str]
            [jonquil.docs :as docs]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.child :as child]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.ops :as ops])
  (:import (java.util.concurrent.locks ReentrantLock)))

(def request-timeout-ms
  ;; estimate: describe, clone and the reset form answered in 1-6 ms in the
  ;; plan's live probe; this bounds a wedged child during start and Run.
  10000)

(def help-timeout-ms
  ;; estimate: completions answered in 6.7 ms and lookup in 1.2 ms while a
  ;; loop ran on the same socket (live probe); this bounds how long a
  ;; wedged child can hold a key handler on the GTK thread.
  300)

(def interrupt-timeout-ms
  ;; estimate: interrupt ended a loop 1.2 ms after it was sent (live probe);
  ;; a SIGSTOPped child never answers, and this is how long Stop waits
  ;; before killing and restarting it.
  1000)

(def busy-warning
  ;; jonquil.eval/eval-async's own wording, so the console reads the same.
  "; [Warning: Evaluation already in progress. Press Stop to cancel.]\n")

(def stdin-notice
  "; This runtime has no stdin: read-line returns nil and read fails with EOF.\n")

(defn- runtime [ev] (:runtime @(:state ev)))

(defn- live? [ev]
  (let [{:keys [child conn]} (runtime ev)]
    (and (child/alive? child) (client/open? conn))))

(defn- teardown!
  "The actual work, run only by whoever is holding start-lock: kills the
  child, then closes the socket, if any. Leaves :runtime nil. Kill first:
  the child's end closing gives the reader its EOF, and a socket closed
  under a blocked reader never does (measured)."
  [state]
  (let [{:keys [child conn]} (:runtime (first (swap-vals! state assoc :runtime nil)))]
    (child/kill! child)
    (client/close! conn)
    nil))

(defn- shutdown-runtime!
  "Blocks on start-lock (a ReentrantLock, so start-runtime! can call this
  on itself -- reentrant, same thread -- and restart-for-stop!, already
  off the GTK thread, can wait out a start already in flight), then
  tears down. stop! never calls this directly: see try-shutdown-runtime!,
  its own non-blocking teardown."
  [{:keys [^ReentrantLock start-lock] :as ev}]
  (.lock start-lock)
  (try (teardown! (:state ev))
       (finally (.unlock start-lock))))

(defn- try-shutdown-runtime!
  "stop!'s own teardown: a tryLock, never a wait -- so closing a window
  never blocks the GTK thread on a start that's mid-spawn (start-lock can
  be held for up to ready-deadline-ms, plus term-grace-ms to tear itself
  down). Wins the lock and tears down synchronously when nothing else
  holds it. When it doesn't -- a start is in flight -- returns nil at once
  and does nothing further: start-runtime! already checks :stopped? right
  after it registers :runtime and tears down what it just started (see
  there), so the child still gets killed, just not by this call."
  [{:keys [^ReentrantLock start-lock] :as ev}]
  (when (.tryLock start-lock)
    (try (teardown! (:state ev))
         (finally (.unlock start-lock)))))

(defn- eval-sync!
  "One eval on conn, waiting for done. The printed value, or throws."
  [conn sid code]
  (let [r (client/request-sync conn {:op "eval" :code code :session sid :ns "user"} request-timeout-ms)]
    (when (= ::client/timeout r)
      (throw (ex-info "the runtime didn't answer" {})))
    (let [res (ops/eval-result r false)]
      (if (= :ok (:status res))
        (:printed res)
        (throw (ex-info (or (:error-text res) "evaluation failed") {}))))))

(defn- start-runtime!
  "Launches and connects a child if none is live. nil, or a sentence.
  stop! is terminal for the evaluator: once its :stopped? flag is up, no
  new child may start, even one whose spawn/connect/clone was already
  under way when stop! ran. Holds start-lock for the whole attempt, so
  try-shutdown-runtime! (stop!'s own non-blocking teardown) can't win the
  lock while this is spawning, connecting or cloning -- it just leaves
  the teardown to the check below instead."
  [{:keys [^ReentrantLock start-lock] :as ev}]
  (.lock start-lock)
  (try
    (when-not (live? ev)
      (shutdown-runtime! ev)
      (let [ch (atom nil)]
        (try
          (let [c (reset! ch (child/start! (:project-dir (:selection ev)) (child/middleware-root)))
                conn (client/connect (:port c))
                ops (ops/op-names (client/request-sync conn {:op "describe"} request-timeout-ms))
                sid (:new-session (first (client/request-sync conn {:op "clone"} request-timeout-ms)))
                baseline (read-string (eval-sync! conn sid "(pr-str (mapv ns-name (all-ns)))"))]
            (swap! (:state ev) assoc :runtime {:child c :conn conn :session sid :ops ops :baseline baseline})
            ;; stop! may have run while this was spawning/connecting/cloning:
            ;; it can't have torn this down (try-shutdown-runtime! only wins
            ;; the lock once this releases it), so tear it down here instead.
            (when (:stopped? @(:state ev))
              (shutdown-runtime! ev))
            nil)
          (catch Throwable e
            (child/kill! @ch)
            (str "Couldn't start Jolt over nREPL: " (ex-message e))))))
    (finally (.unlock start-lock))))

(defn- finish!
  "Ends the running evaluation: idle first, then on-complete, as
  jonquil.eval does."
  [ev {:keys [on-status-change on-complete]} result]
  (swap! (:state ev) (fn [s] (cond-> (assoc s :running nil)
                               (:ns result) (assoc :prompt-ns (:ns result)))))
  (try (when on-status-change (on-status-change :idle)) (catch Throwable _ nil))
  (try (when on-complete (on-complete result)) (catch Throwable _ nil)))

(defn- send-main!
  "Sends the Run's load-file or the REPL entry's eval, feeding :out and
  :err to on-output as they arrive. A promise of all its replies."
  [ev code {:keys [run? path on-output]}]
  (let [{:keys [conn session]} (runtime ev)
        acc (atom [])
        done (promise)
        msg (if run?
              {:op "load-file" :file code :file-path (or path "untitled.clj")
               :file-name (or (some-> path (str/split #"/") last) "untitled.clj") :session session}
              {:op "eval" :code code :session session})
        id (client/request! conn msg
                            (fn [m]
                              (swap! acc conj m)
                              (when on-output
                                (when-let [o (:out m)] (on-output :out o))
                                (when-let [e (:err m)] (on-output :err e)))
                              (when (client/done? m) (deliver done @acc))))]
    (swap! (:state ev) assoc-in [:running :id] id)
    done))

(defn- stop-requested? [ev]
  (boolean (get-in @(:state ev) [:running :stop-requested?])))

(defn- run-worker!
  [ev code {:keys [run? on-output on-status-change] :as opts}]
  (try
    (when on-status-change (on-status-change :running))
    (let [had-runtime? (some? (runtime ev))
          was-live? (live? ev)
          problem (start-runtime! ev)]
      (cond
        problem (finish! ev opts {:status :error :error-text problem})
        ;; Stop before the request went out: nothing to interrupt.
        (stop-requested? ev) (finish! ev opts {:status :interrupted})
        :else
        (do
          (when (and on-output had-runtime? (not was-live?))
            (on-output :system "; The Jolt runtime had exited, so a new one was started.\n"))
          (when (and on-output (ops/reads-stdin? code)) (on-output :system stdin-notice))
          (when run?
            (let [{:keys [conn session baseline]} (runtime ev)]
              (eval-sync! conn session (if (= :reset (:mode @(:state ev)))
                                         (ops/reset-code baseline)
                                         "nil"))))
          (if (stop-requested? ev)
            (finish! ev opts {:status :interrupted})
            (let [msgs @(send-main! ev code opts)]
              (finish! ev opts (ops/eval-result msgs (stop-requested? ev))))))))
    (catch Throwable e
      (finish! ev opts (if (stop-requested? ev)
                         {:status :interrupted}
                         {:status :error :error-text (str "Error: " (ex-message e))})))))

(defn- restart-for-stop!
  "Stop's fallback: kill the child (the running eval then completes as
  :interrupted) and start a fresh one, saying so on the console."
  [ev on-output]
  (shutdown-runtime! ev)
  (when on-output
    (on-output :system "; Stop restarted the Jolt runtime, so its definitions are gone.\n"))
  (start-runtime! ev))

(defrecord NRepl [selection state history start-lock]
  ev/Evaluator
  (start! [this] (start-runtime! this))
  (stop! [this]
    ;; Terminal: :stopped? stays true for this evaluator's whole life, so
    ;; start-runtime! never registers a child after this point (see there).
    ;; Never blocks the caller -- the GTK thread, for a real window close --
    ;; on a start already in flight: try-shutdown-runtime! is a tryLock,
    ;; and when it loses, start-runtime! itself tears down what it just
    ;; started once it notices :stopped?.
    (swap! state (fn [s] (cond-> (assoc s :stopped? true)
                           (:running s) (assoc-in [:running :stop-requested?] true))))
    (try-shutdown-runtime! this)
    nil)
  (eval! [this code opts]
    ;; A REPL entry is recorded even when refused, as jonquil.session's
    ;; submit-repl! does. Claiming is a swap, never start-lock: a Run pressed
    ;; while a child is still starting must not block the GTK thread.
    (when-not (:run? opts) (swap! history eval/history-add code))
    (let [[old _] (swap-vals! state (fn [s] (if (:running s) s (assoc s :running {:opts opts}))))]
      (if (:running old)
        busy-warning
        (do (future (run-worker! this code opts))
            nil))))
  (interrupt! [this]
    (when-let [{:keys [id opts]} (:running @state)]
      (swap! state assoc-in [:running :stop-requested?] true)
      ;; No :id yet means the request hasn't gone out: run-worker! sees the
      ;; flag and finishes as :interrupted without sending it.
      (when id
        ;; Only the first interrupt! for this running id sends anything or
        ;; can restart: a CAS on :restart-triggered? per the current
        ;; :running map, so repeated Stop presses don't each trigger their
        ;; own restart.
        (let [[old _] (swap-vals! state assoc-in [:running :restart-triggered?] true)]
          (when-not (get-in old [:running :restart-triggered?])
            (future
              (let [{:keys [conn session ops]} (runtime this)
                    answered? (and (contains? ops "interrupt")
                                   (vector? (client/request-sync conn {:op "interrupt" :session session :interrupt-id id}
                                                                 interrupt-timeout-ms)))
                    ended? (and answered?
                                (loop [n 0]
                                  (cond (not (:running @state)) true
                                        (>= (* n 20) interrupt-timeout-ms) false
                                        :else (do (Thread/sleep 20) (recur (inc n))))))]
                (when-not ended? (restart-for-stop! this (:on-output opts)))))))))
    nil)
  (evaluating? [_] (some? (:running @state)))
  (waiting-for-input? [_] false)
  (send-input! [_ _] nil)
  (send-eof! [_] nil)
  (history [_] history)
  (prompt-ns [_] (:prompt-ns @state))
  (set-mode! [_ mode] (swap! state assoc :mode mode) nil)
  (complete [this prefix text pos extra-context]
    (let [offline (docs/candidates 'user prefix text pos extra-context)]
      (try
        (let [{:keys [child conn session]} (runtime this)
              r (when (and (child/alive? child) (client/open? conn))
                  (client/request-sync conn {:op "completions" :prefix prefix
                                             :ns (str (:prompt-ns @state)) :session session}
                                       help-timeout-ms))]
          (ops/merge-candidates prefix offline (if (vector? r) (ops/candidates r) [])))
        (catch Throwable _ offline))))
  (lookup [this sym text pos]
    (try
      (or (let [{:keys [child conn ops]} (runtime this)
                r (when (and (child/alive? child) (client/open? conn) (contains? ops "lookup"))
                    (client/request-sync conn {:op "lookup" :sym sym :ns (str (:prompt-ns @state))}
                                         help-timeout-ms))]
            (when (vector? r) (ops/doc-info sym r)))
          (docs/symbol-doc 'user sym text pos))
      (catch Throwable _ nil)))
  (limitations [this]
    (cond-> ["No stdin: read-line returns nil and read fails with EOF."
             "Output appears when an evaluation finishes, not while it runs (jolt-lang/jolt#1153)."
             "Jump to definition stays in this buffer: lookup reports no file or line."]
      (and (live? this) (not (contains? (:ops (runtime this)) "interrupt")))
      (conj "Stop restarts the runtime: this server has no interrupt op."))))

(defn make-nrepl
  "A not yet started nREPL evaluator for a jonquil.dialect/select result
  whose :project-dir is set. start-lock is a ReentrantLock (not a plain
  Object): shutdown-runtime!/start-runtime! block on it (reentrant, so
  start-runtime! can call shutdown-runtime! on itself), while stop!'s own
  try-shutdown-runtime! only ever tries it, never waits."
  [selection]
  (->NRepl selection
           (atom {:mode :reset :prompt-ns 'user :running nil :runtime nil :stopped? false})
           (atom (eval/create-history))
           (ReentrantLock.)))

(defmethod ev/make :nrepl [selection] (make-nrepl selection))

(defn child-pid
  "The live child's pid, or nil (the status bar and self-test rows)."
  [ev]
  (:pid (:child (runtime ev))))

(defn child-port
  "The live child's port, or nil."
  [ev]
  (:port (:child (runtime ev))))

(defn settled?
  "True once ev holds no start-lock (no start, and no self-teardown a
  start triggered, is in flight) and names no live child. jonquil.
  runtime-registry's stop-all! polls this after ev/stop!, which -- unlike
  this -- never blocks on a start already in flight (see stop!'s own
  comment)."
  [{:keys [^ReentrantLock start-lock] :as ev}]
  (and (not (.isLocked start-lock))
       (not (child/alive? (:child (runtime ev))))))
