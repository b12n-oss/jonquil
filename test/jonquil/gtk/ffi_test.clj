(ns jonquil.gtk.ffi-test
  "The binding layer loads and reaches the native libraries. Needs GTK4 and
  pango installed; no display is required, since nothing here opens a window."
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.ffi :as g]))

(deftest pango-and-gtk-are-reachable
  (is (pos? (g/pango-style-get-type)))
  (is (= 128 g/ITER-BYTES)))
