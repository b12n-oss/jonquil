(ns check
  "Headless compile-check: require every project namespace and exit 0.")

(def namespaces
  ;; Project namespaces in dependency order. Add each one as it lands.
  '[jonquil.core
    jonquil.syntax
    jonquil.theme
    jonquil.settings
    jonquil.paint
    jonquil.docs
    jonquil.edit
    jonquil.eval
    jonquil.console
    jonquil.session
    jonquil.commands
    jonquil.help
    jonquil.gtk.ffi
    jonquil.gtk.callbacks
    jonquil.gtk.editor
    jonquil.gtk.find-bar
    jonquil.gtk.console-view
    jonquil.gtk.window
    jonquil.main])

(defn -main [& _]
  (doseq [n namespaces]
    (require n)
    (println "ok" n))
  (println (str (count namespaces) " namespaces loaded"))
  (System/exit 0))
