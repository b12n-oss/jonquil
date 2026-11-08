(ns jonquil.gtk.parts.disk-poll
  "Disk polling as a window part (it has no commands: jonquil.parts.disk-poll/spec
  is the data half). Moved out of jonquil.gtk.window: open tabs follow their
  files on a 2 s timer, whose callable is built once at install. It logs
  through the :log-entry! export of the messages part."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.plugin-loader :as plugin-loader]
            [jonquil.tabs :as tabs]))

(def ^:private DISK-POLL-MS
  ;; estimate: quick enough to see an agent's edit while it works, slow
  ;; enough that statting the open tabs costs nothing
  2000)

(defn- read-disk
  "The file's text, ::gone when it's no longer there, nil when the read fails."
  [path stat]
  (try (if stat (slurp path) ::gone) (catch :default _ nil)))

(defn- follow-disk!
  "Applies tabs/disk-follow's verdict for the tab and the disk text."
  [{:keys [refresh-labels! disk-seen]} {:keys [t seen disk name log!]}]
  (let [ed (:ed t)]
    (case (tabs/disk-follow {:saved @(:saved-text ed) :disk disk :dirty? (editor/dirty? ed)
                             :buffer (when disk (editor/editor-text ed)) :reported (:reported seen)})
      :reload (do (editor/reload-text! ed disk)
                  (refresh-labels!)
                  (log! (str name " was reloaded from disk")))
      :warn (do (editor/rebase-saved! ed disk)
                (refresh-labels!)
                (log! (str name " changed on disk, and this tab keeps your unsaved edits")))
      :rebase (do (editor/rebase-saved! ed disk) (refresh-labels!))
      :missing (do (swap! disk-seen assoc-in [(:id t) :reported] ::tabs/missing)
                   (log! (str name " was deleted or moved on disk")))
      nil)))

(defn- apply-disk!
  "Skips a text GTK would truncate, else follows it."
  [{:keys [disk-seen] :as ctx} {:keys [t seen disk name log!] :as step}]
  (if (and disk (tabs/nul-line disk))
    ;; GTK would truncate it, and rebasing on it would leave the
    ;; tab dirty for good; said once per text
    (when (not= disk (:nul seen))
      (swap! disk-seen assoc-in [(:id t) :nul] disk)
      (log! (str name " changed on disk but holds a NUL byte, so this tab keeps its text")))
    (do (swap! disk-seen update (:id t) dissoc :nul)
        (follow-disk! ctx step))))

(defn- apply-disk-reporting!
  "A throw from here on is one row, not a silent retry: the tab may already
  have changed."
  [{:keys [log-entry!] :as ctx} {:keys [t name] :as step}]
  (try
    (apply-disk! ctx step)
    (catch :default e
      (log-entry! {:kind :file :error? true :target {:tab-id (:id t)}
                   :summary (str name " changed on disk, but following it failed: "
                                 (or (ex-message e) (str e)))}))))

(defn- act-on-change!
  "The stat moved twice: reads the file and acts on it."
  [{:keys [disk-seen log-entry!] :as ctx} {:keys [t path stat seen]}]
  ;; a read that fails logs nothing: the stat stays pending and
  ;; the next tick tries again
  (when-some [disk (read-disk path stat)]
    (let [disk (when-not (= ::gone disk) disk)
          name (last (str/split path #"/"))
          log! (fn [summary] (log-entry! {:kind :file :target {:tab-id (:id t)} :summary summary}))]
      (swap! disk-seen update (:id t) #(-> % (assoc :stat stat) (dissoc :pending)))
      ;; the file is there again: a later delete is said again
      (when disk (swap! disk-seen update (:id t) dissoc :reported))
      (apply-disk-reporting! ctx {:t t :seen seen :disk disk :name name :log! log!}))))

(defn- check-tab!
  "A tab already seen at this path: steps its stat."
  [{:keys [disk-seen] :as ctx} {:keys [t stat seen] :as step}]
  (case (tabs/stat-step (cond-> {:stat stat :seen-stat (:stat seen)}
                          (contains? seen :pending) (assoc :pending (:pending seen))))
    :same (swap! disk-seen update (:id t) dissoc :pending)
    ;; moved: wait a tick, so a writer that truncates and then
    ;; writes is never caught halfway
    :wait (swap! disk-seen assoc-in [(:id t) :pending] stat)
    :act (act-on-change! ctx step)))

(defn- stat-tab!
  "Stats the tab's file; the first sight of a path only remembers it."
  [{:keys [disk-seen] :as ctx} t path]
  (let [f (java.io.File. ^String path)
        stat (when (.exists f) [(.lastModified f) (.length f)])
        seen (get @disk-seen (:id t))]
    (if (or (nil? seen) (not= path (:path seen)))
      ;; first sight (or a new path): only remember it
      (swap! disk-seen assoc (:id t) {:path path :stat stat})
      (check-tab! ctx {:t t :path path :stat stat :seen seen}))))

(defn- tick-tab!
  [ctx t path]
  (try
    (stat-tab! ctx t path)
    ;; the stat itself failed: the next tick tries again
    (catch :default _ nil)))

(defn- disk-tick!
  "Open files changed on disk: agents mostly edit files with their
  own tools, which no MCP call shows. Each tick stats every open
  tab's file (plugin tabs keep sync-plugin-tabs!) and reads one only
  when its time or size moved. A clean tab follows the file as one
  undo step; a dirty one keeps its edits and takes the disk text as
  its saved baseline (tabs/disk-follow), each logged to Messages.
  GTK thread, so the tab list and saved text are read safely."
  [{:keys [tabs settings-path* disk-seen] :as ctx}]
  (let [dir (plugin-loader/plugins-dir @settings-path*)
        ts @tabs]
    (swap! disk-seen select-keys (map :id ts))
    (doseq [t ts
            :let [path @(:path t)]
            :when (and path (not (plugin-loader/plugin-file-or-target? dir path)))]
      (tick-tab! ctx t path))))

(defn- install
  "The disk-poll part for one window: arms the one timer and answers the tick
  as a ui entry. Reads the tab list, the settings path, the label refresh and
  the messages part's :log-entry! from wctx; without that export it throws,
  so the part is reported and no timer runs."
  [{:keys [log-entry!] :as wctx}]
  (when-not log-entry!
    (throw (ex-info "disk-poll needs :log-entry! from the messages part" {})))
  (let [ctx (assoc wctx :disk-seen (atom {}))
        tick! (fn [] (disk-tick! ctx))]
    (g/g-timeout-add DISK-POLL-MS (callable (fn [_data] (tick!) 1) [:pointer] :int :on-throw 1) ffi/null)
    {:ui {:disk-tick!* tick!}}))

(def part
  "The disk-poll part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :disk-poll}))
