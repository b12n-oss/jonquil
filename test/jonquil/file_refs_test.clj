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
