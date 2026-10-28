(ns jonquil.plugin-list
  "The plugin list's rows (plugins P11): one per plugin, loaded, a file in
  the plugins folder or a history, over a plugin state {:plugins-dir
  :registry :disabled}. jonquil_plugins_list prints their lines, so the
  panel and the MCP tool say the same thing. Pure apart from reading the
  plugins folder and its histories."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.plugin-history :as history]
            [jonquil.plugin-loader :as loader]))

(defn ids
  "Every plugin id: loaded, a file in the plugins folder, or a history."
  [{:keys [plugins-dir registry]}]
  (sort (distinct (concat (map name (keys (:plugins registry)))
                          (map first (:files (loader/plugin-files plugins-dir)))
                          (for [f (or (.listFiles (io/file plugins-dir ".history")) [])
                                :when (.isDirectory ^java.io.File f)
                                :let [n (.getName ^java.io.File f)]
                                :when (loader/plugin-id? n)]
                            n)))))

(defn- list-line [{:keys [plugins-dir registry disabled]} id]
  (let [{:keys [entries error]} (history/read-entries (history/history-dir plugins-dir id))
        loaded (get-in registry [:plugins (keyword id)])
        {:keys [doc effects]} (:definition loaded)
        live (cond error "history unreadable"
                   (history/latest-accepted entries) (str "live v" (:n (history/latest-accepted entries)))
                   :else "no live version")
        pending (cond error "history unreadable"
                      (history/pending entries) (str "pending v" (:n (history/pending entries)))
                      :else "no pending proposal")
        state (cond (contains? (set disabled) (keyword id)) "disabled"
                    loaded "loaded"
                    :else "not loaded")
        ;; plugins F1: went live once, and its file is gone now
        deleted (when (and (not error) (history/latest-accepted entries)
                           (not (.isFile (io/file plugins-dir (str id ".clj")))))
                  ", file deleted")
        effects (cond (not loaded) "unknown"
                      (empty? effects) "none"
                      :else (str/join " " (map str (sort-by str effects))))]
    (str id ": " live ", " state deleted ", effects " effects ", " pending "."
         (when doc (str " " doc)))))

(defn row
  "id's row: {:id :line :disabled? :file :pending-n}. :file is the path of
  <plugins-dir>/<id>.clj, nil when there's no such file; :pending-n the
  pending proposal's version, nil when there's none or the history can't
  be read."
  [{:keys [plugins-dir disabled] :as state} id]
  (let [{:keys [entries error]} (history/read-entries (history/history-dir plugins-dir id))
        f (io/file plugins-dir (str id ".clj"))]
    {:id id
     :line (list-line state id)
     :disabled? (contains? (set disabled) (keyword id))
     :file (when (.isFile f) (str f))
     :pending-n (when-not error (:n (history/pending entries)))}))

(defn rows
  "Every plugin's row, sorted by id."
  [state]
  (mapv #(row state %) (ids state)))

(defn version-entries
  "id's entries for version n: the first is the version's own, the last
  holds its status."
  [entries n]
  (filter #(= n (:n %)) entries))

(defn history-line
  "One version's line: v<n>, its status, source, time and reason, with
  the user's note or the check's problems and kept log (while it's there)."
  [entries n]
  (let [es (version-entries entries n)
        e0 (first es)
        e1 (last es)]
    (str "v" n " " (some-> (:status e1) name) " (" (some-> (:source e0) name) ", " (:at e0) "): " (:reason e0)
         (when (:note e1) (str "; note: " (:note e1)))
         (when (= :rejected-by-checks (:status e1))
           (str "; problems: " (str/join "; " (:problems e0))
                (when-let [log (:check-log e0)]
                  (when (.isFile (io/file log)) (str "; check log: " log))))))))

(defn version-rows
  "id's versions, newest first, {:live-n :rows [{:n :line}]}, where :live-n
  is the last accepted version (nil when none); or {:error sentence} when
  its history can't be read."
  [plugins-dir id]
  (let [{:keys [entries error]} (history/read-entries (history/history-dir plugins-dir id))]
    (if error
      {:error (str id "'s history can't be read: " error)}
      {:live-n (:n (history/latest-accepted entries))
       :rows (mapv (fn [n] {:n n :line (history-line entries n)})
                   (reverse (distinct (map :n entries))))})))
