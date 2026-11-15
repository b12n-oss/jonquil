(ns jonquil.slow-events
  "A log of the GTK callbacks that held the window up. Every callback made
  with jonquil.gtk.callbacks/callable is timed, and one that takes
  threshold-ms or longer is kept here with where its callable was made, so
  a slow Jonquil can say what was slow. The log is on from the start and
  holds the newest capacity events. No GTK here.

  While a callback runs the window can't draw or take a key, which is why
  its time is the thing to keep. A callback that itself waits for GTK (one
  that runs a nested main loop) counts as slow for the whole wait."
  (:require [clojure.string :as str]))

(def threshold-ms
  "How long a callback may run before it is kept: 50 ms, three frames at
  60 Hz, about where a pause after a key press starts to show."
  50)

(def capacity
  "How many events the log keeps; older ones are dropped and counted."
  200)

(def ^:private threshold-ns (* threshold-ms 1000000))

(def empty-log
  "A log with nothing in it: the kept events oldest first, and how many
  there have been in all."
  {:events [] :total 0})

(defonce ^{:doc "The live log, shaped like empty-log."}
  log
  (atom empty-log))

(defn add
  "log with event {:at ms :ms n :site s} added, the oldest dropped once
  there are more than capacity."
  [log event]
  (let [events (conj (:events log) event)
        over (- (count events) capacity)]
    {:events (if (pos? over) (subvec events over) events)
     :total (inc (:total log))}))

(defn check!
  "Called as a callback made at site returns: t0 is System/nanoTime from
  before it ran. Keeps the event when it took threshold-ms or longer.
  Answers nil and never throws."
  [site t0]
  (let [d (- (System/nanoTime) t0)]
    (when (>= d threshold-ns)
      (try (swap! log add {:at (System/currentTimeMillis) :ms (quot d 1000000) :site site})
           (catch :default _ nil))))
  nil)

(defn clear!
  "Empties the live log."
  []
  (reset! log empty-log)
  nil)

(defn by-site
  "The events summed by site, the site that cost the most first:
  [{:site s :count n :total-ms n :worst-ms n} ...]."
  [events]
  (->> (group-by :site events)
       (map (fn [[site es]]
              {:site site :count (count es)
               :total-ms (reduce + (map :ms es))
               :worst-ms (reduce max (map :ms es))}))
       (sort-by (juxt (comp - :total-ms) :site))
       vec))

(defn- clock
  "at, epoch ms, as HH:mm:ss.mmm in the local time zone. The milliseconds
  are added by hand: Jolt's SimpleDateFormat leaves an SSS pattern as it is."
  [at]
  (str (.format (java.text.SimpleDateFormat. "HH:mm:ss") (java.util.Date. (long at)))
       "." (format "%03d" (mod (long at) 1000))))

(defn report-lines
  "The log as lines of text for a person or a bug report: facts (lines
  about this Jonquil and machine) first, then the sites by cost, then the
  newest events, at most latest of them. Says so when nothing was slow."
  [{:keys [events total]} facts latest]
  (let [dropped (- total (count events))
        shown (take-last latest events)]
    (vec
     (concat
      facts
      (if (empty? events)
        [(str "No callback has taken " threshold-ms " ms or longer.")]
        (concat
         [(str total " slow " (if (= 1 total) "event" "events")
               " (a callback that took " threshold-ms " ms or longer)"
               (when (pos? dropped) (str ", the newest " (count events) " kept"))
               ".")
          ""
          "By where the callback was made, the costliest first:"]
         (for [{:keys [site count total-ms worst-ms]} (by-site events)]
           (str "  " total-ms " ms in " count (if (= 1 count) " event" " events")
                ", worst " worst-ms " ms: " site))
         [""
          (str "The newest " (clojure.core/count shown) ":")]
         (for [{:keys [at ms site]} shown]
           (str "  " (clock at) "  " ms " ms  " site))))))))

(defn file-text
  "report-lines as the text of a file."
  [log facts]
  (str (str/join "\n" (report-lines log facts capacity)) "\n"))
