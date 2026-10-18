(ns jonquil.app-linux-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [grenadine.xml :as xml]
            [jolt.process :as p]
            [app-linux :as al]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def ^:private bin "/home/dev/.local/share/jonquil/jonquil")

(deftest install-paths-test
  (is (= {:dir "/home/dev/.local/share/jonquil"
          :binary "/home/dev/.local/share/jonquil/jonquil"
          :desktop "/home/dev/.local/share/applications/net.b12n.jonquil.desktop"
          :icon "/home/dev/.local/share/icons/hicolor/256x256/apps/net.b12n.jonquil.png"
          :mime "/home/dev/.local/share/mime/packages/net.b12n.jonquil.xml"
          :mime-db "/home/dev/.local/share/mime"
          :apps-db "/home/dev/.local/share/applications"}
         (al/install-paths "/home/dev"))))

(deftest desktop-entry-test
  (testing "the exact text"
    (is (= (str "[Desktop Entry]\n"
                "Type=Application\n"
                "Name=Jonquil\n"
                "GenericName=Clojure IDE\n"
                "Comment=A DrRacket-style Clojure IDE written in Jolt\n"
                "Exec=" bin " %F\n"
                "Icon=net.b12n.jonquil\n"
                "Terminal=false\n"
                "StartupWMClass=net.b12n.jonquil\n"
                "Categories=Development;IDE;\n"
                "MimeType=text/x-clojure;application/edn;text/x-jank;\n"
                "X-Jonquil-Install=1\n"
                "X-Jonquil-Version=v1\n")
           (al/desktop-entry {:binary bin :version "v1"}))))
  (testing "a binary path with a space is quoted"
    (is (str/includes? (al/desktop-entry {:binary "/home/my dev/jonquil" :version "v1"})
                       "\nExec=\"/home/my dev/jonquil\" %F\n")))
  (testing "a path with a quote, a dollar and a backslash is escaped per the spec"
    (is (str/includes? (al/desktop-entry {:binary "/a b/\"$\\" :version "v1"})
                       "\nExec=\"/a b/\\\\\"\\\\$\\\\\\\\\" %F\n")))
  (testing "a literal percent is doubled"
    (is (str/includes? (al/desktop-entry {:binary "/a%b" :version "v1"}) "\nExec=/a%%b %F\n")))
  (testing "desktop-file-validate accepts it"
    (if (zero? (:exit (p/sh "sh" "-c" "command -v desktop-file-validate")))
      (doseq [b [bin "/home/my dev/jonquil"]]
        (let [f (io/file (temp-dirs/temp-dir! "jonquil-app-linux-test-") "net.b12n.jonquil.desktop")]
          (spit f (al/desktop-entry {:binary b :version "v1"}))
          (let [r (p/sh "desktop-file-validate" (.getPath f))]
            (is (zero? (:exit r)) (str b ": " (:out r) (:err r))))))
      (println "app-linux-test: skipped desktop-file-validate, not on PATH"))))

(defn- elements [el tag] (filter #(= tag (:tag %)) (:content el)))

(deftest mime-xml-test
  (let [root (xml/parse (al/mime-xml))
        types (elements root :mime-type)]
    (testing "it parses as XML"
      (is (= :mime-info (:tag root))))
    (testing "exactly three types, with their globs in order"
      (is (= [["text/x-clojure" ["*.clj" "*.cljc" "*.cljd" "*.cljs" "*.bb"]]
              ["application/edn" ["*.edn"]]
              ["text/x-jank" ["*.jank"]]]
             (mapv (fn [t] [(get-in t [:attrs :type])
                            (mapv #(get-in % [:attrs :pattern]) (elements t :glob))])
                   types))))
    (testing "a comment each"
      (is (= [["Clojure source"] ["EDN data"] ["jank source"]]
             (mapv #(vec (:content (first (elements % :comment)))) types))))
    (testing "each is a sub-class of text/plain"
      (is (every? #(= ["text/plain"] (mapv (fn [s] (get-in s [:attrs :type])) (elements % :sub-class-of)))
                  types)))))

(deftest replaceable-test
  (testing "nothing installed"
    (is (al/replaceable? nil)))
  (testing "our own entry"
    (is (al/replaceable? (al/desktop-entry {:binary bin :version "v1"}))))
  (testing "a foreign launcher"
    (is (not (al/replaceable? "[Desktop Entry]\nType=Application\nName=Other\n"))))
  (testing "one that only mentions X-Jonquil-Install=0"
    (is (not (al/replaceable? "[Desktop Entry]\nName=Jonquil\nX-Jonquil-Install=0\n"))))
  (testing "the marker as part of a longer line"
    (is (not (al/replaceable? "[Desktop Entry]\nComment=X-Jonquil-Install=1 maybe\n")))))

(defn- temp-home []
  (temp-dirs/temp-dir! "jonquil-app-linux-test-"))

(defn- fake-binary [home]
  (let [f (io/file home "built-jonquil")]
    (spit f "#!/bin/sh\necho fake\n")
    (.getPath f)))

(defn- run-install [home opts]
  (let [err (java.io.StringWriter.)
        status (atom nil)
        out (with-out-str
              (binding [*err* err]
                (reset! status (al/install! (merge {:home home :source (fake-binary home) :version "v1"
                                                    :which (constantly false)}
                                                   opts)))))]
    {:status @status :out out :err (str err)}))

(defn- run-uninstall [home opts]
  (let [err (java.io.StringWriter.)
        status (atom nil)
        out (with-out-str
              (binding [*err* err]
                (reset! status (al/uninstall! (merge {:home home :which (constantly false)} opts)))))]
    {:status @status :out out :err (str err)}))

(deftest install-test
  (let [home (temp-home)
        p (al/install-paths home)
        r (run-install home {})]
    (testing "succeeds and prints installed <binary>"
      (is (= 0 (:status r)))
      (is (str/includes? (:out r) (str "installed " (:binary p)))))
    (testing "the four files and the executable binary exist"
      (is (= (al/mime-xml) (slurp (:mime p))))
      (is (.isFile (io/file (:icon p))))
      (is (= (.length (io/file "resources/icon/jonquil-256.png")) (.length (io/file (:icon p)))))
      (is (= (al/desktop-entry {:binary (:binary p) :version "v1"}) (slurp (:desktop p))))
      (is (.canExecute (io/file (:binary p)))))
    (testing "missing database tools print a note and do not fail"
      (is (str/includes? (:out r) "note: update-mime-database not found, skipped"))
      (is (str/includes? (:out r) "note: update-desktop-database not found, skipped")))
    (testing "a second install over our own files succeeds"
      (is (= 0 (:status (run-install home {:version "v2"}))))
      (is (str/includes? (slurp (:desktop p)) "X-Jonquil-Version=v2")))))

(deftest install-refuses-foreign-launcher-test
  (let [home (temp-home)
        p (al/install-paths home)
        foreign "[Desktop Entry]\nType=Application\nName=Other\n"]
    (io/make-parents (:desktop p))
    (spit (:desktop p) foreign)
    (let [r (run-install home {})]
      (testing "non-zero status, message names the file"
        (is (= 1 (:status r)))
        (is (str/includes? (:err r) (:desktop p))))
      (testing "the launcher is unchanged and nothing else was written"
        (is (= foreign (slurp (:desktop p))))
        (is (not (.exists (io/file (:dir p)))))
        (is (not (.exists (io/file (:mime p)))))
        (is (not (.exists (io/file (:icon p)))))))))

(deftest uninstall-test
  (let [home (temp-home)
        p (al/install-paths home)
        sibling (io/file home ".local/share/other-app/keep.txt")]
    (testing "nothing installed"
      (let [r (run-uninstall home {})]
        (is (= 0 (:status r)))
        (is (str/includes? (:out r) "nothing to uninstall"))))
    (run-install home {})
    (io/make-parents sibling)
    (spit sibling "mine")
    (spit (io/file (:dir p) "extra.txt") "inside dir")
    (let [r (run-uninstall home {})]
      (testing "deletes the four files and the dir"
        (is (= 0 (:status r)))
        (is (str/includes? (:out r) "uninstalled"))
        (doseq [k [:dir :binary :desktop :icon :mime]]
          (is (not (.exists (io/file (get p k)))) (str k)))
        (is (str/includes? (:out r) "note: update-mime-database not found, skipped")))
      (testing "a sibling under ~/.local/share survives"
        (is (= "mine" (slurp sibling))))
      (testing "the shared parent directories survive"
        (is (.isDirectory (io/file (:apps-db p))))))))

(deftest uninstall-refuses-foreign-launcher-test
  (let [home (temp-home)
        p (al/install-paths home)]
    (run-install home {})
    (spit (:desktop p) "[Desktop Entry]\nName=Other\n")
    (let [r (run-uninstall home {})]
      (is (= 1 (:status r)))
      (is (str/includes? (:err r) (:desktop p)))
      (is (.exists (io/file (:binary p))))
      (is (.exists (io/file (:mime p))))
      (is (= "[Desktop Entry]\nName=Other\n" (slurp (:desktop p)))))))

(deftest install-reports-failed-move-test
  (let [home (temp-home)
        p (al/install-paths home)]
    (io/make-parents (:binary p))
    (.mkdirs (io/file (:binary p) "sub"))
    (spit (io/file (:binary p) "sub" "f") "x")
    (let [r (run-install home {})]
      (is (= 1 (:status r)))
      (is (not (str/includes? (:out r) "installed")))
      (is (str/includes? (:err r) "install failed: move"))
      (is (not (.exists (io/file (str (:binary p) ".new"))))))))

(deftest install-reports-write-exception-test
  (let [home (temp-home)
        p (al/install-paths home)]
    ;; a plain file where the install dir must be: the binary copy throws
    (io/make-parents (:dir p))
    (spit (:dir p) "in the way")
    (let [r (run-install home {})]
      (is (= 1 (:status r)))
      (is (str/includes? (:err r) "app-linux: install failed:"))
      (is (str/includes? (:err r) (:desktop p)) "names the files already written")
      (is (not (str/includes? (:out r) "installed"))))))

(deftest uninstall-reports-failed-delete-test
  (let [home (temp-home)
        p (al/install-paths home)
        share (io/file home ".local/share")]
    (run-install home {})
    (.setWritable share false)
    (try
      (let [r (run-uninstall home {})]
        (is (= 1 (:status r)))
        (is (not (str/includes? (:out r) "uninstalled")))
        (is (str/includes? (:err r) (:dir p))))
      (finally (.setWritable share true)))))
