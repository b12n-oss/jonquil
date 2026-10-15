(ns jonquil.gtk.trash-test
  "trash! needs GLib only, not an initialised GTK."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.gtk.trash :as trash]))

(deftest trash-test
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "jonquil-trash" (make-array java.nio.file.attribute.FileAttribute 0)))
        name (str "jonquil-trash-" (System/nanoTime) ".txt")
        f (io/file dir name)
        trashed (io/file (System/getProperty "user.home") ".Trash" name)]
    (try
      (spit f "x")
      (let [r (trash/trash! (.getPath f))]
        (if (:ok r)
          (is (not (.exists f)))
          (do (is (string? (:error r)))
              (is (not (str/blank? (:error r))))
              (is (.exists f))))
        (is (not (and (:ok r) (:error r))))
        (when (= "Mac OS X" (System/getProperty "os.name"))
          (is (:ok r))))
      (finally
        (when (.exists trashed) (.delete trashed))
        (.delete f)
        (.delete dir)))))

(deftest trash-missing-path-test
  (let [r (trash/trash! (str (System/getProperty "java.io.tmpdir")
                             "/jonquil-no-such-" (System/nanoTime)))]
    (is (string? (:error r)))
    (is (nil? (:ok r)))))
