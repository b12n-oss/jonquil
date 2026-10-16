(ns jonquil.settings-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.settings :as settings]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- temp-dir [] (temp-dirs/temp-dir! "jonquil-settings-test-"))

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

(deftest auto-theme-test
  (let [p (str (temp-dir) "/settings.edn")]
    (is (nil? (settings/save-settings! p {:theme :auto})))
    (is (= {:theme :auto} (settings/load-settings p)))))

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
  (testing "malformed EDN: refuse with a message and leave the file alone"
    ;; File-tree final review finding 5: saves now also happen when the
    ;; tree is toggled, so overwriting a file mid-edit would silently drop
    ;; its other keys.
    (let [path (str (temp-dir) "/settings.edn")]
      (spit path "{:theme :navy :dialect :jolt")
      (let [msg (settings/save-settings! path {:theme :dark})]
        (is (string? msg))
        (is (str/includes? msg "isn't valid EDN")))
      (is (= "{:theme :navy :dialect :jolt" (slurp path)))))
  (testing "missing file: create and return nil"
    (let [path (str (temp-dir) "/settings.edn")]
      (is (nil? (settings/save-settings! path {:theme :dark})))
      (is (= {:theme :dark} (settings/load-settings path))))))

(deftest save-merges-test
  ;; File-tree plan P1.T2: a save keeps every key the file already holds.
  (let [path (str (temp-dir) "/settings.edn")]
    (spit path "{:theme :light :dialect :jolt :file-tree {:visible? true :width 240}}")
    (is (nil? (settings/save-settings! path {:theme :dark})))
    (is (= {:theme :dark :dialect :jolt :file-tree {:visible? true :width 240}}
           (edn/read-string (slurp path))))
    (is (nil? (settings/save-settings! path {:file-tree {:visible? false :width 300}})))
    (is (= {:theme :dark :dialect :jolt :file-tree {:visible? false :width 300}}
           (edn/read-string (slurp path))))))

(deftest file-tree-load-test
  (let [path (str (temp-dir) "/settings.edn")]
    (spit path "{:theme :navy :file-tree {:visible? true :width 240}}")
    (is (= {:visible? true :width 240} (:file-tree (settings/load-settings path)))))
  (let [path (str (temp-dir) "/settings.edn")]
    (spit path "{:theme :navy :file-tree {:visible? \"yes\"}}")
    (let [r (settings/load-settings path)]
      (is (nil? (:file-tree r)))
      (is (= :navy (:theme r)))
      (is (str/includes? (:warning r) "unusable :file-tree")))))

(deftest reopen-tabs-test
  (let [path (str (temp-dir) "/settings.edn")]
    (spit path "{:theme :light :reopen-tabs? false}")
    (is (false? (:reopen-tabs? (settings/load-settings path)))))
  (let [path (str (temp-dir) "/settings.edn")]
    (spit path "{:theme :light}")
    (is (not (contains? (settings/load-settings path) :reopen-tabs?))))
  (let [path (str (temp-dir) "/settings.edn")]
    (spit path "{:theme :light :reopen-tabs? \"no\"}")
    (let [r (settings/load-settings path)]
      (is (not (contains? r :reopen-tabs?)))
      (is (str/includes? (:warning r) ":reopen-tabs?")))))

(deftest reopen-tabs-no-spurious-warning-test
  (doseq [text ["{:theme" "[1 2]" "{:theme :light :reopen-tabs? nil}"]]
    (let [path (str (temp-dir) "/settings.edn")]
      (spit path text)
      (let [r (settings/load-settings path)]
        (is (not (contains? r :reopen-tabs?)) text)
        (is (not (str/includes? (str (:warning r)) ":reopen-tabs?")) text)))))
