(ns jonquil.mcp.changed
  "The forms edited since they last ran, for an agent (UF6):
  jonquil_changed's and jonquil_run_changed's definitions and answers.
  Pure: the headless table (jonquil.mcp.tools) keeps a record per file and
  the window's (jonquil.mcp.window) reads a tab's, and both hand the text,
  the last whole run's text and the ran set here. Requires no evaluator
  and no jonquil.gtk namespace."
  (:require [clojure.string :as str]
            [jonquil.change-sets :as change-sets]
            [jonquil.changed :as changed]))

(defn- prop [type description] {"type" type "description" description})

(def ^:private run-props
  {"timeout_ms" (prop "integer" "Deadline in milliseconds. Default 30000.")
   "max_output_chars" (prop "integer" "Keep at most this many characters of printed output, the last ones. Default 100000, at most 1000000.")})

(def ^:private changed-description
  (str "The top-level forms edited since they last ran, in buffer order: each one's name, lines, "
       "text now and text at the last whole run (null for a new form), as the gutter marks them. "
       "Reformatting and comment edits don't count. Reads only."))

(def ^:private run-changed-description
  (str "Run only the top-level forms edited since they last ran, in the file's namespace, "
       "and answer as jonquil_eval does. Afterwards they no longer count as changed."))

(def changed-def
  {"name" "jonquil_changed"
   "description" changed-description
   "inputSchema" {"type" "object"
                  "properties" {"path" (prop "string" "The file, as jonquil_run_file ran it.")}
                  "required" ["path"]}})

(def run-changed-def
  {"name" "jonquil_run_changed"
   "description" run-changed-description
   "inputSchema" {"type" "object"
                  "properties" (merge {"path" (prop "string" "The file, as jonquil_run_file ran it.")} run-props)
                  "required" ["path"]}})

(def ^:private id-prop (prop "integer" "The tab id from jonquil_tabs. Default: the active tab."))

(def window-changed-def
  {"name" "jonquil_changed"
   "description" changed-description
   "inputSchema" {"type" "object" "properties" {"id" id-prop} "required" []}})

(def window-run-changed-def
  {"name" "jonquil_run_changed"
   "description" run-changed-description
   "inputSchema" {"type" "object" "properties" (merge {"id" id-prop} run-props) "required" []}})

(def never-ran-headless "Run the file once first (jonquil_run_file).")
(def never-ran-window "Run the tab once first (jonquil_run).")
(def no-changes-text "Nothing has changed since the last run.")

;; --- the forms ---------------------------------------------------------------

(defn- line-at
  "The 1-based line of offset i in text."
  [text i]
  (inc (count (filter #(= \newline %) (subs text 0 i)))))

(defn changed-forms
  "The forms of text edited since they last ran, in buffer order, as
  [{:name :line :end-line :text :before}]: :name the bare symbol a
  definition makes (nil for any other form), :before its text in last-run
  (nil for a new form). ran is the set of keys the forms ran as
  (jonquil.changed/record); nil when the file or tab never ran."
  [text last-run ran]
  (when (some? ran)
    ;; the same forms in the same order: from-changed maps over
    ;; changed/changed
    (mapv (fn [{:keys [start end key]} entry]
            {:name (second (changed/ident key))
             :line (line-at text start)
             :end-line (line-at text (max start (dec end)))
             :text (:text entry)
             :before (:before entry)})
          (changed/changed text ran)
          (change-sets/from-changed text last-run nil ran))))

(defn run-code
  "The code jonquil_run_changed sends: the forms' text, a line apart."
  [forms]
  (str/join "\n" (map :text forms)))

(defn sent-line
  "The console's line before the forms run, as Run Changed says it."
  [n]
  (str "; Running " n " changed form" (when (not= 1 n) "s")))

;; --- the answer --------------------------------------------------------------

(defn- form-text
  "One form's section of the answer's text: its name and lines, then the
  diff from its last-run text."
  [{:keys [name line end-line before] :as f}]
  (str (or name "(form)") (when (nil? before) " (new)")
       "  line " line (when (not= line end-line) (str "-" end-line)) "\n"
       (change-sets/entry-diff f)))

(defn- form-data [{:keys [name line end-line text before]}]
  {"name" name "line" line "end_line" end-line "text" text "before" before})

(defn changed-answer
  "jonquil_changed's {:text :error? :data} for forms, changed-forms' value:
  never-ran (nil, with :never-ran-text), no-changes ([]) or ok. Neither of
  the first two is an error."
  [forms {:keys [never-ran-text]}]
  (cond
    (nil? forms) {:text never-ran-text :error? false :data {"status" "never-ran"}}
    (empty? forms) {:text no-changes-text :error? false :data {"status" "no-changes"}}
    :else {:text (str/join "\n\n" (map form-text forms))
           :error? false
           :data {"status" "ok" "forms" (mapv form-data forms)}}))
