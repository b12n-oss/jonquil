(ns jonquil.nrepl.jvm
  "The JVM Clojure attach evaluator: Jonquil connects to an nREPL server the
  user already started (clojure -M:serve or any server that writes
  .nrepl-port) and never starts, stops or signals that JVM.

  Run is load-file with the buffer's text; a REPL entry, Run Selection and
  Run Form are eval, each with an explicit :ns -- the session's prompt
  namespace for a REPL entry, the buffer's own ns form (or the prompt
  namespace, when the buffer has none) for a selection or a form. Jonquil
  never sends a buffer's ns form on its own (rule 3): a selection whose
  namespace isn't loaded on the server gets the not-loaded sentence and
  nothing else is sent (rule 5/rule 7).

  Unlike ClojureDart's attach evaluator, this server supports interrupt and
  stdin. Stop marks the running request's id and, when the server
  advertises interrupt, sends it and gives the server up to
  :interrupt-timeout-ms to actually stop before detaching; without the op,
  or if it doesn't take, Jonquil detaches and says so. An idle Stop is a
  no-op (ruling 5): there's nothing to interrupt, and the connection is
  cheap to keep, unlike cljd's Stop which always detaches. stdin sends a
  line through the live session and never closes it (nREPL 1.7.0 fails an
  empty stdin, so send-eof! is a no-op, P0.1).

  The prompt namespace moves on a successful Run (to the buffer's own
  namespace, ruling 4) and on a REPL entry's reply :ns; a selection or a
  form leaves it alone.

  The connection lifecycle itself (connect!, detach!, request!, and the
  Attached protocol) lives in jonquil.nrepl.connection, shared with
  jonquil.nrepl.attach: this namespace supplies the :clj policy (in
  jonquil.clj) and the pieces that stay dialect-specific (the run worker
  and the record). complete merges the server's own completions into the
  offline candidates; lookup is backed by the server's own lookup op,
  with a Defined in <file>:<line> line (jonquil.help/location-line) for a
  definition outside the buffer (ruling 13). Neither ever connects on its
  own: both run on the GTK thread and simply fall back to offline
  candidates or a no-docs result when there's no live connection."
  (:require [clojure.java.io :as io]
            [jonquil.clj :as clj]
            [jonquil.cljd :as cljd]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.help :as help]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.ops :as ops]))

(def default-timings
  {;; estimate: attach's own describe/clone timeouts, measured against a
   ;; listening socket that never speaks nREPL (describe) or session
   ;; bookkeeping that answers at once in every spike probe (clone).
   :describe-timeout-ms 5000
   :clone-timeout-ms 10000
   ;; attach's own still-waiting-ms: how long jonquil.nrepl.connection/request!
   ;; waits before saying so once. JVM never passes a sentence through it
   ;; (ruling 7, ns-not-found: Stop can interrupt here), so this bounds
   ;; nothing on its own but keeps the shape the same as attach's timings.
   :still-waiting-ms 30000
   ;; jonquil.nrepl.evaluator's own interrupt-timeout-ms: how long Stop gives
   ;; the server to actually end the evaluation after sending interrupt,
   ;; before Jonquil gives up and detaches instead.
   :interrupt-timeout-ms 1000
   ;; estimate, attach's own help-timeout-ms basis: quick doc runs on the
   ;; GTK thread, so a busy or wedged server can hold a key for at most
   ;; this long.
   :help-timeout-ms 300
   ;; estimate: the full-doc popover (P3.T2) can afford to wait longer than
   ;; a quick-doc lookup, since it's asked for on demand, not on every
   ;; keystroke.
   :full-doc-timeout-ms 10000})

(def busy-warning
  ;; jonquil.eval/eval-async's own wording, so the console reads the same.
  "; [Warning: Evaluation already in progress. Press Stop to cancel.]\n")

(defn- stop-requested?
  "Delegates to connection/stop-requested?, kept local (mirroring
  jonquil.nrepl.attach's identical need) so run-worker!'s own calls, and
  this dialect's connect policy below, stay unqualified and read through
  this namespace's own var at call time. A qualified cross-namespace
  reference (client/request-sync called from inside connection.clj,
  connection/connect! called from inside this namespace) is direct-linked
  and a with-redefs on it can't see calls made from inside that other
  function; only a var reached through the CALLING namespace's own
  unqualified reference is interceptable that way (measured against this
  Jolt build)."
  [ev]
  (connection/stop-requested? ev))

(def ^:private connect-policy
  ;; clj/connect-policy plus a :stop-requested? override (ruling: connect!
  ;; defaults to its own connection/stop-requested? otherwise), so a
  ;; test's with-redefs on this namespace's stop-requested? var reaches
  ;; every guard connect! checks, not only the calls run-worker! makes
  ;; directly.
  (assoc clj/connect-policy :stop-requested? (fn [ev] (stop-requested? ev))))

(defn- finish!
  "Ends the running evaluation: clears :running and :waiting? (a Stop or a
  detach mid-read must never leave the console believing input is still
  wanted), then idle, then on-complete, each in its own try so a throwing
  caller can't swallow the other."
  [{:keys [state]} {:keys [on-status-change on-complete]} result]
  (swap! state assoc :running nil :waiting? false)
  (try (when on-status-change (on-status-change :idle)) (catch Throwable _ nil))
  (try (when on-complete (on-complete result)) (catch Throwable _ nil)))

(defn- call-wait-input!
  "Calls (on-wait-input waiting?); never throws (it runs from the
  connection's reader thread, inside connection/request!'s on-message
  hook, so a throwing caller must lose only itself)."
  [on-wait-input waiting?]
  (when on-wait-input
    (try (on-wait-input waiting?) (catch Throwable _ nil))))

(defn- stdin-watch
  "connection/request!'s :on-message hook for one evaluation: a need-input
  reply marks state waiting and says so; any later reply, while state is
  still marked waiting, clears it and says so. A second need-input (the
  measured double reply after the stdin op's own done) re-marks it the
  same way, harmlessly."
  [state on-wait-input]
  (fn [m]
    (cond
      (clj/need-input? m)
      (do (swap! state assoc :waiting? true)
          (call-wait-input! on-wait-input true))
      (:waiting? @state)
      (do (swap! state assoc :waiting? false)
          (call-wait-input! on-wait-input false)))))

(defn- run-worker!
  [{:keys [state] :as ev} code {:keys [run? path ns-source on-output on-wait-input on-status-change] :as opts}]
  (try
    (when on-status-change (on-status-change :running))
    (if-let [problem (connection/connect! ev on-output connect-policy)]
      (finish! ev opts problem)
      (if (stop-requested? ev)
        ;; Stop landed after connect! published a live session but before
        ;; either request went out: finish at once, mirroring attach.clj's
        ;; own worker guard, and send nothing. The connection itself is
        ;; fine (connect! already succeeded), so this doesn't detach.
        (finish! ev opts {:status :interrupted})
        (let [watch (stdin-watch state on-wait-input)
              do-request! (fn [msg] (connection/request! ev msg {:on-output on-output
                                                                 :still-waiting nil
                                                                 :on-message watch}))]
          (if run?
            ;; Run: load-file, never told an :ns (the fixture's own ns form
            ;; decides what the server evaluates it in). A successful Run
            ;; moves the prompt namespace to the buffer's own (ruling 4).
            (let [f (when path (io/file path))
                  msgs (do-request! {:op "load-file" :file code
                                     :file-path (if f (.getAbsolutePath f) "untitled.clj")
                                     :file-name (if f (.getName f) "untitled.clj")})
                  result (clj/eval-result msgs (stop-requested? ev))]
              (when (= :ok (:status result))
                (swap! state assoc :prompt-ns (clj/prompt-after-run code result (:prompt-ns @state))))
              (finish! ev opts result))
            ;; REPL line (no ns-source): eval in the prompt namespace, and a
            ;; successful reply's own :ns moves the prompt. Selection/Form
            ;; (ns-source present): eval in the buffer's own namespace, or
            ;; the prompt namespace when the buffer has none; the prompt
            ;; never moves for these (rule 3: never the buffer's ns form
            ;; itself, only named as :ns).
            (let [ns (or (when ns-source (clj/buffer-ns ns-source)) (str (:prompt-ns @state)))
                  msgs (do-request! {:op "eval" :code code :ns ns})]
              (if (clj/ns-not-found? msgs)
                (finish! ev opts {:status :notice :text (clj/not-loaded-message ns)})
                (let [result (clj/eval-result msgs (stop-requested? ev))]
                  (when (and (nil? ns-source) (= :ok (:status result)) (:ns result))
                    (swap! state assoc :prompt-ns (:ns result)))
                  (finish! ev opts result))))))))
    (catch Throwable e
      (finish! ev opts {:status :error :error-text (str "Error: " (ex-message e))}))))

(defrecord Jvm [selection state history listeners timings]
  ev/Evaluator
  ;; Connecting happens on demand, when an evaluation finds no live
  ;; connection: nothing to start.
  (start! [_] nil)
  (stop! [this]
    ;; I1: apply-selection! calls interrupt! then stop! back to back when
    ;; switching away from a busy window. The old stop! detached at once,
    ;; on the calling thread, before interrupt!'s own send future had a
    ;; chance to run, so the interrupt never reached the server. This
    ;; keys the decision on :running and an open conn, not on
    ;; :stop-requested? being newly set here, so it still sends even when
    ;; interrupt! already marked the stop -- a duplicate interrupt op for
    ;; the same id is harmless, and interrupt!'s own send future firing
    ;; too is fine. conn, session and id are read from old, the atomic
    ;; pre-swap snapshot, so a later state change (a new evaluation,
    ;; started only once this one's :running clears) can't hide them from
    ;; this send.
    (let [[old] (swap-vals! state (fn [s] (cond-> s (:running s) (assoc-in [:running :stop-requested?] true))))
          conn (:conn old)]
      (if (and (:running old) (client/open? conn) (contains? (:ops old) "interrupt"))
        (let [session (:session old)
              id (get-in old [:running :id])
              sent (promise)]
          (future
            (try
              (client/send! conn {:op "interrupt" :session session :interrupt-id id})
              (catch Throwable _ nil)
              (finally (deliver sent true))))
          (future
            (try
              (deref sent (:interrupt-timeout-ms timings) ::timeout)
              (connection/detach! this)
              (catch Throwable _ nil))))
        (connection/detach! this)))
    nil)
  (eval! [this code opts]
    (when-not (:run? opts) (swap! history eval/history-add code))
    ;; A fresh, unique token per evaluation (Important 1): interrupt!'s
    ;; failed-interrupt future keys its exit test on this identity, not on
    ;; :running being non-nil, so it can never mistake a later evaluation
    ;; for the one it was started to watch.
    (let [[old _] (swap-vals! state (fn [s] (if (:running s) s (assoc s :running {:opts opts :token (Object.)}))))]
      (if (:running old)
        busy-warning
        (do (future (run-worker! this code opts))
            nil))))
  (interrupt! [this]
    ;; Runs on the GTK thread and must not block (Important 3): every
    ;; socket write (the interrupt op itself) moves inside a future, and
    ;; this only ever reads/writes atoms, fires a future, or calls
    ;; detach! (a socket close plus the fail-pending handlers).
    (let [[old _] (swap-vals! state (fn [s] (cond-> s (:running s) (assoc-in [:running :stop-requested?] true))))]
      (when-let [running (:running old)]
        ;; Minor 1: a second Stop for the same evaluation does nothing --
        ;; the first Stop's future (or immediate detach) already owns it.
        (when-not (:stop-requested? running)
          (let [on-output (:on-output (:opts running))
                token (:token running)
                conn (:conn @state)]
            (if-not (client/open? conn)
              ;; Minor 2: connect! hasn't published a live :conn yet (still
              ;; connecting, or never got that far). :ops may be stale from
              ;; an earlier connection and can't be trusted here, so this
              ;; ignores it: detach! at once closes :connecting too, with
              ;; no sentence and no interrupt op sent.
              (connection/detach! this)
              (let [ops (:ops @state)
                    session (:session @state)
                    id (get-in @state [:running :id])]
                (if (contains? ops "interrupt")
                  (do
                    ;; The send and the fallback clock are separate futures,
                    ;; so a peer that stops reading (send! blocked on a full
                    ;; buffer) still gets the interrupt-failed detach on
                    ;; time. The send is skipped once this evaluation has
                    ;; ended, since a nil :interrupt-id would interrupt
                    ;; whatever runs next in the session.
                    (future
                      (try
                        (when (identical? token (get-in @state [:running :token]))
                          (client/send! conn {:op "interrupt" :session session :interrupt-id id}))
                        (catch Throwable _ nil)))
                    (future
                      (try
                        (loop [n 0]
                          (cond
                            ;; This evaluation has ended, or a newer one has
                            ;; already taken :running (Important 1): either
                            ;; way, this future is no longer relevant.
                            (not (identical? token (get-in @state [:running :token]))) nil
                            (>= (* n 20) (:interrupt-timeout-ms timings))
                            (do (connection/say! on-output :system (str clj/interrupt-failed-message "\n"))
                                (connection/detach! this))
                            :else (do (Thread/sleep 20) (recur (inc n)))))
                        (catch Throwable _ nil))))
                  (do
                    (connection/say! on-output :system (str clj/interrupt-failed-message "\n"))
                    (connection/detach! this))))))))
      nil))
  (evaluating? [_] (some? (:running @state)))
  (waiting-for-input? [_] (boolean (:waiting? @state)))
  (send-input! [_ text]
    ;; The stdin op's own socket write moves into a future (Important 3),
    ;; same as interrupt!'s. :waiting? clears synchronously so
    ;; waiting-for-input? reflects the send at once; on-wait-input false
    ;; only fires when it was really waiting AND :running is still the
    ;; same evaluation (Minor 3), both read from the one swap-vals! that
    ;; does the clearing.
    (let [[old _] (swap-vals! state assoc :waiting? false)
          {:keys [conn session running]} old
          token (:token running)]
      (when (client/open? conn)
        (future
          (try
            (client/request! conn {:op "stdin" :stdin (clj/stdin-line (str text)) :session session}
                             (fn [_] nil))
            (catch Throwable _ nil))))
      (when (and (:waiting? old) (some? token) (identical? token (get-in @state [:running :token])))
        (call-wait-input! (:on-wait-input (:opts running)) false)))
    nil)
  ;; P0.1: an empty stdin fails on nREPL 1.7.0, so there's no clean EOF to
  ;; send. A read just keeps waiting.
  (send-eof! [_] nil)
  (history [_] history)
  (prompt-ns [_] (:prompt-ns @state))
  ;; Reset on Run is off for :clj: this is the user's own JVM.
  (set-mode! [_ _] nil)
  ;; Offline candidates are the same buffer/profile-based completion as
  ;; cljd; when connected and the server advertises "completions", its
  ;; names are merged in too, bounded by :help-timeout-ms since this runs
  ;; on the GTK thread. Any throw, a timeout or no connection falls back
  ;; to offline alone. Never docs/candidates, which would list Jolt-
  ;; process namespaces instead of the attached server's own.
  (complete [_ prefix text pos extra-context]
    (let [offline (try (cljd/candidates (:profile selection) prefix text pos extra-context)
                       (catch Throwable _ []))]
      (try
        (let [{:keys [conn session ops prompt-ns]} @state]
          (if (and (client/open? conn) (contains? ops "completions"))
            (let [r (client/request-sync conn {:op "completions" :prefix prefix
                                               :ns (clj/help-ns text prompt-ns)
                                               :session session}
                                         (:help-timeout-ms timings))]
              (if (vector? r)
                (ops/merge-candidates prefix offline (ops/candidates r))
                offline))
            offline))
        (catch Throwable _ offline))))
  ;; Backed by the server's own lookup op, never connecting on its own.
  ;; :file is turned into a Defined in ... line (ruling 13); help/doc-text
  ;; never reads :file, only :location.
  (lookup [_ sym text _pos]
    (try
      (let [{:keys [conn session ops prompt-ns]} @state]
        (cond
          (not (client/open? conn)) (cljd/no-docs sym clj/not-attached-doc)
          (not (contains? ops "lookup")) (cljd/no-docs sym clj/no-lookup-doc)
          :else
          (let [r (client/request-sync conn {:op "lookup" :sym sym :session session
                                             :ns (clj/help-ns text prompt-ns)}
                                       (:help-timeout-ms timings))]
            (if (vector? r)
              (when-let [d (ops/doc-info sym r)]
                (cond-> d (:file d) (assoc :location (help/location-line (:file d) (:line d)))))
              (cljd/no-docs sym clj/lookup-timeout-doc)))))
      (catch Throwable _ nil)))
  (limitations [_] clj/limitations)

  connection/Attached
  (connected? [this] (connection/live? this))
  (on-connection! [this f] (connection/add-listener! this f)))

(defn make-jvm
  "A not yet connected JVM attach evaluator for a jonquil.dialect/select
  result. timings overrides default-timings (tests shorten them)."
  ([selection] (make-jvm selection {}))
  ([selection timings]
   (->Jvm selection
          (atom {:running nil :conn nil :connecting nil :session nil :ops #{}
                 :session-ns nil :prompt-ns 'user :waiting? false})
          (atom (eval/create-history))
          (atom [])
          (merge default-timings timings))))

(defmethod connection/make-attached :clj [selection] (make-jvm selection))
