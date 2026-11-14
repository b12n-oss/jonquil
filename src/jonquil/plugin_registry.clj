(ns jonquil.plugin-registry
  "The live plugin registry: one atom, live, of
  {:plugins {id {:definition :source :file :layer}}}, and the tables the key
  handlers and the palette read from a registry value: the plugins' :edit
  commands as registry commands, their bindings per scope in plugin-id
  order, and each controller's bindings, the plugins' ahead of the shipped
  ones. With no plugins those are exactly the shipped tables. Pure apart
  from live, and from effects, which a :run command reads when it starts."
  (:require [jonquil.command-registry :as cmd-registry]
            [jonquil.console :as console]
            [jonquil.evaluator :as ev]
            [jonquil.keymap :as km]
            [jonquil.plugin :as p]
            [jonquil.plugin-check :as plugin-check]
            [jonquil.plugin-protocol :as protocol]
            [jonquil.user-keys :as user-keys]))

(defn empty-registry [] {:plugins {}})

(defonce live (atom (empty-registry)))

(defn install
  "reg with id's entry {:definition :source :file :layer} replaced."
  [reg id entry]
  (assoc-in reg [:plugins id] entry))

(defn remove-plugin [reg id]
  (update reg :plugins dissoc id))

;; The window's effect functions for :run commands: {:active-tab (fn [] id),
;; called on the GTK thread; :print! :status! :buffer-text :replace-text!
;; :open! :eval! :repl!, jonquil.plugin's implementations, called from a
;; worker; :report!
;; (fn [line]) for a throw}. Empty until a window sets it.
(defonce effects (atom {}))

(defonce ^:private running (atom #{}))

(defn gui-turn
  "Runs (f) on the GTK thread through gui, the enqueue fn, and answers its
  value, as jonquil.mcp.window/gui-call does, except that a turn that
  missed deadline-ms is abandoned: the thunk, if GTK runs it later, does
  nothing. So an effect reported as \"The window did not respond.\" never
  lands afterwards. A thunk already running at the deadline is waited
  for. f's throw comes back as an ex-info with its message."
  [gui f deadline-ms]
  (let [state (atom :waiting)
        p (promise)]
    (gui (fn []
           (when (compare-and-set! state :waiting :running)
             (deliver p (try {:ok (f)}
                             (catch Throwable e {:err (or (ex-message e) (str e))}))))))
    (let [r (deref p deadline-ms ::timeout)
          r (if (and (= ::timeout r) (not (compare-and-set! state :waiting :abandoned)))
              @p
              r)]
      (cond
        (= ::timeout r) (throw (ex-info "The window did not respond." {}))
        (contains? r :err) (throw (ex-info (:err r) {}))
        :else (:ok r)))))

(def ^:private effect-impls
  ;; the implementations each effect brings into a ctx
  {:console [:print! :status!]
   :buffer [:buffer-text :replace-text! :open!]
   :eval [:eval! :repl!]})

(defn effect-ctx
  "The ctx plugin code gets: the declared effects, only their
  implementations from fx (an effects value), and tab-id as the tab it
  acts on."
  [fx declared tab-id]
  {::p/effects declared
   ::p/impl (select-keys fx (mapcat effect-impls declared))
   ::p/tab tab-id})

(defn quiet-capable?
  "True when ev can run p/eval!'s quiet eval (jonquil.evaluator/QuietEval)."
  [ev]
  (satisfies? ev/QuietEval ev))

(defn- eval-callbacks
  "The {:on-output :on-wait-input :on-complete} handed to the evaluator.
  Output lands in the out and err atoms, or goes to on-notice for Jonquil's
  own lines; a console read delivers ::input to p when input-stops?."
  [{:keys [p out err on-notice input-stops?]}]
  {:on-output (fn [k text]
                (case k
                  :out (swap! out conj text)
                  :err (swap! err conj text)
                  ;; Jonquil's own lines (a Stop's restart,
                  ;; auto-require) are for the person
                  (when on-notice (on-notice k text))))
   :on-wait-input (fn [waiting?] (when (and waiting? input-stops?) (deliver p ::input)))
   :on-complete (fn [r] (deliver p r))})

(defn- eval-answer
  "The {:value :out :err & [:error]} for a completed eval result r."
  [r out err]
  (let [base {:value nil :out (apply str @out) :err (apply str @err)}]
    (case (:status r)
      :ok (assoc base :value (:printed r))
      :interrupted (assoc base :error "the eval was interrupted")
      ;; a dialect saying it can't run this now (no runtime, the app
      ;; asleep): its sentence
      :notice (assoc base :error (:text r))
      (assoc base :error (console/error-text r)))))

(defn await-eval!
  "Runs one plugin eval and waits for it, on the plugin's worker. (fire
  callbacks) starts it, handing the evaluator {:on-output :on-wait-input
  :on-complete}, and answers nil, or the evaluator's busy warning. Answers
  {:value printed :out :err}, or with :error (the console's error text) and
  :value nil when the code threw or the dialect answered a :notice. Output
  that isn't the code's :out or :err (Jonquil's own lines: a Stop that
  restarted the runtime, auto-require) goes to (on-notice kind text).
  Throws \"the REPL is busy\"; on a
  timeout (timeout-ms, default 10000) or with :input-stops? (the default)
  a read from the console, it calls (interrupt!) and throws, saying which."
  [{:keys [fire interrupt! timeout-ms input-stops? on-notice] :or {input-stops? true}}]
  (let [timeout-ms (or timeout-ms 10000)
        _ (when-not (and (number? timeout-ms) (pos? timeout-ms))
            (throw (ex-info ":timeout-ms must be a positive number of milliseconds" {})))
        p (promise)
        out (atom [])
        err (atom [])
        busy (fire (eval-callbacks {:p p :out out :err err :on-notice on-notice :input-stops? input-stops?}))]
    (when busy (throw (ex-info "the REPL is busy" {})))
    (let [r (deref p timeout-ms ::timeout)]
      (case r
        ::timeout (do (interrupt!)
                      (throw (ex-info (str "the eval took longer than " timeout-ms " ms and was stopped") {})))
        ::input (do (interrupt!)
                    (throw (ex-info "the eval asked for console input, so it was stopped" {})))
        (eval-answer r out err)))))

(defn- in-order [reg] (sort-by key (:plugins reg)))

(defn- edit-command
  "A registry command running a plugin's :edit: the op it returns on the
  ctx's snapshot is checked against that snapshot (the soak only saw
  fixtures) and applied (:handled); nil leaves the key to GTK as a built-in
  edit that changed nothing does (:to-gtk). A throw, a bad op (nothing is
  applied) or a failed apply comes back naming the plugin, for the
  handler's catch to report."
  [plugin-id edit]
  (fn [ctx _args]
    (let [fail (fn [msg e] (throw (ex-info (str "plugin " (name plugin-id) ": " msg) {:plugin plugin-id} e)))
          snap ((:snapshot ctx))
          op (try (edit snap) (catch Throwable e (fail (or (ex-message e) (str e)) e)))]
      (if (nil? op)
        :to-gtk
        (if-let [why (binding [*print-length* 10 *print-level* 3]
                       (protocol/edit-result-problem snap op))]
          (fail why nil)
          (do (try ((:apply-op! ctx) op) (catch Throwable e (fail (or (ex-message e) (str e)) e)))
              :handled))))))

(defn- run-command
  "A registry command starting a plugin's :run (fn [ctx]) on a worker and
  answering :handled at once. The ctx holds the tab active now and only
  the declared effects' functions from effects; a throw is reported with
  the plugin's id. One run per command at a time: a press while the last
  is still going throws, naming it, for the handler's catch to report
  (nothing can stop a busy run, so a copy per press would keep a core
  each)."
  [plugin-id cid declared run]
  (fn [_ctx _args]
    (let [label (str "plugin " (name plugin-id) ": ")
          [before _] (swap-vals! running conj cid)]
      (when (contains? before cid)
        (throw (ex-info (str label cid " is still running") {:plugin plugin-id})))
      (try
        (let [fx @effects
              ctx (effect-ctx fx declared (when-let [f (:active-tab fx)] (f)))]
          (future
            (try (run ctx)
                 (catch Throwable e
                   (when-let [report! (:report! fx)]
                     (report! (str label (or (ex-message e) (str e))))))
                 (finally (swap! running disj cid)))))
        (catch Throwable e
          ;; nothing started, so the command is free again
          (swap! running disj cid)
          (throw e)))
      :handled)))

(defn commands
  "{command-id {:doc :scope :plugin :run}} for every installed plugin's
  :edit and :run commands."
  [reg]
  (into {}
        (for [[id {:keys [definition]}] (in-order reg)
              {cid :id :keys [doc scope edit run]} (:commands definition)
              :when (or (fn? edit) (fn? run))]
          [cid {:doc doc :scope scope :plugin id
                :run (if (fn? edit)
                       (edit-command id edit)
                       (run-command id cid (set (:effects definition)) run))}])))

(def PLUGIN-TOOL-DEADLINE-MS
  ;; estimate: the built-in window tools' default timeout_ms
  30000)

;; the served names of plugin MCP tools with a call still running
(defonce ^:private tools-running (atom #{}))

(def ^:private arg-type-ok
  {"string" string? "integer" integer? "number" number? "boolean" boolean?})

(defn- arg-problem
  "The error for args against a row's :args (a missing required one, or a
  value of the wrong type), the built-in tools' wording, or nil. Extra
  arguments pass through."
  [args spec]
  (some (fn [[k {:keys [type required?]}]]
          (let [v (get args k)
                ok? (arg-type-ok type)]
            (cond
              (and required? (nil? v)) (str "Missing required argument: " k)
              (and (some? v) ok? (not (ok? v)))
              (str "Argument " k " must be " (if (= "integer" type) "an " "a ") type "."))))
        (sort-by key spec)))

(defn- tool-def [served {:keys [doc args]}]
  {"name" served "description" doc
   "inputSchema" {"type" "object"
                  "properties" (into {} (for [[k {:keys [type doc]}] args] [k {"type" type "description" doc}]))
                  "required" (vec (sort (for [[k a] args :when (:required? a)] k)))}})

(defn- tool-answer
  "A :run's answer as a tool result: a string, or a map whose :text is one."
  [label served r]
  (cond
    (string? r) {:text r :error? false}
    (and (map? r) (string? (:text r))) {:text (:text r) :error? (boolean (:error? r))}
    :else {:text (str label served " answered " (binding [*print-length* 10 *print-level* 3] (pr-str r)) ", not text")
           :error? true}))

(defn- failure-result
  "A tool result for a throwable, in the plugin's label."
  [label e]
  {:text (str label (or (ex-message e) (str e))) :error? true})

(defn- start-tool-future
  "The future running row's :run for one call, its answer made a result
  there too. Frees served again if nothing started."
  [{:keys [label served row declared args]}]
  (try
    (let [fx @effects
          ;; only a :buffer tool needs the
          ;; tab, so only it waits on the GTK thread
          tab (when (contains? declared :buffer)
                (when-let [t (:active-tab-turn fx)] (t)))
          ctx (effect-ctx fx declared tab)]
      ;; the answer is turned into a result
      ;; here too, since realizing a lazy one runs
      ;; plugin code, so it's under the deadline
      (future (try (tool-answer label served ((:run row) ctx args))
                   (catch Throwable e (failure-result label e))
                   (finally (swap! tools-running disj served)))))
    (catch Throwable e
      ;; nothing started, so the tool is free again
      (swap! tools-running disj served)
      (throw e))))

(defn- run-tool-within
  "The result of one call of row, started on a future and awaited for
  deadline-ms."
  [{:keys [label served deadline-ms] :as call}]
  (let [v (deref (start-tool-future call) deadline-ms ::timeout)]
    (if (= ::timeout v)
      {:text (str label served " didn't answer in " (quot deadline-ms 1000) " s; it may still be running")
       :error? true}
      v)))

(defn- tool-call
  "The call fn of plugin-id's tool row, served as served: its args checked,
  then :run on a future with a ctx of the declared effects and, for a
  :buffer tool, the active tab (read through :active-tab-turn, safe off
  the GTK thread), its answer made a result there too, answered within
  deadline-ms. One call per tool at a time, as run-command's one
  run per command, since nothing can stop one that overran. Never throws."
  [{:keys [plugin-id served row declared deadline-ms]}]
  (fn [args]
    (let [label (str "plugin " (name plugin-id) ": ")]
      (try
        (if-let [why (arg-problem args (:args row))]
          {:text why :error? true}
          (let [[before _] (swap-vals! tools-running conj served)]
            (if (contains? before served)
              {:text (str label served " is still running") :error? true}
              (run-tool-within {:label label :served served :row row :declared declared
                                :args args :deadline-ms deadline-ms}))))
        (catch Throwable e
          (failure-result label e))))))

(defn mcp-tools
  "Every installed plugin's MCP tools, in plugin-id then row
  order: {:name served :plugin id :def mcp-tool-def :call (fn [args]
  {:text :error?})}, served as plugin_<id>_<name>. opts :deadline-ms
  (default PLUGIN-TOOL-DEADLINE-MS) is a test knob."
  ([reg] (mcp-tools reg {}))
  ([reg {:keys [deadline-ms]}]
   (vec (for [[id {:keys [definition]}] (in-order reg)
              row (:mcp-tools definition)
              :when (and (map? row) (string? (:name row)) (fn? (:run row)))
              :let [served (plugin-check/served-tool-name (name id) (:name row))]]
          {:name served :plugin id :def (tool-def served row)
           :call (tool-call {:plugin-id id :served served :row row :declared (set (:effects definition))
                             :deadline-ms (or deadline-ms PLUGIN-TOOL-DEADLINE-MS)})}))))

(defn- tool-defs [reg] (mapv :def (mcp-tools reg)))

(defn watch-tools!
  "Watches registry atom a and calls (notify!) on the swapping thread when
  a swap changes the served plugin tools' definitions: a
  tool added, removed or renamed, or a changed doc or argument. A throw
  from the comparison or from notify! is dropped, so the swap stands.
  Returns a fn that removes the watch."
  [a notify!]
  (let [k (keyword "jonquil.plugin-registry" (str "tools-" (gensym)))]
    (add-watch a k (fn [_ _ old new]
                     (try (when (not= (tool-defs old) (tool-defs new))
                            (notify!))
                          (catch Throwable _ nil))))
    (fn [] (remove-watch a k))))

(defn all-commands
  "The built-in commands and reg's plugin commands, for the palette."
  [reg]
  (merge cmd-registry/commands (commands reg)))

(defn bindings-with-source
  "bindings, each tagged :plugin/source with the plugin that made it, for
  the validator to name it (the loader's request)."
  [reg scope]
  (let [known (all-commands reg)]
    (vec (for [[id {:keys [definition]}] (in-order reg)
               b (:bindings definition)
               :when (= scope (:scope (get known (:command b))))]
           (assoc b :plugin/source id)))))

(defn bindings
  "The installed plugins' bindings in scope, ordered by plugin id then file
  order, each one whose command is a live plugin command or a built-in of
  that scope."
  [reg scope]
  (mapv #(dissoc % :plugin/source) (bindings-with-source reg scope)))

(defn window-bindings
  "The live window bindings: your own (jonquil.user-keys/live) first, then
  reg's plugins', then the shipped ones, less the ones yours remove."
  [reg]
  (user-keys/layer @user-keys/live :window (into (bindings reg :window) (:window km/shipped))))

(defn editor-bindings
  "The live editor bindings, in window-bindings' order."
  [reg]
  (user-keys/layer @user-keys/live :editor (into (bindings reg :editor) (:editor km/shipped))))
