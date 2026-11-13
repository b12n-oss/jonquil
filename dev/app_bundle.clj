(ns app-bundle
  "Builds ./Jonquil.app from the ./jonquil binary and installs it.

  Run as `jolt -M:bundle -m app-bundle <bundle|install>` (the jolt tasks
  app:bundle and app:install do, after rebuilding ./jonquil). The bundle is
  not re-signed: `jolt build` appends the program image after the launcher's
  Mach-O, which makes `codesign --force` fail strict validation, and the
  linker's ad-hoc signature on the built binary is what macOS needs."
  (:require [app-linux]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]
            [jonquil.version.describe :as describe]))

(def bundle-id "net.b12n.jonquil")
(def bundle-dir "Jonquil.app")
(def doc-extensions ["clj" "cljc" "cljd" "cljs" "jank" "edn" "bb"])

(defn- xml-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kv [k v] (str "  <key>" k "</key>\n  <string>" (xml-escape v) "</string>\n"))

(defn info-plist
  "The Info.plist XML for the bundle. opts: :version (CFBundleShortVersionString),
  :build (CFBundleVersion, period-separated integers) and :doc-extensions (the extensions Jonquil offers to open, in order).
  A release bundle (app-release) also passes :executable (CFBundleExecutable,
  \"jonquil\" otherwise) and :min-macos (LSMinimumSystemVersion, \"13.0\"
  otherwise)."
  [{:keys [version build doc-extensions executable min-macos] :or {executable "jonquil" min-macos "13.0"}}]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
       "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
       "<plist version=\"1.0\">\n<dict>\n"
       (kv "CFBundleName" "Jonquil")
       (kv "CFBundleDisplayName" "Jonquil")
       (kv "CFBundleIdentifier" bundle-id)
       (kv "CFBundleExecutable" executable)
       (kv "CFBundleIconFile" "Jonquil")
       (kv "CFBundlePackageType" "APPL")
       (kv "CFBundleVersion" build)
       (kv "CFBundleShortVersionString" version)
       (kv "LSMinimumSystemVersion" min-macos)
       "  <key>NSHighResolutionCapable</key>\n  <true/>\n"
       "  <key>CFBundleDocumentTypes</key>\n  <array>\n    <dict>\n"
       "      <key>CFBundleTypeName</key>\n      <string>Clojure source</string>\n"
       "      <key>CFBundleTypeRole</key>\n      <string>Editor</string>\n"
       "      <key>LSHandlerRank</key>\n      <string>Alternate</string>\n"
       "      <key>CFBundleTypeExtensions</key>\n      <array>\n"
       (apply str (map (fn [e] (str "        <string>" (xml-escape e) "</string>\n")) doc-extensions))
       "      </array>\n    </dict>\n"
       "    <dict>\n"
       "      <key>CFBundleTypeName</key>\n      <string>Folder</string>\n"
       "      <key>CFBundleTypeRole</key>\n      <string>Viewer</string>\n"
       "      <key>LSHandlerRank</key>\n      <string>Alternate</string>\n"
       "      <key>LSItemContentTypes</key>\n      <array>\n"
       "        <string>public.folder</string>\n      </array>\n    </dict>\n  </array>\n"
       "</dict>\n</plist>\n"))

(defn replaceable?
  "True when plist-text names Jonquil's own bundle id, so install may
  replace the bundle it came from. Anything else (nil, empty, another
  app's plist) is refused."
  [plist-text]
  (boolean
   (when plist-text
     (when-let [m (re-find #"<key>CFBundleIdentifier</key>\s*<string>([^<]*)</string>" plist-text)]
       (= bundle-id (second m))))))

(defn- fail! [msg]
  (binding [*out* *err*] (println (str "app-bundle: " msg)))
  (System/exit 1))

(defn git-version
  "The version for the Info.plist, the zip name and the stale-app check:
  JONQUIL_VERSION when non-empty (the same value build-env bakes into the
  binary), else the checkout's git describe, else 0.0.0."
  []
  (or (describe/build-version (System/getenv "JONQUIL_VERSION") #(describe/describe-checkout "."))
      "0.0.0"))

(defn build-env
  "The environment a `jolt build` of Jonquil gets so the binary carries its
  version: see jonquil.version.describe/build-env."
  []
  (describe/build-env (System/getenv "JONQUIL_VERSION") #(describe/describe-checkout ".")))

(defn build!
  "Runs `jolt build` of jonquil.main into ./jonquil, with build-env, echoing
  its output. Exits with its status."
  [args]
  (let [{:keys [exit]} (apply p/shell {:continue true :extra-env (build-env)}
                              "jolt" "build" "-m" "jonquil.main" "-o" "jonquil" args)]
    (System/exit exit)))

(defn build-number
  "The commit count as CFBundleVersion (Apple wants period-separated integers)."
  []
  (let [r (p/sh "git" "rev-list" "--count" "HEAD")
        n (str/trim (str (:out r)))]
    (if (and (zero? (:exit r)) (re-matches #"\d+" n)) n "0")))

(defn- copy! [from to]
  (io/make-parents to)
  (io/copy (io/file from) (io/file to)))

(defn- check-bundle!
  "Runs the post-build checks, printing each; exits non-zero naming the first failure."
  [dir]
  (let [plist (str dir "/Contents/Info.plist")
        exe (str dir "/Contents/MacOS/jonquil")
        lint (p/sh "plutil" "-lint" plist)
        sign (p/sh "codesign" "-dv" exe)
        sign-text (str (:out sign) (:err sign))
        checks [["plutil -lint" (zero? (:exit lint))]
                ["executable bit" (.canExecute (io/file exe))]
                ["jonquil command" (.canExecute (io/file dir "Contents/Resources/bin/jonquil"))]
                ["codesign adhoc" (and (zero? (:exit sign)) (str/includes? sign-text "adhoc"))]]]
    (doseq [[n ok] checks] (println (str "  check " n ": " (if ok "ok" "FAILED"))))
    (when-let [[n _] (first (remove second checks))]
      (fail! (str "bundle check failed: " n)))))

(defn bundle! []
  (when-not (.isFile (io/file "jonquil"))
    (fail! "./jonquil is missing; run `jolt app:build` first (or use `jolt app:bundle`)"))
  (p/sh "rm" "-rf" bundle-dir)
  (copy! "jonquil" (str bundle-dir "/Contents/MacOS/jonquil"))
  (.setExecutable (io/file bundle-dir "Contents/MacOS/jonquil") true)
  (copy! "resources/Jonquil.icns" (str bundle-dir "/Contents/Resources/Jonquil.icns"))
  ;; the terminal command (jonquil.cli-install links to it)
  (copy! "resources/cli/jonquil" (str bundle-dir "/Contents/Resources/bin/jonquil"))
  (.setExecutable (io/file bundle-dir "Contents/Resources/bin/jonquil") true)
  (spit (str bundle-dir "/Contents/Info.plist")
        (info-plist {:version (git-version) :build (build-number) :doc-extensions doc-extensions}))
  (println (str "built " bundle-dir))
  (check-bundle! bundle-dir))

(def ^:private lsregister
  "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister")

(defn install! []
  (let [dest (str (System/getProperty "user.home") "/Applications/" bundle-dir)
        existing (io/file dest)]
    (when-not (.isDirectory (io/file bundle-dir))
      (fail! "./Jonquil.app is missing; run `jolt app:bundle` first"))
    (when (.exists existing)
      (let [r (p/sh "plutil" "-convert" "xml1" "-o" "-" (str dest "/Contents/Info.plist"))]
        (when-not (and (zero? (:exit r)) (replaceable? (:out r)))
          (fail! (str dest " exists and is not a Jonquil bundle; refusing to replace it"))))
      (p/sh "rm" "-rf" dest))
    (io/make-parents dest)
    (let [r (p/sh "cp" "-R" bundle-dir dest)]
      (when-not (zero? (:exit r)) (fail! (str "copy failed: " (:err r)))))
    (let [r (p/sh lsregister "-f" dest)]
      (when-not (zero? (:exit r)) (fail! (str "lsregister failed: " (:err r)))))
    ;; macOS registers the checkout's copy too, a moment after it appears on
    ;; disk, so Open With would list Jonquil twice. Unregistering alone races
    ;; that, so the copy is unregistered (if it already was registered) and
    ;; then removed: install moves the bundle rather than copying it.
    (let [here (.getCanonicalPath (io/file bundle-dir))]
      (p/sh lsregister "-u" here)
      (p/sh "rm" "-rf" here))
    (println (str "installed " dest " (moved from ./" bundle-dir ")"))))

(defn- linux? []
  (str/includes? (str (System/getProperty "os.name")) "Linux"))

(defn -main [& args]
  (case (or (first args) "bundle")
    "bundle" (if (linux?)
               (fail! "app:bundle builds a macOS .app; on Linux use jolt app:install")
               (bundle!))
    "build" (build! (rest args))
    "install" (if (linux?)
                (let [status (app-linux/install! {:home (System/getProperty "user.home")
                                                  :source "jonquil"
                                                  :version (git-version)})]
                  (when-not (zero? status) (System/exit status)))
                (install!))
    "uninstall" (if (linux?)
                  (let [status (app-linux/uninstall! {:home (System/getProperty "user.home")})]
                    (when-not (zero? status) (System/exit status)))
                  (println "on macOS, drag ~/Applications/Jonquil.app to the Trash"))
    (fail! (str "unknown command " (pr-str (first args)) "; use build, bundle, install or uninstall")))
  (System/exit 0))
