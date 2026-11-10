(ns jonquil.gtk.window-layers-test
  "jonquil.gtk.window's own namespaces load in layers: each requires only
  namespaces of an earlier layer, none requires jonquil.gtk.window, and
  every file under src/jonquil/gtk/window/ is in a layer."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private layers
  "In load order: a namespace may require only those of earlier layers."
  '[[jonquil.gtk.window.status jonquil.gtk.window.alerts jonquil.gtk.window.plugin-agent jonquil.gtk.window.session]
    [jonquil.gtk.window.files jonquil.gtk.window.right-slot jonquil.gtk.window.theme jonquil.gtk.window.keys jonquil.gtk.window.status-bar]
    [jonquil.gtk.window.runtime jonquil.gtk.window.quit jonquil.gtk.window.layout]
    [jonquil.gtk.window.run]
    [jonquil.gtk.window.inspector jonquil.gtk.window.help]
    [jonquil.gtk.window.tabs jonquil.gtk.window.console]
    [jonquil.gtk.window.reopen jonquil.gtk.window.tree-ops jonquil.gtk.window.context]
    [jonquil.gtk.window.startup]])

(defn- ns-file [sym]
  (str "src/" (str/replace (str/replace (str sym) "-" "_") "." "/") ".clj"))

(defn- window-requires
  "The jonquil.gtk.window namespaces, itself included, in the :require of
  sym's ns form."
  [sym]
  (let [ns-form (read-string (slurp (ns-file sym)))
        reqs (rest (first (filter #(and (seq? %) (= :require (first %))) ns-form)))]
    (set (for [r reqs
               :let [s (if (sequential? r) (first r) r)]
               :when (or (= 'jonquil.gtk.window s)
                         (str/starts-with? (str s) "jonquil.gtk.window."))]
           s))))

(deftest window-namespaces-load-in-layers-test
  (doseq [[i layer] (map-indexed vector layers)
          :let [earlier (set (apply concat (take i layers)))]
          sym layer]
    (is (empty? (remove earlier (window-requires sym)))
        (str sym " requires a window namespace outside an earlier layer"))))

(deftest every-window-namespace-is-in-a-layer-test
  (is (= (set (apply concat layers))
         (set (for [f (or (.listFiles (io/file "src/jonquil/gtk/window")) [])
                    :let [n (.getName ^java.io.File f)]
                    :when (str/ends-with? n ".clj")]
                (symbol (str "jonquil.gtk.window."
                             (str/replace (subs n 0 (- (count n) 4)) "_" "-"))))))))
