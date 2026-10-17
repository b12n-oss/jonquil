(ns jonquil.mcp.tools
  "The six MCP tools over a per-project evaluator.

  `make-tools` returns the table jonquil.mcp consumes:
  `{:defs [mcp-tool-def ...] :call (fn [name args] {:text s :error? b})}`.
  args has string keys, straight from JSON. :call may throw; jonquil.mcp
  turns a throw into an error result. Requires no jonquil.gtk namespace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.commands :as commands]
            [jonquil.console :as console]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.file-tree :as ft]
            [jonquil.help :as help]
            [jonquil.nrepl.connection]
            [jonquil.nrepl.evaluator]
            [jonquil.nrepl.jank]
            [jonquil.runtime-registry :as registry]
            [jonquil.session]
            [jonquil.settings :as settings]))

(def ^:private default-timeout-ms 30000)
(def ^:private interrupt-grace-ms 5000)
(def ^:private max-completions 50)

(defn- prop [type description] {"type" type "description" description})

(def tool-defs
  [{"name" "jonquil_eval"
    "description" "Evaluate code as a REPL line in the project at path, in that project's dialect. Definitions persist between calls."
    "inputSchema" {"type" "object"
                   "properties" {"code" (prop "string" "The code to evaluate.")
                                 "path" (prop "string" "A file or directory selecting the project. Default: the server's directory.")
                                 "timeout_ms" (prop "integer" "Deadline in milliseconds. Default 30000.")}
                   "required" ["code"]}}
   {"name" "jonquil_run_file"
    "description" "Run a file's text the way the Run button does, in the file's project."
    "inputSchema" {"type" "object"
                   "properties" {"path" (prop "string" "The file to run.")
                                 "timeout_ms" (prop "integer" "Deadline in milliseconds. Default 30000.")}
                   "required" ["path"]}}
   {"name" "jonquil_check_brackets"
    "description" "Check brackets in text or in a file. Answers ok, or names the first unclosed or unmatched bracket and its line."
    "inputSchema" {"type" "object"
                   "properties" {"text" (prop "string" "The text to check.")
                                 "path" (prop "string" "A file to check instead of text.")}
                   "required" []}}
   {"name" "jonquil_doc"
    "description" "Documentation for a symbol in the project's runtime."
    "inputSchema" {"type" "object"
                   "properties" {"symbol" (prop "string" "The symbol to look up.")
                                 "path" (prop "string" "A file or directory selecting the project.")}
                   "required" ["symbol"]}}
   {"name" "jonquil_complete"
    "description" "Completion candidates for a symbol prefix, one per line, at most 50."
    "inputSchema" {"type" "object"
                   "properties" {"prefix" (prop "string" "The symbol prefix.")
                                 "path" (prop "string" "A file or directory selecting the project.")}
                   "required" ["prefix"]}}
   {"name" "jonquil_project"
    "description" "The dialect, runtime and project directory the other tools would use for path. Starts nothing."
    "inputSchema" {"type" "object"
                   "properties" {"path" (prop "string" "A file or directory. Default: the server's directory.")}
                   "required" []}}])

;; --- selection ---------------------------------------------------------------

(defn- select-for
  "As jonquil.gtk.window's private select-for (which this namespace can't
  require): dialect/select for a file path, never throwing."
  [path settings-path]
  (try
    (dialect/select dialect/profiles
                    (dialect/find-project dialect/profiles path)
                    path
                    (:dialect (settings/load-settings settings-path)))
    (catch :default e
      (update (dialect/select dialect/profiles nil nil nil) :warnings conj
              (str "couldn't read the project: " (ex-message e))))))

(defn- absolute [dir path]
  (let [f (io/file path)]
    (.getPath (if (.isAbsolute f) f (io/file dir path)))))

(defn- selection
  "The selection for path (relative to dir; nil means dir itself)."
  [{:keys [dir settings-path]} path]
  (let [p (absolute dir (or path dir))
        f (io/file p)]
    (if (.isDirectory f)
      (try
        (ft/project-selection p (:dialect (settings/load-settings settings-path)))
        (catch :default e
          (update (dialect/select dialect/profiles nil nil nil) :warnings conj
                  (str "couldn't read the project: " (ex-message e)))))
      (select-for p settings-path))))

(defn- evaluator-for!
  "The cached evaluator for sel, made, registered and set to :accumulate on
  first use."
  [cache sel]
  (let [k (dialect/selection-key sel)]
    (or (get @cache k)
        (let [e (ev/make sel)]
          (registry/register! e)
          (ev/set-mode! e :accumulate)
          (swap! cache assoc k e)
          e))))

;; --- evaluation --------------------------------------------------------------

(defn- result-text [output result]
  (let [[_ line] (console/result-line result)]
    (str output (str/replace (str line) #"\n+$" ""))))

(defn- run-eval!
  "Evaluates code on e and blocks for the result: {:text :error?}."
  [e code opts timeout-ms]
  (let [out (atom [])
        done (promise)
        warning (ev/eval! e code
                          (assoc opts
                                 :on-output (fn [_ s] (swap! out conj s))
                                 :on-wait-input (fn [waiting?] (when waiting? (ev/send-eof! e)))
                                 :on-complete (fn [r] (deliver done r))))]
    (if warning
      {:text (str warning) :error? true}
      (let [output (fn [] (apply str @out))
            r (deref done timeout-ms ::timeout)]
        (if (= ::timeout r)
          (do (ev/interrupt! e)
              (let [r2 (deref done interrupt-grace-ms ::timeout)]
                {:text (str (output)
                            (if (= ::timeout r2)
                              (str "Timed out after " timeout-ms " ms; interrupted, but the evaluation has not stopped.")
                              (str "Timed out after " timeout-ms " ms; interrupted.")))
                 :error? true}))
          {:text (result-text (output) r)
           :error? (= :error (:status r))})))))

(defn- timeout-of [args]
  (let [t (get args "timeout_ms")]
    (if (and (number? t) (pos? t)) (long t) default-timeout-ms)))

(defn- required-string [args k]
  (let [v (get args k)]
    (if (and (string? v) (not (str/blank? v)))
      v
      (throw (ex-info (str "Missing required argument: " k) {})))))

;; --- the tools ---------------------------------------------------------------

(defn make-tools
  "The tool table for default directory :dir (absolute) and settings file
  :settings-path. Evaluators are cached per project selection in the
  returned table's closure and registered with jonquil.runtime-registry, so
  registry/stop-all! stops them."
  [{:keys [dir] :as ctx}]
  (let [cache (atom {})
        with-ev (fn [path f]
                  (let [sel (selection ctx path)]
                    (f sel (evaluator-for! cache sel))))
        tool-eval (fn [args]
                    (let [code (required-string args "code")]
                      (with-ev (get args "path")
                        (fn [_ e] (run-eval! e code {:run? false :owner :repl} (timeout-of args))))))
        tool-run (fn [args]
                   (let [p (absolute dir (required-string args "path"))
                         f (io/file p)]
                     (cond
                       (not (.exists f)) (throw (ex-info (str "No such file: " p) {}))
                       (.isDirectory f) (throw (ex-info (str "Not a file, a directory: " p) {}))
                       :else
                       (let [text (slurp f)]
                         (with-ev p
                           (fn [_ e] (run-eval! e text {:run? true :path p :owner :run} (timeout-of args))))))))
        tool-brackets (fn [args]
                        (let [text (cond
                                     (string? (get args "text")) (get args "text")
                                     (string? (get args "path"))
                                     (let [p (absolute dir (get args "path"))]
                                       (if (.isFile (io/file p))
                                         (slurp p)
                                         (throw (ex-info (str "No such file: " p) {}))))
                                     :else (throw (ex-info "Give text or path." {})))]
                          {:text (or (commands/pre-run-problem text) "ok") :error? false}))
        tool-doc (fn [args]
                   (let [sym (required-string args "symbol")]
                     (with-ev (get args "path")
                       (fn [_ e]
                         {:text (help/doc-text sym (ev/lookup e sym sym (count sym)))
                          :error? false}))))
        tool-complete (fn [args]
                        (let [prefix (required-string args "prefix")]
                          (with-ev (get args "path")
                            (fn [_ e]
                              (let [names (->> (ev/complete e prefix prefix (count prefix) nil)
                                               (map #(if (map? %) (or (:name %) (:text %) (:symbol %)) %))
                                               (remove nil?)
                                               (map str)
                                               (take max-completions))]
                                {:text (if (seq names)
                                         (str/join "\n" names)
                                         (str "No completions for " prefix "."))
                                 :error? false})))))
        tool-project (fn [args]
                       (let [sel (selection ctx (get args "path"))]
                         {:text (str/join
                                 "\n"
                                 (concat
                                  [(str "Dialect: " (dialect/label sel))
                                   (str "Runtime: " (dialect/runtime-name (:runtime sel)))
                                   (str "Project directory: " (or (:project-dir sel) "none"))
                                   (str "Chosen by: " (name (:source sel)))]
                                  (map #(str "Warning: " %) (:warnings sel))))
                          :error? false}))
        handlers {"jonquil_eval" tool-eval
                  "jonquil_run_file" tool-run
                  "jonquil_check_brackets" tool-brackets
                  "jonquil_doc" tool-doc
                  "jonquil_complete" tool-complete
                  "jonquil_project" tool-project}]
    {:defs tool-defs
     :call (fn [tname args]
             (if-let [h (get handlers tname)]
               (h (or args {}))
               (throw (ex-info (str "Unknown tool: " tname) {}))))}))
