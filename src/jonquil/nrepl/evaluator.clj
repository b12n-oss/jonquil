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
            [jonquil.clj :as clj]
            [jonquil.docs :as docs]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.inspect :as inspect]
            [jonquil.nrepl.child :as child]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.ops :as ops]
            [jonquil.session :as session])
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

(def inspect-not-running
  "Nothing to inspect yet: Jolt isn't running. Run something first.")

(def inspect-lost
  "The connection to Jolt was lost.")

(def stdin-notice
  ;; Only when the live server lacks the stdin op: a plain server, or a
  ;; child that couldn't load Jonquil's middleware.
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

(defn- ns-snapshot
  "[namespace-names loaded-libs] in the child, or nil when asking failed:
  the evaluation's own result never depends on this bookkeeping, so a
  throw here only leaves that evaluation's namespaces unowned (spike: about
  1 ms per call)."
  [conn sid]
    ;; No :ns, unlike eval-sync!: an eval with :ns "user" would leave the
  ;; session in user and the next REPL entry would lose the Run's namespace.
  ;; The data comes back on :out; the value is the old *1 (see
  ;; ops/ns-snapshot-code), so the server's own *1/*2/*3 shift puts the
  ;; user's history back where it was (final review finding 1). The same
  ;; eval refers the REPL helpers into the session's namespace.
  (try
    (let [r (client/request-sync conn {:op "eval" :code ops/ns-snapshot-code :session sid} request-timeout-ms)
          res (when (vector? r) (ops/eval-result r false))]
      (when (= :ok (:status res))
        (read-string (apply str (keep :out r)))))
    (catch Throwable _ nil)))

(defn- spawn-runtime!
  "Starts a child with source's middleware, connects and clones a session.
  The :runtime map, or throws after killing whatever child it started."
  [ev source]
  (let [ch (atom nil)]
    (try
      (let [c (reset! ch (child/start! (:project-dir (:selection ev)) source))
            conn (client/connect (:port c))
            ops (ops/op-names (client/request-sync conn {:op "describe"} request-timeout-ms))
            sid (:new-session (first (client/request-sync conn {:op "clone"} request-timeout-ms)))]
        {:child c :conn conn :session sid :ops ops})
      (catch Throwable e
        (child/kill! @ch)
        (throw e)))))

(defn fetch-error-line
  "The line where jolt says a git dependency couldn't be resolved (\"git
  dep: could not list URL (git ls-remote exited 128 ...)\", measured
  2026-10-18 for an unreachable URL and a refused proxy), from the log a
  failed child start left (child/start!'s ex-data :log), or nil when the
  start failed some other way."
  [e]
  (when-let [log (:log (ex-data e))]
    (try (some (fn [l] (when (str/includes? l "git dep:")
                         (str/trim (str/replace l #"^Unhandled exception:\s*" ""))))
               (str/split-lines (slurp log)))
         (catch Throwable _ nil))))

(defn- log-error-line
  "jolt's own error line from a failed child start's log (its \"Unhandled
  exception: ...\" line, prefix dropped), else the exception's message."
  [e]
  (or (when-let [log (:log (ex-data e))]
        (try (some (fn [l] (when (str/starts-with? l "Unhandled exception:")
                             (str/trim (subs l (count "Unhandled exception:")))))
                   (str/split-lines (slurp log)))
             (catch Throwable _ nil)))
      (ex-message e)
      (str (class e))))

(defn- exited?
  "True when child/start! threw because the child exited before listening."
  [e]
  (boolean (some-> (ex-message e) (str/starts-with? "jolt nrepl-server exited"))))

(defn- attempt-source
  "The child/start! source for the next attempt: child/middleware-source,
  or none once a fetch has failed, plus :jonquil, child/jonquil-root's
  folder (Jonquil's own middleware: the inspector, and stdin with the
  nREPL source), unless loading or writing it has failed. [source
  jonquil-error-or-nil]: a write failure is recorded, not fatal."
  [ev]
  (let [{:keys [fetch-error jonquil-error]} @(:state ev)
        source (when-not fetch-error (child/middleware-source))]
    (if jonquil-error
      [source nil]
      (let [[root err] (try [(child/jonquil-root) nil]
                            (catch Throwable e
                              [nil (str "couldn't write Jonquil's nREPL middleware files: "
                                        (or (ex-message e) (str (class e))))]))]
        (when err (swap! (:state ev) assoc :jonquil-error err))
        [(cond-> source root (assoc :jonquil root)) err]))))

(defn- spawn-with-fallback!
  "spawn-runtime! with attempt-source, falling back step by step. Each
  failure the evaluator can route around is remembered in its state for
  the evaluator's life, so a later restart (a plain server's Stop restarts
  it) skips straight past it; a new window tries again. Each lands on the
  :runtime map for limitations to name.

  - A git source whose fetch fails (offline on the first start, no git, a
    refused proxy: jolt exits saying \"git dep: ...\") is retried with no
    nREPL middleware, a plain server: :fetch-error, jolt's line.
  - A child that loaded Jonquil's middleware and exited before it listened
    is retried without it (jolt requires every :nrepl/middleware namespace
    before it binds, so middleware that won't load would otherwise stop
    Jolt starting at all): :jonquil-error, jolt's line. A timeout isn't
    retried, since a load failure exits rather than hangs.
  - Anything else is thrown as before: a checkout's own failure (the
    user's to see), a failure with nothing left to drop, or any failure
    after stop! ran."
  [ev]
  ;; At most three attempts: each retry first records one more failure to
  ;; route around (the fetch, then Jonquil's middleware), so a third failure
  ;; has nothing left to drop. The cap keeps a mistake in that bookkeeping
  ;; from spinning forever.
  (loop [attempts 1]
    (let [[source _] (attempt-source ev)
          result (try {:rt (spawn-runtime! ev source)}
                      (catch Throwable e {:e e}))]
      (if-let [rt (:rt result)]
        (merge rt (select-keys @(:state ev) [:fetch-error :jonquil-error]))
        (let [e (:e result)
              fetch-line (when (:git source) (fetch-error-line e))]
          (cond
            (:stopped? @(:state ev)) (throw e)
            (>= attempts 3) (throw e)
            fetch-line (do (swap! (:state ev) assoc :fetch-error fetch-line) (recur (inc attempts)))
            (and (:jonquil source) (exited? e)) (do (swap! (:state ev) assoc :jonquil-error
                                                           (str "the Jolt child couldn't load Jonquil's nREPL middleware: " (log-error-line e)))
                                                    (recur (inc attempts)))
            :else (throw e)))))))

(defn- start-runtime!
  "Launches and connects a child if none is live. nil, or a sentence.
  stop! is terminal for the evaluator: once its :stopped? flag is up, no
  new child may start, even one whose spawn/connect/clone was already
  under way when stop! ran. Holds start-lock for the whole attempt, so
  try-shutdown-runtime! (stop!'s own non-blocking teardown) can't win the
  lock while this is spawning, connecting or cloning -- it just leaves
  the teardown to the check below instead.

  I2 (phase-close review): checks :stopped? first, before touching
  live?/shutdown-runtime! or spawning anything -- a restart-for-stop!
  already off the GTK thread, a queued run-worker!, or start-and-report!'s
  own future can each still reach this after stop! ran, and without this
  check would spawn a child only to tear it down again right after."
  [{:keys [^ReentrantLock start-lock] :as ev}]
  (.lock start-lock)
  (try
    (or (when (:stopped? @(:state ev)) "This runtime was stopped.")
        (when-not (live? ev)
          (shutdown-runtime! ev)
          (try
            (let [rt (spawn-with-fallback! ev)]
              ;; A new child has none of the old one's namespaces, so the
              ;; owner map starts empty with it (tabs plan, ruling 9).
              (swap! (:state ev) assoc :runtime rt :created {})
              ;; stop! may have run while this was spawning/connecting/cloning:
              ;; it can't have torn this down (try-shutdown-runtime! only wins
              ;; the lock once this releases it), so tear it down here instead.
              (when (:stopped? @(:state ev))
                (shutdown-runtime! ev))
              nil)
            (catch Throwable e
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

(defn- call-wait-input!
  "Calls the running evaluation's on-wait-input with waiting?; never throws
  (it runs on the client's reader thread)."
  [running waiting?]
  (when-let [f (:on-wait-input (:opts running))]
    (try (f waiting?) (catch Throwable _ nil))))

(defn- stdin-watch!
  "For each reply to the main eval: need-input (jonquil.nrepl.stdin-
  middleware asking for a line) marks the evaluation waiting and says so;
  a reply that ends it (done, interrupted) while still marked waiting
  clears it. Output doesn't: a future printing while the read waits would
  otherwise take the console out of input mode with the read still
  blocked (review). send-stdin! clears it on a send, and the flag lives on
  :running, so finish! clears it too."
  [ev m]
  (let [state (:state ev)
        ends? (some #{"done" "interrupted"} (:status m))]
    (cond
      (clj/need-input? m)
      (let [[old _] (swap-vals! state (fn [s] (cond-> s (:running s) (assoc-in [:running :waiting?] true))))]
        (when (and (:running old) (not (get-in old [:running :waiting?])))
          (call-wait-input! (:running old) true)))
      ends?
      (let [[old _] (swap-vals! state (fn [s] (cond-> s (get-in s [:running :waiting?]) (assoc-in [:running :waiting?] false))))]
        (when (get-in old [:running :waiting?])
          (call-wait-input! (:running old) false))))))

(defn- send-stdin!
  "Answers a waiting read with text (\"\" is end of input), clearing the
  flag at once so waiting-for-input? follows the press; the socket write
  goes out on a future, as interrupt!'s does. Nothing when no read waits."
  [ev text]
  (let [[old _] (swap-vals! (:state ev) (fn [s] (cond-> s (get-in s [:running :waiting?]) (assoc-in [:running :waiting?] false))))
        running (:running old)
        {:keys [conn session]} (:runtime old)]
    (when (and (:waiting? running) (client/open? conn))
      (future
        (try (client/request! conn {:op "stdin" :stdin text :session session} (fn [_] nil))
             (catch Throwable _ nil)))
      (call-wait-input! running false))))

(defn- send-main!
  "Sends the Run's load-file or the REPL entry's eval, feeding :out and
  :err to on-output as they arrive. A promise of all its replies.

  I4 (phase-close review): a Stop pressed after run-worker!'s last
  stop-requested? check but before this records the request's :id used to
  have nothing to act on and was never looked at again. Re-checks the flag
  right after recording the id and, if it's already set, goes through
  interrupt!'s id path once. That's safe even if a real Stop lands at the
  same moment: interrupt!'s own CAS (see I1) only lets one caller trigger
  the restart future per id."
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
                              (stdin-watch! ev m)
                              (when on-output
                                (when-let [o (:out m)] (on-output :out o))
                                (when-let [e (:err m)] (on-output :err e)))
                              (when (client/done? m) (deliver done @acc))))]
    (swap! (:state ev) assoc-in [:running :id] id)
    (when (get-in @(:state ev) [:running :stop-requested?])
      (ev/interrupt! ev))
    done))

(defn- stop-requested? [ev]
  (boolean (get-in @(:state ev) [:running :stop-requested?])))

(def auto-require-retry
  "When an out-of-process :auto-require list that failed runs again."
  "It runs again when the Jolt runtime restarts.")

(defn- auto-require!
  "Once per child, before its first Run or REPL entry, when the selection
  carries .jonquil.edn's :auto-require: says so, then requires the list
  with one eval on the session, streaming its output like any evaluation.
  The flag lives on the :runtime map, which a new child replaces, so a
  restarted child requires the list again (JVM Clojure's once per
  connection). A failure (not a Stop) says so after the child's own
  error."
  [{:keys [selection state] :as ev} on-output]
  (let [nss (seq (:auto-require selection))]
    (when (and nss (not (:auto-required? (runtime ev))))
      (swap! state assoc-in [:runtime :auto-required?] true)
      (when on-output (on-output :system (str (clj/auto-require-message nss) "\n")))
      (let [done (send-main! ev (clj/auto-require-code nss) {:run? false :on-output on-output})
            req-id (get-in @state [:running :id])
            msgs @done]
        ;; The require is over, so a Stop from here on must find no id and
        ;; skip the user's request, as before (review finding 1).
        (swap! state update :running (fn [r] (if (= req-id (:id r)) (dissoc r :id) r)))
        (cond
          ;; Stopped: not loaded, so the next evaluation tries again
          ;; (finding 2).
          (stop-requested? ev) (swap! state assoc-in [:runtime :auto-required?] false)
          (and (not= :ok (:status (ops/eval-result msgs false))) on-output)
          (on-output :system (str (clj/auto-require-failed auto-require-retry) "\n")))))))

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
          (when (and on-output (ops/reads-stdin? code) (not (contains? (:ops (runtime ev)) "stdin")))
            (on-output :system stdin-notice))
          (auto-require! ev on-output)
          (let [{:keys [conn session]} (runtime ev)
                owner (:owner opts (if run? :run :repl))]
            (when (and run? (= :reset (:mode @(:state ev))))
              (let [created (:created @(:state ev))]
                (eval-sync! conn session (ops/reset-code (concat (get created owner) (get created :repl))))
                (swap! (:state ev) update :created dissoc owner :repl)))
            (if (stop-requested? ev)
              (finish! ev opts {:status :interrupted})
              (let [before (ns-snapshot conn session)
                    msgs @(send-main! ev code opts)
                    after (ns-snapshot conn session)]
                (when (and before after)
                  (swap! (:state ev) update-in [:created owner] (fnil into #{})
                         (session/namespaces-created (first before) (first after) (second after))))
                (finish! ev opts (ops/eval-result msgs (stop-requested? ev)))))))))
    (catch Throwable e
      (finish! ev opts (if (stop-requested? ev)
                         {:status :interrupted}
                         {:status :error :error-text (str "Error: " (ex-message e))})))))

(defn- restart-for-stop!
  "Stop's fallback: kill the child (the running eval then completes as
  :interrupted) and start a fresh one, saying so on the console.

  I3 (phase-close review): only claims success once start-runtime! has
  actually returned nil. A failed restart says so instead, and the next
  evaluation still starts the runtime lazily, as it does today."
  [ev on-output]
  (shutdown-runtime! ev)
  (if-let [problem (start-runtime! ev)]
    (when on-output
      (on-output :error (str "; Stop couldn't restart the Jolt runtime: " problem "\n")))
    (when on-output
      (on-output :system "; Stop restarted the Jolt runtime, so its definitions are gone.\n"))))

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
    ;; I1 (phase-close review): both updates below used to be their own
    ;; swap!/swap-vals! against a separately-read :running. A finish!
    ;; landing between the read and either write recreated {:running
    ;; {...}} out of nil, and eval! then refused every later Run as busy
    ;; forever. One swap now, keyed by identity of the exact :running map
    ;; read here: if finish! (or a new eval!) has already cleared or
    ;; replaced it by the time this runs, the swap is a no-op instead of
    ;; rebuilding it. The same swap does the once-per-id restart CAS, so
    ;; repeated Stop presses for one id still trigger only one future.
    (when-let [{:keys [id opts] :as running} (:running @state)]
      (let [[old _] (swap-vals! state
                                (fn [s]
                                  (if (identical? running (:running s))
                                    (cond-> (assoc-in s [:running :stop-requested?] true)
                                      id (assoc-in [:running :restart-triggered?] true))
                                    s)))]
        ;; No :id yet means the request hasn't gone out: run-worker! sees
        ;; the flag and finishes as :interrupted without sending it, and
        ;; send-main! re-checks it once the id lands (I4).
        (when (and id (identical? running (:running old)) (not (:restart-triggered? running)))
          (future
            (try
              (let [{:keys [conn session ops]} (runtime this)
                    answered? (and (contains? ops "interrupt")
                                   (vector? (client/request-sync conn {:op "interrupt" :session session :interrupt-id id}
                                                                 interrupt-timeout-ms)))
                    ended? (and answered?
                                (loop [n 0]
                                  (cond (not (:running @state)) true
                                        (>= (* n 20) interrupt-timeout-ms) false
                                        :else (do (Thread/sleep 20) (recur (inc n))))))]
                (when-not ended? (restart-for-stop! this (:on-output opts))))
              ;; M5 (phase-close review): a throw here used to be stored in
              ;; the future and lost. Report it the same way a failed
              ;; restart is, and never let it escape.
              (catch Throwable e
                (when-let [on-output (:on-output opts)]
                  (try (on-output :error (str "; Stop failed: " (ex-message e) "\n"))
                       (catch Throwable _ nil)))))))))
    nil)
  (evaluating? [_] (some? (:running @state)))
  (waiting-for-input? [_] (boolean (get-in @state [:running :waiting?])))
  (send-input! [this text] (send-stdin! this (clj/stdin-line (str text))) nil)
  (send-eof! [this] (send-stdin! this "") nil)
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
                                       (ev/help-timeout help-timeout-ms)))]
          (ops/merge-candidates prefix offline (if (vector? r) (ops/candidates r) [])))
        (catch Throwable _ offline))))
  (lookup [this sym text pos]
    (try
      ;; jonquil-info (Jonquil's own middleware) answers the var's file and
      ;; line as well, for jump to definition; jolt-lang/nrepl's lookup
      ;; has neither. Both replies are ops/doc-info's.
      (or (let [{:keys [child conn ops]} (runtime this)
                op (cond (contains? ops "jonquil-info") "jonquil-info"
                         (contains? ops "lookup") "lookup")
                r (when (and (child/alive? child) (client/open? conn) op)
                    (client/request-sync conn {:op op :sym sym :ns (str (:prompt-ns @state))}
                                         (ev/help-timeout help-timeout-ms)))]
            (when (vector? r) (ops/doc-info sym r)))
          (docs/symbol-doc 'user sym text pos))
      (catch Throwable _ nil)))
  (limitations [this]
    (let [rt (runtime this)]
      (cond-> []
        (and (live? this) (not (contains? (:ops rt) "jonquil-info")))
        (conj "Jump to definition stays in this buffer: lookup reports no file or line.")
        (and (live? this) (not (contains? (:ops rt) "stdin")))
        (conj "No stdin: read-line returns nil and read fails with EOF. Put {:runtime :in-process} in .jonquil.edn to run this project in process instead.")
        (and (live? this) (not (contains? (:ops rt) "inspect-start")))
        (conj (str "No inspector" (when-let [why (:jonquil-error rt)] (str ": " why))
                   ". {:runtime :in-process} has one."))
        (and (live? this) (:fetch-error rt))
        (conj (str "Jolt's nREPL middleware (jolt-lang/nrepl " (:git/tag child/nrepl-git-coord)
                   ") couldn't be fetched, so this is a plain nREPL server; a new window tries again. Jolt said: "
                   (:fetch-error rt)))
        (and (live? this) (not (contains? (:ops rt) "interrupt")))
        (conj "Stop restarts the runtime: this server has no interrupt op."))))
  ev/ServerHelp
  (offline-candidates [_ prefix text pos extra-context]
    (try (docs/candidates 'user prefix text pos extra-context)
         (catch Throwable _ [])))

  ev/Owners
  (release-owner! [_ owner] (session/release-owner-in! state owner) nil)
  ev/Inspector
  ;; Backed by jonquil.nrepl.inspect-middleware, which child/start! loads
  ;; into every child it can: the JVM inspector's ops and keys, so the
  ;; request mapping (inspect/request) and the view reading are shared with
  ;; jonquil.nrepl.jvm. Middleware ops, not evals, so the user's *1 *2 *3
  ;; stay put. Off the caller's thread, on-view called exactly once.
  (inspect! [this op args on-view]
    (future
      (try
        (let [{:keys [conn session ops] :as rt} (runtime this)
              result
              (cond
                (nil? rt) {:error inspect-not-running}
                ;; I2's shape, as jvm.clj answers it: a runtime that was
                ;; live and dropped (child gone or socket closed) is lost,
                ;; which disables the panel; never-started is not-running.
                (not (live? this)) {:error inspect-lost :lost? true}
                (not (contains? ops "inspect-start")) {:error inspect/unavailable-message}
                :else
                (let [req (cond-> (assoc (inspect/request op args) :session session)
                            (= op :def) (assoc :ns (str (:prompt-ns @state))))
                      r (client/request-sync conn req request-timeout-ms)]
                  (cond
                    (not (vector? r)) {:error inspect/timeout-message}
                    ((ops/statuses r) ops/disconnected) {:error inspect-lost :lost? true}
                    :else (inspect/parse-view (some :view r)))))]
          (try (on-view result) (catch Throwable _)))
        (catch Throwable e
          (try (on-view {:error (or (ex-message e) (str (class e)))})
               (catch Throwable _)))))
    nil)
  ;; true before any runtime (inspect! then says it isn't running yet),
  ;; and after that when the child advertised the inspector's ops (a lost
  ;; one still did: inspect! then answers lost, which disables the panel).
  (inspectable? [this]
    (let [rt (runtime this)]
      (or (nil? rt) (contains? (:ops rt) "inspect-start")))))

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
