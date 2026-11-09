(ns jonquil.plugin-mcp
  "The text of the plugin MCP tools: jonquil_plugins_list,
  jonquil_plugin_read, jonquil_plugin_status and jonquil_plugin_propose's
  answer, over a plugin state {:plugins? :plugins-dir :registry :disabled}.
  Each answers a tool result {:text :error?} and never throws. Pure apart
  from reading the plugins folder and its histories. An id is checked
  before it becomes a path, so nothing outside the plugins folder is read."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.plugin-history :as history]
            [jonquil.plugin-list :as plugin-list]
            [jonquil.plugin-loader :as loader]
            [jonquil.tabs :as tabs]))

(def MAX-SOURCE-CHARS
  ;; estimate, not measured: bigger than any plugin file seen so far, and
  ;; small enough to keep a pasted megabyte out of the history and the
  ;; review panel's text view
  262144)

(def MAX-REASON-CHARS
  ;; estimate, as MAX-SOURCE-CHARS: a few paragraphs, since the
  ;; reason reaches the history, the console and the review panel too
  4000)

(def ^:private ECHOED-ID-CHARS 40)

(defn- ok [text] {:text text :error? false})
(defn- err [text] {:text text :error? true})

(defn- guarded
  "(f)'s result, or an error result naming what failed."
  [what f]
  (try (f)
       (catch Throwable e (err (str what " failed: " (or (ex-message e) (str e)))))))

(defn- bad-id
  "The sentence refusing id, echoing at most ECHOED-ID-CHARS of it."
  [id]
  (str (let [s (str id)] (if (> (count s) ECHOED-ID-CHARS) (str (subs s 0 ECHOED-ID-CHARS) "…") s))
       " isn't a plugin id: lowercase letters, digits and dashes, starting with a letter"))

(defn propose-refusal
  "Why a proposal is refused before any check runs, or nil: plugins off in
  the window, a bad id, a source that isn't text, a blank reason, a source
  or reason holding a NUL byte (the review pane couldn't show it), or a
  source or reason longer than MAX-SOURCE-CHARS or MAX-REASON-CHARS."
  [{:keys [id source reason plugins?]}]
  (cond
    (false? plugins?) "plugins are off in this window (--no-plugins), so it takes no proposals"
    (not (loader/plugin-id? id)) (bad-id id)
    (not (string? source)) "a proposal needs its source as text"
    ;; The review pane shows source through GTK, which
    ;; cuts at a NUL, while the reader reads past one inside a string, so a
    ;; proposal could hide code from the person reviewing it.
    (tabs/nul-line source)
    (str "the source holds a NUL byte on line " (tabs/nul-line source)
         ", which the review pane can't show")
    (or (not (string? reason)) (str/blank? reason)) "a proposal needs a reason"
    ;; the reason is agent text shown at the review gate through GTK
    ;; labels, C strings too, so a NUL would display it truncated
    (tabs/nul-line reason)
    (str "the reason holds a NUL byte on line " (tabs/nul-line reason)
         ", which the review pane can't show")
    (> (count source) MAX-SOURCE-CHARS)
    (str "the source is " (count source) " characters, more than the " MAX-SOURCE-CHARS " a proposal may have")
    (> (count reason) MAX-REASON-CHARS)
    (str "the reason is " (count reason) " characters, more than the " MAX-REASON-CHARS " a proposal may have")))

;; --- list --------------------------------------------------------------------

(defn list-text
  "jonquil_plugins_list: one line per plugin, sorted by id."
  [state]
  (guarded "listing the plugins"
           (fn []
             (let [rows (plugin-list/rows state)]
               (ok (if (empty? rows)
                     "No plugins. Call jonquil_plugin_propose to propose one."
                     (str/join "\n" (map :line rows))))))))

;; --- read and status ---------------------------------------------------------

(defn- with-history
  "(f hdir entries) for a good id whose history reads, else the error."
  [{:keys [plugins-dir id]} f]
  (if-not (loader/plugin-id? id)
    (err (bad-id id))
    (let [hdir (history/history-dir plugins-dir id)
          {:keys [entries error]} (history/read-entries hdir)]
      (if error
        (err (str id "'s history can't be read: " error))
        (f hdir entries)))))

(defn- version-text
  "read-text's answer for one version of id."
  [hdir entries id version]
  (if-let [src (history/version-source hdir version)]
    (ok (str id " v" version ": "
             (if-let [s (history/version-status entries version)] (name s) "not in its history")
             "\n\n" src))
    (err (str id " has no v" version ". Call jonquil_plugin_read with just the id to see its history."))))

(defn- live-text
  "read-text's answer for id's live source and history."
  [{:keys [plugins-dir registry id]} hdir entries]
  (let [loaded (get-in registry [:plugins (keyword id)])
        live-n (:n (history/latest-accepted entries))
        src (or (:source loaded) (when live-n (history/version-source hdir live-n)))]
    (if (and (empty? entries) (not loaded) (not (.isFile (io/file plugins-dir (str id ".clj")))))
      (err (str "There's no plugin " id ". Call jonquil_plugins_list to see them."))
      (ok (str/join "\n" (concat [(str id ": " (if live-n (str "live v" live-n) "no live version"))]
                                 (when src ["" src])
                                 (when (seq entries)
                                   (into ["" "History:"]
                                         (map #(plugin-list/history-line entries %) (distinct (map :n entries)))))))))))

(defn read-text
  "jonquil_plugin_read: id's live source and history, or with :version
  that version's status and source."
  [{:keys [id version] :as state}]
  (guarded
   "reading the plugin"
   (fn []
     (with-history
       state
       (fn [hdir entries]
         (if version
           (version-text hdir entries id version)
           (live-text state hdir entries)))))))

(defn- status-follow-up
  "The sentence after a proposal's status, by status st: e1 is its latest
  entry and es all its entries."
  [st e1 es]
  (case st
    :pending " The user decides in the window. Call jonquil_plugin_status again later."
    :rejected (str (if (:note e1) (str " The user's note: " (:note e1)) " The user left no note.")
                   " Call jonquil_plugin_propose with a new version to try again.")
    :rejected-by-checks (str " Problems: " (str/join "; " (:problems (first es))) "."
                             ;; the kept log, while it's there
                             (when-let [log (:check-log (first es))]
                               (when (.isFile (io/file log)) (str " The check's log: " log ".")))
                             " Fix them and call jonquil_plugin_propose again.")
    :superseded " A newer proposal replaced it."
    ""))

(defn status-text
  "jonquil_plugin_status: version n's status (default: id's latest agent
  proposal), with the user's note or the check's problems, and the call
  that continues it."
  [{:keys [id n] :as state}]
  (guarded
   "reading the proposal's status"
   (fn []
     (with-history
       state
       (fn [_ entries]
         (let [n (or n (:n (last (filter #(= :agent (:source %)) entries))))
               es (plugin-list/version-entries entries n)]
           (cond
             (nil? n) (err (str id " has no proposals. Call jonquil_plugin_propose to make one."))
             (empty? es) (err (str id " has no v" n "."))
             :else
             (let [e1 (last es)
                   st (:status e1)]
               (ok (str id " v" n ": " (some-> st name) "." (status-follow-up st e1 es)))))))))))

;; --- propose -----------------------------------------------------------------

(defn propose-text
  "jonquil_plugin_propose's answer for id from a propose result {:status
  :n :problems :log}: its status, version, problems and the check's kept
  log, then the call that continues it. Only pending isn't an error."
  [id {:keys [status n problems log]}]
  (guarded
   "answering the proposal"
   (fn []
     {:text (str/join "\n" (concat [(str "status: " (name status))]
                                   (when n [(str "proposal: v" n)])
                                   (when (seq problems) (cons "problems:" (map #(str "- " %) problems)))
                                   (when log [(str "check log: " log)])
                                   [(case status
                                      :pending (str "The user reviews it in the window. Call jonquil_plugin_status with id "
                                                    id " and n " n " to see what they decide.")
                                      :rejected-by-checks (str "Fix the problems and call jonquil_plugin_propose again with id " id ".")
                                      :refused "Fix that and call jonquil_plugin_propose again."
                                      (str "Call jonquil_plugin_status with id " id " to see whether it was recorded."))]))
      :error? (not= :pending status)})))
