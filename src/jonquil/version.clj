(ns jonquil.version
  "Which Jonquil this is. A build bakes the version in: JONQUIL_VERSION is
  read when this namespace compiles, and every build task of ours sets it
  (to the value already in the environment, else the checkout's git
  describe). It is a build-time override, so set it for a build, not for a
  run. A bare `jolt build` by hand bakes nothing, and that binary then
  reports the checkout it is run in, or \"dev\". A run from source asks git
  about the checkout that holds the source each time, because Jolt's AOT
  cache reuses the compiled namespace until version.clj itself changes.
  One trap, measured: a run from source that compiles this file with
  JONQUIL_VERSION set caches that value until version.clj changes, so don't
  set it for a source run."
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

(defn baked
  "v when it is a non-empty string, else nil: what a build bakes in."
  [v]
  (when (seq v) v))

(defmacro ^:private baked-version
  "Expands to JONQUIL_VERSION read at macroexpansion, or nil. It never runs
  git."
  []
  (baked (System/getenv "JONQUIL_VERSION")))

(def built-version
  "The version a build baked in, nil when none did."
  (baked-version))

(defn checkout-dir
  "The checkout a source file URL (a string) lives in, when it is a file: URL
  under src/jonquil/, else nil."
  [url]
  (when-let [m (and url (re-matches #"file:(.*)/src/jonquil/version\.clj" url))]
    (str/replace (second m) #"%([0-9A-Fa-f]{2})"
                 (fn [[_ h]] (str (char (Integer/parseInt h 16)))))))

(defn pick-version
  "The version to show: the :built one when a build baked it in, else the
  :source checkout's describe, else \"dev\"."
  [{:keys [built source]}]
  (or built source "dev"))

(defn current-version
  "The version string now: see pick-version. Never throws, never reads the
  environment."
  []
  (pick-version
   {:built built-version
    :source (when-not built-version
              (when-let [dir (try (checkout-dir (some-> (io/resource "jonquil/version.clj") str))
                                  (catch :default _ nil))]
                (git-describe dir)))}))

(defn version-line
  "The line `jonquil --version` and Show Jonquil's version print."
  []
  (str "Jonquil " (current-version)))
