(ns jonquil.bundle-env
  "A release Jonquil.app's launcher (dev/app_release.clj) puts the bundle's
  Frameworks on DYLD_LIBRARY_PATH so jonquil-bin finds the bundled GTK, and
  marks that with JONQUIL_BUNDLE_FRAMEWORKS. dyld matches that variable by
  file name for every process that inherits it, so git, node or jank that
  Jonquil starts would load Jonquil's copies of libintl, pcre2 or zstd in
  place of their own. Once the libraries are loaded (they find each other
  by @loader_path after that), Jonquil drops it from its own environment,
  which its children inherit."
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn ^:private unsetenv* "unsetenv" [:string] :int)

(defn drop-bundle-dyld!
  "Unsets DYLD_LIBRARY_PATH when the release launcher started Jonquil
  (JONQUIL_BUNDLE_FRAMEWORKS is set); leaves it alone otherwise."
  []
  (when (System/getenv "JONQUIL_BUNDLE_FRAMEWORKS")
    (unsetenv* "DYLD_LIBRARY_PATH"))
  nil)
