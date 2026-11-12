(ns jonquil.docs
  "Documentation and completion lookups resolved in the namespace the user's
  program runs in. Upstream resolved them in the IDE's own *ns*, which misses
  everything Run defined.

  A namespace that no longer exists (Reset on Run removed it) falls back to
  `user`: the-ns would throw \"No namespace: ... found\" (measured
  2026-09-26). symbol-doc is memoised on [namespace symbol text], because a
  name defined only in the buffer takes about 144 ms on a 2,000-line file
  (measured), too slow for a preview that follows every arrow key. The key
  includes the namespace, unlike upstream's docstring-cache, so a Run that
  redefines a name in another namespace can't return a stale doc."
  (:require [jonquil.syntax :as syntax]))

(def ^:private doc-cache-limit 256)

;; {:gen n :m {key doc}}. clear-doc-cache! bumps :gen, so a lookup that
;; started before a clear (a Run redefining the name) drops its answer
;; instead of storing it after the clear.
(defonce ^:private doc-cache (atom {:gen 0 :m {}}))

(defn- resolve-ns
  "The namespace named ns-sym, or `user` when it no longer exists."
  [ns-sym]
  (or (find-ns ns-sym) (the-ns 'user)))

(defn clear-doc-cache!
  "Empties the symbol-doc memo (tests, and after a Run redefines names)."
  []
  (swap! doc-cache (fn [{:keys [gen]}] {:gen (inc gen) :m {}}))
  nil)

(defn- lookup-doc
  "[ok? doc] for sym-name looked up with ns-sym's namespace as *ns*; [false
  nil] when the lookup throws."
  [{:keys [ns-sym sym-name text pos]}]
  (try [true (binding [*ns* (resolve-ns ns-sym)]
               (syntax/get-symbol-doc sym-name text pos))]
       (catch Exception _ [false nil])))

(defn- assoc-capped
  "m with k mapped to doc; a full m (doc-cache-limit entries) restarts with
  just this entry."
  [m k doc]
  (assoc (if (>= (count m) doc-cache-limit) {} m) k doc))

(defn- store-doc!
  "Memoises doc under k, unless a clear bumped the generation since gen. A
  full memo restarts with just this entry."
  [gen k doc]
  (swap! doc-cache (fn [c]
                     (if (= gen (:gen c))
                       (update c :m assoc-capped k doc)
                       c))))

(defn symbol-doc
  "syntax/get-symbol-doc with ns-sym's namespace as *ns* (or `user` when it
  is gone). Memoised on [ns-sym sym-name text]; nil when the lookup throws,
  and that nil isn't memoised, so the next call asks again."
  [ns-sym sym-name text pos]
  (let [k [ns-sym sym-name text]
        {:keys [gen m]} @doc-cache]
    (if-let [hit (find m k)]
      (val hit)
      (let [[ok? v] (lookup-doc {:ns-sym ns-sym :sym-name sym-name :text text :pos pos})]
        (when ok? (store-doc! gen k v))
        v))))

(defn candidates
  "syntax/get-autocomplete-candidates with ns-sym's namespace as *ns* (or
  `user` when it is gone). [] when the lookup throws."
  ([ns-sym prefix text pos] (candidates ns-sym prefix text pos nil))
  ([ns-sym prefix text pos extra-context]
   (try (binding [*ns* (resolve-ns ns-sym)]
          (syntax/get-autocomplete-candidates prefix text pos extra-context))
        (catch Exception _ []))))
