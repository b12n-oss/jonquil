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

(deftest physical-key-test
  ;; the Option-translated keyvals measured on macOS, and the Control_L
  ;; guard (Review Focus 1, 2)
  (is (= "f" (editor/physical-key "Mac OS X" 0x8f6 3 cmd-alt)))
  (is (= "l" (editor/physical-key "Mac OS X" 0xac 37 cmd-alt)))
  (is (nil? (editor/physical-key "Linux" 0xac 37 ctrl-alt)))
  (is (nil? (editor/physical-key "Mac OS X" 0xffe3 37 cmd-alt)) "Control_L's own press")
  (is (nil? (editor/physical-key "Mac OS X" 0x8f6 3 0)) "no Option held")
  (is (= "p" (editor/physical-key "Mac OS X" 0x3c0 35 cmd-alt)) "Option+P arrives as pi"))
