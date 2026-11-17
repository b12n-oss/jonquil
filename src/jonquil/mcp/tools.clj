(ns jonquil.mcp.tools
  "The seven MCP tools over a per-project evaluator.

  `make-tools` returns the table jonquil.mcp consumes:
  `{:defs [mcp-tool-def ...] :call (fn [name args] {:text s :error? b})}`.
  args has string keys, straight from JSON. :call may throw; jonquil.mcp
  turns a throw into an error result. Requires no jonquil.gtk namespace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.commands :as commands]
            [jonquil.console :as console]
            [jonquil.clj :as clj]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.file-tree :as ft]
            [jonquil.help :as help]
            [jonquil.mcp.result :as result]
            [jonquil.nrepl.attach]
            [jonquil.nrepl.cljs-node]
            [jonquil.nrepl.connection]
            [jonquil.nrepl.evaluator]
            [jonquil.nrepl.jank]
            [jonquil.nrepl.jvm]
            [jonquil.nrepl.shadow]
            [jonquil.runtime-registry :as registry]
            [jonquil.session]
            [jonquil.settings :as settings]
            [jonquil.test-run :as test-run]))

(def ^:private default-timeout-ms 30000)
(def ^:private interrupt-grace-ms 5000)
(def ^:private max-completions 50)

(defn- prop [type description] {"type" type "description" description})

(def max-output-prop
  (prop "integer" "Keep at most this many characters of printed output, the last ones. Default 100000, at most 1000000."))

(def tool-defs
  [{"name" "jonquil_eval"
    "description" "Evaluate code as a REPL line in the project at path, in that project's dialect. Definitions persist between calls."
    "inputSchema" {"type" "object"
                   "properties" {"code" (prop "string" "The code to evaluate.")
                                 "path" (prop "string" "A file or directory selecting the project. Default: the server's directory.")
                                 "timeout_ms" (prop "integer" "Deadline in milliseconds. Default 30000.")
                                 "max_output_chars" max-output-prop}
                   "required" ["code"]}}
   {"name" "jonquil_run_file"
    "description" "Run a file's text the way the Run button does, in the file's project."
    "inputSchema" {"type" "object"
                   "properties" {"path" (prop "string" "The file to run.")
                                 "timeout_ms" (prop "integer" "Deadline in milliseconds. Default 30000.")
                                 "max_output_chars" max-output-prop}
                   "required" ["path"]}}
   {"name" "jonquil_test"
    "description" "Run a namespace's clojure.test tests, or one test, in the project's runtime. Reloads the test namespace from disk first. Answers counts and each failure's test, file, line, expected and actual. Jolt and JVM Clojure."
    "inputSchema" {"type" "object"
                   "properties" {"ns" (prop "string" "The test namespace. Optional when path is a test file.")
                                 "test" (prop "string" "One test's name in that namespace. Default: every test.")
                                 "path" (prop "string" "A file or directory selecting the project. A test file here also names the namespace when ns is absent.")
                                 "timeout_ms" (prop "integer" "Deadline in milliseconds. Default 30000.")
                                 "max_output_chars" max-output-prop}
                   "required" []}}
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
  "As jonquil.gtk.window.runtime's select-for (which this namespace can't
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

(defn absolute
  "path made absolute against dir."
  [dir path]
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

(defn result-text
  "Collected output, then the result line, as one string."
  [output result]
  (let [[_ line] (console/result-line result)
        sep (if (or (str/blank? output) (str/ends-with? output "\n")) "" "\n")]
    (str output sep (str/replace (str line) #"\n+$" ""))))

(defn done-result
  "The {:text :error? :data} for an evaluation that finished with result r.
  out is {:output :err :dropped} (jonquil.mcp.result), ms the wall time."
  [r {:keys [output err dropped]} ms]
  (let [status (:status r)]
    {:text (str (result/dropped-line dropped) (result-text output r))
     :error? (= :error status)
     :data (result/eval-data {:status status :output output :err err :dropped dropped :ms ms
                              :ns (:ns r)
                              :value (when (= :ok status) (:printed r))
                              :error-text (when (= :error status) (console/error-text r))})}))

(defn timeout-result
  "The {:text :error? :data} for an evaluation that missed its deadline:
  what it printed, then sentence. out and ms as done-result's."
  [sentence {:keys [output err dropped]} ms]
  {:text (str (result/dropped-line dropped) output sentence)
   :error? true
   :data (result/eval-data {:status :timeout :output output :err err :dropped dropped :ms ms})})

(defn- timed-out-result!
  "The result for an evaluation that missed its deadline. Interrupts e and
  waits grace-ms for it to finish; if it is still stuck, evicts it and
  stops it. output is a fn giving what was collected so far, elapsed the
  wall time."
  [e done {:keys [output elapsed]} {:keys [timeout-ms grace-ms evict!]}]
  (ev/interrupt! e)
  (let [stuck? (= ::timeout (deref done grace-ms ::timeout))]
    (when stuck?
      (evict!)
      (try (ev/stop! e) (catch :default _ nil)))
    (timeout-result (if stuck?
                      (str "Timed out after " timeout-ms " ms; interrupted, but the evaluation has not stopped."
                           " The runtime was stopped; a fresh one will be started on the next call.")
                      (str "Timed out after " timeout-ms " ms; interrupted."))
                    (output) (elapsed))))

(defn- run-eval!
  "Evaluates code on e and blocks for the result: {:text :error? :data}.
  limits holds :opts (eval options), :timeout-ms, :max-output (characters
  of output kept), :grace-ms and :evict! (called when e is stuck past the
  grace)."
  [e code {:keys [opts timeout-ms max-output] :as limits}]
  (let [cap (or max-output result/default-max-output-chars)
        out (atom result/empty-output)
        done (promise)
        t0 (System/currentTimeMillis)
        elapsed (fn [] (- (System/currentTimeMillis) t0))
        warning (ev/eval! e code
                          (assoc opts
                                 :on-output (fn [kind s] (swap! out result/add-output kind s cap))
                                 :on-wait-input (fn [waiting?] (when waiting? (ev/send-eof! e)))
                                 :on-complete (fn [r] (deliver done r))))
        output (fn [] (result/output-of @out cap))]
    (if warning
      {:text (str warning) :error? true
       :data (result/eval-data {:status :refused :output ""})}
      (let [r (deref done timeout-ms ::timeout)]
        (if (= ::timeout r)
          (timed-out-result! e done {:output output :elapsed elapsed} limits)
          (done-result r (output) (elapsed)))))))

(defn timeout-of
  "The call's timeout_ms when a positive number, else the 30000 ms default."
  [args]
  (let [t (get args "timeout_ms")]
    (if (and (number? t) (pos? t)) (long t) default-timeout-ms)))

(defn required-string
  "args' non-blank string at k; throws naming k otherwise."
  [args k]
  (let [v (get args k)]
    (if (and (string? v) (not (str/blank? v)))
      v
      (throw (ex-info (str "Missing required argument: " k) {})))))

(defn brackets-result
  "The check_brackets tool's answer for args (\"text\" or \"path\", the path
  relative to dir): {:text .. :error? false}. Throws when given neither or the
  file is missing."
  [dir args]
  (let [text (cond
               (string? (get args "text")) (get args "text")
               (string? (get args "path"))
               (let [p (absolute dir (get args "path"))]
                 (if (.isFile (io/file p))
                   (slurp p)
                   (throw (ex-info (str "No such file: " p) {}))))
               :else (throw (ex-info "Give text or path." {})))]
    {:text (or (commands/pre-run-problem text) "ok") :error? false}))

(defn selection-text
  "The project tool's report for a dialect selection."
  [sel]
  (str/join
   "\n"
   (concat
    [(str "Dialect: " (dialect/label sel))
     (str "Runtime: " (dialect/runtime-name (:runtime sel)))
     (str "Project directory: " (or (:project-dir sel) "none"))
     (str "Chosen by: " (name (:source sel)))]
    (map #(str "Warning: " %) (:warnings sel)))))

;; --- the tools ---------------------------------------------------------------

(defn- with-ev
  "Calls (f selection evaluator) for the project selected by path."
  [{:keys [ctx cache]} path f]
  (let [sel (selection ctx path)]
    (f sel (evaluator-for! cache sel))))

(defn- run-in!
  "run-eval! on evaluator e for the selection at path, evicting e from the
  cache when it is stuck. step holds :path :e :code :opts and the call's
  :args, read for its timeout and output cap."
  [{:keys [ctx cache grace-ms]} {:keys [path e code opts args]}]
  (let [k (dialect/selection-key (selection ctx path))]
    (run-eval! e code {:opts opts :timeout-ms (timeout-of args)
                       :max-output (result/max-output-of args) :grace-ms grace-ms
                       :evict! (fn [] (swap! cache (fn [m] (if (identical? e (get m k)) (dissoc m k) m))))})))

(defn- tool-eval [env args]
  (let [code (required-string args "code")
        path (get args "path")]
    (with-ev env path
      (fn [_ e] (run-in! env {:path path :e e :code code :opts {:run? false :owner :repl}
                              :args args})))))

(defn- tool-run [env args]
  (let [p (absolute (:dir env) (required-string args "path"))
        f (io/file p)]
    (cond
      (not (.exists f)) (throw (ex-info (str "No such file: " p) {}))
      (.isDirectory f) (throw (ex-info (str "Not a file, a directory: " p) {}))
      :else
      (let [text (slurp f)]
        (with-ev env p
          (fn [_ e] (run-in! env {:path p :e e :code text :opts {:run? true :path p :owner :run}
                                  :args args})))))))

;; --- tests -------------------------------------------------------------------

(def ^:private max-located-files 20000)

(defn- project-files
  "The paths of the files under dir, at most max-located-files, skipping
  dot directories, target and node_modules."
  [dir]
  (let [skip? (fn [^java.io.File f]
                (let [n (.getName f)]
                  (or (str/starts-with? n ".") (contains? #{"target" "node_modules"} n))))
        walk (fn walk [^java.io.File d]
               (mapcat (fn [^java.io.File f]
                         (cond
                           (skip? f) nil
                           (.isDirectory f) (walk f)
                           :else [(.getPath f)]))
                       (.listFiles d)))]
    (take max-located-files (walk (io/file dir)))))

(defn- test-ns-name
  "The namespace a jonquil_test call names: its ns, or the ns form of the
  file at its path. Throws when it gives neither."
  [dir args]
  (let [ns-name (get args "ns")
        path (get args "path")
        f (when (string? path) (io/file (absolute dir path)))]
    (cond
      (and (string? ns-name) (not (str/blank? ns-name))) ns-name
      (and f (.isFile f)) (or (clj/buffer-ns (slurp f))
                              (throw (ex-info (str "No ns form in " (.getPath f) ".") {})))
      :else (throw (ex-info "Give ns, or the path of a test file." {})))))

(defn- unreadable-test-result [r ns-name]
  (let [message "The test run returned something unreadable."]
    {:text (str message "\n" (:text r))
     :error? true
     :data (-> (:data r)
               (dissoc "value")
               (assoc "status" "error" "ns" ns-name "error" {"message" message}))}))

(defn- test-result
  "A jonquil_test answer from r, run-eval!'s answer for the snippet. An
  evaluation that didn't finish with a value (an error while loading, a
  timeout) keeps its text and status. files is a delay of the project's
  file paths, where failures' files are looked up."
  [r {:keys [ns-name test files]}]
  (let [d (:data r)]
    (if (not= "ok" (get d "status"))
      (assoc r :error? true :data (-> d (dissoc "value") (assoc "ns" ns-name)))
      (if-let [v (test-run/read-value (get d "value"))]
        (let [report (test-run/report v {:ns ns-name :test test
                                         :locate (fn [rel] (test-run/locate @files rel))})
              dropped (or (get d "dropped_chars") 0)]
          {:text (str (result/dropped-line dropped) (test-run/text report) (get d "output"))
           :error? (not (contains? #{"ok" "failed"} (:status report)))
           :data (test-run/data report {:output (get d "output") :err (get d "err")
                                        :dropped dropped :ms (get d "ms")})})
        (unreadable-test-result r ns-name)))))

(defn- unsupported-test-result [sel]
  {:text (str "jonquil_test runs Jolt and JVM Clojure tests for now. This project is "
              (:name (:profile sel)) ".")
   :error? true
   :data {"status" "unsupported"}})

(defn- session-only?
  "True when no file under the project holds namespace ns-name. files is
  the project's file paths."
  [files ns-name]
  (let [base (-> ns-name (str/replace "-" "_") (str/replace "." "/"))]
    (not-any? #(test-run/locate files (str base %)) [".clj" ".cljc" ".jolt"])))

(defn- tool-test [env args]
  (let [path (get args "path")
        ns-name (test-ns-name (:dir env) args)
        test (get args "test")
        sel (selection (:ctx env) path)
        files (delay (project-files (or (:project-dir sel) (:dir env))))
        ;; Jolt reloads a namespace that has no file as a no-op, so there
        ;; a namespace defined only in the session runs as it is.
        code (test-run/snippet {:ns ns-name :test test
                                :keep-unchanged? (and (= :jolt (:id (:profile sel)))
                                                      (session-only? @files ns-name))})]
    (if-not (test-run/supported? (:id (:profile sel)))
      (unsupported-test-result sel)
      (let [e (evaluator-for! (:cache env) sel)
            opts (cond-> {:run? false :owner :repl}
                   (satisfies? ev/QuietEval e) (assoc :quiet (str "jonquil-test-" (random-uuid) ":")))]
        (test-result (run-in! env {:path path :e e :code code :opts opts :args args})
                     {:ns-name ns-name :test test :files files})))))

(defn- tool-brackets [env args] (brackets-result (:dir env) args))

(defn- tool-doc [env args]
  (let [sym (required-string args "symbol")]
    (with-ev env (get args "path")
      (fn [_ e]
        {:text (help/doc-text sym (ev/lookup e sym sym (count sym)))
         :error? false}))))

(defn- completion-names
  "The first max-completions completion names for prefix on evaluator e."
  [e prefix]
  (->> (ev/complete e {:prefix prefix :text prefix :pos (count prefix) :extra-context nil})
       (map #(if (map? %) (or (:name %) (:text %) (:symbol %)) %))
       (remove nil?)
       (map str)
       (take max-completions)))

(defn- tool-complete [env args]
  (let [prefix (required-string args "prefix")]
    (with-ev env (get args "path")
      (fn [_ e]
        (let [names (completion-names e prefix)]
          {:text (if (seq names)
                   (str/join "\n" names)
                   (str "No completions for " prefix "."))
           :error? false})))))

(defn- tool-project [env args]
  {:text (selection-text (selection (:ctx env) (get args "path")))
   :error? false})

(def ^:private tool-fns
  {"jonquil_eval" tool-eval
   "jonquil_run_file" tool-run
   "jonquil_test" tool-test
   "jonquil_check_brackets" tool-brackets
   "jonquil_doc" tool-doc
   "jonquil_complete" tool-complete
   "jonquil_project" tool-project})

(defn make-tools
  "The tool table for default directory :dir (absolute) and settings file
  :settings-path. Evaluators are cached per project selection in the
  returned table's closure (or in :cache, an atom, for tests; :grace-ms
  overrides the 5000 ms wait after an interrupt) and registered with jonquil.runtime-registry, so
  registry/stop-all! stops them."
  [{:keys [dir cache grace-ms] :as ctx}]
  (let [env {:ctx ctx :dir dir
             :cache (or cache (atom {}))
             :grace-ms (or grace-ms interrupt-grace-ms)}
        handlers (into {} (map (fn [[tname f]] [tname (fn [args] (f env args))])) tool-fns)]
    {:defs tool-defs
     :call (fn [tname args]
             (if-let [h (get handlers tname)]
               (h (or args {}))
               (throw (ex-info (str "Unknown tool: " tname) {}))))}))
