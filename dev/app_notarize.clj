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
  (:require [clojure.data.json :as json]
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
