(ns jonquil.nrepl.middleware-files
  "Jonquil's own nREPL middleware for an out-of-process Jolt child (the
  inspector and stdin) as source files, carried inside Jonquil and written
  where the child can load them as a :local/root dep.

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
   "jonquil/nrepl/inspect_middleware.clj" (embedded-text "jonquil/nrepl/inspect_middleware.clj")
   "jonquil/nrepl/stdin_middleware.clj" (embedded-text "jonquil/nrepl/stdin_middleware.clj")})

(def inspect-middleware
  "The inspector's :nrepl/middleware symbol: last, after jolt-lang/nrepl's."
  'jonquil.nrepl.inspect-middleware/inspect)

(def stdin-middleware
  "stdin's :nrepl/middleware symbol: first, outside jolt-lang/nrepl's
  session middleware, whose clone it has to wrap."
  'jonquil.nrepl.stdin-middleware/stdin)

(def lib
  "The dep name the root is added under in the child's -Sdeps."
  'jonquil/nrepl-middleware)

(defn cache-base
  "$XDG_CACHE_HOME/jonquil/nrepl-middleware, or ~/.cache/jonquil/nrepl-middleware
  when that is unset, blank or relative (the XDG rule settings.clj follows)."
  [env home]
  (let [xdg (get env "XDG_CACHE_HOME")]
    (str (if (and xdg (str/starts-with? xdg "/")) xdg (str home "/.cache"))
         "/jonquil/nrepl-middleware")))

(defn- folder-name []
  (format "%08x" (bit-and (hash (apply str (sort sources))) 0xffffffff)))

(defn- complete? [dir]
  (and (.exists (io/file dir "deps.edn"))
       (every? (fn [[rel text]] (let [f (io/file dir "src" rel)]
                                  (and (.exists f) (= text (slurp f)))))
               sources)))

(defn- delete-tree! [f]
  (doseq [x (reverse (file-seq (io/file f)))] (.delete x)))

(defn- write-tmp!
  "A fresh sibling of dir holding every file, ready to rename into place."
  [dir]
  (let [tmp (io/file (.getParentFile (io/file dir)) (str (.getName (io/file dir)) ".tmp-" (System/nanoTime)))]
    (doseq [[rel text] sources]
      (let [f (io/file tmp "src" rel)]
        (io/make-parents f)
        (spit f text)))
    (spit (io/file tmp "deps.edn") "{:paths [\"src\"]}\n")
    tmp))

(defn ensure-root!
  "The folder under base holding this Jonquil's inspector files, written
  first when it isn't there. The files go into a temp sibling that is then
  renamed into place, so a child never reads half a file. A rename onto a
  folder that exists fails (macOS and Linux both refuse a non-empty
  target), and nothing deletes a complete folder: when another window won
  the race, its copy is used. Only a folder whose files differ (a crash
  mid-write, or another Jonquil whose files hash the same) is moved aside
  and deleted before the rename is tried again. Named by a hash of the
  text, so a newer Jonquil writes its own. Throws when it can't be written;
  the evaluator then starts the child without an inspector and says why."
  ([] (ensure-root! (cache-base (System/getenv) (System/getProperty "user.home"))))
  ([base]
   (let [dir (io/file base (folder-name))]
     (if (complete? dir)
       (.getCanonicalPath dir)
       (let [tmp (write-tmp! dir)]
         (try
           (when-not (.renameTo tmp dir)
             (when (and (.exists dir) (not (complete? dir)))
               (let [aside (io/file base (str (.getName dir) ".stale-" (System/nanoTime)))]
                 (when (.renameTo dir aside) (delete-tree! aside))))
             (.renameTo tmp dir))
           (if (complete? dir)
             (.getCanonicalPath dir)
             (throw (ex-info (str "couldn't write " (.getPath dir)) {:dir (.getPath dir)})))
           (finally (when (.exists tmp) (delete-tree! tmp)))))))))
