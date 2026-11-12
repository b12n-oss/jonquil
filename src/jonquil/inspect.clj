(ns jonquil.inspect
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def unavailable-message
  "Inspect needs a runtime Jonquil can inspect: in-process Jolt, or a JVM server with clojure-cli.repl's inspector.")

(def timeout-message "The server didn't answer.")

(def no-history-message
  "A JVM server's inspector (clojure-cli.repl's) keeps only its current
  object, so Earlier and Later can't go back to another one."
  "This server keeps only the object it shows now.")

(defn- wire
  "The wire map for op-name: {:op op-name} plus each wire key in renames
  mapped to args' value for the arg key it names (nil when args lacks it)."
  [op-name args renames]
  (reduce-kv (fn [m wire-key arg-key] (assoc m wire-key (get args arg-key)))
             {:op op-name}
             renames))

(def ^:private pane-size {:rows :rows :cols :cols})

(def ^:private requests
  "Each request op's wire builder, a fn of the call's args."
  {:start #(wire "inspect-start" % pane-size)
   ;; a local of the last error's frame: only Jonquil's Jolt middleware
   :start-local #(wire "inspect-local" %
                       (assoc pane-size :frame :frame :local :local :error-id :error-id :label :label))
   ;; a traced call's value (jonquil.trace-rt): only Jonquil's Jolt middleware.
   ;; trace-id, never id, which is the nREPL message's own
   :start-trace #(assoc (wire "inspect-trace" % (assoc pane-size :trace-id :id :label :label))
                        :part (name (:part %)))
   ;; a console result's value (jonquil.results-rt): only Jonquil's Jolt middleware
   :start-result #(wire "inspect-result" %
                        (assoc pane-size :result-id :id :result-epoch :epoch :label :label))
   :in #(wire "inspect-in" % {:idx :idx})
   :out #(wire "inspect-out" % {})
   :page #(wire "inspect-page" % {:delta :delta})
   :meta #(wire "inspect-toggle-meta" % {:cursor :cursor})
   :table #(wire "inspect-toggle-table" % {})
   :def #(wire "inspect-def" % {:name :name})
   ;; Jonquil's own history ops: only its Jolt middleware answers them
   :earlier #(wire "inspect-earlier" % {})
   :later #(wire "inspect-later" % {})})

(defn request
  "The wire op for a jonquil.evaluator/Inspector call: op/args to the
  server's inspect-* op and its keys, before :session is merged in. The
  JVM's clojure-cli.repl inspector and Jonquil's own Jolt middleware
  (jonquil.nrepl.inspect-middleware) answer the same ops."
  [op args]
  (if-let [build (get requests op)]
    (build args)
    (throw (IllegalArgumentException. (str "No matching clause: " op)))))

(def ^:private LABEL-MAX 40)

(defn root-label
  "The inspector's name for a value opened from text (a console line, a
  traced call): its first line, cut at LABEL-MAX characters; *1 when
  there's nothing to show."
  [s]
  (let [line (first (str/split-lines (str/trim (str s))))]
    (cond (str/blank? line) "*1"
          (> (count line) LABEL-MAX) (str (subs line 0 LABEL-MAX) "…")
          :else line)))

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

(defn- history-step
  "The :earlier / :later keys: at either end the view's own :history
  answers, without a request, so the selection stays where it was."
  [state key]
  (let [{at :at n :count} (:history (:view state))]
    (case key
      :earlier (if (= 1 at)
                 {:state (assoc state :note "No earlier object.") :request nil}
                 {:state (assoc state :pending :first) :request [:earlier]})
      :later (if (and at (= at n))
               {:state (assoc state :note "No later object.") :request nil}
               {:state (assoc state :pending :first) :request [:later]}))))

(defn- step-down
  "The :down key on a view with entries: the next row, else the next page,
  else nothing."
  [{:keys [view selected] :as state}]
  (let [{:keys [start total entries]} view
        n-entries (count entries)]
    (cond
      (< selected (dec n-entries)) {:state (assoc state :selected (inc selected)) :request nil}
      (< (+ start n-entries) total) {:state (assoc state :pending :first) :request [:page {:delta 1}]}
      :else {:state state :request nil})))

(defn- step-up
  "The :up key on a view with entries: the previous row, else the previous
  page (landing on its last row), else nothing."
  [{:keys [view selected] :as state}]
  (cond
    (pos? selected) {:state (assoc state :selected (dec selected)) :request nil}
    (pos? (:start view)) {:state (assoc state :pending :last) :request [:page {:delta -1}]}
    :else {:state state :request nil}))

(defn- row-key-step
  "Row keys on a view with entries: move the selection, page, or go in."
  [state key]
  (let [{:keys [view selected]} state]
    (case key
      :down (step-down state)
      :up (step-up state)
      :page-down {:state (assoc state :pending :first) :request [:page {:delta 1}]}
      :page-up {:state (assoc state :pending :first) :request [:page {:delta -1}]}
      :in {:state state :request [:in {:idx (+ (:start view) selected)}]}
      {:state state :request nil})))

(defn key-step
  "Handle a key press. Returns {:state s :request r} where r is nil when there's no request.
   Every key is a no-op while :disabled? is set (a lost connection):
   otherwise Return/m/v/Left/paging would keep sending requests the
   connection can't answer (spec: \"disables the actions\")."
  [state key]
  (let [{:keys [view selected disabled?]} state
        none {:state state :request nil}]
    (if disabled?
      none
      (case key
        ;; Available on every view, a leaf included (a leaf can carry metadata).
        :out {:state (assoc state :pending :cursor) :request [:out]}
        :meta {:state (assoc state :pending :cursor) :request [:meta {:cursor selected}]}
        :table {:state (assoc state :pending :keep) :request [:table]}
        ;; the history of inspected objects: another root, its first row
        (:earlier :later) (history-step state key)
        ;; Row keys: nothing to move over on a leaf or an empty collection.
        (if (or (leaf? view) (zero? (count (:entries view))))
          none
          (row-key-step state key))))))

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
