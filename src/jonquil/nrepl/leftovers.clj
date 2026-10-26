(ns jonquil.nrepl.leftovers
  "What runtime children leave in the temp folder after they're gone, and
  the prune that removes it. A ClojureScript start or setup that fails keeps
  its jonquil-cljs-run- folder so the error can name the log. A Jolt start
  that never ran keeps its jonquil-nrepl-<n>.log. A Jonquil, launcher or
  jank that is SIGKILLed leaves whatever it held (jonquil-cljs-run-,
  jonquil-cljs-, jonquil-jank-run-). An isolated plugin check whose child
  misbehaved keeps its jonquil-plugin-check- folder for a post-mortem
  (jonquil.plugin-isolated/check!). prune! deletes those once untouched for
  a day, the same age middleware-files/prune-old! uses.

  A child with no project runs inside its run folder, so a live one must
  never lose it: each folder holds a pid file naming the process using it,
  and a folder whose pid is still running is skipped at any age. A folder
  with no pid file (an older Jonquil's, or a write that failed) waits
  pidless-age-ms instead, since its compile output sits in subfolders the
  age check doesn't look into."
  (:require [clojure.java.io :as io]))

(def prune-age-ms
  "estimate: a day, as middleware-files/prune-age-ms. A kept log is read
  right after its failure, never a day later."
  (* 24 60 60 1000))

(def pidless-age-ms
  "estimate: a week, long enough that a session left idle over a weekend
  keeps its folder."
  (* 7 24 60 60 1000))

(def ^:private folder-name
  ;; createTempDirectory's suffix: <millis>-<n> on Jolt (the run dirs),
  ;; a random unsigned long on the JVM (the launcher's own folder)
  #"jonquil-(cljs-run|cljs|jank-run|plugin-check)-\d+(-\d+)?")

(def ^:private log-name #"jonquil-nrepl-\d+\.log")

(defn write-pid!
  "Records pid as the process using run-dir, for prune! to check. Never
  throws: a folder with no pid file is only pruned by age."
  [run-dir pid]
  (try (spit (io/file run-dir "pid") (str pid)) (catch Throwable _ nil)))

(defn- pid-state
  "dir's pid file: :live when it names a running process, :dead when it
  names another, :none when there's no readable one."
  [^java.io.File dir]
  (let [f (io/file dir "pid")
        pid (when (.isFile f)
              (try (parse-long (.trim ^String (slurp f))) (catch Throwable _ nil)))]
    (cond (nil? pid) :none
          (.isPresent (java.lang.ProcessHandle/of pid)) :live
          :else :dead)))

(defn- newest-mtime
  "The latest modified time of dir and the files directly in it."
  [^java.io.File dir]
  (reduce max (.lastModified dir) (map #(.lastModified ^java.io.File %) (or (.listFiles dir) []))))

(defn- symlink? [^java.io.File f]
  (java.nio.file.Files/isSymbolicLink (.toPath f)))

(defn- delete-tree! [^java.io.File f]
  (when (and (.isDirectory f) (not (symlink? f)))
    (doseq [c (.listFiles f)] (delete-tree! c)))
  (.delete f))

(defn prune!
  "Deletes base's leftovers (see the ns doc) untouched for prune-age-ms
  (pidless-age-ms for a folder with no pid file), skipping a folder whose
  pid file names a running process and anything that is a symlink.
  Returns how many it deleted. Never throws."
  ([] (prune! (System/getProperty "java.io.tmpdir") (System/currentTimeMillis)))
  ([base now-ms]
   (try
     (let [older? (fn [t age] (> (- now-ms t) age))
           old? (fn [t] (older? t prune-age-ms))
           stale? (fn [^java.io.File f]
                    (let [n (.getName f)]
                      (and (not (symlink? f))
                           (cond
                             (re-matches folder-name n)
                             (and (.isDirectory f)
                                  (case (pid-state f)
                                    :live false
                                    :dead (old? (newest-mtime f))
                                    :none (older? (newest-mtime f) pidless-age-ms)))
                             (re-matches log-name n)
                             (and (.isFile f) (old? (.lastModified f)))))))]
       (reduce (fn [k ^java.io.File f]
                 (if (stale? f)
                   (do (delete-tree! f) (if (.exists f) k (inc k)))
                   k))
               0
               (or (.listFiles (io/file base)) [])))
     (catch Throwable _ 0))))
