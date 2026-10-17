(ns jonquil.nrepl.shadow
  "The ClojureScript attach evaluator: Jonquil connects to the shadow-cljs
  nREPL server the user already started (it writes its port to
  .shadow-cljs/nrepl.port) and never starts, stops or signals it.

  A fresh session is CLJ. The first evaluation after a connect (and the
  first after :repl/quit) selects the build with
  (shadow.cljs.devtools.api/nrepl-select :build), which moves the session to
  cljs.user. The build is resolved on every connect attempt from
  .jonquil.edn's :cljs :shadow-build or the project's shadow-cljs.edn, so an
  edit takes effect on the next connect.

  Run is one eval of the whole buffer with no :ns (the buffer's own ns form
  decides). Shadow answers one :value per form and keeps going after a
  throw, so a Run never fails on per-form errors: it ends with a summary
  line and the errors themselves have already streamed to :err. A REPL
  entry, Run Selection and Run Form are eval with an explicit :ns (the
  buffer's ns form for a selection or form, else the tracked prompt
  namespace); a throw there is the :repl/exception! value, and its :err text
  becomes the error result.

  Stop detaches, as for ClojureDart: it never sends interrupt. The
  connection lifecycle itself lives in jonquil.nrepl.connection."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.cljd :as cljd]
            [jonquil.cljs :as cljs]
            [jonquil.clj :as clj]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.ops :as ops]))

(def default-timings
  {:describe-timeout-ms 5000
   :clone-timeout-ms 10000
   :still-waiting-ms 30000
   :help-timeout-ms 300})

(def busy-warning
  ;; jonquil.eval/eval-async's own wording, so the console reads the same.
  "; [Warning: Evaluation already in progress. Press Stop to cancel.]\n")

(def lost-connection "Lost the connection to the shadow-cljs server.")

(def no-eldoc-doc
  "No docs: this shadow-cljs server doesn't answer eldoc")

(defn- stop-requested?
  "Delegates to connection/stop-requested?, kept local so a test's
  with-redefs on this var reaches every guard connect! checks (through the
  policy's :stop-requested? key below)."
  [ev]
  (connection/stop-requested? ev))

(def ^:private cljs-policy
  {:messages {:no-project cljs/no-project-message :not-running cljs/not-running-message
              :bad-port-file cljs/bad-port-file-message :refused cljs/refused-message
              :not-server cljs/not-server-message :connecting cljs/connecting-message}
   :on-connect {:session-ns "shadow.user" :selected? false}
   :stop-requested? (fn [ev] (stop-requested? ev))})

(defn- request!
  "A call through connection/request!, with no still-waiting sentence
  (Stop detaches, so there's nothing to wait out silently)."
  [ev msg on-output]
  (connection/request! ev msg {:on-output on-output :still-waiting nil}))

(defn- detach!
  "connection/detach! plus the part it can't know: the next evaluation has
  to select the build again."
  [ev]
  (connection/detach! ev)
  (swap! (:state ev) assoc :selected? false))

(defn- finish!
  "Ends the running evaluation: idle first, then on-complete, as
  jonquil.eval does. result is already fully resolved by the caller."
  [{:keys [state]} {:keys [on-status-change on-complete]} result]
  (swap! state assoc :running nil)
  (try (when on-status-change (on-status-change :idle)) (catch Throwable _ nil))
  (try (when on-complete (on-complete result)) (catch Throwable _ nil)))

(defn- read-shadow-edn
  "The project's shadow-cljs.edn text, or nil when there's none."
  [dir]
  (let [f (io/file dir "shadow-cljs.edn")]
    (when (.exists f) (try (slurp f) (catch Exception _ nil)))))

(defn- err-text [msgs]
  (apply str (keep :err msgs)))

(defn- lost-result
  "The result for a request whose connection went away: Stop reads as
  interrupted, anything else as the lost-connection error."
  [ev]
  (if (stop-requested? ev)
    {:status :interrupted}
    {:status :error :error-text lost-connection}))

(defn- select!
  "Selects build on the session. nil once selected, else the result to
  finish with. A failed select leaves the connection up (the CLJ session is
  fine) and :selected? false."
  [{:keys [state] :as ev} build on-output]
  (let [msgs (request! ev {:op "eval" :code (cljs/select-code build)} on-output)]
    (cond
      ((ops/statuses msgs) ops/disconnected)
      (do (swap! state assoc :selected? false) (lost-result ev))

      (some #(cljs/selected-value? build %) (keep :value msgs))
      (do (swap! state assoc :selected? true) nil)

      (stop-requested? ev) {:status :interrupted}

      :else {:status :notice :text (cljs/select-failed-message (err-text msgs) build)})))

(defn- prepare!
  "Resolves the build, connects when needed and selects the build when the
  session hasn't. nil when the session is ready for the user's code, else
  the result to finish with."
  [{:keys [state selection] :as ev} on-output]
  (let [dir (:project-dir selection)
        {:keys [selected? build]} @state
        resolved (if (and dir (or (not (connection/live? ev)) (not selected?)))
                   (cljs/shadow-build (:cljs selection) (read-shadow-edn dir))
                   {:build build})]
    (if (:notice resolved)
      {:status :notice :text (:notice resolved)}
      (do (swap! state assoc :build (:build resolved))
          (or (connection/connect! ev on-output cljs-policy)
              (when-not (stop-requested? ev)
                (when-not (:selected? @state) (select! ev (:build resolved) on-output))))))))

(defn- quit!
  "Marks the session as no longer selected when msgs show it left CLJS: the
  quit value, or shadow's \"Exited CLJS session\" notice on :err."
  [{:keys [state]} msgs]
  (when (or (some cljs/quit-value? (keep :value msgs))
            (some cljs/quit-err? (keep :err msgs)))
    (swap! state assoc :selected? false)))

(defn- run-result
  "A Run's eval replies as one result: the summary as a quiet notice, never
  an error for per-form exceptions (their :err text already streamed)."
  [{:keys [state] :as ev} msgs]
  (let [st (ops/statuses msgs)
        errs (keep :err msgs)
        values (vec (keep :value msgs))]
    (cond
      (st ops/disconnected) (lost-result ev)
      (some cljs/no-runtime? errs) {:status :notice :text cljs/no-runtime-message}
      (st "eval-error") {:status :error :error-text (cljs/error-line (err-text msgs))}
      :else (do (quit! ev msgs)
                {:status :notice :text (cljs/run-summary (:session-ns @state) values)}))))

(defn- entry-result
  "A REPL entry's, selection's or form's eval replies as one result."
  [ev msgs]
  (let [st (ops/statuses msgs)
        values (vec (keep :value msgs))]
    (cond
      (st ops/disconnected) (lost-result ev)
      (some cljs/no-runtime? (keep :err msgs)) {:status :notice :text cljs/no-runtime-message}
      (cljs/exception-value? (last values)) {:status :error :error-text (cljs/error-line (err-text msgs))}
      :else (do (quit! ev msgs)
                (ops/eval-result msgs false lost-connection)))))

(defn- run-worker!
  [{:keys [state] :as ev} code {:keys [run? ns-source on-output on-status-change] :as opts}]
  (try
    (when on-status-change (on-status-change :running))
    (if-let [problem (prepare! ev on-output)]
      (finish! ev opts problem)
      (if (stop-requested? ev)
        (finish! ev opts {:status :interrupted})
        (if run?
          (finish! ev opts (run-result ev (request! ev {:op "eval" :code code} on-output)))
          (let [ns (or (when ns-source (clj/buffer-ns ns-source))
                       (:session-ns @state) "cljs.user")]
            (finish! ev opts (entry-result ev (request! ev {:op "eval" :code code :ns ns} on-output)))))))
    (catch Throwable e
      (finish! ev opts {:status :error :error-text (str "Error: " (ex-message e))}))))

;; --- quick doc and completion ----------------------------------------------

(defn- eldoc-arglists [arglists]
  (when (seq arglists)
    (str "(" (str/join " " (map (fn [a] (str "[" (str/join " " a) "]")) arglists)) ")")))

(defn- eldoc-info
  "A jonquil.docs/symbol-doc-shaped map from an eldoc reply, or nil when it
  names nothing."
  [sym msgs]
  (when-let [m (some (fn [m] (when (:name m) m)) msgs)]
    {:status :found
     :symbol sym
     :ns (:ns m)
     :name (:name m)
     :arglists (eldoc-arglists (:eldoc m))
     :doc (:docstring m)}))

(defn- eldoc! [{:keys [conn session session-ns]} sym text timeout-ms]
  (client/request-sync conn {:op "eldoc" :sym sym :session session
                             :ns (clj/help-ns text (or session-ns "cljs.user"))}
                       timeout-ms))

(defrecord Shadow [selection state history listeners timings]
  ev/Evaluator
  ;; Connecting happens on demand, when an evaluation finds no live
  ;; connection: nothing to start.
  (start! [_] nil)
  (stop! [this]
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
    ;; Stop detaches. It never sends interrupt: the server keeps whatever it
    ;; already has, and the next evaluation reconnects and selects again.
    (let [[old _] (swap-vals! state (fn [s] (cond-> s (:running s) (assoc-in [:running :stop-requested?] true))))]
      (if-let [running (:running old)]
        (when-not (:stop-requested? running)
          (connection/say! (:on-output (:opts running)) :system (str cljs/detached-message "\n"))
          (detach! this))
        (when (:conn old)
          (detach! this))))
    nil)
  (evaluating? [_] (some? (:running @state)))
  (waiting-for-input? [_] false)
  (send-input! [_ _] nil)
  (send-eof! [_] nil)
  (history [_] history)
  (prompt-ns [_] (symbol (or (:session-ns @state) "cljs.user")))
  (set-mode! [_ _] nil)
  ;; Offline candidates, plus the server's own when connected and it
  ;; advertises complete, bounded by :help-timeout-ms since this runs on the
  ;; GTK thread. Any throw, a timeout or no connection falls back to offline.
  (complete [_ prefix text pos extra-context]
    (let [offline (try (cljd/candidates (:profile selection) prefix text pos extra-context)
                       (catch Throwable _ []))]
      (try
        (let [{:keys [conn session ops session-ns]} @state]
          (if (and (client/open? conn) (contains? ops "complete"))
            (let [r (client/request-sync conn {:op "complete" :prefix prefix
                                               :ns (clj/help-ns text (or session-ns "cljs.user"))
                                               :session session}
                                         (:help-timeout-ms timings))]
              (if (vector? r)
                (ops/merge-candidates prefix offline (ops/candidates r))
                offline))
            offline))
        (catch Throwable _ offline))))
  ;; Backed by cider's eldoc op, never connecting on its own.
  (lookup [_ sym text _pos]
    (try
      (let [{:keys [conn ops] :as s} @state]
        (cond
          (not (client/open? conn)) (cljd/no-docs sym cljd/not-attached-doc)
          (not (contains? ops "eldoc")) (cljd/no-docs sym no-eldoc-doc)
          :else
          (let [r (eldoc! s sym text (:help-timeout-ms timings))]
            (cond
              (not (vector? r)) (cljd/no-docs sym cljd/lookup-timeout-doc)
              ((ops/statuses r) ops/disconnected) (cljd/no-docs sym cljd/not-attached-doc)
              :else (or (eldoc-info sym r) (cljd/no-docs sym (str "No docs for " sym)))))))
      (catch Throwable _ nil)))
  (limitations [_]
    ["Stop detaches: there's no interrupt, so the build's runtime keeps running whatever it already has."
     "No stdin."
     "Reset on Run isn't available: every Run adds to the running build."
     "Run needs the build loaded in a JS runtime (open the app, or run the node script)."])

  ev/FullDoc
  (full-doc [_ sym text on-result]
    (try
      (let [{:keys [conn ops] :as s} @state]
        (cond
          (not (client/open? conn)) (on-result {:status :not-attached})
          (not (contains? ops "eldoc")) (on-result {:status :no-op})
          :else
          (future
            (try
              (let [r (eldoc! s sym text (:help-timeout-ms timings))]
                (on-result
                 (cond
                   (not (vector? r)) {:status :timeout}
                   ((ops/statuses r) ops/disconnected) {:status :not-attached}
                   :else (if-let [d (eldoc-info sym r)]
                           {:status :ok
                            :text (str/join "\n" (remove str/blank? [(str (:ns d) "/" (:name d))
                                                                      (:arglists d)
                                                                      (:doc d)]))}
                           {:status :not-found}))))
              (catch Throwable _ (on-result {:status :timeout}))))))
      (catch Throwable _ (on-result {:status :timeout})))
    nil)

  connection/Attached
  (connected? [this] (connection/live? this))
  (on-connection! [this f] (connection/add-listener! this f)))

(defn make-shadow
  "A not yet connected shadow-cljs attach evaluator for a
  jonquil.dialect/select result. timings overrides default-timings (tests
  shorten them)."
  ([selection] (make-shadow selection {}))
  ([selection timings]
   (->Shadow selection
             (atom {:running nil :conn nil :connecting nil :session nil :ops #{}
                    :session-ns nil :selected? false :build nil})
             (atom (eval/create-history))
             (atom [])
             (merge default-timings timings))))

(defmethod connection/make-attached :cljs [selection] (make-shadow selection))
