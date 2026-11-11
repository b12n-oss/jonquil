(ns jonquil.last-session
  "Which files were open in each project the last time the window closed: a
  small EDN map {:version 1 :sessions [{:root :tabs :active} ...]} in
  sessions.edn beside settings.edn, newest project first, at most 20 kept.
  This is not jonquil.session (the evaluation session). Nothing here
  throws: loading a bad file gives an empty list plus a :warning, and
  saving returns nil or an error string. Saving writes a temp file beside
  the target and renames it over the target with ATOMIC_MOVE, the way
  jonquil.settings does."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private max-entries 20)

(def ^:private unreadable-warning
  "Couldn't read sessions.edn, so no tabs were reopened.")

(defn sessions-path
  "The sessions file's path: sessions.edn in the directory of settings-path."
  [settings-path]
  (str (.getParent (io/file settings-path)) "/sessions.edn"))

(defn- absolute? [s]
  (and (string? s) (str/starts-with? s "/")))

(defn- clean-entry
  "The entry e with unusable tabs dropped, or nil when e isn't a usable entry."
  [e]
  (when (and (map? e)
             (or (nil? (:root e)) (absolute? (:root e)))
             (sequential? (:tabs e))
             (or (nil? (:active e)) (string? (:active e))))
    {:root (:root e)
     :tabs (vec (filter absolute? (:tabs e)))
     :active (:active e)}))

(defn load-sessions
  "{:sessions [entry ...]} from path. A missing file is {:sessions []}. A
  file that isn't EDN, isn't a map, or whose :sessions isn't sequential is
  {:sessions [] :warning ..}. Unusable entries are dropped silently."
  [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      {:sessions []}
      (let [m (try (edn/read-string (slurp f)) (catch Exception _ ::unreadable))]
        (if (and (map? m) (sequential? (:sessions m)))
          {:sessions (vec (keep clean-entry (:sessions m)))}
          {:sessions [] :warning unreadable-warning})))))

(defn remember
  "sessions with {:root :tabs :active} put first, any entry with an equal
  :root removed, trimmed to 20."
  [sessions {:keys [root tabs active]}]
  (->> (remove #(= root (:root %)) sessions)
       (cons {:root root :tabs (vec tabs) :active active})
       (take max-entries)
       vec))

(defn entry-for
  "The entry whose :root equals root; with a nil root, the first entry."
  [sessions root]
  (if (nil? root)
    (first sessions)
    (first (filter #(= root (:root %)) sessions))))

(defn- resolved-target
  "path itself, unless it is a symlink, in which case the real file it
  points at. A copy of the private helper in jonquil.settings."
  [path]
  (let [p (.toPath (java.io.File. ^String path))]
    (if (java.nio.file.Files/isSymbolicLink p)
      (str (.toRealPath p (make-array java.nio.file.LinkOption 0)))
      path)))

(defn create-owner-only!
  "Creates path as an empty rw------- file, the mode given as the file is
  made. true when it did; false, never a throw, when it couldn't (the file
  already exists, its directory is missing, or the platform has no POSIX
  file attributes)."
  [path]
  (try
    (java.nio.file.Files/createFile
     (.toPath (java.io.File. ^String path))
     (into-array java.nio.file.attribute.FileAttribute
                 [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                   (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))]))
    true
    (catch Exception _ false)))

(defn write-atomically!
  "Writes text to path atomically (temp file beside the target, then
  ATOMIC_MOVE over it), rewriting a symlink's real target in place. nil on
  success, else \"couldn't save <path>: <message>\". With opts
  {:owner-only? true} the temp file is created rw------- before anything is
  written to it; when that can't be done it is set to rw------- after the
  write, before the move (a failure to set that never fails the write)."
  ([path text] (write-atomically! path text nil))
  ([path text opts]
   (let [tmp (atom nil)]
     (try
       (let [target (resolved-target path)]
         (reset! tmp (str target ".tmp-" (System/nanoTime)))
         (io/make-parents target)
         (let [private? (and (:owner-only? opts) (create-owner-only! @tmp))]
           (if private?
            ;; not spit: on Linux, Jolt's spit replaces the file, dropping the
            ;; rw------- it was created with; Files/write keeps the inode
             (java.nio.file.Files/write
              (.toPath (java.io.File. ^String @tmp))
              (.getBytes ^String text "UTF-8")
              (into-array java.nio.file.OpenOption
                          [java.nio.file.StandardOpenOption/WRITE
                           java.nio.file.StandardOpenOption/TRUNCATE_EXISTING]))
             (spit @tmp text))
           (when (and (:owner-only? opts) (not private?))
             (try (java.nio.file.Files/setPosixFilePermissions
                   (.toPath (java.io.File. ^String @tmp))
                   (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
                  (catch Exception _ nil))))
         (java.nio.file.Files/move
          (.toPath (java.io.File. ^String @tmp))
          (.toPath (java.io.File. ^String target))
          (into-array java.nio.file.CopyOption
                      [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                       java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
         nil)
       (catch Exception e
         (when @tmp (try (io/delete-file @tmp true) (catch Exception _ nil)))
         (str "couldn't save " path ": " (.getMessage e)))))))

(defn save-sessions!
  "Writes {:version 1 :sessions sessions} to path atomically, rewriting a
  symlink's real target in place. nil on success, else
  \"couldn't save <path>: <message>\"."
  [path sessions]
  (write-atomically! path (pr-str {:version 1 :sessions (vec sessions)})))

(defn missing-line
  "The console line for files that couldn't be reopened, with a trailing
  newline, or nil for an empty names seq."
  [names]
  (case (count names)
    0 nil
    1 (str "; Couldn't reopen " (first names) " from last time.\n")
    (str "; Couldn't reopen " (count names) " files from last time: "
         (str/join ", " names) "\n")))
