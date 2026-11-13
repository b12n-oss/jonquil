(ns jonquil.app-notarize-test
  (:require [app-notarize :as an]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jolt.process :as p]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

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

;; --- review fixes ---------------------------------------------------------------------

(deftest the-user-keychain-list-never-keeps-a-sign-keychain-test
  (is (= ["/Users/u/Library/Keychains/login.keychain-db"]
         (an/without-sign-keychains ["/Users/u/Library/Keychains/login.keychain-db"
                                     "/var/folders/x/T/jonquil-sign.AbC123/jonquil-sign.keychain-db"]))))

(deftest artifact-names-come-from-the-app-test
  (is (= "v1.2-feature-x" (an/safe-version "v1.2/feature x")))
  (is (an/stale-app? "4c1ea4f" "46d5c9e") "an app built at another commit is refused")
  (is (not (an/stale-app? "46d5c9e" "46d5c9e"))))

(deftest notary-outcome-reads-the-answer-whatever-the-exit-test
  (is (= {:status "Invalid" :id "x"} (an/notary-outcome {:exit 1 :out "{\"id\":\"x\",\"status\":\"Invalid\"}"})))
  (is (= {:status "In Progress" :id "y"} (an/notary-outcome {:exit 0 :out "{\"id\":\"y\",\"status\":\"In Progress\"}"})))
  (is (= {:status nil :id nil} (an/notary-outcome {:exit 1 :out "Error: HTTP status code: 401"}))))

(deftest once-runs-its-body-once-test
  (let [n (atom 0)
        f (an/once (fn [] (swap! n inc)))]
    (f) (f)
    (is (= 1 @n))))

(deftest decode-refuses-an-empty-read-test
  (let [dir (temp-dirs/temp-dir! "jonquil-notarize-test-")
        out (str dir "/x.bin")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"empty"
                          (an/decode-b64! "test entry" "" out)))
    (an/decode-b64! "test entry" "aGVsbG8=" out)
    (is (= "hello" (slurp out)))
    (is (= "-rw-------" (str/trim (:out (p/sh "stat" "-f" "%Sp" out)))) "0600, read only by you")))

(deftest rekey-gives-a-p12-only-the-new-password-opens-test
  (let [dir (temp-dirs/temp-dir! "jonquil-notarize-test-")
        sh! (fn [& a] (apply p/sh {:dir dir} a))]
    (sh! "/usr/bin/openssl" "req" "-x509" "-newkey" "rsa:2048" "-nodes" "-keyout" "k.pem" "-out" "c.pem" "-subj" "/CN=probe" "-days" "1")
    (sh! "/usr/bin/openssl" "pkcs12" "-export" "-in" "c.pem" "-inkey" "k.pem" "-out" "a.p12" "-passout" "pass:orig pw")
    (an/rekey-p12! (str dir "/a.p12") "orig pw" (str dir "/b.p12") "onetime")
    (is (= 0 (:exit (sh! "/usr/bin/openssl" "pkcs12" "-in" "b.p12" "-nodes" "-passin" "pass:onetime" "-out" "out.pem"))))
    (is (str/includes? (slurp (str dir "/out.pem")) "PRIVATE KEY"))
    (is (not (.exists (java.io.File. (str dir "/b.pem")))) "no unencrypted key left beside it")))

(deftest a-sigint-still-cleans-up-test
  (let [dir (temp-dirs/temp-dir! "jonquil-notarize-test-")
        victim (str dir "/victim")
        _ (.mkdirs (java.io.File. victim))
        proc (p/process {:out :string :err :string}
                        "jolt" "-M:bundle" "-e"
                        (str "(require 'app-notarize) "
                             "(app-notarize/on-exit! (app-notarize/once (fn [] (clojure.java.io/delete-file \"" victim "\" true)))) "
                             "(println :ready) (flush) (Thread/sleep 60000)"))
        pid (.pid (:proc proc))]
    ;; jolt -e starts in about a second; give the hook time to register
    (Thread/sleep 5000)
    (p/sh "kill" "-INT" (str pid))
    (deref proc 20000 nil)
    (is (not (.exists (java.io.File. victim))) "the shutdown hook removed it although ^C skips finally")))
