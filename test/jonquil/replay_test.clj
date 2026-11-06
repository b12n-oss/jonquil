(ns jonquil.replay-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.replay :as replay]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- dir! [] (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-replay-test-"))))

(defn- perms [path]
  (java.nio.file.attribute.PosixFilePermissions/toString
   (java.nio.file.Files/getPosixFilePermissions
    (.toPath (io/file path)) (make-array java.nio.file.LinkOption 0))))

(def ^:private file-entry {:kind :file :path "/a.clj" :at 1 :dialect "clj"})
(def ^:private form-entry {:kind :form :text "(+ 1 2)" :ns "user" :source-file nil :at 2 :dialect "clj"})

(deftest record-path-test
  (is (= (str "/c/jonquil/replays/my-app-" (Integer/toHexString (.hashCode "/p/my app")) ".edn")
         (replay/record-path "/c/jonquil/settings.edn" "/p/my app")))
  (is (= "/c/jonquil/replays/no-folder.edn" (replay/record-path "/c/jonquil/settings.edn" nil))))

(deftest load-missing-record-test
  (let [r (replay/load-record (str (dir!) "/nope.edn"))]
    (is (= {:record (replay/empty-record)} r))
    (is (not (contains? r :warning)))))

(deftest load-bad-record-test
  (doseq [text ["{:previous [" "[1 2]" "{:version 1 :previous 5}"]]
    (let [p (str (dir!) "/rec.edn")]
      (spit p text)
      (let [r (replay/load-record p)]
        (is (= (replay/empty-record) (:record r)) text)
        (is (= "Couldn't read rec.edn, so the last session starts empty." (:warning r)) text)))))

(deftest save-load-round-trip-test
  (let [p (str (dir!) "/replays/rec.edn")
        rec {:version 1 :previous [file-entry form-entry] :current [] :history ["(+ 1 2)"]}]
    (is (nil? (replay/save-record! p rec)))
    (is (= {:record rec} (replay/load-record p)))))

(deftest save-through-symlink-test
  (let [d (dir!)
        target (str d "/elsewhere/real.edn")
        link (str d "/rec.edn")
        rec (assoc (replay/empty-record) :previous [file-entry])]
    (io/make-parents target)
    (spit target "{}")
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file link)) (.toPath (io/file target))
     (make-array java.nio.file.attribute.FileAttribute 0))
    (is (nil? (replay/save-record! link rec)))
    (is (java.nio.file.Files/isSymbolicLink (.toPath (io/file link))))
    (is (= rec (edn/read-string (slurp target))))))

(deftest save-record-owner-only-test
  (let [d (dir!)
        p (str d "/replays/rec.edn")]
    (is (nil? (replay/save-record! p (replay/empty-record))))
    (is (= "rw-------" (perms p)))
    (is (= "rwx------" (perms (str d "/replays"))))
    (is (nil? (replay/save-record! p (replay/empty-record))))
    (is (= "rw-------" (perms p)))))

(deftest load-drops-bad-entries-test
  (let [p (str (dir!) "/rec.edn")]
    (spit p (pr-str {:version 1 :current [{:kind :file :path "/a.clj"} "junk" {:kind :zap}]}))
    (let [r (replay/load-record p)]
      (is (not (contains? r :warning)))
      (is (= [{:kind :file :path "/a.clj"}] (:current (:record r)))))))

(deftest save-record-preexisting-open-dir-test
  (let [d (dir!)
        rd (str d "/replays")
        p (str rd "/rec.edn")]
    (.mkdirs (io/file rd))
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath (io/file rd))
     (java.nio.file.attribute.PosixFilePermissions/fromString "rwxr-xr-x"))
    (is (nil? (replay/save-record! p (replay/empty-record))))
    (is (= "rw-------" (perms p)))
    (is (= "rwxr-xr-x" (perms rd)))))

(deftest save-record-nil-path-test
  (is (string? (replay/save-record! nil (replay/empty-record)))))
