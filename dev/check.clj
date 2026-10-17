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
    jonquil.mcp
    jonquil.mcp.json
    jonquil.mcp.tools
    jonquil.evaluator
    jonquil.session
    jonquil.inspect.view
    jonquil.inspect
    jonquil.nrepl.bencode
    jonquil.nrepl.ops
    jonquil.cljd
    jonquil.clj
    jonquil.jank
    jonquil.nrepl.client
    jonquil.nrepl.connection
    jonquil.tool-path
    jonquil.save-path
    jonquil.login-path
    jonquil.nrepl.jank-child
    jonquil.nrepl.child
    jonquil.nrepl.evaluator
    jonquil.nrepl.attach
    jonquil.nrepl.jvm
    jonquil.nrepl.jank
    jonquil.runtime-registry
    jonquil.commands
    jonquil.help
    jonquil.source
    jonquil.tabs
    jonquil.file-ops
    jonquil.last-session
    jonquil.file-tree
    jonquil.gtk.ffi
    jonquil.gtk.appearance
    jonquil.gtk.trash
    jonquil.gtk.callbacks
    jonquil.gtk.popover
    jonquil.gtk.help-popover
    jonquil.gtk.inspector-pane
    jonquil.gtk.editor
    jonquil.gtk.source-pane
    jonquil.gtk.completion
    jonquil.gtk.find-bar
    jonquil.gtk.console-view
    jonquil.gtk.tab
    jonquil.gtk.file-tree-pane
    jonquil.gtk.window
    jonquil.mcp.window
    jonquil.main])

(defn -main [& _]
  (doseq [n namespaces]
    (require n)
    (println "ok" n))
  (println (str (count namespaces) " namespaces loaded"))
  (System/exit 0))
