(ns jonquil.mcp.window
  "The nineteen MCP tools over a live Jonquil window, four of them over its
  plugins folder (through jonquil.plugin-mcp).

  `make-window-tools` returns the same table shape as jonquil.mcp.tools:
  `{:defs [mcp-tool-def ...] :call (fn [name args] {:text s :error? b})}`,
  consumed by the unchanged jonquil.mcp/serve!. args has string keys; :call
  may throw, and jonquil.mcp turns a throw into an error result.

  The ui map is the whole window surface (the :on-ready map), so this
  namespace never requires jonquil.gtk.window. Every ui touch from the serve
  thread goes through `gui-call`: a thunk enqueued with a bounded deref. A
  notify callback runs on the eval worker and only delivers a promise."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.help :as help]
            [jonquil.mcp :as mcp]
            [jonquil.mcp.describe :as describe]
            [jonquil.mcp.tools :as tools]
            [jonquil.plugin-mcp :as plugin-mcp]
            [jonquil.tabs :as tabs]))

(def ^:private gui-deadline-ms 5000)
(def ^:private default-grace-ms 5000)
(def ^:private slice-ms 100)
(def ^:private max-completions 50)

(defn- prop [type description] {"type" type "description" description})

(def ^:private plugin-id-doc "The plugin id: lowercase letters, digits and dashes, starting with a letter.")

(def window-tool-defs
  [{"name" "jonquil_eval"
    "description" "Evaluate code as a REPL entry at the window's console prompt: echoed, visible, in the history as if typed."
    "inputSchema" {"type" "object"
                   "properties" {"code" (prop "string" "The code to evaluate.")
                                 "timeout_ms" (prop "integer" "Deadline in milliseconds. Default 30000.")}
                   "required" ["code"]}}
   {"name" "jonquil_run"
    "description" "Run the active tab the way the Run button does: banner, bracket check, reset-on-run as configured."
    "inputSchema" {"type" "object"
                   "properties" {"timeout_ms" (prop "integer" "Deadline in milliseconds. Default 30000.")}
                   "required" []}}
   {"name" "jonquil_run_selection"
    "description" "Select a character range in the active tab (visible) and run it as Cmd/Ctrl+E does."
    "inputSchema" {"type" "object"
                   "properties" {"start" (prop "integer" "Start offset, in characters.")
                                 "end" (prop "integer" "End offset, in characters.")
                                 "timeout_ms" (prop "integer" "Deadline in milliseconds. Default 30000.")}
                   "required" ["start" "end"]}}
   {"name" "jonquil_stop"
    "description" "Interrupt the running evaluation; reports whether it stopped within 5 seconds."
    "inputSchema" {"type" "object" "properties" {} "required" []}}
   {"name" "jonquil_open"
    "description" "Open a file as a tab, or select the tab already showing it. It becomes the active tab."
    "inputSchema" {"type" "object"
                   "properties" {"path" (prop "string" "The file to open.")}
                   "required" ["path"]}}
   {"name" "jonquil_tabs"
    "description" "One line per tab: id, an active marker, dirty or saved, the path or Untitled."
    "inputSchema" {"type" "object" "properties" {} "required" []}}
   {"name" "jonquil_select_tab"
    "description" "Make a tab the active one."
    "inputSchema" {"type" "object"
                   "properties" {"id" (prop "integer" "The tab id from jonquil_tabs.")}
                   "required" ["id"]}}
   {"name" "jonquil_read_tab"
    "description" "A tab's full text. Default: the active tab."
    "inputSchema" {"type" "object"
                   "properties" {"id" (prop "integer" "The tab id from jonquil_tabs.")}
                   "required" []}}
   {"name" "jonquil_replace_text"
    "description" "Replace a tab's whole text as one undoable edit. The tab reads dirty until saved."
    "inputSchema" {"type" "object"
                   "properties" {"text" (prop "string" "The new text.")
                                 "id" (prop "integer" "The tab id from jonquil_tabs. Default: the active tab.")}
                   "required" ["text"]}}
   {"name" "jonquil_save"
    "description" "Save the active tab to path, or to its own path."
    "inputSchema" {"type" "object"
                   "properties" {"path" (prop "string" "Where to save. Default: the tab's own path.")}
                   "required" []}}
   {"name" "jonquil_console"
    "description" "The console's output text from an offset to the current boundary, after a first line `mark <N>` naming that boundary. Pass a previous mark as since to read only what is new."
    "inputSchema" {"type" "object"
                   "properties" {"since" (prop "integer" "A character offset, usually a previous mark. Default 0.")}
                   "required" []}}
   {"name" "jonquil_doc"
    "description" "Documentation for a symbol from the window's runtime."
    "inputSchema" {"type" "object"
                   "properties" {"symbol" (prop "string" "The symbol to look up.")}
                   "required" ["symbol"]}}
   {"name" "jonquil_complete"
    "description" "Completion candidates for a symbol prefix, one per line, at most 50."
    "inputSchema" {"type" "object"
                   "properties" {"prefix" (prop "string" "The symbol prefix.")}
                   "required" ["prefix"]}}
   {"name" "jonquil_check_brackets"
    "description" "Check brackets in text or in a file. Answers ok, or names the first unclosed or unmatched bracket and its line."
    "inputSchema" {"type" "object"
                   "properties" {"text" (prop "string" "The text to check.")
                                 "path" (prop "string" "A file to check instead of text.")}
                   "required" []}}
   {"name" "jonquil_project"
    "description" "The window's dialect, runtime and project directory."
    "inputSchema" {"type" "object" "properties" {} "required" []}}
   {"name" "jonquil_plugins_list"
    "description" "Every plugin: its live version, whether it's loaded or disabled, its effects, any pending proposal, and its doc."
    "inputSchema" {"type" "object" "properties" {} "required" []}}
   {"name" "jonquil_plugin_read"
    "description" "A plugin's live source, or one version's, and its history: each version's status, source and reason."
    "inputSchema" {"type" "object"
                   "properties" {"id" (prop "string" plugin-id-doc)
                                 "version" (prop "integer" "A version number from its history. Default: the live version.")}
                   "required" ["id"]}}
   {"name" "jonquil_plugin_propose"
    "description" "Propose a plugin's whole new source with a reason. It's checked in a separate jolt process, never in Jonquil, and goes live only if the user accepts it in the window. Answers its status: pending, rejected-by-checks or refused."
    "inputSchema" {"type" "object"
                   "properties" {"id" (prop "string" plugin-id-doc)
                                 "source" (prop "string" "The whole plugin file.")
                                 "reason" (prop "string" "Why this version exists. It's kept in the plugin's history.")}
                   "required" ["id" "source" "reason"]}}
   {"name" "jonquil_plugin_status"
    "description" "A proposal's status, and the user's note when they rejected it. Default: the plugin's latest proposal."
    "inputSchema" {"type" "object"
                   "properties" {"id" (prop "string" plugin-id-doc)
                                 "n" (prop "integer" "The proposal's version number.")}
                   "required" ["id"]}}])

;; --- pure helpers ------------------------------------------------------------

(defn tab-line
  "One jonquil_tabs line: id, * for the active tab or -, dirty or saved,
  the path or Untitled."
  [{:keys [id active? dirty? path]}]
  (str id " " (if active? "*" "-") " " (if dirty? "dirty" "saved") " " (or path "Untitled")))

(defn clamp-since
  "since as an offset in [0, end]; nil, negative or past the end clamp."
  [since end]
  (if (number? since)
    (max 0 (min (long since) end))
    0))

(defn replace-op
  "The edit-op result that swaps all of old-text for new-text, in the shape
  editor/apply-op! takes."
  [old-text new-text]
  {:edits [[0 (count old-text) new-text]] :caret (count new-text)})

;; --- the gui turn ------------------------------------------------------------

(defn gui-call
  "Runs (f) on the GTK thread through gui, the enqueue fn, and returns its
  value. Throws f's exception message as an ex-info, or \"The window did not
  respond.\" when deadline-ms passes first (a destroyed window never runs
  the thunk, so this is also the window-closed detector). The catch lives
  inside the thunk because the drain swallows throws."
  [gui f deadline-ms]
  (let [p (promise)
        _ (gui (fn []
                 (deliver p (try {:ok (f)}
                                 (catch Throwable e {:err (or (ex-message e) (str e))})))))
        r (deref p deadline-ms ::timeout)]
    (cond
      (= ::timeout r) (throw (ex-info "The window did not respond." {}))
      (contains? r :err) (throw (ex-info (:err r) {}))
      :else (:ok r))))

;; --- argument readers --------------------------------------------------------

(defn- number-arg
  "args' number at k as a long, nil when absent; throws when present but not
  a number."
  [args k]
  (let [v (get args k)]
    (cond
      (nil? v) nil
      (number? v) (long v)
      :else (throw (ex-info (str "Argument " k " must be a number.") {})))))

(defn- required-number [args k]
  (or (number-arg args k)
      (throw (ex-info (str "Missing required argument: " k) {}))))

(defn- required-text
  "args' string at k. Unlike required-string, an empty string is allowed."
  [args k]
  (let [v (get args k)]
    (if (string? v)
      v
      (throw (ex-info (str "Missing required argument: " k) {})))))

;; --- evaluation --------------------------------------------------------------

(defn- notify->result
  "A notify map as a tool result: :done is result-text, the rest are errors."
  [m]
  (if-let [{:keys [result output]} (:done m)]
    {:text (tools/result-text output result) :error? (= :error (:status result))}
    {:text (str (or (:busy m) (:refused m) (:problem m) (:none m))) :error? true}))

(defn- now-ms [] (System/currentTimeMillis))

(defn- timed-out
  "await-eval past its deadline: stops the evaluation through a gui turn,
  then waits up to grace-ms more for p's notify. The timeout result."
  [{:keys [ui gui timeout-ms grace-ms]} p reader]
  (gui-call gui
            ;; as tool-stop: never interrupt an idle evaluator (an attach
            ;; evaluator would detach)
            (fn [] (when (ev/evaluating? ((:evaluator* ui))) ((:stop!* ui))))
            gui-deadline-ms)
  (let [r2 (deref p grace-ms ::pending)]
    (if (= ::pending r2)
      {:text (str (reader) "Timed out after " timeout-ms " ms; interrupted, but the evaluation has not stopped.")
       :error? true}
      {:text (str (or (:output (:done r2)) (reader)) "Timed out after " timeout-ms " ms; interrupted.")
       :error? true})))

(defn- await-eval
  "Fires a hook in one gui turn ((fire notify) returns the output reader) and
  waits for its notify: the result, or the early waiting-for-input result,
  or the timeout path (stop through a gui turn, then up to grace-ms more)."
  [{:keys [ui gui fire timeout-ms grace-ms]}]
  (let [p (promise)
        ;; Accepted property of the bounded deref: if this turn outlives its
        ;; deadline the tool throws, but the queued thunk may still run later
        ;; and start an evaluation whose notify delivers into this dead
        ;; promise; the next call then sees busy.
        reader (gui-call gui (fn [] (fire (partial deliver p))) gui-deadline-ms)
        deadline (+ (now-ms) timeout-ms)]
    (loop []
      (let [r (deref p (max 1 (min slice-ms (- deadline (now-ms)))) ::pending)]
        (cond
          (not= ::pending r) (notify->result r)

          (ev/waiting-for-input? ((:evaluator* ui)))
          {:text (str (reader) "\n; waiting for console input (a person can type in the window, or call jonquil_stop)")
           :error? false}

          ;; the notify may land between the slice's deref and here
          (and (>= (now-ms) deadline) (realized? p))
          (notify->result @p)

          (>= (now-ms) deadline)
          (timed-out {:ui ui :gui gui :timeout-ms timeout-ms :grace-ms grace-ms} p reader)

          :else (recur))))))

;; --- the tools ---------------------------------------------------------------

(defn- find-tab [ui id]
  (some #(when (= id (:id %)) %) ((:tabs* ui))))

(defn- no-tab [id] {:text (str "No tab with id " id ".") :error? true})

(defn- mark-offset
  "mark's current character offset in buf."
  [buf mark]
  (let [it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-buffer-get-iter-at-mark buf it mark)
      (g/gtk-text-iter-get-offset it)
      (finally (ffi/free it)))))

;; make-window-tools' tools, one named fn each. ctx is {:ui :dir :grace-ms
;; :turn :await!}: turn runs a thunk on the GUI thread, await! runs an eval.

(defn- tool-eval [{:keys [ui await!]} args]
  (let [code (tools/required-string args "code")]
    (await! args (fn [notify] ((:mcp-eval!* ui) code notify)))))

;; the tab a run runs, for the Messages entry's jump (a :tab-id
;; side field the agent never sees); a ui without tabs has none
(defn- active-id [{:keys [ui turn]}]
  (when-let [f (:active-tab* ui)] (turn (fn [] (:id (f))))))

(defn- with-tab [r id] (cond-> r id (assoc :tab-id id)))

(defn- tool-run [{:keys [ui await!] :as ctx} args]
  (let [id (active-id ctx)]
    (with-tab (await! args (fn [notify] ((:mcp-run!* ui) notify))) id)))

(defn- tool-run-selection [{:keys [ui await!] :as ctx} args]
  (let [start (required-number args "start")
        end (required-number args "end")
        id (active-id ctx)]
    (with-tab (await! args (fn [notify] ((:mcp-run-selection!* ui) start end notify))) id)))

(defn- wait-stopped
  "Polls until the evaluator stops or grace-ms passes; true when it stopped."
  [ui grace-ms]
  (let [deadline (+ (now-ms) grace-ms)]
    (loop []
      (cond
        (not (ev/evaluating? ((:evaluator* ui)))) true
        (>= (now-ms) deadline) false
        :else (do (Thread/sleep 50) (recur))))))

(defn- tool-stop [{:keys [ui turn grace-ms]} _]
  (let [running? (turn (fn []
                         (when (ev/evaluating? ((:evaluator* ui)))
                           ((:stop!* ui))
                           true)))]
    (if-not running?
      {:text "Nothing is running." :error? false}
      (let [stopped? (wait-stopped ui grace-ms)]
        {:text (if stopped?
                 "Stopped."
                 "Interrupt requested; the evaluation has not stopped yet.")
         :error? false}))))

(defn- tool-open [{:keys [ui dir turn]} args]
  (let [p (tools/absolute dir (tools/required-string args "path"))]
    (turn (fn []
            (let [r ((:open-file! ui) p)]
              (if (and (map? r) (:error r))
                {:text (str (:error r)) :error? true}
                (let [t ((:active-tab* ui))]
                  {:text (str "Opened tab " (:id t) " " (or @(:path t) "Untitled"))
                   :error? false :tab-id (:id t)})))))))

(defn- tool-tabs [{:keys [ui turn]} _]
  (turn (fn []
          (let [active (:id ((:active-tab* ui)))]
            {:text (str/join "\n"
                             (map (fn [t]
                                    (tab-line {:id (:id t)
                                               :active? (= active (:id t))
                                               :dirty? (editor/dirty? (:ed t))
                                               :path @(:path t)}))
                                  ((:tabs* ui))))
             :error? false}))))

(defn- tool-select [{:keys [ui turn]} args]
  (let [id (required-number args "id")]
    (turn (fn []
            (if (find-tab ui id)
              (do ((:select-tab! ui) id)
                  {:text (str "Selected tab " id ".") :error? false :tab-id id})
              (no-tab id))))))

(defn- tool-read [{:keys [ui turn]} args]
  (let [id (number-arg args "id")]
    (turn (fn []
            (let [t (if id (find-tab ui id) ((:active-tab* ui)))]
              (cond
                t {:text (editor/editor-text (:ed t)) :error? false}
                id (no-tab id)
                :else {:text "No active tab." :error? true}))))))

(defn- replace-in-tab
  "The replace_text result for tab t (nil when there is none) on the GUI thread."
  [t id text]
  (if-not t
    (if id (no-tab id) {:text "No active tab." :error? true})
    (let [ed (:ed t)]
      (if (editor/apply-op-plain! ed (replace-op (editor/editor-text ed) text))
        {:text (str "Replaced the text of tab " (:id t) ".") :error? false :tab-id (:id t)}
        {:text (str "Tab " (:id t) " is read-only.") :error? true :tab-id (:id t)}))))

(defn- tool-replace [{:keys [ui turn]} args]
  (let [text (required-text args "text")
        id (number-arg args "id")]
    ;; GTK text is C strings, so a NUL
    ;; would truncate the insert; apply-op! refuses such
    ;; an op, and this pre-check names the reason instead
    ;; of mislabelling the refusal as read-only.
    (if-let [n (when (string? text) (tabs/nul-line text))]
      {:text (str "The new text holds a NUL byte on line " n
                  ", which Jonquil's editor can't keep, so nothing was changed.")
       :error? true}
      (turn (fn []
              (replace-in-tab (if id (find-tab ui id) ((:active-tab* ui))) id text))))))

(defn- tool-save [{:keys [ui dir turn]} args]
  (let [arg-path (when (get args "path")
                   (tools/absolute dir (tools/required-string args "path")))]
    (turn (fn []
            (let [t ((:active-tab* ui))
                  p (or arg-path (some-> t :path deref))]
              (cond
                (not t) {:text "No active tab." :error? true}
                (not p) {:text "The active tab is Untitled; give a path." :error? true}
                :else
                (do ((:save-to! ui) p)
                    (if (editor/dirty? (:ed t))
                      {:text "The save failed; the console names the reason." :error? true :tab-id (:id t)}
                      {:text (str "Saved " p) :error? false :tab-id (:id t)}))))))))

(defn- tool-console [{:keys [ui turn]} args]
  (let [since (number-arg args "since")]
    (turn (fn []
            (let [cv (:console ui)
                  end (mark-offset (:buf cv) (:out-end cv))
                  text (g/buffer-text (:buf cv))]
              {:text (str "mark " end "\n" (subs text (clamp-since since end) end))
               :error? false})))))

(defn- tool-doc [{:keys [ui]} args]
  (let [sym (tools/required-string args "symbol")
        e ((:evaluator* ui))]
    {:text (help/doc-text sym (ev/lookup e sym sym (count sym))) :error? false}))

(defn- tool-complete [{:keys [ui]} args]
  (let [prefix (tools/required-string args "prefix")
        names (->> (ev/complete ((:evaluator* ui)) {:prefix prefix :text prefix :pos (count prefix) :extra-context nil})
                   (map #(if (map? %) (or (:name %) (:text %) (:symbol %)) %))
                   (remove nil?)
                   (map str)
                   (take max-completions))]
    {:text (if (seq names)
             (str/join "\n" names)
             (str "No completions for " prefix "."))
     :error? false}))

(defn- tool-brackets [{:keys [dir]} args] (tools/brackets-result dir args))

(defn- tool-project [{:keys [ui]} _] {:text (tools/selection-text ((:selection* ui))) :error? false})

;; reads go straight to the plugins folder; a propose
;; is refused here before the window's hook runs any check
(defn- tool-plugins-list [{:keys [ui]} _] (plugin-mcp/list-text ((:plugin-state* ui))))

(defn- tool-plugin-read [{:keys [ui]} args]
  (let [id (tools/required-string args "id")
        version (number-arg args "version")]
    (plugin-mcp/read-text (assoc ((:plugin-state* ui)) :id id :version version))))

(defn- tool-plugin-status [{:keys [ui]} args]
  (let [id (tools/required-string args "id")
        n (number-arg args "n")]
    (plugin-mcp/status-text (assoc ((:plugin-state* ui)) :id id :n n))))

(defn- tool-plugin-propose [{:keys [ui]} args]
  (let [p {:id (get args "id") :source (get args "source") :reason (get args "reason")}
        why (plugin-mcp/propose-refusal (assoc p :plugins? (:plugins? ((:plugin-state* ui)))))]
    (cond-> (plugin-mcp/propose-text (:id p)
                                     (if why
                                       {:status :refused :n nil :problems [why]}
                                       ((:plugin-propose!* ui) p)))
      (string? (:id p)) (assoc :plugin-id (:id p)))))

(defn- window-handlers
  "Tool name -> (fn [args]) over ctx."
  [ctx]
  (let [h (fn [f] (fn [args] (f ctx args)))]
    {"jonquil_eval" (h tool-eval)
     "jonquil_run" (h tool-run)
     "jonquil_run_selection" (h tool-run-selection)
     "jonquil_stop" (h tool-stop)
     "jonquil_open" (h tool-open)
     "jonquil_tabs" (h tool-tabs)
     "jonquil_select_tab" (h tool-select)
     "jonquil_read_tab" (h tool-read)
     "jonquil_replace_text" (h tool-replace)
     "jonquil_save" (h tool-save)
     "jonquil_console" (h tool-console)
     "jonquil_doc" (h tool-doc)
     "jonquil_complete" (h tool-complete)
     "jonquil_check_brackets" (h tool-brackets)
     "jonquil_project" (h tool-project)
     "jonquil_plugins_list" (h tool-plugins-list)
     "jonquil_plugin_read" (h tool-plugin-read)
     "jonquil_plugin_propose" (h tool-plugin-propose)
     "jonquil_plugin_status" (h tool-plugin-status)}))

(defn make-window-tools
  "The tool table over a live window. :ui is the :on-ready ui map, :dir the
  server's default directory (absolute), :gui the enqueue fn (default
  cb/on-gui!; tests pass a synchronous one), :grace-ms the wait after an
  interrupt and for Stop (default 5000; a test knob, as make-tools has)."
  [{:keys [ui dir gui grace-ms]}]
  (let [gui (or gui cb/on-gui!)
        grace-ms (or grace-ms default-grace-ms)
        await! (fn [args fire]
                 (await-eval {:ui ui :gui gui :fire fire :grace-ms grace-ms
                              :timeout-ms (tools/timeout-of args)}))
        handlers (window-handlers {:ui ui :dir dir :grace-ms grace-ms :await! await!
                                   :turn (fn [f] (gui-call gui f gui-deadline-ms))})]
    {:defs window-tool-defs
     :call (fn [tname args]
             (if-let [h (get handlers tname)]
               (h (or args {}))
               (throw (ex-info (str "Unknown tool: " tname) {}))))}))

;; --- serving -----------------------------------------------------------------

(defn with-plugin-tools
  "base (make-window-tools' table) with the window's live plugin tools
  after the built-ins: a fn answering a table, for
  jonquil.mcp/serve! to read on every message. (:plugin-tools* ui) answers
  jonquil.plugin-registry/mcp-tools' rows; it's read once per table, so one
  message's list and call agree. A ui without it serves base unchanged.
  When the ui has :watch-plugin-tools*, the table says
  :list-changed? true, so initialize declares the capability."
  [base ui]
  (fn []
    (let [plugins (if-let [f (:plugin-tools* ui)] (f) [])
          by-name (into {} (map (juxt :name :call)) plugins)]
      (cond-> {:defs (into (:defs base) (map :def) plugins)
               :call (fn [tname args]
                       (if-let [call (get by-name tname)]
                         (call (or args {}))
                         ((:call base) tname args)))}
        (:watch-plugin-tools* ui) (assoc :list-changed? true)))))

(defn client-name
  "What the Messages panel calls an MCP client: the name in its
  initialize's clientInfo, else \"Agent\"."
  [client-info]
  (let [n (when (map? client-info) (get client-info "name"))]
    (if (and (string? n) (not (str/blank? n))) n "Agent")))

(defn- log-call!
  "One Messages entry for a tool call. Never throws: a log that broke would
  break the agent's call it describes."
  [log! m]
  (try (log! (describe/describe-call m))
       (catch Throwable e
         (binding [*out* *err*]
           (println "jonquil: couldn't log an MCP call:" (or (ex-message e) (str e)))))))

(defn- logged-call
  "(f)'s result, logged through log! as base plus :result; a throw is
  logged as base plus :threw and re-thrown as itself."
  [log! base f]
  (let [r (try (f)
               (catch Throwable e
                 (log-call! log! (assoc base :threw (or (ex-message e) (str e))))
                 (throw e)))]
    (log-call! log! (assoc base :result r))
    r))

(defn with-messages
  "tables-fn (with-plugin-tools' fn answering a table per message) with
  every :call logged to the window's Messages through (:log!* ui), named
  by (:client @session*). What the agent gets back is unchanged: a result
  passes through, a throw is logged and re-thrown as itself. A ui without
  :log!* (a test's) gets tables-fn as it is."
  [tables-fn ui session*]
  (if-let [log! (:log!* ui)]
    (fn []
      (let [t (tables-fn)
            call (:call t)]
        (assoc t
               :on-unknown (fn [tname args]
                             (log-call! log! {:tool tname :args args :client (:client @session*)
                                              :threw (str "Unknown tool: " tname)}))
               :call (fn [tname args]
                       (logged-call log! {:tool tname :args args :client (:client @session*)}
                                    #(call tname args))))))
    tables-fn))

(defn- stdio-attached!
  "Records the client's name in session* and logs its attach (stdio's EOF
  closes the window, so only the attach is logged)."
  [ui session* client-info]
  (let [c (client-name client-info)]
    (swap! session* assoc :client c)
    (when-let [log! (:log!* ui)]
      (log! {:kind :connection :summary (str c " attached")}))))

(defn- serve-stdio!
  "Runs the MCP serve loop on read-line / write-line. A throw out of it is
  logged on stderr and swallowed so the caller still quits the window."
  [{:keys [ui dir read-line write-line gui]}]
  (try
    (let [session* (atom {})]
      (mcp/serve! {:tools (with-messages (with-plugin-tools (make-window-tools {:ui ui :dir dir :gui gui}) ui) ui session*)
                   :on-initialize (fn [ci] (stdio-attached! ui session* ci))
                   :subscribe (:watch-plugin-tools* ui)
                   :read-line read-line
                   :write-line write-line}))
    (catch Throwable e
      (binding [*out* *err*]
        (println "jonquil: the MCP serve loop failed:" (or (ex-message e) (str e)))))))

(defn- quit-window!
  "One gui turn that requests the window's close, answering the save prompt
  with Discard (index 1) when any tab is dirty. A throw is logged on stderr."
  [ui gui]
  (try
    (gui-call gui
              (fn []
                (let [dirty? (some #(editor/dirty? (:ed %)) ((:tabs* ui)))]
                  ((:request-close!* ui))
                  (when dirty?
                    ((:close-choice! ui) {:index 1}))))
              gui-deadline-ms)
    (catch Throwable e
      ;; a timeout means the window is already gone; anything else is a bug
      ;; worth a line on stderr
      (binding [*out* *err*]
        (println "jonquil: the quit turn failed:" (or (ex-message e) (str e)))))))

(defn serve-window!
  "Serves the window table over :read-line / :write-line until EOF, then quits
  the window, discarding unsaved edits: one gui turn that, when any tab is
  dirty, requests the close and answers the save prompt with index 1
  (Discard), else just requests the close. A gui timeout there is swallowed:
  the window may already be gone. A throw out of the serve loop (stdin
  failing, say) is logged on stderr and takes the same quit path, so the window
  closes instead of sitting there with no server."
  [{:keys [ui dir gui] :as opts}]
  (let [gui (or gui cb/on-gui!)]
    (binding [*out* *err*]
      (println "jonquil: MCP server driving the window on stdio, default directory" dir))
    (serve-stdio! (assoc opts :gui gui))
    (quit-window! ui gui)))
