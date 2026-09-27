(ns gtk-async
  "Go-style plumbing for the dev GTK harnesses (dev/gtk_selftest*.clj,
  dev/demo_shots.clj): a row is a go block that steps top to bottom, and
  never a chain of nested callbacks.

  The one hard rule: the GTK thread never parks. A go block runs on its own
  OS thread (the default :thread backend, never the GTK thread), so every
  GTK, editor, console or `since` call goes through on-gtk or await-until,
  which run it on the GTK thread via jonquil.gtk.callbacks/on-gui! and park
  the go block until it's done. call-gtk refuses to run on the GTK thread,
  because a take there freezes the main loop (spike probe: a 300 ms <! on
  the GTK thread advanced 0 idle ticks).

  Jolt only: call-gtk, await-until, settle and sh are plain fns with a <!
  inside, which Jolt allows (fibers.md, 'Parking works through function
  calls'). Call them only from inside a go block."
  (:require [clojure.core.async :as a :refer [<! go promise-chan put! timeout]]
            [clojure.java.shell :as shell]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]))

(ffi/defcfn ^:private pthread-main-np "pthread_main_np" [] :int)

(defn gtk-thread?
  "True on GTK's thread. On macOS that's the process main thread, because
  g_application_run is called from -main (spike: activate and every on-gui!
  thunk ran with pthread_main_np = 1, go bodies with 0)."
  []
  (= 1 (pthread-main-np)))

(defn gui-ch
  "Runs f on the GTK thread via on-gui! and returns a promise-chan of {:ok v}
  or {:err e}. The try matters: on-gui!'s drain swallows a throw, and then
  nothing would ever be put here."
  [f]
  (let [p (promise-chan)]
    (cb/on-gui! (fn [] (put! p (try {:ok (f)} (catch :default e {:err e})))))
    p))

(defn call-gtk
  "Parks the calling go block until f has run on the GTK thread, then
  returns f's value, or rethrows f's exception here. Throws at once when
  called on the GTK thread."
  [f]
  (when (gtk-thread?)
    (throw (ex-info "call-gtk on the GTK thread would block the main loop" {})))
  (let [{:keys [ok err]} (<! (gui-ch f))]
    (if err (throw err) ok)))

(defmacro on-gtk
  "Runs body on the GTK thread as one thunk, in one main-loop iteration, and
  parks until it's done. Its value is body's value."
  [& body]
  `(call-gtk (fn [] ~@body)))

(defn await-until
  "Parks until (pred), run on the GTK thread each time, is truthy, or
  deadline-ms has passed; checks every interval-ms. true or false, like
  poll-until's done argument. The first check runs at once."
  [deadline-ms interval-ms pred]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond (call-gtk pred) true
            (>= (- (System/currentTimeMillis) t0) deadline-ms) false
            :else (do (<! (timeout interval-ms)) (recur))))))

(defn settle
  "Parks for n more 20 ms ticks after a text condition passed, so the frame
  repaints before a screenshot reads it. A settle for painting, never a
  sleep for correctness."
  [n]
  (<! (timeout (* 20 n))))

(defn sh
  "clojure.java.shell/sh on its own thread (a/thread); parks until the
  subprocess exits and returns its result map."
  [& args]
  (<! (a/thread (apply shell/sh args))))

(defn screenshot
  "cgevent screenshot of pid's window into path; parks until it exits."
  [path pid]
  (sh "cgevent" "screenshot" path "--pid" (str pid)))

(defmacro go-row
  "A row: a go block that returns :done, or records (record! row-id false
  {:threw msg}) and returns :threw when body throws. A throwing go body
  would otherwise leave nil on its channel and only a stderr line."
  [row-id record! & body]
  `(go (try ~@body :done
            (catch :default e#
              (~record! ~row-id false {:threw (ex-message e#)})
              :threw))))

(defn from-callback
  "The migration shim: wraps a callback-style row (fn [ui next] ...) as a
  go-style one (fn [ui] ch). The row starts on the GTK thread, exactly as
  it did from the old driver, and its (next) puts :done on the channel. A
  throw from the row's first step records (record! :row-threw false
  {:threw msg}); a throw from a later GTK callback still ends the process,
  as it always has."
  [record! row-fn]
  (fn [ui]
    (let [done (promise-chan)]
      (cb/on-gui!
       (fn []
         (try (row-fn ui (fn [] (put! done :done)))
              (catch :default e
                (record! :row-threw false {:threw (ex-message e)})
                (put! done :threw)))))
      done)))

(defn run-rows
  "Runs steps strictly in order. Each step is a zero-argument fn returning a
  channel, and the next step starts only once that channel delivers or
  per-step-ms has passed. A step that times out is recorded as
  (record! :row-timeout false {:index i :timeout-ms per-step-ms}), and the
  run goes on with the next step. Returns a channel that delivers :done
  after the last step."
  [steps per-step-ms record!]
  (go (loop [i 0 steps (seq steps)]
        (when steps
          (let [ch ((first steps))
                [_ port] (a/alts! [ch (timeout per-step-ms)])]
            (when-not (= port ch)
              (record! :row-timeout false {:index i :timeout-ms per-step-ms}))
            (recur (inc i) (next steps)))))
      :done))
