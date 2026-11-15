(ns jonquil.bundle-env
  "A release Jonquil.app's launcher (dev/app_release.clj) puts the bundle's
  Frameworks on DYLD_LIBRARY_PATH so jonquil-bin finds the bundled GTK, and
  marks that with JONQUIL_BUNDLE_FRAMEWORKS. dyld matches that variable by
  file name for every process that inherits it, so git, node or jank that
  Jonquil starts would load Jonquil's copies of libintl, pcre2 or zstd in
  place of their own. Once the libraries are loaded (they find each other
  by @loader_path after that), Jonquil drops it from its own environment,
  which its children inherit, and the marker with it."
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn ^:private unsetenv* "unsetenv" [:string] :int)

;; The bundle's Frameworks folder, kept here once drop-bundle-dyld! has
;; taken the launcher's marker out of the environment.
(defonce ^:private kept (atom nil))

(defn frameworks
  "The Frameworks folder of the release Jonquil.app this process is, or nil
  when it isn't one: what the launcher's JONQUIL_BUNDLE_FRAMEWORKS named."
  []
  (or @kept (System/getenv "JONQUIL_BUNDLE_FRAMEWORKS")))

(defn drop-bundle-dyld!
  "When the release launcher started Jonquil (JONQUIL_BUNDLE_FRAMEWORKS is
  set): keeps the marker's value for frameworks, then unsets
  DYLD_LIBRARY_PATH and the marker itself. Children inherit neither, so a
  shell in Jonquil's terminal doesn't make a Jonquil started from source
  there think it is the release app. Leaves both alone otherwise."
  []
  (when-let [dir (System/getenv "JONQUIL_BUNDLE_FRAMEWORKS")]
    (reset! kept dir)
    (unsetenv* "DYLD_LIBRARY_PATH")
    (unsetenv* "JONQUIL_BUNDLE_FRAMEWORKS"))
  nil)
