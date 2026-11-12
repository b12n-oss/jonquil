(ns app-release
  "Builds a self-contained Jonquil.app and its zip for macOS (Apple
  Silicon): jolt -M:bundle -m app-release <release|check [app]>, which the
  jolt tasks app:release and app:release-check run.

  The GTK libraries deps.edn's :jolt/native names, and every non-system
  library they load, are copied into Contents/Frameworks and relinked to
  find each other by @rpath. Contents/MacOS/Jonquil is a launcher that puts
  Frameworks on DYLD_LIBRARY_PATH and execs jonquil-bin: Jolt joins an
  @executable_path candidate to the project directory, so the binary can't
  name the bundle's copies itself. The binary is jonquil-bin, never
  jonquil: the filesystem ignores case, so it would be the launcher.
  app:bundle and app:install stay the Homebrew-linked local build."
  (:require [app-bundle]
            [clojure.string :as str]))

;; --- reading otool's output ------------------------------------------------------

(defn- system-lib? [path]
  (or (str/starts-with? path "/usr/lib/") (str/starts-with? path "/System/")))

(defn otool-refs
  "The libraries `otool -L` text names, less its header line, the library's
  own id (own-id, `otool -D`'s answer) and the system's."
  [text own-id]
  (->> (rest (str/split-lines text))
       (map #(str/trim (first (str/split % #" \(compatibility"))))
       (remove #(or (str/blank? %) (= own-id %) (system-lib? %)))
       vec))

(defn otool-minos
  "The minos of `otool -l` text's LC_BUILD_VERSION, or nil."
  [text]
  (second (re-find #"(?m)^\s*minos\s+(\S+)" text)))

(defn- version-key
  "v's numeric parts, padded to four so vectors compare part by part."
  [v]
  (let [ps (mapv parse-long (re-seq #"\d+" v))]
    (into ps (repeat (- 4 (count ps)) 0))))

(defn max-version
  "The highest of dotted versions vs, compared part by part as numbers."
  [vs]
  (last (sort-by version-key vs)))

;; --- the closure ------------------------------------------------------------------

(defn- base-name [path] (last (str/split path #"/")))

(defn- parent [path] (subs path 0 (max 0 (str/last-index-of path "/"))))

(defn closure-plan
  "The libraries to bundle, from roots, as [{:src :name :refs}] in
  discovery order, each base name once. refs-of answers a library's refs
  (otool-refs); an @ ref (@rpath, @loader_path) resolves next to the
  library that names it."
  [roots refs-of]
  (loop [queue (vec roots) seen #{} plan []]
    (if-let [src (first queue)]
      (let [name (base-name src)]
        (if (seen name)
          (recur (subvec queue 1) seen plan)
          (let [refs (vec (refs-of src))
                deps (map #(if (str/starts-with? % "@") (str (parent src) "/" (base-name %)) %) refs)]
            (recur (into (subvec queue 1) deps) (conj seen name)
                   (conj plan {:src src :name name :refs refs})))))
      plan)))

(defn lzo?
  "True when names (the bundled libraries) hold lzo, which is GPL."
  [names]
  (boolean (some #(str/starts-with? % "liblzo2") names)))

;; --- what the bundle carries --------------------------------------------------------

(defn launcher-text
  "Contents/MacOS/Jonquil: puts the bundle's Frameworks first on
  DYLD_LIBRARY_PATH and runs jonquil-bin, every path quoted."
  []
  (str "#!/bin/sh\n"
       "# Jonquil.app's launcher: load the bundled libraries, then run Jonquil.\n"
       "here=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n"
       "export DYLD_LIBRARY_PATH=\"$here/../Frameworks${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}\"\n"
       "exec \"$here/jonquil-bin\" \"$@\"\n"))

(defn sources-txt
  "licenses/SOURCES.txt for formulae ({:name :version :license :url
  :license-files})."
  [formulae]
  (str "Jonquil.app bundles these libraries unmodified, as separate dynamic\n"
       "libraries, built by Homebrew. Each keeps its own licence. The source of\n"
       "each, at exactly the version bundled, is at the URL given.\n\n"
       (str/join "\n"
                 (for [{:keys [name version license url license-files]} formulae]
                   (str name " " version " (" license ")\n"
                        "  source: " url "\n"
                        "  licence files: "
                        (if (seq license-files)
                          (str/join ", " (map #(str "licenses/" name "/" %) license-files))
                          "none found in the Homebrew keg")
                        "\n")))))
