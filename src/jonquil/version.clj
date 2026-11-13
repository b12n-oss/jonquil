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
  set it for a source run. The build drivers in dev/ must not require this
  namespace (see jonquil.version.describe)."
  (:require [clojure.java.io :as io]
            [jonquil.version.describe :as describe]))

(defmacro ^:private baked-version
  "Expands to JONQUIL_VERSION read at macroexpansion, or nil. It never runs
  git."
  []
  (describe/baked (System/getenv "JONQUIL_VERSION")))

(def built-version
  "The version a build baked in, nil when none did."
  (baked-version))

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
              (describe/describe-checkout
               (try (describe/checkout-dir (some-> (io/resource "jonquil/version.clj") str))
                    (catch :default _ nil))))}))

(defn version-line
  "The line `jonquil --version` and Show Jonquil's version print."
  []
  (str "Jonquil " (current-version)))
