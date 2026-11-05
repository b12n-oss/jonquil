(ns jonquil.messages
  "The Messages panel's log (LM8): what happened in the window's background,
  as a value. Agent tool calls, connections, runtime starts and (hidden by
  default) status lines, newest last, capped, with dropped entries counted.
  The window holds one atom of it and adds through log!, from any thread.
  No GTK here."
  (:require [clojure.string :as str]))

(def CAP
  ;; estimate: a long agent session is a few hundred calls, so a lunch
  ;; break drops nothing, and the list stays quick to rebuild
  1000)

(def SHOWN-KINDS
  "The kinds the panel shows until the person changes its filter. :file is
  an open tab's file changed on disk, an agent's own edit tool most often."
  #{:agent :plugin :file :connection :runtime})

(def COUNTED-KINDS
  "The kinds that raise the unread count: the background ones. Reads and
  status lines never do, so the count means something."
  SHOWN-KINDS)

(def ^:private DETAIL-LINES 20)
(def ^:private DETAIL-CHARS 2000)

(defn empty-log []
  {:entries [] :next-id 1 :dropped 0 :read-id 0})

(defn- same-row? [a b]
  (and a (= (:kind a) (:kind b)) (= (:summary a) (:summary b))
       (= (boolean (:error? a)) (boolean (:error? b)))))

(defn- drop-one
  "es without its oldest hidden-kind entry (a status line or a read), or
  without its oldest entry when it has none: past CAP, the rows nobody
  ticked go before an agent's."
  [es]
  (if-let [i (first (keep-indexed (fn [i e] (when-not (contains? SHOWN-KINDS (:kind e)) i)) es))]
    (into (subvec es 0 i) (subvec es (inc i)))
    (subvec es 1)))

(defn add
  "log with entry ({:kind :summary & [:error? :detail :target :client
  :summary-tab]}) added at now-ms. An entry equal in kind, summary and
  failure to the newest one bumps that one's :repeat and :at instead,
  keeping its :id. Past CAP the oldest go, hidden kinds first (drop-one),
  counted in :dropped."
  [log entry now-ms]
  (let [es (:entries log)
        newest (peek es)]
    (if (same-row? newest entry)
      (assoc log :entries (conj (pop es) (assoc newest :at now-ms :repeat (inc (or (:repeat newest) 1)))))
      (let [es (conj es (assoc entry :id (:next-id log) :at now-ms))
            over? (> (count es) CAP)]
        (-> log
            (assoc :entries (if over? (vec (drop-one es)) es))
            (update :next-id inc)
            (update :dropped + (if over? 1 0)))))))

(defn mark-read [log]
  (assoc log :read-id (or (:id (peek (:entries log))) (:read-id log))))

(defn unread-count [log]
  (let [r (:read-id log)]
    (count (filter #(and (> (:id %) r) (contains? COUNTED-KINDS (:kind %))) (:entries log)))))

(defn visible
  "The entries whose kind is in kinds, newest first."
  [log kinds]
  (into [] (filter #(contains? kinds (:kind %))) (rseq (:entries log))))

(defn cap-text
  "s cut for an entry's detail: the first 20 lines, then \"… N more
  lines\"; then, if still over 2,000 characters, those, then \"… cut at
  2,000 characters\". nil stays nil."
  [s]
  (when s
    (let [lines (str/split-lines s)
          more (- (count lines) DETAIL-LINES)
          s (if (pos? more)
              (str (str/join "\n" (take DETAIL-LINES lines)) "\n… " more " more lines")
              s)]
      (if (> (count s) DETAIL-CHARS)
        (str (subs s 0 DETAIL-CHARS) "\n… cut at 2,000 characters")
        s))))

(defn log!
  "Adds entry to the log in log*, from any thread. Never throws: a failure
  is one stderr line, since a log that breaks its caller would break the
  agent's call it describes."
  [log* entry]
  (try (swap! log* add entry (System/currentTimeMillis))
       (catch :default e
         (binding [*out* *err*]
           (println "jonquil: messages:" (or (ex-message e) (str e))))))
  nil)
