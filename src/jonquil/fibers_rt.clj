(ns jonquil.fibers-rt
  "LM4's Processes panel where the code runs (in process, or embedded in a
  Jolt child like jonquil.trace-rt): Jolt's jolt.fibers keeps no list of
  live fibers, so this wraps jolt.fibers/spawn (as Trace wraps a var, so
  code compiled earlier goes through it too) and records each fiber spawned
  from then on. Every answer is a map, never a throw."
  (:require [clojure.string :as str]))

(def NO-API "Processes need a Jolt with jolt.fibers.")

(def ^:private KEEP-FINISHED
  ;; finished fibers kept for the panel, the newest
  50)

(defonce ^:private registry
  ;; {:next id :fibers {id {:fiber f :label s :at ms}} :wrapped? b}
  (atom {:next 1 :fibers (sorted-map) :wrapped? false}))

(defn available? []
  (boolean (try (requiring-resolve 'jolt.fibers/spawn) (catch Throwable _ nil))))

(defn- f* [sym] (requiring-resolve (symbol "jolt.fibers" (name sym))))

(defn label
  "What a spawned fn reads as: user$worker@1f2e as user/worker, an
  anonymous one as \"a fn\"."
  [f]
  (let [s (str/replace (str f) #"@[0-9a-f]+$" "")]
    (if (or (str/includes? s "AFunction") (str/blank? s))
      "a fn"
      (-> s (str/replace-first "$" "/") (str/replace #"\$fn__\d+" "") (str/replace "$" "/")))))

(defn- finished? [state] (contains? #{:done :dead} state))

(defn- state-of [fib] (try ((f* 'state) fib) (catch Throwable _ :dead)))

(defn- prune
  "The registry with at most KEEP-FINISHED finished fibers, the newest."
  [{:keys [fibers] :as r}]
  (let [done (filter (fn [[_ {:keys [fiber]}]] (finished? (state-of fiber))) (rseq fibers))
        drop (map first (drop KEEP-FINISHED done))]
    (assoc r :fibers (apply dissoc fibers drop))))

(defn- record! [fib f]
  (swap! registry (fn [{:keys [next] :as r}]
                    (prune (-> r
                               (assoc :next (inc next))
                               (assoc-in [:fibers next] {:fiber fib :label (label f) :at (System/currentTimeMillis)}))))))

(defn- watch! []
  (locking registry
    (when-not (:wrapped? @registry)
      (alter-var-root (f* 'spawn)
                      (fn [orig]
                        (fn [& args]
                          (let [fib (apply orig args)]
                            (try (record! fib (first args)) (catch Throwable _ nil))
                            fib))))
      (swap! registry assoc :wrapped? true)))
  {:watching? true})

(defn- fiber-entry [id {:keys [fiber label at]} now]
  {:id id :label label :state (state-of fiber) :age-ms (max 0 (- now at))})

(defn- signal! [id kind]
  (if-let [{:keys [fiber]} (get-in @registry [:fibers id])]
    (if (finished? (state-of fiber))
      {:error (str "Fiber #" id " has already finished.")}
      (do ((f* (if (= kind :kill) 'kill! 'interrupt!))
           fiber (ex-info "Interrupted from Jonquil's Processes panel" {:fiber id}))
          {:ok true}))
    {:error (str "No fiber #" id ".")}))

(defn op
  "One Processes panel op: :watch, :list, :interrupt {:id}, :kill {:id},
  :forget (drops finished fibers). {:error s} without jolt.fibers or on a
  throw."
  [k args]
  (if-not (available?)
    {:error NO-API}
    (try
      (case k
        :watch (watch!)
        :list (let [now (System/currentTimeMillis)
                    {:keys [fibers wrapped?]} (swap! registry prune)]
                {:watching? wrapped?
                 :fibers (vec (for [[id e] (rseq fibers)] (fiber-entry id e now)))})
        :interrupt (signal! (:id args) :interrupt)
        :kill (signal! (:id args) :kill)
        :forget (do (swap! registry update :fibers
                           (fn [fs] (into (sorted-map) (remove (fn [[_ {:keys [fiber]}]] (finished? (state-of fiber)))) fs)))
                    {:ok true})
        {:error (str "Unknown Processes op " k)})
      (catch Throwable e {:error (str "Processes: " (or (ex-message e) (str (class e))))}))))
