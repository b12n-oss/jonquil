(ns jonquil.key-sets
  "Key sets: named groups of key bindings shipped with Jonquil, one of
  which is on at a time. The chosen set sits under your keys.edn and over
  the plugins' and the shipped bindings, so a key of yours still wins. No
  GTK here.

  :default binds nothing. Every other set is an EDN file under
  jonquil/keysets, shaped like keys.edn ({:window [...] :editor [...]})
  with a :label, where an entry may carry :os :mac or :os :linux to be on
  for that system only. settings.edn's :key-set names the chosen one."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defmacro ^:private embedded-text
  "The text of classpath file path, read when this form is compiled (the
  installed app has no source tree)."
  [path]
  (slurp (io/resource path)))

(def default-id
  "The key set a new install has: Jonquil's own keys and nothing more."
  :default)

(def sets
  "Every key set, in the order the chooser lists them: {:id :label :window
  [...] :editor [...]}."
  [{:id default-id :label "Jonquil default" :window [] :editor []}
   (assoc (edn/read-string (embedded-text "jonquil/keysets/calva.edn")) :id :calva)])

(def ids
  "The ids settings.edn's :key-set may hold."
  (set (map :id sets)))

(defn key-set
  "The set with id, or the default one for an id Jonquil doesn't know."
  [id]
  (or (some #(when (= id (:id %)) %) sets)
      (first sets)))

(defn label
  "The name the chooser shows for id."
  [id]
  (:label (key-set id)))

(defn system
  "The :os an entry names for os-name (the os.name property): :mac on
  macOS, :linux everywhere else."
  [os-name]
  (if (= "Mac OS X" os-name) :mac :linux))

(defn bindings
  "The bindings set id has for scope on os-name's system, in file order,
  without their :os."
  [id scope os-name]
  (let [sys (system os-name)]
    (into []
          (comp (filter #(contains? #{nil sys} (:os %)))
                (map #(dissoc % :os)))
          (get (key-set id) scope))))

(defonce ^{:doc "The id of the key set that is on. The Preferences part
  sets it from settings.edn when the window starts and when the chooser
  changes; jonquil.plugin-registry's window-bindings and editor-bindings
  read it."}
  live
  (atom default-id))

(defn live-bindings
  "The chosen set's bindings for scope on this system."
  [scope]
  (bindings @live scope (System/getProperty "os.name")))
