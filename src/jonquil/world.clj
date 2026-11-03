(ns jonquil.world
  "Save World and Restore World's decisions (LM7): where a project's world
  history lives, which saves to keep, how each reads in the palette, and
  what the status bar and console say about a save or a restore
  (jonquil.world-rt/op's answers). The file helpers (entries, record!,
  prune!) are the only IO. No GTK here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def KEEP
  ;; a choice: saves kept per project, oldest dropped first, and as many
  ;; again of the ones made before a restore
  10)

(defn world-dir
  "The project's world folder: worlds/<folder>-<hash> beside the settings
  file, as sessions.edn sits, so it never lands in the project's own
  tree. root is the project folder, or nil for no-folder. The hash is the
  folder path's String.hashCode, the same in every run."
  [settings-path root]
  (let [dir (str (.getParent (java.io.File. ^String settings-path)) "/worlds")]
    (if root
      (let [leaf (str/replace (.getName (java.io.File. ^String root)) #"[^A-Za-z0-9._-]" "-")]
        (str dir "/" leaf "-" (Integer/toHexString (.hashCode ^String root))))
      (str dir "/no-folder"))))

(defn entry-path
  "The world file for a save made at ms, in dir. The time is padded to 13
  digits, so names sort in time order."
  [dir ms]
  (str dir "/" (format "%013d" ms) ".world"))

(defn- record-path [world-path]
  (str/replace world-path #"\.world$" ".edn"))

(defn record!
  "Writes rec ({:at :kind :saved :placeholders}) beside the world file."
  [world-path rec]
  (spit (record-path world-path) (pr-str rec)))

(defn entries
  "dir's saves, newest first: {:path :at} plus the record written beside
  each one (record!). A missing folder has none."
  [dir]
  (let [files (or (.listFiles (io/file dir)) [])]
    (->> files
         (keep (fn [^java.io.File f]
                 (when-let [[_ stamp] (re-matches #"(\d+)\.world" (.getName f))]
                   (let [path (.getCanonicalPath f)
                         rec (try (let [r (io/file (record-path path))]
                                    (when (.isFile r) (edn/read-string (slurp r))))
                                  (catch Throwable _ nil))]
                     (merge (when (map? rec) rec) {:path path :at (parse-long stamp)})))))
         (sort-by :at >)
         vec)))

(defn- before-restore? [e] (= :before-restore (:kind e)))

(defn to-prune
  "The entries to delete, entries newest first: past the newest keep of
  the saves you made, and separately past the newest keep of those made
  before a restore, so restores never push out a save you made."
  [entries keep]
  (let [{autos true saves false} (group-by before-restore? entries)]
    (vec (concat (drop keep saves) (drop keep autos)))))

(defn newest-save
  "The entry Restore World brings back: the newest save you made, skipping
  the ones made before a restore (Restore an Earlier World lists those).
  nil when there's none."
  [entries]
  (first (remove before-restore? entries)))

(defn prune!
  "Deletes dir's saves past the newest keep, each world file with its
  record."
  [dir keep]
  (doseq [{:keys [path]} (to-prune (entries dir) keep)]
    (io/delete-file path true)
    (io/delete-file (record-path path) true)))

(defn ago-text
  "How long before now (ms) at was, in words: just now, N minutes, hours
  or days ago. No time zone needed, so it reads right on any machine."
  [now at]
  (let [s (quot (max 0 (- now at)) 1000)
        [n unit] (cond (< s 60) nil
                       (< s 3600) [(quot s 60) "minute"]
                       (< s 86400) [(quot s 3600) "hour"]
                       :else [(quot s 86400) "day"])]
    (if n (str n " " unit (when (not= 1 n) "s") " ago") "just now")))

(defn- vars-word [n] (if (= 1 n) "var" "vars"))

(defn entry-label
  "An entry's palette row: when it was saved, whether it was the save made
  before a restore, and what it holds when its record says."
  [now {:keys [at kind saved placeholders]}]
  (str "Restore World saved "
       (when (= :before-restore kind) "before a restore, ")
       (ago-text now at)
       (when saved
         (str " (" saved " " (vars-word saved)
              (when (pos? (or placeholders 0))
                (str ", " placeholders " placeholder" (when (> placeholders 1) "s")))
              ")"))))

(defn save-report
  "{:status :lines} for a :save answer: lines go to the console, one per
  var named."
  [{:keys [error saved placeholders unwritable]}]
  (cond
    (seq unwritable)
    {:status "; Save World: nothing was saved (see the console)."
     :lines (into [(str "; " error)] (map (fn [{:keys [var kind]}] (str ";   " var " (" kind ")"))) unwritable)}

    error {:status (str "; Save World: " error) :lines []}

    (seq placeholders)
    {:status (str "; Saved " saved " " (vars-word saved) " to this project's world history, "
                  (count placeholders) " as a placeholder" (when (> (count placeholders) 1) "s")
                  " (see the console).")
     :lines (into ["; Save World: saved as placeholders, since they can't be written as they are:"]
                  (map (fn [{:keys [var kind]}] (str ";   " var " (" kind ")")))
                  placeholders)}

    :else {:status (str "; Saved " saved " " (vars-word saved) " to this project's world history.") :lines []}))

(defn restore-report
  "{:status :lines} for a :restore answer. With :saved-first? the status
  says the data before the restore was saved, and how to get it back."
  ([r] (restore-report r {}))
  ([{:keys [error missing restored placeholders]} {:keys [saved-first?]}]
   (let [first-note (when saved-first? " What you had first is saved: Restore an Earlier World brings it back.")]
     (cond
       missing {:status "; No saved world for this project yet: Save World makes one." :lines []}
       error {:status (str "; Restore World: " error) :lines []}

       (seq placeholders)
       {:status (str "; Restored " restored " " (vars-word restored) " from this project's world, "
                     (count placeholders) " a placeholder" (when (> (count placeholders) 1) "s")
                     " (see the console)." first-note)
        :lines (into ["; Restore World: placeholders for what the world couldn't carry:"]
                     (map (fn [{:keys [var description]}] (str ";   " var ": " description)))
                     placeholders)}

       :else {:status (str "; Restored " restored " " (vars-word restored) " from this project's world." first-note)
              :lines []}))))
