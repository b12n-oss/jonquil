(ns jonquil.nrepl.inspect-files-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.nrepl.inspect-files :as files]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(deftest the-embedded-text-is-the-source
  (is (= (slurp "src/jonquil/inspect/view.clj") (get files/sources "jonquil/inspect/view.clj")))
  (is (= (slurp "src/jonquil/nrepl/inspect_middleware.clj")
         (get files/sources "jonquil/nrepl/inspect_middleware.clj"))))

(deftest ensure-root-writes-a-loadable-folder-once
  (let [base (temp-dirs/temp-dir! "jonquil-inspect-files-")
        root (files/ensure-root! base)]
    (is (some? root))
    (is (str/starts-with? root (.getCanonicalPath (io/file base))))
    (is (= "{:paths [\"src\"]}\n" (slurp (io/file root "deps.edn"))))
    (doseq [[rel text] files/sources]
      (is (= text (slurp (io/file root "src" rel))) rel))
    (let [stamp (.lastModified (io/file root "src/jonquil/inspect/view.clj"))]
      (Thread/sleep 1100)
      (is (= root (files/ensure-root! base)) "the same folder again")
      (is (= stamp (.lastModified (io/file root "src/jonquil/inspect/view.clj"))) "not rewritten"))
    (is (= [(.getName (io/file root))] (vec (.list (io/file base)))) "no temp folder left behind")))

(deftest a-folder-that-cant-be-written-throws
  (let [f (io/file (temp-dirs/temp-dir! "jonquil-inspect-files-") "a-file")]
    (spit f "not a folder")
    (is (thrown? Throwable (files/ensure-root! (.getPath f))))))

(deftest a-complete-folder-from-another-window-is-kept
  ;; The race: this window's first look finds nothing, another window's
  ;; rename lands, then this window's rename fails onto it.
  (let [base (temp-dirs/temp-dir! "jonquil-inspect-files-")
        root (files/ensure-root! base)
        marker (io/file root "kept-marker")
        real @#'files/complete?
        looks (atom 0)]
    (spit marker "x")
    (with-redefs [files/complete? (fn [d] (if (= 1 (swap! looks inc)) false (real d)))]
      (is (= root (files/ensure-root! base))))
    (is (< 1 @looks) "it looked again after the failed rename")
    (is (.exists marker) "the winner's folder was neither deleted nor rewritten")
    (is (= [(.getName (io/file root))] (vec (.list (io/file base)))) "its own temp copy is gone")))

(deftest an-incomplete-folder-is-replaced
  (let [base (temp-dirs/temp-dir! "jonquil-inspect-files-")
        root (files/ensure-root! base)]
    (spit (io/file root "src/jonquil/inspect/view.clj") "(ns half")
    (is (= root (files/ensure-root! base)))
    (is (= (get files/sources "jonquil/inspect/view.clj") (slurp (io/file root "src/jonquil/inspect/view.clj"))))
    (is (= [(.getName (io/file root))] (vec (.list (io/file base)))) "nothing left aside")))

(deftest cache-base-follows-xdg
  (is (= "/x/cache/jonquil/nrepl-inspect" (files/cache-base {"XDG_CACHE_HOME" "/x/cache"} "/home/u")))
  (is (= "/home/u/.cache/jonquil/nrepl-inspect" (files/cache-base {} "/home/u")))
  (is (= "/home/u/.cache/jonquil/nrepl-inspect" (files/cache-base {"XDG_CACHE_HOME" "relative"} "/home/u"))
      "a relative XDG path is invalid, as settings treats one"))
