(ns jonquil.nrepl.cljs-node-files-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.nrepl.cljs-node-files :as cljs-node-files]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(deftest launcher-text-is-the-source-test
  (is (str/includes? cljs-node-files/launcher-text "(ns jonquil.cljs-node-launch"))
  (is (str/includes? cljs-node-files/launcher-text ".listen(0, \\\"127.0.0.1\\\", function () {"))
  (is (str/includes? cljs-node-files/launcher-text "cljs.repl.node/create-socket"))
  (is (str/includes? cljs-node-files/launcher-text "p=clojure.browser.repl.repl_print"))
  (is (str/includes? cljs-node-files/launcher-text "no-console"))
  (is (str/includes? cljs-node-files/launcher-text ":jonquil/ping"))
  (is (str/includes? cljs-node-files/launcher-text "jonquil-gen:"))
  (is (str/includes? cljs-node-files/launcher-text "location.reload")))

(deftest ensure-launcher-writes-once-test
  (let [base (str (temp-dirs/temp-dir! "jonquil-cljs-node-files-"))
        p1 (cljs-node-files/ensure-launcher! base)
        f (io/file p1)]
    (is (.isAbsolute f))
    (is (= cljs-node-files/launcher-text (slurp f)))
    (.setLastModified f 1000)
    (is (= p1 (cljs-node-files/ensure-launcher! base)))
    (is (= 1000 (.lastModified f)) "an identical file isn't rewritten")
    (spit f "stale")
    (cljs-node-files/ensure-launcher! base)
    (is (= cljs-node-files/launcher-text (slurp f)) "a changed file is replaced")))

(deftest cache-base-test
  (is (= "/x/jonquil/cljs-node" (cljs-node-files/cache-base {"XDG_CACHE_HOME" "/x"} "/h")))
  (is (= "/h/.cache/jonquil/cljs-node" (cljs-node-files/cache-base {"XDG_CACHE_HOME" "rel"} "/h"))))
