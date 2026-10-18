(ns jonquil.project-roots
  "The project's own source folders, for Jolt running in Jonquil's process.

  Out of process, the Jolt child starts in the project with its deps.edn as
  the classpath. In process, Jolt only has the roots it started with, so a
  project kept there ({:runtime :in-process}) couldn't require its own
  namespaces: \"Could not locate demo/util.jolt (or .clj/.cljc) on the
  source roots\" (measured 2026-10-18, Jolt v0.8.23). use-project! puts
  the project's :paths after those roots, through jolt.host's
  set-source-roots!, the seam jolt.main and jolt.deps use to set up a
  project. After, not before (review finding 4): Jolt and Jonquil run in
  this same process, and a project's src/clojure/... or src/jonquil/...
  must not shadow the IDE's own namespaces. Only :paths: a dependency in
  :deps is not resolved here. The roots are process-wide, which is one
  window's worth: Jonquil runs one window per process."
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
            ;; any collection of strings (a set too); anything else is nothing
            (if (coll? ps) ps [])))))

(defn- host-fn [sym]
  (try (some-> (resolve sym) deref) (catch Exception _ nil)))

(defonce ^:private base-roots
  ;; The roots Jolt started with, kept so switching to another project
  ;; replaces the old project's roots rather than piling them up. Read when
  ;; this namespace loads, before any project is used, and again later only
  ;; while it's still unknown (review finding 3: a nil is never kept).
  (atom (when-let [f (host-fn 'jolt.host/source-roots)] (vec (f)))))

(defonce ^:private warned? (atom false))

(defn use-project!
  "Makes dir's source folders (paths) roots Jolt searches, after the roots
  it started with; nil dir leaves only those. true when the roots were set.
  Without jolt.host's seam it says so once on stderr and returns false
  (review finding 2), since a project kept in process then can't require
  its own namespaces."
  [dir]
  (let [set-roots! (host-fn 'jolt.host/set-source-roots!)
        base (or @base-roots
                 (when-let [f (host-fn 'jolt.host/source-roots)]
                   (reset! base-roots (vec (f)))))]
    (if (and set-roots! base)
      (do (set-roots! (vec (distinct (concat base (paths dir))))) true)
      (do (when (and dir (compare-and-set! warned? false true))
            (binding [*out* *err*]
              (println "jonquil: this Jolt has no jolt.host/set-source-roots!, so a project kept in process can't load its own namespaces")))
          false))))
