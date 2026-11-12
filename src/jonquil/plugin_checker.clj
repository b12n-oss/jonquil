(ns jonquil.plugin-checker
  "The program an isolated check runs in a throwaway jolt child. It reads request.edn
  and candidate.clj from its working folder, puts the candidate through
  jonquil.plugin-protocol/run-protocol :in-process (the child's own
  process), prints one EDN result between MARK-START and MARK-END, and
  exits. Embedded in Jonquil by jonquil.plugin-checker-files, so it
  requires only what that carries."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [jonquil.plugin-protocol :as protocol]))

(def MARK-START "__JONQUIL_CHECK__")
(def MARK-END "__END_CHECK__")

(defn- tool-summary
  "A tool row's :name :doc :args, or the row itself when it isn't a map."
  [row]
  (if (map? row) (select-keys row [:name :doc :args]) row))

(defn summary
  "A plugin definition as plain data, nothing that can't print and read
  back: its id, doc and effects, each command's id, doc and scope, its
  bindings, its hook points, and (only when it has the key,
  so a summary from before then still compares equal) its MCP tools'
  name, doc and args. nil for nil."
  [definition]
  (when definition
    (cond-> {:id (:id definition) :doc (:doc definition) :effects (:effects definition)
             :commands (mapv #(select-keys % [:id :doc :scope]) (:commands definition))
             :bindings (vec (:bindings definition))
             :hooks (vec (sort (keys (:hooks definition))))}
      (contains? definition :mcp-tools)
      (assoc :mcp-tools (mapv tool-summary (:mcp-tools definition))))))

(defn check-dir
  "The check of dir's candidate.clj against dir's request.edn ({:request
  :file-id :load-ms :soak-ms}): {:ok? :problems :warnings :timings
  :plugin}. Never throws."
  [dir]
  (try
    (let [{:keys [request file-id load-ms soak-ms]} (edn/read-string (slurp (io/file dir "request.edn")))
          source (slurp (io/file dir "candidate.clj"))
          r (protocol/run-protocol {:where :in-process :source source :file-id file-id
                                    :request request :load-ms load-ms :soak-ms soak-ms})]
      (-> (select-keys r [:ok? :problems :warnings :timings])
          (assoc :plugin (summary (:definition r)))))
    (catch Throwable e
      {:ok? false :problems [(str "the checker couldn't run: " (or (ex-message e) (str e)))]
       :warnings [] :timings {}})))

(defn result-text
  "result between the two marker lines, with no #:ns{} map syntax (the
  :timings map's keys share a namespace), as child.clj prints its coord."
  [result]
  (str "\n" MARK-START "\n" (binding [*print-namespace-maps* false] (pr-str result)) "\n" MARK-END "\n"))

(defn -main
  "Checks the working folder, prints the result and exits: a soak thread
  the check gave up on would otherwise keep the process alive (probed: a
  leftover future held jolt -e about a minute)."
  [& _]
  (print (result-text (check-dir ".")))
  (flush)
  (System/exit 0))
