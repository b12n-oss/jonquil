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
  (let [{:keys [view selected]} state]
    (if (leaf? view)
      (case key
        (:in :down :up :page-down :page-up) {:state state :request nil}
        :out {:state (assoc state :pending :cursor) :request [:out]}
        :meta {:state (assoc state :pending :cursor) :request [:meta {:cursor selected}]}
        :table {:state (assoc state :pending :keep) :request [:table]}
        {:state state :request nil})
      (let [{:keys [start total entries]} view
            n-entries (count entries)
            last-visible-idx (dec n-entries)
            at-last-page? (= (+ start n-entries) total)]
        (case key
          :down
          (if (< selected last-visible-idx)
            {:state (assoc state :selected (inc selected)) :request nil}
            (if at-last-page?
              {:state state :request nil}
              {:state (assoc state :pending :first) :request [:page {:delta 1}]}))

          :up
          (if (> selected 0)
            {:state (assoc state :selected (dec selected)) :request nil}
            (if (zero? start)
              {:state state :request nil}
              {:state (assoc state :pending :last) :request [:page {:delta -1}]}))

          :page-down
          {:state (assoc state :pending :first) :request [:page {:delta 1}]}

          :page-up
          {:state (assoc state :pending :first) :request [:page {:delta -1}]}

          :in
          (if (pos? n-entries)
            {:state state :request [:in {:idx (+ start selected)}]}
            {:state state :request nil})

          :out
          {:state (assoc state :pending :cursor) :request [:out]}

          :meta
          {:state (assoc state :pending :cursor) :request [:meta {:cursor selected}]}

          :table
          {:state (assoc state :pending :keep) :request [:table]}

          {:state state :request nil})))))

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
