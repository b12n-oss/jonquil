(ns gtk-async
  "Go-style plumbing for the dev GTK harnesses (dev/gtk_selftest*.clj,
  dev/demo_shots.clj): a row is a go block that steps top to bottom, and
  never a chain of nested callbacks.

  The one hard rule: the GTK thread never parks. A go block runs on its own
  OS thread (the default :thread backend, never the GTK thread), so every
  GTK, editor, console or `since` call goes through on-gtk or await-until,
  which run it on the GTK thread via jonquil.gtk.callbacks/on-gui! and park
  the go block until it's done. call-gtk refuses to run on the GTK thread,
  because a take there freezes the main loop (measured: a 300 ms <! on
  the GTK thread advanced 0 idle ticks).

  Jolt only: call-gtk, await-until, settle, sh and close-window-bounded!
  are plain fns with a <! inside, which Jolt allows (fibers.md, 'Parking
  works through function calls'). Call them only from inside a go block."
  (:require [clojure.core.async :as a :refer [<! go promise-chan put! timeout]]
            [clojure.java.shell :as shell]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.ffi :as g]))

(ffi/defcfn ^:private g-main-context-default "g_main_context_default" [] :pointer)
(ffi/defcfn ^:private g-main-context-is-owner "g_main_context_is_owner" [:pointer] :int)

(defn gtk-thread?
  "True on GTK's thread, the one running g_application_run. It asks GLib
  who owns the default main context. pthread_main_np exists only on
  macOS, and this check was measured on both systems: 0 before g_application_run, 1 inside an open handler."
  []
  (= 1 (g-main-context-is-owner (g-main-context-default))))

;; --- row abandonment -------------------------------------------------------
;; run-rows gives each row a generation and bumps its run's counter when the
;; row outlives its deadline, so a stale body can't act into the next row:
;; the parking helpers (call-gtk and everything over it, sh, and the nREPL
;; rows' sh-within) check the row is still current and otherwise throw a
;; marked error that go-row swallows (the :row-timeout FAIL already told
;; the story). The throw fires ONCE per row: it unwinds the body at its
;; first helper call, and the row's own finally then runs its cleanup
;; helpers unchecked, so an abandoned row still closes its tabs and windows
;; (skipping cleanup left state that poisoned every
;; later row, worse than a late cleanup). Bindings convey
;; into go blocks and across parks (probed), so the row's
;; identity rides a dynamic var; code outside a row has no binding and is
;; never checked, and each run-rows owns its counter, so two runs could
;; never stale each other's rows.

(def ^:dynamic ^:private *row-ctx*
  "nil outside rows; [run-counter-atom gen thrown?-atom] inside one."
  nil)

(defn check-row!
  "The abandonment check every parking helper runs (public for sh-within
  in the nREPL rows): throws, once, when this row was timed out by
  run-rows; a no-op outside rows and after the row's single throw."
  []
  (when-let [[counter g thrown?] *row-ctx*]
    (when (and (not= g @counter) (compare-and-set! thrown? false true))
      (throw (ex-info "the row outlived its timeout and was abandoned" {::abandoned true})))))

(defn abandoned-throw?
  "True for the error check-row! throws."
  [e]
  (boolean (::abandoned (ex-data e))))

(defn assert-off-gtk!
  "Throws when called on the GTK thread, where a parking helper's take
  would freeze the main loop (measured: a 300 ms <! there advanced 0
  idle ticks). call-gtk always checked; sh and settle here, and the nREPL
  rows' sh-within, refuse by construction too. who names the helper."
  [who]
  (when (gtk-thread?)
    (throw (ex-info (str who " on the GTK thread would block the main loop") {}))))

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
  (check-row!)
  (assert-off-gtk! "call-gtk")
  (let [{:keys [ok err]} (<! (gui-ch f))]
    (check-row!)
    (if err (throw err) ok)))

(defmacro on-gtk
  "Runs body on the GTK thread as one thunk, in one main-loop iteration, and
  parks until it's done. Its value is body's value."
  [& body]
  `(call-gtk (fn [] ~@body)))

(defn await-until
  "Parks until (pred), run on the GTK thread each time, is truthy, or
  deadline-ms has passed; checks every interval-ms. Returns true or false. The first check
  runs at once."
  [deadline-ms interval-ms pred]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond (call-gtk pred) true
            (>= (- (System/currentTimeMillis) t0) deadline-ms) false
            :else (do (<! (timeout interval-ms)) (recur))))))

(defn ready-for-row?
  "A row's start: parks until (idle?) or deadline-ms. An engine still busy
  then was left running by an earlier row (one that threw before its own
  Stop, say), so this presses Stop (stop!, on the GTK thread), says so on
  stderr, and waits deadline-ms again. Still busy after that, it records
  (record! :row-skipped-busy false ...) and returns false, and the caller
  skips the row. Without the Stop every later row failed with
  \"Evaluation already in progress\" after waiting out its own deadlines."
  [{:keys [idle? stop! deadline-ms]} record!]
  (or (await-until deadline-ms 20 idle?)
      (do (binding [*out* *err*]
            (println "[selftest] engine still busy" deadline-ms "ms after the last row; pressing Stop"))
          (on-gtk (stop!))
          (or (await-until deadline-ms 20 idle?)
              (do (record! :row-skipped-busy false
                           {:reason "engine still busy after Stop; row skipped" :deadline-ms deadline-ms})
                  false)))))

(defn settle
  "Parks for n more 20 ms ticks after a text condition passed, so the frame
  repaints before a screenshot reads it. A settle for painting, never a
  sleep for correctness.

  It parks on wall-clock time, so the wait alone proves nothing about the
  main loop: a frozen main loop would pass it the same as a healthy one.
  The trailing on-gtk round trip restores that proof: it can only return
  once the GTK thread has actually run the thunk."
  [n]
  (assert-off-gtk! "settle")
  (<! (timeout (* 20 n)))
  (on-gtk nil))

(defn sh
  "clojure.java.shell/sh on its own thread (a/thread); parks until the
  subprocess exits and returns its result map."
  [& args]
  (assert-off-gtk! "sh")
  (check-row!)
  (let [r (<! (a/thread (apply shell/sh args)))]
    (check-row!)
    r))

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
              (if (abandoned-throw? e#)
                :abandoned
                (do (~record! ~row-id false {:threw (ex-message e#)})
                    :threw))))))

(defn run-rows
  "Runs steps strictly in order. Each step is a zero-argument fn returning a
  channel, and the next step starts only once that channel delivers or
  per-step-ms has passed. A step that times out is recorded as
  (record! :row-timeout false {:index i :timeout-ms per-step-ms}), and the
  run goes on with the next step, while the overdue row is abandoned: its
  next parking-helper call throws a marked error, once, that go-row
  swallows, so a stale body can't act into the next row while its own
  finally still cleans up (see the section comment above check-row!).
  Returns a channel that delivers :done after the last step."
  [steps per-step-ms record!]
  (go (let [counter (atom 0)]
        (loop [i 0 steps (seq steps)]
          (when steps
            (let [g (swap! counter inc)
                  ch (binding [*row-ctx* [counter g (atom false)]] ((first steps)))
                  [_ port] (a/alts! [ch (timeout per-step-ms)])]
              (when-not (= port ch)
                ;; abandon the overdue row at once: its next helper call
                ;; throws instead of acting (see check-row!)
                (swap! counter inc)
                (record! :row-timeout false {:index i :timeout-ms per-step-ms}))
              (recur (inc i) (next steps))))))
      :done))

(defn close-window-bounded!
  "Closes window (through g/gtk-window-close, so close-request-fn's own
  quit path runs), then polls destroyed? until it's true or deadline-ms
  has passed. Calls (on-stuck) if the window is still alive at the
  deadline.

  A stuck self-test flag (suppress-close? left true by a throw
  between a row's set and its own reset) makes close-request-fn return
  the suppressed branch forever, so gtk-window-close alone never ends
  g_application_run -- the driver's own thread stays parked inside
  main/start! and never reaches its own System/exit. on-stuck is the way
  out: the drivers pass one that records a FAIL, makes sure SUMMARY (or
  the harness's own summary) has printed, then calls System/exit itself,
  from this go thread, since the GTK/main thread is the one stuck.

  destroyed? is a plain atom flipped from the window's own \"destroy\"
  signal (each driver's -main connects it). The poll reads destroyed?
  directly, off the GTK thread, and never uses await-until: once the
  window is gone an on-gui! thunk would never run. Call from inside a go
  block, never on the GTK thread."
  [window destroyed? deadline-ms on-stuck]
  (on-gtk (g/gtk-window-close window))
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (when (and (not @destroyed?)
                 (< (- (System/currentTimeMillis) t0) deadline-ms))
        (<! (timeout 50))
        (recur))))
  (when-not @destroyed?
    (on-stuck)))
