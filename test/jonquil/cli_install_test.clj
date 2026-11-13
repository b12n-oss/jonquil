(ns jonquil.cli-install-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.process :as p]
            [jonquil.cli-install :as ci]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def ^:private exe "/Applications/Jonquil.app/Contents/MacOS/jonquil-bin")
(def ^:private script "/Applications/Jonquil.app/Contents/Resources/bin/jonquil")

(deftest bundle-of-test
  (is (= "/Applications/Jonquil.app" (ci/bundle-of exe)))
  (is (= "/Users/me/My Apps/Jonquil 2.app" (ci/bundle-of "/Users/me/My Apps/Jonquil 2.app/Contents/MacOS/jonquil")))
  (is (nil? (ci/bundle-of "/Users/me/dev/jonquil/jonquil")) "a checkout's binary")
  (is (nil? (ci/bundle-of "jolt")) "a bare program name")
  (is (nil? (ci/bundle-of nil))))

(deftest ours-test
  (is (ci/ours? script))
  (is (ci/ours? "/Users/me/Applications/Jonquil.app/Contents/Resources/bin/jonquil"))
  (is (not (ci/ours? "/opt/homebrew/bin/jonquil")))
  (is (not (ci/ours? "/tmp/Contents/Resources/bin/jonquil")) "not inside a .app")
  (is (not (ci/ours? nil))))

(defn- plan [m]
  (ci/plan (merge {:exe exe :home "/Users/me" :path-env "/usr/local/bin:/usr/bin"
                   :existing {} :writable? (constantly true)}
                  m)))

(deftest plan-test
  (testing "/usr/local/bin when it can be written"
    (let [r (plan {})]
      (is (= ["/usr/local/bin/jonquil" script] [(:link r) (:target r)]))
      (is (= 2 (count (:lines r))) "no PATH note: it's on PATH")))
  (testing "else ~/.local/bin, with the PATH line when it isn't on PATH"
    (let [r (plan {:writable? #(= "/Users/me/.local/bin" %)})]
      (is (= "/Users/me/.local/bin/jonquil" (:link r)))
      (is (some #(str/includes? % "export PATH=\"/Users/me/.local/bin:$PATH\"") (:lines r))))
    (is (= 2 (count (:lines (plan {:writable? #(= "/Users/me/.local/bin" %)
                                   :path-env "/usr/bin:/Users/me/.local/bin"}))))))
  (testing "a link into a Jonquil.app is replaced"
    (is (= "/usr/local/bin/jonquil"
           (:link (plan {:existing {"/usr/local/bin/jonquil" [:link "/Old/Jonquil.app/Contents/Resources/bin/jonquil"]}})))))
  (testing "anything else there is left alone"
    (is (str/includes? (first (:refuse (plan {:existing {"/usr/local/bin/jonquil" :file}}))) "left alone"))
    (is (str/includes? (first (:refuse (plan {:existing {"/usr/local/bin/jonquil" [:link "/opt/other/jonquil"]}})))
                       "/opt/other/jonquil")))
  (testing "refusals"
    (is (str/includes? (first (:refuse (plan {:exe "/Users/me/dev/jonquil/jonquil"}))) "isn't running from a Jonquil.app"))
    (is (str/includes? (first (:refuse (plan {:exe nil}))) "couldn't tell where it is running from"))
    (is (str/includes? (first (:refuse (plan {:writable? (constantly false)}))) "wasn't installed"))))

(deftest removal-test
  (is (= ["/Users/me/.local/bin/jonquil"]
         (:remove (ci/removal {:home "/Users/me"
                               :existing {"/usr/local/bin/jonquil" [:link "/opt/other/jonquil"]
                                          "/Users/me/.local/bin/jonquil" [:link script]}})))
      "only a link into a Jonquil.app")
  (let [r (ci/removal {:home "/Users/me" :existing {"/usr/local/bin/jonquil" :file}})]
    (is (= [] (:remove r)))
    (is (str/includes? (first (:lines r)) "nothing was removed"))))

(defn- fake-app!
  "A Jonquil.app with the script under home. Returns its executable's path."
  [home]
  (let [app (str home "/Applications/Jonquil.app")]
    (io/make-parents (ci/script-of app))
    (spit (ci/script-of app) "#!/bin/sh\n")
    (str app "/Contents/MacOS/jonquil-bin")))

(deftest a-link-that-cant-be-removed-is-reported-test
  (let [home (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-cli-remove-")))
        exe (fake-app! home)
        bin (str home "/bin")
        link (str bin "/jonquil")
        opts {:exe exe :home home :path-env bin :dirs [bin]}]
    (.mkdirs (io/file bin))
    (is (str/includes? (first (ci/install! opts)) "Installed"))
    (.setWritable (io/file bin) false)
    (try
      (let [lines (ci/remove! opts)]
        (is (str/includes? (first lines) (str "Couldn't remove the jonquil command at " link)))
        (is (not-any? #(str/includes? % "Removed") lines))
        (is (java.nio.file.Files/isSymbolicLink (.toPath (io/file link))) "still there"))
      (finally (.setWritable (io/file bin) true)))
    (is (= [(str "; Removed the jonquil command at " link ".")] (ci/remove! opts)))))

(deftest install-and-remove-on-disk-test
  ;; the two folders stand in for /usr/local/bin and ~/.local/bin, so the
  ;; test never writes the real ones
  (let [home (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-cli-install-")))
        exe (fake-app! home)
        system-bin (str home "/system-bin")
        user-bin (str home "/.local/bin")
        opts {:exe exe :home home :path-env "/usr/bin" :dirs [system-bin user-bin]}
        link (str user-bin "/jonquil")
        target (fn [l] (str (java.nio.file.Files/readSymbolicLink (.toPath (io/file l)))))
        link? (fn [l] (java.nio.file.Files/isSymbolicLink (.toPath (io/file l))))]
    (.mkdirs (io/file system-bin))
    (testing "the first folder when it can be written"
      (let [lines (ci/install! opts)]
        (is (str/includes? (first lines) (str "Installed the jonquil command: " system-bin "/jonquil")))
        (is (= (ci/script-of (ci/bundle-of exe)) (target (str system-bin "/jonquil"))))
        (is (str/includes? (first (ci/remove! opts)) "Removed"))
        (is (not (link? (str system-bin "/jonquil"))))))
    (testing "the second, created, when the first can't be written"
      (.setWritable (io/file system-bin) false)
      (try
        (let [lines (ci/install! opts)]
          (is (str/includes? (first lines) (str "Installed the jonquil command: " link)))
          (is (some #(str/includes? % "isn't on your PATH yet") lines))
          (is (= (ci/script-of (ci/bundle-of exe)) (target link))))
        (testing "installing again replaces the link"
          (ci/install! opts)
          (is (= (ci/script-of (ci/bundle-of exe)) (target link))))
        (testing "remove deletes it"
          (is (str/includes? (first (ci/remove! opts)) "Removed"))
          (is (not (link? link))))
        (testing "a file that isn't ours stays"
          (spit link "mine")
          (is (str/includes? (first (ci/install! opts)) "left alone"))
          (is (str/includes? (first (ci/remove! opts)) "nothing was removed"))
          (is (= "mine" (slurp link))))
        (testing "a bundle without the script"
          (io/delete-file link)
          (io/delete-file (ci/script-of (ci/bundle-of exe)))
          (is (str/includes? (first (ci/install! opts)) "is missing from this Jonquil.app"))
          (is (not (.exists (io/file link)))))
        (finally (.setWritable (io/file system-bin) true))))))

(deftest own-executable-test
  (let [exe (ci/own-executable)]
    (is (or (nil? exe) (str/starts-with? exe "/")) "an absolute path or nothing")
    (is (zero? (:exit (p/sh "sh" "-c" "ps -o comm= -p $PPID"))) "ps answers on this OS")))
