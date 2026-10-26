(ns jonquil.plugin-checker-files-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.plugin-checker-files :as files]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(deftest embedded-text-is-the-source-test
  (doseq [[path text] files/sources]
    (is (= (slurp (io/resource path)) text) path)))

(defn- ns-path
  "jonquil.plugin-check -> jonquil/plugin_check.clj"
  [sym]
  (str (str/replace (str/replace (str sym) "-" "_") "." "/") ".clj"))

(defn- jonquil-requires
  "Every jonquil.* namespace in the :require of text's first form."
  [text]
  (let [ns-form (read-string text)
        reqs (rest (first (filter #(and (seq? %) (= :require (first %))) ns-form)))]
    (for [r reqs
          :let [sym (if (sequential? r) (first r) r)]
          :when (str/starts-with? (str sym) "jonquil.")]
      sym)))

(deftest embedded-requires-are-closed-test
  (is (contains? files/sources "jonquil/keymap.edn"))
  (is (contains? files/sources "jonquil/plugin_checker.clj"))
  (doseq [[path text] files/sources
          :when (str/ends-with? path ".clj")
          sym (jonquil-requires text)]
    (is (contains? files/sources (ns-path sym)) (str path " requires " sym ", which isn't embedded"))))

(deftest ensure-root-writes-the-checker-test
  (let [base (temp-dirs/temp-dir! "jonquil-checker-files-")
        root (files/ensure-root! base)]
    (is (= "{:paths [\"src\"]}\n" (slurp (io/file root "deps.edn"))))
    (doseq [[rel text] files/sources]
      (is (= text (slurp (io/file root "src" rel))) rel))
    (is (= root (files/ensure-root! base)))))

(deftest cache-base-test
  (is (= "/x/jonquil/plugin-checker" (files/cache-base {"XDG_CACHE_HOME" "/x"} "/h")))
  (is (= "/h/.cache/jonquil/plugin-checker" (files/cache-base {} "/h")))
  (is (= "/h/.cache/jonquil/plugin-checker" (files/cache-base {"XDG_CACHE_HOME" "rel"} "/h"))))
