(ns jonquil.fibers-rt
  "LM4's Processes panel where the code runs (in process, or embedded in a
  Jolt child like jonquil.trace-rt): Jolt's jolt.fibers keeps no list of
  live fibers, so this wraps jolt.fibers/spawn (as Trace wraps a var, so
  code compiled earlier goes through it too) and records each fiber spawned
  from then on. core.async's io-thread, :fiber go blocks and flow processes
  spawn below jolt.fibers/spawn, so they are never recorded; a Jolt-side
  list of live fibers is jolt-lang/jolt#1606. Every answer is a map, never
  a throw."
  (:require [clojure.string :as str]))

(def NO-API "Processes need a Jolt with jolt.fibers.")

(def ^:private KEEP-FINISHED
  ;; finished fibers kept for the panel, the newest
  50)

(defonce ^:private registry
  ;; {:next id :fibers {id {:fiber f :label s :at ms}} :finished [id ...]
  ;;  :wrapped? b}; :finished holds the finished ids oldest first, at most
  ;; KEEP-FINISHED, so a spawn never scans the fibers
  (atom {:next 1 :fibers (sorted-map) :finished [] :wrapped? false}))

(defn available? []
  (boolean (try (requiring-resolve 'jolt.fibers/spawn) (catch Throwable _ nil))))

(defn- f* [sym] (requiring-resolve (symbol "jolt.fibers" (name sym))))

(defn label
  "What a spawned fn reads as: my_app.core$worker_QMARK_@1f2e as
  my-app.core/worker?, an anonymous one as \"a fn\"."
  [f]
  (let [s (str/replace (str f) #"@[0-9a-f]+$" "")
        demunge (try (requiring-resolve 'clojure.repl/demunge) (catch Throwable _ nil))]
    (if (or (str/includes? s "AFunction") (str/blank? s))
      "a fn"
      (let [named (-> s (str/replace #"\$fn__\d+" ""))]
        (if demunge (demunge named) (str/replace-first named "$" "/"))))))

(defn- finished? [state] (contains? #{:done :dead} state))

(defn- state-of [fib] (try ((f* 'state) fib) (catch Throwable _ :dead)))

(defn- note-finished
  "r with id among the finished, dropping the oldest finished fiber past
  KEEP-FINISHED."
  [r id]
  (let [fin (conj (:finished r) id)
        over (- (count fin) KEEP-FINISHED)]
    (if (pos? over)
      (-> r
          (assoc :finished (vec (drop over fin)))
          (update :fibers #(apply dissoc % (take over fin))))
      (assoc r :finished fin))))

(defn- record! [fib f]
  (let [id (:next (first (swap-vals! registry
                                     (fn [{:keys [next] :as r}]
                                       (-> r
                                           (assoc :next (inc next))
                                           (assoc-in [:fibers next] {:fiber fib :label (label f) :at (System/currentTimeMillis)}))))))]
    ;; called once it ends (at once when it already has), on its carrier
    ((f* 'monitor!) fib (fn [_] (swap! registry note-finished id)))))

(defn- recording-spawn
  "spawn wrapped to record each fiber it starts, so the panel can list it. A
  failure to record never reaches the spawner."
  [orig]
  (fn [& args]
    (let [fib (apply orig args)]
      (try (record! fib (first args)) (catch Throwable _ nil))
      fib)))

(defn- watch! []
  (locking registry
    (when-not (:wrapped? @registry)
      (alter-var-root (f* 'spawn) recording-spawn)
      (swap! registry assoc :wrapped? true)))
  {:watching? true})

(defn- fiber-entry [id {:keys [fiber label at]} now]
  {:id id :label label :state (state-of fiber) :age-ms (max 0 (- now at))})

(defn- signal! [id kind]
  (if-let [{:keys [fiber]} (get-in @registry [:fibers id])]
    ;; interrupt! and kill! answer false for a fiber that already finished,
    ;; including one that finished since the panel listed it
    (if (and (not (finished? (state-of fiber)))
             ((f* (if (= kind :kill) 'kill! 'interrupt!))
              fiber (ex-info "Interrupted from Jonquil's Processes panel" {:fiber id})))
      {:ok true}
      {:error (str "Fiber #" id " has already finished.")})
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
                    {:keys [fibers wrapped?]} @registry]
                {:watching? wrapped?
                 :fibers (vec (for [[id e] (rseq fibers)] (fiber-entry id e now)))})
        :interrupt (signal! (:id args) :interrupt)
        :kill (signal! (:id args) :kill)
        :forget (do (swap! registry (fn [{:keys [finished] :as r}]
                                      (-> r (update :fibers #(apply dissoc % finished)) (assoc :finished []))))
                    {:ok true})
        {:error (str "Unknown Processes op " k)})
      (catch Throwable e {:error (str "Processes: " (or (ex-message e) (str (class e))))}))))
