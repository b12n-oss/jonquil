(ns check
  "Headless compile-check: require every project namespace and exit 0.")

(def namespaces
  ;; Project namespaces in dependency order. Add each one as it lands.
  '[jonquil.core
    jonquil.dialect
    jonquil.syntax
    jonquil.parinfer
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
    jonquil.nrepl.info-middleware
    jonquil.project-roots
    jonquil.nrepl.inspect-middleware
    jonquil.nrepl.generic
    jonquil.nrepl.stdin-middleware
    jonquil.nrepl.middleware-files
    jonquil.nrepl.leftovers
    jonquil.nrepl.cljs-child
    jonquil.nrepl.cljs-node-files
    jonquil.nrepl.prepl
    jonquil.cljd
    jonquil.clj
    jonquil.cljs
    jonquil.cljs-node
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
    jonquil.nrepl.shadow
    jonquil.nrepl.jank
    jonquil.nrepl.cljs-node
    jonquil.runtime-registry
    jonquil.commands
    jonquil.keymap
    jonquil.command-registry
    jonquil.palette
    jonquil.plugin
    jonquil.plugin-check
    jonquil.plugin-protocol
    jonquil.plugin-registry
    jonquil.plugin-hooks
    jonquil.plugin-history
    jonquil.plugin-checker
    jonquil.plugin-checker-files
    jonquil.plugin-isolated
    jonquil.plugin-loader
    jonquil.plugin-review
    jonquil.plugin-list
    jonquil.plugin-mcp
    jonquil.gtk.review-pane
    jonquil.gtk.plugin-list-pane
    jonquil.gtk.plugin-history-pane
    jonquil.gtk.palette
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
    jonquil.mcp.listen
    jonquil.main])

(defn -main [& _]
  (doseq [n namespaces]
    (require n)
    (println "ok" n))
  (println (str (count namespaces) " namespaces loaded"))
  (System/exit 0))
