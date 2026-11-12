(ns jonquil.file-refs-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.file-refs :as fr]))

(defn- at
  "file-refs/ref-at for the column where needle starts in line."
  [line needle]
  (fr/ref-at line (.indexOf ^String line ^String needle)))

(deftest ref-at-test
  (testing "the shapes runtimes print"
    (is (= {:file "src/demo/core.clj" :line 12 :col 3}
           (select-keys (at "Location: src/demo/core.clj:12:3" "core") [:file :line :col])))
    (is (= {:file "core.clj" :line 12}
           (select-keys (at "    at demo.core$f.invoke (core.clj:12)" "core.clj") [:file :line])))
    (is (= {:file "/tmp/p/a.cljc" :line 4}
           (select-keys (at "Syntax error compiling at (/tmp/p/a.cljc:4:1)." "/tmp") [:file :line]))))
  (testing "the whole reference is the span, file through line and column"
    (let [line "x src/a.clj:7:2 y"
          r (at line "a.clj")]
      (is (= "src/a.clj:7:2" (subs line (:start r) (:end r))))))
  (testing "a column on the reference's line number counts too"
    (is (= 7 (:line (at "x src/a.clj:7 y" ":7")))))
  (testing "nothing outside a reference, and no reference without a line"
    (is (nil? (at "x src/a.clj:7 y" "x")))
    (is (nil? (at "see src/a.clj for that" "src")))
    (is (nil? (at "notes.txt:3" "notes")) "only Clojure-family files")))

(deftest ns-hint-test
  (is (= "demo/core" (fr/ns-hint "    at demo.core$f.invoke (core.clj:12)")))
  (is (= "my_app/util" (fr/ns-hint "at my-app.util$g (util.clj:3)")))
  (is (nil? (fr/ns-hint "Location: src/demo/core.clj:12:3"))))

(deftest resolve-file-test
  (let [root "/p"
        files ["/p/src/demo/core.clj" "/p/src/other/core.clj" "/p/src/demo/util.clj" "/p/test/demo/core_test.clj"]
        opts {:root root :files files :exists? #(contains? #{"/abs/x.clj" "/p/src/demo/util.clj"} %)}]
    (testing "an absolute path that exists"
      (is (= {:path "/abs/x.clj"} (fr/resolve-file "/abs/x.clj" opts))))
    (testing "a path under the root"
      (is (= {:path "/p/src/demo/util.clj"} (fr/resolve-file "src/demo/util.clj" opts))))
    (testing "a bare name one project file has"
      (is (= {:path "/p/test/demo/core_test.clj"} (fr/resolve-file "core_test.clj" opts))))
    (testing "a bare name several have: the namespace in the line decides"
      (is (= {:path "/p/src/demo/core.clj"} (fr/resolve-file "core.clj" (assoc opts :ns-hint "demo/core"))))
      (is (= {:candidates ["/p/src/demo/core.clj" "/p/src/other/core.clj"]}
             (fr/resolve-file "core.clj" opts))))
    (testing "a partial path picks by its folders"
      (is (= {:path "/p/src/other/core.clj"} (fr/resolve-file "other/core.clj" opts))))
    (testing "nothing matches"
      (is (= {:none true} (fr/resolve-file "nope.clj" opts)))
      (is (= {:none true} (fr/resolve-file "/abs/gone.clj" opts))))))

;; --- review round 1 ---------------------------------------------------------

(deftest ref-at-is-linear-test
  (testing "a long unbroken run of word characters answers at once, not after backtracking"
    (let [line (apply str (repeat 20000 "ab.cd"))
          t0 (System/nanoTime)]
      (is (nil? (fr/ref-at line 10000)))
      (is (< (/ (- (System/nanoTime) t0) 1e6) 500) "well under half a second"))))

(deftest ref-at-prefixes-test
  (testing "a word before a colon isn't a drive"
    (is (= "src/a.clj" (:file (at "file:src/a.clj:3" "src"))))
    (is (= "x.clj" (:file (at "error:x.clj:3" "x.clj")))))
  (testing "a URL isn't a file reference"
    (is (nil? (at "see https://example.com/y.clj:80 now" "y.clj"))))
  (testing "a Windows drive path keeps its drive"
    (is (= "C:\\x\\a.clj" (:file (at "at C:\\x\\a.clj:4" "C:")))))
  (testing "the reference nearest the click on a line with two"
    (is (= "b.clj" (:file (at "a.clj:1 then b.clj:2" "b.clj"))))))

(deftest ns-hint-word-start-test
  (is (nil? (fr/ns-hint "at MyApp.core$f (core.clj:3)")) "never from the middle of a word"))

(deftest resolve-file-review-test
  (let [files ["/p/src/a.clj" "/p/src/b/a.clj"]
        opts {:root "/p" :files files :exists? (constantly false)}]
    (testing "an absolute path from another machine falls back to the project's own file"
      (is (= {:path "/p/src/a.clj"} (fr/resolve-file "/home/ci/proj/src/a.clj" opts))))
    (testing "a Windows absolute path is absolute"
      (is (= {:path "C:/x/a.clj"} (fr/resolve-file "C:\\x\\a.clj" (assoc opts :exists? #{"C:/x/a.clj"})))))
    (testing "the project's files are only walked when they're needed"
      (let [walked (atom 0)
            lazy-files (delay (swap! walked inc) files)]
        (is (= {:path "/abs.clj"} (fr/resolve-file "/abs.clj" (assoc opts :files lazy-files :exists? #{"/abs.clj"}))))
        (is (= 0 @walked))
        (is (= {:path "/p/src/b/a.clj"} (fr/resolve-file "b/a.clj" (assoc opts :files lazy-files))))
        (is (= 1 @walked))))))
