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
  line through the live session. send-eof! sends an empty stdin, the
  end-of-input signal, on nREPL 1.8.0 or later while a read waits; an older
  server fails an empty stdin (P0.1), so it gets a one-time note instead and
  the read keeps waiting.

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
  candidates or a no-docs result when there's no live connection.
  full-doc (P3.T2, the jonquil.evaluator/FullDoc protocol) is backed by
  the server's own doc op instead, for the window's second quick-doc
  press (ruling 11): unlike lookup/complete it runs in a future, since
  it's asked for on demand rather than on every keystroke.

  Both lookup and full-doc resolve their own :ns through the shared
  resolve-lookup!: the buffer's own namespace first, falling back once to
  the prompt namespace when the server hasn't loaded it yet, and a
  not-loaded result (never a silent \"not found\" or a hung full-doc
  request) when neither answers without an error."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.clj :as clj]
            [jonquil.cljd :as cljd]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.help :as help]
            [jonquil.inspect :as inspect]
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
   ;; estimate: the full-doc popover waits off the GTK thread, with a
   ;; Loading line in the status bar. clojure-cli.repl's first doc call
   ;; downloads the ClojureDocs export (about 3.7 MB) into the server's
   ;; config dir, measured at 3.6 s to over 12 s, so this leaves room for
   ;; that one slow call. Later calls answer in milliseconds.
   :full-doc-timeout-ms 30000
   ;; estimate: the describe timeout's own basis. inspect! runs off the
   ;; GTK thread already (in a future), so this only bounds one request.
   :inspect-timeout-ms 5000})

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

(defn- register-inspect!
  "Ruling 4: right after a successful connect!, once the server has
  advertised inspect-start and this connection hasn't registered yet,
  sends one eval of nil on the session so a first Cmd/Ctrl+I after a Run
  doesn't show nil (spec probe 1: inspect-start only sees a session's *1
  once an eval, not merely a load-file, has run on it). Sent straight
  through client/request-sync on the conn, never connection/request!, so
  this streams nothing to the console and never moves :prompt-ns or
  :session-ns. Whatever the reply -- a failure only means the first
  inspect may show nil -- :inspect-registered? is set so this never
  resends on the same connection; connect!'s own :on-connect resets it to
  false on every fresh connect."
  [{:keys [state timings]}]
  (let [{:keys [conn session ops]} @state]
    (when (and (contains? ops "inspect-start") (not (:inspect-registered? @state)))
      (client/request-sync conn {:op "eval" :code "nil" :session session} (:describe-timeout-ms timings))
      (swap! state assoc :inspect-registered? true))))

(defn- auto-require!
  "Once per connection, when the selection carries .jonquil.edn's
  :auto-require list: says so, then requires every namespace in it with
  one eval on the session, streaming its output like any evaluation. A
  failure (not a Stop) says so after the server's own error; either way
  the user's request goes out next, and :auto-required? is set first so
  a failed list isn't resent until the next connect."
  [{:keys [selection state] :as ev} on-output]
  (let [nss (:auto-require selection)]
    (when (and (seq nss) (not (:auto-required? @state)))
      (swap! state assoc :auto-required? true)
      (connection/say! on-output :system (str (clj/auto-require-message nss) "\n"))
      (let [msgs (connection/request! ev {:op "eval" :code (clj/auto-require-code nss)}
                                      {:on-output on-output :still-waiting nil})]
        (when (and (not= :ok (:status (clj/eval-result msgs false)))
                   (not (stop-requested? ev)))
          (connection/say! on-output :system (str clj/auto-require-failed-message "\n")))))))

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
        (do
          (register-inspect! ev)
          (when-not (stop-requested? ev) (auto-require! ev on-output))
          ;; The registration and the auto-require are round trips of their
          ;; own, so a Stop can land during either: check again before the
          ;; user's request goes out.
          (if (stop-requested? ev)
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
                      (finish! ev opts result))))))))))
    (catch Throwable e
      (finish! ev opts {:status :error :error-text (str "Error: " (ex-message e))}))))

(defn- ns-errored?
  "True when a lookup reply's own statuses carry lookup-error or error --
  the real server's own shape for an unloaded ns."
  [r]
  (let [st (ops/statuses r)]
    (boolean (or (st "lookup-error") (st "error")))))

(defn- resolve-lookup!
  "Both lookup and full-doc's shared resolution: sends a lookup for sym,
  first with ns1 (clj/help-ns's own value: the buffer's own namespace
  form, else the prompt namespace). ns1 answering without an error
  (ns-errored?) resolves at once, even when it finds no info -- that's
  lookup's own long-standing plain-not-found case, unaffected.
  Only when ns1 errors does this retry once with (str prompt-ns) (unless
  that's the same ns already tried), and the retry counts as resolved
  only when it BOTH answers without error AND actually names sym
  (ops/doc-info): a namespace that merely doesn't error but knows nothing
  about sym is no more useful than ns1's own failure, and full-doc must
  never send doc against a namespace that was never really where sym
  lives. Each request is bounded by timeout-ms, so this never costs more
  than two of them (600 ms worst case with :help-timeout-ms's own
  default).

  {:status :ok :ns ns1 :reply r1}: ns1 answered without error.
  {:status :retried :ns ns2 :reply r2}: ns1 errored, but ns2 both
  answered without error and actually named sym.
  {:status :not-loaded :ns1 ns1}: every ns tried either errored or found
  nothing, or there was no distinct ns to retry with.
  {:status :not-attached}: the connection dropped during either lookup
  (the client's own synthetic disconnected reply), checked before any
  error or not-found reading of that reply.
  {:status :timeout}: a bounded request never answered."
  [conn session sym text prompt-ns timeout-ms]
  (let [ns1 (clj/help-ns text prompt-ns)
        lookup! (fn [ns] (client/request-sync conn {:op "lookup" :sym sym :ns ns :session session} timeout-ms))
        r1 (lookup! ns1)]
    (cond
      (not (vector? r1)) {:status :timeout}
      ((ops/statuses r1) ops/disconnected) {:status :not-attached}
      (not (ns-errored? r1)) {:status :ok :ns ns1 :reply r1}
      :else
      (let [ns2 (str prompt-ns)]
        (if (= ns1 ns2)
          {:status :not-loaded :ns1 ns1}
          (let [r2 (lookup! ns2)]
            (cond
              (not (vector? r2)) {:status :timeout}
              ((ops/statuses r2) ops/disconnected) {:status :not-attached}
              (and (not (ns-errored? r2)) (ops/doc-info sym r2)) {:status :retried :ns ns2 :reply r2}
              :else {:status :not-loaded :ns1 ns1})))))))

(defrecord Jvm [selection state history listeners timings]
  ev/Evaluator
  ;; Connecting happens on demand, when an evaluation finds no live
  ;; connection: nothing to start.
  (start! [_] nil)
  (stop! [this]
    ;; apply-selection! calls interrupt! then stop! back to back when
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
    ;; A fresh, unique token per evaluation: interrupt!'s failed-interrupt
    ;; future keys its exit test on this identity, not on :running being
    ;; non-nil, so it can never mistake a later evaluation for the one it
    ;; was started to watch.
    (let [[old _] (swap-vals! state (fn [s] (if (:running s) s (assoc s :running {:opts opts :token (Object.)}))))]
      (if (:running old)
        busy-warning
        (do (future (run-worker! this code opts))
            nil))))
  (interrupt! [this]
    ;; Runs on the GTK thread and must not block: every socket write (the
    ;; interrupt op itself) moves inside a future, and this only ever
    ;; reads/writes atoms, fires a future, or calls detach! (a socket
    ;; close plus the fail-pending handlers).
    (let [[old _] (swap-vals! state (fn [s] (cond-> s (:running s) (assoc-in [:running :stop-requested?] true))))]
      (when-let [running (:running old)]
        ;; A second Stop for the same evaluation does nothing --
        ;; the first Stop's future (or immediate detach) already owns it.
        (when-not (:stop-requested? running)
          (let [on-output (:on-output (:opts running))
                token (:token running)
                conn (:conn @state)]
            (if-not (client/open? conn)
              ;; connect! hasn't published a live :conn yet (still
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
                            ;; This evaluation has ended, or a newer one
                            ;; has already taken :running: either way,
                            ;; this future is no longer relevant.
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
    ;; The stdin op's own socket write moves into a future, same as
    ;; interrupt!'s. :waiting? clears synchronously so waiting-for-input?
    ;; reflects the send at once; on-wait-input false only fires when it
    ;; was really waiting AND :running is still the same evaluation, both
    ;; read from the one swap-vals! that does the clearing.
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
  ;; Only while a read waits. On nREPL 1.8.0 or later an empty stdin is EOF
  ;; (P0.1: 1.7.0 fails it): :waiting? clears and the write goes out. Older
  ;; or unknown servers get the note once per connection and keep waiting.
  ;; One swap-vals! decides both, so quick presses can't double up.
  ;; A narrow race is accepted: a read that finishes between the swap and
  ;; the socket write leaves the EOF queued, so the next read in that
  ;; session sees EOF once. send-input! has the same window.
  (send-eof! [_]
    (let [[old _] (swap-vals! state
                              (fn [st]
                                (cond
                                  (not (and (:waiting? st) (:running st))) st
                                  (clj/stdin-eof? (:nrepl-version st)) (assoc st :waiting? false)
                                  (not (:eof-noted? st)) (assoc st :eof-noted? true)
                                  :else st)))
          {:keys [conn session running nrepl-version]} old
          token (:token running)]
      (when (and (:waiting? old) running)
        (if (clj/stdin-eof? nrepl-version)
          (do
            (when (client/open? conn)
              (future
                (try
                  (client/request! conn {:op "stdin" :stdin "" :session session}
                                   (fn [_] nil))
                  (catch Throwable _ nil))))
            (when (and (some? token) (identical? token (get-in @state [:running :token])))
              (call-wait-input! (:on-wait-input (:opts running)) false)))
          (when-not (:eof-noted? old)
            (connection/say! (:on-output (:opts running)) :system
                             (str (clj/eof-unsupported-message nrepl-version) "\n"))))))
    nil)
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
  ;; never reads :file, only :location. A buffer namespace the server
  ;; hasn't loaded yet errors instead of answering info (resolve-lookup!'s
  ;; own ns1), so this falls back to the prompt namespace once before
  ;; giving up as not-loaded.
  (lookup [_ sym text _pos]
    (try
      (let [{:keys [conn session ops prompt-ns]} @state
            found (fn [r] (when-let [d (ops/doc-info sym r)]
                            (cond-> d (:file d) (assoc :location (help/location-line (:file d) (:line d))))))]
        (cond
          (not (client/open? conn)) (cljd/no-docs sym clj/not-attached-doc)
          (not (contains? ops "lookup")) (cljd/no-docs sym clj/no-lookup-doc)
          :else
          (let [res (resolve-lookup! conn session sym text prompt-ns (:help-timeout-ms timings))]
            (case (:status res)
              :timeout (cljd/no-docs sym clj/lookup-timeout-doc)
              :not-attached (cljd/no-docs sym clj/not-attached-doc)
              :not-loaded (cljd/no-docs sym (clj/not-loaded-doc (:ns1 res)))
              ;; :ok or :retried: resolve-lookup! already confirmed a
              ;; retry's own reply names sym, so found only ever returns
              ;; nil here for :ok's plain not-found (as today).
              (found (:reply res))))))
      (catch Throwable _ nil)))
  (limitations [_] clj/limitations)

  ev/FullDoc
  ;; P3.T2: backed by the server's own "doc" op, in a future (never on the
  ;; GTK thread, unlike lookup/complete above -- ruling: full doc is asked
  ;; for on demand, not on every keystroke, so it can afford to wait
  ;; longer, per :full-doc-timeout-ms's own estimate). Not connected or no
  ;; "doc" op answer at once, on the calling thread; on-result is called
  ;; exactly once either way. Everything is caught: a throw anywhere
  ;; (including inside the future) reports :timeout, never an escaping
  ;; exception into a key handler or a future nobody's watching.
  ;;
  ;; doc throws server-side (and never answers at all) for a namespace
  ;; the server hasn't loaded, so this first runs the SAME resolution
  ;; lookup uses (resolve-lookup!, bounded by :help-timeout-ms, same as a
  ;; real lookup) to find an :ns to trust: the buffer's own namespace when
  ;; the server answers there at all, else the prompt namespace when it
  ;; actually names sym. doc is sent only with that :ns, and only when
  ;; the lookup there found sym; neither resolving reports :not-loaded,
  ;; and a namespace that doesn't know sym reports :not-found, without
  ;; ever sending doc.
  ;;
  ;; A reply carrying the client's own disconnected status
  ;; (ops/disconnected, the synthetic reply a lost connection gets) is
  ;; :not-attached, never :ok -- and :ok requires actual, non-blank doc
  ;; text, since a vector reply with none is as good as no reply at all.
  (full-doc [_ sym text on-result]
    (try
      (let [{:keys [conn session ops prompt-ns]} @state]
        (cond
          (not (client/open? conn)) (on-result {:status :not-attached})
          (not (contains? ops "doc")) (on-result {:status :no-op})
          :else
          (future
            (try
              (let [res (resolve-lookup! conn session sym text prompt-ns (:help-timeout-ms timings))]
                (case (:status res)
                  :timeout (on-result {:status :timeout})
                  :not-attached (on-result {:status :not-attached})
                  :not-loaded (on-result {:status :not-loaded :ns (:ns1 res)})
                  ;; The namespace answered, but it doesn't know sym. doc
                  ;; would hang the server for a symbol it can't resolve
                  ;; (an unknown alias like nope/zip), so it's never sent.
                  (if-not (ops/doc-info sym (:reply res))
                    (on-result {:status :not-found})
                    (let [r (client/request-sync conn {:op "doc" :sym sym :session session :ns (:ns res)}
                                                 (:full-doc-timeout-ms timings))]
                      (on-result
                       (cond
                         (not (vector? r)) {:status :timeout}
                         ((ops/statuses r) ops/disconnected) {:status :not-attached}
                         :else (let [text (some :doc r)]
                                 (if (str/blank? text) {:status :timeout} {:status :ok :text text}))))))))
              (catch Throwable _ (on-result {:status :timeout}))))))
      (catch Throwable _ (on-result {:status :timeout})))
    nil)

  ev/Inspector
  ;; P3.T1: backed by the server's own inspect-* ops (inspect/request's own
  ;; mapping), off the GTK thread in a future, on-view called exactly once
  ;; and everything caught -- the same pattern jonquil.session's InProcess
  ;; uses. Every request carries :session.
  (inspect! [_ op args on-view]
    (future
      (try
        (let [result
              (let [{:keys [conn session ops]} @state]
                (cond
                  ;; I2: a nil :conn is "never attached" (inspect-not-
                  ;; attached, "Run something first"); a non-nil :conn
                  ;; that's no longer open was attached and dropped (the
                  ;; connection's own reader thread flips client/open? to
                  ;; false on EOF, but leaves :conn in place until the
                  ;; NEXT connect! or an explicit detach!) -- that case
                  ;; is clj/lost-connection, same sentence and :lost? true
                  ;; the in-flight-request drop below already answers.
                  (nil? conn) {:error clj/inspect-not-attached}
                  (not (client/open? conn)) {:error clj/lost-connection :lost? true}
                  (not (contains? ops "inspect-start")) {:error inspect/unavailable-message}
                  :else
                  (let [r (client/request-sync conn (assoc (inspect/request op args) :session session)
                                               (:inspect-timeout-ms timings))]
                    (cond
                      (not (vector? r)) {:error inspect/timeout-message}
                      ((ops/statuses r) ops/disconnected) {:error clj/lost-connection :lost? true}
                      :else (inspect/parse-view (some :view r))))))]
          (try (on-view result)
               (catch Throwable _)))
        (catch Throwable e
          (try (on-view {:error (or (ex-message e) (str (class e)))})
               (catch Throwable _)))))
    nil)
  ;; Ruling 5: true before connecting at all (:ops still empty -- inspect!
  ;; then answers not-attached, not unavailable), or once the server has
  ;; advertised inspect-start.
  (inspectable? [_]
    (let [ops (:ops @state)] (or (empty? ops) (contains? ops "inspect-start"))))

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
