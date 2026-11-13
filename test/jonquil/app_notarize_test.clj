(ns jonquil.app-notarize-test
  (:require [app-notarize :as an]
            [clojure.test :refer [deftest is]]))

(deftest entitlements-are-exactly-two-test
  (let [t (an/entitlements-plist)]
    (is (= #{"com.apple.security.cs.allow-jit" "com.apple.security.cs.allow-dyld-environment-variables"}
           (set (map second (re-seq #"<key>([^<]+)</key>" t)))))
    (is (= t (slurp "resources/Jonquil.entitlements")) "the committed file is the same text")))

(deftest the-g2-intermediate-is-pinned-test
  (is (= "f16cd3c54c7f83cea4bf1a3e6a0819c8aaa8e4a1528fd144715f350643d2df3a" an/G2-SHA256))
  (is (= an/G2-SHA256 (an/sha256-hex "resources/certs/DeveloperIDG2CA.cer"))))

(deftest keychain-commands-test
  (is (= [["create-keychain" "-p" "kp" "/t/k.keychain-db"]
          ["set-keychain-settings" "-t" "3600" "/t/k.keychain-db"]
          ["unlock-keychain" "-p" "kp" "/t/k.keychain-db"]
          ["import" "/r/g2.cer" "-k" "/t/k.keychain-db"]
          ["import" "/t/id.p12" "-k" "/t/k.keychain-db" "-P" "pp" "-T" "/usr/bin/codesign"]
          ["set-key-partition-list" "-S" "apple-tool:,apple:,codesign:" "-s" "-k" "kp" "/t/k.keychain-db"]]
         (an/keychain-commands {:kc "/t/k.keychain-db" :kc-pw "kp" :p12 "/t/id.p12" :p12-pw "pp" :ca "/r/g2.cer"}))))

(deftest notary-result-test
  (is (= {:status "Accepted" :id "2efe2717-52ef-43a5-96dc-0797e4ca1041"}
         (an/notary-result "{\"id\":\"2efe2717-52ef-43a5-96dc-0797e4ca1041\",\"status\":\"Accepted\",\"message\":\"Processing complete\"}")))
  (is (= "Invalid" (:status (an/notary-result "{\"id\":\"x\",\"status\":\"Invalid\"}")))))

(deftest spctl-accepted-test
  (is (an/spctl-accepted? "target/Jonquil.app: accepted\nsource=Notarized Developer ID\norigin=Developer ID Application: X (T)\n"))
  (is (not (an/spctl-accepted? "target/Jonquil.app: rejected\nsource=Unnotarized Developer ID\n"))))
