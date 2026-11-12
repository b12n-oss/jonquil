(ns jonquil.mcp.describe
  "One MCP tool call as a Messages entry (LM8): its kind, a one-line
  summary, a capped detail and a jump target. The window's tool wrapper
  (jonquil.mcp.window/with-messages) calls describe-call after every call,
  so a tool nobody added a row for still logs, as \"called NAME\". No GTK."
  (:require [clojure.string :as str]
            [jonquil.messages :as messages]))

(defn- tab [{:keys [tab-id]}]
  (if tab-id (str "tab " tab-id) "a tab"))

(defn- arg [args k] (let [v (get args k)] (when (and (string? v) (not (str/blank? v))) v)))

(defn- read-of [what] {:kind :agent-read :what (str "read " what)})

(def ^:private rows
  "tool -> (fn [args result] {:kind :what & [:detail :target]}); :what
  follows the client's name in the summary."
  {"jonquil_eval" (fn [args r]
                    {:kind :agent :what "evaluated code"
                     :detail (str (messages/cap-text (str (get args "code"))) "\n\n"
                                  (messages/cap-text (str (:text r))))})
   "jonquil_run" (fn [_ r] {:kind :agent :what (str "ran " (tab r)) :tab? true})
   "jonquil_run_selection" (fn [_ r] {:kind :agent :what (str "ran a selection of " (tab r)) :tab? true
                                      :detail (messages/cap-text (:text r))})
   "jonquil_stop" (fn [_ r] {:kind (if (= "Nothing is running." (:text r)) :agent-read :agent)
                             :what "stopped the evaluation"})
   "jonquil_open" (fn [_ r] {:kind :agent :what (str "opened " (tab r)) :tab? true})
   "jonquil_select_tab" (fn [_ r] {:kind :agent :what (str "switched to " (tab r)) :tab? true})
   "jonquil_replace_text" (fn [_ r] {:kind :agent :what (str "replaced the text of " (tab r)) :tab? true})
   "jonquil_save" (fn [_ r] {:kind :agent :what (str "saved " (tab r)) :tab? true})
   "jonquil_plugin_propose" (fn [args r]
                              (let [id (or (:plugin-id r) (arg args "id"))]
                                {:kind :plugin :what (str "proposed plugin " id)
                                 :target (when (:plugin-id r) {:plugin-id (:plugin-id r)})}))
   "jonquil_tabs" (fn [_ _] (read-of "the tabs"))
   "jonquil_read_tab" (fn [_ r] (assoc (read-of (tab r)) :tab? true))
   "jonquil_console" (fn [_ _] (read-of "the console"))
   "jonquil_doc" (fn [args _] (read-of (str "the docs for " (arg args "symbol"))))
   "jonquil_complete" (fn [args _] (read-of (str "completions for " (arg args "prefix"))))
   "jonquil_project" (fn [_ _] (read-of "the project"))
   "jonquil_check_brackets" (fn [args _] (read-of (str "the brackets of " (or (arg args "path") "a tab"))))
   "jonquil_plugins_list" (fn [_ _] (read-of "the plugins"))
   "jonquil_plugin_read" (fn [args _] (read-of (str "plugin " (arg args "id"))))
   "jonquil_plugin_status" (fn [args _] (read-of (str "plugin " (arg args "id") "'s status")))})

(defn- unknown [tool args]
  {:kind :plugin :what (str "called " tool) :detail (messages/cap-text (pr-str args))})

(defn- row-of
  "The row map (:kind :what :detail :target :tab?) a tool's describer gives
  for this call, or the generic one for a tool with no row."
  [tool args result]
  (if-let [f (get rows tool)]
    (f (or args {}) result)
    (unknown tool args)))

(defn- failure
  "The reason this call failed: threw, else the first line of the tool's
  result text."
  [result threw]
  (or threw (first (str/split-lines (str (:text result))))))

(defn- summary-text
  "\"<client> <what>\", plus \", which failed: <why>\" when failed?."
  [client what failed? why]
  (str (or client "Agent") " " what
       (when failed? (str ", which failed: " why))))

(defn describe-call
  "{:tool :args :result :client :threw} -> an entry for jonquil.messages/add.
  result is the tool's {:text :error? & [:tab-id :plugin-id]}, nil when it
  threw (then threw is the message). client nil reads as \"Agent\"."
  [{:keys [tool args result client threw]}]
  (let [{:keys [kind what detail target tab?]} (row-of tool args result)
        failed? (or (some? threw) (boolean (:error? result)))
        tab-id (when tab? (:tab-id result))]
    (cond-> {:kind kind
             :summary (summary-text client what failed? (failure result threw))
             :client client}
      failed? (assoc :error? true)
      detail (assoc :detail detail)
      target (assoc :target target)
      tab-id (assoc :target {:tab-id tab-id} :summary-tab tab-id))))
