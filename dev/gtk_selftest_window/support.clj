(ns gtk-selftest-window.support
  "Shared plumbing for the window self-test's feature namespaces: the
  results atom and its `check` recorder, the settings-path/poll/timer
  helpers every row is built from, and the key constants more than one
  feature area needs.

  Each row is (fn [ui next] ...): it acts on ui, records exactly one
  check, then calls (next)."
  (:require [jolt.ffi :as ffi]
            [jonquil.eval :as eval]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(defonce results (atom []))

(defn check [row ok detail]
  (swap! results conj [row (boolean ok) detail])
  (binding [*out* *err*]
    (println "[gtk-selftest-window]" (name row) (if ok "PASS" "FAIL") (pr-str detail))))

(defn fresh-settings-path
  "TH.T5 isolation: a settings.edn under a fresh temp directory (one per
  run of this file, never the user's real $XDG_CONFIG_HOME/jonquil), so
  this self-test can never read or write the real settings file."
  []
  (str (System/getProperty "java.io.tmpdir")
       "/jonquil-selftest-window-settings-" (System/currentTimeMillis) "-" (System/nanoTime)
       "/settings.edn"))

(defn later [ms f]
  (g/g-timeout-add ms (cb/callable (fn [_data] (f) 0) [:pointer] :int) ffi/null))

(defn poll-until
  "Polls (pred?) every interval-ms via g_timeout_add until it's truthy or
  deadline-ms elapses, then calls (done final-pred-value). Never sleeps."
  [deadline-ms interval-ms pred? done]
  (let [t0 (System/currentTimeMillis)]
    (letfn [(step []
              (cond
                (pred?) (done true)
                (>= (- (System/currentTimeMillis) t0) deadline-ms) (done false)
                :else (later interval-ms step)))]
      (step))))

(defn console-text [ui] (g/buffer-text (:buf (:console ui))))

(defn idle? [ui] (not (eval/evaluating? (:ctx (:session ui)))))

(defn- mark-offset
  "mark's current character offset in buf -- console-view keeps its marks
  (:out-end) in its returned map but doesn't expose an offset-of-mark
  reader, mirroring dev/gtk_selftest.clj's own copy of this helper."
  [buf mark]
  (let [it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-buffer-get-iter-at-mark buf it mark)
      (g/gtk-text-iter-get-offset it)
      (finally (ffi/free it)))))

(defn out-end-offset
  "The console's out-end mark's current offset: the boundary between output
  history so far and the live prompt/input tail. Capture this BEFORE a step
  runs; `since` below reads the suffix from that offset once the step is
  done, which is exactly the new output the step produced (plus the live
  prompt/input tail, harmless for a substring check) -- never anything an
  earlier row or step already appended."
  [ui]
  (let [cv (:console ui)]
    (mark-offset (:buf cv) (:out-end cv))))

(defn since
  "Console text from mark onward -- see out-end-offset. Every row below
  scopes its own assertions to this instead of the whole accumulated
  console-text, because run-row! only clears the editor and the console's
  INPUT, never its output history, so a plain (console-text ui) search can
  match a prior row's (or a prior step's) leftover line and pass for the
  wrong reason."
  [ui mark]
  (subs (console-text ui) mark))

(defn place-cursor!
  "Places buf's cursor at offset -- an ordinary GtkTextBuffer call, not a
  synthesized keystroke, same category as the existing
  gtk-text-buffer-insert-interactive-at-cursor/select-range calls other
  rows already make directly on the buffer."
  [buf offset]
  (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))

(defn backspace-interactive!
  "Deletes the character immediately before offset in buf, interactively --
  what GTK's own default handling does for a plain Backspace once the key
  controller returns 0 for it. Measured: jonquil.edit/backspace-pair
  returns nil whenever the caret sits at the buffer's end (no character
  after it to pair against, e.g. (edit/backspace-pair {:text \"(map\"
  :caret 4 :sel-start 4 :sel-end 4}) => nil), so editor.clj's/console-
  view.clj's own KEY-BACKSPACE handling does nothing in that case either --
  same category as gtk-text-buffer-insert-interactive-at-cursor above,
  standing in for GTK's default since a bare handle-key! call has no real
  key event underneath it to trigger that default."
  [buf offset]
  (g/with-iters* buf (dec offset) offset
    (fn [a b] (g/gtk-text-buffer-delete-interactive buf a b 1))))

(defn select-range!
  [buf a b]
  (g/with-iters* buf a b (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2))))

(defn run-and-wait!
  "editor/set-text! code, run!*, then waits (up to deadline-ms) until BOTH
  the engine has gone idle AND (expect-fn text-since-start) is true, where
  text-since-start is the console text from a mark captured right before
  run!* -- exactly this run's own output, never a prior run's. (done ok
  mark) is called with the poll's final result and that same mark, so a
  caller can scope its own further assertions to exactly this run's output.

  I1: engine-idle alone is not enough. The engine flips to idle BEFORE
  on-complete's own on-gui!-scheduled append lands the result line (the
  worker resets running-state to idle-state, then only afterward calls
  on-complete, which enqueues the console append for a later GTK-thread
  drain) -- so a poll on idle? alone can resolve before the result is
  actually visible, exactly what reset-vs-accumulate's flaky first-error?
  false demonstrated."
  [ui code deadline-ms expect-fn done]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) code)
    ((:run!* ui))
    (poll-until deadline-ms 20
                (fn [] (and (idle? ui) (expect-fn (since ui mark))))
                (fn [ok] (done ok mark)))))

(defn wait-stable-height
  "Polls widget's natural-height (probe 4's numeric proxy for \"did the CSS
  font-size actually apply\") until two consecutive reads 20ms apart agree,
  up to deadline-ms, then calls (done height). Probe 4 measured
  gtk_widget_measure answering a value from BEFORE the most recent
  change (a CSS reload, or here also a big buffer deletion just before
  this row from row-clear-console) until GTK runs at least one more
  main-loop layout pass; polling for the reading to settle -- rather than
  trusting a single synchronous read -- waits that out without a fixed
  sleep."
  [widget deadline-ms done]
  (let [prev (atom ::none)]
    (poll-until deadline-ms 20
                (fn []
                  (let [cur (g/natural-height widget)
                        stable? (= cur @prev)]
                    (reset! prev cur)
                    stable?))
                (fn [_] (done @prev)))))

;; --- key constants used by more than one feature area ------------------

;; gtk-keys.md's shared table -- window.clj's own copy is private.
(def KEY-ESCAPE 0xff1b)
(def KEY-RETURN 0xff0d)
(def KEY-F6 0xffc3)
(def KEY-Q 0x071)
