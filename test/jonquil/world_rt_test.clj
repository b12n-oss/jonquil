(ns jonquil.world-rt-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jolt.image :as img]
            [jonquil.world-rt :as world-rt]))

(defn- temp-path []
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    "jonquil-world" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (str (.getCanonicalPath d) "/sub/project.world")))

(defn- fresh-ns!
  "A namespace named sym holding vars, a map of name -> value; any earlier
  one of that name is removed first."
  [sym vars]
  (remove-ns sym)
  (let [n (create-ns sym)]
    (doseq [[k v] vars] (intern n k v))
    (str sym)))

(deftest data-vars-test
  (let [n (fresh-ns! 'jonquil.world-rt-test.a {'counter (atom 1) 'rows [1 2] 'f (fn [x] x)})]
    (is (= #{"jonquil.world-rt-test.a/counter" "jonquil.world-rt-test.a/rows"}
           (set (map world-rt/var-name (world-rt/data-vars [n]))))
        "bound, non-fn vars only")
    (is (= [] (world-rt/data-vars ["no.such.namespace"])) "a namespace that isn't loaded")))

(deftest save-and-restore-test
  (let [n (fresh-ns! 'jonquil.world-rt-test.b {'counter (atom 41) 'rows [{:a 1}]})
        path (temp-path)
        saved (world-rt/op :save {:path path :namespaces [n]})]
    (is (= {:saved 2 :placeholders []} (select-keys saved [:saved :placeholders])))
    (is (.isFile (io/file path)) "the world's folder is made")
    (swap! @(ns-resolve (symbol n) 'counter) inc)
    (alter-var-root (ns-resolve (symbol n) 'rows) (constantly nil))
    (let [r (world-rt/op :restore {:path path})]
      (is (= 2 (:restored r)))
      (is (= 41 @@(ns-resolve (symbol n) 'counter)) "the saved data is back")
      (is (= [{:a 1}] @(ns-resolve (symbol n) 'rows)))
      (is (= [] (:placeholders r))))))

(deftest placeholders-test
  ;; An open file, which every Jolt with jolt.image writes as a
  ;; placeholder. A BufferedReader over a StringReader isn't one on Jolt
  ;; v0.8.23 (the Linux image's), which writes it as plain data.
  (let [f (doto (java.io.File/createTempFile "jonquil-world" ".txt") (spit "x"))
        reader (java.io.FileReader. f)]
    (try
      (let [n (fresh-ns! 'jonquil.world-rt-test.c {'reader reader 'n 1})
            path (temp-path)
            saved (world-rt/op :save {:path path :namespaces [n]})]
        (testing "a var that can only be a placeholder is named, and the world is still written"
          (is (= 2 (:saved saved)))
          (is (= ["jonquil.world-rt-test.c/reader"] (map :var (:placeholders saved))))
          (is (.isFile (io/file path))))
        (testing "the restore names it too"
          (is (= ["jonquil.world-rt-test.c/reader"]
                 (map :var (:placeholders (world-rt/op :restore {:path path})))))))
      (finally (.close reader) (.delete f)))))

(deftest unwritable-writes-nothing-test
  (let [n (fresh-ns! 'jonquil.world-rt-test.d {'x 1 'y 2})
        path (temp-path)
        real img/scan]
    (with-redefs [img/scan (fn [v] (if (= 2 v)
                                     [{:path "0" :object "#object[demo.Thing 0x1]" :disposition :unwritable}]
                                     (real v)))]
      (let [r (world-rt/op :save {:path path :namespaces [n]})]
        (is (= ["jonquil.world-rt-test.d/y"] (map :var (:unwritable r))))
        (is (string? (:error r)))
        (is (not (.exists (io/file path))) "nothing written")))))

(deftest restore-missing-test
  (is (= {:missing true} (world-rt/op :restore {:path (temp-path)}))))

(deftest child-namespaces-test
  (fresh-ns! 'demo.world-rt-later {'x 1})
  (let [ns-names (set (world-rt/child-namespaces))]
    (is (contains? ns-names "user"))
    (is (contains? ns-names "demo.world-rt-later") "a namespace loaded after world-rt's is the user's")
    (is (not (contains? ns-names "clojure.string")) "one loaded before it isn't")
    (is (not-any? #(re-find #"^(clojure|jolt|jonquil|nrepl)\." %) ns-names)
        "never the language's, Jonquil's or nREPL's own")))
