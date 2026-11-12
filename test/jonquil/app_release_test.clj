(ns jonquil.app-release-test
  (:require [app-release :as ar]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest otool-refs-drops-the-header-own-id-and-system-test
  (let [t (str "/opt/homebrew/lib/libgtk-4.1.dylib:\n"
               "\t/opt/homebrew/opt/gtk4/lib/libgtk-4.1.dylib (compatibility version 2401.0.0, current version 2401.1.0)\n"
               "\t/opt/homebrew/opt/glib/lib/libglib-2.0.0.dylib (compatibility version 9001.0.0, current version 9001.1.0)\n"
               "\t@rpath/libfoo.1.dylib (compatibility version 1.0.0, current version 1.0.0)\n"
               "\t/usr/lib/libSystem.B.dylib (compatibility version 1.0.0, current version 1351.0.0)\n"
               "\t/System/Library/Frameworks/Cocoa.framework/Versions/A/Cocoa (compatibility version 1.0.0, current version 24.0.0)\n")]
    (is (= ["/opt/homebrew/opt/glib/lib/libglib-2.0.0.dylib" "@rpath/libfoo.1.dylib"]
           (ar/otool-refs t "/opt/homebrew/opt/gtk4/lib/libgtk-4.1.dylib")))))

(deftest otool-minos-and-max-version-test
  (is (= "26.0" (ar/otool-minos "Load command 9\n      cmd LC_BUILD_VERSION\n  cmdsize 32\n platform 1\n    minos 26.0\n      sdk 26.2\n")))
  (is (nil? (ar/otool-minos "Load command 1\n      cmd LC_SEGMENT_64\n")))
  (is (= "26.0" (ar/max-version ["11.0" "26.0" "14.2"])))
  (is (= "10.15" (ar/max-version ["10.9" "10.15"]))))

(deftest closure-plan-follows-refs-once-test
  (let [refs {"/c/gtk/libgtk.dylib" ["/c/glib/libglib.dylib" "@rpath/libintl.8.dylib"]
              "/c/glib/libglib.dylib" ["/c/gettext/libintl.8.dylib"]
              "/c/gtk/libintl.8.dylib" []
              "/c/gettext/libintl.8.dylib" []}
        plan (ar/closure-plan ["/c/gtk/libgtk.dylib" "/c/glib/libglib.dylib"] #(get refs % []))]
    (is (= ["libgtk.dylib" "libglib.dylib" "libintl.8.dylib"] (mapv :name plan))
        "discovery order, each base name once")
    (is (= "/c/gtk/libintl.8.dylib" (:src (nth plan 2)))
        "an @rpath ref resolves next to the library that names it")))

(deftest launcher-quotes-every-path-test
  (let [t (ar/launcher-text)]
    (is (str/starts-with? t "#!/bin/sh\n"))
    (is (str/includes? t "export DYLD_LIBRARY_PATH=\"$here/../Frameworks${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}\""))
    (is (str/includes? t "exec \"$here/jonquil-bin\" \"$@\""))
    (is (str/includes? t "here=\"$(cd \"$(dirname \"$0\")\" && pwd)\""))))

(deftest sources-txt-names-each-formula-test
  (let [t (ar/sources-txt [{:name "gtk4" :version "4.24.1" :license "LGPL-2.1-or-later"
                            :url "https://download.gnome.org/sources/gtk/4.24/gtk-4.24.1.tar.xz"
                            :license-files ["COPYING"]}
                           {:name "lzo" :version "2.10" :license "GPL-2.0-or-later"
                            :url "https://www.oberhumer.com/opensource/lzo/download/lzo-2.10.tar.gz"
                            :license-files []}])]
    (is (str/includes? t "gtk4 4.24.1 (LGPL-2.1-or-later)\n  source: https://download.gnome.org/sources/gtk/4.24/gtk-4.24.1.tar.xz\n  licence files: licenses/gtk4/COPYING\n"))
    (is (str/includes? t "lzo 2.10 (GPL-2.0-or-later)\n  source: https://www.oberhumer.com/opensource/lzo/download/lzo-2.10.tar.gz\n  licence files: none found in the Homebrew keg\n"))))

(deftest lzo-test
  (is (true? (ar/lzo? ["libgtk-4.1.dylib" "liblzo2.2.dylib"])))
  (is (false? (ar/lzo? ["libgtk-4.1.dylib"]))))
