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
  (is (= "/home/u/.config/jonquil/settings.edn" (settings/settings-path {"XDG_CONFIG_HOME" ""} "/home/u"))))

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

(deftest save-failure-test
  (let [blocker (str (temp-dir) "/file")]
    (spit blocker "x")
    ;; a path under a regular file can't be created
    (let [msg (settings/save-settings! (str blocker "/jonquil/settings.edn") {:theme :navy})]
      (is (string? msg))
      (is (str/includes? msg "couldn't save")))))
