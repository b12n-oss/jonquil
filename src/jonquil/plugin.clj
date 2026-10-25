(ns jonquil.plugin
  "The plugin API (plugins design, Part 2): the one namespace a plugin may
  assume exists. Everything else in jonquil.* is reachable from a plugin
  but carries no stability promise. A plugin file calls defplugin once.
  The helpers below are pure, over a snapshot {:text :caret :sel-start
  :sel-end}, and an :edit command returns jonquil.edit's op shape
  {:edits [[start end replacement] ...] :caret n}, or nil when it doesn't
  apply."
  (:require [jonquil.commands :as commands]))

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

(defn form-at-caret
  "The form around the caret, {:text :start :end}, or nil: the form Run Form
  would send (jonquil.commands/run-form), so inside a (comment ...) block
  it's the form there that holds the caret."
  [snapshot]
  (commands/run-form snapshot))

(defn wrap-form
  "An edit op wrapping the form at the caret in open and close, the caret
  moving with the text; nil when the caret is on no form."
  [snapshot open close]
  (when-let [{:keys [start end]} (form-at-caret snapshot)]
    {:edits [[start start open] [end end close]]
     :caret (+ (:caret snapshot) (count open))}))
