(ns jonquil.gtk.ffi-test
  "The binding layer loads and reaches the native libraries. Needs GTK4 and
  pango installed; no display is required, since nothing here opens a window."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.ffi :as g]))

(deftest pango-and-gtk-are-reachable
  (is (pos? (g/pango-style-get-type)))
  (is (= 128 g/ITER-BYTES)))

(deftest keyval-to-lower-normalises-caps-lock-test
  ;; Caps Lock reports the uppercase keyval with no Shift bit set.
  (is (= 0x73 (g/gdk-keyval-to-lower 0x53)))   ; 'S' -> 's'
  (is (= 0x73 (g/gdk-keyval-to-lower 0x73)))   ; already lower, unchanged
  (is (= 0xffc2 (g/gdk-keyval-to-lower 0xffc2)))) ; non-letter keyval (F5), unchanged

(deftest g-application-non-unique-flag-round-trips-test
  ;; constructing a GtkApplication needs no display, only GObject.
  (let [app (g/gtk-application-new "net.b12n.jonquil.ffi-test"
                                   (bit-or g/APPLICATION-DEFAULT-FLAGS g/G-APPLICATION-NON-UNIQUE))]
    (is (= 32 g/G-APPLICATION-NON-UNIQUE))
    (is (= g/G-APPLICATION-NON-UNIQUE
           (bit-and (g/g-application-get-flags app) g/G-APPLICATION-NON-UNIQUE)))))

(ffi/defcfn ^:private text-buffer-new "gtk_text_buffer_new" [:pointer] :pointer)

(deftest text-survives-a-collection-in-an-insert-handler-test
  ;; gtk_text_buffer_set_text and gtk_text_buffer_insert emit insert-text
  ;; before they copy the text, and the editor has a handler on it. With the
  ;; text passed as a :string argument, a collection in that handler moved it
  ;; and GTK read freed memory: the process died with "invalid memory
  ;; reference" within 300 inserts. The wrappers pass a C string instead.
  (let [buf (text-buffer-new ffi/null)
        garbage (atom nil)
        handler (cb/callable (fn [_buf _iter _text _len _data]
                               (System/gc)
                               ;; fill what the collection freed with other bytes
                               (reset! garbage (vec (repeatedly 500 #(apply str (repeat 400 (char 255))))))
                               nil)
                             [:pointer :pointer :pointer :int :pointer] :void)
        text-of (fn [i] (str/join "\n" (map (fn [j] (str "line " i " " j " abcdefghijklmnopqrstuvwxyz")) (range 100))))]
    (g/g-signal-connect-data buf "insert-text" handler ffi/null ffi/null g/CONNECT-DEFAULT)
    (try
      (testing "set-text"
        (is (every? (fn [i] (let [t (text-of i)]
                              (g/gtk-text-buffer-set-text buf t -1)
                              (= t (g/buffer-text buf))))
                    (range 60))))
      (testing "insert at the end"
        (g/gtk-text-buffer-set-text buf "" -1)
        (let [parts (mapv #(str "part " % " abcdefghij\n") (range 60))]
          (doseq [p parts]
            (g/with-iters* buf (g/gtk-text-buffer-get-char-count buf) (g/gtk-text-buffer-get-char-count buf)
              (fn [a _] (g/gtk-text-buffer-insert buf a p -1))))
          (is (= (apply str parts) (g/buffer-text buf)))))
      (finally (g/g-object-unref buf)))))

