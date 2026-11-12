(ns jonquil.app-bundle-test
  (:require [app-bundle :as ab]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.process :as p]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- plist-file! [text]
  (let [f (io/file (temp-dirs/temp-dir! "jonquil-app-bundle-test-") "Info.plist")]
    (spit f text)
    (.getPath f)))

(defn- extract
  "The raw value of keypath in the plist at path, via plutil; nil if absent."
  [path keypath]
  (let [r (p/sh "plutil" "-extract" keypath "raw" "-o" "-" path)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defmacro ^:private when-plutil
  "Runs body where plutil exists (macOS); elsewhere asserts nothing and
  prints one line."
  [& body]
  `(if (= "Mac OS X" (System/getProperty "os.name"))
     (do ~@body)
     (println "app-bundle-test: skipped, plutil is macOS-only")))

(def ^:private base {:version "v0.1.0-3-gabc123" :build "57" :doc-extensions ["clj" "cljc" "cljd" "jank" "edn" "bb"]})

(deftest info-plist-keys-test
  (when-plutil
   (let [path (plist-file! (ab/info-plist base))]
     (is (zero? (:exit (p/sh "plutil" "-lint" path))))
     (doseq [[k v] {"CFBundleName" "Jonquil"
                    "CFBundleDisplayName" "Jonquil"
                    "CFBundleIdentifier" "net.b12n.jonquil"
                    "CFBundleExecutable" "jonquil"
                    "CFBundleIconFile" "Jonquil"
                    "CFBundlePackageType" "APPL"
                    "CFBundleVersion" "57"
                    "CFBundleShortVersionString" "v0.1.0-3-gabc123"
                    "LSMinimumSystemVersion" "13.0"
                    "NSHighResolutionCapable" "true"
                    "CFBundleDocumentTypes.0.CFBundleTypeName" "Clojure source"
                    "CFBundleDocumentTypes.0.CFBundleTypeRole" "Editor"
                    "CFBundleDocumentTypes.0.LSHandlerRank" "Alternate"
                    "CFBundleDocumentTypes.1.CFBundleTypeName" "Folder"
                    "CFBundleDocumentTypes.1.CFBundleTypeRole" "Viewer"
                    "CFBundleDocumentTypes.1.LSHandlerRank" "Alternate"
                    "CFBundleDocumentTypes.1.LSItemContentTypes" "1"
                    "CFBundleDocumentTypes.1.LSItemContentTypes.0" "public.folder"}]
       (testing k (is (= v (extract path k)))))
     (testing "document type extensions keep their order"
       (is (= "6" (extract path "CFBundleDocumentTypes.0.CFBundleTypeExtensions")) "count")
       (is (= ["clj" "cljc" "cljd" "jank" "edn" "bb"]
              (mapv #(extract path (str "CFBundleDocumentTypes.0.CFBundleTypeExtensions." %)) (range 6)))))
     (testing "exactly two document types"
       (is (nil? (extract path "CFBundleDocumentTypes.2")))))))

(deftest info-plist-executable-and-floor-test
  (when-plutil
   (let [release (plist-file! (ab/info-plist (assoc base :executable "Jonquil" :min-macos "26.0")))
         plain (plist-file! (ab/info-plist base))]
     (is (= "Jonquil" (extract release "CFBundleExecutable")))
     (is (= "26.0" (extract release "LSMinimumSystemVersion")))
     (is (= "jonquil" (extract plain "CFBundleExecutable")) "app:bundle's plist is unchanged")
     (is (= "13.0" (extract plain "LSMinimumSystemVersion"))))))

(deftest info-plist-dirty-version-test
  (when-plutil
   (let [path (plist-file! (ab/info-plist (assoc base :version "abc1234-dirty")))]
     (is (= "57" (extract path "CFBundleVersion")))
     (is (= "abc1234-dirty" (extract path "CFBundleShortVersionString"))))))

(deftest info-plist-escapes-test
  (when-plutil
   (let [path (plist-file! (ab/info-plist (assoc base :version "a&b<c")))]
     (is (zero? (:exit (p/sh "plutil" "-lint" path))))
     (is (= "a&b<c" (extract path "CFBundleShortVersionString"))))))

(deftest replaceable-test
  (testing "a Jonquil bundle's plist"
    (is (ab/replaceable? (ab/info-plist base))))
  (testing "a foreign bundle's plist"
    (is (not (ab/replaceable?
              (str "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict>"
                   "<key>CFBundleIdentifier</key><string>com.example.other</string>"
                   "</dict></plist>")))))
  (testing "an id that only contains ours"
    (is (not (ab/replaceable?
              (str "<plist><dict><key>CFBundleIdentifier</key>"
                   "<string>x.net.b12n.jonquil.y</string></dict></plist>")))))
  (testing "no plist at all"
    (is (not (ab/replaceable? nil)))
    (is (not (ab/replaceable? "")))))

(deftest the-real-bundle-offers-every-dialects-extension
  ;; Pure, so it runs on every OS (the plist checks above need plutil).
  (is (= ["clj" "cljc" "cljd" "cljs" "jank" "edn" "bb"] ab/doc-extensions)
      "ClojureScript's .cljs is offered too, since Jonquil has a cljs dialect"))
