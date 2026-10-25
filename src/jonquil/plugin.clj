(ns jonquil.plugin
  "The plugin API (plugins design, Part 2): the one namespace a plugin may
  assume exists. Everything else in jonquil.* is reachable from a plugin
  but carries no stability promise. A plugin file calls defplugin once;
  slice P2 adds the pure helpers its commands use (the form at the caret,
  wrapping it) and the loader that binds *definitions*.")

(def ^:dynamic *definitions*
  "While Jonquil loads a plugin file, an atom of the definitions its
  defplugin calls made, in order; nil at any other time."
  nil)

(defn record!
  "defplugin's work: conj m, with the call's line, onto *definitions*.
  Throws when no plugin load is under way."
  [m line]
  (if-let [defs *definitions*]
    ;; a non-map is recorded as it is, for the validator to name
    (do (swap! defs conj (if (map? m) (assoc m ::line line) m)) nil)
    (throw (ex-info "defplugin only runs while Jonquil loads a plugin file"
                    {:plugin/outside-load true}))))

(defmacro defplugin
  "Declares this file's plugin: m is {:id :doc :effects :commands :bindings
  :hooks :mcp-tools} (see the plugins guide). The call's line is kept, so a
  problem with the definition can name it."
  [m]
  `(record! ~m ~(:line (meta &form))))
