(ns jonquil.plugin-history
  "Plugin history: <plugins-dir>/.history/<id>/ keeps each version that went live as
  <n>.clj, and history.edn holds one vector of {:n :at :source :reason
  :status}. No entry is ever changed or removed: a new one goes at the end
  and the file is replaced whole through a temp file and an atomic move. A
  history.edn that can't be read is never overwritten. An agent's proposal
  is a version too, :source :agent, :pending or
  :rejected-by-checks; a later change of a version's status (:superseded,
  :accepted, :rejected) is a short {:n :at :status} entry appended after
  it, so a version's status is its last entry's. Pure apart from
  read-entries, version-source, write-atomic! and the record and
  all-pending fns, none of which throws."
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

(defn- copy-mode!
  "Gives the file at from the POSIX permissions of the file at to, when that
  file exists."
  [from to]
  (when (.exists (io/file to))
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath (io/file from))
     (java.nio.file.Files/getPosixFilePermissions
      (.toPath (io/file to)) (make-array java.nio.file.LinkOption 0)))))

(defn- move-into-place!
  "Atomically moves the file at from over the one at to."
  [from to]
  (java.nio.file.Files/move
   (.toPath (java.io.File. ^String from))
   (.toPath (java.io.File. ^String to))
   (into-array java.nio.file.CopyOption
               [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                java.nio.file.StandardCopyOption/ATOMIC_MOVE])))

(defn write-atomic!
  "Writes text to path through a temp file and an atomic move, making its
  folder: nil, or the sentence saying why it couldn't. A symlinked path
  is followed (save-path/resolve-target), so the link survives and its
  target is the file rewritten, keeping its POSIX mode, as the window's
  Save does (a revert must not replace a dotfiles link)."
  [path text]
  (let [tmp (atom nil)]
    (try
      (let [target (save-path/resolve-target path)]
        (reset! tmp (str target ".tmp-" (System/nanoTime)))
        (io/make-parents target)
        (save-path/create-temp! @tmp target)
        (spit @tmp text)
        (copy-mode! @tmp target)
        (move-into-place! @tmp target)
        nil)
      (catch Throwable e
        (when @tmp (try (io/delete-file @tmp true) (catch Throwable _ nil)))
        (str "couldn't write " path ": " (or (ex-message e) (str e)))))))

(defn read-entries
  "{:entries [...]} from dir's history.edn ([] when it's missing), or
  {:error sentence} when it can't be read or isn't a vector of maps, each
  with a whole-number :n (a bad :n would throw in next-n)."
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

(defn version-status
  "The :status of the last entry for version n, or nil."
  [entries n]
  (:status (last (filter #(= n (:n %)) entries))))

(defn pending
  "The proposal entry (the version's first entry) of the version whose
  status is :pending, or nil."
  [entries]
  (some (fn [e] (when (and (= :pending (:status e)) (= :pending (version-status entries (:n e))))
                  e))
        (reverse entries)))

(defn- append!
  "Writes text (when given) as dir's <n>.clj, then history.edn with more
  appended to entries, printed without namespaced maps: nil, or the
  sentence saying why it couldn't. step holds :n :text :entries :more."
  [dir {:keys [n text entries more]}]
  (or (when text (write-atomic! (str dir "/" n ".clj") text))
      (write-atomic! (str dir "/history.edn")
                     (binding [*print-namespace-maps* false] (pr-str (into entries more))))))

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
              (if-let [why (append! dir {:n n :text text :entries entries :more [(entry {:n n :at at :source source :reason reason})]})]
                {:error why}
                {:n n}))))))
    (catch Throwable e
      {:error (str "recording it threw: " (or (ex-message e) (str e)))})))

(defn- proposal-entry
  "history.edn's entry for a proposal recorded as version n at at: check
  kept on a :pending one, problems (and check-log, when it's a string) on
  a :rejected-by-checks one."
  [{:keys [n at reason status based-on check problems check-log]}]
  (let [e {:n n :at at :source :agent :reason reason :status status :based-on based-on}]
    (case status
      :pending (assoc e :check check)
      :rejected-by-checks (cond-> (assoc e :problems problems)
                            (string? check-log) (assoc :check-log check-log))
      e)))

(defn record-proposal!
  "Records an agent's proposal of text for id as its next version, with
  status :pending (keeping check, what its check reported) or
  :rejected-by-checks (keeping problems, and check-log, the path of the
  check's kept log, when it's a string), and based-on, the live version
  it was made against: {:n n}, {:n n :superseded m} when a pending one
  replaced pending version m (in the same history.edn write), or {:error
  sentence}. Never throws."
  [{:keys [plugins-dir id text status now] :as opts}]
  (try
    (let [dir (history-dir plugins-dir id)
          {:keys [entries error]} (read-entries dir)]
      (cond
        error {:error error}
        ;; an entry never names a file that wasn't written
        (not (string? text)) {:error "a proposal needs its text"}
        :else
        (let [n (next-n entries (file-ns dir))
              at ((or now #(str (java.time.Instant/now))))
              old (when (= :pending status) (:n (pending entries)))
              e (proposal-entry (assoc opts :n n :at at))]
          (if-let [why (append! dir {:n n :text text :entries entries
                                     :more (cond-> [e] old (conj {:n old :at at :status :superseded}))})]
            {:error why}
            (cond-> {:n n} old (assoc :superseded old))))))
    (catch Throwable e
      {:error (str "recording it threw: " (or (ex-message e) (str e)))})))

(defn record-status!
  "Appends {:n :at :status} (and :note, when given) to id's history: nil,
  or the sentence saying why it wasn't recorded. Never throws."
  [{:keys [plugins-dir id n status note now]}]
  (try
    (let [dir (history-dir plugins-dir id)
          {:keys [entries error]} (read-entries dir)]
      (or error
          (append! dir {:n n :entries entries
                        :more [(cond-> {:n n :at ((or now #(str (java.time.Instant/now)))) :status status}
                                 note (assoc :note note))]})))
    (catch Throwable e
      (str "recording it threw: " (or (ex-message e) (str e))))))

(defn all-pending
  "Every plugin's pending proposal under plugins-dir, as its proposal entry
  with :id, sorted by id. A history that can't be read is skipped. Never
  throws."
  [plugins-dir]
  (try
    (vec (for [f (sort-by #(.getName ^java.io.File %) (or (.listFiles (io/file plugins-dir ".history")) []))
               :when (.isDirectory ^java.io.File f)
               :let [p (pending (:entries (read-entries f)))]
               :when p]
           (assoc p :id (.getName ^java.io.File f))))
    (catch Throwable _ [])))
