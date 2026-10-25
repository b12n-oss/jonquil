(ns jonquil.plugin-registry
  "The live plugin registry (plugins design, Part 2): one atom, live, of
  {:plugins {id {:definition :source :file :layer}}}, and the tables the key
  handlers and the palette read from a registry value: the plugins' :edit
  commands as registry commands, their bindings per scope in plugin-id
  order, and each controller's bindings, the plugins' ahead of the shipped
  ones. With no plugins those are exactly the shipped tables. Pure apart
  from live, and from effects, which a :run command reads when it starts."
  (:require [jonquil.command-registry :as cmd-registry]
            [jonquil.keymap :as km]
            [jonquil.plugin :as p]
            [jonquil.plugin-protocol :as protocol]))

(defn empty-registry [] {:plugins {}})

(defonce live (atom (empty-registry)))

(defn install
  "reg with id's entry {:definition :source :file :layer} replaced."
  [reg id entry]
  (assoc-in reg [:plugins id] entry))

(defn remove-plugin [reg id]
  (update reg :plugins dissoc id))

;; The window's effect functions for :run commands: {:active-tab (fn [] id),
;; called on the GTK thread; :print! :status! :buffer-text :replace-text!
;; :open!, jonquil.plugin's implementations, called from a worker; :report!
;; (fn [line]) for a throw}. Empty until a window sets it.
(defonce effects (atom {}))

(defonce ^:private running (atom #{}))

(def ^:private effect-impls
  ;; the implementations each effect brings into a ctx
  {:console [:print! :status!]
   :buffer [:buffer-text :replace-text! :open!]})

(defn- in-order [reg] (sort-by key (:plugins reg)))

(defn- edit-command
  "A registry command running a plugin's :edit: the op it returns on the
  ctx's snapshot is checked against that snapshot (the soak only saw
  fixtures) and applied (:handled); nil leaves the key to GTK as a built-in
  edit that changed nothing does (:to-gtk). A throw, a bad op (nothing is
  applied) or a failed apply comes back naming the plugin, for the
  handler's catch to report."
  [plugin-id edit]
  (fn [ctx _args]
    (let [fail (fn [msg e] (throw (ex-info (str "plugin " (name plugin-id) ": " msg) {:plugin plugin-id} e)))
          snap ((:snapshot ctx))
          op (try (edit snap) (catch Throwable e (fail (or (ex-message e) (str e)) e)))]
      (if (nil? op)
        :to-gtk
        (if-let [why (binding [*print-length* 10 *print-level* 3]
                       (protocol/edit-result-problem snap op))]
          (fail why nil)
          (do (try ((:apply-op! ctx) op) (catch Throwable e (fail (or (ex-message e) (str e)) e)))
              :handled))))))

(defn- run-command
  "A registry command starting a plugin's :run (fn [ctx]) on a worker and
  answering :handled at once. The ctx holds the tab active now and only
  the declared effects' functions from effects; a throw is reported with
  the plugin's id. One run per command at a time: a press while the last
  is still going throws, naming it, for the handler's catch to report
  (nothing can stop a busy run, so a copy per press would keep a core
  each)."
  [plugin-id cid declared run]
  (fn [_ctx _args]
    (let [label (str "plugin " (name plugin-id) ": ")
          [before _] (swap-vals! running conj cid)]
      (when (contains? before cid)
        (throw (ex-info (str label cid " is still running") {:plugin plugin-id})))
      (try
        (let [fx @effects
              ctx {::p/effects declared
                   ::p/impl (select-keys fx (mapcat effect-impls declared))
                   ::p/tab (when-let [f (:active-tab fx)] (f))}]
          (future
            (try (run ctx)
                 (catch Throwable e
                   (when-let [report! (:report! fx)]
                     (report! (str label (or (ex-message e) (str e))))))
                 (finally (swap! running disj cid)))))
        (catch Throwable e
          ;; nothing started, so the command is free again
          (swap! running disj cid)
          (throw e)))
      :handled)))

(defn commands
  "{command-id {:doc :scope :plugin :run}} for every installed plugin's
  :edit and :run commands."
  [reg]
  (into {}
        (for [[id {:keys [definition]}] (in-order reg)
              {cid :id :keys [doc scope edit run]} (:commands definition)
              :when (or (fn? edit) (fn? run))]
          [cid {:doc doc :scope scope :plugin id
                :run (if (fn? edit)
                       (edit-command id edit)
                       (run-command id cid (set (:effects definition)) run))}])))

(defn all-commands
  "The built-in commands and reg's plugin commands, for the palette."
  [reg]
  (merge cmd-registry/commands (commands reg)))

(defn bindings-with-source
  "bindings, each tagged :plugin/source with the plugin that made it, for
  the validator to name it (the loader's request)."
  [reg scope]
  (let [known (all-commands reg)]
    (vec (for [[id {:keys [definition]}] (in-order reg)
               b (:bindings definition)
               :when (= scope (:scope (get known (:command b))))]
           (assoc b :plugin/source id)))))

(defn bindings
  "The installed plugins' bindings in scope, ordered by plugin id then file
  order, each one whose command is a live plugin command or a built-in of
  that scope."
  [reg scope]
  (mapv #(dissoc % :plugin/source) (bindings-with-source reg scope)))

(defn window-bindings [reg] (into (bindings reg :window) (:window km/shipped)))

(defn editor-bindings [reg] (into (bindings reg :editor) (:editor km/shipped)))
