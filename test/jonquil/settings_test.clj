(ns jonquil.settings-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.settings :as settings]))

(defn- temp-dir []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "jonquil-settings-test-" (System/nanoTime)))]
    (.mkdirs d)
    (.getPath d)))

(deftest settings-path-test
  (is (= "/x/cfg/jonquil/settings.edn" (settings/settings-path {"XDG_CONFIG_HOME" "/x/cfg"} "/home/u")))
  (is (= "/home/u/.config/jonquil/settings.edn" (settings/settings-path {} "/home/u")))
  (is (= "/home/u/.config/jonquil/settings.edn" (settings/settings-path {"XDG_CONFIG_HOME" ""} "/home/u")))
  ;; fix wave item 4: a relative XDG_CONFIG_HOME is invalid per the XDG spec.
  (is (= "/home/u/.config/jonquil/settings.edn"
         (settings/settings-path {"XDG_CONFIG_HOME" "relative/cfg"} "/home/u"))))

(deftest round-trip-test
  (let [path (str (temp-dir) "/nested/jonquil/settings.edn")]
    (is (nil? (settings/save-settings! path {:theme :navy})))
    (is (= {:theme :navy} (settings/load-settings path)))
    (is (nil? (settings/save-settings! path {:theme :dark})))
    (is (= {:theme :dark} (settings/load-settings path)))
    (testing "no temp file is left behind"
      (is (= ["settings.edn"] (vec (.list (.getParentFile (io/file path)))))))))

(deftest missing-file-test
  (is (= {:theme :light} (settings/load-settings (str (temp-dir) "/none.edn")))))

(deftest unreadable-file-test
  (let [path (str (temp-dir) "/settings.edn")]
    (spit path "{:theme")
    (let [r (settings/load-settings path)]
      (is (= :light (:theme r)))
      (is (str/includes? (:warning r) "couldn't read")))))

(deftest unknown-theme-test
  (doseq [content ["{:theme :purple}" "[:not :a :map]" "{}"]]
    (let [path (str (temp-dir) "/settings.edn")]
      (spit path content)
      (let [r (settings/load-settings path)]
        (is (= :light (:theme r)) content)
        (is (str/includes? (:warning r) "no known :theme") content)))))

(deftest save-preserves-symlink-test
  ;; fix wave item 3: saving through a symlinked settings.edn must rewrite
  ;; the real target in place, not replace the link with a plain file.
  (let [dir (temp-dir)
        real (str dir "/real-target.edn")
        link (str dir "/settings.edn")]
    (spit real (pr-str {:theme :light}))
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file link)) (.toPath (io/file real))
     (make-array java.nio.file.attribute.FileAttribute 0))
    (is (nil? (settings/save-settings! link {:theme :dark})))
    (is (java.nio.file.Files/isSymbolicLink (.toPath (io/file link)))
        "the link itself must survive the save")
    (is (= {:theme :dark} (settings/load-settings real))
        "the real target must read back the new value")))

(deftest save-failure-test
  (let [blocker (str (temp-dir) "/file")]
    (spit blocker "x")
    ;; a path under a regular file can't be created
    (let [msg (settings/save-settings! (str blocker "/jonquil/settings.edn") {:theme :navy})]
      (is (string? msg))
      (is (str/includes? msg "couldn't save")))))

(deftest save-through-bad-symlink-test
  (testing "dangling symlink"
    (let [dir (temp-dir)
          link (str dir "/settings.edn")
          target (str dir "/missing.edn")]
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file link)) (.toPath (io/file target))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (let [msg (settings/save-settings! link {:theme :dark})]
        (is (string? msg) "should return a string, not throw")
        (is (str/includes? msg "couldn't save") "error message should mention save failure"))))
  (testing "symlink loop"
    (let [dir (temp-dir)
          a (str dir "/a.edn")
          b (str dir "/b.edn")]
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file a)) (.toPath (io/file b))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file b)) (.toPath (io/file a))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (let [msg (settings/save-settings! a {:theme :dark})]
        (is (string? msg) "should return a string, not throw")
        (is (str/includes? msg "couldn't save") "error message should mention save failure")))))

(deftest dialect-test
  (testing "a known :dialect loads; the map has no :dialect key when the file names none"
    (let [path (str (temp-dir) "/settings.edn")]
      (spit path "{:theme :navy :dialect :jolt}")
      (is (= {:theme :navy :dialect :jolt} (settings/load-settings path)))))
  (testing "an unknown :dialect is a warning, and the theme still loads"
    (let [path (str (temp-dir) "/settings.edn")]
      (spit path "{:theme :navy :dialect :cobol}")
      (let [r (settings/load-settings path)]
        (is (= :navy (:theme r)))
        (is (nil? (:dialect r)))
        (is (str/includes? (:warning r) "names :dialect :cobol")))))
  (testing "both parts bad: both warnings"
    (let [path (str (temp-dir) "/settings.edn")]
      (spit path "{:theme :purple :dialect :cobol}")
      (let [w (:warning (settings/load-settings path))]
        (is (str/includes? w "no known :theme"))
        (is (str/includes? w "names :dialect :cobol")))))
  (testing "saving a theme keeps a hand-set :dialect"
    (let [path (str (temp-dir) "/settings.edn")]
      (spit path "{:theme :light :dialect :jolt}")
      (is (nil? (settings/save-settings! path {:theme :dark})))
      (is (= {:theme :dark :dialect :jolt} (settings/load-settings path)))))
  (testing "saving never invents a :dialect"
    (let [path (str (temp-dir) "/settings.edn")]
      (is (nil? (settings/save-settings! path {:theme :dark})))
      (is (= "{:theme :dark}" (slurp path))))))

(deftest save-keeps-dialect-when-unreadable-test
  (testing "unreadable file: return a message and don't modify the file"
    (let [path (str (temp-dir) "/settings.edn")
          f (io/file path)]
      (spit path "{:theme :navy :dialect :jolt}")
      (.setReadable f false false)
      (try
        (let [msg (settings/save-settings! path {:theme :dark})]
          (is (string? msg) "should return a string")
          (is (str/includes? msg "couldn't read") "should mention couldn't read"))
        (finally
          (.setReadable f true false)))
      (is (= "{:theme :navy :dialect :jolt}" (slurp path)) "file should be unchanged byte-for-byte")))
  (testing "malformed EDN: overwrite and return nil"
    (let [path (str (temp-dir) "/settings.edn")]
      (spit path "{:theme :navy :dialect :jolt")
      (is (nil? (settings/save-settings! path {:theme :dark})))
      (is (= {:theme :dark} (settings/load-settings path)))))
  (testing "missing file: create and return nil"
    (let [path (str (temp-dir) "/settings.edn")]
      (is (nil? (settings/save-settings! path {:theme :dark})))
      (is (= {:theme :dark} (settings/load-settings path))))))
