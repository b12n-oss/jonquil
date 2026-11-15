(ns jonquil.terminal-open-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.settings :as settings]
            [jonquil.terminal-open :as to]))

(defn- temp-dir []
  (.getCanonicalPath (.toFile (java.nio.file.Files/createTempDirectory
                               "jonquil-terminal-open" (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest folder-test
  (let [root (temp-dir)
        src (io/file root "src" "app")]
    (.mkdirs src)
    (spit (io/file root "deps.edn") "{}")
    (spit (io/file src "core.clj") "(ns app.core)")
    (testing "a file in a project: the project's folder, not the file's"
      (is (= root (to/folder (.getPath (io/file src "core.clj")) "/home/me"))))
    (testing "a file in no project: its own folder"
      (let [loose (temp-dir)]
        (spit (io/file loose "x.txt") "x")
        (is (= loose (to/folder (.getPath (io/file loose "x.txt")) "/home/me")))))
    (testing "a tab with no file: home"
      (is (= "/home/me" (to/folder nil "/home/me")))
      (is (= "/home/me" (to/folder "" "/home/me"))))))

(deftest command-test
  (let [none (constantly false)]
    (testing "a Mac opens an app on the folder"
      (is (= {:argv ["open" "-a" "Terminal" "/p"] :dir nil}
             (to/command "/p" {:os "Mac OS X" :setting nil :on-path? none})))
      (is (= {:argv ["open" "-a" "Ghostty" "/p"] :dir nil}
             (to/command "/p" {:os "Mac OS X" :setting "Ghostty" :on-path? none}))))
    (testing "elsewhere a program starts with the folder as its working directory"
      (is (= {:argv ["kitty"] :dir "/p"}
             (to/command "/p" {:os "Linux" :setting "kitty" :on-path? none})))
      (is (= {:argv ["konsole"] :dir "/p"}
             (to/command "/p" {:os "Linux" :setting nil :on-path? #{"konsole" "xterm"}}))
          "the first of the list that is on the PATH"))
    (testing "none found: says what it tried and how to name one"
      (let [p (:problem (to/command "/p" {:os "Linux" :setting nil :on-path? none}))]
        (is (str/includes? p "xdg-terminal-exec"))
        (is (str/includes? p ":terminal in settings.edn"))))
    (testing "the test override wins everywhere and gets the folder"
      (doseq [os ["Mac OS X" "Linux"]]
        (is (= {:argv ["/bin/fake" "/p"] :dir "/p"}
               (to/command "/p" {:os os :setting "kitty" :override "/bin/fake" :on-path? none})))))))

(deftest terminal-setting-test
  (let [path (str (temp-dir) "/settings.edn")
        load (fn [text] (spit path text) (settings/load-settings path))]
    (is (= "Ghostty" (:terminal (load "{:terminal \"Ghostty\"}"))))
    (is (nil? (:warning (load "{:terminal \"Ghostty\"}"))))
    (is (nil? (:terminal (load "{}"))))
    (doseq [bad ["{:terminal :ghostty}" "{:terminal \"\"}" "{:terminal 3}"]]
      (let [r (load bad)]
        (is (nil? (:terminal r)) bad)
        (is (str/includes? (:warning r) "unusable :terminal") bad)))))
