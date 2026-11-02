(ns jonquil.results-rt
  "The values the console's result lines point at (LM1), kept where the
  code runs: keep! stores a value in a ring of the last RING and answers
  its id and kind, and value answers it back. In process it's on Jonquil's
  classpath; in the Jolt child jonquil.nrepl.middleware-files embeds it,
  so it requires nothing. No GTK here.")

(def ^:private RING
  ;; a choice: results kept, oldest dropped first
  100)

(defonce ^:private state
  ;; :values is id -> value, oldest first
  (atom {:next 0 :values (sorted-map)}))

(defn kind
  "v's kind for the console's verbs: :var, :ns, :exception or :value.
  Never realizes v."
  [v]
  (cond (var? v) :var
        (instance? clojure.lang.Namespace v) :ns
        (instance? Throwable v) :exception
        :else :value))

(defn keep!
  "Stores v and answers {:id :kind}."
  [v]
  (let [s (swap! state (fn [{:keys [next values]}]
                         (let [values (assoc values next v)]
                           {:next (inc next)
                            :values (if (> (count values) RING) (dissoc values (first (keys values))) values)})))]
    {:id (dec (:next s)) :kind (kind v)}))

(defn value
  "The value kept under id, or ::gone once it left the ring."
  [id]
  (get (:values @state) id ::gone))

(defn value!
  "The value kept under id, throwing once it left the ring: what the
  console's Define puts at the prompt, so a gone value never quietly
  becomes ::gone in a var."
  [id]
  (let [v (value id)]
    (if (= v ::gone)
      (throw (ex-info "That result is gone." {:id id}))
      v)))
