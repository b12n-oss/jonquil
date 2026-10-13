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
  (is (= {:dir "/home/dev/.local/share"
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
      (is (= [["text/x-clojure" ["*.clj" "*.cljc" "*.cljd" "*.bb"]]
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
