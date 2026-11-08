(ns jonquil.parts
  "Window parts: a feature of the window as a spec. This is the GTK-free
  data half, a part's commands, merged into jonquil.command-registry's
  public tables. A spec command names its context fn as :call data
  ([ctx-key & args]), never a :run fn, so a spec namespace needs nothing
  from the registry (which requires this namespace).")

(def specs
  "Every part's spec, {:id kw :commands {cmd-id {:doc s :scope sc :call [k & args]}}}."
  [])

(defn- claim
  "owners with id's owner added, or throws when id is already owned."
  [owners id owner]
  (if-let [prior (get owners id)]
    (throw (ex-info (str "duplicate command " id ": " prior " and " owner)
                    {:command id :owners [prior owner]}))
    (assoc owners id owner)))

(defn merge-commands
  "core merged with every spec command of scope. Each :call [k & args]
  becomes :run (apply call-fn k args). Throws on an id two owners share;
  an owner is :core or a spec's :id."
  [core specs scope call-fn]
  (let [owners (reduce #(claim %1 %2 :core) {} (keys core))
        entries (for [spec specs
                      [id cmd] (:commands spec)
                      :when (= scope (:scope cmd))]
                  [(:id spec) id cmd])]
    (loop [acc core, owners owners, [[owner id cmd] & more :as entries] entries]
      (if (empty? entries)
        acc
        (recur (assoc acc id (-> (dissoc cmd :call)
                                 (assoc :run (apply call-fn (:call cmd)))))
               (claim owners id owner)
               more)))))

(defn check-clashes
  "Throws when a command id has two owners across every scope: the core
  tables (cores, {scope table}) and every spec, whatever each command's
  scope. Same message as merge-commands. Answers nil."
  [cores specs]
  (reduce (fn [owners [owner id]] (claim owners id owner))
          (reduce #(claim %1 %2 :core) {} (mapcat keys (vals cores)))
          (for [spec specs, id (keys (:commands spec))] [(:id spec) id]))
  nil)

;; The install half. Still GTK-free: an install fn is handed in, so
;; bb test runs all of this headless.

(defn- part-id
  "An install fn's :part/id metadata, else its index in the installs."
  [install-fn index]
  (or (:part/id (meta install-fn)) index))

(defn- claim-key
  "owners with k added as owned by owner, or throws naming kind, k and both owners."
  [owners kind k owner]
  (if-let [prior (get owners k)]
    (throw (ex-info (str "duplicate " (name kind) " key " k ": " prior " and " owner)
                    {:kind kind :key k :owners [prior owner]}))
    (assoc owners k owner)))

(def ^:private merged-kinds [:fns :ui :exports])

(defn- add-contribution
  "acc with one part's contribution folded in. Strict for :fns, :ui and
  :exports; :hooks append in part order."
  [acc id {:keys [hooks] :as contribution}]
  (let [acc (reduce (fn [acc kind]
                      (reduce (fn [acc [k v]]
                                (-> acc
                                    (update-in [:owners kind] claim-key kind k id)
                                    (assoc-in [kind k] v)))
                              acc (get contribution kind)))
                    acc merged-kinds)]
    (reduce (fn [acc [hook f]]
              (-> acc
                  (update-in [:hooks hook] (fnil conj []) f)
                  (update-in [:owners :hooks hook] (fnil conj []) id)))
            acc hooks)))

(defn install
  "Calls each install fn with wctx plus the :exports of the parts before it,
  in order. Answers {:fns {} :ui {} :hooks {hook-kw [f ...]} :exports {}
  :owners {kind {k part-id}, :hooks {hook-kw [part-id ...]}}}, merged strictly: a duplicate :fns, :ui or
  :exports key throws naming the key and both part ids. An install fn that
  throws is reported once, (on-error part-id ex), and contributes nothing;
  the others still install."
  [installs wctx on-error]
  (reduce (fn [acc [i install-fn]]
            (let [id (part-id install-fn i)
                  contribution (try (install-fn (merge wctx (:exports acc)))
                                    (catch Throwable ex (on-error id ex) nil))]
              (add-contribution acc id contribution)))
          {:fns {} :ui {} :hooks {} :exports {} :owners {:fns {} :ui {} :exports {} :hooks {}}}
          (map-indexed vector installs)))

(defn merge-with-core
  "core (a map of kind's keys) with the contribution's kind keys added.
  Throws on a key core already has, naming it and :core and the part."
  [kind core contribution]
  (reduce (fn [acc [k v]]
            (when (contains? core k)
              (throw (ex-info (str "duplicate " (name kind) " key " k ": :core and "
                                   (get-in contribution [:owners kind k]))
                              {:kind kind :key k})))
            (assoc acc k v))
          core (get contribution kind)))

(defn run-hooks!
  "Calls every fn under hook-kw with args, in part order. A throwing hook is
  reported, (on-error part-id ex), and the rest still run. Answers nil."
  [contribution hook-kw args on-error]
  (doseq [[f id] (map vector
                      (get-in contribution [:hooks hook-kw])
                      (get-in contribution [:owners :hooks hook-kw]))]
    (try (apply f args)
         (catch Throwable ex (on-error id ex))))
  nil)

(defn missing-calls
  "The :call keys that commands (id -> spec command) name and ctx-keys lacks."
  [commands ctx-keys]
  (into #{} (comp (keep (comp first :call)) (remove (set ctx-keys))) (vals commands)))
