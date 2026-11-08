(ns jonquil.parts.extra
  "Extra window parts, registered before jonquil.parts loads. Production
  never touches these; the window self-test registers its probe part here.
  No GTK, so jonquil.parts can read the specs headless.")

;; Specs appended to jonquil.parts/specs, after the real parts. Read once,
;; when jonquil.parts loads, so register before anything requires it.
(defonce specs* (atom []))

;; Install fns appended to jonquil.gtk.parts/installs, in the same order as
;; specs*.
(defonce installs* (atom []))
