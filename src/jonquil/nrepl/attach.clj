(ns jonquil.nrepl.attach
  "The ClojureDart attach evaluator: Jonquil connects to the nREPL server a
  running app already started (the nrepl-poc fork's, which writes its port
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
  on-status-change and on-complete from the worker."
  (:require [clojure.java.io :as io]
            [jonquil.cljd :as cljd]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.ops :as ops]))

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

(defn- say! [on-output kind text]
  (when on-output
    (try (on-output kind text) (catch Throwable _ nil))))

;; --- the connection ----------------------------------------------------------

(defn connected?
  "True while this evaluator holds a live connection to the app."
  [ev]
  (client/open? (:conn @(:state ev))))

(defn on-connection!
  "Registers (f connected?), called from any thread whenever this
  evaluator connects or loses its connection (Stop's detach, the app
  quitting, stop!). The window's status-bar label listens here."
  [ev f]
  (swap! (:listeners ev) conj f)
  nil)

(defn- notify! [ev connected]
  (doseq [f @(:listeners ev)]
    (try (f connected) (catch Throwable _ nil))))

(defn- port-file
  "The profile's :port-file in the selection's project directory, or nil
  when the window's file has no project."
  [{:keys [selection]}]
  (when-let [dir (:project-dir selection)]
    (io/file dir (:port-file (:profile selection)))))

(defn- stop-requested? [{:keys [state]}]
  (boolean (get-in @state [:running :stop-requested?])))

(defn- connect!
  "The spec's connect rules, run only when there's no live connection. nil
  once connected, else the result to finish the evaluation with (a
  :notice: a missing or broken NREPL.lock, or a port that doesn't answer
  as ClojureDart ends the Run normally). Reads NREPL.lock and never writes
  or deletes it.

  Checked after each blocking step (describe, clone): if Stop landed
  while waiting, the local socket is closed and nothing is published into
  state, so the connection this attempt was building is never mistaken
  for a live one, never notified as connected, and never probed."
  [{:keys [state timings] :as ev} on-output]
  (when-not (connected? ev)
    (let [f (port-file ev)]
      (cond
        (nil? f) {:status :notice :text cljd/no-project-message}
        (not (.exists ^java.io.File f)) {:status :notice :text cljd/not-running-message}
        :else
        (if-let [port (cljd/parse-port (try (slurp f) (catch Exception _ nil)))]
          (do
            (say! on-output :system (str (cljd/connecting-message port) "\n"))
            (if-let [conn (try (client/connect port) (catch Exception _ nil))]
              (let [d (client/request-sync conn {:op "describe"} (:describe-timeout-ms timings))]
                (if (stop-requested? ev)
                  (do (client/close! conn) {:status :interrupted})
                  (let [op-set (if (vector? d) (ops/op-names d) #{})
                        sid (when (and (contains? op-set "eval") (contains? op-set "load-file"))
                              (let [c (client/request-sync conn {:op "clone"} (:clone-timeout-ms timings))]
                                (when (vector? c) (some :new-session c))))]
                    (cond
                      (stop-requested? ev) (do (client/close! conn) {:status :interrupted})
                      sid (let [[_ new] (swap-vals! state
                                                    (fn [s] (if (get-in s [:running :stop-requested?])
                                                              s
                                                              (assoc s :conn conn :session sid :ops op-set
                                                                     :session-ns cljd/default-ns :fresh? true))))]
                            (if (identical? conn (:conn new))
                              (do (add-watch (:open? conn) ::connection
                                             (fn [_ _ was now] (when (and was (not now)) (notify! ev false))))
                                  (notify! ev true)
                                  nil)
                              (do (client/close! conn) {:status :interrupted})))
                      ;; describe answered disconnected: the app quit between
                      ;; the socket opening and the reply landing. That's a
                      ;; refused connection, not a non-cljd server (the
                      ;; not-cljd sentence is only for one that answers
                      ;; describe for real, without eval and load-file).
                      (and (vector? d) (contains? (ops/statuses d) ops/disconnected))
                      (do (client/close! conn) {:status :notice :text (cljd/refused-message port)})
                      :else (do (client/close! conn)
                                {:status :notice :text (cljd/not-cljd-message port)})))))
              {:status :notice :text (cljd/refused-message port)}))
          {:status :notice :text cljd/bad-port-file-message})))))

(defn- detach!
  "Forgets the session and closes the socket: pending requests fail with
  jonquil.nrepl.client's disconnected status. Sends nothing to the app."
  [{:keys [state]}]
  (let [{:keys [conn]} (first (swap-vals! state assoc :conn nil :session nil :fresh? false))]
    (client/close! conn)))

;; --- one evaluation ------------------------------------------------------------

(defn- request!
  "Sends msg on the live session and waits for its done reply, with no
  timeout. Streams :out and :err to on-output as they arrive, tracks the
  session's namespace from every :ns, and says once when it has waited
  :still-waiting-ms. Returns every reply, in order. Never resends
  anything.

  Guards against a reply that arrives after this connection is no longer
  the live one (a detach racing an already-buffered reply): conn is the
  connection this request was actually sent on, captured once above, so
  once state has moved past it a late :ns can't overwrite the next
  session's tracked namespace and a late :out/:err can't reach a
  finished evaluation's console. The reply is still recorded and still
  ends the wait when it carries \"done\" (that's how a detach's own
  synthetic disconnected reply unblocks this same call), only the two
  side effects are skipped.

  A Stop that lands between the wake probe and this call (or between two
  calls in the same evaluation, the ns-switch then the user's code) has
  already detached: :conn is nil. Sending on a nil or closed conn would
  crash client/request! (it swaps on (:counter nil)), so this returns the
  same synthetic disconnected reply a mid-request detach produces,
  without ever calling send!."
  [{:keys [state timings]} msg on-output]
  (let [{:keys [conn session]} @state]
    (if-not (client/open? conn)
      [{:status ["done" ops/disconnected]}]
      (let [acc (atom [])
            done (promise)]
        (client/request! conn (assoc msg :session session)
                         (fn [m]
                           (swap! acc conj m)
                           (when (identical? conn (:conn @state))
                             (when-let [n (:ns m)] (swap! state assoc :session-ns n))
                             (when-let [o (:out m)] (say! on-output :out o))
                             (when-let [e (:err m)] (say! on-output :err e)))
                           (when (client/done? m) (deliver done @acc))))
        (when (= ::waiting (deref done (:still-waiting-ms timings) ::waiting))
          (say! on-output :system (str cljd/still-waiting-message "\n")))
        @done))))

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
      (if-let [problem (or (connect! ev on-output) (wake! ev on-output))]
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
    (detach! this)
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
          (say! (:on-output (:opts running)) :system (str cljd/detached-message "\n"))
          (detach! this))
        (when (:conn old)
          (detach! this))))
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
                                       (:help-timeout-ms timings))]
            (if (vector? r)
              (cljd/doc-result sym r)
              (cljd/no-docs sym cljd/lookup-timeout-doc)))))
      (catch Throwable _ nil)))
  (limitations [_]
    ["Stop detaches: there's no interrupt, so the app keeps running whatever it already has."
     "No stdin. println output comes back, but is lost when the form throws."
     "No server-side completion: completion uses this buffer and ClojureDart's own forms. Docs come from the fork's lookup."
     "Reset on Run isn't available: hot reload can't unload a namespace, so every Run adds to the app."
     "A load stops at the first form the app's REPL compiler rejects; the app's watcher still compiles the saved file."]))

(defn make-attach
  "A not yet connected attach evaluator for a jonquil.dialect/select result.
  timings overrides default-timings (tests shorten them)."
  ([selection] (make-attach selection {}))
  ([selection timings]
   (->Attach selection
             (atom {:running nil :conn nil :session nil :ops #{} :session-ns nil :fresh? false})
             (atom (eval/create-history))
             (atom [])
             (merge default-timings timings))))

(defmethod ev/make :attach [selection] (make-attach selection))
