(ns app-notarize
  "Signs target/Jonquil.app (from app:release) with the Developer ID,
  notarizes and staples it, and writes the notarized zip and dmg:
  jolt -M:bundle -m app-notarize, which the jolt task app:notarize runs.

  The key comes from b12n-pass into a throwaway keychain, never the login
  keychain; the App Store Connect key that notarytool uses comes from pass
  into a temp file. Both go when the run ends, however it ends.

  Entitlements: exactly allow-jit (Chez writes its machine code at run
  time) and allow-dyld-environment-variables (the launcher's
  DYLD_LIBRARY_PATH); measured 2026-11-13, see the step 2 design."
  (:require [app-bundle]
            [app-release]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [jolt.process :as p]))

;; --- what the signing carries ------------------------------------------------------

(defn entitlements-plist
  "resources/Jonquil.entitlements: the hardened runtime's two exceptions."
  []
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
       "<plist version=\"1.0\">\n<dict>\n"
       "  <key>com.apple.security.cs.allow-jit</key>\n  <true/>\n"
       "  <key>com.apple.security.cs.allow-dyld-environment-variables</key>\n  <true/>\n"
       "</dict>\n</plist>\n"))

(def G2-SHA256
  ;; resources/certs/DeveloperIDG2CA.cer, Apple's Developer ID G2
  ;; intermediate (apple.com/certificateauthority), checked before import
  "f16cd3c54c7f83cea4bf1a3e6a0819c8aaa8e4a1528fd144715f350643d2df3a")

(defn sha256-hex
  "path's SHA-256, lower-case hex."
  [path]
  (first (str/split (str/trim (:out (p/sh "shasum" "-a" "256" (str path)))) #"\s+")))

;; --- the throwaway keychain --------------------------------------------------------

(defn keychain-commands
  "The security(1) argv vectors that make keychain kc (password kc-pw),
  import the G2 intermediate ca and the identity p12 (password p12-pw)
  usable by codesign, and let codesign use the key without a prompt."
  [{:keys [kc kc-pw p12 p12-pw ca]}]
  [["create-keychain" "-p" kc-pw kc]
   ["set-keychain-settings" "-t" "3600" kc]
   ["unlock-keychain" "-p" kc-pw kc]
   ["import" ca "-k" kc]
   ["import" p12 "-k" kc "-P" p12-pw "-T" "/usr/bin/codesign"]
   ["set-key-partition-list" "-S" "apple-tool:,apple:,codesign:" "-s" "-k" kc-pw kc]])

;; --- reading the tools' answers ----------------------------------------------------

(defn notary-result
  "{:status :id} from notarytool's --output-format json."
  [text]
  (let [m (json/read-str text)]
    {:status (get m "status") :id (get m "id")}))

(defn spctl-accepted?
  "True when spctl -a -vv's text says accepted, from a notarized Developer ID."
  [text]
  (boolean (and (re-find #": accepted" text) (str/includes? text "source=Notarized Developer ID"))))

;; --- running it ----------------------------------------------------------------------

(def ^:private app "target/Jonquil.app")
(def ^:private no-proxy ["/usr/bin/env" "-u" "HTTPS_PROXY" "-u" "https_proxy" "-u" "HTTP_PROXY" "-u" "http_proxy"])

(defn- fail! [msg]
  (throw (ex-info msg {::fail true})))

(defn- exec!
  "Runs argv, answering {:out :err}; fails naming only the tool (argv may
  hold a password, so it's never printed)."
  [argv]
  (let [{:keys [exit out err]} (apply p/sh argv)]
    (when-not (zero? exit)
      (fail! (str (first argv) " " (second argv) " failed (exit " exit ")"
                  (when (seq err) (str ": " (first (str/split-lines err)))))))
    {:out out :err err}))

(def ^:private pass-env
  (or (System/getenv "PASSWORD_STORE_DIR") (str (System/getProperty "user.home") "/dev/b12n-pass")))

(defn- sh-c!
  "Runs script with /bin/sh -c (pass's output piped straight to a file,
  so no secret goes through argv); fails naming what."
  [what script]
  (let [{:keys [exit]} (p/sh "/bin/sh" "-c" (str "PASSWORD_STORE_DIR='" pass-env "' " script))]
    (when-not (zero? exit) (fail! (str what " failed (exit " exit ")")))))

(defn- pass-line
  "The first line of pass entry e, trimmed."
  [e]
  (let [{:keys [exit out]} (p/sh "env" (str "PASSWORD_STORE_DIR=" pass-env) "pass" "show" e)]
    (when-not (zero? exit) (fail! (str "pass show " e " failed")))
    (str/trim (first (str/split-lines out)))))

(defn- auth-key-entry
  "The pass entry of the App Store Connect key: AuthKey_<id>.p8."
  []
  (let [dir (java.io.File. (str pass-env "/secrets/cfg/appstore-connect"))
        f (first (filter #(re-matches #"AuthKey_.*\.p8\.gpg" %) (sort (.list dir))))]
    (or (some->> f (re-find #"(.*)\.gpg$") second (str "secrets/cfg/appstore-connect/"))
        (fail! "no secrets/cfg/appstore-connect/AuthKey_*.p8 in b12n-pass"))))

(defn- random-hex [n]
  (let [b (byte-array n)]
    (.nextBytes (java.security.SecureRandom.) b)
    (apply str (map #(format "%02x" (bit-and % 0xff)) b))))

(defn- user-keychains []
  (->> (str/split-lines (:out (exec! ["security" "list-keychains" "-d" "user"])))
       (map #(str/replace (str/trim %) "\"" ""))
       (remove str/blank?)
       vec))

(defn- identity-hash
  "The SHA-1 of keychain kc's Developer ID Application identity."
  [kc]
  (or (second (re-find #"\d+\) ([0-9A-F]{40}) \"Developer ID Application"
                       (:out (exec! ["security" "find-identity" "-v" "-p" "codesigning" kc]))))
      (fail! "no valid Developer ID Application identity in the temporary keychain")))

(defn- notarize-file!
  "Submits path to Apple's notary service with the API key; on anything
  but Accepted saves the log to target/notarization-<id>.json and fails."
  [path {:keys [p8 key-id issuer]}]
  (println (str "notarizing " path " (this waits for Apple)"))
  (let [auth ["--key" p8 "--key-id" key-id "--issuer" issuer]
        r (notary-result (:out (exec! (concat no-proxy ["xcrun" "notarytool" "submit" path "--wait" "--output-format" "json"] auth))))]
    (println (str "  " (:status r) " (" (:id r) ")"))
    (when-not (= "Accepted" (:status r))
      (let [log (str "target/notarization-" (:id r) ".json")]
        (apply p/sh (concat no-proxy ["xcrun" "notarytool" "log" (:id r) log] auth))
        (fail! (str "notarization " (:status r) "; Apple's log is in " log))))))

(defn- sign! [kc hash path & more]
  (exec! (vec (concat ["codesign" "--force" "--options" "runtime" "--timestamp" "--keychain" kc "-s" hash] more [path]))))

(defn- check-lines
  "[name ok?] for the notarized app and dmg."
  [dmg]
  [["app accepted (Notarized Developer ID)"
    (spctl-accepted? (:err (p/sh "spctl" "-a" "-vv" "-t" "exec" app)))]
   ["dmg accepted (Notarized Developer ID)"
    (spctl-accepted? (:err (p/sh "spctl" "-a" "-vv" "-t" "open" "--context" "context:primary-signature" dmg)))]
   ["app ticket stapled" (zero? (:exit (p/sh "xcrun" "stapler" "validate" app)))]
   ["dmg ticket stapled" (zero? (:exit (p/sh "xcrun" "stapler" "validate" dmg)))]])

(defn- notarize-in!
  "The run, with tmp (a 700 directory) for the keychain and decoded
  secrets; old the user keychain list to restore."
  [tmp]
  (let [kc (str tmp "/jonquil-sign.keychain-db")
        kc-pw (random-hex 32)
        p12 (str tmp "/id.p12")
        p8 (str tmp "/key.p8")
        version (app-bundle/git-version)
        zip (str "target/Jonquil-" version "-macos-arm64.zip")
        dmg (str "target/Jonquil-" version "-macos-arm64.dmg")]
    (sh-c! "decoding the Developer ID .p12 from pass"
           (str "pass show secrets/apple/developer-id-application-p12 | base64 -d > '" p12 "' && chmod 600 '" p12 "'"))
    (sh-c! "decoding the App Store Connect key from pass"
           (str "pass show '" (auth-key-entry) "' | base64 -d > '" p8 "' && chmod 600 '" p8 "'"))
    (when-not (= G2-SHA256 (sha256-hex "resources/certs/DeveloperIDG2CA.cer"))
      (fail! "resources/certs/DeveloperIDG2CA.cer doesn't match its pinned SHA-256"))
    (doseq [argv (keychain-commands {:kc kc :kc-pw kc-pw :p12 p12 :p12-pw (pass-line "secrets/apple/developer-id-application-p12-password")
                                     :ca "resources/certs/DeveloperIDG2CA.cer"})]
      (exec! (into ["security"] argv)))
    (exec! (into ["security" "list-keychains" "-d" "user" "-s" kc] (user-keychains)))
    (let [hash (identity-hash kc)
          ents "resources/Jonquil.entitlements"
          auth {:p8 p8 :key-id (pass-line "secrets/apple/asc-key-id") :issuer (pass-line "secrets/apple/asc-issuer-id")}
          app-zip (str tmp "/Jonquil.app.zip")
          stage (str tmp "/dmg")]
      (println "signing with the Developer ID (hardened runtime, timestamp)")
      (doseq [f (sort (.list (java.io.File. (str app "/Contents/Frameworks"))))]
        (sign! kc hash (str app "/Contents/Frameworks/" f)))
      (sign! kc hash (str app "/Contents/MacOS/jonquil-bin") "--entitlements" ents)
      (sign! kc hash app "--entitlements" ents)
      (exec! ["codesign" "--verify" "--deep" "--strict" app])
      (exec! ["ditto" "-c" "-k" "--keepParent" app app-zip])
      (notarize-file! app-zip auth)
      (exec! (concat no-proxy ["xcrun" "stapler" "staple" app]))
      (exec! ["rm" "-f" zip])
      (exec! ["ditto" "-c" "-k" "--keepParent" app zip])
      (exec! ["mkdir" "-p" stage])
      (exec! ["cp" "-R" app (str stage "/Jonquil.app")])
      (exec! ["ln" "-s" "/Applications" (str stage "/Applications")])
      (exec! ["rm" "-f" dmg])
      (exec! ["hdiutil" "create" "-volname" "Jonquil" "-srcfolder" stage "-ov" "-format" "UDZO" dmg])
      (exec! ["codesign" "--force" "--timestamp" "--keychain" kc "-s" hash dmg])
      (notarize-file! dmg auth)
      (exec! (concat no-proxy ["xcrun" "stapler" "staple" dmg]))
      (let [cs (check-lines dmg)]
        (doseq [[n ok] cs] (println (str "  check " n ": " (if ok "ok" "FAILED"))))
        (when-let [[n _] (first (remove second cs))] (fail! (str "check failed: " n))))
      (println (str "wrote " app " (notarized), " zip " and " dmg)))))

(defn notarize!
  "Signs, notarizes and staples target/Jonquil.app, then writes the
  notarized zip and dmg. The keychain, the decoded .p12 and .p8 and the
  temp directory go whatever happens, and the user keychain list is put
  back as it was."
  []
  (when-not (.isDirectory (java.io.File. app))
    (fail! "no target/Jonquil.app: run jolt app:release first"))
  (when-let [[n _] (first (remove second (app-release/checks)))]
    (fail! (str "target/Jonquil.app fails app:release's check \"" n "\": run jolt app:release again")))
  (when (some #(str/starts-with? % "liblzo2") (.list (java.io.File. (str app "/Contents/Frameworks"))))
    (binding [*out* *err*]
      (println "app-notarize: WARNING: liblzo2 (GPL-2.0-or-later) is in the bundle; notarizing isn't publishing, but don't publish this build")))
  (let [old (user-keychains)
        tmp (str/trim (:out (exec! ["mktemp" "-d" "-t" "jonquil-sign"])))]
    (exec! ["chmod" "700" tmp])
    (try
      (notarize-in! tmp)
      (finally
        (p/sh "security" "delete-keychain" (str tmp "/jonquil-sign.keychain-db"))
        (apply p/sh "security" "list-keychains" "-d" "user" "-s" old)
        (p/sh "rm" "-rf" tmp)))))

(defn -main [& _]
  (try
    (notarize!)
    (catch clojure.lang.ExceptionInfo e
      (if (::fail (ex-data e))
        (do (binding [*out* *err*] (println (str "app-notarize: " (ex-message e))))
            (System/exit 1))
        (throw e)))))
