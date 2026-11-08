(ns jonquil.parts
  "Window parts: a feature of the window as a spec. This is the GTK-free
  data half, a part's commands, merged into jonquil.command-registry's
  public tables. A spec command names its context fn as :call data
  ([ctx-key & args]), never a :run fn, so a spec namespace needs nothing
  from the registry (which requires this namespace).")

(def specs
  "Every part's spec, {:id kw :commands {cmd-id {:doc s :scope sc :call [k & args]}}}."
  [])

(defn- claim
  "owners with id's owner added, or throws when id is already owned."
  [owners id owner]
  (if-let [prior (get owners id)]
    (throw (ex-info (str "duplicate command " id ": " prior " and " owner)
                    {:command id :owners [prior owner]}))
    (assoc owners id owner)))

(defn merge-commands
  "core merged with every spec command of scope. Each :call [k & args]
  becomes :run (apply call-fn k args). Throws on an id two owners share;
  an owner is :core or a spec's :id."
  [core specs scope call-fn]
  (let [owners (reduce #(claim %1 %2 :core) {} (keys core))
        entries (for [spec specs
                      [id cmd] (:commands spec)
                      :when (= scope (:scope cmd))]
                  [(:id spec) id cmd])]
    (loop [acc core, owners owners, [[owner id cmd] & more :as entries] entries]
      (if (empty? entries)
        acc
        (recur (assoc acc id (-> (dissoc cmd :call)
                                 (assoc :run (apply call-fn (:call cmd)))))
               (claim owners id owner)
               more)))))

(defn check-clashes
  "Throws when a command id has two owners across every scope: the core
  tables (cores, {scope table}) and every spec, whatever each command's
  scope. Same message as merge-commands. Answers nil."
  [cores specs]
  (reduce (fn [owners [owner id]] (claim owners id owner))
          (reduce #(claim %1 %2 :core) {} (mapcat keys (vals cores)))
          (for [spec specs, id (keys (:commands spec))] [(:id spec) id]))
  nil)
