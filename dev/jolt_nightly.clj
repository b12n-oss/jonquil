;; Runs the live nREPL suite (`jolt -M:test --live`, what `jolt test:nrepl`
;; runs) against Jolt's nightly build, the `vnightly` pre-release built daily
;; from jolt-lang/jolt main, so a change on Jolt main that breaks Jonquil
;; shows up before it reaches a release. Run via `jolt test:nightly`.
;;
;; The archive is checked against its published .sha256 and unpacked under
;; $XDG_CACHE_HOME/jonquil/jolt-nightly/<platform>, or
;; ~/.cache/jonquil/jolt-nightly/<platform>; nothing is installed and the jolt
;; on PATH is left alone. Each run fetches only the small .sha256 and
;; downloads the archive again when that changes. `--offline` uses the cached
;; build without asking the network.
;;
;; The nightly goes first on PATH for the suite, which reaches both the test
;; runner and the Jolt children it starts: those resolve jolt through a login
;; shell, which keeps the inherited PATH in front (checked with ps,
;; 2026-10-20).
(ns jolt-nightly
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def ^:private base-url
  "https://github.com/jolt-lang/jolt/releases/download/vnightly/")

(defn- platform
  "The nightly's platform name for this machine, or nil when it publishes none."
  []
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")
        os (cond (str/includes? os "mac") "macos"
                 (str/includes? os "linux") "linux")
        arch (case arch ("aarch64" "arm64") "aarch64" ("x86_64" "amd64") "x86_64" nil)]
    (#{"aarch64-macos" "x86_64-macos" "x86_64-linux"} (str arch "-" os))))

(defn- cache-dir [plat]
  (let [xdg (System/getenv "XDG_CACHE_HOME")
        root (if (and xdg (str/starts-with? xdg "/"))
               xdg
               (str (System/getProperty "user.home") "/.cache"))]
    (fs/path root "jonquil" "jolt-nightly" plat)))

(defn- fail! [& msg]
  (binding [*out* *err*] (println (str "[nightly] " (apply str msg))))
  (System/exit 1))

(defn- curl! [url out]
  (let [r (p/shell {:continue true} "curl" "-fsSL" "-o" (str out) url)]
    (when-not (zero? (:exit r))
      (fail! "download failed (curl exit " (:exit r) "): " url))))

(defn- first-word [s] (first (str/split (str/trim s) (re-pattern "\\s+"))))

(defn- sha256
  "file's SHA-256, hex, from the system's own tool: java.security's
  MessageDigest needs jolt-lang/crypto in Jolt (since v0.8.1), which
  Jonquil doesn't depend on, and every platform with a nightly has one of
  these."
  [file]
  (let [cmd (if (fs/which "sha256sum") ["sha256sum"] ["shasum" "-a" "256"])
        r (apply p/shell {:out :string :continue true} (conj cmd (str file)))]
    (when-not (zero? (:exit r))
      (fail! "couldn't hash " file " with " (str/join " " cmd)))
    (first-word (:out r))))

(defn- fetch!
  "Makes sure dir holds the current nightly's jolt; returns its path."
  [plat dir offline?]
  (let [stem (str "jolt-vnightly-" plat)
        jolt (fs/path dir stem "jolt")
        stamp (fs/path dir "archive.sha256")]
    (if offline?
      (if (fs/executable? jolt)
        jolt
        (fail! "--offline, but no cached nightly at " jolt))
      (let [tmp (fs/create-temp-dir {:prefix "jolt-nightly-"})]
        (try
          (curl! (str base-url stem ".tar.gz.sha256") (fs/path tmp "sum"))
          (let [want (first-word (slurp (str (fs/path tmp "sum"))))]
            (if (and (fs/executable? jolt) (fs/exists? stamp)
                     (= want (str/trim (slurp (str stamp)))))
              (println "[nightly] cached build is current")
              (let [archive (fs/path tmp "jolt.tar.gz")]
                (println "[nightly] downloading" (str stem ".tar.gz"))
                (curl! (str base-url stem ".tar.gz") archive)
                (let [got (sha256 archive)]
                  (when-not (= want got)
                    (fail! "sha256 mismatch: published " want ", downloaded " got)))
                (fs/delete-tree dir)
                (fs/create-dirs dir)
                (p/shell "tar" "xzf" (str archive) "-C" (str dir))
                (spit (str stamp) want)))
            jolt)
          (finally (fs/delete-tree tmp)))))))

(defn -main [& args]
  (let [plat (or (platform)
                 (fail! "no nightly build for " (System/getProperty "os.name")
                        " " (System/getProperty "os.arch")))
        jolt (fetch! plat (cache-dir plat) (some #{"--offline"} args))
        bin (str (fs/parent jolt))
        path (str bin java.io.File/pathSeparator (System/getenv "PATH"))
        version (str/trim (:out (p/shell {:out :string} (str jolt) "--version")))]
    (println "[nightly]" version "from" bin)
    (System/exit
     (:exit (p/shell {:continue true :extra-env {"PATH" path}}
                     (str jolt) "-M:test" "--live")))))

(apply -main *command-line-args*)
