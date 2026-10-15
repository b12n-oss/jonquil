(ns jonquil.gtk.appearance
  "Asks macOS whether it is showing Light or Dark, for the `auto` theme.
  GTK reports gtk-interface-color-scheme as UNSUPPORTED on macOS, so this
  goes through the Objective-C runtime instead (probed 2026-10-15, see the
  auto-theme spec). A missing symbol fails at the call, not at load, so
  this namespace loads on Linux too."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]))

(ffi/defcfn objc-get-class "objc_getClass" [:string] :pointer)
(ffi/defcfn sel-register-name "sel_registerName" [:string] :pointer)
(ffi/defcfn msg-send "objc_msgSend" [:pointer :pointer] :pointer)

(defn system-dark?
  "True when macOS is showing the Dark appearance, false when Light, nil when
  it can't tell (not macOS, or any step returned null or threw). Reads
  [[NSApplication sharedApplication] effectiveAppearance] name and checks
  for \"Dark\", which covers NSAppearanceNameDarkAqua and the high-contrast
  dark variants. Must run on the GTK thread after GTK is up (probed
  2026-10-15)."
  []
  (when (= "Mac OS X" (System/getProperty "os.name"))
    (try
      (let [app (msg-send (objc-get-class "NSApplication") (sel-register-name "sharedApplication"))]
        (when-not (ffi/null? app)
          (let [appearance (msg-send app (sel-register-name "effectiveAppearance"))]
            (when-not (ffi/null? appearance)
              (let [nm (msg-send appearance (sel-register-name "name"))]
                (when-not (ffi/null? nm)
                  (let [cstr (msg-send nm (sel-register-name "UTF8String"))]
                    (when-not (ffi/null? cstr)
                      (str/includes? (ffi/ptr->string cstr) "Dark")))))))))
      (catch Throwable _ nil))))
