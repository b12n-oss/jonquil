(ns jonquil.nrepl.attach
  "The ClojureDart attach evaluator: Jonquil connects to the nREPL server a
  running app already started (the ClojureDart nREPL fork's, which writes its port
  to NREPL.lock in the app directory) and never starts, stops or signals
  that app.

  Run is load-file with the buffer's text; a REPL entry, Run Selection and
  Run Form are eval. The server ignores a request's :ns (measured), so Run
  Selection and Run Form pass the buffer's text as :ns-source, and when the
  session is in another namespace this first evaluates the buffer's ns form
  (measured at 263 ms). The session's namespace is tracked from every
  reply's :ns.

  A fresh session's first request is Jonquil's own probe, (+ 1 2), sent
  again once if the app answers nil; user code is sent exactly once, and a
  nil value from it is a normal result.

  There's no interrupt and no close op, ever: the fork's connect skill says
  a client-side interrupt wedges the app's watcher. Stop detaches (closes
  this socket) instead, and the next evaluation reconnects with a fresh
  clone. NREPL.lock is only ever read.

  Every callback rule in jonquil.evaluator applies: on-output fires from the
  connection's reader thread and this evaluation's worker future,
  on-status-change and on-complete from the worker.

  The connection lifecycle itself (connect!, detach!, request!, and the
  Attached protocol) lives in jonquil.nrepl.connection, shared with a
  later JVM attach evaluator: this namespace supplies the :cljd policy and
  the pieces that stay dialect-specific (the wake-up probe, the run
  worker, and the record)."
  (:require [clojure.java.io :as io]
            [jonquil.cljd :as cljd]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.connection :as connection]))

(def default-timings
  {;; estimate: describe answered in 3 ms in the C0 spike; this bounds a
   ;; listener on the port that accepts but never speaks nREPL.
   :describe-timeout-ms 5000
   ;; estimate: clone answered at once in every spike probe (not timed on
   ;; its own); it's session bookkeeping, not an eval, so it doesn't queue
   ;; behind abandoned work.
   :clone-timeout-ms 10000
   ;; The fork's own eval-timeout-ms (cljd.nrepl, 30000): after that long
   ;; the app gives up on an eval itself. Jonquil keeps waiting (no
   ;; client-side timeout) and says so once.
   :still-waiting-ms 30000
   ;; estimate, the nREPL evaluator's help-timeout-ms basis: quick doc asks
   ;; lookup on the GTK thread, so a busy or wedged app can hold a key for
   ;; at most this long.
   :help-timeout-ms 300})

(def busy-warning
  ;; jonquil.eval/eval-async's own wording, so the console reads the same.
  "; [Warning: Evaluation already in progress. Press Stop to cancel.]\n")

(defn connected?
  "True while this evaluator holds a live connection to the app."
  [ev]
  (connection/connected? ev))

(defn on-connection!
  "Registers (f connected?), called from any thread whenever this
  evaluator connects or loses its connection (Stop's detach, the app
  quitting, stop!). The window's status-bar label listens here."
  [ev f]
  (connection/on-connection! ev f))

;; --- one evaluation ------------------------------------------------------------

(defn- stop-requested?
  "Delegates to connection/stop-requested?, kept local so wake! and
  run-worker!'s own calls stay unqualified, and so a test's with-redefs
  on this var (reached via connection/connect!'s :stop-requested? policy
  key below) can intercept every guard connect! checks, not only the
  in-swap reads of state connect! makes directly."
  [ev]
  (connection/stop-requested? ev))

(def ^:private cljd-policy
  ;; Passed to connection/connect!: the cljd sentences (ruling 6: a
  ;; message is a string or a fn of the port or project dir), the
  ;; session state a successful connect starts with, and stop-requested?
  ;; wrapped in a fn so it's read through attach.clj's own var at call
  ;; time (a with-redefs on the var itself wouldn't reach a value already
  ;; captured into this map).
  {:messages {:no-project cljd/no-project-message :not-running cljd/not-running-message
              :bad-port-file cljd/bad-port-file-message :refused cljd/refused-message
              :not-server cljd/not-cljd-message :connecting cljd/connecting-message}
   :on-connect {:session-ns cljd/default-ns :fresh? true}
   :stop-requested? (fn [ev] (stop-requested? ev))})

(defn- request!
  "attach's calls go through connection/request!, cljd's still-waiting
  sentence and no on-message hook."
  [ev msg on-output]
  (connection/request! ev msg {:on-output on-output :still-waiting cljd/still-waiting-message}))

(defn- wake!
  "A fresh session's first requests are Jonquil's own probe (cljd/probe-code),
  never the user's code: sent once, and once more when the app answers nil
  (a REPL that isn't awake yet). nil once the app answers 3 (the session is
  then no longer fresh), else the result to finish with, and the user's
  code isn't sent. A session that stays fresh is probed again next time.
  The probe's banner out is shown; its value isn't.

  A Stop that landed while any probe was in flight reads as :interrupted
  -- any non-:awake outcome, not only :lost or :failed, checked ahead of
  the asleep retry so a Stop between or after the two probes neither
  retries again nor falls through to not-awake-message -- the same way
  a disconnected or eval-error status does for an eval or a load
  (jonquil.nrepl.ops/eval-result, jonquil.cljd/load-result): finish!
  no longer squashes every non-:interrupted result on Stop, so each
  failure path decides for itself."
  [{:keys [state] :as ev} on-output]
  (when (:fresh? @state)
    (loop [tries 2]
      (let [r (cljd/probe-result (request! ev {:op "eval" :code cljd/probe-code} on-output))]
        (cond
          (= :awake r) (do (swap! state assoc :fresh? false) nil)
          (stop-requested? ev) {:status :interrupted}
          (and (= :asleep r) (> tries 1)) (recur (dec tries))
          (= :lost r) {:status :error :error-text cljd/lost-connection}
          :else {:status :notice :text cljd/not-awake-message})))))

(defn- finish!
  "Ends the running evaluation: idle first, then on-complete, as
  jonquil.eval does. result is already fully resolved by the caller (the
  early-exit branches hardcode :interrupted, wake! and cljd/load-result
  decide it themselves for their own failure paths, and cljd/eval-result
  goes through jonquil.nrepl.ops/eval-result) -- finish! doesn't
  second-guess it, since a blanket Stop-implies-:interrupted squash here
  once masked load-result's own \"Load stopped at form N\" diagnostic
  end to end."
  [{:keys [state]} {:keys [on-status-change on-complete]} result]
  (swap! state assoc :running nil)
  (try (when on-status-change (on-status-change :idle)) (catch Throwable _ nil))
  (try (when on-complete (on-complete result)) (catch Throwable _ nil)))

(defn- run-worker!
  [ev code {:keys [run? path ns-source on-output on-status-change] :as opts}]
  (try
    (when on-status-change (on-status-change :running))
    (if (and run? (nil? path))
      (finish! ev opts {:status :error :error-text cljd/save-first-message})
      (if-let [problem (or (connection/connect! ev on-output cljd-policy) (wake! ev on-output))]
        (finish! ev opts problem)
        (let [target (when (and (not run?) ns-source (not (stop-requested? ev))) (cljd/target-ns ns-source))
              switch (when (and target (not= (:name target) (:session-ns @(:state ev))))
                       (cljd/eval-result (request! ev {:op "eval" :code (:text target)} on-output)
                                         (stop-requested? ev)))]
          (cond
            (stop-requested? ev) (finish! ev opts {:status :interrupted})
            (and switch (not= :ok (:status switch))) (finish! ev opts switch)
            run?
            (let [msgs (request! ev {:op "load-file" :file code
                                     :file-path (.getAbsolutePath (io/file path))
                                     :file-name (.getName (io/file path))}
                                 on-output)]
              (finish! ev opts (cljd/load-result msgs (or (:name (cljd/ns-form code)) (.getName (io/file path)))
                                                 (stop-requested? ev))))
            :else
            (finish! ev opts (cljd/eval-result (request! ev {:op "eval" :code code} on-output)
                                               (stop-requested? ev)))))))
    (catch Throwable e
      (finish! ev opts {:status :error :error-text (str "Error: " (ex-message e))}))))

(defrecord Attach [selection state history listeners timings]
  ev/Evaluator
  ;; Connecting happens on demand, when an evaluation finds no live
  ;; connection (the spec's "reconnect on demand"): nothing to start.
  (start! [_] nil)
  (stop! [this]
    ;; Window close, app quit, or a switch to another evaluator: close this
    ;; socket and nothing else.
    (swap! state (fn [s] (cond-> s (:running s) (assoc-in [:running :stop-requested?] true))))
    (connection/detach! this)
    nil)
  (eval! [this code opts]
    (when-not (:run? opts) (swap! history eval/history-add code))
    (let [[old _] (swap-vals! state (fn [s] (if (:running s) s (assoc s :running {:opts opts}))))]
      (if (:running old)
        busy-warning
        (do (future (run-worker! this code opts))
            nil))))
  (interrupt! [this]
    ;; Stop detaches. It never sends interrupt: the app keeps whatever it
    ;; already has, and the next evaluation reconnects. Spec §3 has no
    ;; "while running" qualifier, so an idle Stop on a still-connected
    ;; session detaches too -- there's no evaluation and so no on-output
    ;; to print the notice through, and the window doesn't show
    ;; on-connection! anywhere yet (that's P2.T4's job), so this closes
    ;; the socket and lets the connection watch notify listeners, without
    ;; printing anything.
    (let [[old _] (swap-vals! state (fn [s] (cond-> s (:running s) (assoc-in [:running :stop-requested?] true))))]
      (if-let [running (:running old)]
        (when-not (:stop-requested? running)
          (connection/say! (:on-output (:opts running)) :system (str cljd/detached-message "\n"))
          (connection/detach! this))
        (when (:conn old)
          (connection/detach! this))))
    nil)
  (evaluating? [_] (some? (:running @state)))
  (waiting-for-input? [_] false)
  (send-input! [_ _] nil)
  (send-eof! [_] nil)
  (history [_] history)
  (prompt-ns [_] (symbol (or (:session-ns @state) cljd/default-ns)))
  ;; Reset on Run is off for cljd: hot reload can't unload a namespace.
  (set-mode! [_ _] nil)
  ;; Completion is offline: the fork has no completions op (measured
  ;; unknown-op). Never Jolt's own docs.
  (complete [_ prefix text pos extra-context]
    (try (cljd/candidates (:profile selection) prefix text pos extra-context)
         (catch Throwable _ [])))
  ;; Quick doc asks the app's lookup (fork 685e56de and later answer it) in
  ;; the buffer's namespace, only while attached: it never connects on its
  ;; own. Bounded by :help-timeout-ms, since it runs on the GTK thread.
  (lookup [_ sym text _pos]
    (try
      (let [{:keys [conn session ops session-ns]} @state]
        (cond
          (not (client/open? conn)) (cljd/no-docs sym cljd/not-attached-doc)
          (not (contains? ops "lookup")) (cljd/no-docs sym cljd/no-lookup-doc)
          :else
          (let [r (client/request-sync conn {:op "lookup" :sym sym :session session
                                             :ns (or (:name (cljd/ns-form text)) session-ns cljd/default-ns)}
                                       (ev/help-timeout (:help-timeout-ms timings)))]
            (if (vector? r)
              (cljd/doc-result sym r)
              (cljd/no-docs sym cljd/lookup-timeout-doc)))))
      (catch Throwable _ nil)))
  (limitations [_]
    ["Stop detaches: there's no interrupt, so the app keeps running whatever it already has."
     "No stdin. println output comes back, but is lost when the form throws."
     "No server-side completion: completion uses this buffer and ClojureDart's own forms. Docs come from the fork's lookup."
     "Reset on Run isn't available: hot reload can't unload a namespace, so every Run adds to the app."
     "A load stops at the first form the app's REPL compiler rejects; the app's watcher still compiles the saved file."])

  ev/ServerHelp
  (offline-candidates [_ prefix text pos extra-context]
    (try (cljd/candidates (:profile selection) prefix text pos extra-context)
         (catch Throwable _ [])))

  connection/Attached
  (connected? [this] (connection/live? this))
  (on-connection! [this f] (connection/add-listener! this f)))

(defn make-attach
  "A not yet connected attach evaluator for a jonquil.dialect/select result.
  timings overrides default-timings (tests shorten them)."
  ([selection] (make-attach selection {}))
  ([selection timings]
   (->Attach selection
             (atom {:running nil :conn nil :connecting nil :session nil :ops #{} :session-ns nil :fresh? false})
             (atom (eval/create-history))
             (atom [])
             (merge default-timings timings))))

(defmethod connection/make-attached :cljd [selection] (make-attach selection))
