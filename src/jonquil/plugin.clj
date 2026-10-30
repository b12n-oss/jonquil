(ns jonquil.plugin
  "The plugin API: the one namespace a plugin may
  assume exists. Everything else in jonquil.* is reachable from a plugin
  but carries no stability promise. A plugin file calls defplugin once.
  The helpers below are pure, over a snapshot {:text :caret :sel-start
  :sel-end}, and an :edit command returns jonquil.edit's op shape
  {:edits [[start end replacement] ...] :caret n}, or nil when it doesn't
  apply. A :run command (fn [ctx]) gets the effect functions at the end,
  each refusing an effect its plugin didn't declare."
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

;; The effect functions, for :run commands. A command's ctx carries the
;; effects its plugin declared (::effects), their implementations (::impl,
;; only the declared ones') and the tab that was active when it started
;; (::tab). Each function checks its effect first, so an undeclared one
;; throws before anything is reached. A ctx with ::refused (a :before-run
;; hook's) refuses every effect with that sentence.

(defn- effect!
  "The ctx's implementation named k, after checking that effect is declared."
  [ctx effect k]
  (when-let [why (::refused ctx)]
    (throw (ex-info why {:plugin/effect effect})))
  (when-not (contains? (::effects ctx) effect)
    (throw (ex-info (str "this plugin didn't declare " effect) {:plugin/effect effect})))
  (get (::impl ctx) k))

(defn print!
  "(:console) Prints s on the console."
  [ctx s]
  ((effect! ctx :console :print!) s)
  nil)

(defn status!
  "(:console) Shows s on the status bar."
  [ctx s]
  ((effect! ctx :console :status!) s)
  nil)

(defn buffer-text
  "(:buffer) The text of the command's tab. Throws \"that tab was closed\"
  once it has."
  [ctx]
  ((effect! ctx :buffer :buffer-text) (::tab ctx)))

(defn replace-text!
  "(:buffer) Replaces the whole text of the command's tab, as one undo step."
  [ctx text]
  ((effect! ctx :buffer :replace-text!) (::tab ctx) text)
  nil)

(defn open!
  "(:buffer) Opens path in a tab and answers that tab's id, for a ctx
  assoc'd ::tab to act on it."
  [ctx path]
  ((effect! ctx :buffer :open!) path))

(defn eval!
  "(:eval) Evaluates code quietly in the command's tab's namespace: nothing
  at the console, no history entry, *1 *2 *3 *e untouched. Blocks until it
  answers, or opts' :timeout-ms (default 10000) passes. {:value :out :err},
  with :error (and :value nil) when the code threw. Throws when the REPL is
  busy, on a timeout or a read from the console (the eval is stopped), on
  a dialect other than Jolt, and when the tab has closed."
  ([ctx code] (eval! ctx code {}))
  ([ctx code opts] ((effect! ctx :eval :eval!) (::tab ctx) code opts)))

(defn repl!
  "(:eval) Evaluates code at the console as if typed there: echoed, shown,
  added to the history. {:value :out}, with :error when it threw; throws as
  eval! does, except that a read from the console waits for the person."
  ([ctx code] (repl! ctx code {}))
  ([ctx code opts] ((effect! ctx :eval :repl!) (::tab ctx) code opts)))
