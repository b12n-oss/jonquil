(ns jonquil.gtk.ffi-test
  "The binding layer loads and reaches the native libraries. Needs GTK4 and
  pango installed; no display is required, since nothing here opens a window."
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.ffi :as g]))

(deftest pango-and-gtk-are-reachable
  (is (pos? (g/pango-style-get-type)))
  (is (= 128 g/ITER-BYTES)))

(deftest keyval-to-lower-normalises-caps-lock-test
  ;; M4: Caps Lock reports the uppercase keyval with no Shift bit set.
  (is (= 0x73 (g/gdk-keyval-to-lower 0x53)))   ; 'S' -> 's'
  (is (= 0x73 (g/gdk-keyval-to-lower 0x73)))   ; already lower, unchanged
  (is (= 0xffc2 (g/gdk-keyval-to-lower 0xffc2)))) ; non-letter keyval (F5), unchanged

(deftest g-application-non-unique-flag-round-trips-test
  ;; M6: constructing a GtkApplication needs no display, only GObject.
  (let [app (g/gtk-application-new "net.b12n.jonquil.ffi-test"
                                   (bit-or g/APPLICATION-DEFAULT-FLAGS g/G-APPLICATION-NON-UNIQUE))]
    (is (= 32 g/G-APPLICATION-NON-UNIQUE))
    (is (= g/G-APPLICATION-NON-UNIQUE
           (bit-and (g/g-application-get-flags app) g/G-APPLICATION-NON-UNIQUE)))))
