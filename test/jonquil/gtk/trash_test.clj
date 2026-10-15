(ns jonquil.gtk.trash-test
  "trash! needs GLib only, not an initialised GTK."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.trash :as trash]))

(deftest trash-test
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "jonquil-trash" (make-array java.nio.file.attribute.FileAttribute 0)))
        name (str "jonquil-trash-" (System/nanoTime) ".txt")
        f (io/file dir name)
        home (System/getProperty "user.home")
        ;; This test's own trashed copy, by its literal name: ~/.Trash on
        ;; macOS; the freedesktop files/ entry and its .trashinfo on Linux.
        trashed [(io/file home ".Trash" name)
                 (io/file home ".local/share/Trash/files" name)
                 (io/file home ".local/share/Trash/info" (str name ".trashinfo"))]]
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
        (doseq [t trashed] (when (.exists t) (.delete t)))
        (.delete f)
        (.delete dir)))))

(deftest trash-missing-path-test
  (let [r (trash/trash! (str (System/getProperty "java.io.tmpdir")
                             "/jonquil-no-such-" (System/nanoTime)))]
    (is (string? (:error r)))
    (is (nil? (:ok r)))))

(deftest trash-non-exception-throw-test
  (testing "a throw that isn't an Exception is still an :error, with its message"
    (with-redefs [g/g-file-new-for-path (fn [_] (throw (AssertionError. "boom")))]
      (is (= {:error "boom"} (trash/trash! "/nowhere/x.txt"))))))
