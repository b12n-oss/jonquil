(ns jonquil.version
  "Which Jonquil this is. A binary built by `jolt build` carries the version
  fixed when this namespace compiled and never runs git. A run from source
  can't use that string, because Jolt's AOT cache reuses the compiled
  namespace until version.clj itself changes, so it asks git about the
  checkout that holds the source, each time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]))

(defn version-from-describe
  "`git describe` output as a version: a release tag's leading v
  goes (v0.1.0 -> 0.1.0), since CFBundleShortVersionString wants the bare
  number. Anything else passes through."
  [s]
  (str/replace-first s #"^v(?=\d)" ""))

(defn- git-describe
  "The trimmed version from `git describe --tags --always` run in dir (the
  process's directory when nil), or nil when git is missing, dir isn't a
  repo, or git fails. Never throws."
  [dir]
  (try
    (let [r (if dir
              (p/sh {:dir dir} "git" "describe" "--tags" "--always")
              (p/sh "git" "describe" "--tags" "--always"))
          out (str/trim (str (:out r)))]
      (when (and (zero? (:exit r)) (seq out))
        (version-from-describe out)))
    (catch :default _ nil)))

(defmacro ^:private compile-time-version
  "Expands to the version string: JONQUIL_VERSION when set, else the git
  describe, else \"dev\". It runs at macroexpansion, which is why a built
  binary holds the string."
  []
  (or (let [v (System/getenv "JONQUIL_VERSION")] (when (seq v) v))
      (git-describe nil)
      "dev"))

(def version
  "Jonquil's version string as fixed at compile time. A run from source
  should use current-version instead."
  (compile-time-version))

(defn checkout-dir
  "The checkout a source file URL (a string) lives in, when it is a file: URL
  under src/jonquil/, else nil. A built binary's resource is no such file."
  [url]
  (when-let [m (and url (re-matches #"file:(.*)/src/jonquil/version\.clj" url))]
    (str/replace (second m) #"%([0-9A-Fa-f]{2})"
                 (fn [[_ h]] (str (char (Integer/parseInt h 16)))))))

(defn pick-version
  "The version to show. From source (a :checkout dir) the JONQUIL_VERSION
  :override wins, then the checkout's :describe, then \"dev\"; the :compiled
  string is stale there. A built binary (no :checkout) shows :compiled."
  [{:keys [checkout override describe compiled]}]
  (if checkout
    (or override describe "dev")
    compiled))

(defn current-version
  "The version string now: see pick-version. Never throws."
  []
  (let [checkout (try (checkout-dir (some-> (io/resource "jonquil/version.clj") str))
                      (catch :default _ nil))]
    (pick-version {:checkout checkout
                   :override (let [v (System/getenv "JONQUIL_VERSION")] (when (seq v) v))
                   :describe (when checkout (git-describe checkout))
                   :compiled version})))

(defn version-line
  "The line `jonquil --version` and Show Jonquil's version print."
  []
  (str "Jonquil " (current-version)))
