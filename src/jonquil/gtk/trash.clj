(ns jonquil.gtk.trash
  "Moves a file to the Trash through GLib's g_file_trash. It needs GLib
  only, so GTK doesn't have to be initialised."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.ffi :as g]))

(defn trash!
  "Returns {:ok true} or {:error msg}. Never throws.
  GError message pointer sits at byte 8 (glib/gerror.h:45-50)."
  [path]
  (try
    (let [slot (ffi/alloc 8)
          file (g/g-file-new-for-path path)]
      (try
        (ffi/write slot :pointer ffi/null 0)
        (if (zero? (g/g-file-trash file ffi/null slot))
          (let [err (ffi/read slot :pointer)]
            (if (ffi/null? err)
              {:error "trash failed without a GError"}
              (try {:error (ffi/ptr->string (ffi/read err :pointer 8))}
                   (finally (g/g-error-free err)))))
          {:ok true})
        (finally
          (g/g-object-unref file)
          (ffi/free slot))))
    (catch :default e
      ;; :default, so a throw that isn't an Exception (an AssertionError)
      ;; is still an :error, keeping its message.
      {:error (or (ex-message e) "trash failed")})))
