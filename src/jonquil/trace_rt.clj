(ns jonquil.trace-rt
  "Tracing where the code runs (LM4): trace! wraps a var's fn with
  alter-var-root, so callers compiled before the wrap go through it too
  (measured on Jolt v0.8.23 and main), and each call adds an entry to a
  log of the last CAP calls with its depth, the printed text of its args
  and result (jonquil.debugger/value-text, which never realizes a lazy
  seq) and the live values for the inspector. Runs in process and in the
  Jolt child (jonquil.nrepl.middleware-files embeds it), so it requires
  only jonquil.debugger. No GTK here."
  (:require [clojure.string :as str]
            [jonquil.debugger :as debugger]))

(def ^:private CAP
  ;; a choice: calls kept, oldest dropped first
  500)

(defonce ^:private state
  ;; :log is id -> entry, oldest first; :traced is \"ns/f\" -> {:var :orig :wrapper}
  (atom {:next 0 :log (sorted-map) :traced {}}))

(def ^:dynamic ^:private *depth* 0)

(defn- var-name [v]
  (let [m (meta v)] (str (ns-name (:ns m)) "/" (:name m))))

(defn- call-text [fname args]
  (str "(" fname (when (seq args) " ") (str/join " " (map debugger/value-text args)) ")"))

(defn- begin! [fname args depth]
  (let [s (swap! state (fn [{:keys [next log] :as s}]
                         (let [log (assoc log next {:id next :depth depth :fn fname
                                                    :args (call-text fname args) :args-v (vec args)
                                                    :pending true})
                               log (if (> (count log) CAP) (dissoc log (first (keys log))) log)]
                           (assoc s :next (inc next) :log log))))]
    (dec (:next s))))

(defn- finish! [id k v]
  (swap! state update :log
         (fn [log]
           (if-let [e (get log id)]
             (assoc log id (cond-> (dissoc e :pending)
                             (= k :ret) (assoc :ret (debugger/value-text v) :ret-v v)
                             (= k :threw) (assoc :threw (str (.getSimpleName (class v)) ": " (ex-message v)))))
             log))))

(defn- wrap [fname orig]
  (fn [& args]
    (let [id (begin! fname args *depth*)]
      (try
        (let [r (binding [*depth* (inc *depth*)] (apply orig args))]
          (finish! id :ret r)
          r)
        (catch Throwable t
          (finish! id :threw t)
          (throw t))))))

(defn- resolve-in [sym ns-name]
  (let [n (or (find-ns (symbol (str ns-name))) (the-ns 'user))]
    (try (ns-resolve n (symbol (str sym))) (catch Throwable _ nil))))

(defn- traced-entry
  "The trace record for v when v still holds its wrapper; a var redefined
  since it was traced isn't traced any more."
  [v]
  (let [e (get (:traced @state) (var-name v))]
    (when (and e (identical? @v (:wrapper e))) e)))

(defn trace!
  "Wraps the fn sym names in namespace ns-name: {:traced name}, {:already
  name}, or {:error s} for a name that isn't a var holding a fn, or a macro."
  [sym ns-name]
  (let [v (resolve-in sym ns-name)]
    (cond
      (not (var? v)) {:error (str "No function named " sym ".")}
      (:macro (meta v)) {:error (str (var-name v) " is a macro.")}
      (not (fn? @v)) {:error (str (var-name v) " isn't a function.")}
      (traced-entry v) {:already (var-name v)}
      :else
      (let [fname (var-name v)
            orig @v
            w (wrap fname orig)]
        (alter-var-root v (constantly w))
        (swap! state assoc-in [:traced fname] {:var v :orig orig :wrapper w})
        {:traced fname}))))

(defn- drop-trace!
  "Restores e's original when its var still holds the wrapper (never over a
  newer definition), and forgets it. true when it was restored."
  [fname {:keys [var orig wrapper]}]
  (let [restored? (identical? @var wrapper)]
    (when restored? (alter-var-root var (constantly orig)))
    (swap! state update :traced dissoc fname)
    restored?))

(defn untrace!
  "Stops tracing the fn sym names: {:untraced name} or {:error s}."
  [sym ns-name]
  (let [v (resolve-in sym ns-name)
        fname (when (var? v) (var-name v))
        e (when fname (get (:traced @state) fname))]
    (if e
      (do (drop-trace! fname e) {:untraced fname})
      {:error (str (or fname sym) " isn't traced.")})))

(defn untrace-all!
  "Stops tracing everything: {:count n}, the functions still traced."
  []
  {:count (count (filter true? (for [[fname e] (:traced @state)] (drop-trace! fname e))))})

(defn traced
  "The traced functions' names, sorted, leaving out any redefined since."
  []
  (vec (sort (for [[fname {:keys [var wrapper]}] (:traced @state)
                   :when (identical? @var wrapper)]
               fname))))

(defn clear!
  "Forgets every recorded call."
  []
  (swap! state assoc :log (sorted-map))
  nil)

(defn log-since
  "The entries after id, texts only: {:entries [...] :last id}, :last the
  newest id (id itself when there's nothing newer)."
  [id]
  (let [es (for [[k e] (:log @state) :when (> k id)] (dissoc e :args-v :ret-v))]
    {:entries (vec es) :last (if (seq es) (:id (last es)) id)}))

(defn op
  "An evaluator's trace op (jonquil.evaluator/Trace): op :trace, :untrace,
  :untrace-all, :clear or :log, args as that protocol says."
  [op {:keys [sym ns since]}]
  (case op
    :trace (trace! sym ns)
    :untrace (untrace! sym ns)
    :untrace-all (untrace-all!)
    :clear (do (clear!) {:cleared true})
    :log (log-since (or since -1))))

(defn value
  "Entry id's live :ret or :args, or ::gone once it left the log."
  [id part]
  (if-let [e (get (:log @state) id)]
    (case part :ret (:ret-v e) :args (:args-v e))
    ::gone))
