(ns jonquil.plugin-history
  "Plugin history (plugins design, Part 2, \"History and revert\"; slice
  P7): <plugins-dir>/.history/<id>/ keeps each version that went live as
  <n>.clj, and history.edn holds one vector of {:n :at :source :reason
  :status}. No entry is ever changed or removed: a new one goes at the end
  and the file is replaced whole through a temp file and an atomic move. A
  history.edn that can't be read is never overwritten. Pure apart from
  read-entries, version-source, record! and write-atomic!, none of which
  throws."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [jonquil.save-path :as save-path]))

(defn history-dir
  "The folder holding id's versions, in the plugins folder plugins-dir."
  [plugins-dir id]
  (str plugins-dir "/.history/" id))

(defn next-n
  "The next version number: one past every entry's :n and every number in
  file-ns (the <n> of each <n>.clj already in the folder), so neither a
  file left by a crash nor a deleted history.edn gets a number reused."
  [entries file-ns]
  (inc (reduce max 0 (concat (keep :n entries) file-ns))))

(defn latest-accepted
  "The last :accepted entry, or nil."
  [entries]
  (last (filter #(= :accepted (:status %)) entries)))

(defn entry
  "A history entry for a version that went live."
  [{:keys [n at source reason]}]
  {:n n :at at :source source :reason reason :status :accepted})

(defn write-atomic!
  "Writes text to path through a temp file and an atomic move, making its
  folder: nil, or the sentence saying why it couldn't. A symlinked path
  is followed (save-path/resolve-target), so the link survives and its
  target is the file rewritten, keeping its POSIX mode, as the window's
  Save does (review I2: a revert used to replace a dotfiles link)."
  [path text]
  (let [tmp (atom nil)]
    (try
      (let [target (save-path/resolve-target path)]
        (reset! tmp (str target ".tmp-" (System/nanoTime)))
        (io/make-parents target)
        (save-path/create-temp! @tmp target)
        (spit @tmp text)
        (when (.exists (io/file target))
          (java.nio.file.Files/setPosixFilePermissions
           (.toPath (io/file @tmp))
           (java.nio.file.Files/getPosixFilePermissions
            (.toPath (io/file target)) (make-array java.nio.file.LinkOption 0))))
        (java.nio.file.Files/move
         (.toPath (java.io.File. ^String @tmp))
         (.toPath (java.io.File. ^String target))
         (into-array java.nio.file.CopyOption
                     [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                      java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
        nil)
      (catch Throwable e
        (when @tmp (try (io/delete-file @tmp true) (catch Throwable _ nil)))
        (str "couldn't write " path ": " (or (ex-message e) (str e)))))))

(defn read-entries
  "{:entries [...]} from dir's history.edn ([] when it's missing), or
  {:error sentence} when it can't be read or isn't a vector of maps, each
  with a whole-number :n (review I1: a bad :n threw in next-n)."
  [dir]
  (let [f (io/file dir "history.edn")]
    (if-not (.exists f)
      {:entries []}
      (try
        (let [v (edn/read-string (slurp f))]
          (if (and (vector? v) (every? #(and (map? %) (nat-int? (:n %))) v))
            {:entries v}
            {:error (str (.getPath f) " isn't a vector of entries, so it was left as it is")}))
        (catch Throwable e
          {:error (str (.getPath f) " can't be read, so it was left as it is: "
                       (or (ex-message e) (str e)))})))))

(defn version-source
  "The text of dir's <n>.clj, or nil when it's missing or unreadable."
  [dir n]
  (let [f (io/file dir (str n ".clj"))]
    (when (.isFile f)
      (try (slurp f) (catch Throwable _ nil)))))

(defn- file-ns
  "The <n> of every <n>.clj in dir."
  [dir]
  (keep #(some-> (re-matches #"(\d+)\.clj" (.getName ^java.io.File %)) second parse-long)
        (some-> (.listFiles (io/file dir)) seq)))

(defn record!
  "Records text as id's next version, from source with reason: {:n n}, or
  {:same n} when it equals the latest accepted version n's text (unless
  always?, a revert, which is a version even then), or {:error sentence}.
  <n>.clj is written before its entry, so an entry never names a missing
  file. now gives :at (an ISO instant by default). Never throws: anything
  unexpected is an :error too."
  [{:keys [plugins-dir id text source reason now always?]}]
  (try
    (let [dir (history-dir plugins-dir id)
          {:keys [entries error]} (read-entries dir)]
      (if error
        {:error error}
        (let [latest (latest-accepted entries)]
          (if (and latest (not always?) (= text (version-source dir (:n latest))))
            {:same (:n latest)}
            (let [n (next-n entries (file-ns dir))
                  at ((or now #(str (java.time.Instant/now))))]
              (if-let [why (or (write-atomic! (str dir "/" n ".clj") text)
                               (write-atomic! (str dir "/history.edn")
                                              (pr-str (conj entries (entry {:n n :at at :source source :reason reason})))))]
                {:error why}
                {:n n}))))))
    (catch Throwable e
      {:error (str "recording it threw: " (or (ex-message e) (str e)))})))
