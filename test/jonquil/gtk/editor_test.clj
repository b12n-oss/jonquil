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
  ;; guard
  (is (= "f" (editor/physical-key "Mac OS X" 0x8f6 3 cmd-alt)))
  (is (= "l" (editor/physical-key "Mac OS X" 0xac 37 cmd-alt)))
  (is (nil? (editor/physical-key "Linux" 0xac 37 ctrl-alt)))
  (is (nil? (editor/physical-key "Mac OS X" 0xffe3 37 cmd-alt)) "Control_L's own press")
  (is (nil? (editor/physical-key "Mac OS X" 0x8f6 3 0)) "no Option held")
  (is (= "p" (editor/physical-key "Mac OS X" 0x3c0 35 cmd-alt)) "Option+P arrives as pi")
  ;; the panel keys' digits; Option+5 types U+221E, a keyval above 0x1000
  (is (= ["1" "2" "3" "4" "5" "6" "7"]
         (map #(editor/physical-key "Mac OS X" 0xa1 % cmd-alt) [18 19 20 21 23 22 26])))
  (is (= "5" (editor/physical-key "Mac OS X" 0x100221e 23 cmd-alt)) "Option+5's infinity")
  (is (nil? (editor/physical-key "Mac OS X" 0xffe9 18 cmd-alt)) "a modifier keyval is never a digit")
  (is (nil? (editor/physical-key "Linux" 0x31 18 ctrl-alt)) "Linux keeps the plain keyval"))
