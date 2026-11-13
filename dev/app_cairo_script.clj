(ns app-cairo-script
  "cairo's script interpreter, built without lzo, for the release bundle.

  Homebrew's libgtk-4 links libcairo-script-interpreter, and Homebrew's
  build of it imports lzo (GPL-2.0-or-later). cairo makes lzo optional,
  so this builds just that one library from the pinned cairo source with
  -Dlzo=disabled (and X11 off, which the interpreter doesn't need and
  which didn't compile here), caches it, and app-release bundles it in
  place of Homebrew's. It links Homebrew's libcairo of the same version:
  all 162 cairo functions it imports were checked to exist there
  (2026-11-13). Needs meson and ninja (brew install meson)."
  (:require [clojure.string :as str]
            [jolt.process :as p]))

(def PIN
  "The cairo source the interpreter is built from: Homebrew's cairo must be
  this version (revision aside)."
  {:version "1.18.6"
   :url "https://cairographics.org/releases/cairo-1.18.6.tar.xz"
   :sha256 "1c767308174337a74694da0f3ec069c271452163a1ef4540964c50c301f157d4"})

(def ^:private MESON-OPTS
  ["-Dlzo=disabled" "-Dtests=disabled" "-Dgtk_doc=false" "-Dxlib=disabled" "-Dxcb=disabled"
   "-Dfreetype=enabled" "-Dfontconfig=enabled" "--buildtype=release"])

(def ^:private TARGET "util/cairo-script/libcairo-script-interpreter.2.dylib")

(defn keg-mismatch
  "A message when Homebrew's cairo keg version kv isn't PIN's version (an
  _N revision is the same source), else nil."
  [kv]
  (when-not (= (:version PIN) (str/replace (str kv) #"_\d+$" ""))
    (str "Homebrew's cairo is " kv " but the bundled script interpreter is pinned to cairo "
         (:version PIN) ": update app-cairo-script/PIN (version, url, sha256)")))

(defn cache-path
  "Where the built interpreter is kept between releases."
  []
  (let [root (or (System/getenv "XDG_CACHE_HOME") (str (System/getProperty "user.home") "/.cache"))]
    (str root "/jonquil/cairo-script/" (:version PIN) "-nolzo/libcairo-script-interpreter.2.dylib")))

(defn- fail! [msg] (throw (ex-info (str "app-cairo-script: " msg) {})))

(defn- run!*
  "Runs argv in dir; fails with what and the tail of the output when it fails."
  [what dir argv]
  (let [{:keys [exit out err]} (apply p/sh {:dir dir} argv)]
    (when-not (zero? exit)
      (fail! (str what " failed (exit " exit "):\n"
                  (str/join "\n" (take-last 12 (str/split-lines (str out err)))))))
    out))

(defn- keg-version []
  (let [real (str/trim (:out (p/sh "realpath" "/opt/homebrew/opt/cairo")))]
    (or (second (re-find #"/Cellar/cairo/([^/]+)$" real))
        (fail! "Homebrew's cairo isn't installed (brew install cairo)"))))

(defn- build!
  "Builds the interpreter into a temp directory and moves it onto the
  cache path, so a killed build never leaves a half-written cache."
  []
  (doseq [tool ["meson" "ninja"]]
    (when-not (zero? (:exit (p/sh "/bin/sh" "-c" (str "command -v " tool))))
      (fail! (str tool " isn't on PATH: brew install meson"))))
  (when-let [m (keg-mismatch (keg-version))] (fail! m))
  (let [tmp (str/trim (:out (p/sh "mktemp" "-d" "-t" "jonquil-cairo")))
        tarball (str tmp "/cairo.tar.xz")
        src (str tmp "/cairo-" (:version PIN))
        out (cache-path)]
    (try
      (println (str "cairo-script: downloading cairo " (:version PIN)))
      (run!* "downloading cairo" tmp ["/usr/bin/env" "-u" "HTTPS_PROXY" "-u" "https_proxy" "-u" "HTTP_PROXY" "-u" "http_proxy"
                                      "curl" "-fsSL" "-o" tarball (:url PIN)])
      (let [got (first (str/split (str/trim (run!* "hashing cairo" tmp ["shasum" "-a" "256" tarball])) #"\s+"))]
        (when-not (= got (:sha256 PIN))
          (fail! (str "cairo's tarball SHA-256 is " got ", not the pinned " (:sha256 PIN)))))
      (run!* "unpacking cairo" tmp ["tar" "xf" tarball])
      (println "cairo-script: building libcairo-script-interpreter (-Dlzo=disabled)")
      (run!* "meson setup" src (into ["meson" "setup" "build"] MESON-OPTS))
      (run!* "ninja" src ["ninja" "-C" "build" TARGET])
      (let [built (str src "/build/" TARGET)]
        (when (str/includes? (run!* "nm" tmp ["nm" "-u" built]) "lzo")
          (fail! "the built interpreter still imports lzo"))
        (run!* "creating the cache" tmp ["mkdir" "-p" (subs out 0 (str/last-index-of out "/"))])
        (run!* "caching the interpreter" tmp ["cp" built (str out ".tmp")])
        (run!* "caching the interpreter" tmp ["mv" "-f" (str out ".tmp") out]))
      (println (str "cairo-script: cached " out))
      out
      (finally (p/sh "rm" "-rf" tmp)))))

(defn interpreter-path
  "The lzo-free libcairo-script-interpreter.2.dylib, built (once) when the
  cache doesn't have it."
  []
  (let [out (cache-path)]
    (if (.isFile (java.io.File. out)) out (build!))))
