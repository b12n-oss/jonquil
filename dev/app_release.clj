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
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]))

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

(defn otool-rpaths
  "The LC_RPATH paths in `otool -l` text, in order."
  [text]
  (->> (str/split text #"Load command \d+")
       (filter #(re-find #"cmd LC_RPATH" %))
       (keep #(second (re-find #"path (.+?) \(offset" %)))
       vec))

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
  library that names it. substitutes (base name -> path) bundles that
  path in place of the library of that name, following its refs, an @ ref
  resolving next to the library it replaces."
  ([roots refs-of] (closure-plan roots refs-of {}))
  ([roots refs-of substitutes]
   (loop [queue (vec roots) seen #{} plan []]
     (if-let [found (first queue)]
       (let [name (base-name found)]
         (if (seen name)
           (recur (subvec queue 1) seen plan)
           (let [src (get substitutes name found)
                 refs (vec (refs-of src))
                 deps (map #(if (str/starts-with? % "@") (str (parent found) "/" (base-name %)) %) refs)]
             (recur (into (subvec queue 1) deps) (conj seen name)
                    (conj plan {:src src :name name :refs refs})))))
       plan))))

(defn gtk-schemas
  "GTK's own GSettings schema files among names (a keg's
  share/glib-2.0/schemas), sorted: org.gtk.gtk4.*, never the demo's."
  [names]
  (vec (sort (filter #(re-matches #"org\.gtk\.gtk4\..*\.gschema\.xml" %) names))))

(defn missing-natives
  "The :name of each :jolt/native entry with a :darwin list but none of
  its /opt/homebrew candidates in exists? (a set or fn of paths): the
  build Mac lacks that Homebrew library."
  [entries exists?]
  (vec (keep (fn [{:keys [name darwin]}]
               (when (and (seq darwin)
                          (not-any? #(and (str/starts-with? % "/opt/homebrew/") (exists? %)) darwin))
                 name))
             entries)))

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
       "# Jonquil drops DYLD_LIBRARY_PATH again once it's loaded, so git, node\n"
       "# or jank it starts load their own libraries (jonquil.bundle-env)\n"
       "export JONQUIL_BUNDLE_FRAMEWORKS=\"$here/../Frameworks\"\n"
       "# GTK's GSettings schemas (a file dialog reads them), compiled into the\n"
       "# bundle; searched before the system's, which it doesn't replace\n"
       "export GSETTINGS_SCHEMA_DIR=\"$here/../Resources/share/glib-2.0/schemas${GSETTINGS_SCHEMA_DIR:+:$GSETTINGS_SCHEMA_DIR}\"\n"
       "exec \"$here/jonquil-bin\" \"$@\"\n"))

(defn- older?
  "True when keg version (Homebrew's revision suffix, _N, aside) isn't
  the formula's stable version."
  [version stable]
  (boolean (and stable (not= stable (str/replace version #"_\d+$" "")))))

(defn outdated
  "The names of formulae whose bundled :version isn't Homebrew's :stable."
  [formulae]
  (vec (keep (fn [{:keys [name version stable]}] (when (older? version stable) name)) formulae)))

(defn sources-txt
  "licenses/SOURCES.txt for formulae ({:name :version :stable :license :url
  :license-files}). :url is Homebrew's source for :stable, its formula's
  current version, which an older keg (:version) doesn't match: said so."
  [formulae]
  (str "Jonquil.app bundles these libraries, built by Homebrew, as separate dynamic\n"
       "libraries, with only their install names and rpaths changed (install_name_tool) and re-signed.\n"
       "Each keeps its own licence. The source of each is at the URL given, at the version given.\n\n"
       (str/join "\n"
                 (for [{:keys [name version stable license url license-files note]} formulae]
                   (str name " " version " (" license ")\n"
                        "  source: " url
                        (when (older? version stable)
                          (str " (for " stable ", newer than the " version " bundled: brew upgrade " name
                               " before a public build)"))
                        "\n"
                        "  licence files: "
                        (if (seq license-files)
                          (str/join ", " (map #(str "licenses/" name "/" %) license-files))
                          "none found in the Homebrew keg")
                        "\n"
                        (when note (str "  " note "\n")))))))

;; --- building it -------------------------------------------------------------------

(def ^:private app "target/Jonquil.app")
(def ^:private contents (str app "/Contents"))
(def ^:private frameworks (str contents "/Frameworks"))
(def ^:private licenses (str contents "/Resources/licenses"))
(def ^:private bin (str contents "/MacOS/jonquil-bin"))
(def ^:private launcher (str contents "/MacOS/Jonquil"))

(defn- fail! [msg]
  (binding [*out* *err*] (println (str "app-release: " msg)))
  (System/exit 1))

(defn- warn! [msg]
  (binding [*out* *err*] (println (str "app-release: WARNING: " msg))))

(defn- sh!
  "Runs args, answering stdout; fails the release naming the command when
  it fails."
  [& args]
  (let [{:keys [exit out err]} (apply p/sh (map str args))]
    (when-not (zero? exit)
      (fail! (str (str/join " " args) " failed:\n" err)))
    out))

(defn- real-path [path] (str/trim (sh! "realpath" path)))

(defn- refs-of [lib]
  (otool-refs (sh! "otool" "-L" lib) (last (str/split-lines (str/trim (sh! "otool" "-D" lib))))))

(defn- on-disk? [path] (.exists (io/file path)))

(defn- roots
  "The /opt/homebrew candidates deps.edn's :jolt/native gives for macOS,
  those on disk; fails when an entry has none (no Homebrew GTK here)."
  []
  (let [entries (:jolt/native (edn/read-string (slurp "deps.edn")))]
    (when-let [missing (seq (missing-natives entries on-disk?))]
      (fail! (str "no Homebrew library on this Mac for " (str/join ", " missing)
                  " (brew install gtk4); the bundle would have no GTK")))
    (->> entries
         (mapcat :darwin)
         (filter #(and (str/starts-with? % "/opt/homebrew/") (on-disk? %)))
         (map real-path))))

(defn- bundle-lib!
  "Copies plan entry {:src :name :refs} into Frameworks, relinked to @rpath."
  [{:keys [src name refs]}]
  (let [dest (str frameworks "/" name)]
    (sh! "cp" (real-path src) dest)
    (sh! "chmod" "u+w" dest)
    (sh! "install_name_tool" "-id" (str "@rpath/" name) dest)
    (doseq [r refs]
      (sh! "install_name_tool" "-change" r (str "@rpath/" (base-name r)) dest))
    ;; every rpath Homebrew left (a Cellar path, @loader_path/../lib) goes,
    ;; then exactly @loader_path: the copies find each other beside them
    (doseq [r (otool-rpaths (sh! "otool" "-l" dest))]
      (sh! "install_name_tool" "-delete_rpath" r dest))
    (sh! "install_name_tool" "-add_rpath" "@loader_path" dest)))

(defn- formula-of
  "[name version] of the Homebrew keg a library's real path is in, or nil."
  [path]
  (when-let [[_ n v] (re-find #"^/opt/homebrew/Cellar/([^/]+)/([^/]+)/" path)] [n v]))

(defn- licence-files
  "The top-level licence files of keg dir: LICENSE, COPYING and the like,
  or a file named for its licence (glib's LGPL-2.1-or-later.txt)."
  [dir]
  (->> (.listFiles (io/file dir))
       (filter #(and (.isFile %)
                     (re-find #"(?i)^(licen[cs]e|copying|copyright|notice)|^[A-Za-z0-9.+-]*(GPL|MIT|BSD|MPL|Apache)[A-Za-z0-9.+-]*\.txt$"
                              (.getName %))))
       (map #(.getName %))
       sort vec))

(defn- write-licences!
  "licenses/<formula>/ with each bundled formula's licence files, then
  SOURCES.txt and NOTICE."
  [plan]
  (let [kegs (->> plan (keep (comp formula-of real-path :src)) distinct (sort-by first))
        by-name (into {} (map (fn [f] [(get f "name") f]))
                      (get (json/read-str (apply sh! "brew" "info" "--json=v2" (map first kegs))) "formulae"))
        formulae (doall
                  (for [[n v] kegs
                        :let [dir (str "/opt/homebrew/Cellar/" n "/" v)
                              files (licence-files dir)
                              f (by-name n)]]
                    (do (doseq [x files]
                          (io/make-parents (str licenses "/" n "/" x))
                          (sh! "cp" (str dir "/" x) (str licenses "/" n "/" x)))
                        {:name n :version v :stable (get-in f ["versions" "stable"]) :license (str (get f "license"))
                         :url (str (get-in f ["urls" "stable" "url"])) :license-files files})))]
    (when-let [old (seq (outdated formulae))]
      (warn! (str "these formulae are older than Homebrew's source links: " (str/join ", " old))))
    (when-let [bare (seq (keep #(when (empty? (:license-files %)) (:name %)) formulae))]
      (warn! (str "no licence file in the keg of " (str/join ", " bare) "; add its text before a public build")))
    (io/make-parents (str licenses "/SOURCES.txt"))
    (spit (str licenses "/SOURCES.txt") (sources-txt formulae))
    (spit (str licenses "/NOTICE")
          (str "Jonquil is under the Eclipse Public License 2.0. The libraries in\n"
               "Contents/Frameworks are separate works under their own licences, listed\n"
               "with their sources in SOURCES.txt.\n"))))

(def ^:private schemas (str contents "/Resources/share/glib-2.0/schemas"))

(defn- write-schemas!
  "Compiles GTK's GSettings schemas, from the gtk4 keg the bundle's
  libgtk came from, into Resources/share/glib-2.0/schemas: without them
  GLib aborts at the first file dialog."
  [plan]
  (let [gtk (some #(when (= "libgtk-4.1.dylib" (:name %)) (real-path (:src %))) plan)
        [_ keg] (re-find #"^(/opt/homebrew/Cellar/gtk4/[^/]+)/" (str gtk))
        src (str keg "/share/glib-2.0/schemas")
        names (gtk-schemas (seq (.list (io/file src))))]
    (when (empty? names) (fail! (str "no GTK GSettings schemas in " src)))
    (.mkdirs (io/file schemas))
    (doseq [n names] (sh! "cp" (str src "/" n) (str schemas "/" n)))
    (sh! "/opt/homebrew/opt/glib/bin/glib-compile-schemas" schemas)
    (println (str (count names) " GTK schemas compiled"))))

(defn- macho-files
  "The Mach-O files of the bundle: the binary and every Frameworks dylib."
  []
  (cons bin (map #(str frameworks "/" %) (sort (.list (io/file frameworks))))))

(defn- min-macos
  "The highest minos among the bundle's Mach-O files."
  []
  (max-version (keep #(otool-minos (sh! "otool" "-l" %)) (macho-files))))

(defn- sign! []
  (let [id (or (System/getenv "JONQUIL_SIGN_IDENTITY") "-")]
    (println (str "signing " (if (= "-" id) "ad hoc" (str "as " id))))
    (doseq [f (concat (rest (macho-files)) [bin app])]
      (sh! "codesign" "--force" "--timestamp=none" "-s" id f))))

(defn checks
  "[name ok?] for each check of the built bundle."
  []
  [["no Homebrew paths" (not-any? #(re-find #"/opt/homebrew|/usr/local"
                                            (str (sh! "otool" "-L" %) (str/join "\n" (otool-rpaths (sh! "otool" "-l" %)))))
                                  (macho-files))]
   ["rpaths are @loader_path" (every? #(= ["@loader_path"] (otool-rpaths (sh! "otool" "-l" %))) (rest (macho-files)))]
   ["plutil -lint" (zero? (:exit (p/sh "plutil" "-lint" (str contents "/Info.plist"))))]
   ["codesign --verify --deep --strict" (zero? (:exit (p/sh "codesign" "--verify" "--deep" "--strict" app)))]
   ["launcher executable" (.canExecute (io/file launcher))]
   ["GTK file chooser schema" (zero? (:exit (p/sh "/opt/homebrew/opt/glib/bin/gsettings" "--schemadir" schemas
                                                  "list-keys" "org.gtk.gtk4.Settings.FileChooser")))]])

(defn release!
  "Builds target/Jonquil.app and target/Jonquil-<version>-macos-arm64.zip."
  []
  (sh! "rm" "-rf" app)
  (doseq [d [(str contents "/MacOS") frameworks licenses]] (.mkdirs (io/file d)))
  (println "building jonquil-bin")
  (sh! "jolt" "build" "-m" "jonquil.main" "--signable" "-o" bin)
  (sh! "rm" "-rf" (str bin ".build"))
  (spit launcher (launcher-text))
  (.setExecutable (io/file launcher) true)
  (let [plan (closure-plan (roots) refs-of)]
    (when (empty? plan) (fail! "no library to bundle"))
    (doseq [e plan] (bundle-lib! e))
    (println (str (count plan) " libraries in Frameworks"))
    (when (lzo? (map :name plan))
      (warn! "liblzo2 (GPL-2.0-or-later) is in the bundle (cairo's script interpreter needs it); don't publish this build"))
    (write-licences! plan)
    (write-schemas! plan))
  (sh! "cp" "resources/Jonquil.icns" (str contents "/Resources/Jonquil.icns"))
  (spit (str contents "/Info.plist")
        (app-bundle/info-plist {:version (app-bundle/git-version) :build (app-bundle/build-number)
                                :doc-extensions app-bundle/doc-extensions
                                :executable "Jonquil" :min-macos (min-macos)}))
  (sign!)
  (let [cs (checks)]
    (doseq [[n ok] cs] (println (str "  check " n ": " (if ok "ok" "FAILED"))))
    (when-let [[n _] (first (remove second cs))]
      (fail! (str "release check failed: " n))))
  (let [zip (str "target/Jonquil-" (app-bundle/git-version) "-macos-arm64.zip")]
    (sh! "rm" "-f" zip)
    (sh! "ditto" "-c" "-k" "--keepParent" app zip)
    (println (str "wrote " app " and " zip))))

;; --- checking it ---------------------------------------------------------------------

(def ^:private SANDBOX
  ;; every read under /opt/homebrew denied, so a library or data file from
  ;; Homebrew shows as a failure
  "(version 1)(allow default)(deny file-read* (subpath \"/opt/homebrew\"))")

(defn- outside
  "The dyld-loaded paths in log text that are neither in dir nor the
  system's."
  [text dir]
  (->> (re-seq #"(?m)^dyld\[\d+\]: <[^>]+> (.+)$" text)
       (map (comp str/trim second))
       (remove #(or (str/starts-with? % dir) (system-lib? %)))
       distinct vec))

(defn release-check!
  "Launches the app at dir's launcher for 10 seconds under SANDBOX, with
  DYLD_PRINT_LIBRARIES set inside (sandbox-exec drops DYLD_* it's
  handed), then stops it: exits 1 when it ended early, loaded a library
  from outside the bundle and the system, or showed no load from the
  bundle at all."
  [dir]
  (let [dir (.getCanonicalPath (io/file dir))
        log (java.io.File/createTempFile "jonquil-release-check-" ".log")
        proc (p/process {:out :discard :err log}
                        "sandbox-exec" "-p" SANDBOX "/bin/sh" "-c"
                        ;; sourced, not run: a fresh /bin/sh (protected) would
                        ;; drop DYLD_PRINT_LIBRARIES; the launcher's exec keeps it
                        "export DYLD_PRINT_LIBRARIES=1; . \"$0\"" (str dir "/Contents/MacOS/Jonquil"))]
    (Thread/sleep 10000)
    (let [alive? (p/alive? proc)]
      (when alive? (p/destroy proc))
      @proc
      (let [text (slurp log)
            bad (outside text dir)
            n (count (filter #(str/includes? % (str dir "/Contents/Frameworks/")) (str/split-lines text)))]
        (.delete log)
        (cond
          (not alive?) (fail! (str "release-check: Jonquil exited early:\n"
                                   (str/join "\n" (take-last 15 (str/split-lines text)))))
          (seq bad) (fail! (str "release-check: loaded from outside the bundle: " (first bad)))
          (zero? n) (fail! "release-check: saw no library load from the bundle, so it can't tell where they came from")
          :else (println (str "release-check: ok (" n " libraries from the bundle)")))))))

(defn -main [& [cmd dir]]
  (case cmd
    "release" (release!)
    "check" (release-check! (or dir app))
    (fail! "usage: jolt -M:bundle -m app-release <release|check [app]>")))
