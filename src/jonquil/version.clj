(ns jonquil.version
  "Which Jonquil this is. The version is fixed when this namespace compiles,
  so a binary built by `jolt build` carries it and needs no git at runtime.
  Running from source evaluates it at load, which gives the same answer."
  (:require [clojure.string :as str]
            [jolt.process :as p]))

(defn version-from-describe
  "`git describe` output as a version: a release tag's leading v
  goes (v0.1.0 -> 0.1.0), since CFBundleShortVersionString wants the bare
  number. Anything else passes through."
  [s]
  (str/replace-first s #"^v(?=\d)" ""))

(defn- git-describe
  "The trimmed version from `git describe --tags --always`, or nil when git
  is missing, this isn't a repo, or git fails. Never throws."
  []
  (try
    (let [r (p/sh "git" "describe" "--tags" "--always")
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
      (git-describe)
      "dev"))

(def version
  "Jonquil's version string, fixed at compile time."
  (compile-time-version))

(defn version-line
  "The line `jonquil --version` and Show Jonquil's Version print."
  []
  (str "Jonquil " version))
