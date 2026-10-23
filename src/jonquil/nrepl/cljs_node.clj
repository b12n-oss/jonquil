(ns jonquil.nrepl.cljs-node
  "The ClojureScript-on-node evaluator: a window on a .cljs file owns a
  ClojureScript child (clojure launching cljs.main's node prepl), started
  lazily on the first evaluation and spoken to over a line-EDN prepl
  (jonquil.nrepl.prepl).

  The shape follows jonquil.nrepl.jank. The prepl has no request ids, so
  each evaluation sends its code followed by a sentinel form and reads
  replies until the sentinel's ret. Stop kills the child (no interrupt), so
  earlier definitions are gone afterwards. Help evaluates (cljs.repl/doc x)
  only while idle; a help that times out (or that an evaluation cancelled)
  leaves its sentinel in :drain, and
  replies up to its ret are dropped before the next evaluation's are read.

  Replies reach the state through one handler per connection. It drops
  anything after that connection's :closed (the prepl client may deliver a
  few buffered replies late) and touches shared state only through atoms,
  so overlapping calls from the reader thread and close! are safe."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jolt.process :as p]
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
   :start-timeout-ms nil
   ;; estimate: the page connects ~2.6 s after `open` (measured); the rest is
   ;; slack for a cold browser.
   :page-timeout-ms 60000})

(def settle-deadline-ms
  ;; estimate: a launch notices a Stop within its poll, then kills the tree;
  ;; the rest is slack (same as jonquil.nrepl.jank).
  5000)

(def busy-warning
  "; [Warning: Evaluation already in progress. Press Stop to cancel.]\n")

(def not-running-doc "No docs: ClojureScript isn't running yet. Run something first.")
(def busy-doc "No docs: ClojureScript is busy evaluating.")
(def timeout-doc "No docs: ClojureScript didn't answer in time.")

(def exited-text "Error: the ClojureScript process exited")

(def port-twice-message
  "; ClojureScript couldn't start: the browser port was in use twice. Try Run again.")

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
  the child and kills its process tree. Idempotent. With :background? the
  kill runs in a future (Stop calls this on the GTK thread, and a JVM plus
  node can take a second or two to die); :killing counts kills in flight,
  and settled? waits for them, so quitting never cuts one off. With
  :keep-log? the run dir stays, so a message that names the log still
  points at a file."
  ([ev] (kill-child! ev {}))
  ([{:keys [state hooks] :as ev} {:keys [background? keep-log?]}]
   (let [[old _] (swap-vals! state (fn [s]
                                     (cond-> (assoc s :child nil :conn nil :ready? false
                                                    :used? false :prompt-ns 'cljs.user :drain nil
                                                    :setup-pending nil :analyzer-syms nil :analyzer-stale? nil)
                                       (and background? (:child s)) (update :killing inc))))]
     (when (:conn old) (try (prepl/close! (:conn old)) (catch Throwable _ nil)))
     (when-let [child (cond-> (:child old) keep-log? (dissoc :run-dir))]
       ;; Said now, not when the kill ends: a background kill can finish after
       ;; the next evaluation's child connected, and must not contradict it.
       (connection/notify! ev false)
       (if background?
         (future (try ((:kill hooks) child)
                      (catch Throwable _ nil)
                      (finally (swap! state update :killing dec))))
         ((:kill hooks) child)))
     nil)))

(defn- setup-failed
  "The notice for a setup that failed or timed out, naming the child's log,
  which kill-child! :keep-log? leaves on disk."
  [what child]
  {:status :notice
   :text (cljs-node/start-failed-message
          (str what (when (:log child) (str ". The log: " (:log child)))))})

(defn- browser? [{:keys [selection]}]
  (= :browser (:cljs-target selection)))

(defn- free-port
  "A loopback port the OS just handed out: bind port 0, read it, close it."
  []
  (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort s)))

(defn- http-port!
  "The evaluator's HTTP port: chosen once, kept for every launch. With
  :fresh? a new one replaces it (the launcher found it taken)."
  [{:keys [state]} fresh?]
  (:http-port (swap! state (fn [s] (if (and (:http-port s) (not fresh?))
                                     s
                                     (assoc s :http-port (free-port)))))))

(defn- launch-child!
  "Launches once, or twice when the browser's HTTP port was taken. [started
  port-changed?]."
  [{:keys [hooks selection] :as ev} clojure launcher]
  (let [base {:clojure clojure
              :launcher launcher
              :project-dir (:project-dir selection)
              :cancelled? (fn [] (stopping? ev))}
        go (fn [fresh?]
             ((:launch hooks) (cond-> base
                                (browser? ev) (assoc :target :browser
                                                     :http-port (http-port! ev fresh?)
                                                     :console? (not (false? (:cljs-console? selection)))))))
        started (go false)]
    (if (= :http-port-taken (:error started))
      [(go true) true]
      [started false])))

(defn- await-kills!
  "A browser launch pre-binds the evaluator's HTTP port, so it waits (bounded
  by settle-deadline-ms) for background kills to finish: the old JVM would
  still hold the port, and the launcher would exit 3 and change it under an
  open tab."
  [{:keys [state] :as ev}]
  (let [deadline (+ (System/currentTimeMillis) settle-deadline-ms)]
    (loop []
      (when (and (pos? (:killing @state))
                 (not (stopping? ev))
                 (< (System/currentTimeMillis) deadline))
        (Thread/sleep 20)
        (recur)))))

(defn- ensure-child!
  "nil once a live child is stored in state, else the result to finish the
  evaluation with. A stored child that died, or lost its connection, is
  killed first, and so is one whose late setup failed (it would only report
  the failure again). A Stop during the launch kills what it produced."
  [{:keys [state hooks] :as ev} on-output]
  (let [child (:child @state)]
    (if (and child ((:alive? hooks) child) (prepl/open? (:conn @state))
             (not= :failed (:setup-pending @state)))
      nil
      (let [failed? (= :failed (:setup-pending @state))]
        (when failed?
          (connection/say! on-output :system
                           (str (:text (setup-failed "the session setup failed" child)) "\n")))
        (when child (kill-child! ev {:keep-log? failed?}))
        (when (browser? ev) (await-kills! ev))
        (if-let [clojure ((:find-clojure hooks))]
          (let [launcher (try ((:ensure-launcher hooks)) (catch Throwable e e))]
            (if (instance? Throwable launcher)
              {:status :notice :text (cljs-node/start-failed-message (ex-message launcher))}
              (do
                (connection/say! on-output :system (str (cljs-node/starting-message clojure) "\n"))
                (let [[started port-changed?] (launch-child! ev clojure launcher)]
                  (cond
                    (= :cancelled (:error started)) {:status :interrupted}
                    (= :http-port-taken (:error started)) {:status :notice :text port-twice-message}
                    (:error started) {:status :notice :text (cljs-node/start-failed-message (:error started))}
                    :else (do (swap! state
                                     (fn [s]
                                       (cond-> (assoc s :child started)
                                         (browser? ev)
                                         (assoc :await-page (str "http://127.0.0.1:" (:http-port s))
                                                :announce (if (:opened? s) :reload :first)
                                                :port-changed? port-changed?))))
                              (when (stopping? ev) (kill-child! ev))
                              nil))))))
          {:status :notice :text cljs-node/no-clojure-message})))))

(defn- dispatch!
  "Routes one reply of one connection. closed? is that connection's own
  flag: a reply after its :closed is dropped, and :closed itself is
  delivered once. A ret whose :form is the pending :drain sentinel (a help
  that timed out or was cancelled) clears :drain and is dropped with
  everything before it. A
  ret that is the current exchange's sentinel is recorded in :done-forms in
  the same swap, so exchange! can tell a timeout from a ret that just
  beat it."
  [{:keys [state] :as ev} closed? r]
  (cond
    (and (browser? ev) (cljs-node/waiting-line? r)) nil

    (= :closed (:tag r))
    (when (compare-and-set! closed? false true)
      (swap! state assoc :drain nil)
      (when-let [f (:sink @state)] (f r)))

    @closed? nil

    ;; Nothing waiting: output node prints after an evaluation finished (a
    ;; setTimeout, a promise) goes to the last evaluation's on-output.
    (and (nil? (:sink @state)) (nil? (:drain @state)) (#{:out :err :tap} (:tag r)))
    (when-let [f (:last-output @state)]
      (when-let [[k t] (cljs-node/output-of r)]
        (connection/say! f k t)))

    :else
    (let [form (when (= :ret (:tag r)) (:form r))
          [old _] (swap-vals! state
                              (fn [s]
                                (let [s (if (and form (= form cljs-node/setup-form) (true? (:setup-pending s))
                                                 (or (= form (:drain s)) (= form (:sink-form s))))
                                          ;; The setup's answer is recorded here whether
                                          ;; or not its exchange is still waiting.
                                          (assoc s :setup-pending (if (:exception r) :failed nil)
                                                 :ready? (not (:exception r)))
                                          s)]
                                  (cond
                                    (and form (= form (:drain s))) (assoc s :drain nil)
                                    (and form (= form (:sink-form s))) (update s :done-forms conj form)
                                    :else s))))]
      (when (and form (= form (:drain old)) (= form cljs-node/setup-form)
                 (true? (:setup-pending old)) (not (:exception r)))
        (connection/notify! ev true))
      (cond
        (:drain old) nil
        (:sink old) ((:sink old) r)))))

(defn- exchange!
  "Sends text and collects the replies until (done? reply) or :closed or
  timeout-ms. [outcome replies], outcome :done, :closed, :timeout or
  :cancelled. Output replies go to on-output as they arrive when it is
  given. With :sentinel (the form whose ret ends the exchange), the end of
  the exchange is one swap: it clears the sink and, on a timeout or a
  cancel whose sentinel ret has not arrived, sets :drain to it, so the
  late ret is dropped by dispatch! and nothing else is. With :cancel-key,
  a fn ending the exchange with :cancelled sits at that state key while it
  runs (how wait-not-helping! lets an evaluation cancel a help exchange);
  a stale cancel after the exchange ended is a no-op."
  [{:keys [state]} text done? {:keys [on-output timeout-ms sentinel cancel-key]}]
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
    (swap! state (fn [s] (cond-> (assoc s :sink sink :sink-form sentinel)
                           cancel-key (assoc cancel-key (fn [] (deliver p :cancelled))))))
    (let [outcome (try (prepl/send! (:conn @state) text)
                       (if timeout-ms (deref p timeout-ms :timeout) (deref p))
                       (catch Throwable _ :closed))]
      (swap! state
             (fn [s]
               (let [arrived? (contains? (:done-forms s) sentinel)]
                 (cond-> (if (= sentinel (:sink-form s)) (assoc s :sink nil :sink-form nil) s)
                   cancel-key (assoc cancel-key nil)
                   sentinel (update :done-forms disj sentinel)
                   (and sentinel (#{:timeout :cancelled} outcome) (not arrived?))
                   (assoc :drain sentinel)))))
      [outcome @replies])))

(defn- next-sentinel! [{:keys [state]}]
  (cljs-node/sentinel (:n (swap! state update :n inc))))

(defn- ret? [r] (= :ret (:tag r)))

(defn- open-url!
  "Opens url in the default browser: open on macOS, xdg-open elsewhere, or
  the command named by env JONQUIL_OPEN_URL (given the URL as its only
  argument) when that is set. True when it started and did not exit
  non-zero within about a second (a process still running then is reaped in
  the background), false (never throws) otherwise."
  ([url] (open-url! url (System/getenv "JONQUIL_OPEN_URL")))
  ([url override]
   (try (let [mac? (str/includes? (str/lower-case (str (System/getProperty "os.name"))) "mac")
              program (if (str/blank? override) (if mac? "open" "xdg-open") override)
              proc (p/process {:out :discard :err :discard} program url)
              jp ^java.lang.Process (:proc proc)]
          ;; A non-zero exit within ~1 s is a failure; still running is fine.
          (loop [n 0]
            (cond (not (p/alive? proc)) (zero? (.exitValue jp))
                  ;; Still running: reap it when it ends, so it can't linger.
                  (>= n 50) (do (future (try (deref proc) (catch Throwable _ nil)))
                                true)
                  :else (do (Thread/sleep 20) (recur (inc n))))))
        (catch Throwable _ false))))

(defn- announce-page!
  "Says how the page is reached, once per browser launch: opens it while it
  has not been opened yet (and says when its port changed on the way), else
  asks for a reload, or to open the new address when the port changed."
  [{:keys [state hooks]} on-output]
  (let [[old _] (swap-vals! state (fn [s] (cond-> (assoc s :announce nil :port-changed? nil)
                                            (= :first (:announce s)) (assoc :opened? true))))
        url (:await-page old)
        changed? (:port-changed? old)
        say (fn [t] (connection/say! on-output :system (str t "\n")))]
    (case (:announce old)
      :first (do (if ((:open-url hooks) url)
                   (say (cljs-node/opened-message url))
                   (say (str "; Open " url " in a browser.")))
                 (when changed?
                   (say (str "; The browser port changed: the page is at " url "."))))
      :reload (say (if changed?
                     (str "; The browser port changed: open " url ".")
                     (cljs-node/reload-message url)))
      nil)))

(defn- connect!
  "Connects the prepl to the stored child and runs the setup form. nil when
  ready, else the result to finish with."
  [{:keys [state timings] :as ev}]
  (let [child (:child @state)
        browser? (browser? ev)
        closed? (atom false)
        connect (fn [] (prepl/connect! "127.0.0.1" (:port child) (fn [r] (dispatch! ev closed? r))))
        ;; A connect right after the previous connection closed was once
        ;; reset (measured, transient): one retry.
        conn (try (try (connect)
                       (catch Throwable e
                         (if (re-find #"(?i)reset|refused" (str (ex-message e)))
                           (do (Thread/sleep 300) (connect))
                           (throw e))))
                  (catch Throwable e e))]
    (if (instance? Throwable conn)
      (do (kill-child! ev {:keep-log? true})
          (setup-failed (str "couldn't connect to port " (:port child) ": " (ex-message conn)) child))
      (do
        (swap! state assoc :conn conn)
        (if (stopping? ev)
          (do (try (prepl/close! conn) (catch Throwable _ nil))
              {:status :interrupted})
          ;; The browser's setup blocks until a page connects, so the page is
          ;; announced (and opened) first, and the wait is the page bound.
          (let [_ (when browser?
                    (announce-page! ev (:last-output @state))
                    ;; Before the exchange, so dispatch! records the setup's
                    ;; answer whichever side of the page bound it lands on.
                    (swap! state assoc :setup-pending true))
                [outcome replies] (exchange! ev cljs-node/setup-form ret?
                                             (if browser?
                                               {:timeout-ms (:page-timeout-ms timings)
                                                :sentinel cljs-node/setup-form}
                                               {:timeout-ms (:setup-timeout-ms timings)}))]
            (cond
              (stopping? ev) {:status :interrupted}
              (and browser? (true? (:setup-pending @state)) (= :timeout outcome))
              ;; Keep the child: the setup ret is drained when it arrives and
              ;; the next evaluation waits for it (await-setup!).
              {:status :notice :text (cljs-node/no-page-message (:await-page @state))}
              (and browser? (nil? (:setup-pending @state)) (#{:done :timeout} outcome))
              (do (connection/notify! ev true)
                  nil)
              (and (not browser?) (= :done outcome) (not-any? :exception replies))
              (do (swap! state assoc :ready? true)
                  (connection/notify! ev true)
                  nil)
              :else
              (do (kill-child! ev {:keep-log? true})
                  (setup-failed (str "the session setup " (if (= :timeout outcome) "timed out" "failed"))
                                child)))))))))

(defn- await-setup!
  "A browser setup that timed out is still pending: wait for its ret, bounded
  by the page timeout again. nil when ready, else the result to finish with."
  [{:keys [state timings] :as ev}]
  (let [url (:await-page @state)
        deadline (+ (System/currentTimeMillis) (:page-timeout-ms timings))]
    (loop []
      (let [{:keys [setup-pending conn]} @state]
        (cond
          (stopping? ev) {:status :interrupted}
          (nil? setup-pending) nil
          (= :failed setup-pending) (let [child (:child @state)]
                                      (kill-child! ev {:keep-log? true})
                                      (setup-failed "the session setup failed" child))
          (not (prepl/open? conn)) {:status :error :error-text exited-text}
          (> (System/currentTimeMillis) deadline) {:status :notice :text (cljs-node/no-page-message url)}
          :else (do (Thread/sleep 20) (recur)))))))

(defn- wait-not-helping!
  "An evaluation outranks help: cancels an in-flight help exchange (its
  sentinel drains like a timeout's, see exchange!), then waits out the
  milliseconds the helper needs to release :helping; without the cancel a
  Run could wait a help budget, ~2 s against a page that just closed."
  [{:keys [state]}]
  (when (:helping @state)
    (loop [n 0]
      (when (and (:helping @state) (< n 700))
        ;; re-read each tick: the helper claims :helping a moment before
        ;; exchange! stores the cancel fn, and deliver is idempotent
        (when-let [cancel (:help-cancel @state)] (cancel))
        (Thread/sleep 10)
        (recur (inc n))))))

(defn- eval-text [prompt-ns {:keys [run? ns-source]} code sentinel-form]
  (let [buffer-ns (when (and (not run?) ns-source) (clj/buffer-ns ns-source))
        prefix (when (and buffer-ns (not= buffer-ns (str prompt-ns)))
                 (str "(in-ns '" buffer-ns ")\n"))]
    (str prefix code "\n" sentinel-form)))

(defn- incomplete-problem
  "The reader's EOF message when code ends inside a form (an open string,
  a trailing #_ ' @ or backslash), else nil. Brackets are checked by
  commands/pre-run-problem; this catches what balanced brackets miss, which
  would otherwise swallow the sentinel into the last form and hang. Other
  read errors pass: the prepl answers those itself."
  [code]
  (let [r (java.io.PushbackReader. (java.io.StringReader. code))]
    (try
      (binding [*default-data-reader-fn* (fn [_ v] v)]
        (loop []
          (when-not (= ::eof (read {:read-cond :allow :features #{:cljs} :eof ::eof} r))
            (recur))))
      nil
      (catch Throwable e
        (let [m (or (ex-message e) "")]
          (when (str/starts-with? m "EOF") (str "Error: " m)))))))

(defn- fetch-syms!
  "Fetches the analyzer's full symbol list (cljs-node/apropos-form) into
  :analyzer-syms, bounded like a doc lookup; skipped while anything runs
  or helps, or with no live child. Claiming the fetch clears
  :analyzer-stale?, so each completed evaluation buys one attempt: a page
  that went away can't cost the help budget on every keystroke. An answer
  that isn't a symbol list (cljs-node/parse-apropos nil) changes nothing.
  Never throws. It must never ride after an evaluation on its own: sent to
  a browser page that just left, it held up the closed-tab fail-fast (8 s
  measured, 2026-10-23) and made quick docs answer busy."
  [{:keys [state timings] :as ev}]
  (let [[old _] (swap-vals! state (fn [s] (if (or (:running s) (:helping s)) s (assoc s :helping true))))]
    (when-not (or (:running old) (:helping old))
      (swap! state assoc :analyzer-stale? nil)
      (if-not (live? ev)
        (swap! state assoc :helping false)
        (try
          (let [sentinel-form (next-sentinel! ev)
                form cljs-node/apropos-form
                [outcome replies] (exchange! ev (str form "\n" sentinel-form)
                                             (fn [r] (and (ret? r) (= sentinel-form (:form r))))
                                             {:sentinel sentinel-form
                                              :cancel-key :help-cancel
                                              :on-output (:last-output @state)
                                              :timeout-ms (ev/help-timeout (:help-timeout-ms timings))})]
            (when (= :done outcome)
              (when-let [syms (some (fn [r]
                                      (when (and (ret? r) (= form (:form r)) (not (:exception r)))
                                        (cljs-node/parse-apropos (:val r))))
                                    replies)]
                (swap! state assoc :analyzer-syms syms))))
          (catch Throwable _ nil)
          (finally (swap! state assoc :helping false)))))))

(defn- run-worker!
  [{:keys [state] :as ev} code {:keys [run? on-output on-status-change] :as opts}]
  (try
    (when on-output (swap! state assoc :last-output on-output))
    (when on-status-change (on-status-change :running))
    (if-let [problem (or (commands/pre-run-problem code) (incomplete-problem code))]
      (finish! ev opts {:status :error :error-text problem})
      (let [_ (when (and run? (= :reset (:mode @state)) (:used? @state))
                (kill-child! ev)
                (connection/say! on-output :system (str cljs-node/reset-message "\n")))
            problem (or (ensure-child! ev on-output)
                        (if (:setup-pending @state)
                          (await-setup! ev)
                          (when-not (live? ev)
                            (or (when (stopping? ev) {:status :interrupted})
                                (connect! ev)))))]
        (cond
          problem (do (when (and (:child @state)
                                 (not (and (= :notice (:status problem)) (true? (:setup-pending @state))
                                           (prepl/open? (:conn @state)))))
                        (kill-child! ev))
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
                                               {:on-output on-output :sentinel sentinel-form})]
              (case outcome
                :done (let [result (cljs-node/fold-replies replies sentinel-form)]
                        (when (:ns result) (swap! state assoc :prompt-ns (:ns result)))
                        ;; the analyzer may know new names now; the next
                        ;; completion's backstop fetches them. Marked
                        ;; before finish!, so a completion racing
                        ;; on-complete can't miss the mark (review M2)
                        (swap! state assoc :analyzer-stale? true)
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

(defn- doc-form [sym] (str "(with-out-str (cljs.repl/doc " sym "))"))

(defn- doc-text
  "The doc text from a help exchange's replies: the value of doc-form's own
  ret (with-out-str hands the printed doc back as a string), so output the
  program prints meanwhile can't pass for docs. \"\" when there's none."
  [replies form]
  (or (some (fn [r]
              (when (and (ret? r) (= form (:form r)) (not (:exception r)))
                (let [v (try (edn/read-string (:val r)) (catch Throwable _ nil))]
                  (when (string? v) (str/trim v)))))
            replies)
      ""))

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
              form (doc-form sym)
              ;; The program's own output during the lookup (a timer firing)
              ;; goes to the console, as it would between evaluations.
              [outcome replies] (exchange! ev (str form "\n" sentinel-form)
                                           (fn [r] (and (ret? r) (= sentinel-form (:form r))))
                                           {:sentinel sentinel-form
                                            :cancel-key :help-cancel
                                            :on-output (:last-output @state)
                                            :timeout-ms (or timeout-ms (ev/help-timeout (:help-timeout-ms timings)))})]
          (if (= :done outcome)
            (let [t (doc-text replies form)]
              (cljd/no-docs sym (if (str/blank? t) (str "No docs for " sym) t)))
            ;; cancelled = an evaluation arrived: that's busy, not slow
            (cljd/no-docs sym (if (= :cancelled outcome) busy-doc timeout-doc))))
        (finally (swap! state assoc :helping false))))))

(defrecord CljsNode [selection state history listeners timings hooks]
  ev/Evaluator
  (start! [_] nil)
  (stop! [this]
    (swap! state (fn [s] (cond-> (assoc s :stopped? true :last-output nil)
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
        (kill-child! this {:background? true})))
    nil)
  (evaluating? [_] (some? (:running @state)))
  (waiting-for-input? [_] false)
  (send-input! [_ _] nil)
  (send-eof! [_] nil)
  (history [_] history)
  (prompt-ns [_] (:prompt-ns @state))
  (set-mode! [_ mode] (swap! state assoc :mode mode) nil)
  (complete [this prefix text pos extra-context]
    (try
      (let [offline (cljd/candidates (:profile selection) prefix text pos extra-context)]
        (try
          ;; stale alone arms the fetch: an empty cache must not, or a
          ;; failed fetch (a gone page, say) would retry on every
          ;; completion and hold :helping ~2 s each time (review I1)
          (when (and (:analyzer-stale? @state)
                     (live? this) (nil? (:running @state)))
            ;; the fetch runs here, on ask-help!'s worker, never after an
            ;; evaluation (see fetch-syms!'s docstring)
            (fetch-syms! this))
          (let [syms (:analyzer-syms @state)
                have (into #{} (map :symbol) offline)
                extra (remove #(contains? have (:symbol %))
                              (cljs-node/analyzer-candidates
                               {:syms syms :prompt-ns (:prompt-ns @state)
                                :text text :prefix prefix}))]
            (if (seq extra)
              (vec (sort-by (fn [c] (cljd/candidate-sort-key (or prefix "") c))
                            (concat offline extra)))
              offline))
          (catch Throwable _ offline)))
      (catch Throwable _ [])))
  (lookup [this sym _text _pos]
    (try (meta-lookup this sym nil)
         (catch Throwable _ (cljd/no-docs sym timeout-doc))))
  (limitations [_] (if (= :browser (:cljs-target selection))
                     (cljs-node/browser-limitations (not (false? (:cljs-console? selection))))
                     cljs-node/limitations))

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
  cancelled? target http-port]}] child-or-{:error}), :find-clojure,
  :ensure-launcher, :alive?, :kill, and :open-url (fn [url] truthy on
  success), used only for a browser selection (:cljs-target :browser)."
  ([selection] (make-cljs-node selection {}))
  ([selection {:keys [timings launch find-clojure alive? kill ensure-launcher open-url]}]
   (let [timings (merge default-timings timings)]
     (->CljsNode selection
                 (atom {:running nil :conn nil :child nil :ready? false :sink nil
                        :drain nil :sink-form nil :done-forms #{} :helping false :n 0
                        :prompt-ns 'cljs.user :mode :reset :used? false :stopped? false
                        :killing 0 :last-output nil
                        :http-port nil :opened? false :await-page nil :announce nil})
                 (atom (eval/create-history))
                 (atom [])
                 timings
                 {:launch (or launch
                              (fn [o] (cljs-child/start! (assoc o :timeout-ms (:start-timeout-ms timings)))))
                  :find-clojure (or find-clojure cljs-child/find-clojure)
                  :ensure-launcher (or ensure-launcher files/ensure-launcher!)
                  :alive? (or alive? cljs-child/alive?)
                  :kill (or kill cljs-child/kill!)
                  :open-url (or open-url open-url!)}))))

(defn settled?
  "True once no evaluation (and so no launch) and no background kill is in
  flight."
  [ev]
  (let [s @(:state ev)]
    (and (nil? (:running s)) (zero? (:killing s)))))

(defn child-pid [ev] (:pid (:child @(:state ev))))

(defn run-dir [ev] (:run-dir (:child @(:state ev))))

(defmethod ev/make-launched :cljs [selection] (make-cljs-node selection))
