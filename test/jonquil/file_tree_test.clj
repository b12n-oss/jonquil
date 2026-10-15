(ns jonquil.file-tree-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.file-tree :as ft]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- canon [p] (.getCanonicalPath (io/file p)))

(defn- tree!
  "A fresh temp dir holding entries: a string value is a file's text, nil
  makes a folder. Its canonical path."
  [entries]
  (let [root (temp-dirs/temp-dir! "jonquil-file-tree-test-")]
    (doseq [[rel text] entries]
      (let [f (io/file root rel)]
        (if (nil? text)
          (.mkdirs f)
          (do (io/make-parents f) (spit f text)))))
    (canon root)))

(def ^:private sample
  {"src/a.clj" "" "src/b/c.cljc" "" "README.md" "" ".git/x" "" "target/y" ""
   "node_modules/z" "" "out.log" "" "notes" nil ".gitignore" "*.log\n"})

(deftest gitignore-test
  (let [rules (ft/parse-gitignore "# c\n\n*.log\nclasses/\n!keep.log\n**/out\nsrc/gen/\nout\n")]
    (is (ft/ignored? rules "x.log" false))
    (is (ft/ignored? rules "classes" true))
    (is (not (ft/ignored? rules "classes" false)) "a dir/ rule is folders only")
    (is (ft/ignored? rules "keep.log" false) "the ! line is skipped; *.log still applies")
    (is (not (ft/ignored? rules "gen" true)) "an inner-slash line is skipped")
    (is (ft/ignored? rules "out" false))
    (is (not (ft/ignored? rules "src" true)))))

(deftest always-hidden-test
  (let [rules (ft/parse-gitignore "")]
    (is (ft/ignored? rules ".git" true))
    (is (ft/ignored? rules ".gitignore" false))
    (is (ft/ignored? rules "target" true))
    (is (ft/ignored? rules "node_modules" true))
    (is (not (ft/ignored? rules "target" false)) "only a folder named target")))

(deftest children-test
  (let [root (tree! sample)
        m (ft/load-model root)]
    (is (= ["notes" "src" "README.md"] (mapv :name (ft/children m root))))
    (is (= [true true false] (mapv :dir? (ft/children m root))))))

(deftest rows-test
  (let [root (tree! sample)
        m (ft/toggle (ft/load-model root) (str root "/src"))]
    (is (= [["notes" 0 false] ["src" 0 false] ["b" 1 false] ["a.clj" 1 false] ["README.md" 0 true]]
           (mapv (juxt :name :depth :dim?) (ft/rows m))))
    (is (:expanded? (second (ft/rows m))))
    (testing "toggling again collapses"
      (is (= ["notes" "src" "README.md"] (mapv :name (ft/rows (ft/toggle m (str root "/src")))))))))

(deftest rows-deleted-folder-test
  (let [root (tree! sample)
        b (str root "/src/b")
        m (-> (ft/load-model root) (ft/toggle (str root "/src")) (ft/toggle b))]
    (doseq [f (reverse (file-seq (io/file b)))] (io/delete-file f))
    (is (not-any? #(= "b" (:name %)) (ft/rows m)))
    (is (not-any? :error? (ft/rows m)))
    (is (not (contains? (:expanded (ft/prune m)) b)))))

(deftest rows-unreadable-test
  (let [root (tree! {"locked/x.clj" ""})
        locked (io/file root "locked")
        m (ft/toggle (ft/load-model root) (str root "/locked"))]
    (.setReadable locked false false)
    (try
      (let [rs (ft/rows m)]
        (is (= [["locked" 0] ["(can't read)" 1]] (mapv (juxt :name :depth) rs)))
        (is (:error? (second rs))))
      (finally (.setReadable locked true false)))))

(deftest expand-to-test
  (let [root (tree! sample)
        m (ft/expand-to (ft/load-model root) (str root "/src/b/c.cljc"))]
    (is (= #{(str root "/src") (str root "/src/b")} (:expanded m)))
    (is (= #{} (:expanded (ft/expand-to (ft/load-model root) "/elsewhere/x.clj"))))))

(deftest under-root-test
  (let [root (tree! sample)
        m (ft/load-model root)]
    (is (ft/under-root? m (str root "/src/a.clj")))
    (is (not (ft/under-root? m "/elsewhere/x.clj")))
    (is (not (ft/under-root? (ft/load-model nil) (str root "/src/a.clj"))))))

(deftest root-for-test
  (let [root (tree! {"proj/deps.edn" "{}" "proj/src/x.clj" "" "loose/y.clj" ""})]
    (is (= (str root "/proj") (ft/root-for {:dir (str root "/proj")})))
    (is (= (str root "/proj") (ft/root-for {:file (str root "/proj/src/x.clj")})))
    (is (= (str root "/loose") (ft/root-for {:file (str root "/loose/y.clj")})))
    (is (nil? (ft/root-for {:cwd "/"})))
    (is (nil? (ft/root-for {:cwd ""})))
    (is (= "/tmp/x" (ft/root-for {:cwd "/tmp/x"})))))

(deftest clojure-file-test
  (is (every? ft/clojure-file? ["a.clj" "b.cljc" "c.cljs" "d.cljd" "e.edn" "f.bb"]))
  (is (not-any? ft/clojure-file? ["README.md" "x.txt" "Makefile"])))

(deftest symlink-folder-test
  (let [root (tree! {"src/a.clj" ""})
        link (io/file root "linked")]
    (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath (io/file root "src"))
                                            (make-array java.nio.file.attribute.FileAttribute 0))
    (let [m (ft/load-model root)
          linked (some #(when (= "linked" (:name %)) %) (ft/children m root))]
      (is (:dir? linked))
      (is (= (str root "/src/a.clj") (:path (first (ft/children m (:path linked)))))))))

(deftest symlink-cycle-test
  ;; Final review finding 2: a link to the folder itself (or an ancestor)
  ;; has the same canonical path as an expanded folder; rows must not
  ;; recurse forever.
  (let [root (tree! {"a/x.clj" ""})
        a (str root "/a")]
    (java.nio.file.Files/createSymbolicLink (.toPath (io/file a "self")) (.toPath (io/file "."))
                                            (make-array java.nio.file.attribute.FileAttribute 0))
    (java.nio.file.Files/createSymbolicLink (.toPath (io/file a "up")) (.toPath (io/file ".."))
                                            (make-array java.nio.file.attribute.FileAttribute 0))
    (let [m (-> (ft/load-model root) (ft/toggle a) (ft/toggle root))
          rs (deref (future (ft/rows m)) 3000 ::hung)]
      (is (not= ::hung rs))
      (is (= ["a" "self" "up" "x.clj"] (mapv :name rs)))
      (is (not-any? :expanded? (rest rs)) "a cycle's row is shown collapsed"))))

(deftest project-selection-test
  ;; Final review finding 1: a directory's own marker decides its dialect.
  (let [cljd (tree! {"pubspec.yaml" "name: x\n"})
        jolt (tree! {"deps.edn" "{}"})]
    (is (= :cljd (get-in (ft/project-selection cljd nil) [:profile :id])))
    (is (= cljd (:project-dir (ft/project-selection cljd nil))))
    (is (= :jolt (get-in (ft/project-selection jolt nil) [:profile :id])))
    (is (= jolt (:project-dir (ft/project-selection jolt nil))))))

(deftest listed-symlink-test
  ;; File-ops fix round 1, Critical 1: :path is the canonical target, :listed
  ;; the entry itself, for a symlinked file and a symlinked folder.
  (let [root (tree! {"proj/a.clj" "" "else/notes.txt" "n" "else/vd/v.clj" ""})
        proj (str root "/proj")
        link! (fn [n target]
                (java.nio.file.Files/createSymbolicLink (.toPath (io/file proj n)) (.toPath (io/file target))
                                                        (make-array java.nio.file.attribute.FileAttribute 0)))]
    (link! "notes-link.txt" "../else/notes.txt")
    (link! "vendor" "../else/vd")
    (let [m (ft/load-model proj)
          by-name (into {} (map (juxt :name identity)) (ft/children m proj))
          rs (into {} (map (juxt :name identity)) (ft/rows m))]
      (is (= (str root "/else/notes.txt") (:path (by-name "notes-link.txt"))))
      (is (= (str proj "/notes-link.txt") (:listed (by-name "notes-link.txt"))))
      (is (= (str root "/else/vd") (:path (by-name "vendor"))))
      (is (= (str proj "/vendor") (:listed (by-name "vendor"))))
      (is (= (str proj "/a.clj") (:path (by-name "a.clj")) (:listed (by-name "a.clj"))))
      (is (= (str proj "/vendor") (:listed (rs "vendor"))) "rows carry :listed too")
      (is (= (str proj "/notes-link.txt") (:listed (rs "notes-link.txt")))))))
