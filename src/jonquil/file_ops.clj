(ns jonquil.file-ops
  "File operations for the file tree as plain functions (new file, new
  folder, rename) plus the text the tree shows around them. Touches the disk
  but never GTK, and none of the operations throw: failures come back as
  {:error message}.

  Measured facts this namespace is built on (Jolt v0.8.22, macOS):
  - java.io.File/renameTo silently overwrites an existing target, so it is
    never used for a rename onto another name.
  - java.nio.file.Files/move with no options refuses an existing target with
    FileAlreadyExistsException and moves a folder with its contents.
  - On a case-insensitive volume Files/move also refuses a case-only rename
    (G.clj -> g.clj), where renameTo works. Files/isSameFile is true for the
    two spellings, which is how that case is told apart.
  - Files/createFile and Files/createDirectory refuse an existing name.

  Error messages name the path the caller passed, never a canonical spelling
  they didn't type (the rule save-to! follows)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file CopyOption FileAlreadyExistsException Files)
           (java.nio.file.attribute FileAttribute)))

(defn- canonical [p] (.getCanonicalPath (io/file p)))

(defn- same-file?
  "True when a and b are the same file on disk (so two spellings of one name
  on a case-insensitive volume). False when either is missing."
  [a b]
  (try (boolean (Files/isSameFile (.toPath (io/file a)) (.toPath (io/file b))))
       (catch Exception _ false)))

(defn- case-only?
  "True when target is old under another spelling of the same name: the same
  file on disk AND names equal ignoring case. A symlink or hard link to old
  is the same file too, but its name differs, so it is not a case-only
  rename and must not be overwritten. On a case-sensitive volume a distinct
  entry spelled exactly like the new name (A.clj linked to a.clj) is the same
  hazard, so the parent's listing must not hold that spelling unless it is
  the old name itself."
  [old target]
  (let [o (io/file old) t (io/file target)
        on (.getName o) tn (.getName t)]
    (and (.equalsIgnoreCase on tn)
         (same-file? o t)
         (or (= on tn)
             (not-any? #{tn} (.list (.getAbsoluteFile (.getParentFile (.getAbsoluteFile t)))))))))

(defn name-problem
  "Why name can't be used in dir, or nil when it can. old-path is the entry
  being renamed (omit for a new one): the same file under another spelling
  of its name is fine, and so is its exact name."
  [{:keys [dir name old-path]}]
  (let [n (str/trim (str name))]
    (cond
      (str/blank? n) "Type a name."
      ;; A NUL ends the C string the OS sees, so "a.clj\u0000" would test
      ;; (and create) a.clj.
      (str/includes? n "\u0000") "That name has a character that can't be used."
      (str/includes? n "/") "A name can't contain /."
      (#{"." ".."} n) "That name is reserved."
      (let [f (io/file dir n)]
        (and (.exists f) (not (and old-path (case-only? old-path f)))))
      (str n " already exists here.")
      :else nil)))

(defn- failure
  "The error message for an exception that stopped an operation on shown (the
  path as the caller passed it)."
  [shown ^Exception e]
  (if (instance? FileAlreadyExistsException e)
    (str (.getName (io/file shown)) " already exists here.")
    (str "Couldn't change " shown ": " (or (.getMessage e) (str (class e))))))

(defn- create!
  [create-fn dir name]
  (let [f (io/file dir (str/trim (str name)))]
    (try (create-fn (.toPath f) (make-array FileAttribute 0))
         {:path (canonical f)}
         (catch Exception e {:error (failure (.getPath f) e)}))))

(defn create-file!
  "Creates an empty file name in dir. {:path canonical} or {:error msg}; an
  existing name is an error and the existing file is untouched."
  [dir name]
  (create! (fn [p attrs] (Files/createFile p attrs)) dir name))

(defn create-folder!
  "Creates a folder name in dir. {:path canonical} or {:error msg}; an
  existing name is an error."
  [dir name]
  (create! (fn [p attrs] (Files/createDirectory p attrs)) dir name))

(defn rename!
  "Renames old-path to new-name within its parent folder. {:path canonical}
  or {:error msg}. A case-only rename (same file on disk) uses renameTo;
  anything else uses Files/move with no options, so a target that appeared
  after name-problem checked is refused rather than overwritten."
  [old-path new-name']
  (let [new-name (str/trim (str new-name'))
        old (io/file old-path)
        target (io/file (.getParentFile (.getAbsoluteFile old)) new-name)]
    (try
      (if (case-only? old target)
        (when-not (.renameTo old target)
          (throw (java.io.IOException. "rename failed")))
        (Files/move (.toPath old) (.toPath target) (make-array CopyOption 0)))
      {:path (canonical target)}
      (catch FileAlreadyExistsException _
        {:error (str new-name " already exists here.")})
      (catch Exception e {:error (failure old-path e)}))))

(defn retarget
  "Where path ends up after old was renamed or moved to new: new when path is
  old itself, new plus the rest when it is under old, else nil. path matches
  in its canonical form (a path reached through .. or a symlink) or, failing
  that, as given (a tab's stale spelling after a case-only rename). The
  canonical form goes first: a given path like /r/src/../src/x.clj starts
  with /r/src/ too, and its rest would keep the .."
  [path old new]
  (let [given (str path)
        canon (try (canonical path) (catch Exception _ nil))
        hit (fn [p]
              (cond
                (= p old) new
                (str/starts-with? p (str old "/")) (str new (subs p (count old)))))]
    (or (when canon (hit canon)) (hit given))))

(defn trash-alert
  "The confirm dialog for trashing name: {:message :buttons}."
  [name dir?]
  {:message (if dir?
              (str "Move the folder " name " and everything in it to the Trash?")
              (str "Move " name " to the Trash?"))
   :buttons ["Move to Trash" "Cancel"]})

(defn trash-message
  "The transcript line after trashing name, n-tabs being how many open tabs
  were on it (dir? when name is a folder). Ends in a newline. Save can write
  a trashed file back, but not a file in a trashed folder (save-to! doesn't
  create the folder), so the folder wording points at Save As."
  [name n-tabs dir?]
  (if dir?
    (str "; Moved the folder " name " to the Trash."
         (case n-tabs
           0 ""
           1 " A tab on a file in it is still open; use Save As to keep it."
           (str " " n-tabs " tabs on files in it are still open; use Save As to keep them."))
         "\n")
    (str "; Moved " name " to the Trash."
         (case n-tabs
           0 ""
           1 " Its tab is still open; Save writes it back."
           (str " " n-tabs " tabs on it are still open; Save writes them back."))
         "\n")))

(defn hidden-message
  "The transcript line when a new entry is hidden by the tree's filters."
  [name]
  (str "; " name " is hidden in the tree (dotfile or .gitignore).\n"))
