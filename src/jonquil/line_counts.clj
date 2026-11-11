(ns jonquil.line-counts
  "LM4's exact line counts over Jolt's jolt.profile: code compiled while
  counting is on counts each line's executions, and the window draws a
  file's hits beside its lines. The in-process calls answer a map, never
  a throw, so a Jolt without jolt.profile just says so; the Jolt child
  runs the same calls through its middleware (jonquil-line-counts)."
  (:require [clojure.java.io :as io]
            [jonquil.instrument-lock :as instrument-lock]))

(def NO-API "Line counts need a Jolt with jolt.profile.")

(defn label
  "A hit count as the gutter shows it: the number below 1000, then 1.2k,
  12k, 1.2M. Truncated, so 1999 is 1.9k, never 2.0k."
  [n]
  (let [one (fn [x unit] (str (quot x 10) "." (mod x 10) unit))]
    (cond (< n 1000) (str n)
          (< n 10000) (one (quot n 100) "k")
          (< n 1000000) (str (quot n 1000) "k")
          :else (one (quot n 100000) "M"))))

(defn- canonical [p]
  (try (.getCanonicalPath (io/file p)) (catch Throwable _ p)))

(defn for-file
  "{line hits}, sorted by line, from jolt.profile/line-counts' entries for
  the file path names (a symlinked folder or /var for /private/var still
  matches). Entries with no file, code typed or run unsaved, never match."
  [counts path]
  (let [want (canonical path)]
    (into (sorted-map)
          (reduce (fn [m {:keys [file line hits]}]
                    (if (and file (= want (canonical file)))
                      (update m line (fnil + 0) hits)
                      m))
                  {} counts))))

(defn available? []
  (boolean (try (requiring-resolve 'jolt.profile/line-counts) (catch Throwable _ nil))))

(defn- err [e] {:error (str "Line counts: " (or (ex-message e) (str (class e))))})

(defn set-counting!
  "Turns counting on or off for code compiled from now on. {:on? b}, with
  :was-debug? true when turning it on ended debug mode (Jolt has one
  instrumentation mode at a time); {:error s} without jolt.profile."
  [on?]
  (if-not (available?)
    {:error NO-API}
    (try
      (locking instrument-lock/lock
        (let [was ((requiring-resolve 'jolt.profile/instrument-mode))]
          ((requiring-resolve 'jolt.profile/instrument!) (if on? :counts false))
          (cond-> {:on? (boolean on?)}
            (and on? (= :debug was)) (assoc :was-debug? true))))
      (catch Throwable e (err e)))))

(defn toggle!
  "Flips counting, read from the runtime's own mode: on unless it is
  :counts now. As set-counting!. The read and the set hold
  jonquil.instrument-lock's lock, so two toggles at once flip it twice."
  []
  (if-not (available?)
    {:error NO-API}
    (try (locking instrument-lock/lock
           (set-counting! (not= :counts ((requiring-resolve 'jolt.profile/instrument-mode)))))
         (catch Throwable e (err e)))))

(defn off-if-counting!
  "Turns this process's counting off when it is on, as set-counting!
  answers; nil when it wasn't counting. A window leaving its in-process Jolt
  calls it: the mode is the whole process's, so it would go on counting
  everything Jonquil compiles."
  []
  (when (available?)
    (locking instrument-lock/lock
      (when (= :counts (try ((requiring-resolve 'jolt.profile/instrument-mode)) (catch Throwable _ nil)))
        (set-counting! false)))))

(defn clear!
  "Zeroes every counter. {:ok true} or {:error s}."
  []
  (if-not (available?)
    {:error NO-API}
    (try ((requiring-resolve 'jolt.profile/clear!)) {:ok true}
         (catch Throwable e (err e)))))

(defn fetch
  "{:counts {line hits} :mode m} for the file at path, m the runtime's
  instrumentation mode now (:counts while counting; a restarted runtime
  answers nil), or {:error s}."
  [path]
  (if-not (available?)
    {:error NO-API}
    (try {:counts (for-file ((requiring-resolve 'jolt.profile/line-counts)) path)
          :mode ((requiring-resolve 'jolt.profile/instrument-mode))}
         (catch Throwable e (err e)))))
