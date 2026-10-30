(ns jonquil.nrepl.info-middleware
  "nREPL middleware for an out-of-process Jolt child: the jonquil-info op,
  where a var is defined, for jump to definition. jolt-lang/nrepl's lookup
  answers a var's doc and arglists but no file or line.

  Nothing in Jonquil requires this namespace. jonquil.nrepl.middleware-files
  embeds its text with the inspector's and stdin's, and the child loads it
  the same way, after jolt-lang/nrepl's middleware (or alone, on a plain
  server).

  It reads the var's own metadata, which Jolt records as it loads a file
  (measured 2026-10-18, Jolt v0.8.23): a project namespace's var has
  :file \"./src/demo/util.clj\", relative to the directory jolt started in,
  which for the child is the project (a newer Jolt records
  \"demo/util.clj\", see file-of); a var from a jar dependency has a
  jar:file: URL; a var built into Jolt (clojure.core) has no :file, and
  clojure.string's has a :line but no :file. jolt-lang/nrepl's cider info
  middleware answers the same and more, but loading orchard for it added
  about 0.6 s to every child start (0.30 s against 0.92 s, measured the
  same day), so this reads the metadata itself."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.nrepl :as server]))

(def op "jonquil-info")

(defn- classpath-file
  "A bare classpath name through io/resource on the child's classpath: an
  existing file: URL's canonical path, a jar:file:...!/... URL as it is,
  else nil. jonquil.source/classpath-file's twin (this text loads in the
  child, where jonquil.source isn't)."
  [cp-name]
  (when-let [u (try (io/resource cp-name) (catch Throwable _ nil))]
    (let [s (str u)]
      (cond
        (and (str/starts-with? s "jar:file:") (str/includes? s "!/")) s
        (str/starts-with? s "file:")
        (let [x (io/file (.getPath (java.net.URI. s)))]
          (when (.isFile x) (.getCanonicalPath x)))))))

(defn- file-of
  "A var's :file as something that opens on this machine: a jar: or file:
  URL as it is; an absolute path, or one starting ./ (relative to the
  child's directory, the project), as its canonical path when it names a
  file; a bare name such as demo/util.clj through classpath-file; else nil.
  A bare name is a classpath name, not a path: resolving it against the
  project directory could open an unrelated file of the same name there.
  Jolt from jolt-lang/jolt f714b0ed (2026-10-19)
  records a required namespace's :file as that bare name, as JVM Clojure
  does; v0.8.23 recorded ./src/demo/util.clj."
  [f]
  (when (and (string? f) (not (str/blank? f)))
    (cond
      (or (str/starts-with? f "jar:") (str/starts-with? f "file:")) f
      (or (str/starts-with? f "/") (str/starts-with? f "./"))
      (let [x (io/file f)] (when (.isFile x) (.getCanonicalPath x)))
      :else (try (classpath-file f) (catch Throwable _ nil)))))

(defn info
  "The reply fields for sym resolved in the namespace named ns-str (user
  when that isn't loaded): name, ns, doc, arglists-str, and file and line
  when the var records a file that opens here. nil when sym isn't a var
  there."
  [ns-str sym]
  (let [the-ns* (or (when (and ns-str (not (str/blank? ns-str))) (find-ns (symbol ns-str)))
                    (the-ns 'user))
        v (when (and sym (not (str/blank? sym)))
            (try (ns-resolve the-ns* (symbol sym)) (catch Throwable _ nil)))]
    (when (var? v)
      (let [m (meta v)
            file (file-of (:file m))]
        ;; :name falls back to sym, and a missing :ns is left out rather
        ;; than thrown on, as jolt-lang/nrepl's lookup does (findings 2, 3).
        (cond-> {"name" (str (or (:name m) sym))}
          (:ns m) (assoc "ns" (str (ns-name (:ns m))))
          (:doc m) (assoc "doc" (:doc m))
          (:arglists m) (assoc "arglists-str" (pr-str (:arglists m)))
          file (assoc "file" file)
          (and file (:line m)) (assoc "line" (:line m)))))))

(defn handle
  "The response for a jonquil-info request, or nil for any other op: info's
  fields and done, or done alone when sym isn't a var."
  [request]
  (when (= op (get request "op"))
    (assoc (or (try (info (get request "ns") (get request "sym")) (catch Throwable _ nil)) {})
           "status" ["done"])))

(defn jonquil-info
  "The middleware: answers jonquil-info on the reader thread (a metadata
  read, nothing that can wait), hands everything else on."
  [handler]
  (server/register-ops! op)
  (fn [request]
    (if-let [r (handle request)]
      (do (try (server/respond request r) (catch Throwable _ nil)) nil)
      (handler request))))
