(ns test-runner
  "Run the project's test namespaces under Jolt and exit with a status.

  With no arguments every namespace in `test-namespaces` runs. With arguments,
  only the named ones do, which is the focused loop during TDD. The exit is
  explicit because a Jolt process with live threads may not exit on its own."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [jonquil.login-path :as login-path]
            [jonquil.nrepl.cljs-child :as cljs-child]
            [jonquil.nrepl.jank-child :as jank-child]
            [jonquil.plugin-isolated :as isolated]
            [jonquil.tool-path :as tool-path]))

(def test-namespaces
  ;; Add each new test namespace here as tasks land.
  '[gtk-async-test
    jonquil.app-bundle-test
    jonquil.app-release-test
    jonquil.metrics-gate-test
    jonquil.app-cairo-script-test
    jonquil.app-notarize-test
    jonquil.bundle-env-test
    jonquil.app-linux-test
    jonquil.smoke-test
    jonquil.upstream.syntax-test
    jonquil.upstream.parinfer-test
    jonquil.parinfer-edit-test
    jonquil.theme-test
    jonquil.settings-test
    jonquil.paint-test
    jonquil.syntax-test
    jonquil.docs-test
    jonquil.edit-test
    jonquil.eval-test
    jonquil.restarts-test
    jonquil.change-sets-test
    jonquil.compare-test
    jonquil.fibers-rt-test
    jonquil.hints-test
    jonquil.layouts-test
    jonquil.docs-panel-test
    jonquil.docs-part-test
    jonquil.line-counts-test
    jonquil.messages-test
    jonquil.registry-test
    jonquil.console-test
    jonquil.mcp-test
    jonquil.mcp.json-test
    jonquil.mcp.tools-test
    jonquil.mcp.window-test
    jonquil.mcp.listen-test
    jonquil.mcp.describe-test
    jonquil.session-test
    jonquil.commands-test
    jonquil.changed-test
    jonquil.breakpoints-test
    jonquil.trace-rt-test
    jonquil.world-rt-test
    jonquil.world-test
    jonquil.results-rt-test
    jonquil.results-test
    jonquil.keymap-test
    jonquil.parts-test
    jonquil.parts-panels-test
    jonquil.command-registry-test
    jonquil.keymap-shipped-test
    jonquil.palette-test
    jonquil.debugger-test
    jonquil.describe-test
    jonquil.locations-test
    jonquil.usages-test
    jonquil.file-refs-test
    jonquil.resolve-test
    jonquil.timing-test
    jonquil.plugin-test
    jonquil.plugin-check-test
    jonquil.plugin-protocol-test
    jonquil.plugin-registry-test
    jonquil.plugin-hooks-test
    jonquil.plugin-history-test
    jonquil.plugin-checker-test
    jonquil.plugin-checker-files-test
    jonquil.plugin-isolated-test
    jonquil.plugin-loader-test
    jonquil.plugin-review-test
    jonquil.plugin-list-test
    jonquil.plugin-mcp-test
    jonquil.help-test
    jonquil.doc-examples-test
    jonquil.source-test
    jonquil.tabs-test
    jonquil.file-ops-test
    jonquil.last-session-test
    jonquil.replay-test
    jonquil.file-tree-test
    jonquil.tool-path-test
    jonquil.save-path-test
    jonquil.login-path-test
    jonquil.main-test
    jonquil.version-test
    jonquil.dialect-test
    jonquil.evaluator-test
    jonquil.nrepl.bencode-test
    jonquil.nrepl.client-test
    jonquil.nrepl.connection-test
    jonquil.nrepl.jank-child-test
    jonquil.nrepl.ops-test
    jonquil.cljd-test
    jonquil.clj-test
    jonquil.cljs-test
    jonquil.cljs-node-test
    jonquil.jank-test
    jonquil.fake-cljd-nrepl-test
    jonquil.fake-jvm-nrepl-test
    jonquil.fake-shadow-nrepl-test
    jonquil.fake-cljs-prepl-test
    jonquil.fake-jank-nrepl-test
    jonquil.nrepl.attach-test
    jonquil.nrepl.jvm-frames-test
    jonquil.nrepl.jvm-test
    jonquil.nrepl.shadow-test
    jonquil.nrepl.jank-eval-test
    jonquil.nrepl.cljs-node-eval-test
    jonquil.gtk.appearance-test
    jonquil.gtk.trash-test
    jonquil.gtk.window-layers-test
    jonquil.gtk.window.status-test
    jonquil.gtk.window.alerts-test
    jonquil.gtk.window.files-test
    jonquil.gtk.window.plugin-agent-test
    jonquil.gtk.window.right-slot-test
    jonquil.gtk.window.theme-test
    jonquil.gtk.window.runtime-test
    jonquil.gtk.window.session-test
    jonquil.gtk.window.quit-test
    jonquil.gtk.window.run-test
    jonquil.gtk.window.inspector-test
    jonquil.gtk.window.keys-test
    jonquil.gtk.window.help-test
    jonquil.gtk.window.tabs-test
    jonquil.gtk.window.reopen-test
    jonquil.gtk.window.tree-ops-test
    jonquil.gtk.window.status-bar-test
    jonquil.gtk.window.console-test
    jonquil.gtk.window.context-test
    jonquil.gtk.window.layout-test
    jonquil.gtk.window.startup-test
    jonquil.gtk.window.ui-test
    jonquil.gtk.ffi-test
    jonquil.gtk.editor-test
    jonquil.gtk.change-sets-part-test
    jonquil.gtk.processes-pane-test
    jonquil.gtk.messages-pane-test
    jonquil.inspect.view-test
    jonquil.nrepl.info-middleware-test
    jonquil.project-roots-test
    jonquil.nrepl.inspect-middleware-test
    jonquil.nrepl.generic-test
    jonquil.nrepl.stdin-middleware-test
    jonquil.nrepl.break-middleware-test
    jonquil.nrepl.middleware-files-test
    jonquil.nrepl.leftovers-test
    jonquil.nrepl.cljs-child-test
    jonquil.nrepl.cljs-node-files-test
    jonquil.nrepl.prepl-test
    jonquil.inspect-test])

(def live-namespaces
  ;; Tests that spawn real Jolt nREPL children and need the jolt-lang/nrepl
  ;; checkout. Run with `jolt test:nrepl`, not `jolt test`.
  '[jonquil.nrepl.child-test
    jonquil.nrepl.evaluator-test
    jonquil.nrepl.child-debugging-test
    jonquil.runtime-registry-test])

(def live-clj-namespaces
  ;; The :clj dialect's live suite: starts a real clojure -M:serve nREPL
  ;; server (dev/clj_server.clj) instead of a Jolt child. Run with `jolt
  ;; test:clj`, not `jolt test` or `jolt test:nrepl`: needs Java
  ;; 17+, the clojure CLI and, on a first run, the network.
  '[jonquil.live.clj-attach-test])

(def live-jank-namespaces
  ;; The :jank dialect's live suite: launches a real `jank repl` per test.
  ;; Run with `jolt test:jank`, not `jolt test`. JONQUIL_JANK picks the binary.
  '[jonquil.live.jank-live-test])

(def live-cljs-node-namespaces
  ;; The :cljs dialect's launch runtime against real ClojureScript on node.
  ;; Run with `jolt test:cljs-node`. Skips without the clojure CLI or node.
  '[jonquil.live.cljs-node-live-test])

(def live-bb-namespaces
  ;; The :nrepl dialect's live suite: starts a real `bb nrepl-server` per
  ;; test. Run with `jolt test:bb`, not `jolt test`. Skips without bb on PATH.
  '[jonquil.live.bb-nrepl-test])

(def live-plugins-namespaces
  ;; The isolated plugin check against real jolt checker children. Run with
  ;; `jolt test:plugins`, not `jolt test`. Skips without jolt on the login PATH.
  '[jonquil.live.plugin-isolated-live-test])

(def live-basilisp-namespaces
  ;; The :nrepl dialect against Basilisp: starts a real `basilisp
  ;; nrepl-server` per test. Run with `jolt test:basilisp`. Skips without
  ;; JONQUIL_BASILISP or a basilisp on PATH.
  '[jonquil.live.basilisp-nrepl-test])

(defn- on-path? [program]
  (some (fn [d] (.canExecute (java.io.File. ^String d ^String program)))
        (str/split (or (System/getenv "PATH") "") #":")))

(defn -main [& args]
  (when (and (= ["--live-bb"] args) (not (on-path? "bb")))
    (println "test:bb: no bb on PATH; skipping")
    (System/exit 0))
  (when (and (= ["--live-basilisp"] args)
             (not (if-let [b (System/getenv "JONQUIL_BASILISP")] (.canExecute (java.io.File. ^String b)) (on-path? "basilisp"))))
    (println "test:basilisp: no basilisp found (set JONQUIL_BASILISP or put basilisp on PATH); skipping")
    (System/exit 0))
  (when (and (= ["--live-jank"] args) (nil? (jank-child/find-binary)))
    (println "test:jank: no jank found (set JONQUIL_JANK or install jank); skipping")
    (System/exit 0))
  (when (and (= ["--live-plugins"] args) (nil? (isolated/jolt-program)))
    (println "test:plugins: no jolt on the login PATH; skipping")
    (System/exit 0))
  (when (= ["--live-cljs-node"] args)
    (when (nil? (cljs-child/find-clojure))
      (println "test:cljs-node: no clojure CLI found (set JONQUIL_CLOJURE or install clojure); skipping")
      (System/exit 0))
    (when (nil? (tool-path/resolve-program "node" (login-path/path) login-path/executable-file?))
      (println "test:cljs-node: no node on the login PATH; skipping")
      (System/exit 0)))
  (let [nss (cond (= ["--live"] args) live-namespaces
                  (= ["--live-clj"] args) live-clj-namespaces
                  (= ["--live-jank"] args) live-jank-namespaces
                  (= ["--live-cljs-node"] args) live-cljs-node-namespaces
                  (= ["--live-bb"] args) live-bb-namespaces
                  (= ["--live-basilisp"] args) live-basilisp-namespaces
                  (= ["--live-plugins"] args) live-plugins-namespaces
                  (seq args) (mapv symbol args)
                  :else test-namespaces)]
    (apply require nss)
    (let [{:keys [test pass fail error]} (apply t/run-tests nss)]
      (println (str "\nSUMMARY: " test " tests, " (+ pass fail error)
                    " assertions, " fail " failures, " error " errors"))
      (System/exit (if (zero? (+ fail error)) 0 1)))))
