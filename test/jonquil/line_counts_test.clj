(ns jonquil.line-counts-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jonquil.line-counts :as lc]))

(deftest label-test
  (is (= ["0" "999" "1.0k" "1.9k" "12k" "999k" "1.2M"]
         (mapv lc/label [0 999 1000 1999 12345 999999 1200000]))))

(defn- temp-dir []
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "jonquil-lc-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(deftest for-file-test
  (let [d (temp-dir)
        f (io/file d "a.clj")
        _ (spit f "x")
        p (.getPath f)
        counts [{:file p :line 2 :hits 3} {:file p :line 2 :hits 4} {:file p :line 5 :hits 1}
                {:file "/elsewhere/b.clj" :line 2 :hits 9} {:file nil :line 1 :hits 2}]]
    (is (= {2 7 5 1} (lc/for-file counts p)))
    (is (= [2 5] (keys (lc/for-file counts p))) "sorted by line")
    (let [link (io/file (str (.getPath d) "-link"))]
      (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath d)
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (is (= {2 7 5 1} (lc/for-file counts (.getPath (io/file link "a.clj"))))
          "the same file through a symlinked folder"))))

(deftest live-test
  (if-not (lc/available?)
    (println "SKIP live-test: this Jolt has no jolt.profile")
    (let [mode ((requiring-resolve 'jolt.profile/instrument-mode))
          f (io/file (temp-dir) "loop.clj")]
      (spit f "(ns jonquil.lc-probe)\n(defn spin [n]\n  (loop [i 0]\n    (when (< i n)\n      (recur (inc i)))))\n(spin 5)\n")
      (try
        (is (= {:on? true} (lc/set-counting! true)))
        (load-file (.getPath f))
        (let [{:keys [counts mode]} (lc/fetch (.getPath f))]
          (is (= :counts mode) "a fetch says the mode it found")
          (is (< 1 (get counts 4 0)) "the loop's test ran more than once")
          (is (= 1 (get counts 6)) "the call ran once"))
        (is (= {:ok true} (lc/clear!)))
        (is (= {} (:counts (lc/fetch (.getPath f)))))
        (is (= {:on? false} (lc/toggle!)))
        (testing "off-if-counting! turns counting off, and leaves other modes alone"
          (lc/set-counting! true)
          (is (= {:on? false} (lc/off-if-counting!)))
          (is (nil? ((requiring-resolve 'jolt.profile/instrument-mode))))
          (is (nil? (lc/off-if-counting!)) "nothing to do"))
        (finally ((requiring-resolve 'jolt.profile/instrument!) (or mode false)))))))
