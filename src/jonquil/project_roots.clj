(ns jonquil.project-roots
  "The project's own source folders, for Jolt running in Jonquil's process.

  Out of process, the Jolt child starts in the project with its deps.edn as
  the classpath. In process, Jolt only has the roots it started with, so a
  project kept there ({:runtime :in-process}) couldn't require its own
  namespaces: \"Could not locate demo/util.jolt (or .clj/.cljc) on the
  source roots\" (measured 2026-10-18, Jolt v0.8.23). use-project! puts
  the project's :paths in front of those roots, through jolt.host's
  set-source-roots!, the seam jolt.main and jolt.deps use to set up a
  project. Only :paths: a dependency in :deps is not resolved here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn paths
  "The project's source folders as absolute paths: deps.edn's :paths (the
  Clojure CLI's default [\"src\"] when the file or the key is missing),
  each resolved against dir, kept only when it is a folder. A path that
  isn't a string (an alias keyword) is skipped, and a deps.edn that
  doesn't read as a map counts as missing. [] without a dir."
  [dir]
  (if-not dir
    []
    (let [f (io/file dir "deps.edn")
          deps (when (.isFile f)
                 (try (let [m (edn/read-string (slurp f))] (when (map? m) m))
                      (catch Exception _ nil)))
          ps (if (contains? deps :paths) (:paths deps) ["src"])]
      (into []
            (comp (filter string?)
                  (map (fn [p] (let [x (io/file p)] (if (.isAbsolute x) x (io/file dir p)))))
                  (filter #(.isDirectory ^java.io.File %))
                  (map #(.getCanonicalPath ^java.io.File %))
                  (distinct))
            (if (sequential? ps) ps [])))))

(defn- host-fn [sym]
  (try (some-> (resolve sym) deref) (catch Exception _ nil)))

(defonce ^:private base-roots
  ;; The roots Jolt started with, read the first time a project is used, so
  ;; switching to another project replaces the old project's roots rather
  ;; than piling them up.
  (delay (when-let [f (host-fn 'jolt.host/source-roots)] (vec (f)))))

(defn use-project!
  "Makes dir's source folders (paths) the first roots Jolt searches, ahead
  of the roots it started with; nil dir leaves only those. A no-op when
  jolt.host doesn't offer the seam."
  [dir]
  (when-let [set-roots! (host-fn 'jolt.host/set-source-roots!)]
    (when-let [base @base-roots]
      (set-roots! (vec (distinct (concat (paths dir) base)))))))
