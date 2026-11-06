(ns jonquil.replay
  "The record behind \"replay last session\": per project, an EDN map
  {:version 1 :previous [entry ...] :current [entry ...] :history [text ...]}
  in replays/<folder>-<hash>.edn beside settings.edn. An entry is
  {:kind :file :path abs :at ms :dialect name} or
  {:kind :form :text s :ns \"user\" :source-file abs-or-nil :at ms :dialect name}.
  The record holds whatever the user typed, secrets included, so the file is
  owner-only (rw-------) and a directory made for it is rwx------. Nothing
  here throws: loading a bad file gives an empty record plus a :warning, and
  saving returns nil or an error string."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.last-session :as last-session]))

(defn empty-record
  "A record with nothing in it."
  []
  {:version 1 :previous [] :current [] :history []})

(defn record-path
  "The project's record file: replays/<folder>-<hash>.edn beside the
  settings file, or replays/no-folder.edn for a nil root. The leaf and hash
  are made as jonquil.world/world-dir makes them."
  [settings-path root]
  (let [dir (str (.getParent (java.io.File. ^String settings-path)) "/replays")]
    (if root
      (let [leaf (str/replace (.getName (java.io.File. ^String root)) #"[^A-Za-z0-9._-]" "-")]
        (str dir "/" leaf "-" (Integer/toHexString (.hashCode ^String root)) ".edn"))
      (str dir "/no-folder.edn"))))

(defn- file-name [path]
  (.getName (java.io.File. ^String path)))

(defn- good-entry? [e]
  (and (map? e) (contains? #{:file :form} (:kind e))))

(defn- clean-entries [xs]
  (vec (filter good-entry? xs)))

(defn load-record
  "{:record r} from path. A missing file is {:record (empty-record)}. A file
  that isn't EDN, isn't a map, or whose :previous, :current or :history
  isn't sequential (when present) is the empty record plus a :warning.
  Entries that aren't maps with :kind :file or :form are dropped silently."
  [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      {:record (empty-record)}
      (let [m (try (edn/read-string (slurp f)) (catch Exception _ ::unreadable))
            ok? (and (map? m)
                     (every? #(or (nil? (get m %)) (sequential? (get m %)))
                             [:previous :current :history]))]
        (if ok?
          {:record {:version 1
                    :previous (clean-entries (:previous m))
                    :current (clean-entries (:current m))
                    :history (vec (filter string? (:history m)))}}
          {:record (empty-record)
           :warning (str "Couldn't read " (file-name path)
                         ", so the last session starts empty.")})))))

(def ^:private owner-only-dir "rwx------")

(defn- make-private-dir!
  "Makes the directory of path as rwx------ when it doesn't exist yet.
  Never throws."
  [path]
  (try
    (let [dir (.getParentFile (java.io.File. ^String path))]
      (when (and dir (not (.exists dir)))
        (.mkdirs dir)
        (java.nio.file.Files/setPosixFilePermissions
         (.toPath dir)
         (java.nio.file.attribute.PosixFilePermissions/fromString owner-only-dir))))
    (catch Exception _ nil)))

(defn save-record!
  "Writes record to path atomically; the file is rw------- from the moment
  it exists. A replays/ directory it has to make is rwx------. Failing to
  set permissions never fails the save. Never throws: nil on success, else
  \"couldn't save <path>: <message>\"."
  [path record]
  (try
    (make-private-dir! path)
    (last-session/write-atomically! path (pr-str record) {:owner-only? true})
    (catch Exception e
      (str "couldn't save " path ": " (.getMessage e)))))
