(ns jonquil.plugin-protocol
  "The plugin protocol (plugins design, Part 2): every change to the live
  plugin set goes through run-protocol, which loads a candidate file,
  validates it (jonquil.plugin-check) and soaks its :edit commands on
  fixture snapshots, answering {:ok? :problems :warnings :timings
  :definition}. No GTK; nothing here goes live, and a caller (the user's
  reload, Accept, a revert) swaps the registry on a pass."
  (:require [jonquil.plugin :as p]))

(defn- load-problem
  "One sentence for a throw while loading: with its line when the reader or
  compiler gave one."
  [e]
  (let [line (:clojure.error/line (ex-data e))
        msg (or (ex-message e) (str e))]
    (if line
      (str "line " line ": " msg)
      (str "loading the file threw: " msg))))

(defn load-candidate
  "Loads source, a plugin file's text, with defplugin's collector bound and
  *ns* restored afterwards: {:definitions [...] :problems [...]}. Never
  throws; a load error is a problem with its line when it has one, and a
  file that doesn't end in jonquil.plugins.<file-id> is a problem too. The
  namespace the file made stays loaded."
  [{:keys [source file-id]}]
  (binding [*ns* *ns* p/*definitions* (atom [])]
    (let [threw (try (load-string source) nil (catch Throwable e e))
          defs @p/*definitions*
          ns-now (str (ns-name *ns*))
          wanted (str "jonquil.plugins." file-id)]
      {:definitions defs
       :problems (cond
                   threw [(load-problem threw)]
                   (not= wanted ns-now)
                   [(str "the file's namespace is " ns-now "; a plugin file's is " wanted)]
                   :else [])})))
