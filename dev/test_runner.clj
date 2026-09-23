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
    jonquil.paint-test])

(defn -main [& args]
  (let [nss (if (seq args) (mapv symbol args) test-namespaces)]
    (apply require nss)
    (let [{:keys [test pass fail error]} (apply t/run-tests nss)]
      (println (str "\nSUMMARY: " test " tests, " (+ pass fail error)
                    " assertions, " fail " failures, " error " errors"))
      (System/exit (if (zero? (+ fail error)) 0 1)))))
