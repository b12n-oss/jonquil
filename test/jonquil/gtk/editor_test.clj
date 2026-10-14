(ns jonquil.gtk.editor-test
  "The theme chord's keycode fallback is macOS-only. Needs GTK4 installed,
  no display."
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(def ^:private cmd-alt (bit-or g/META-MASK g/ALT-MASK))
(def ^:private ctrl-alt (bit-or g/CONTROL-MASK g/ALT-MASK))

(deftest keycode-fallback-is-macos-only-test
  (is (true? (editor/keycode-fallback? "Mac OS X" 3 cmd-alt)))
  (is (true? (editor/keycode-fallback? "Mac OS X" 37 cmd-alt)))
  (is (not (editor/keycode-fallback? "Linux" 37 ctrl-alt)))
  (is (not (editor/keycode-fallback? "Linux" 3 ctrl-alt)))
  (is (not (editor/keycode-fallback? "Mac OS X" 38 cmd-alt)))
  (is (not (editor/keycode-fallback? "Mac OS X" 37 g/META-MASK)))
  (is (not (editor/keycode-fallback? nil 37 cmd-alt))))
