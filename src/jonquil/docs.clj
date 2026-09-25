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

(defonce ^:private doc-cache (atom {}))

(defn- resolve-ns
  "The namespace named ns-sym, or `user` when it no longer exists."
  [ns-sym]
  (or (find-ns ns-sym) (the-ns 'user)))

(defn clear-doc-cache!
  "Empties the symbol-doc memo (tests, and after a Run redefines names)."
  []
  (reset! doc-cache {}))

(defn symbol-doc
  "syntax/get-symbol-doc with ns-sym's namespace as *ns* (or `user` when it
  is gone). Memoised on [ns-sym sym-name text]; nil when the lookup throws."
  [ns-sym sym-name text pos]
  (let [k [ns-sym sym-name text]]
    (if-let [hit (find @doc-cache k)]
      (val hit)
      (let [v (try (binding [*ns* (resolve-ns ns-sym)]
                     (syntax/get-symbol-doc sym-name text pos))
                   (catch Exception _ nil))]
        (swap! doc-cache (fn [m] (assoc (if (>= (count m) doc-cache-limit) {} m) k v)))
        v))))

(defn candidates
  "syntax/get-autocomplete-candidates with ns-sym's namespace as *ns* (or
  `user` when it is gone). [] when the lookup throws."
  ([ns-sym prefix text pos] (candidates ns-sym prefix text pos nil))
  ([ns-sym prefix text pos extra-context]
   (try (binding [*ns* (resolve-ns ns-sym)]
          (syntax/get-autocomplete-candidates prefix text pos extra-context))
        (catch Exception _ []))))
