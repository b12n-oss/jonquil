(ns jonquil.registry-test
  "Every namespace on disk is registered with bb check and bb test. Both
  lists are hand-maintained, and a namespace missing from them is skipped
  silently."
  (:require [check]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [test-runner]))

(defn- ns-names-under
  "Namespace symbols for every .clj file under dir, derived from the path."
  [dir]
  (->> (file-seq (java.io.File. dir))
       (map str)
       (filter #(str/ends-with? % ".clj"))
       (map #(-> %
                 (subs (inc (count dir)))
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       set))

(def ^:private jvm-only
  "Source files run only inside a JVM child, never loaded by Jolt, so bb check
  can't load them."
  '#{jonquil.cljs-node-launch})

(deftest every-src-namespace-is-checked
  (is (= (apply disj (ns-names-under "src") jvm-only) (set check/namespaces))))

(deftest every-test-namespace-is-run
  (is (= (ns-names-under "test")
         (into (set test-runner/test-namespaces)
               (concat test-runner/live-namespaces test-runner/live-clj-namespaces
                       test-runner/live-cljs-node-namespaces
                       test-runner/live-jank-namespaces
                       test-runner/live-bb-namespaces test-runner/live-basilisp-namespaces)))))
