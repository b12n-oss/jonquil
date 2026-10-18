(ns jonquil.nrepl.inspect-files
  "The out-of-process inspector's source files, carried inside Jonquil and
  written where a Jolt child can load them as a :local/root dep.

  The installed app is a compiled binary with no source tree, and
  io/resource of a source file is nil there at runtime, so embedded-text
  reads each file when this namespace is compiled (measured 2026-10-18: the
  text survives jolt build exactly, with the source folder hidden)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defmacro ^:private embedded-text
  "The text of classpath file path, read when this form is compiled."
  [path]
  (slurp (io/resource path)))

(def sources
  "Classpath-relative path -> text, for every file the child loads."
  {"jonquil/inspect/view.clj" (embedded-text "jonquil/inspect/view.clj")
   "jonquil/nrepl/inspect_middleware.clj" (embedded-text "jonquil/nrepl/inspect_middleware.clj")})

(def middleware
  "The middleware symbol for :nrepl/middleware."
  'jonquil.nrepl.inspect-middleware/inspect)

(def lib
  "The dep name the root is added under in the child's -Sdeps."
  'jonquil/nrepl-inspect)

(defn cache-base
  "$XDG_CACHE_HOME/jonquil/nrepl-inspect, or ~/.cache/jonquil/nrepl-inspect
  when that is unset, blank or relative (the XDG rule settings.clj follows)."
  [env home]
  (let [xdg (get env "XDG_CACHE_HOME")]
    (str (if (and xdg (str/starts-with? xdg "/")) xdg (str home "/.cache"))
         "/jonquil/nrepl-inspect")))

(defn- folder-name []
  (format "%08x" (bit-and (hash (apply str (sort sources))) 0xffffffff)))

(defn- complete? [dir]
  (and (.exists (io/file dir "deps.edn"))
       (every? (fn [[rel text]] (let [f (io/file dir "src" rel)]
                                  (and (.exists f) (= text (slurp f)))))
               sources)))

(defn- delete-tree! [f]
  (doseq [x (reverse (file-seq (io/file f)))] (.delete x)))

(defn ensure-root!
  "The folder under base holding this Jonquil's inspector files, written
  first when it isn't there: into a temp sibling, then renamed, so a child
  never reads half a file. A lost rename race uses the winner's copy. The
  folder is named by a hash of the text, so a newer Jonquil writes its own.
  nil when anything fails: the child then starts without an inspector."
  ([] (ensure-root! (cache-base (System/getenv) (System/getProperty "user.home"))))
  ([base]
   (try
     (let [dir (io/file base (folder-name))]
       (if (complete? dir)
         (.getCanonicalPath dir)
         (let [tmp (io/file base (str (folder-name) ".tmp-" (System/nanoTime)))]
           (try
             (doseq [[rel text] sources]
               (let [f (io/file tmp "src" rel)]
                 (io/make-parents f)
                 (spit f text)))
             (spit (io/file tmp "deps.edn") "{:paths [\"src\"]}\n")
             (when (.exists dir) (delete-tree! dir))
             (.renameTo tmp dir)
             (when (complete? dir) (.getCanonicalPath dir))
             (finally (when (.exists tmp) (delete-tree! tmp)))))))
     (catch Throwable _ nil))))
