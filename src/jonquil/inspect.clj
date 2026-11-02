(ns jonquil.inspect
  (:require [clojure.edn :as edn]))

(def unavailable-message
  "Inspect needs a runtime Jonquil can inspect: in-process Jolt, or a JVM server with clojure-cli.repl's inspector.")

(def timeout-message "The server didn't answer.")

(def no-history-message
  "A JVM server's inspector (clojure-cli.repl's) keeps only its current
  object, so Earlier and Later can't go back to another one."
  "This server keeps only the object it shows now.")

(defn request
  "The wire op for a jonquil.evaluator/Inspector call: op/args to the
  server's inspect-* op and its keys, before :session is merged in. The
  JVM's clojure-cli.repl inspector and Jonquil's own Jolt middleware
  (jonquil.nrepl.inspect-middleware) answer the same ops."
  [op args]
  (case op
    :start {:op "inspect-start" :rows (:rows args) :cols (:cols args)}
    ;; a local of the last error's frame: only Jonquil's Jolt middleware
    :start-local {:op "inspect-local" :frame (:frame args) :local (:local args)
                  :rows (:rows args) :cols (:cols args) :error-id (:error-id args)}
    ;; a traced call's value (jonquil.trace-rt): only Jonquil's Jolt middleware.
    ;; trace-id, never id, which is the nREPL message's own
    :start-trace {:op "inspect-trace" :trace-id (:id args) :part (name (:part args))
                  :rows (:rows args) :cols (:cols args)}
    :in {:op "inspect-in" :idx (:idx args)}
    :out {:op "inspect-out"}
    :page {:op "inspect-page" :delta (:delta args)}
    :meta {:op "inspect-toggle-meta" :cursor (:cursor args)}
    :table {:op "inspect-toggle-table"}
    :def {:op "inspect-def" :name (:name args)}
    ;; Jonquil's own history ops: only its Jolt middleware answers them
    :earlier {:op "inspect-earlier"}
    :later {:op "inspect-later"}))

(defn parse-view
  "Parse a server view string to a map. A blank or unreadable string gives
   {:path \"\" :info \"\" :header \"\" :note \"The server's view couldn't be read.\"}."
  [s]
  (let [unreadable {:path "" :info "" :header "" :note "The server's view couldn't be read."}
        v (try (edn/read-string (or s "")) (catch Exception _ nil))]
    (if (map? v) v unreadable)))

(defn open
  "Create initial pane state from a view. A first reply shaped {:error s}
   (the server never attached, or the first :start timed out or
   dropped) would otherwise make the error map itself the view, and
   footer's own :start lookup would have nothing to read. Opens onto an
   empty view instead, with the message in :note so it still reaches the
   footer, and :disabled? when the reply named :lost? true."
  [view]
  (if (:error view)
    (cond-> {:view {} :selected 0 :pending nil :note (:error view)}
      (:lost? view) (assoc :disabled? true))
    {:view view :selected 0 :pending nil}))

(defn- leaf?
  "True if the view is a leaf (no entries)."
  [view]
  (contains? view :leaf))

(defn key-step
  "Handle a key press. Returns {:state s :request r} where r is nil when there's no request.
   Every key is a no-op while :disabled? is set (a lost connection):
   otherwise Return/m/v/Left/paging would keep sending requests the
   connection can't answer (spec: \"disables the actions\")."
  [state key]
  (let [{:keys [view selected disabled?]} state
        {:keys [start total entries]} view
        n-entries (count entries)
        none {:state state :request nil}]
    (if disabled?
      none
      (case key
        ;; Available on every view, a leaf included (a leaf can carry metadata).
        :out {:state (assoc state :pending :cursor) :request [:out]}
        :meta {:state (assoc state :pending :cursor) :request [:meta {:cursor selected}]}
        :table {:state (assoc state :pending :keep) :request [:table]}
        ;; the history of inspected objects: another root, its first row
        ;; at either end the view's own :history answers, without a request,
        ;; so the selection stays where it was
        :earlier (if (= 1 (:at (:history view)))
                   {:state (assoc state :note "No earlier object.") :request nil}
                   {:state (assoc state :pending :first) :request [:earlier]})
        :later (let [{at :at n :count} (:history view)]
                 (if (and at (= at n))
                   {:state (assoc state :note "No later object.") :request nil}
                   {:state (assoc state :pending :first) :request [:later]}))
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
            none))))))

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

(defn- position-text
  "\"11-20 of 25\", or \"0 of 0\" for a page with no entries (an empty
   collection, start 0 total 0) rather than the empty math's own
   \"1-0 of 0\"."
  [start total n-entries]
  (if (zero? n-entries)
    (str "0 of " total)
    (str (inc start) "-" (+ start n-entries) " of " total)))

(defn footer
  "Position text plus the key hints (Design: \"a footer shows start+1-end
   of total and the key hints\"), e.g. \"11-20 of 25 · ↑↓ move · ⏎ in · ← out · d def · m meta · v table\".
   A leaf has nothing to move over or go into, so it shows only the
   hints after ← (out, def, meta, table). A view with neither :start
   nor :leaf -- a fresh {:error s} reply's own empty view, or
   an unreadable/fallback map (parse-view, run-op's own throw catch) --
   has nothing to report yet: \"\"."
  [state]
  (let [{:keys [view]} state
        {:keys [start total entries history]} view
        ;; more than one object inspected: where you are among them
        objects (when (and history (> (:count history) 1))
                  (str " · object " (:at history) " of " (:count history) " · [ ] earlier/later"))]
    (cond
      (contains? view :leaf) (str "← out · d def · m meta · v table" objects)
      (contains? view :start) (str (position-text start total (count entries))
                                   " · ↑↓ move · ⏎ in · ← out · d def · m meta · v table" objects)
      :else "")))
