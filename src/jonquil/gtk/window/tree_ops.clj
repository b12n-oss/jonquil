(ns jonquil.gtk.window.tree-ops
  "The file tree's file operations: New File, New Folder, Rename and Move to Trash. Each runs inside the tree's popover, so the disk change happens now and the tree rebuild and any tab change wait for the next main-loop turn; a rename moves the tabs on the old path, and the trash asks first through the window's one alert guard. build makes activate!'s tree pane and its two forward atoms. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window unchanged; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [clojure.string :as str]
            [jonquil.file-ops :as ops]
            [jonquil.file-tree :as ft]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.file-tree-pane :as file-tree-pane]
            [jonquil.gtk.trash :as trash]
            [jonquil.gtk.window.alerts :as win-alerts]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.gtk.window.tabs :as win-tabs]))

;; --- file operations from the tree ---------------------------------------------
;; The pane calls these inside its popover's own signal emission (the entry's
;; activate, a button's clicked). The disk operation runs now, so an error
;; string can go back to the entry; the tree rebuild and any tab opening wait
;; for the next main-loop turn, since a rebuild inside the emission would
;; tear down rows under it. ctx is the window's tab map (*tab-ctx), which
;; carries :tree-pane.

(defn- tree-later!
  "Runs f on the next main-loop turn; a throw goes to the console under what."
  [cv what f]
  (on-gui! (fn [] (try (f) (catch :default e (cview/append! cv :error (str "; " what ": " (ex-message e) "\n")))))))

(defn- tree-failed!
  "Reports msg on the console under what, refreshes the tree next turn and
  returns msg for the name entry."
  [{:keys [cv tree-pane]} what msg]
  (cview/append! cv :error (str "; " what ": " msg "\n"))
  (tree-later! cv what (fn [] (file-tree-pane/refresh! tree-pane)))
  msg)

(defn- show-in-tree!
  "Rebuilds the tree with path's folders expanded and selects its row."
  [tree-pane path]
  (file-tree-pane/show-model! tree-pane (ft/expand-to (ft/prune @(:state tree-pane)) path))
  (file-tree-pane/select-path! tree-pane path))

(defn- note-if-hidden!
  "Says so on the console when the tree's filters hide the new entry at path."
  [{:keys [cv tree-pane]} path dir?]
  (let [n (.getName (java.io.File. ^String path))]
    (when (ft/ignored? (:ignore @(:state tree-pane)) n dir?)
      (cview/append! cv :system (ops/hidden-message n)))))

(defn- stale-tab-problem
  "The entry's problem when a tab not in except still holds path, or a path
  under it (a tab left open on a trashed file), else nil. Making path again
  would hand that tab a file it never had."
  [tabs path except]
  (when (some (fn [t] (and (not-any? (fn [x] (identical? x t)) except) (ops/retarget @(:path t) path path))) tabs)
    (str (.getName (java.io.File. ^String path)) " is open in a tab; close that tab first.")))

(defn- tree-new-file!
  "New File... in dir: creates name, then shows and selects it and opens it
  in a tab. nil, or the error string for the entry (a tab still on that
  path is checked before the disk is touched)."
  [{:keys [cv tree-pane tabs] :as ctx} dir name]
  (let [target (.getAbsolutePath (java.io.File. ^String dir (str/trim (str name))))
        {:keys [path error]} (if-let [p (stale-tab-problem @tabs target [])]
                               {:error p}
                               (ops/create-file! dir name))]
    (if error
      (tree-failed! ctx "new file" error)
      (do (tree-later! cv "new file" (fn []
                                       (show-in-tree! tree-pane path)
                                       (win-tabs/open-file! ctx path)
                                       (note-if-hidden! ctx path false)))
          nil))))

(defn- tree-new-folder!
  "New Folder... in dir: creates name, then shows and selects it. nil, or
  the error string for the entry."
  [{:keys [cv tree-pane] :as ctx} dir name]
  (let [{:keys [path error]} (ops/create-folder! dir name)]
    (if error
      (tree-failed! ctx "new folder" error)
      (do (tree-later! cv "new folder" (fn []
                                         (show-in-tree! tree-pane path)
                                         (note-if-hidden! ctx path true)))
          nil))))

(defn- tree-rename!
  "Rename...: renames old (the entry as listed: a symlink itself, never its
  target) to new-name, then every tab on old (or under it, for a folder)
  follows to the new listed path, keeping its text and dirty state. A tab on
  a symlink's target doesn't move, since the target didn't. The rename runs
  now; the tab paths (a path change rebuilds the tree through the tab's
  watch), labels, title and tree wait for the next turn. Which tabs move is
  decided before the move: a tab spelled differently from old (A.clj for
  a.clj on a case-insensitive volume) resolves only while old exists.
  Expanded folders at or under old stay expanded under the new name. A tab
  still on the new path refuses the rename. nil, or the error string for the entry."
  [{:keys [cv tree-pane tabs win active-tab] :as ctx} old new-name]
  (let [new (.getAbsolutePath (java.io.File. (.getParentFile (java.io.File. ^String old)) (str/trim (str new-name))))
        moves (vec (keep (fn [t] (when-let [p (ops/retarget @(:path t) old new)] [t p])) @tabs))
        old-canon (.getCanonicalPath (java.io.File. ^String old))
        {:keys [error path]} (if-let [p (stale-tab-problem @tabs new (map first moves))]
                               {:error p}
                               (ops/rename! old new-name))]
    (if error
      (tree-failed! ctx "rename" error)
      (do (tree-later! cv "rename"
                       (fn []
                         ;; before the tab paths change, since a path change
                         ;; rebuilds the tree (pruning the old folders)
                         (swap! (:state tree-pane) update :expanded
                                (fn [e] (set (map (fn [d] (cond
                                                            (= d old-canon) path
                                                            (str/starts-with? d (str old-canon "/")) (str path (subs d (count old-canon)))
                                                            :else d))
                                                  e))))
                         (doseq [[t p] moves]
                           (reset! (:path t) p))
                         (win-tabs/refresh-labels! ctx)
                         (let [t (active-tab)] (win-files/update-title! (:ed t) win (:path t)))
                         (show-in-tree! tree-pane new)))
          nil))))

(defn- listed-dir?
  "Whether path is a folder itself, not following a symlink: a link to a
  folder is trashed as a link, so it isn't called a folder."
  [path]
  (java.nio.file.Files/isDirectory (.toPath (java.io.File. ^String path))
                                   (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS])))

(defn trash-choice!
  "The trash alert's decision for path (the entry as listed, so a symlink
  goes to the Trash, not its target): index 0 moves it to the Trash and
  says how many open tabs were on it (they stay open, text and all); any
  other index or a dismissal does nothing. The count is taken before the
  move, while the path still resolves. Rows call this with a synthetic
  {:index n}, exactly as the alert's completion does."
  [{:keys [cv tabs tree-pane]} path {:keys [index]}]
  (when (= index 0)
    (let [n-tabs (count (filter (fn [t] (ops/retarget @(:path t) path path)) @tabs))
          dir? (listed-dir? path)
          {:keys [error]} (trash/trash! path)]
      (if error
        (cview/append! cv :error (str "; trash: " error "\n"))
        (cview/append! cv :system (ops/trash-message (.getName (java.io.File. ^String path)) n-tabs dir?)))
      (tree-later! cv "trash" (fn [] (file-tree-pane/refresh! tree-pane))))))

(defn- tree-trash!
  "Move to Trash: asks first (Cancel is the default), sharing the close
  alerts' alert-pending? guard, so a second alert never opens over one."
  [{:keys [win cv] :as ctx} path]
  (when-not @win-alerts/alert-pending?
    (reset! win-alerts/alert-pending? true)
    (try
      (let [f (java.io.File. ^String path)]
        (win-alerts/show-alert! win (ops/trash-alert (.getName f) (listed-dir? path)) win-alerts/TRASH-ALERT
                                (fn [result] (trash-choice! ctx path result))))
      (catch :default e
        (reset! win-alerts/alert-pending? false)
        (cview/append! cv :error (str "; trash: " (ex-message e) "\n")))))
  nil)

(defn build
  "activate!'s file tree binding, in activate!'s order: the two forward
  atoms the pane's callbacks read, *open-folder (Open Folder...'s dialog
  fn) and *folder-result (its result handler), both reset later by
  activate!, then the tree pane, whose callbacks open a file in a tab, run
  Open Folder..., say an error on the status line and run New File, New
  Folder, Rename and Move to Trash. w holds what they read: :status-label,
  and :tab-ctx* (activate!'s *tab-ctx, read when a callback runs). Returns
  w with :open-folder* :folder-result* and :tree-pane added."
  [{:keys [status-label] *tab-ctx :tab-ctx* :as w}]
  (let [*open-folder (atom nil)
        *folder-result (atom nil)
        tree-pane (file-tree-pane/make-pane {:on-open (fn [p] (win-tabs/open-file! @*tab-ctx p))
                                             :on-open-folder (fn [] (when-let [f @*open-folder] (f)))
                                             :on-error (fn [msg] (win-status/set-status! status-label msg))
                                             :on-new-file (fn [dir n] (tree-new-file! @*tab-ctx dir n))
                                             :on-new-folder (fn [dir n] (tree-new-folder! @*tab-ctx dir n))
                                             :on-rename (fn [p n] (tree-rename! @*tab-ctx p n))
                                             :on-trash (fn [p] (tree-trash! @*tab-ctx p))})]
    (assoc w :open-folder* *open-folder :folder-result* *folder-result :tree-pane tree-pane)))
