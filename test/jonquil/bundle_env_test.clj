(ns jonquil.bundle-env-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [jolt.process :as p]
            [jonquil.bundle-env :as be]))

(ffi/defcfn ^:private setenv* "setenv" [:string :string :int] :int)
(ffi/defcfn ^:private unsetenv* "unsetenv" [:string] :int)

(defn- child-dyld
  "DYLD_LIBRARY_PATH as a spawned child sees it, nil when unset. The child
  is jolt, not /usr/bin/env: macOS strips DYLD_* from a protected system
  binary whatever its parent holds."
  []
  (let [v (str/trim (:out (p/sh "jolt" "-e" "(print (or (System/getenv \"DYLD_LIBRARY_PATH\") \"\"))")))]
    (when (seq v) v)))

(deftest a-bundle-launch-keeps-dyld-out-of-children-test
  (let [old (System/getenv "DYLD_LIBRARY_PATH")]
    (try
      (setenv* "DYLD_LIBRARY_PATH" "/tmp/jonquil-test-fw" 1)
      (unsetenv* "JONQUIL_BUNDLE_FRAMEWORKS")
      (be/drop-bundle-dyld!)
      (is (= "/tmp/jonquil-test-fw" (child-dyld)) "without the launcher's marker it's left alone")
      (setenv* "JONQUIL_BUNDLE_FRAMEWORKS" "/tmp/jonquil-test-fw" 1)
      (be/drop-bundle-dyld!)
      (is (nil? (System/getenv "DYLD_LIBRARY_PATH")))
      (is (nil? (child-dyld)) "a child Jonquil starts (git, node, jank) loads its own libraries")
      (finally
        (unsetenv* "JONQUIL_BUNDLE_FRAMEWORKS")
        (if old (setenv* "DYLD_LIBRARY_PATH" old 1) (unsetenv* "DYLD_LIBRARY_PATH"))))))
