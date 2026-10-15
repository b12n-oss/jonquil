(ns jonquil.save-path-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [jonquil.save-path :as sp]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "jonquil-save-path"
            (into-array java.nio.file.attribute.FileAttribute []))))

(defn- canon [^java.io.File f] (.getCanonicalPath f))

(defn- link! [^java.io.File dir name target]
  (java.nio.file.Files/createSymbolicLink
   (.toPath (java.io.File. dir ^String name))
   (java.nio.file.Paths/get ^String target (into-array String []))
   (into-array java.nio.file.attribute.FileAttribute []))
  (str dir "/" name))

(deftest resolve-target-test
  (testing "a regular file resolves to its canonical path"
    (let [d (temp-dir)
          f (java.io.File. d "a.clj")]
      (spit f "x")
      (is (= (canon f) (sp/resolve-target (str f))))))
  (testing "a relative link resolves to the real file"
    (let [d (temp-dir)
          real (java.io.File. d "real.clj")]
      (spit real "x")
      (is (= (canon real) (sp/resolve-target (link! d "link.clj" "real.clj"))))))
  (testing "an absolute link target resolves to it"
    (let [d (temp-dir)
          real (java.io.File. d "real.clj")]
      (spit real "x")
      (is (= (canon real) (sp/resolve-target (link! d "link.clj" (canon real)))))))
  (testing "a chain of links resolves to the last file"
    (let [d (temp-dir)
          real (java.io.File. d "c.clj")]
      (spit real "x")
      (link! d "b" "c.clj")
      (is (= (canon real) (sp/resolve-target (link! d "a" "b"))))))
  (testing "a dangling link resolves to the file it would create"
    (let [d (temp-dir)]
      (is (= (str (canon d) "/missing.clj")
             (sp/resolve-target (link! d "link.clj" "missing.clj"))))))
  (testing "a dangling link into a missing directory resolves under it"
    (let [parent (temp-dir)
          d (java.io.File. parent "sub")]
      (.mkdir d)
      (is (= (str (canon parent) "/nodir/x.clj")
             (sp/resolve-target (link! d "link.clj" "../nodir/x.clj"))))))
  (testing "a link loop throws"
    (let [d (temp-dir)]
      (link! d "b" "a")
      (let [e (try (sp/resolve-target (link! d "a" "b")) nil
                   (catch :default e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "too many levels of symbolic links"))))))

(deftest create-temp-test
  (testing "an existing target gets an owner-only empty temp file"
    (let [d (temp-dir)
          target (java.io.File. d "t.clj")
          tmp (java.io.File. d ".t.clj.tmp")]
      (spit target "x")
      (sp/create-temp! (str tmp) (str target))
      (is (.exists tmp))
      (is (= "" (slurp tmp)))
      (is (= (set (map str (java.nio.file.Files/getPosixFilePermissions (.toPath tmp)
                                                                         (into-array java.nio.file.LinkOption []))))
             #{"OWNER_READ" "OWNER_WRITE"}))))
  (testing "a missing target creates nothing"
    (let [d (temp-dir)
          tmp (java.io.File. d ".t.clj.tmp")]
      (sp/create-temp! (str tmp) (str d "/none.clj"))
      (is (not (.exists tmp))))))
