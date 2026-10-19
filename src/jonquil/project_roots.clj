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
  must not shadow the IDE's own namespaces. use-project! sets :paths only,
  which needs no network; use-project-deps! also resolves :deps through
  jolt.deps/resolve-project, the same resolver the jolt CLI and an
  out-of-process child use (git and Maven dependencies fetched on first
  use, so it runs on the eval worker, never the GTK thread). The roots are
  process-wide, which is one window's worth: Jonquil runs one window per
  process."
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

(defonce ^:private resolved
  ;; {[dir deps.edn-mtime] {:roots [...]} or {:roots [...] :problem msg}}:
  ;; one resolve per deps.edn edit, a failure included, so a project whose
  ;; dependency can't be fetched isn't fetched again on every Run.
  (atom {}))

(defn- resolve-project-roots
  "{:roots [...]} for dir's whole deps.edn (:paths, then each dependency's
  root, in classpath order), or {:roots (paths dir) :problem msg} when it
  can't be resolved."
  [dir]
  (try
    (let [resolve-project (requiring-resolve 'jolt.deps/resolve-project)]
      ;; canonical, as paths' are: a :local/root comes back as
      ;; app/../lib/src (measured)
      {:roots (mapv (fn [r] (let [f (io/file r)] (if (.exists f) (.getCanonicalPath f) r)))
                    (:roots (resolve-project dir)))})
    (catch Throwable e
      {:roots (paths dir)
       :problem (str "couldn't resolve " dir "/deps.edn, so only its :paths are loaded: "
                     (or (ex-message e) (str (class e))))})))

(defn use-project-deps!
  "use-project!, with dir's :deps resolved too when it has a deps.edn: the
  dependencies' roots go after its :paths, all after the roots Jolt started
  with. Cached per deps.edn edit. Prints why on *err* (the console, on the
  eval worker) when a fresh resolve fails, and the roots are then :paths
  only. true when the roots were set, as use-project!."
  [dir]
  (let [f (when dir (io/file dir "deps.edn"))]
    (if-not (and f (.isFile f))
      (use-project! dir)
      (let [k [(.getCanonicalPath (io/file dir)) (.lastModified f)]
            hit (get @resolved k)
            r (or hit (let [r (resolve-project-roots (first k))]
                        (swap! resolved assoc k r)
                        r))
            set-roots! (host-fn 'jolt.host/set-source-roots!)
            base (or @base-roots
                     (when-let [g (host-fn 'jolt.host/source-roots)]
                       (reset! base-roots (vec (g)))))]
        (when (and (:problem r) (not hit))
          (binding [*out* *err*] (println (str "; " (:problem r)))))
        (if (and set-roots! base)
          (do (set-roots! (vec (distinct (concat base (:roots r))))) true)
          (use-project! dir))))))
