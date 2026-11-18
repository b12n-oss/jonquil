(ns jonquil.mcp.world
  "Save and restore the session's data for an agent (UF7):
  jonquil_world_save's and jonquil_world_restore's definitions and answers,
  built from jonquil.world-ops' results. An agent's saves are a list of
  their own (jonquil.world kind :agent). Pure: the headless table
  (jonquil.mcp.tools) and the window's (jonquil.mcp.window) run the steps
  and hand the results here. No GTK."
  (:require [clojure.string :as str]
            [jonquil.world :as world]))

(defn- prop [type description] {"type" type "description" description})

(def ^:private timeout-prop (prop "integer" "Deadline in milliseconds. Default 30000."))
(def ^:private path-prop (prop "string" "A file or directory selecting the project. Default: the server's directory."))
(def ^:private id-prop (prop "string" "A world's id from jonquil_world_save, or an undo_id from jonquil_world_restore. Default: the newest world an agent saved."))

(def ^:private save-description
  (str "Save the session's data, the values of its data vars, to this project's world history, in a list "
       "kept apart from the person's own saves, and answer the world's id. Functions aren't saved, since "
       "running the code defines them. Jolt only."))

(def ^:private restore-description
  (str "Bring back a world an agent saved: the newest, or the one id names. What's there now is saved "
       "first, and the answer's undo_id brings it back. Jolt only."))

(def save-def
  {"name" "jonquil_world_save" "description" save-description
   "inputSchema" {"type" "object" "properties" {"path" path-prop "timeout_ms" timeout-prop} "required" []}})

(def restore-def
  {"name" "jonquil_world_restore" "description" restore-description
   "inputSchema" {"type" "object" "properties" {"id" id-prop "path" path-prop "timeout_ms" timeout-prop}
                  "required" []}})

(def window-save-def (update-in save-def ["inputSchema" "properties"] dissoc "path"))
(def window-restore-def (update-in restore-def ["inputSchema" "properties"] dissoc "path"))

;; --- answers -----------------------------------------------------------------

(defn- vars-word [n] (if (= 1 n) "var" "vars"))

(defn- named
  "xs as JSON-ready maps holding only ks, string-keyed."
  [ks xs]
  (mapv (fn [x] (into {} (map (fn [k] [(name k) (get x k)])) ks)) xs))

(defn- lines [title xs line-of]
  (when (seq xs) (str "\n" title "\n" (str/join "\n" (map line-of xs)))))

(defn- unwritable-answer
  "A save that wrote nothing: r's error and what couldn't be written.
  prefix leads the text."
  [prefix {:keys [error unwritable]}]
  {:text (str prefix error
              (lines "Can't be written:" unwritable (fn [{:keys [var kind]}] (str "  " var " (" kind ")"))))
   :error? true
   :data (cond-> {"status" "error" "error" error}
           (seq unwritable) (assoc "unwritable" (named [:var :kind] unwritable)))})

(defn save-answer
  "jonquil_world_save's {:text :error? :data} for a world-ops/save! answer."
  [{:keys [error saved placeholders] :as r}]
  (if error
    (unwritable-answer "Nothing was saved: " r)
    (let [id (world/entry-id r)]
      {:text (str "Saved " saved " " (vars-word saved) " as world " id "."
                  (lines "Saved as placeholders, since they can't be written as they are:" placeholders
                         (fn [{:keys [var kind]}] (str "  " var " (" kind ")"))))
       :error? false
       :data {"status" "ok" "id" id "saved" saved "placeholders" (named [:var :kind] placeholders)}})))

(def no-world-answer
  {:text "No world saved by an agent for this project yet: jonquil_world_save makes one."
   :error? false :data {"status" "no-world"}})

(defn unknown-id-answer [id]
  {:text (str "No world " id " that an agent can restore in this project.")
   :error? true :data {"status" "unknown-id"}})

(defn restore-answer
  "jonquil_world_restore's answer for world-ops/restore!'s {:first :restore}."
  [{s :first r :restore}]
  (let [undo (world/entry-id s)]
    (cond
      (:error s) (unwritable-answer "Nothing was restored: what's there now couldn't be saved first. " s)
      (:missing r) {:text "That world is gone." :error? true :data {"status" "unknown-id"}}
      (:error r) {:text (str "Restore failed: " (:error r)) :error? true
                  :data {"status" "error" "error" (:error r) "undo_id" undo}}
      :else
      (let [{:keys [restored placeholders]} r]
        {:text (str "Restored " restored " " (vars-word restored) "."
                    " What was there is saved as world " undo ": restore that id to undo this."
                    (lines "Placeholders for what the world couldn't carry:" placeholders
                           (fn [{:keys [var description]}] (str "  " var ": " description))))
         :error? false
         :data {"status" "ok" "restored" restored
                "placeholders" (named [:var :description] placeholders) "undo_id" undo}}))))

(defn unsupported-answer
  "The answer for a project whose runtime can't save a world."
  [sel]
  {:text (str "Saving the world needs Jolt. This project is " (:name (:profile sel)) ".")
   :error? true :data {"status" "unsupported"}})

(defn timeout-answer
  "The answer when the runtime didn't finish in timeout-ms. The op isn't
  cancelled and may still finish; for a restore, first-path is the save of
  what was there, whose id undoes it if it does."
  ([timeout-ms] (timeout-answer timeout-ms nil))
  ([timeout-ms first-path]
   (let [undo (some-> first-path (as-> p (world/entry-id {:path p})))
         m (str "The runtime didn't finish the world in " timeout-ms " ms. It may still finish"
                (if undo
                  (str ", so the data may still change: what was there is being saved as world " undo
                       ", and restoring that id undoes it.")
                  ", so check before trying again."))]
     {:text m :error? true :data (cond-> {"status" "timeout" "error" m} undo (assoc "undo_id" undo))})))

(defn restore-target
  "{:path p} for the world args' id names among entries (an agent's newest
  without one), or {:answer a} when there's none to restore."
  [entries id]
  ;; an id may come as a number, since it's all digits; blank is none
  (let [id (some-> id str str/trim not-empty)]
    (if id
      (if-let [e (world/agent-entry entries id)] {:path (:path e)} {:answer (unknown-id-answer id)})
      (if-let [e (world/newest-agent-save entries)] {:path (:path e)} {:answer no-world-answer}))))
