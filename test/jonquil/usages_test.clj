(ns jonquil.usages-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.usages :as usages]))

(def ^:private src
  (str "(ns demo.a\n"
       "  (:require [clojure.string :as str :refer [join]]))\n"
       "(defn join [xs] xs)\n"
       "(join [1])\n"
       "(str/join \",\" [2])\n"
       "(map join xs)\n"
       "#(clojure.string/join %)\n"
       "\"join\" :join ; join\n"
       "(joint 1) (join-all 2)\n"))

(defn- at [text {:keys [start end]}] (subs text start end))

(deftest hits-test
  (let [hs (usages/hits src "join")]
    (testing "every use, in order, with how it's used"
      (is (= [["join" :ns] ["join" :definition] ["join" :call] ["str/join" :call]
              ["join" :value] ["clojure.string/join" :call]]
             (mapv (juxt #(at src %) :how) hs))))
    (testing "strings, keywords, comments and longer names don't count"
      (is (= 6 (count hs)))))
  (testing "a name that isn't there"
    (is (= [] (usages/hits src "nothing-like-it"))))
  (testing "a qualified search name searches by its name part"
    (is (= (usages/hits src "join") (usages/hits src "str/join"))))
  (testing "no ns form: nothing is :ns"
    (is (= [:call] (mapv :how (usages/hits "(f 1)" "f"))))))

(defn- temp-project
  "A temp dir holding files, a map of relative path -> text."
  [files]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "jonquil-usages" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (doseq [[rel text] files]
      (let [f (io/file root rel)]
        (io/make-parents f)
        (spit f text)))
    (.getCanonicalPath root)))

(deftest project-files-test
  (let [root (temp-project {"src/a.clj" "(a)" "src/b.cljc" "(b)" "README.md" "join"
                            "target/c.clj" "(c)" ".git/d.clj" "(d)"
                            "gen/e.clj" "(e)" ".gitignore" "gen/\n"})
        rels (set (map #(subs % (inc (count root))) (usages/project-files root)))]
    (is (= #{"src/a.clj" "src/b.cljc"} rels)
        "Clojure files only, never target, a dot folder or what .gitignore names")))

(deftest scan-test
  (let [root (temp-project {"src/a.clj" "(ns a)\n(join 1)\n" "src/b.clj" "(ns b)\n(x)\n(map join y)\n"
                            "src/c.clj" "(nothing)\n"})
        a (str root "/src/a.clj")
        r (usages/scan {:root root :name "join"})]
    (testing "entries in path order, each with its file, line, text and how"
      (is (= [["src/a.clj" 2 "(join 1)" :call] ["src/b.clj" 3 "(map join y)" :value]]
             (mapv (fn [{:keys [path line text how]}] [(subs path (inc (count root))) line text how])
                   (:entries r)))))
    (is (= 3 (:files r)) "every Clojure file was looked at")
    (is (= 2 (:matched r)) "and two had a use")
    (testing "an open tab's text replaces the file's"
      (let [r (usages/scan {:root root :name "join" :texts {a "(ns a)\n\n\n(f (join 2))\n"}})
            e (first (:entries r))]
        (is (= [4 "(f (join 2))" :call] [(:line e) (:text e) (:how e)]))))))

(deftest scan-offsets-are-characters-test
  (testing "past an emoji, offsets are characters, as GTK counts them"
    (let [root (temp-project {"a.clj" "; 😀\n(join 1)\n"})
          e (first (:entries (usages/scan {:root root :name "join"})))]
      (is (= [5 9] [(:start e) (:end e)]))
      (is (str/includes? (:text e) "(join 1)")))))
