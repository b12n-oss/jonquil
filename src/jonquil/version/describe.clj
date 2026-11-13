(ns jonquil.version.describe
  "The helpers behind jonquil.version that never read JONQUIL_VERSION when
  they compile. The build drivers in dev/ require this namespace and not
  jonquil.version, whose macro would otherwise compile inside a driver that
  inherits the caller's JONQUIL_VERSION and leave that value in Jolt's AOT
  cache for later runs from source."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]))

(defn version-from-describe
  "`git describe` output as a version: a release tag's leading v
  goes (v0.1.0 -> 0.1.0), since CFBundleShortVersionString wants the bare
  number. Anything else passes through."
  [s]
  (str/replace-first s #"^v(?=\d)" ""))

(defn git-describe
  "The trimmed version from `git describe --tags --always` run in dir, or
  nil when git is missing, dir isn't a repo, or git fails. Never throws."
  [dir]
  (try
    (let [r (p/sh {:dir dir} "git" "describe" "--tags" "--always")
          out (str/trim (str (:out r)))]
      (when (and (zero? (:exit r)) (seq out))
        (version-from-describe out)))
    (catch :default _ nil)))

(defn describe-checkout
  "git-describe of dir, only when dir has its own .git (a directory, or a
  file in a worktree). A tree unpacked inside another repo would otherwise
  report the parent repo's version. Nil otherwise, and never throws."
  [dir]
  (try
    (when (and dir (.exists (io/file dir ".git")))
      (git-describe dir))
    (catch :default _ nil)))

(defn baked
  "v when it is a non-empty string, else nil: what a build bakes in."
  [v]
  (when (seq v) v))

(def ^:private source-suffix "/src/jonquil/version.clj")

(defn checkout-dir
  "The checkout a source file URL (a string) lives in, when it is a file: URL
  of src/jonquil/version.clj, else nil. Escapes are decoded as UTF-8."
  [url]
  (try
    (when (and url (str/starts-with? url "file:"))
      (let [path (.getPath (java.net.URI. url))]
        (when (and path (str/ends-with? path source-suffix))
          (subs path 0 (- (count path) (count source-suffix))))))
    (catch :default _ nil)))

(defn build-version
  "What a build should bake in: env-value (JONQUIL_VERSION) when non-empty,
  else (describe), else nil."
  [env-value describe]
  (or (baked env-value) (describe)))

(defn build-env
  "The environment map for a `jolt build` of Jonquil: JONQUIL_VERSION set to
  build-version, or {} when there is none (which bakes nothing)."
  [env-value describe]
  (if-let [v (build-version env-value describe)]
    {"JONQUIL_VERSION" v}
    {}))
