(ns jonquil.file-ops-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.file-ops :as ops]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- canon [p] (.getCanonicalPath (io/file p)))

(defn- tree!
  "A fresh temp dir holding entries: a string value is a file's text, nil
  makes a folder. Its canonical path."
  [entries]
  (let [root (temp-dirs/temp-dir! "jonquil-file-ops-test-")]
    (doseq [[rel text] entries]
      (let [f (io/file root rel)]
        (if (nil? text)
          (.mkdirs f)
          (do (io/make-parents f) (spit f text)))))
    (canon root)))

(deftest name-problem-test
  (let [dir (tree! {"a.clj" "A" "sub" nil})
        old (str dir "/a.clj")]
    (is (= "Type a name." (ops/name-problem {:dir dir :name ""})))
    (is (= "Type a name." (ops/name-problem {:dir dir :name "   "})))
    (is (= "Type a name." (ops/name-problem {:dir dir :name nil})))
    (is (= "A name can't contain /." (ops/name-problem {:dir dir :name "x/y"})))
    (is (= "That name is reserved." (ops/name-problem {:dir dir :name "."})))
    (is (= "That name is reserved." (ops/name-problem {:dir dir :name ".."})))
    (is (= "a.clj already exists here." (ops/name-problem {:dir dir :name "a.clj"})))
    (is (= "sub already exists here." (ops/name-problem {:dir dir :name "sub" :old-path old})))
    (is (nil? (ops/name-problem {:dir dir :name "fresh.clj"})))
    (testing "renaming to the same name is nothing to do"
      (is (nil? (ops/name-problem {:dir dir :name "a.clj" :old-path old}))))
    (testing "case-only rename"
      (spit (io/file dir "core.clj") "C")
      (if (.exists (io/file dir "CORE.CLJ"))
        (is (nil? (ops/name-problem {:dir dir :name "Core.clj"
                                     :old-path (str dir "/core.clj")})))
        (is (nil? (ops/name-problem {:dir dir :name "Core.clj"
                                     :old-path (str dir "/core.clj")}))
            "case-sensitive volume: Core.clj is just a free name")))))

(deftest create-file-test
  (let [dir (tree! {"taken.clj" "keep"})
        r (ops/create-file! dir "new.clj")]
    (is (= (canon (str dir "/new.clj")) (:path r)))
    (is (= "" (slurp (io/file dir "new.clj"))))
    (let [e (ops/create-file! dir "taken.clj")]
      (is (string? (:error e)))
      (is (nil? (:path e)))
      (is (= "keep" (slurp (io/file dir "taken.clj")))))
    (testing "never throws on a missing parent"
      (is (string? (:error (ops/create-file! (str dir "/nope") "x.clj")))))))

(deftest create-trims-name-test
  (let [dir (tree! {})
        r (ops/create-file! dir "x.clj ")]
    (is (= (canon (str dir "/x.clj")) (:path r)))
    (is (.exists (io/file dir "x.clj")))))

(deftest symlink-target-test
  (let [dir (tree! {"a.clj" "A"})
        link (io/file dir "link.clj")]
    (java.nio.file.Files/createSymbolicLink
     (.toPath link) (.toPath (io/file dir "a.clj"))
     (make-array java.nio.file.attribute.FileAttribute 0))
    (is (= "link.clj already exists here."
           (ops/name-problem {:dir dir :name "link.clj" :old-path (str dir "/a.clj")})))
    (let [r (ops/rename! (str dir "/a.clj") "link.clj")]
      (is (= "link.clj already exists here." (:error r)))
      (is (java.nio.file.Files/isSymbolicLink (.toPath link)))
      (is (= "A" (slurp (io/file dir "a.clj")))))))

(deftest distinct-link-spelled-like-new-name-test
  (let [dir (tree! {"a.clj" "A"})]
    (if (.exists (io/file dir "A.CLJ"))
      (testing "case-insensitive volume: A.clj cannot exist beside a.clj, so the
                case-sensitive link scenario can't be built here (not asserted)"
        (is true))
      (let [link (io/file dir "A.clj")]
        (java.nio.file.Files/createSymbolicLink
         (.toPath link) (.toPath (io/file dir "a.clj"))
         (make-array java.nio.file.attribute.FileAttribute 0))
        (is (= "A.clj already exists here."
               (ops/name-problem {:dir dir :name "A.clj" :old-path (str dir "/a.clj")})))
        (is (= "A.clj already exists here." (:error (ops/rename! (str dir "/a.clj") "A.clj"))))
        (is (java.nio.file.Files/isSymbolicLink (.toPath link)))))))

(deftest create-folder-test
  (let [dir (tree! {"taken" "keep"})
        r (ops/create-folder! dir "fresh")]
    (is (= (canon (str dir "/fresh")) (:path r)))
    (is (.isDirectory (io/file dir "fresh")))
    (is (string? (:error (ops/create-folder! dir "taken"))))
    (is (= "keep" (slurp (io/file dir "taken"))))))

(deftest rename-test
  (testing "plain rename"
    (let [dir (tree! {"a.clj" "A"})
          r (ops/rename! (str dir "/a.clj") "b.clj")]
      (is (= (canon (str dir "/b.clj")) (:path r)))
      (is (not (.exists (io/file dir "a.clj"))))
      (is (= "A" (slurp (io/file dir "b.clj"))))))
  (testing "a folder keeps its children"
    (let [dir (tree! {"src/x.clj" "X" "src/deep/y.clj" "Y"})
          r (ops/rename! (str dir "/src") "lib")]
      (is (= (canon (str dir "/lib")) (:path r)))
      (is (= "X" (slurp (io/file dir "lib/x.clj"))))
      (is (= "Y" (slurp (io/file dir "lib/deep/y.clj"))))))
  (testing "a target created after name-problem returned nil is not overwritten"
    (let [dir (tree! {"a.clj" "A"})
          old (str dir "/a.clj")]
      (is (nil? (ops/name-problem {:dir dir :name "b.clj" :old-path old})))
      (spit (io/file dir "b.clj") "B")
      (let [e (ops/rename! old "b.clj")]
        (is (= "b.clj already exists here." (:error e)))
        (is (= "A" (slurp (io/file dir "a.clj"))))
        (is (= "B" (slurp (io/file dir "b.clj")))))))
  (testing "a missing source is an :error, not a throw"
    (let [dir (tree! {})]
      (is (string? (:error (ops/rename! (str dir "/ghost.clj") "x.clj"))))))
  (testing "case-only rename"
    (let [dir (tree! {"core.clj" "content"})]
      (if (.exists (io/file dir "CORE.CLJ"))
        (let [r (ops/rename! (str dir "/core.clj") "Core.clj")]
          (is (nil? (:error r)))
          (is (= (canon (str dir "/Core.clj")) (:path r)))
          (is (= "Core.clj" (.getName (io/file (:path r)))) "new spelling on disk")
          (is (= "content" (slurp (io/file dir "Core.clj")))))
        (testing "case-sensitive volume: case-only rename is not a special case"
          (is true))))))

(deftest retarget-test
  (let [dir (tree! {"src/a.clj" "" "src2/x.clj" "" "src/deep/b.clj" ""})
        old (str dir "/src")
        new (str dir "/lib")]
    (is (= new (ops/retarget old old new)) "the folder itself")
    (is (= (str new "/a.clj") (ops/retarget (str old "/a.clj") old new)))
    (is (= (str new "/deep/b.clj") (ops/retarget (str old "/deep/b.clj") old new)))
    (is (nil? (ops/retarget (str dir "/src2/x.clj") old new)) "src vs src2")
    (is (nil? (ops/retarget (str dir "/src2") old new)))
    (is (nil? (ops/retarget (str dir "/other") old new)))
    (testing "a non-canonical path reached through .."
      (is (= (str new "/a.clj")
             (ops/retarget (str dir "/src2/../src/a.clj") old new)))
      (is (= new (ops/retarget (str dir "/src2/../src") old new)))
      (is (= (str new "/x.clj") (ops/retarget (str old "/../src/x.clj") old new))
          "a given path that starts with old still resolves its .. first"))
    (testing "a stale spelling after a case-only rename"
      (let [d (tree! {"core.clj" "c"})
            o (str d "/core.clj")
            n (str d "/Core.clj")]
        (when (.exists (io/file d "CORE.CLJ"))
          (is (nil? (:error (ops/rename! o "Core.clj"))))
          (is (= n (ops/retarget o o n))))))
    (testing "a path that cannot be canonicalised is used as given"
      (is (nil? (ops/retarget "/definitely/not/here" old new))))))

(deftest trash-alert-test
  (is (= {:message "Move a.clj to the Trash?" :buttons ["Move to Trash" "Cancel"]}
         (ops/trash-alert "a.clj" false)))
  (is (= {:message "Move the folder src and everything in it to the Trash?"
          :buttons ["Move to Trash" "Cancel"]}
         (ops/trash-alert "src" true))))

(deftest trash-message-test
  (is (= "; Moved a.clj to the Trash.\n" (ops/trash-message "a.clj" 0 false)))
  (is (= "; Moved a.clj to the Trash. Its tab is still open; Save writes it back.\n"
         (ops/trash-message "a.clj" 1 false)))
  (is (= "; Moved a.clj to the Trash. 3 tabs on it are still open; Save writes them back.\n"
         (ops/trash-message "a.clj" 3 false)))
  (is (= "; Moved the folder src to the Trash.\n" (ops/trash-message "src" 0 true)))
  (is (= "; Moved the folder src to the Trash. A tab on a file in it is still open; use Save As to keep it.\n"
         (ops/trash-message "src" 1 true)))
  (is (= "; Moved the folder src to the Trash. 2 tabs on files in it are still open; use Save As to keep them.\n"
         (ops/trash-message "src" 2 true))))

(deftest hidden-message-test
  (is (= "; x.log is hidden in the tree (dotfile or .gitignore).\n"
         (ops/hidden-message "x.log"))))
