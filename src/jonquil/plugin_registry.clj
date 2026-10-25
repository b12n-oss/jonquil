(ns jonquil.plugin-registry
  "The live plugin registry (plugins design, Part 2): one atom, live, of
  {:plugins {id {:definition :source :file :layer}}}, and the tables the key
  handlers and the palette read from a registry value: the plugins' :edit
  commands as registry commands, their bindings per scope in plugin-id
  order, and each controller's bindings, the plugins' ahead of the shipped
  ones. With no plugins those are exactly the shipped tables. Pure apart
  from live. :run commands wait for effects (a later slice), so they and
  the bindings to them aren't here."
  (:require [jonquil.command-registry :as cmd-registry]
            [jonquil.keymap :as km]))

(defn empty-registry [] {:plugins {}})

(defonce live (atom (empty-registry)))

(defn install
  "reg with id's entry {:definition :source :file :layer} replaced."
  [reg id entry]
  (assoc-in reg [:plugins id] entry))

(defn remove-plugin [reg id]
  (update reg :plugins dissoc id))

(defn- in-order [reg] (sort-by key (:plugins reg)))

(defn- edit-command
  "A registry command running a plugin's :edit: the op it returns on the
  ctx's snapshot is applied (:handled), nil leaves the key to GTK as a
  built-in edit that changed nothing does (:to-gtk). A throw comes back
  naming the plugin, for the handler's catch to report."
  [plugin-id edit]
  (fn [ctx _args]
    (let [op (try (edit ((:snapshot ctx)))
                  (catch Throwable e
                    (throw (ex-info (str "plugin " (name plugin-id) ": " (or (ex-message e) (str e)))
                                    {:plugin plugin-id} e))))]
      (if op
        (do ((:apply-op! ctx) op) :handled)
        :to-gtk))))

(defn commands
  "{command-id {:doc :scope :plugin :run}} for every installed plugin's
  :edit commands."
  [reg]
  (into {}
        (for [[id {:keys [definition]}] (in-order reg)
              {cid :id :keys [doc scope edit]} (:commands definition)
              :when (fn? edit)]
          [cid {:doc doc :scope scope :plugin id :run (edit-command id edit)}])))

(defn all-commands
  "The built-in commands and reg's plugin commands, for the palette."
  [reg]
  (merge cmd-registry/commands (commands reg)))

(defn bindings
  "The installed plugins' bindings in scope, ordered by plugin id then file
  order, each one whose command is a live plugin command or a built-in of
  that scope."
  [reg scope]
  (let [known (all-commands reg)]
    (vec (for [[_ {:keys [definition]}] (in-order reg)
               b (:bindings definition)
               :when (= scope (:scope (get known (:command b))))]
           b))))

(defn window-bindings [reg] (into (bindings reg :window) (:window km/shipped)))

(defn editor-bindings [reg] (into (bindings reg :editor) (:editor km/shipped)))
