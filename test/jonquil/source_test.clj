(ns jonquil.source-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.source :as source]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- write-jar!
  "Writes a jar at path holding entries ({entry-name text})."
  [path entries]
  (with-open [z (java.util.zip.ZipOutputStream. (java.io.FileOutputStream. ^String path))]
    (doseq [[n text] entries]
      (.putNextEntry z (java.util.zip.ZipEntry. ^String n))
      (.write z (.getBytes ^String text "UTF-8"))
      (.closeEntry z)))
  path)

(deftest location-test
  (testing "no :file means no location"
    (is (nil? (source/location {:line 3} {:project-dir "/p"})))
    (is (nil? (source/location {:file "" :line 3} {:project-dir "/p"}))))
  (testing "a :file that isn't a jar:/file: URL or an absolute path is no location either"
    (is (nil? (source/location {:file "unknown" :line 1} {:project-dir "/p"})))
    (is (nil? (source/location {:file "clojure/core.clj" :line 10} {:project-dir "/p"}))))
  (testing "a plain file under the project is editable"
    (let [dir (temp-dirs/temp-dir! "jonquil-source-test-")
          f (io/file dir "src/demo/util.clj")]
      (.mkdirs (.getParentFile f))
      (spit f "(ns demo.util)\n")
      (is (= {:path (.getPath f) :line 4 :editable? true :label "util.clj"}
             (source/location {:file (.getPath f) :line 4} {:project-dir dir})))))
  (testing "outside the project, or climbing out with .., is read-only"
    (let [dir (temp-dirs/temp-dir! "jonquil-source-test-")
          other (temp-dirs/temp-dir! "jonquil-source-test-")
          f (io/file other "x.clj")
          climbing (str dir "/../" (.getName (io/file other)) "/x.clj")]
      (spit f "(ns x)\n")
      (is (false? (:editable? (source/location {:file (.getPath f) :line 1} {:project-dir dir}))))
      (is (false? (:editable? (source/location {:file climbing :line 1} {:project-dir dir}))))
      (is (false? (:editable? (source/location {:file (.getPath f) :line 1} {:project-dir nil}))))))
  (testing "a symlinked project dir still counts as the project"
    (let [real (temp-dirs/temp-dir! "jonquil-source-test-")
          parent (temp-dirs/temp-dir! "jonquil-source-test-")
          link (io/file parent "proj")
          f (io/file real "a.clj")]
      (spit f "(ns a)\n")
      (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath (io/file real))
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (is (true? (:editable? (source/location {:file (.getPath f) :line 1} {:project-dir (.getPath link)}))))))
  (testing "a jar entry, with percent escapes decoded"
    (is (= {:jar "/Users/a b/café.jar" :entry "clojure/string.clj" :line 180 :editable? false
            :label "string.clj in café.jar"}
           (source/location {:file "jar:file:/Users/a%20b/caf%C3%A9.jar!/clojure/string.clj" :line 180}
                            {:project-dir "/p"}))))
  (testing "a missing or non-positive line reads as 1"
    (is (= 1 (:line (source/location {:file "/x/y.clj"} {:project-dir "/p"}))))
    (is (= 1 (:line (source/location {:file "/x/y.clj" :line 0} {:project-dir "/p"}))))))

(deftest classpath-file-test
  ;; A Jolt after v0.8.23 (jolt-lang/jolt f714b0ed) records a required
  ;; namespace's :file as its classpath name, so these stand in for that.
  (testing "a name on this process's roots resolves to the file's path"
    (is (= (.getCanonicalPath (io/file "src/jonquil/help.clj")) (source/classpath-file "jonquil/help.clj"))))
  (testing "nothing on the roots is nil"
    (is (.isFile (io/file "README.md")) "it is in the directory, but not on a root")
    (is (nil? (source/classpath-file "README.md")))
    (is (nil? (source/classpath-file "no/such/thing.clj"))))
  (testing "a Jolt stdlib name answers whatever this Jolt can read, or nil.
           Releases to v0.8.23 give a URL whose string is the bare name
           (nil here); jolt main (#1438) a readable jar: URL; a source-mode
           jolt a real file"
    (let [r (source/classpath-file "clojure/string.clj")]
      (is (or (nil? r)
              (and (str/starts-with? r "jar:file:") (pos? (count (slurp r))))
              (.isFile (io/file r)))
          (pr-str r)))))

(deftest resolve-file-test
  (let [help (.getCanonicalPath (io/file "src/jonquil/help.clj"))]
    (is (= {:file help :line 3} (source/resolve-file {:file "jonquil/help.clj" :line 3})))
    (is (= help (:path (source/location (source/resolve-file {:file "jonquil/help.clj"}) {})))
        "location can open what it resolves to")
    (testing "anything else comes back unchanged"
      (doseq [d [{:file "unknown"} {:file "/x/y.clj"} {:file "jar:file:/a.jar!/b.clj"}
                 {:file "file:/x/y.clj"} {:file ""} {:line 1} {:file "README.md"}
                 {:file "./src/jonquil/help.clj"} {:file "jonquil"} {:file nil}]]
        (is (= d (source/resolve-file d)))))))

(deftest same-file-test
  (let [dir (temp-dirs/temp-dir! "jonquil-source-test-")
        f (io/file dir "a.clj")]
    (spit f "x")
    (is (source/same-path? (.getPath f) (str dir "/./a.clj")))
    (is (not (source/same-path? (.getPath f) (str dir "/b.clj"))))
    (is (source/same-file? {:path (.getPath f)} {:path (str dir "/./a.clj")}))
    (is (source/same-file? {:jar "/j.jar" :entry "a.clj"} {:jar "/j.jar" :entry "a.clj"}))
    (is (not (source/same-file? {:jar "/j.jar" :entry "a.clj"} {:jar "/j.jar" :entry "b.clj"})))
    (is (not (source/same-file? {:path (.getPath f)} nil)))))

(deftest read-text-test
  (let [dir (temp-dirs/temp-dir! "jonquil-source-test-")
        f (io/file dir "a.clj")
        jar (write-jar! (str dir "/lib.jar") {"demo/lib.clj" "(ns demo.lib)\n(defn f [] 1)\n"})]
    (spit f "(ns a)\n")
    (is (= {:text "(ns a)\n"} (source/read-text {:path (.getPath f)})))
    (is (str/starts-with? (:error (source/read-text {:path (str dir "/missing.clj")})) "Couldn't read "))
    (is (= {:text "(ns demo.lib)\n(defn f [] 1)\n"} (source/read-text {:jar jar :entry "demo/lib.clj"})))
    (is (= {:error "nope/ghost.clj isn't in lib.jar."} (source/read-text {:jar jar :entry "nope/ghost.clj"})))
    (is (str/starts-with? (:error (source/read-text {:jar (str dir "/gone.jar") :entry "a.clj"})) "Couldn't read "))))

(deftest lines-test
  (let [text "a\nb\nc\n"]
    (is (= {:line 2 :moved? false} (source/clamp-line text 2)))
    (is (= {:line 4 :moved? true} (source/clamp-line text 99)) "a trailing newline makes an empty last line")
    (is (= 0 (source/line-offset text 1)))
    (is (= 4 (source/line-offset text 3)))
    (is (= 6 (source/line-offset text 9)) "past the end gives the text's end")
    (is (= 1 (source/line-at text 0)))
    (is (= 3 (source/line-at text 4)))
    (is (= 4 (source/line-at text 99)))))

(deftest history-test
  (is (= [{:caret 1}] (source/push [] {:caret 1})))
  (is (= [{:caret 2} [{:caret 1}]] (source/back [{:caret 1} {:caret 2}])))
  (is (nil? (source/back [])))
  (let [h (reduce source/push [] (map (fn [i] {:caret i}) (range 60)))]
    (is (= source/history-limit (count h)))
    (is (= {:caret 59} (peek h)))
    (is (= {:caret 10} (first h)))))
