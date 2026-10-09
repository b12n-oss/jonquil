(ns test-runner
  "Run the project's test namespaces under Jolt and exit with a status.

  With no arguments every namespace in `test-namespaces` runs. With arguments,
  only the named ones do, which is the focused loop during TDD. The exit is
  explicit because a Jolt process with live threads may not exit on its own."
  (:require [clojure.test :as t]))

(def test-namespaces
  ;; Add each new test namespace here as tasks land.
  '[jonquil.smoke-test
    jonquil.upstream.syntax-test
    jonquil.theme-test
    jonquil.settings-test
    jonquil.paint-test
    jonquil.docs-test
    jonquil.edit-test
    jonquil.eval-test
    jonquil.registry-test
    jonquil.console-test
    jonquil.session-test
    jonquil.commands-test
    jonquil.help-test
    jonquil.source-test
    jonquil.tabs-test
    jonquil.dialect-test
    jonquil.evaluator-test
    jonquil.nrepl.bencode-test
    jonquil.nrepl.client-test
    jonquil.nrepl.connection-test
    jonquil.nrepl.ops-test
    jonquil.cljd-test
    jonquil.clj-test
    jonquil.fake-cljd-nrepl-test
    jonquil.fake-jvm-nrepl-test
    jonquil.nrepl.attach-test
    jonquil.nrepl.jvm-test
    jonquil.gtk.ffi-test
    jonquil.inspect.view-test
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

(defn -main [& args]
  (let [nss (cond (= ["--live"] args) live-namespaces
                  (= ["--live-clj"] args) live-clj-namespaces
                  (seq args) (mapv symbol args)
                  :else test-namespaces)]
    (apply require nss)
    (let [{:keys [test pass fail error]} (apply t/run-tests nss)]
      (println (str "\nSUMMARY: " test " tests, " (+ pass fail error)
                    " assertions, " fail " failures, " error " errors"))
      (System/exit (if (zero? (+ fail error)) 0 1)))))
