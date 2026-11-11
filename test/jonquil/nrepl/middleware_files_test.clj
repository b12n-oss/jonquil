(ns jonquil.nrepl.middleware-files-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.nrepl.middleware-files :as files]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(deftest the-embedded-text-is-the-source
  (is (= (slurp "src/jonquil/inspect/view.clj") (get files/sources "jonquil/inspect/view.clj")))
  (is (= (slurp "src/jonquil/nrepl/inspect_middleware.clj")
         (get files/sources "jonquil/nrepl/inspect_middleware.clj"))))

(deftest ensure-root-writes-a-loadable-folder-once
  (let [base (temp-dirs/temp-dir! "jonquil-middleware-files-")
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
  (let [f (io/file (temp-dirs/temp-dir! "jonquil-middleware-files-") "a-file")]
    (spit f "not a folder")
    (is (thrown? Throwable (files/ensure-root! (.getPath f))))))

(deftest a-complete-folder-from-another-window-is-kept
  ;; The race: this window's first look finds nothing, another window's
  ;; rename lands, then this window's rename fails onto it.
  (let [base (temp-dirs/temp-dir! "jonquil-middleware-files-")
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
  (let [base (temp-dirs/temp-dir! "jonquil-middleware-files-")
        root (files/ensure-root! base)]
    (spit (io/file root "src/jonquil/inspect/view.clj") "(ns half")
    (is (= root (files/ensure-root! base)))
    (is (= (get files/sources "jonquil/inspect/view.clj") (slurp (io/file root "src/jonquil/inspect/view.clj"))))
    (is (= [(.getName (io/file root))] (vec (.list (io/file base)))) "nothing left aside")))

(deftest old-versions-folders-are-pruned
  (let [base (temp-dirs/temp-dir! "jonquil-middleware-files-")
        mk (fn [n age-ms] (let [d (io/file base n)]
                            (.mkdirs (io/file d "src"))
                            (spit (io/file d "deps.edn") "{}")
                            (.setLastModified d (- (System/currentTimeMillis) age-ms))
                            d))
        old (mk "deadbeef" (* 2 files/prune-age-ms))
        recent (mk "cafebabe" 1000)
        in-flight (mk "deadbeef.tmp-123" (* 2 files/prune-age-ms))
        other (mk "not-a-hash" (* 2 files/prune-age-ms))
        target (let [d (io/file (temp-dirs/temp-dir! "jonquil-middleware-files-") "keep")]
                 (.mkdirs d) (spit (io/file d "precious") "x") d)
        link (let [l (io/file base "0badf00d")]
               (java.nio.file.Files/createSymbolicLink (.toPath l) (.toPath target)
                                                       (make-array java.nio.file.attribute.FileAttribute 0))
               l)
        root (files/ensure-root! base)]
    (is (not (.exists old)) "another version's folder, unused for two days")
    (is (.exists recent) "used a second ago: kept")
    (is (.exists in-flight) "a temp folder: never ours to take")
    (is (.exists other) "not a hash: not ours")
    (is (.exists (io/file target "precious")) "a hash-named symlink's target is never touched")
    (is (.exists link))
    (is (.exists (io/file root)))))

(deftest a-folder-in-use-is-marked-used
  (let [base (temp-dirs/temp-dir! "jonquil-middleware-files-")
        root (io/file (files/ensure-root! base))]
    (.setLastModified root (- (System/currentTimeMillis) (* 2 files/prune-age-ms)))
    (files/ensure-root! base)
    (is (< (- (System/currentTimeMillis) (.lastModified root)) 60000) "touched, so no other Jonquil prunes it")))

(deftest cache-base-follows-xdg
  (is (= "/x/cache/jonquil/nrepl-middleware" (files/cache-base {"XDG_CACHE_HOME" "/x/cache"} "/home/u")))
  (is (= "/home/u/.cache/jonquil/nrepl-middleware" (files/cache-base {} "/home/u")))
  (is (= "/home/u/.cache/jonquil/nrepl-middleware" (files/cache-base {"XDG_CACHE_HOME" "relative"} "/home/u"))
      "a relative XDG path is invalid, as settings treats one"))

(deftest every-jonquil-require-is-embedded
  ;; The child loads only these files, so a jonquil namespace one of them
  ;; requires has to be here too, or the child's load fails.
  (let [ns-file (fn [sym] (str (-> (name sym) (str/replace "-" "_") (str/replace "." "/")) ".clj"))
        required (for [[_ text] files/sources
                       :let [form (read-string text)]
                       clause (rest form)
                       :when (and (seq? clause) (= :require (first clause)))
                       spec (rest clause)
                       :let [sym (if (vector? spec) (first spec) spec)]
                       :when (and (symbol? sym) (str/starts-with? (name sym) "jonquil."))]
                   (ns-file sym))]
    (is (seq required) "the files do require jonquil namespaces")
    (is (empty? (remove (set (keys files/sources)) required))
        (str "required but not embedded: " (vec (distinct (remove (set (keys files/sources)) required)))))))
