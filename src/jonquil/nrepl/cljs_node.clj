(ns jonquil.nrepl.cljs-node
  "The ClojureScript-on-node evaluator: a window on a .cljs file owns a
  ClojureScript child (clojure launching cljs.main's node prepl), started
  lazily on the first evaluation and spoken to over a line-EDN prepl
  (jonquil.nrepl.prepl).

  The shape follows jonquil.nrepl.jank. The prepl has no request ids, so
  each evaluation sends its code followed by a sentinel form and reads
  replies until the sentinel's ret. Stop kills the child (no interrupt), so
  earlier definitions are gone afterwards. Help evaluates (cljs.repl/doc x)
  only while idle; a help that times out leaves its sentinel in :drain, and
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

(def busy-warning
  "; [Warning: Evaluation already in progress. Press Stop to cancel.]\n")

(def not-running-doc "No docs: ClojureScript isn't running yet. Run something first.")
(def busy-doc "No docs: ClojureScript is busy evaluating.")
(def timeout-doc "No docs: ClojureScript didn't answer in time.")

(def exited-text "Error: the ClojureScript process exited")

(defn- name* [x] (if (keyword? x) (name x) x))

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
  and settled? waits for them, so quitting never cuts one off."
  ([ev] (kill-child! ev {}))
  ([{:keys [state hooks] :as ev} {:keys [background?]}]
   (let [[old _] (swap-vals! state (fn [s]
                                     (cond-> (assoc s :child nil :conn nil :ready? false
                                                    :used? false :prompt-ns 'cljs.user :drain nil)
                                       (and background? (:child s)) (update :killing inc))))]
     (when (:conn old) (try (prepl/close! (:conn old)) (catch Throwable _ nil)))
     (when-let [child (:child old)]
       ;; Said now, not when the kill ends: a background kill can finish after
       ;; the next evaluation's child connected, and must not contradict it.
       (connection/notify! ev false)
       (if background?
         (future (try ((:kill hooks) child)
                      (catch Throwable _ nil)
                      (finally (swap! state update :killing dec))))
         ((:kill hooks) child)))
     nil)))

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
                                                     :http-port (http-port! ev fresh?)))))
        started (go false)]
    (if (= :http-port-taken (:error started))
      [(go true) true]
      [started false])))

(defn- ensure-child!
  "nil once a live child is stored in state, else the result to finish the
  evaluation with. A stored child that died, or lost its connection, is
  killed first. A Stop during the launch kills what it produced."
  [{:keys [state hooks] :as ev} on-output]
  (let [child (:child @state)]
    (if (and child ((:alive? hooks) child) (prepl/open? (:conn @state)))
      nil
      (do
        (when child (kill-child! ev))
        (if-let [clojure ((:find-clojure hooks))]
          (let [launcher (try ((:ensure-launcher hooks)) (catch Throwable e e))]
            (if (instance? Throwable launcher)
              {:status :notice :text (cljs-node/start-failed-message (ex-message launcher))}
              (do
                (connection/say! on-output :system (str (cljs-node/starting-message clojure) "\n"))
                (let [[started port-changed?] (launch-child! ev clojure launcher)]
                  (cond
                    (= :cancelled (:error started)) {:status :interrupted}
                    (:error started) {:status :notice :text (cljs-node/start-failed-message (name* (:error started)))}
                    :else (do (swap! state
                                     (fn [s]
                                       (cond-> (assoc s :child started)
                                         (browser? ev)
                                         (assoc :await-page (:url started)
                                                :announce (cond port-changed? :port-changed
                                                                (:opened? s) :reload
                                                                :else :first)))))
                              (when (stopping? ev) (kill-child! ev))
                              nil))))))
          {:status :notice :text cljs-node/no-clojure-message})))))

(defn- dispatch!
  "Routes one reply of one connection. closed? is that connection's own
  flag: a reply after its :closed is dropped, and :closed itself is
  delivered once. A ret whose :form is the pending :drain sentinel (a help
  that timed out) clears :drain and is dropped with everything before it. A
  ret that is the current exchange's sentinel is recorded in :done-forms in
  the same swap, so exchange! can tell a timeout from a ret that just
  beat it."
  [{:keys [state]} closed? r]
  (cond
    (cljs-node/waiting-line? r) nil

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
                                (cond
                                  (and form (= form (:drain s))) (assoc s :drain nil)
                                  (and form (= form (:sink-form s))) (update s :done-forms conj form)
                                  :else s)))]
      (cond
        (:drain old) nil
        (:sink old) ((:sink old) r)))))

(defn- exchange!
  "Sends text and collects the replies until (done? reply) or :closed or
  timeout-ms. [outcome replies], outcome :done, :closed or :timeout. Output
  replies go to on-output as they arrive when it is given. With :sentinel
  (the form whose ret ends the exchange), the end of the exchange is one
  swap: it clears the sink and, on a timeout whose sentinel ret has not
  arrived, sets :drain to it, so the late ret is dropped by dispatch! and
  nothing else is."
  [{:keys [state]} text done? {:keys [on-output timeout-ms sentinel]}]
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
    (swap! state assoc :sink sink :sink-form sentinel)
    (let [outcome (try (prepl/send! (:conn @state) text)
                       (if timeout-ms (deref p timeout-ms :timeout) (deref p))
                       (catch Throwable _ :closed))]
      (swap! state
             (fn [s]
               (let [arrived? (contains? (:done-forms s) sentinel)]
                 (cond-> (if (= sentinel (:sink-form s)) (assoc s :sink nil :sink-form nil) s)
                   sentinel (update :done-forms disj sentinel)
                   (and sentinel (= :timeout outcome) (not arrived?)) (assoc :drain sentinel)))))
      [outcome @replies])))

(defn- next-sentinel! [{:keys [state]}]
  (cljs-node/sentinel (:n (swap! state update :n inc))))

(defn- ret? [r] (= :ret (:tag r)))

(defn- connect!
  "Connects the prepl to the stored child and runs the setup form. nil when
  ready, else the result to finish with."
  [{:keys [state timings] :as ev}]
  (let [child (:child @state)
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
      {:status :notice
       :text (cljs-node/start-failed-message
              (str "couldn't connect to port " (:port child) ": " (ex-message conn)
                   (when (:log child) (str ". The log: " (:log child)))))}
      (do
        (swap! state assoc :conn conn)
        (if (stopping? ev)
          (do (try (prepl/close! conn) (catch Throwable _ nil))
              {:status :interrupted})
          (let [[outcome replies] (exchange! ev cljs-node/setup-form ret?
                                             {:timeout-ms (:setup-timeout-ms timings)})]
            (cond
              (stopping? ev) {:status :interrupted}
              (and (= :done outcome) (not-any? :exception replies))
              (do (swap! state assoc :ready? true)
                  (connection/notify! ev true)
                  nil)
              :else
              {:status :notice
               :text (cljs-node/start-failed-message
                      (str "the session setup " (if (= :timeout outcome) "timed out" "failed")
                           (when (:log child) (str ". The log: " (:log child)))))})))))))

(defn- open-url!
  "Opens url in the default browser: open on macOS, xdg-open elsewhere.
  True when the command started, false (never throws) when it did not."
  [url]
  (try (let [mac? (str/includes? (str/lower-case (str (System/getProperty "os.name"))) "mac")]
         (p/process {:out :discard :err :discard} (if mac? "open" "xdg-open") url)
         true)
       (catch Throwable _ false)))

(defn- announce-page!
  "Says how the page is reached, once per browser launch: opens it on the
  evaluator's first launch, else asks for a reload."
  [{:keys [state hooks]} on-output]
  (let [[old _] (swap-vals! state (fn [s] (cond-> (assoc s :announce nil)
                                            (= :first (:announce s)) (assoc :opened? true))))
        url (:await-page old)
        say (fn [t] (connection/say! on-output :system (str t "\n")))]
    (case (:announce old)
      :first (if ((:open-url hooks) url)
               (say (cljs-node/opened-message url))
               (say (str "; Open " url " in a browser.")))
      :reload (say (cljs-node/reload-message url))
      :port-changed (say (str "; The browser port changed: open " url "."))
      nil)))

(defn- wait-not-helping! [{:keys [state]}]
  (loop [n 0]
    (when (and (:helping @state) (< n 700))
      (Thread/sleep 10)
      (recur (inc n)))))

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

(defn- run-worker!
  [{:keys [state timings] :as ev} code {:keys [run? on-output on-status-change] :as opts}]
  (try
    (when on-output (swap! state assoc :last-output on-output))
    (when on-status-change (on-status-change :running))
    (if-let [problem (or (commands/pre-run-problem code) (incomplete-problem code))]
      (finish! ev opts {:status :error :error-text problem})
      (let [_ (when (and run? (= :reset (:mode @state)) (:used? @state))
                (kill-child! ev)
                (connection/say! on-output :system (str cljs-node/reset-message "\n")))
            problem (or (ensure-child! ev on-output)
                        (when-not (live? ev)
                          (or (when (stopping? ev) {:status :interrupted})
                              (connect! ev))))]
        (cond
          problem (do (when (:child @state) (kill-child! ev))
                      (finish! ev opts problem))
          (stopping? ev) (finish! ev opts {:status :interrupted})
          :else
          (do
            (wait-not-helping! ev)
            (announce-page! ev on-output)
            (let [await-url (:await-page @state)
                  sentinel-form (next-sentinel! ev)
                  text (eval-text (:prompt-ns @state) opts code sentinel-form)
                  _ (swap! state assoc :used? true)
                  [outcome replies] (exchange! ev text
                                               (fn [r] (and (ret? r) (= sentinel-form (:form r))))
                                               {:on-output on-output :sentinel sentinel-form
                                                :timeout-ms (when await-url (:page-timeout-ms timings))})]
              (case outcome
                :done (let [result (cljs-node/fold-replies replies sentinel-form)]
                        (swap! state assoc :await-page nil)
                        (when (:ns result) (swap! state assoc :prompt-ns (:ns result)))
                        (finish! ev opts result))
                (cond
                  (stopping? ev) (finish! ev opts {:status :interrupted})
                  ;; No page yet: keep the child, its late ret is drained.
                  (and (= :timeout outcome) await-url)
                  (finish! ev opts {:status :notice :text (cljs-node/no-page-message await-url)})
                  :else (do (kill-child! ev)
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
                                            :on-output (:last-output @state)
                                            :timeout-ms (or timeout-ms (ev/help-timeout (:help-timeout-ms timings)))})]
          (if (= :done outcome)
            (let [t (doc-text replies form)]
              (cljd/no-docs sym (if (str/blank? t) (str "No docs for " sym) t)))
            (cljd/no-docs sym timeout-doc)))
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
  (complete [_ prefix text pos extra-context]
    (try (cljd/candidates (:profile selection) prefix text pos extra-context)
         (catch Throwable _ [])))
  (lookup [this sym _text _pos]
    (try (meta-lookup this sym nil)
         (catch Throwable _ (cljd/no-docs sym timeout-doc))))
  (limitations [_] (if (= :browser (:cljs-target selection))
                     cljs-node/browser-limitations
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

(def settle-deadline-ms
  ;; estimate: a launch notices a Stop within its poll, then kills the tree;
  ;; the rest is slack (same as jonquil.nrepl.jank).
  5000)

(defn settled?
  "True once no evaluation (and so no launch) and no background kill is in
  flight."
  [ev]
  (let [s @(:state ev)]
    (and (nil? (:running s)) (zero? (:killing s)))))

(defn child-pid [ev] (:pid (:child @(:state ev))))

(defn run-dir [ev] (:run-dir (:child @(:state ev))))

(defmethod ev/make-launched :cljs [selection] (make-cljs-node selection))
