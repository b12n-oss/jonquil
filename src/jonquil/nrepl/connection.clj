(ns jonquil.nrepl.connection
  "The attach connection lifecycle shared by every out-of-process nREPL
  evaluator that reconnects on demand instead of starting or stopping its
  own server: ClojureDart's fork today (jonquil.nrepl.attach), a JVM
  project's clj -M:nrepl or similar later. Every evaluator here stays on
  runtime :attach; make-attached is the second dispatch, on the
  selection's dialect id.

  Every function reads an evaluator's :state atom, whose shape is the
  evaluator record's own: at least :running, :conn, :connecting and
  :session, plus whatever dialect-specific keys that dialect's connect
  policy's :on-connect adds on a successful connect (:session-ns and
  :fresh? for cljd).

  Connecting happens on demand, when an evaluation finds no live
  connection (reconnect on demand): nothing to start. Behaviour here is
  unchanged from jonquil.nrepl.attach's pre-split connected?,
  on-connection!, notify!, port-file, stop-requested?, connect!, detach!
  and request! -- only the messages, the :on-connect state, the
  still-waiting text, the on-message hook and the running request id are
  parameterised per dialect."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.ops :as ops]))

(defprotocol Attached
  "Satisfied by every attach evaluator record. The window checks
  (satisfies? connection/Attached e) in place of an instance? check on
  any one dialect's record."
  (connected? [ev] "True while ev holds a live connection.")
  (on-connection! [ev f]
    "Registers (f connected?), called from any thread whenever ev
    connects or loses its connection."))

(defmulti make-attached
  "A not yet connected attach evaluator for a jonquil.dialect/select
  result, dispatched on (:id (:profile selection)). Each dialect's
  namespace adds its method; requiring that namespace is what registers
  it."
  (fn [selection] (:id (:profile selection))))

(defmethod make-attached :default [selection]
  (let [id (:id (:profile selection))]
    (throw (ex-info (str "No attach evaluator for dialect " id) {:dialect id}))))

(defmethod ev/make :attach [selection] (make-attached selection))

(defn live?
  "True while ev's :conn is open. The body both connected? implementations
  use."
  [ev]
  (client/open? (:conn @(:state ev))))

(defn add-listener!
  "Registers (f connected?) on ev, called from any thread whenever ev
  connects or loses its connection."
  [ev f]
  (swap! (:listeners ev) conj f)
  nil)

(defn notify! [ev connected]
  (doseq [f @(:listeners ev)]
    (try (f connected) (catch Throwable _ nil))))

(defn say!
  "Calls (on-output kind text); never throws."
  [on-output kind text]
  (when on-output
    (try (on-output kind text) (catch Throwable _ nil))))

(defn stop-requested? [{:keys [state]}]
  (boolean (get-in @state [:running :stop-requested?])))

(defn port-file
  "The profile's :port-file in the selection's project directory, or nil
  when the window's file has no project."
  [{:keys [selection]}]
  (when-let [dir (:project-dir selection)]
    (io/file dir (:port-file (:profile selection)))))

(defn- message-text
  "The value at (get messages k), calling it with arg when it's a fn
  (ruling 6: a message is a string or a fn of its argument)."
  [messages k arg]
  (let [v (get messages k)]
    (if (fn? v) (v arg) v)))

(defn parse-port
  "A port file's text as a port number, or nil when it doesn't hold one.
  Every port file so far (the cljd fork's NREPL.lock, a JVM server's
  .nrepl-port) is the bare number and a newline."
  [text]
  (let [n (try (some-> text str/trim parse-long) (catch Exception _ nil))]
    (when (and n (< 0 n 65536)) n)))

(defn connect!
  "The connect rules, run only when there's no live connection. nil once
  connected, else the result to finish the evaluation with (a :notice: a
  missing or broken port file, or a port that doesn't answer as this
  dialect ends the Run normally). Reads the port file and never writes or
  deletes it.

  Checked after each blocking step (describe, clone): if Stop landed
  while waiting, the local socket is closed and nothing is published as
  the LIVE :conn in state, so the connection this attempt was building is
  never mistaken for one that's fully attached, never notified as
  connected, and never probed. M4: the raw conn is published early, as
  :connecting, the moment it opens -- before describe or clone can block
  -- guarded the same conditional way, so a Stop landing mid-handshake
  has something live for detach!/interrupt! to close at once instead of
  waiting out describe's or clone's own timeout.

  policy is {:messages {:no-project s :not-running s-or-fn-of-dir
  :bad-port-file s :refused fn-of-port :not-server fn-of-port :connecting
  fn-of-port} :on-connect {state keys merged into :state on success}
  :stop-requested? fn-of-ev, optional}. :stop-requested? defaults to
  connection/stop-requested? itself; a dialect passes its own so a test
  can intercept every guard connect! checks (a with-redefs on a var
  reached only through the dialect's own namespace can't otherwise see
  calls made from inside this function)."
  [{:keys [state timings] :as ev} on-output {:keys [messages on-connect] :as policy}]
  (let [stop? (or (:stop-requested? policy) stop-requested?)]
    (when-not (connected? ev)
      (let [f (port-file ev)]
        (cond
          (nil? f) {:status :notice :text (message-text messages :no-project nil)}
          (not (.exists ^java.io.File f))
          {:status :notice :text (message-text messages :not-running (:project-dir (:selection ev)))}
          :else
          (if-let [port (parse-port (try (slurp f) (catch Exception _ nil)))]
            (do
              (say! on-output :system (str (message-text messages :connecting port) "\n"))
              (if-let [conn (try (client/connect port) (catch Exception _ nil))]
                (let [[_ new] (swap-vals! state (fn [s] (if (get-in s [:running :stop-requested?])
                                                          s
                                                          (assoc s :connecting conn))))]
                  (if-not (identical? conn (:connecting new))
                    (do (client/close! conn) {:status :interrupted})
                    (let [d (client/request-sync conn {:op "describe"} (:describe-timeout-ms timings))]
                      (if (stop? ev)
                        (do (client/close! conn) {:status :interrupted})
                        (let [op-set (if (vector? d) (ops/op-names d) #{})
                              sid (when (and (contains? op-set "eval") (contains? op-set "load-file"))
                                    (let [c (client/request-sync conn {:op "clone"} (:clone-timeout-ms timings))]
                                      (when (vector? c) (some :new-session c))))]
                          (cond
                            (stop? ev) (do (client/close! conn) {:status :interrupted})
                            sid (let [[_ new] (swap-vals! state
                                                          (fn [s] (if (get-in s [:running :stop-requested?])
                                                                    s
                                                                    (merge (assoc s :conn conn :session sid :ops op-set)
                                                                           on-connect))))]
                                  (if (identical? conn (:conn new))
                                    (do (add-watch (:open? conn) ::connection
                                                   (fn [_ _ was now] (when (and was (not now)) (notify! ev false))))
                                        (notify! ev true)
                                        nil)
                                    (do (client/close! conn) {:status :interrupted})))
                            ;; describe answered disconnected: the app quit between
                            ;; the socket opening and the reply landing. That's a
                            ;; refused connection, not a non-matching server (the
                            ;; not-server sentence is only for one that answers
                            ;; describe for real, without eval and load-file).
                            (and (vector? d) (contains? (ops/statuses d) ops/disconnected))
                            (do (client/close! conn) {:status :notice :text (message-text messages :refused port)})
                            :else (do (client/close! conn)
                                      {:status :notice :text (message-text messages :not-server port)})))))))
                {:status :notice :text (message-text messages :refused port)}))
            {:status :notice :text (message-text messages :bad-port-file nil)}))))))

(defn detach!
  "Forgets the session and closes the socket: pending requests fail with
  jonquil.nrepl.client's disconnected status. Sends nothing to the app.
  Also closes and clears :connecting (M4): connect! publishes the raw
  socket there before describe or clone can block on it, so a Stop
  landing mid-handshake has something live to close instead of waiting
  out describe's or clone's own timeout. client/close! is safe to call
  twice on the same conn (a successful connect! leaves :conn and
  :connecting pointing at the same object until the next detach!)."
  [{:keys [state]}]
  (let [{:keys [conn connecting]} (first (swap-vals! state assoc :conn nil :session nil :connecting nil :fresh? false))]
    (client/close! conn)
    (client/close! connecting)))

(defn request!
  "Sends msg on the live session and waits for its done reply, with no
  timeout. Streams :out and :err to (:on-output opts) as they arrive,
  tracks the session's namespace from every :ns, and says once when it
  has waited (:still-waiting-ms timings), through (:still-waiting opts)
  when it's set (ruling 7: cljd passes its sentence, JVM passes nil since
  Stop can interrupt there). Calls (:on-message opts) with every reply
  after request!'s own side effects. While (:running @state) is set,
  records the request id at [:running :id] (ruling 8). Returns every
  reply, in order. Never resends anything.

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
  [{:keys [state timings]} msg {:keys [on-output on-message still-waiting]}]
  (let [{:keys [conn session]} @state]
    (if-not (client/open? conn)
      [{:status ["done" ops/disconnected]}]
      (let [acc (atom [])
            done (promise)
            id (client/request! conn (assoc msg :session session)
                                (fn [m]
                                  (swap! acc conj m)
                                  (when (identical? conn (:conn @state))
                                    (when-let [n (:ns m)] (swap! state assoc :session-ns n))
                                    (when-let [o (:out m)] (say! on-output :out o))
                                    (when-let [e (:err m)] (say! on-output :err e)))
                                  (when on-message (on-message m))
                                  (when (client/done? m) (deliver done @acc))))]
        (when (:running @state) (swap! state assoc-in [:running :id] id))
        (when (and still-waiting (= ::waiting (deref done (:still-waiting-ms timings) ::waiting)))
          (say! on-output :system (str still-waiting "\n")))
        @done))))
