(ns check
  "Headless compile-check: require every project namespace and exit 0.")

(def namespaces
  ;; Project namespaces in dependency order. Add each one as it lands.
  '[jonquil.core
    jonquil.syntax])

(defn -main [& _]
  (doseq [n namespaces]
    (require n)
    (println "ok" n))
  (println (str (count namespaces) " namespaces loaded"))
  (System/exit 0))
