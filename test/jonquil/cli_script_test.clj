(ns jonquil.cli-script-test
  "resources/cli/jonquil run with sh against a throwaway Jonquil.app: its
  launcher and an `open` first on PATH both write the arguments they got to a
  file, one per line."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.process :as p]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- recorder
  "A script that writes its name, then each argument, to out."
  [out label]
  ;; printf, not echo: dash's echo takes -n as its own option and prints nothing
  (str "#!/bin/sh\nprintf '%s\\n' " label " > '" out "'\nfor a in \"$@\"; do printf '%s\\n' \"$a\" >> '" out "'; done\n"))

(defn- executable! [path text]
  (io/make-parents path)
  (spit path text)
  (.setExecutable (io/file path) true))

(defn- fixture
  "{:root :app :script :link :out :work}: the app with the script inside it,
  a symlink to the script reached through a second, relative symlink, and a
  work folder holding a.clj and sub/."
  []
  (let [root (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-cli-test-")))
        app (str root "/Apps/Jonquil.app")
        script (str app "/Contents/Resources/bin/jonquil")
        out (str root "/out.txt")
        work (str root "/work dir")]
    (executable! script (slurp "resources/cli/jonquil"))
    (executable! (str app "/Contents/MacOS/Jonquil") (recorder out "launcher"))
    (executable! (str root "/fakebin/open") (recorder out "open"))
    (.mkdirs (io/file root "links"))
    (.mkdirs (io/file work "sub"))
    (spit (str work "/a.clj") "")
    (is (zero? (:exit (p/sh "ln" "-s" script (str root "/links/real")))))
    (is (zero? (:exit (p/sh "ln" "-s" "real" (str root "/links/jonquil")))))
    {:root root :app app :script script :link (str root "/links/jonquil") :out out :work work}))

(defn- run
  "Runs the script through its symlink from the work folder with args (one
  shell-quoted string). {:exit :err :got}, :got being the recorder's lines."
  [{:keys [root link out work]} args]
  (io/delete-file out true)
  (let [r (p/sh "sh" "-c" (str "cd '" work "' && PATH='" root "/fakebin':\"$PATH\" '" link "' " args))]
    {:exit (:exit r) :err (str (:err r))
     :got (when (.isFile (io/file out)) (str/split-lines (slurp out)))}))

(deftest paths-go-to-open-test
  (let [{:keys [app work] :as fx} (fixture)]
    ;; a new window gets its paths after --args, so it starts in that
    ;; folder with that folder's own tabs
    (testing "no argument opens the current folder in a new window"
      (is (= ["open" "-n" "-a" app "--args" work] (:got (run fx "")))))
    (testing "a folder among the paths asks for a new window; relative paths become absolute, and a space survives"
      (is (= ["open" "-n" "-a" app "--args" (str work "/a.clj") (str work "/sub") work]
             (:got (run fx "a.clj sub .")))))
    (testing "files alone go to the running Jonquil"
      (is (= ["open" "-a" app (str work "/a.clj")] (:got (run fx "a.clj"))))
      (is (= ["open" "-a" app (str work "/a.clj")] (:got (run fx (str "'" work "/a.clj'"))))
          "an absolute path stays as it is"))))

(deftest cdpath-doesnt-redirect-a-relative-folder-test
  ;; with CDPATH set, a plain `cd sub` looks there first and prints the
  ;; folder it found, so the path handed to open was another project's
  (let [{:keys [root app work link out]} (fixture)
        elsewhere (str root "/elsewhere")]
    (.mkdirs (io/file elsewhere "sub"))
    (io/delete-file out true)
    (is (zero? (:exit (p/sh "sh" "-c" (str "cd '" work "' && CDPATH='" elsewhere "' PATH='" root "/fakebin':\"$PATH\" '" link "' sub a.clj")))))
    (is (= ["open" "-n" "-a" app "--args" (str work "/sub") (str work "/a.clj")] (str/split-lines (slurp out))))))

(deftest a-file-in-the-root-folder-test
  ;; dirname gives /, and "//hosts" would be a different path on some systems
  (let [{:keys [app] :as fx} (fixture)]
    (is (= ["open" "-a" app "/etc/hosts"] (:got (run fx "/etc/hosts"))))
    (when (.exists (io/file "/etc"))
      (is (= ["open" "-n" "-a" app "--args" "/etc"] (:got (run fx "/etc/")))))))

(deftest a-missing-path-is-an-error-test
  (let [fx (fixture)
        r (run fx "a.clj nope.clj")]
    (is (= 1 (:exit r)))
    (is (= "jonquil: nope.clj: no such file or folder\n" (:err r)))
    (is (nil? (:got r)) "nothing was opened")))

(deftest a-flag-runs-the-launcher-test
  (let [fx (fixture)]
    (is (= ["launcher" "--version"] (:got (run fx "--version"))))
    (is (= ["launcher" "a.clj" "--no-plugins"] (:got (run fx "a.clj --no-plugins")))
        "every argument goes along, untouched")))

(deftest the-dev-bundle-has-no-launcher-test
  ;; jolt app:bundle puts the binary itself at Contents/MacOS/jonquil
  (let [{:keys [app out] :as fx} (fixture)]
    (io/delete-file (str app "/Contents/MacOS/Jonquil"))
    (executable! (str app "/Contents/MacOS/jonquil") (recorder out "binary"))
    (is (= ["binary" "--version"] (:got (run fx "--version"))))))
