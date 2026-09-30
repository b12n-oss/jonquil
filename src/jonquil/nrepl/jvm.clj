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
  and the record). complete and lookup are this task's P2 versions --
  offline completion only, no docs -- P3.T1 replaces lookup with one
  backed by the server's own lookup and doc ops."
  (:require [clojure.java.io :as io]
            [jonquil.clj :as clj]
            [jonquil.cljd :as cljd]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.connection :as connection]))

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
    (if-let [problem (connection/connect! ev on-output clj/connect-policy)]
      (finish! ev opts problem)
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
                result (clj/eval-result msgs (connection/stop-requested? ev))]
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
              (let [result (clj/eval-result msgs (connection/stop-requested? ev))]
                (when (and (nil? ns-source) (= :ok (:status result)) (:ns result))
                  (swap! state assoc :prompt-ns (:ns result)))
                (finish! ev opts result)))))))
    (catch Throwable e
      (finish! ev opts {:status :error :error-text (str "Error: " (ex-message e))}))))

(defrecord Jvm [selection state history listeners timings]
  ev/Evaluator
  ;; Connecting happens on demand, when an evaluation finds no live
  ;; connection: nothing to start.
  (start! [_] nil)
  (stop! [this]
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
    ;; Runs on the GTK thread: everything here either reads/writes an atom
    ;; or fires a socket write, then hands the wait to a future.
    (let [[old _] (swap-vals! state (fn [s] (cond-> s (:running s) (assoc-in [:running :stop-requested?] true))))]
      (when-let [running (:running old)]
        (let [on-output (:on-output (:opts running))
              {:keys [conn session ops]} @state]
          (if (contains? ops "interrupt")
            (do
              (client/send! conn {:op "interrupt" :session session
                                  :interrupt-id (get-in @state [:running :id])})
              (future
                (try
                  (loop [n 0]
                    (cond
                      (nil? (:running @state)) nil
                      (>= (* n 20) (:interrupt-timeout-ms timings))
                      (do (connection/say! on-output :system (str clj/interrupt-failed-message "\n"))
                          (connection/detach! this))
                      :else (do (Thread/sleep 20) (recur (inc n)))))
                  (catch Throwable _ nil))))
            (do
              (connection/say! on-output :system (str clj/interrupt-failed-message "\n"))
              (connection/detach! this))))))
    nil)
  (evaluating? [_] (some? (:running @state)))
  (waiting-for-input? [_] (boolean (:waiting? @state)))
  (send-input! [_ text]
    (let [{:keys [conn session running]} @state
          on-wait-input (:on-wait-input (:opts running))]
      (when (client/open? conn)
        (client/request! conn {:op "stdin" :stdin (clj/stdin-line (str text)) :session session}
                         (fn [_] nil)))
      (swap! state assoc :waiting? false)
      (call-wait-input! on-wait-input false))
    nil)
  ;; P0.1: an empty stdin fails on nREPL 1.7.0, so there's no clean EOF to
  ;; send. A read just keeps waiting.
  (send-eof! [_] nil)
  (history [_] history)
  (prompt-ns [_] (:prompt-ns @state))
  ;; Reset on Run is off for :clj: this is the user's own JVM.
  (set-mode! [_ _] nil)
  ;; P2 version: offline only, the same buffer/profile-based completion as
  ;; cljd. P3.T1 merges in the server's own completions.
  (complete [_ prefix text pos extra-context]
    (try (cljd/candidates (:profile selection) prefix text pos extra-context)
         (catch Throwable _ [])))
  ;; P2 version: no docs yet. P3.T1 replaces this with one backed by the
  ;; server's lookup and doc ops.
  (lookup [_ _ _ _] nil)
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
