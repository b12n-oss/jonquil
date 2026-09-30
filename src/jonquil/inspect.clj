(ns jonquil.inspect
  (:require [clojure.edn :as edn]))

(def unavailable-message
  "Inspect needs a runtime Jonquil can inspect: in-process Jolt, or a JVM server with clojure-cli.repl's inspector.")

(def timeout-message "The server didn't answer.")

(defn parse-view
  "Parse a server view string to a map. A blank or unreadable string gives
   {:path \"\" :info \"\" :header \"\" :note \"The server's view couldn't be read.\"}."
  [s]
  (let [unreadable {:path "" :info "" :header "" :note "The server's view couldn't be read."}
        v (try (edn/read-string (or s "")) (catch Exception _ nil))]
    (if (map? v) v unreadable)))

(defn open
  "Create initial pane state from a view."
  [view]
  {:view view :selected 0 :pending nil})

(defn- leaf?
  "True if the view is a leaf (no entries)."
  [view]
  (contains? view :leaf))

(defn key-step
  "Handle a key press. Returns {:state s :request r} where r is nil when there's no request."
  [state key]
  (let [{:keys [view selected]} state
        {:keys [start total entries]} view
        n-entries (count entries)
        none {:state state :request nil}]
    (case key
      ;; Available on every view, a leaf included (a leaf can carry metadata).
      :out {:state (assoc state :pending :cursor) :request [:out]}
      :meta {:state (assoc state :pending :cursor) :request [:meta {:cursor selected}]}
      :table {:state (assoc state :pending :keep) :request [:table]}
      ;; Row keys: nothing to move over on a leaf or an empty collection.
      (if (or (leaf? view) (zero? n-entries))
        none
        (case key
          :down
          (cond
            (< selected (dec n-entries)) {:state (assoc state :selected (inc selected)) :request nil}
            (< (+ start n-entries) total) {:state (assoc state :pending :first) :request [:page {:delta 1}]}
            :else none)

          :up
          (cond
            (pos? selected) {:state (assoc state :selected (dec selected)) :request nil}
            (pos? start) {:state (assoc state :pending :last) :request [:page {:delta -1}]}
            :else none)

          :page-down {:state (assoc state :pending :first) :request [:page {:delta 1}]}
          :page-up {:state (assoc state :pending :first) :request [:page {:delta -1}]}
          :in {:state state :request [:in {:idx (+ start selected)}]}
          none)))))

(defn on-reply
  "Apply a server reply to the state. reply is a view map, or {:error s :lost? t}."
  [state reply]
  (if (:error reply)
    (let [disabled? (:lost? reply)]
      (cond-> (assoc state :note (:error reply))
        disabled? (assoc :disabled? true)))
    (let [{:keys [selected pending]} state
          {:keys [entries cursor]} reply
          last-row (max 0 (dec (count entries)))
          new-selected (case pending
                         :first 0
                         :last last-row
                         :cursor (min (max 0 (or cursor 0)) last-row)
                         :keep (min selected last-row)
                         0)]
      ;; A new view drops the previous reply's note and def message.
      (cond-> (-> state
                  (dissoc :note :defd)
                  (assoc :view reply :selected new-selected :pending nil))
        (:note reply) (assoc :note (:note reply))
        (:defd reply) (assoc :defd (:defd reply))))))

(defn footer
  "Return position text, e.g. \"11-20 of 25\", or \"\" for a leaf."
  [state]
  (let [{:keys [view]} state
        {:keys [start total entries]} view]
    (if (contains? view :leaf)
      ""
      (str (inc start) "-" (+ start (count entries)) " of " total))))

(defn selected-index
  "Return the absolute index of the selected entry, or nil."
  [state]
  (let [{:keys [view selected]} state
        {:keys [start]} view]
    (when-not (contains? view :leaf)
      (+ start selected))))
