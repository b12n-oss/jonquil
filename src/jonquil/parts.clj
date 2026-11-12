(ns jonquil.parts
  "Window parts: a feature of the window as a spec. This is the GTK-free
  data half, a part's commands, merged into jonquil.command-registry's
  public tables. A spec command names its context fn as :call data
  ([ctx-key & args]), never a :run fn, so a spec namespace needs nothing
  from the registry (which requires this namespace)."
  (:require [jonquil.parts.breakpoints :as breakpoints]
            [jonquil.parts.change-sets :as change-sets]
            [jonquil.parts.command-palette :as command-palette]
            [jonquil.parts.debugger :as debugger]
            [jonquil.parts.extra :as extra]
            [jonquil.parts.file-tree :as file-tree]
            [jonquil.parts.layouts :as layouts]
            [jonquil.parts.locations :as locations]
            [jonquil.parts.disk-poll :as disk-poll]
            [jonquil.parts.docs :as docs]
            [jonquil.parts.messages :as messages]
            [jonquil.parts.plugins :as plugins]
            [jonquil.parts.processes :as processes]
            [jonquil.parts.replay :as replay]
            [jonquil.parts.live-results :as live-results]
            [jonquil.parts.trace :as trace]
            [jonquil.parts.time-form :as time-form]
            [jonquil.parts.worlds :as worlds]))

(def specs
  "Every part's spec, {:id kw :commands {cmd-id {:doc s :scope sc :call [k & args]}}
  :panels [{:id kw :label s}] :bindings {:window [binding ...] :editor [binding ...]}};
  :panels and :bindings are optional, a binding has keymap.edn's shape. The
  real parts, then any registered in jonquil.parts.extra."
  (into [messages/spec disk-poll/spec layouts/spec worlds/spec time-form/spec processes/spec trace/spec live-results/spec locations/spec debugger/spec breakpoints/spec command-palette/spec file-tree/spec plugins/spec change-sets/spec docs/spec replay/spec] @extra/specs*))

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

(defn- check-command-shape
  "Throws unless cmd (of part-id) has a known :scope and a non-empty :call
  vector, since merge-commands would otherwise drop it without a word."
  [part-id id {:keys [scope call]}]
  (when-not (contains? #{:window :editor :console} scope)
    (throw (ex-info (str "command " id " of " part-id ": :scope must be :window, :editor or :console")
                    {:command id :part part-id :scope scope})))
  (when-not (and (vector? call) (seq call))
    (throw (ex-info (str "command " id " of " part-id ": :call must be a non-empty vector")
                    {:command id :part part-id :call call}))))

(defn merge-panels
  "core-labels ({panel-id label}) with every spec's :panels added. Throws on
  an id two owners share; an owner is :core or a spec's :id. :review and
  :history are core right-hand panels with no label (never offered by Switch
  to panel), so they are reserved for :core too."
  [core-labels specs]
  (loop [acc core-labels
         owners (reduce #(assoc %1 %2 :core) {} (concat (keys core-labels) [:review :history]))
         [[owner {:keys [id label]}] & more :as entries]
         (for [spec specs, panel (:panels spec)] [(:id spec) panel])]
    (if (empty? entries)
      acc
      (do (when-let [prior (get owners id)]
            (throw (ex-info (str "duplicate panel " id ": " prior " and " owner)
                            {:panel id :owners [prior owner]})))
          (recur (assoc acc id label) (assoc owners id owner) more)))))

(defn bindings
  "The specs' :bindings for scope (:window or :editor), concatenated in
  spec order."
  [specs scope]
  (into [] (mapcat #(get-in % [:bindings scope])) specs))

(defn- check-panels-and-bindings
  "Throws unless spec's :panels are {:id kw :label s} maps and its :bindings
  are a map of :window and :editor vectors."
  [{:keys [id panels bindings]}]
  (doseq [{pid :id label :label} panels]
    (when-not (and (keyword? pid) (string? label))
      (throw (ex-info (str "part " id ": a panel needs a keyword :id and a string :label")
                      {:part id :panel pid :label label}))))
  (doseq [[scope bs] bindings]
    (when-not (contains? #{:window :editor} scope)
      (throw (ex-info (str "part " id ": :bindings scope must be :window or :editor, got " scope)
                      {:part id :scope scope})))
    (when-not (vector? bs)
      (throw (ex-info (str "part " id ": :bindings " scope " must be a vector")
                      {:part id :scope scope})))))

(defn check-clashes
  "Throws when a command id has two owners across every scope: the core
  tables (cores, {scope table}) and every spec, whatever each command's
  scope. Same message as merge-commands. Also throws when a spec command
  has no known :scope or no :call vector. Answers nil."
  [cores specs]
  (doseq [spec specs]
    (check-panels-and-bindings spec))
  (doseq [spec specs, [id cmd] (:commands spec)]
    (check-command-shape (:id spec) id cmd))
  (reduce (fn [owners [owner id]] (claim owners id owner))
          (reduce #(claim %1 %2 :core) {} (mapcat keys (vals cores)))
          (for [spec specs, id (keys (:commands spec))] [(:id spec) id]))
  nil)

;; The install half. Still GTK-free: an install fn is handed in, so
;; jolt test runs all of this headless.

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

(def ^:private merged-kinds [:fns :ui :exports :panels])
(def ^:private contribution-keys #{:fns :ui :hooks :exports :panels})
(def ^:private hook-kinds #{:on-start :on-reroot :on-quit :on-tab-removed :on-tab-wired :on-switch})

(defn- check-keys
  [id contribution]
  (doseq [k (keys contribution)
          :when (not (contains? contribution-keys k))]
    (throw (ex-info (str "part " id ": unknown contribution key " k) {:part id :key k}))))

(defn- check-hooks
  [id contribution]
  (doseq [h (keys (:hooks contribution))
          :when (not (contains? hook-kinds h))]
    (throw (ex-info (str "part " id ": unknown hook " h) {:part id :hook h}))))

(defn- check-panel
  [id declared [k {:keys [root focus! open!]}]]
  (when-not (contains? declared k)
    (throw (ex-info (str "part " id ": panel " k " is not declared in its spec")
                    {:part id :panel k})))
  (when-not (and (some? root) (fn? focus!) (fn? open!))
    (throw (ex-info (str "part " id ": panel " k " needs a :root and :focus! and :open! fns")
                    {:part id :panel k}))))

(defn- check-panels
  [id specs contribution]
  (let [declared (into #{} (comp (filter #(= id (:id %))) (mapcat :panels) (map :id)) specs)]
    (doseq [entry (:panels contribution)]
      (check-panel id declared entry))))

(defn- check-exports
  [id wctx contribution]
  (doseq [k (keys (:exports contribution))
          :when (contains? wctx k)]
    (throw (ex-info (str "duplicate exports key " k ": :core and " id)
                    {:kind :exports :key k :owners [:core id]}))))

(defn- check-contribution
  "Throws unless contribution (of id) uses only the known keys and hook
  kinds, exports no key wctx already holds, and answers :panels only for ids
  the part's spec (in specs) declares."
  [id wctx specs contribution]
  (check-keys id contribution)
  (check-hooks id contribution)
  (check-panels id specs contribution)
  (check-exports id wctx contribution))

(defn- add-contribution
  "acc with one part's contribution folded in. Strict for :fns, :ui,
  :exports and :panels; :hooks append in part order."
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
  :panels {id {:root w :focus! f :open! f}}
  :owners {kind {k part-id}, :hooks {hook-kw [part-id ...]}}}, merged strictly: a duplicate :fns, :ui,
  :exports or :panels key throws naming the key and both part ids. An install fn that
  throws, or answers a contribution with an unknown key or hook kind, an
  :exports key wctx already holds or a :panels id its spec doesn't declare, is
  reported once, (on-error part-id ex), and contributes nothing; the others
  still install. opts' :specs (default none, so any :panels is refused) are
  the specs a part's id is looked up in."
  ([installs wctx on-error] (install installs wctx on-error {}))
  ([installs wctx on-error {:keys [specs]}]
   (reduce (fn [acc [i install-fn]]
             (let [id (part-id install-fn i)
                   contribution (try (let [c (install-fn (merge wctx (:exports acc)))]
                                       (check-contribution id wctx specs c)
                                       c)
                                     (catch :default ex (on-error id ex) nil))]
               (add-contribution acc id contribution)))
           {:fns {} :ui {} :hooks {} :exports {} :panels {}
            :owners {:fns {} :ui {} :exports {} :panels {} :hooks {}}}
           (map-indexed vector installs))))

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
         (catch :default ex (on-error id ex))))
  nil)

(defn run-tab-hooks!
  "Runs every part's hook of kind for tab, each called (f ctx tab), in part
  order, when ctx holds :part-contribution; a throw is reported through
  ctx's :part-error!, as run-hooks! does. Answers nil."
  [ctx kind tab]
  (when-let [contribution (:part-contribution ctx)]
    (run-hooks! contribution kind [ctx tab] (:part-error! ctx))))

(defn missing-calls
  "The :call keys that commands (id -> spec command) name and ctx-keys lacks."
  [commands ctx-keys]
  (into #{} (comp (keep (comp first :call)) (remove (set ctx-keys))) (vals commands)))
