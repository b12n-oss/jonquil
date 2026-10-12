(ns jonquil.app-bundle-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.process :as p]
            [app-bundle :as ab]
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

(def ^:private base {:version "v0.1.0-3-gabc123" :doc-extensions ["clj" "cljc" "cljd" "jank" "edn" "bb"]})

(deftest info-plist-keys-test
  (let [path (plist-file! (ab/info-plist base))]
    (is (zero? (:exit (p/sh "plutil" "-lint" path))))
    (doseq [[k v] {"CFBundleName" "Jonquil"
                   "CFBundleDisplayName" "Jonquil"
                   "CFBundleIdentifier" "net.b12n.jonquil"
                   "CFBundleExecutable" "jonquil"
                   "CFBundleIconFile" "Jonquil"
                   "CFBundlePackageType" "APPL"
                   "CFBundleVersion" "v0.1.0-3-gabc123"
                   "CFBundleShortVersionString" "v0.1.0-3-gabc123"
                   "LSMinimumSystemVersion" "13.0"
                   "NSHighResolutionCapable" "true"
                   "CFBundleDocumentTypes.0.CFBundleTypeName" "Clojure source"
                   "CFBundleDocumentTypes.0.CFBundleTypeRole" "Editor"
                   "CFBundleDocumentTypes.0.LSHandlerRank" "Alternate"}]
      (testing k (is (= v (extract path k)))))
    (testing "document type extensions keep their order"
      (is (= "6" (extract path "CFBundleDocumentTypes.0.CFBundleTypeExtensions" )) "count")
      (is (= ["clj" "cljc" "cljd" "jank" "edn" "bb"]
             (mapv #(extract path (str "CFBundleDocumentTypes.0.CFBundleTypeExtensions." %)) (range 6)))))
    (testing "exactly one document type"
      (is (nil? (extract path "CFBundleDocumentTypes.1"))))))

(deftest info-plist-dirty-version-test
  (let [path (plist-file! (ab/info-plist (assoc base :version "abc1234-dirty")))]
    (is (= "abc1234-dirty" (extract path "CFBundleVersion")))
    (is (= "abc1234-dirty" (extract path "CFBundleShortVersionString")))))

(deftest info-plist-escapes-test
  (let [path (plist-file! (ab/info-plist (assoc base :version "a&b<c")))]
    (is (zero? (:exit (p/sh "plutil" "-lint" path))))
    (is (= "a&b<c" (extract path "CFBundleVersion")))))

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
