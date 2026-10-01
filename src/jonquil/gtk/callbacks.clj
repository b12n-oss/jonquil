(ns jonquil.gtk.callbacks
  "GTK main-loop plumbing shared by every jonquil.gtk.* namespace: a macro
  that turns a Clojure fn into a retained, :collect-safe foreign-callable,
  and on-gui!, which lets any thread ask the GTK main thread to run a thunk.

  ABI rule (AGENTS.md / global.md): every foreign-callable must be
  :collect-safe and kept alive in an atom for the process lifetime, or GTK's
  own event loop can invoke a callback jolt has already garbage-collected.
  Engine callbacks that run on a worker thread (P2.T6's eval output, for
  instance) must only enqueue -- never print, block, or invoke-and-wait on
  the GTK thread themselves (the lock-caller rule) -- and on-gui! is that
  enqueue point."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.ffi :as g]))

;; Every foreign-callable ever made through `callable`, kept alive for the
;; process lifetime. Not ^:private: the macro's expansion runs in whatever
;; namespace calls `callable`, and Jolt (like JVM Clojure) rejects a
;; cross-namespace reference to a private var even when it only appears
;; through a macroexpansion.
(defonce retained (atom []))

(defn report-callback-exception!
  "Reports an exception a foreign-callable's body threw, on the process's
  real *err*. callable's backstop calls it; it never throws itself."
  [e]
  (try
    (binding [*out* *err*]
      (println (str "jonquil.gtk.callbacks: a GTK callback threw: " (ex-message e))))
    (catch :default _ nil)))

(defn- default-on-throw
  "What a callback answers GTK when its body threw and the call site named
  no value: nothing for :void, 0 for an int (a key handler's \"not
  handled\"), and a null pointer for :pointer."
  [ret]
  (case ret
    :void nil
    (:int :uint :long :ulong) 0
    :pointer `ffi/null
    nil))

(defmacro callable
  "Wraps f as a :collect-safe foreign-callable with signature sig and return
  ret, retaining it for the process lifetime so GTK's own event loop never
  invokes a collected callback. foreign-callable needs its signature as a
  literal, so this has to be a macro (from the S2 spike).

  Every callable is also contained: a throw from f never crosses the FFI
  boundary into GTK, where it would kill the process. It's reported on
  *err* (report-callback-exception!) and the callback answers :on-throw, or
  default-on-throw's value for ret when the call site names none. A site
  where that default is wrong passes its own, as a keyword option: a
  GSource that must keep running is (callable f sig :int :on-throw 1), and
  so is a close-request that must not close. The wrapper has sig's own
  fixed arity, so foreign-callable sees the same shape f had."
  [f sig ret & {:keys [on-throw] :or {on-throw ::default}}]
  (let [on-throw (if (= on-throw ::default) (default-on-throw ret) on-throw)
        args (vec (repeatedly (count sig) #(gensym "arg")))]
    `(let [f# ~f
           c# (ffi/foreign-callable
               (fn ~args
                 (try (f# ~@args)
                      (catch :default e#
                        (report-callback-exception! e#)
                        ~on-throw)))
               ~sig ~ret :collect-safe)]
       (swap! retained conj c#)
       c#)))

;; --- on-gui!: hand a thunk to the GTK main thread from any thread --------

(defonce ^:private pending (atom []))
(defonce ^:private drain-scheduled (atom false))

(defn- report-thunk-exception!
  "Reports an exception an on-gui! thunk threw, on the process's real *err*,
  then lets the drain continue with the next thunk. Mirrors
  jonquil.eval/report-callback-exception! -- a throwing thunk loses only
  itself."
  [e]
  (try
    (binding [*out* *err*]
      (println (str "jonquil.gtk.callbacks: on-gui! thunk threw: " (ex-message e))))
    (catch :default _ nil)))

(defn- drain-pending [_data]
  ;; Flip the flag BEFORE taking the queue. A thread's on-gui! landing
  ;; between the two would otherwise conj onto a queue we've already
  ;; emptied while compare-and-set! still sees the (stale) true flag, so
  ;; its thunk schedules nothing and is stranded until some unrelated
  ;; later on-gui! happens to drain it. Flipped first, that same race
  ;; either lands the thunk in the reset-vals! below or wins its own
  ;; compare-and-set! and schedules a second, harmless, possibly-empty
  ;; drain.
  (reset! drain-scheduled false)
  (let [thunks (first (reset-vals! pending []))]
    (doseq [f thunks]
      (try (f) (catch :default e (report-thunk-exception! e)))))
  0)

(defonce ^:private drain-cb (callable drain-pending [:pointer] :int))

(defn on-gui!
  "Runs f on the GTK main thread. From any thread it enqueues f and
  schedules one g_idle_add drain (the S2 spike's pending/drain-scheduled
  pattern, generalized from console output chunks to arbitrary thunks). The
  drain runs every queued thunk in order; an exception in one thunk is
  reported on *err* and does not stop the rest."
  [f]
  (swap! pending conj f)
  (when (compare-and-set! drain-scheduled false true)
    (g/g-idle-add drain-cb ffi/null)))
