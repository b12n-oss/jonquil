(ns jonquil.project-roots
  "The project's own source folders, for Jolt running in Jonquil's process.

  Out of process, the Jolt child starts in the project with its deps.edn as
  the classpath. In process, Jolt only has the roots it started with, so a
  project kept there ({:runtime :in-process}) couldn't require its own
  namespaces: \"Could not locate demo/util.jolt (or .clj/.cljc) on the
  source roots\" (measured 2026-10-18, Jolt v0.8.23). use-project! puts
  the project's :paths after those roots, through jolt.host's
  set-source-roots!, the seam jolt.main and jolt.deps use to set up a
  project. After, not before: Jolt and Jonquil run in
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

(defn- read-deps
  "dir's deps.edn as a map, nil when it is missing or doesn't read as a map."
  [dir]
  (let [f (io/file dir "deps.edn")]
    (when (.isFile f)
      (try (let [m (edn/read-string (slurp f))] (when (map? m) m))
           (catch Exception _ nil)))))

(defn paths
  "The project's source folders as absolute paths: deps.edn's :paths (the
  Clojure CLI's default [\"src\"] when the file or the key is missing),
  each resolved against dir, kept only when it is a folder. A path that
  isn't a string (an alias keyword) is skipped, and a deps.edn that
  doesn't read as a map counts as missing. [] without a dir."
  [dir]
  (if-not dir
    []
    (let [deps (read-deps dir)
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
  ;; while it's still unknown (a nil is never kept).
  (atom (when-let [f (host-fn 'jolt.host/source-roots)] (vec (f)))))

(defonce ^:private warned? (atom false))

(defn- known-base-roots
  "The roots Jolt started with: the kept ones, else read now (and kept when
  there are any), nil when this Jolt can't say."
  []
  (or @base-roots
      (when-let [f (host-fn 'jolt.host/source-roots)]
        (reset! base-roots (vec (f))))))

(defn use-project!
  "Makes dir's source folders (paths) roots Jolt searches, after the roots
  it started with; nil dir leaves only those. true when the roots were set.
  Without jolt.host's seam it says so once on stderr and returns false,
  since a project kept in process then can't require
  its own namespaces."
  [dir]
  (let [set-roots! (host-fn 'jolt.host/set-source-roots!)
        base (known-base-roots)]
    (if (and set-roots! base)
      (do (set-roots! (vec (distinct (concat base (paths dir))))) true)
      (do (when (and dir (compare-and-set! warned? false true))
            (binding [*out* *err*]
              (println "jonquil: this Jolt has no jolt.host/set-source-roots!, so a project kept in process can't load its own namespaces")))
          false))))

(defonce ^:private resolved
  ;; {key {:roots [...]}}, or {:roots [...] :problem msg :at ms} for a
  ;; failure. key is the project dir with its deps.edn's and the user
  ;; deps.edn's modification times, so an edit to either resolves again. A
  ;; failure is kept only failure-retry-ms: long enough
  ;; that a project whose dependency can't be fetched isn't fetched again
  ;; on every keystroke-paced Run, short enough that a network blip heals.
  (atom {}))

(def ^:private failure-retry-ms 30000)

(defn- stopped?
  "True when e is a Stop rather than a failure: Jolt's interrupt signal
  anywhere in its causes, or a Java interrupt."
  [e]
  (loop [c e]
    (cond (nil? c) false
          (instance? InterruptedException c) true
          (:jolt/interrupted (ex-data c)) true
          :else (recur (.getCause ^Throwable c)))))

(defn- resolve-project-roots
  "{:roots [...]} for dir's whole deps.edn (:paths, then each dependency's
  root, in classpath order), or {:roots (paths dir) :problem msg :at ms}
  when it can't be resolved. A Stop during the resolve is rethrown, never
  taken for a failure."
  [dir]
  (try
    (let [resolve-project (requiring-resolve 'jolt.deps/resolve-project)]
      ;; canonical, as paths' are: a :local/root comes back as
      ;; app/../lib/src (measured)
      {:roots (mapv (fn [r] (let [f (io/file r)] (if (.exists f) (.getCanonicalPath f) r)))
                    (:roots (resolve-project dir)))})
    (catch Throwable e
      (when (stopped? e) (throw e))
      {:roots (paths dir)
       :at (System/currentTimeMillis)
       :problem (str "couldn't resolve " dir "/deps.edn, so only its :paths are loaded"
                     " (Jonquil tries again on a Run after " (quot failure-retry-ms 1000) " s): "
                     (or (ex-message e) (str (class e))))})))

(defn- user-deps-mtime
  "The user deps.edn's modification time (the resolver merges it, as the
  clj CLI does), 0 when there's none."
  []
  (let [cfg (or (System/getenv "CLJ_CONFIG")
                (some-> (System/getenv "XDG_CONFIG_HOME") (str "/clojure"))
                (str (System/getProperty "user.home") "/.clojure"))]
    (.lastModified (io/file cfg "deps.edn"))))

(defn- has-deps?
  "True when dir's deps.edn names any :deps, so a resolve may fetch."
  [^java.io.File f]
  (try (boolean (seq (:deps (edn/read-string (slurp f))))) (catch Throwable _ false)))

(defn- resolve-cached
  "[roots-result hit?] for deps.edn file f under cache key k: the kept result
  while it is good (a failure only for failure-retry-ms), else a fresh
  resolve, which says \"Resolving deps.edn...\" first when f names :deps."
  [f k]
  (let [h (get @resolved k)
        hit (when (or (not (:problem h))
                      (< (- (System/currentTimeMillis) (:at h 0)) failure-retry-ms))
              h)]
    [(or hit
         (do (when (has-deps? f) (println "; Resolving deps.edn..."))
             (let [r (resolve-project-roots (first k))]
               (swap! resolved assoc k r)
               r)))
     (some? hit)]))

(defn use-project-deps!
  "use-project!, with dir's :deps resolved too when it has a deps.edn: the
  dependencies' roots go after its :paths, all after the roots Jolt started
  with. Cached per edit of deps.edn or the user deps.edn (a :local/root
  dependency's own deps.edn, or a moved :git/tag, isn't watched: edit the
  project's deps.edn to resolve again). Says \"Resolving deps.edn...\" on
  *out* before a fresh resolve that names :deps, and why on *err* when it
  fails (both the console, on the eval worker); the roots are then :paths
  only. true when the roots were set, as use-project!."
  [dir]
  (let [f (when dir (io/file dir "deps.edn"))]
    (if-not (and f (.isFile f))
      (use-project! dir)
      (let [k [(.getCanonicalPath (io/file dir)) (.lastModified f) (user-deps-mtime)]
            [r hit?] (resolve-cached f k)
            set-roots! (host-fn 'jolt.host/set-source-roots!)
            base (known-base-roots)]
        (when (and (:problem r) (not hit?))
          (binding [*out* *err*] (println (str "; " (:problem r)))))
        (if (and set-roots! base)
          (do (set-roots! (vec (distinct (concat base (:roots r))))) true)
          (use-project! dir))))))
