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
            [jonquil.tool-path :as tool-path]))

(def test-namespaces
  ;; Add each new test namespace here as tasks land.
  '[gtk-async-test
    jonquil.app-bundle-test
    jonquil.app-linux-test
    jonquil.smoke-test
    jonquil.upstream.syntax-test
    jonquil.theme-test
    jonquil.settings-test
    jonquil.paint-test
    jonquil.syntax-test
    jonquil.docs-test
    jonquil.edit-test
    jonquil.eval-test
    jonquil.registry-test
    jonquil.console-test
    jonquil.mcp-test
    jonquil.mcp.json-test
    jonquil.mcp.tools-test
    jonquil.mcp.window-test
    jonquil.mcp.listen-test
    jonquil.session-test
    jonquil.commands-test
    jonquil.keymap-test
    jonquil.command-registry-test
    jonquil.keymap-window-oracle-test
    jonquil.help-test
    jonquil.source-test
    jonquil.tabs-test
    jonquil.file-ops-test
    jonquil.last-session-test
    jonquil.file-tree-test
    jonquil.tool-path-test
    jonquil.save-path-test
    jonquil.login-path-test
    jonquil.main-test
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
    jonquil.nrepl.jvm-test
    jonquil.nrepl.shadow-test
    jonquil.nrepl.jank-eval-test
    jonquil.nrepl.cljs-node-eval-test
    jonquil.gtk.appearance-test
    jonquil.gtk.trash-test
    jonquil.gtk.ffi-test
    jonquil.gtk.editor-test
    jonquil.inspect.view-test
    jonquil.nrepl.info-middleware-test
    jonquil.project-roots-test
    jonquil.nrepl.inspect-middleware-test
    jonquil.nrepl.generic-test
    jonquil.nrepl.stdin-middleware-test
    jonquil.nrepl.middleware-files-test
    jonquil.nrepl.leftovers-test
    jonquil.nrepl.cljs-child-test
    jonquil.nrepl.cljs-node-files-test
    jonquil.nrepl.prepl-test
    jonquil.inspect-test])

(def live-namespaces
  ;; Tests that spawn real Jolt nREPL children and need the jolt-lang/nrepl
  ;; checkout. Run with `bb test:nrepl`, not `bb test`.
  '[jonquil.nrepl.child-test
    jonquil.nrepl.evaluator-test
    jonquil.runtime-registry-test])

(def live-clj-namespaces
  ;; The :clj dialect's live suite: starts a real clojure -M:serve nREPL
  ;; server (dev/clj_server.clj) instead of a Jolt child. Run with `bb
  ;; test:clj`, not `bb test` or `bb test:nrepl` (ruling 12): needs Java
  ;; 17+, the clojure CLI and, on a first run, the network.
  '[jonquil.live.clj-attach-test])

(def live-jank-namespaces
  ;; The :jank dialect's live suite: launches a real `jank repl` per test.
  ;; Run with `bb test:jank`, not `bb test`. JONQUIL_JANK picks the binary.
  '[jonquil.live.jank-live-test])

(def live-cljs-node-namespaces
  ;; The :cljs dialect's launch runtime against real ClojureScript on node.
  ;; Run with `bb test:cljs-node`. Skips without the clojure CLI or node.
  '[jonquil.live.cljs-node-live-test])

(def live-bb-namespaces
  ;; The :nrepl dialect's live suite: starts a real `bb nrepl-server` per
  ;; test. Run with `bb test:bb`, not `bb test`. Skips without bb on PATH.
  '[jonquil.live.bb-nrepl-test])

(def live-basilisp-namespaces
  ;; The :nrepl dialect against Basilisp: starts a real `basilisp
  ;; nrepl-server` per test. Run with `bb test:basilisp`. Skips without
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
                  (seq args) (mapv symbol args)
                  :else test-namespaces)]
    (apply require nss)
    (let [{:keys [test pass fail error]} (apply t/run-tests nss)]
      (println (str "\nSUMMARY: " test " tests, " (+ pass fail error)
                    " assertions, " fail " failures, " error " errors"))
      (System/exit (if (zero? (+ fail error)) 0 1)))))
