(ns jonquil.docs
  "Documentation and completion lookups resolved in the namespace the user's
  program runs in. Upstream resolved them in the IDE's own *ns*, which misses
  everything Run defined."
  (:require [jonquil.syntax :as syntax]))

(defn symbol-doc
  "syntax/get-symbol-doc with ns-sym as *ns*. ns-sym must name an existing
  namespace."
  [ns-sym sym-name text pos]
  (binding [*ns* (the-ns ns-sym)]
    (syntax/get-symbol-doc sym-name text pos)))

(defn candidates
  "syntax/get-autocomplete-candidates with ns-sym as *ns*."
  [ns-sym prefix text pos]
  (binding [*ns* (the-ns ns-sym)]
    (syntax/get-autocomplete-candidates prefix text pos)))
