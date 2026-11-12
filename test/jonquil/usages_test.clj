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
    (is (= [:call] (mapv :how (usages/hits "(f 1)" "f")))))
  (testing "only a top-level ns form, never one in a comment block or discarded"
    (is (= [:ns :call] (mapv :how (usages/hits "(comment (ns scratch))\n(ns a (:require [x :refer [join]]))\n(join)" "join"))))
    (is (= [:ns] (mapv :how (usages/hits "#_(ns old)\n(ns a (:require [x :refer [join]]))" "join")))))
  (testing "a reader conditional's ns form counts, as every one in the file does"
    (is (= [:ns] (mapv :how (usages/hits "(ns a)\n#?(:clj (ns b (:require [x :refer [join]])))" "join"))))
    (is (= [:ns] (mapv :how (usages/hits "(ns a)\n#?@(:clj (ns b (:require [x :refer [join]])))" "join")))
        "a splicing reader conditional too"))
  (testing "an ns form nested in an ordinary list, or two deep, isn't an ns form"
    (is (= [:value] (mapv :how (usages/hits "(foo (ns b (:require [x :refer [join]])))" "join"))))
    (is (= [:value] (mapv :how (usages/hits "#?(:clj [(ns b (:require [x :refer [join]]))])" "join")))))
  (testing "a stray closer, and a list or vector whose head is not ns, change nothing"
    (is (= [:ns :call] (mapv :how (usages/hits ") (ns a (:require [x :refer [join]])) (join)" "join"))))
    (is (= [:value] (mapv :how (usages/hits "[ns a [x join]]" "join"))))))

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

(deftest project-files-symlink-loop-test
  (let [root (temp-project {"y.clj" "(y)" "sub/x.clj" "(x)"})
        link (java.nio.file.Paths/get (str root "/sub/loop") (make-array String 0))
        target (java.nio.file.Paths/get root (make-array String 0))]
    (java.nio.file.Files/createSymbolicLink link target (make-array java.nio.file.attribute.FileAttribute 0))
    (is (= 2 (count (usages/project-files root))) "a link back up the tree is walked once")))

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
    (is (= {a (hash (slurp a))} (select-keys (:hashes r) [a]))
        "each matched file's text is hashed, to check its offsets still fit later")
    (is (= 2 (count (:hashes r))) "matched files only")
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

(deftest scan-resolved-test
  (let [root (temp-project
              {"src/my/text.clj" "(ns my.text)\n(defn join [xs] xs)\n(join [1])\n"
               "src/app/a.clj" (str "(ns app.a (:require [clojure.string :as str] [my.text :as t]))\n"
                                    "(str/join [1]) (t/join [2]) (let [join inc] (join 3))\n")
               "src/app/b.clj" "(ns app.b (:require [my.text :refer [join]]))\n(join [4])\n"
               "src/app/c.clj" "(ns app.c (:use my.text))\n(join [5])\n"
               "src/app/d.cljc" "(ns app.d (:require #?(:clj [my.text :as z] :cljs [other :as z])))\n(z/join 6)\n"})
        rel (fn [r] (mapv (fn [{:keys [path text match]}] [(subs path (inc (count root))) text match])
                          (:entries r)))
        target {:var 'my.text/join}]
    (testing "each hit says whether it names the target var"
      (is (= [["src/app/a.clj" "(str/join [1]) (t/join [2]) (let [join inc] (join 3))" :other]
              ["src/app/a.clj" "(str/join [1]) (t/join [2]) (let [join inc] (join 3))" :this]
              ["src/app/a.clj" "(str/join [1]) (t/join [2]) (let [join inc] (join 3))" :other]
              ["src/app/a.clj" "(str/join [1]) (t/join [2]) (let [join inc] (join 3))" :other]
              ["src/app/b.clj" "(ns app.b (:require [my.text :refer [join]]))" :this]
              ["src/app/b.clj" "(join [4])" :this]
              ["src/app/c.clj" "(join [5])" :unresolved]
              ["src/app/d.cljc" "(z/join 6)" :this]
              ["src/my/text.clj" "(defn join [xs] xs)" :this]
              ["src/my/text.clj" "(join [1])" :this]]
             (rel (usages/scan {:root root :name "join" :target target :features #{:jolt :clj}})))))
    (testing "the cljc alias follows the window's features"
      (is (= :other (->> (usages/scan {:root root :name "join" :target target :features #{:cljs}})
                         :entries (filter #(str/ends-with? (:path %) "d.cljc")) first :match))))
    (testing "a target that isn't a var: every hit is :this, as a by-name search"
      (is (= #{:this} (set (map :match (:entries (usages/scan {:root root :name "join" :target {:local true}})))))))
    (is (= target (:target (usages/scan {:root root :name "join" :target target}))))))
