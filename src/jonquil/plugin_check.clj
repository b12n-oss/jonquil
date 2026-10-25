(ns jonquil.plugin-check
  "The plugin validator (plugins design, Part 2, the protocol's validate
  step): one plugin file's definitions against a description of Jonquil,
  answered as {:ok? :problems :warnings}, each problem or warning one
  sentence naming the item. Pure, and its request is plain data (the
  registered commands as {id {:scope s}}, the condition ids, the shipped
  keymap, the OS name), so the isolated check can send the same request to
  a throwaway Jolt as EDN and run this same code there. Malformed input
  gets a sentence, never a throw."
  (:require [clojure.string :as str]
            [jonquil.plugin :as p]))

(def EFFECTS
  "The effect vocabulary a plugin declares from; #{} means pure."
  #{:buffer :console :eval :fs :proc :net})

(def HOOKS
  "The hook points a plugin may name in :hooks."
  #{:before-run :after-run :after-save :on-error})

(def ^:private top-keys #{:id :doc :effects :commands :bindings :hooks :mcp-tools ::p/line})

(def ^:private id-pattern #"[a-z][a-z0-9-]*")

(defn request-from-registry
  "The data part of a validate request: commands (id -> a registry entry)
  reduced to {id {:scope s}}, with conditions, keymap and os as given."
  [{:keys [commands conditions keymap os]}]
  {:registered (into {} (map (fn [[id c]] [id {:scope (:scope c)}])) commands)
   :conditions (set conditions) :keymap keymap :os os})

(defn- blank-string? [s] (not (and (string? s) (not (str/blank? s)))))

(defn- definition-problems [d file-id]
  (cond-> []
    (not (and (string? file-id) (re-matches id-pattern file-id)))
    (conj (str "the file name " (pr-str file-id) " isn't a plugin id: lower-case letters, digits and -, "
               "starting with a letter"))
    (seq (remove top-keys (keys d)))
    (conj (str "defplugin has unknown keys " (pr-str (vec (remove top-keys (keys d))))))
    (not (and (keyword? (:id d)) (nil? (namespace (:id d)))))
    (conj (str ":id " (pr-str (:id d)) " isn't a plain keyword"))
    (and (keyword? (:id d)) (nil? (namespace (:id d))) (not= (name (:id d)) file-id))
    (conj (str ":id " (pr-str (:id d)) " doesn't match the file name " (pr-str file-id)))
    (blank-string? (:doc d))
    (conj ":doc isn't a sentence; the palette and the plugin list show it")))

(defn- effects-problems [d]
  (let [e (:effects d)]
    (cond
      (not (contains? d :effects)) ["declares no :effects; write #{} for a pure plugin"]
      (not (set? e)) [(str ":effects " (pr-str e) " isn't a set")]
      (seq (remove EFFECTS e))
      [(str ":effects names " (pr-str (vec (remove EFFECTS e))) ", not one of " (pr-str (sort EFFECTS)))]
      :else [])))

(defn- command-problems [i c plugin-name pure? seen]
  (if-not (map? c)
    [(str "command " i " isn't a map")]
    (let [{:keys [id doc scope edit run]} c
          label (str "command " i " (" (pr-str id) ")")]
      (cond-> []
        (and plugin-name (not (and (keyword? id) (= plugin-name (namespace id)))))
        (conj (str label ": its id isn't :" plugin-name "/<name>"))
        (contains? seen id) (conj (str label ": its id is used twice"))
        (blank-string? doc) (conj (str label ": :doc isn't a sentence"))
        (not (contains? #{:window :editor} scope))
        (conj (str label ": :scope " (pr-str scope) " isn't :window or :editor"))
        (and (some? edit) (some? run)) (conj (str label ": has both :edit and :run"))
        (and (nil? edit) (nil? run)) (conj (str label ": has neither :edit nor :run"))
        (and (some? edit) (not (fn? edit))) (conj (str label ": :edit isn't a function"))
        (and (some? run) (not (fn? run))) (conj (str label ": :run isn't a function"))
        (and (some? edit) (nil? run) (not= :editor scope) (contains? #{:window :editor} scope))
        (conj (str label ": an :edit command acts on the editor, so its :scope is :editor"))
        (and (some? run) (nil? edit) pure?)
        (conj (str label ": a pure plugin's commands are :edit commands; declare the :effects its :run uses"))))))

(defn- commands-problems
  "The file name is the plugin's id (a wrong :id is its own problem), so
  command ids are checked against it, and not at all when it's invalid."
  [d file-id]
  (let [cs (:commands d)
        plugin-name (when (and (string? file-id) (re-matches id-pattern file-id)) file-id)
        pure? (and (set? (:effects d)) (empty? (:effects d)))]
    (cond
      (nil? cs) []
      (not (vector? cs)) [":commands isn't a vector"]
      :else (loop [i 0 seen #{} out []]
              (if (< i (count cs))
                (let [c (nth cs i)]
                  (recur (inc i) (if (map? c) (conj seen (:id c)) seen)
                         (into out (command-problems i c plugin-name pure? seen))))
                out)))))

(defn- hooks-problems [d]
  (let [h (:hooks d)]
    (cond
      (nil? h) []
      (not (map? h)) [":hooks isn't a map of hook point to function"]
      :else (vec (concat
                  (for [k (keys h) :when (not (contains? HOOKS k))]
                    (str ":hooks names " (pr-str k) ", not one of " (pr-str (sort HOOKS))))
                  (for [[k f] h :when (and (contains? HOOKS k) (not (fn? f)))]
                    (str "hook " (pr-str k) " isn't a function")))))))

(defn- mcp-tools-problems [d]
  (if (and (contains? d :mcp-tools) (not (vector? (:mcp-tools d))))
    [":mcp-tools isn't a vector"]
    []))

(defn validate
  "One plugin file's {:ok? :problems :warnings}. definitions is what the
  file's defplugin calls recorded; file-id its name without .clj;
  registered {command-id {:scope s}}; conditions the condition ids; keymap
  the shipped {:window [...] :editor [...]}; os the OS name, for chords in
  messages."
  [{:keys [definitions file-id] :as _request}]
  (let [n (count definitions)
        d (first definitions)
        problems (cond
                   (zero? n) ["defplugin wasn't called"]
                   (> n 1) [(str "defplugin was called " n " times; a plugin file calls it once")]
                   (not (map? d)) ["defplugin's argument isn't a map"]
                   :else (vec (concat (definition-problems d file-id)
                                      (effects-problems d)
                                      (commands-problems d file-id)
                                      (hooks-problems d)
                                      (mcp-tools-problems d))))]
    {:ok? (empty? problems) :problems problems :warnings []}))
