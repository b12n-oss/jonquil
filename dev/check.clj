(ns check
  "Headless compile-check: require every project namespace and exit 0.")

(def namespaces
  ;; Project namespaces in dependency order. Add each one as it lands.
  '[jonquil.core
    jonquil.dialect
    jonquil.syntax
    jonquil.theme
    jonquil.settings
    jonquil.paint
    jonquil.docs
    jonquil.edit
    jonquil.eval
    jonquil.console
    jonquil.evaluator
    jonquil.session
    jonquil.inspect.view
    jonquil.inspect
    jonquil.nrepl.bencode
    jonquil.nrepl.ops
    jonquil.cljd
    jonquil.clj
    jonquil.nrepl.client
    jonquil.nrepl.connection
    jonquil.nrepl.child
    jonquil.nrepl.evaluator
    jonquil.nrepl.attach
    jonquil.nrepl.jvm
    jonquil.runtime-registry
    jonquil.commands
    jonquil.help
    jonquil.source
    jonquil.gtk.ffi
    jonquil.gtk.callbacks
    jonquil.gtk.popover
    jonquil.gtk.help-popover
    jonquil.gtk.inspector-pane
    jonquil.gtk.editor
    jonquil.gtk.completion
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
