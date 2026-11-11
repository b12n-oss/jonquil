(ns jonquil.instrument-lock
  "The one lock around Jolt's instrumentation mode (jolt.profile's
  instrument-mode and instrument!). The mode is the whole process's, and
  line counting and debug mode both read it before they set it, so every
  read-then-set holds this lock. Without it two toggles at once both read
  the old mode and one press was lost.")

;; Held around each read-then-set of the mode; locking is reentrant, so a
;; toggle may call its set fn while holding it.
(defonce lock (Object.))
