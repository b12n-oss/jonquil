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

(defn- fail-msg! [msg]
  (throw (ex-info msg {::fail true})))

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

;; --- the review fixes' pure parts --------------------------------------------------

(defn without-sign-keychains
  "paths less any throwaway signing keychain (a crashed run's included)."
  [paths]
  (vec (remove #(str/includes? % "jonquil-sign") paths)))

(defn safe-version
  "v fit for a file name: a slash or whitespace becomes -."
  [v]
  (str/replace v #"[/\s]" "-"))

(defn stale-app?
  "True when the app was built at another version than the checkout's."
  [app-version git-version]
  (not= app-version git-version))

(defn notary-outcome
  "{:status :id} from a notarytool run ({:exit :out}), read whatever its
  exit code; both nil when its output isn't the JSON answer."
  [{:keys [out]}]
  (try (notary-result out)
       (catch Throwable _ {:status nil :id nil})))

(defn once
  "f, run at most once however often it's called."
  [f]
  (let [done (atom false)]
    (fn [] (when (compare-and-set! done false true) (f)))))

(defn on-exit!
  "Runs f when the process ends, a ^C included: Jolt runs shutdown hooks
  on SIGINT but unwinds no finally (measured, Jolt v0.8.27)."
  [f]
  (jolt.host/add-shutdown-hook f))

(defn decode-b64!
  "Writes base64 text, decoded, to out with mode 0600; fails naming what
  when text is blank (a pass read that gave nothing) or decodes to nothing."
  [what text out]
  (when (str/blank? text) (fail-msg! (str what ": read nothing (empty)")))
  (spit out "")
  (p/sh "chmod" "600" out)
  (let [{:keys [exit]} (p/sh {:in (str/trim text)} "/bin/sh" "-c" (str "base64 -d > \"$0\"") out)]
    (when (or (not (zero? exit)) (zero? (.length (java.io.File. out))))
      (fail-msg! (str what ": decoded to nothing (empty or not base64)")))))

(defn rekey-p12!
  "Re-exports the .p12 at src (password src-pw, fed on stdin) as dest under
  new-pw, a one-time password: only that one ever reaches an argv. The
  unencrypted pem in between lives 0600 beside dest and is deleted."
  [src src-pw dest new-pw]
  (let [pem (str dest ".pem")]
    (try
      (spit pem "")
      (p/sh "chmod" "600" pem)
      (let [r (p/sh {:in (str src-pw "\n")} "/usr/bin/openssl" "pkcs12" "-in" src "-nodes" "-passin" "stdin" "-out" pem)]
        (when-not (zero? (:exit r)) (fail-msg! "the Developer ID .p12 didn't open with its password from pass")))
      (let [r (p/sh "/usr/bin/openssl" "pkcs12" "-export" "-in" pem "-out" dest "-passout" (str "pass:" new-pw))]
        (when-not (zero? (:exit r)) (fail-msg! "re-exporting the Developer ID .p12 failed")))
      (p/sh "chmod" "600" dest)
      (finally (p/sh "rm" "-f" pem)))))

;; --- running it ----------------------------------------------------------------------

(def ^:private app "target/Jonquil.app")
(def ^:private no-proxy ["/usr/bin/env" "-u" "HTTPS_PROXY" "-u" "https_proxy" "-u" "HTTP_PROXY" "-u" "http_proxy"])

(defn- fail! [msg] (fail-msg! msg))

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

(defn- pass-text
  "pass entry e's text; fails when pass fails or gives nothing (a
  cancelled pinentry, a missing entry)."
  [e]
  (let [{:keys [exit out]} (p/sh "env" (str "PASSWORD_STORE_DIR=" pass-env) "pass" "show" e)]
    (when (or (not (zero? exit)) (str/blank? out)) (fail! (str "pass show " e " gave nothing (exit " exit ")")))
    out))

(defn- pass-line
  "The first line of pass entry e, trimmed."
  [e]
  (str/trim (first (str/split-lines (pass-text e)))))

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
       without-sign-keychains))

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
        sub (apply p/sh (concat no-proxy ["xcrun" "notarytool" "submit" path "--wait" "--output-format" "json"] auth))
        r (notary-outcome sub)]
    (println (str "  " (or (:status r) "no answer") (when (:id r) (str " (" (:id r) ")"))))
    (cond
      (= "Accepted" (:status r)) nil
      (nil? (:id r)) (fail! (str "notarytool submit failed (exit " (:exit sub) ")"
                                 (when-let [e (first (str/split-lines (str (:err sub))))] (str ": " e))))
      :else (let [log (str "target/notarization-" (:id r) ".json")
                  got (apply p/sh (concat no-proxy ["xcrun" "notarytool" "log" (:id r) log] auth))]
              (fail! (str "notarization " (:status r) "; "
                          (if (zero? (:exit got)) (str "Apple's log is in " log)
                              (str "fetching Apple's log failed too (exit " (:exit got) "); notarytool log " (:id r) " fetches it"))))))))

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

(defn- app-version
  "The version target/Jonquil.app was built at (its Info.plist)."
  []
  (str/trim (:out (exec! ["plutil" "-extract" "CFBundleShortVersionString" "raw" "-o" "-" (str app "/Contents/Info.plist")]))))

(defn- notarize-in!
  "The run, with tmp (a 700 directory) for the keychain and decoded
  secrets; old the user keychain list to restore."
  [tmp]
  (let [kc (str tmp "/jonquil-sign.keychain-db")
        kc-pw (random-hex 32)
        p12-pw (random-hex 32)
        p12 (str tmp "/id.p12")
        p8 (str tmp "/key.p8")
        version (safe-version (app-version))
        zip (str "target/Jonquil-" version "-macos-arm64.zip")
        dmg (str "target/Jonquil-" version "-macos-arm64.dmg")]
    (decode-b64! "the Developer ID .p12" (pass-text "secrets/apple/developer-id-application-p12") (str p12 ".orig"))
    (decode-b64! "the App Store Connect key" (pass-text (auth-key-entry)) p8)
    ;; the long-lived password only ever goes through stdin; a one-time one
    ;; (p12-pw) is what security's argv shows
    (rekey-p12! (str p12 ".orig") (pass-line "secrets/apple/developer-id-application-p12-password") p12 p12-pw)
    (exec! ["rm" "-f" (str p12 ".orig")])
    (when-not (= G2-SHA256 (sha256-hex "resources/certs/DeveloperIDG2CA.cer"))
      (fail! "resources/certs/DeveloperIDG2CA.cer doesn't match its pinned SHA-256"))
    (doseq [argv (keychain-commands {:kc kc :kc-pw kc-pw :p12 p12 :p12-pw p12-pw
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

(defn- sweep-stale!
  "A run that died without its cleanup (SIGKILL, a power cut) can leave a
  jonquil-sign keychain in the user list and its temp directory: both go."
  []
  (let [listed (str/split-lines (:out (exec! ["security" "list-keychains" "-d" "user"])))
        stale (filter #(str/includes? % "jonquil-sign") (map #(str/replace (str/trim %) "\"" "") listed))]
    (doseq [kc stale] (p/sh "security" "delete-keychain" kc))
    (when (seq stale)
      (apply p/sh "security" "list-keychains" "-d" "user" "-s" (user-keychains)))
    (doseq [d (.listFiles (java.io.File. (System/getProperty "java.io.tmpdir")))
            :when (str/starts-with? (.getName d) "jonquil-sign.")]
      (p/sh "rm" "-rf" (str d)))))

(defn notarize!
  "Signs, notarizes and staples target/Jonquil.app, then writes the
  notarized zip and dmg. The keychain, the decoded secrets and the temp
  directory go however the run ends, a ^C included (a shutdown hook, as
  Jolt unwinds no finally on SIGINT), and the user keychain list is put
  back as it was."
  []
  (when-not (.isDirectory (java.io.File. app))
    (fail! "no target/Jonquil.app: run jolt app:release first"))
  (when-let [[n _] (first (remove second (app-release/checks)))]
    (fail! (str "target/Jonquil.app fails app:release's check \"" n "\": run jolt app:release again")))
  (when (stale-app? (app-version) (app-bundle/git-version))
    (fail! (str "target/Jonquil.app was built at " (app-version) ", but this checkout is "
                (app-bundle/git-version) ": run jolt app:release again")))
  (when (some #(str/starts-with? % "liblzo2") (.list (java.io.File. (str app "/Contents/Frameworks"))))
    (binding [*out* *err*]
      (println "app-notarize: WARNING: liblzo2 (GPL-2.0-or-later) is in the bundle; notarizing isn't publishing, but don't publish this build")))
  (sweep-stale!)
  (let [old (user-keychains)
        _ (when (empty? old) (fail! "the user keychain list reads empty; not touching it"))
        tmp (str/trim (:out (exec! ["mktemp" "-d" "-t" "jonquil-sign"])))
        cleanup! (once (fn []
                         (p/sh "security" "delete-keychain" (str tmp "/jonquil-sign.keychain-db"))
                         (apply p/sh "security" "list-keychains" "-d" "user" "-s" old)
                         (p/sh "rm" "-rf" tmp)))]
    (on-exit! cleanup!)
    (try
      (notarize-in! tmp)
      (finally (cleanup!)))))

(defn -main [& _]
  (try
    (notarize!)
    (catch clojure.lang.ExceptionInfo e
      (if (::fail (ex-data e))
        (do (binding [*out* *err*] (println (str "app-notarize: " (ex-message e))))
            (System/exit 1))
        (throw e)))))
