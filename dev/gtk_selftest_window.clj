(ns gtk-selftest-window
  "P2.T7 self-test: opens the REAL Jonquil window via jonquil.main/start!
  and runs the 8 Run/Stop/REPL/stdin/open+save rows against its ui, on the
  same timer-chain pattern as dev/gtk_selftest.clj -- never a fixed sleep,
  every wait polls a condition against a deadline. Rows call ui's exposed
  functions (run!*, stop!*, open-path!, save-to!) directly, or drive the
  console's own submit path (handle-return! over an interactive insert),
  exactly as the buttons/keys and the user's typing do; no keystroke is
  ever synthesized.

  Each row resets the editor and console first and leaves the engine idle
  before returning control to the driver -- a row that starts a program
  stops it and waits for real, rather than trusting the next row's own
  reset to paper over it."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.docs :as docs]
            [jonquil.eval :as eval]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.popover :as popover]
            [jonquil.main :as main]
            [jonquil.session :as session]
            [jonquil.settings :as settings]))

(defonce results (atom []))

(defn- check [row ok detail]
  (swap! results conj [row (boolean ok) detail])
  (binding [*out* *err*]
    (println "[gtk-selftest-window]" (name row) (if ok "PASS" "FAIL") (pr-str detail))))

(defn- fresh-settings-path
  "TH.T5 isolation: a settings.edn under a fresh temp directory (one per
  run of this file, never the user's real $XDG_CONFIG_HOME/jonquil), so
  this self-test can never read or write the real settings file."
  []
  (str (System/getProperty "java.io.tmpdir")
       "/jonquil-selftest-window-settings-" (System/currentTimeMillis) "-" (System/nanoTime)
       "/settings.edn"))

(defn- later [ms f]
  (g/g-timeout-add ms (cb/callable (fn [_data] (f) 0) [:pointer] :int) ffi/null))

(defn- poll-until
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

(defn- console-text [ui] (g/buffer-text (:buf (:console ui))))
(defn- idle? [ui] (not (eval/evaluating? (:ctx (:session ui)))))

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

(defn- out-end-offset
  "The console's out-end mark's current offset: the boundary between output
  history so far and the live prompt/input tail. Capture this BEFORE a step
  runs; `since` below reads the suffix from that offset once the step is
  done, which is exactly the new output the step produced (plus the live
  prompt/input tail, harmless for a substring check) -- never anything an
  earlier row or step already appended."
  [ui]
  (let [cv (:console ui)]
    (mark-offset (:buf cv) (:out-end cv))))

(defn- since
  "Console text from mark onward -- see out-end-offset. Every row below
  scopes its own assertions to this instead of the whole accumulated
  console-text, because run-row! only clears the editor and the console's
  INPUT, never its output history, so a plain (console-text ui) search can
  match a prior row's (or a prior step's) leftover line and pass for the
  wrong reason."
  [ui mark]
  (subs (console-text ui) mark))

(defn- place-cursor!
  "Places buf's cursor at offset -- an ordinary GtkTextBuffer call, not a
  synthesized keystroke, same category as the existing
  gtk-text-buffer-insert-interactive-at-cursor/select-range calls other
  rows already make directly on the buffer."
  [buf offset]
  (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))

(defn- backspace-interactive!
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

(defn- select-range!
  [buf a b]
  (g/with-iters* buf a b (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2))))

(defn- run-and-wait!
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

;; --- rows --------------------------------------------------------------
;; Each row is (fn [ui next] ...): it acts on ui, records exactly one
;; check, then calls (next).

(defn- row-run-output [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(println :hi) (+ 1 2)")
    ((:run!* ui))
    (poll-until 5000 20
                (fn [] (let [t (since ui mark)]
                         (and (str/includes? t ":hi") (str/includes? t "=> 3"))))
                (fn [ok]
                  ;; Poll the status label too, rather than a single read, for
                  ;; the same reason: consistency with every other condition
                  ;; here being a poll-with-deadline, never a one-shot check.
                  (poll-until 2000 20
                              (fn [] (= "Ready" (g/label-text (:status-label ui))))
                              (fn [ready?]
                                (check :run-output (and ok ready?)
                                       {:console (since ui mark) :status (g/label-text (:status-label ui))})
                                (next)))))))

(defn- row-run-while-running [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(loop [] (recur))")
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  ((:run!* ui))
                  (poll-until 2000 20
                              (fn [] (str/includes? (since ui mark) "already in progress"))
                              (fn [warned?]
                                ((:stop!* ui))
                                (poll-until 3000 20
                                            (fn [] (str/includes? (since ui mark) "; interrupted"))
                                            (fn [stopped?]
                                              ;; M1: the refused second Run must not print a
                                              ;; second banner -- exactly one for the whole row.
                                              (let [banner-count (count (re-seq #"; --- Running Definitions ---"
                                                                                (since ui mark)))]
                                                (check :run-while-running
                                                       (and started? warned? stopped? (= 1 banner-count))
                                                       {:started started? :warned warned? :stopped stopped?
                                                        :banner-count banner-count
                                                        :console (since ui mark)})
                                                (next))))))))))

(defn- row-stdin-roundtrip [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(let [n (read-line)] (str \"Hello, \" n))")
    ((:run!* ui))
    (poll-until 3000 20
                (fn [] ((:waiting? (:console ui))))
                (fn [waiting?]
                  (let [cv (:console ui)]
                    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "World" -1 1)
                    (cview/handle-return! cv false)
                    (poll-until 3000 20
                                (fn [] (str/includes? (since ui mark) "=> \"Hello, World\""))
                                (fn [ok]
                                  (check :stdin-roundtrip (and waiting? ok)
                                         {:waiting waiting? :console (since ui mark)})
                                  (next))))))))

(defn- row-stop-while-reading [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(let [n (read-line)] (str \"Hello, \" n))")
    ((:run!* ui))
    (poll-until 3000 20
                (fn [] ((:waiting? (:console ui))))
                (fn [waiting-before]
                  ((:stop!* ui))
                  (poll-until 3000 20
                              (fn [] (str/includes? (since ui mark) "; interrupted"))
                              (fn [interrupted?]
                                ;; waiting? is a plain worker-thread atom reset (no on-gui!
                                ;; scheduling), so it should already be false once "; interrupted"
                                ;; -- itself only visible after an on-gui! drain -- has landed.
                                ;; Poll it too rather than reading once, so a real lag shows up as
                                ;; a slow pass instead of a silent-looking false failure.
                                (let [t0 (System/currentTimeMillis)]
                                  (poll-until 2000 20
                                              (fn [] (not ((:waiting? (:console ui)))))
                                              (fn [waiting-cleared?]
                                                (let [cv (:console ui)
                                                      waiting-after ((:waiting? cv))
                                                      ms (- (System/currentTimeMillis) t0)
                                                      input-before (cview/input-text cv)
                                                      inserted (not (zero? (g/gtk-text-buffer-insert-interactive-at-cursor
                                                                            (:buf cv) "z" -1 1)))
                                                      input-after (cview/input-text cv)]
                                                  (check :stop-while-reading
                                                         (and waiting-before interrupted? waiting-cleared?
                                                              (= "" input-before) inserted (= "z" input-after))
                                                         {:waiting-before waiting-before :interrupted interrupted?
                                                          :waiting-cleared waiting-cleared? :waiting-after waiting-after
                                                          :ms-to-clear ms :input-before input-before
                                                          :inserted inserted :input-after input-after})
                                                  (next)))))))))))

(defn- row-run-unbalanced [ui next]
  ;; The brief's literal fixture was "(ok)\n\n(defn f [x]" -- measured against
  ;; the real engine, (ok) throws an unresolved-symbol error at EVAL time,
  ;; before load-string's read/eval loop ever reaches the unclosed third
  ;; form, so the result is a plain :error, never "EOF while reading", and
  ;; the unclosed-bracket message never fires. (+ 1 1) evaluates cleanly, so
  ;; the loop advances to read the still-unclosed third form and genuinely
  ;; hits EOF -- same line-3 shape, the intended code path.
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(+ 1 1)\n\n(defn f [x]")
    ((:run!* ui))
    (poll-until 3000 20
                (fn [] (str/includes? (since ui mark) "The ( on line 3 is never closed."))
                (fn [ok]
                  (check :run-unbalanced ok {:console (since ui mark)})
                  (next)))))

(defn- row-reset-vs-accumulate
  "Every step's own assertion is scoped (via run-and-wait!'s mark) to the
  output THAT step produced. Without this, run-row!'s reset (editor + input
  only, never the console's output history) would let the first (greet)'s
  own leftover error line -- or even a PRIOR row's -- satisfy first-error?
  regardless of whether Reset actually removed anything; see the fix-round-1
  can-fail proof in the P2.T7 report. Each run-and-wait! also gates on its
  OWN expected text (I1), not just engine-idle, so mark1/mark2 are captured
  only once the previous step's result is actually visible."
  [ui next]
  (run-and-wait! ui "(defn greet [] :hi)" 3000
                 (fn [t] (str/includes? t "=> #'user/greet"))
                 (fn [defined1? _]
                   (run-and-wait! ui "(greet)" 3000
                                  (fn [t] (str/includes? t "Error ["))
                                  (fn [first-error? mark1]
                                    (session/set-mode! (:session ui) :accumulate)
                                    (run-and-wait! ui "(defn greet [] :hi)" 3000
                                                   (fn [t] (str/includes? t "=> #'user/greet"))
                                                   (fn [defined2? _]
                                                     (run-and-wait! ui "(greet)" 3000
                                                                    (fn [t] (str/includes? t "=> :hi"))
                                                                    (fn [second-ok? mark3]
                                                                      (check :reset-vs-accumulate
                                                                             (and defined1? first-error? defined2? second-ok?)
                                                                             {:defined1 defined1? :first-error first-error?
                                                                              :defined2 defined2? :second-ok second-ok?
                                                                              :first-console (since ui mark1)
                                                                              :second-console (since ui mark3)})
                                                                      (next))))))))))

(defn- row-repl-after-run [ui next]
  (run-and-wait! ui "(def x 41)" 3000
                 (fn [t] (str/includes? t "=> #'user/x"))
                 (fn [defined? _]
                   (let [cv (:console ui)
                         mark (out-end-offset ui)]
                     (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(inc x)" -1 1)
                     (cview/handle-return! cv false)
                     ;; I1 again: gate on the text itself, not idle? alone.
                     (poll-until 3000 20
                                 (fn [] (and (idle? ui) (str/includes? (since ui mark) "=> 42")))
                                 (fn [ok]
                                   (check :repl-after-run (and defined? ok)
                                          {:defined defined? :console (since ui mark)})
                                   (next)))))))

(defn- row-fast-repl-twice
  "I2: a fast REPL eval (nil, completing in microseconds) must not strand
  the console in :busy. With the old console-mirrored evaluating? atom, a
  fast on-complete could clear the mirror BEFORE do-repl-eval!'s own
  `set-evaluating! true` ran, leaving it stuck true and every later Return
  silently hitting :busy. Submits nil twice in a row and requires both
  results to actually appear."
  [ui next]
  (let [cv (:console ui)
        mark1 (out-end-offset ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "nil" -1 1)
    (let [got1 (cview/handle-return! cv false)]
      (poll-until 2000 20
                  (fn [] (and (idle? ui) (str/includes? (since ui mark1) "=> nil")))
                  (fn [ok1]
                    (let [mark2 (out-end-offset ui)]
                      (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "nil" -1 1)
                      (let [got2 (cview/handle-return! cv false)]
                        (poll-until 2000 20
                                    (fn [] (and (idle? ui) (str/includes? (since ui mark2) "=> nil")))
                                    (fn [ok2]
                                      (check :fast-repl-twice
                                             (and (= 1 got1) ok1 (= 1 got2) ok2)
                                             {:got1 got1 :ok1 ok1 :got2 got2 :ok2 ok2
                                              :console1 (since ui mark1) :console2 (since ui mark2)})
                                      (next))))))))))

(defn- row-busy-hint-once
  "M3: Return while a program runs and isn't waiting on stdin does nothing
  silently by default; the FIRST such Return during a run appends a hint,
  later ones during the SAME run stay silent."
  [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(loop [] (recur))")
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  (let [cv (:console ui)
                        got1 (cview/handle-return! cv false)
                        got2 (cview/handle-return! cv false)]
                    (poll-until 1000 20
                                (fn [] (str/includes? (since ui mark) "A program is running"))
                                (fn [hinted?]
                                  (let [hint-count (count (re-seq #"A program is running" (since ui mark)))]
                                    ((:stop!* ui))
                                    (poll-until 3000 20
                                                (fn [] (str/includes? (since ui mark) "; interrupted"))
                                                (fn [stopped?]
                                                  (check :busy-hint-once
                                                         (and started? (= 1 got1) (= 1 got2)
                                                              hinted? (= 1 hint-count) stopped?)
                                                         {:started started? :got1 got1 :got2 got2
                                                          :hinted hinted? :hint-count hint-count :stopped stopped?
                                                          :console (since ui mark)})
                                                  (next)))))))))))

(defn- row-console-history-down-empty
  "I3: eval/history-next returns \"\" for both an empty history and a
  not-currently-browsing index; the window.clj adapter must tell those
  apart from a real replacement, or Down erases whatever the user typed.
  Forces a fresh, empty history on THIS window's real session (not a stub)
  so the real adapter -- not console_view.clj's own nil handling, already
  covered by the multiline-caret row -- is what's under test."
  [ui next]
  (let [cv (:console ui)
        ctx (:ctx (:session ui))]
    (reset! (:history ctx) (eval/create-history))
    (cview/clear-input! cv)
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "typed" -1 1)
    (let [got (cview/handle-history! cv :next)
          input (cview/input-text cv)]
      (check :console-history-down-empty
             (and (not got) (= "typed" input))
             {:got got :input input})
      (next))))

;; gtk-keys.md's shared table -- window.clj's own copy is private.
(def ^:private KEY-ESCAPE 0xff1b)
;; gtk-keys.md's shared table -- find_bar.clj's own copy is private.
(def ^:private KEY-RETURN 0xff0d)
;; The uppercase keyval GDK reports for Ctrl+S under Caps Lock, with no
;; Shift bit set in state -- window.clj's own copy is private.
(def ^:private KEY-S-CAPS 0x53)
;; gtk-keys.md's shared table (0xffc3, GDK_KEY_F6) -- window.clj's own copy
;; is private.
(def ^:private KEY-F6 0xffc3)
;; GDK_KEY_a (gdkkeysyms.h) -- an ordinary letter, for popover-keys-stay-
;; with-editor's synthetic handle-key! call.
(def ^:private KEY-A 0x061)

(defn- row-escape-yields-when-idle
  "M5: the window-level capture controller used to consume Escape even
  when nothing was evaluating, so Escape could never reach an IME
  composition. Calls the exact fn the \"key-pressed\" signal calls (an
  ordinary function call with a synthetic keyval/state, not a synthesized
  OS keystroke) -- once while idle (must yield: 0) and once while a
  program runs (must Stop it: 1, and the program must actually stop)."
  [ui next]
  (let [idle-result ((:handle-window-key! ui) nil KEY-ESCAPE 0 0 nil)]
    (editor/set-text! (:editor ui) "(loop [] (recur))")
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  (let [mark (out-end-offset ui)
                        running-result ((:handle-window-key! ui) nil KEY-ESCAPE 0 0 nil)]
                    (poll-until 3000 20
                                (fn [] (str/includes? (since ui mark) "; interrupted"))
                                (fn [stopped?]
                                  (check :escape-yields-when-idle
                                         (and (= 0 idle-result) started? (= 1 running-result) stopped?)
                                         {:idle-result idle-result :started started?
                                          :running-result running-result :stopped stopped?})
                                  (next))))))))

(defn- row-command-chord-capslock
  "M4: shortcuts compared the raw keyval, so Caps Lock -- which makes GDK
  report the UPPERCASE keyval with no Shift bit set -- broke Ctrl+S (and
  Ctrl+R/O, not separately exercised here since they share the same
  gdk_keyval_to_lower normalisation). Establishes a path via the
  dialog-free :save-to! (no live dialog needed), dirties the editor, then
  drives the exact key-controller fn with the Caps-Lock-shaped keyval:
  it must Save (not Save As, since Shift isn't set), and the file's
  content must actually update."
  [ui next]
  (let [ed (:editor ui)
        tmp (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-capslock-" (System/currentTimeMillis) ".clj")]
    (editor/set-text! ed "(+ 1 1)")
    ((:save-to! ui) tmp)
    (editor/set-text! ed "(+ 2 2)")
    (let [handled ((:handle-window-key! ui) nil KEY-S-CAPS 0 g/CONTROL-MASK nil)
          saved-content (slurp tmp)]
      (check :command-chord-capslock
             (and (= 1 handled) (= "(+ 2 2)" saved-content))
             {:handled handled :saved-content saved-content})
      (.delete (java.io.File. ^String tmp))
      (next))))

(defn- row-repl-eof-no-editor-hint
  "M11: the EOF-unclosed-bracket hint reads the DEFINITIONS editor's text,
  so it must fire only for Runs, never for a REPL error. Sets the
  Definitions text to something WITH an unclosed bracket -- if the guard
  were missing, on-complete would misapply this Run-only hint and name
  THIS opener even though it's the REPL entry's own nested load-string
  that actually failed. (load-string \"(+ 1\") is balanced at the REPL's
  own top level (console/input-action lets it through to :eval), so the
  reader only hits EOF one level down, inside the nested load-string
  call -- verified against this Jolt to throw exactly \"EOF while
  reading\" at the top of the exception, same message text as a Run's own
  unbalanced Definitions."
  [ui next]
  (let [ed (:editor ui)
        cv (:console ui)
        mark (out-end-offset ui)]
    (editor/set-text! ed "(defn f [x]")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(load-string \"(+ 1\")" -1 1)
    (cview/handle-return! cv false)
    (poll-until 3000 20
                (fn [] (and (idle? ui) (str/includes? (since ui mark) "EOF while reading")))
                (fn [ok]
                  (check :repl-eof-no-editor-hint
                         (and ok (not (str/includes? (since ui mark) "is never closed.")))
                         {:console (since ui mark)})
                  (next)))))

(defn- row-open-refuses-dirty
  "I4: Open silently discarded unsaved edits. Dirties the editor without
  going through set-text! (which marks clean), then drives (:open-path!
  ui) -- the SAME guarded-open! gate do-open! uses -- twice: the first
  attempt must refuse and leave the text untouched, the second (still
  dirty) must proceed. The refusal text arrives through cview/append!,
  an on-gui!-scheduled drain (same I1 lesson: poll for it, don't read
  the console immediately after the synchronous call that queued it)."
  [ui next]
  (let [ed (:editor ui)
        mark (out-end-offset ui)
        tmp (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-dirty-" (System/currentTimeMillis) ".clj")]
    (spit tmp "(+ 9 9)")
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (let [dirty-before (editor/dirty? ed)]
      ((:open-path! ui) tmp)
      (poll-until 1000 20
                  (fn [] (str/includes? (since ui mark)
                                        "Unsaved changes. Save first, or Open again to discard them."))
                  (fn [refused?]
                    (let [text-after-first (editor/editor-text ed)]
                      ((:open-path! ui) tmp)
                      (let [text-after-second (editor/editor-text ed)]
                        (check :open-refuses-dirty
                               (and dirty-before refused?
                                    (= "(+ 1 1)z" text-after-first)
                                    (= "(+ 9 9)" text-after-second))
                               {:dirty-before dirty-before :refused refused?
                                :text-after-first text-after-first
                                :text-after-second text-after-second
                                :console (since ui mark)})
                        (.delete (java.io.File. ^String tmp))
                        (next))))))))

(defn- row-open-invalid-utf8
  "I6: a file with a stray non-UTF-8 byte (0xE9, the brief's own example)
  loads as U+FFFD without throwing; Open must append a :system warning
  rather than silently accept the loss. Poll for the warning -- same
  on-gui! drain lesson as row-open-refuses-dirty."
  [ui next]
  (let [ed (:editor ui)
        mark (out-end-offset ui)
        tmp (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-badutf8-" (System/currentTimeMillis) ".clj")]
    (with-open [os (java.io.FileOutputStream. ^String tmp)]
      (.write os (byte-array [(byte 0x28) (unchecked-byte 0xE9) (byte 0x29)])))
    ((:open-path! ui) tmp)
    (poll-until 1000 20
                (fn [] (str/includes? (since ui mark) "isn't valid UTF-8"))
                (fn [warned?]
                  (let [text (editor/editor-text ed)]
                    (check :open-invalid-utf8
                           (and (str/includes? text "�") warned?)
                           {:text text :warned warned? :console (since ui mark)})
                    (.delete (java.io.File. ^String tmp))
                    (next))))))

(defn- row-save-atomic
  "M12: save-to! writes a temp file beside the target and renames over it,
  never truncating the target in place. Saving into a missing directory
  must still show :error (and never create the bad path); saving to a
  real path round-trips. Poll for the :error line -- same on-gui! drain
  lesson; the file writes themselves are synchronous, no poll needed for
  those."
  [ui next]
  (let [ed (:editor ui)
        mark (out-end-offset ui)
        good-tmp (str (System/getProperty "java.io.tmpdir")
                      "/jonquil-selftest-atomic-" (System/currentTimeMillis) ".clj")
        bad-path (str (System/getProperty "java.io.tmpdir")
                      "/jonquil-selftest-missing-dir-" (System/currentTimeMillis) "/nested/file.clj")]
    (editor/set-text! ed "(+ 3 3)")
    ((:save-to! ui) bad-path)
    (poll-until 1000 20
                (fn [] (str/includes? (since ui mark) "; save:"))
                (fn [error-shown?]
                  (let [bad-path-exists? (.exists (java.io.File. ^String bad-path))]
                    (editor/set-text! ed "(+ 4 4)")
                    ((:save-to! ui) good-tmp)
                    (let [round-tripped? (= "(+ 4 4)" (slurp good-tmp))]
                      (check :save-atomic
                             (and error-shown? (not bad-path-exists?) round-tripped?)
                             {:error-shown error-shown? :bad-path-exists bad-path-exists?
                              :round-tripped round-tripped? :console (since ui mark)})
                      (.delete (java.io.File. ^String good-tmp))
                      (next)))))))

(defn- row-save-follows-symlink
  "M12 follow-up (a): the atomic rename used to target the SAVE PATH
  directly, so if that path is a symlink, java.nio.file.Files/move
  replaces the link itself with a regular file -- the link stops being a
  link, and whatever it used to point at is never touched (probed:
  reproduced exactly this against the old, unfixed code before writing
  this row -- see the fix-wave report's Follow-up section). save-to! now
  resolves the path first (.getCanonicalPath) and renames onto THAT, so
  saving through a symlink updates the real file and the link stays a
  link pointing at the same place."
  [ui next]
  (let [ed (:editor ui)
        dir (System/getProperty "java.io.tmpdir")
        real (str dir "/jonquil-selftest-symlink-real-" (System/currentTimeMillis) ".clj")
        link (str dir "/jonquil-selftest-symlink-link-" (System/currentTimeMillis) ".clj")]
    (spit real "(+ 0 0)")
    (java.nio.file.Files/createSymbolicLink
     (.toPath (java.io.File. ^String link))
     (.toPath (java.io.File. ^String real)))
    (editor/set-text! ed "(+ 9 9)")
    ((:save-to! ui) link)
    (let [still-symlink? (java.nio.file.Files/isSymbolicLink (.toPath (java.io.File. ^String link)))
          real-content (slurp real)
          via-link-content (slurp link)]
      (check :save-follows-symlink
             (and still-symlink? (= "(+ 9 9)" real-content) (= "(+ 9 9)" via-link-content))
             {:still-symlink still-symlink? :real-content real-content :via-link-content via-link-content})
      (.delete (java.io.File. ^String link))
      (.delete (java.io.File. ^String real))
      (next))))

(defn- row-save-executable-keeps-mode
  "M12 follow-up (b): a freshly spit temp file gets the process umask's
  default mode (probed: 644), so the old save-to! silently turned a 755
  file into 644 on every save. copy-permissions! now copies the target's
  real POSIX permissions onto the temp file before the rename."
  [ui next]
  (let [ed (:editor ui)
        path (str (System/getProperty "java.io.tmpdir")
                  "/jonquil-selftest-exec-" (System/currentTimeMillis) ".clj")]
    (spit path "(+ 0 0)")
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath (java.io.File. ^String path))
     (java.nio.file.attribute.PosixFilePermissions/fromString "rwxr-xr-x"))
    (editor/set-text! ed "(+ 1 1)")
    ((:save-to! ui) path)
    (let [mode (java.nio.file.attribute.PosixFilePermissions/toString
                (java.nio.file.Files/getPosixFilePermissions (.toPath (java.io.File. ^String path))))
          content (slurp path)]
      (check :save-executable-keeps-mode
             (and (= "rwxr-xr-x" mode) (= "(+ 1 1)" content))
             {:mode mode :content content})
      (.delete (java.io.File. ^String path))
      (next))))

(defn- row-save-failure-leaves-original-intact
  "M12 follow-up: a target directory made read-only AFTER the file
  already exists there -- spit can't create the temp file inside it, so
  the rename never runs at all. Must leave the original content intact,
  leave no stray .jonquil-tmp-* file in the directory, and show :error
  naming path (dir/file.clj, what the user typed) rather than the hidden
  temp filename or path's resolved-through-symlinks form (here the
  process tmpdir itself, /var/folders/... on macOS, which name-user-path
  must also rewrite since it resolves to /private/var/folders/...)."
  [ui next]
  (let [ed (:editor ui)
        dir (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-readonly-" (System/currentTimeMillis))
        path (str dir "/file.clj")
        mark (out-end-offset ui)]
    (.mkdir (java.io.File. ^String dir))
    (spit path "(+ 0 0)")
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath (java.io.File. ^String dir))
     (java.nio.file.attribute.PosixFilePermissions/fromString "r-xr-xr-x"))
    (editor/set-text! ed "(+ 1 1)")
    ((:save-to! ui) path)
    (poll-until 1000 20
                (fn [] (str/includes? (since ui mark) "; save:"))
                (fn [error-shown?]
                  (let [error-text (since ui mark)
                        content-intact? (= "(+ 0 0)" (slurp path))
                        stray-tmp? (boolean (some (fn [n] (str/includes? n ".jonquil-tmp-"))
                                                  (seq (.list (java.io.File. ^String dir)))))
                        names-user-path? (str/includes? error-text path)
                        names-tmp? (str/includes? error-text ".jonquil-tmp-")]
                    ;; restore write access before deleting, or .delete may
                    ;; itself fail against the still-read-only directory.
                    (java.nio.file.Files/setPosixFilePermissions
                     (.toPath (java.io.File. ^String dir))
                     (java.nio.file.attribute.PosixFilePermissions/fromString "rwxr-xr-x"))
                    (check :save-failure-leaves-original-intact
                           (and error-shown? content-intact? (not stray-tmp?)
                                names-user-path? (not names-tmp?))
                           {:error-shown error-shown? :content-intact content-intact?
                            :stray-tmp stray-tmp? :names-user-path names-user-path?
                            :names-tmp names-tmp? :error-text error-text})
                    (.delete (java.io.File. ^String path))
                    (.delete (java.io.File. ^String dir))
                    (next))))))

(defn- row-dialog-dismissed-silent
  "M2 follow-up: cancelling/dismissing a file dialog
  (GTK_DIALOG_ERROR_DISMISSED, code 2, IN GTK's OWN error domain) must not
  print an error line -- but code 2 is ALSO G_IO_ERROR_EXISTS in a
  different domain, so dialog-dismissed? must check the domain too, not
  just the code. file-dialog! itself can't be driven without a live
  dialog, so this drives the exact result-handling fns with result maps
  shaped like g/finish-file's.

  Every append! (real or, if the dismissed guard were ever broken, errant)
  goes through on-gui!'s async drain. The FIRST version of this row read
  (since ui mark) synchronously right after the simulate calls, with
  nothing real ever enqueued -- so it could never fail no matter what
  dialog-dismissed? actually did. Fixed by adding a wrong-domain code-2
  case and a genuine different-code error, both real appends the row can
  poll for; once the last one lands, the SAME drain call has already
  flushed everything queued before it (drain! processes the whole queue
  in one pass), including any errant dismissed-case append, so the scoped
  text at that point is a real assertion, not a foregone conclusion."
  [ui next]
  (let [mark (out-end-offset ui)
        real-domain (g/gtk-dialog-error-quark)
        wrong-domain (inc real-domain)] ; any value != real-domain
    ;; the real dismissed case: code 2, the real GTK-dialog-error domain.
    ((:simulate-open-result! ui) {:error "Dismissed by user" :code 2 :domain real-domain})
    ((:simulate-save-result! ui) {:error "Dismissed by user" :code 2 :domain real-domain})
    ;; code 2, wrong domain -- stands in for G_IO_ERROR_EXISTS -- must NOT
    ;; be swallowed by a code-only check.
    ((:simulate-open-result! ui) {:error "exists" :code 2 :domain wrong-domain})
    ;; a genuine, different-code error: something real to poll for.
    ((:simulate-open-result! ui) {:error "boom" :code 1 :domain real-domain})
    (poll-until 1000 20
                (fn [] (str/includes? (since ui mark) "; open: boom"))
                (fn [boom-shown?]
                  (let [text (since ui mark)]
                    (check :dialog-dismissed-silent
                           (and boom-shown?
                                (str/includes? text "; open: exists")
                                (not (str/includes? text "; open: Dismissed by user"))
                                (not (str/includes? text "; save: Dismissed by user")))
                           {:console text})
                    (next))))))

(defn- row-save-and-open [ui next]
  (let [tmp (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-" (System/currentTimeMillis) ".clj")]
    (editor/set-text! (:editor ui) "(+ 1 1)")
    ((:save-to! ui) tmp)
    (let [saved-content (slurp tmp)]
      (editor/set-text! (:editor ui) "(+ 2 2)")
      ((:open-path! ui) tmp)
      (let [text (editor/editor-text (:editor ui))
            title (g/window-title (:window ui))]
        (check :save-and-open
               (and (= "(+ 1 1)" saved-content) (= "(+ 1 1)" text)
                    (some? title) (not (str/includes? title "* ")))
               {:saved-content saved-content :text text :title title})
        (.delete (java.io.File. ^String tmp))
        (next)))))

(defn- row-run-selection-line
  "Caret on line 2, no selection: commands/run-selection-text (P3.T1) sends
  the whole line. Asserts BOTH the echoed \"> (inc 41)\" (console-view/
  echo-entry!) and the real result \"=> 42\", so a path that evaluates
  without echoing (or echoes without evaluating) still fails."
  [ui next]
  (let [ed (:editor ui)
        mark (out-end-offset ui)
        text "(def a 1)\n(inc 41)"]
    (editor/set-text! ed text)
    (place-cursor! (:buf ed) (count text))
    ((:run-selection!* ui))
    (poll-until 3000 20
                (fn [] (let [t (since ui mark)]
                         (and (str/includes? t "> (inc 41)") (str/includes? t "=> 42"))))
                (fn [ok]
                  (check :run-selection-line ok {:console (since ui mark)})
                  (next)))))

(defn- row-run-selection-sel
  "A selection inside a longer line sends only the selection, not the whole
  line."
  [ui next]
  (let [ed (:editor ui)
        mark (out-end-offset ui)
        text "foo (+ 1 2) bar"]
    (editor/set-text! ed text)
    (select-range! (:buf ed) 4 11) ; "(+ 1 2)"
    ((:run-selection!* ui))
    (poll-until 3000 20
                (fn [] (str/includes? (since ui mark) "=> 3"))
                (fn [ok]
                  (check :run-selection-sel ok {:console (since ui mark)})
                  (next)))))

(defn- row-run-selection-blank [ui next]
  ;; since is always non-empty (it includes the live "> " prompt and any
  ;; typed input), so "nothing appended" is asserted against a BASELINE
  ;; captured right before the action, not literal "".
  (let [ed (:editor ui)
        mark (out-end-offset ui)
        text "(def a 1)\n\n(inc 41)"]
    (editor/set-text! ed text)
    (place-cursor! (:buf ed) (count "(def a 1)\n")) ; the blank line 2
    (let [baseline (since ui mark)]
      ((:run-selection!* ui))
      ;; The brief's one allowed fixed wait: absence has no positive
      ;; condition to poll for, so this waits out the full window rather
      ;; than polling for something that should never appear.
      (later 500
             (fn []
               (let [t (since ui mark)]
                 (check :run-selection-blank (= baseline t) {:console t :baseline baseline})
                 (next)))))))

(defn- row-toggle-focus
  "Grabs editor focus first (a real function call, not a keystroke) so the
  row starts from a known state regardless of what an earlier row left
  focused, then toggles twice: console, then back to the editor."
  [ui next]
  (let [ed (:editor ui) cv (:console ui) win (:window ui)]
    (editor/focus! ed)
    ((:toggle-focus!* ui))
    (let [after-first (g/gtk-root-get-focus win)]
      ((:toggle-focus!* ui))
      (let [after-second (g/gtk-root-get-focus win)]
        (check :toggle-focus
               (and (= after-first (:view cv)) (= after-second (:view ed)))
               {:after-first after-first :after-second after-second
                :editor-view (:view ed) :console-view (:view cv)})
        (next)))))

(defn- row-clear-console
  "Produces real output, types \"abc\" at the (uncommitted) prompt, then
  clears. clear-output! only deletes [0, out-end) -- the whole buffer
  should read exactly the fresh prompt plus the still-typed input, and the
  input itself must be untouched."
  [ui next]
  (let [cv (:console ui)
        mark (out-end-offset ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 1)" -1 1)
    (cview/handle-return! cv false)
    (poll-until 3000 20
                (fn [] (and (idle? ui) (str/includes? (since ui mark) "=> 2")))
                (fn [produced?]
                  (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "abc" -1 1)
                  ((:clear-console!* ui))
                  (let [text (console-text ui)
                        input (cview/input-text cv)]
                    (check :clear-console
                           (and produced? (= "> abc" text) (= "abc" input))
                           {:produced produced? :text text :input input})
                    (next))))))

(defn- wait-stable-height
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

(defn- row-zoom-in-out
  "Zooms in, waits for the editor view's natural height to settle and grow
  past the baseline, then resets and waits for it to settle back to
  exactly the original -- each measurement via wait-stable-height, never
  a synchronous read on the same tick as the change."
  [ui next]
  (let [view (:view (:editor ui))]
    (wait-stable-height
     view 2000
     (fn [h0]
       ((:zoom!* ui) :in)
       (wait-stable-height
        view 2000
        (fn [h1]
          ((:zoom!* ui) :reset)
          (wait-stable-height
           view 2000
           (fn [h2]
             (check :zoom-in-out (and (> h1 h0) (= h2 h0)) {:h0 h0 :h1 h1 :h2 h2})
             (next)))))))))

;; --- P3.T4: find and replace bar ------------------------------------------
;; Each row is self-contained (its own editor/set-text!, open! and query)
;; rather than chaining off a prior row's leftover selection, since
;; run-row! resets the editor text before every row anyway.

(defn- row-find-highlights
  "\"foo bar foo baz foo\" has three occurrences of \"foo\", at character
  offsets [0 3], [8 11] and [16 19] (measured). Opening find and setting
  the query to \"foo\" must tag all three, show \"1 of 3\", and select the
  first."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "foo")
    (let [tag0 (g/has-tag-at? buf 0 (:tag fb))
          tag8 (g/has-tag-at? buf 8 (:tag fb))
          tag16 (g/has-tag-at? buf 16 (:tag fb))
          label (g/label-text (:count-label fb))
          sel (g/selection-offsets buf)]
      (check :find-highlights
             (and tag0 tag8 tag16 (= "1 of 3" label) (= [0 3] sel))
             {:tag0 tag0 :tag8 tag8 :tag16 tag16 :label label :sel sel})
      (next))))

(defn- row-find-next-wraps
  "From the initial [0 3] (find-highlights' own finding), three find-next!
  calls step to [8 11], [16 19], then wrap back to [0 3]."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "foo")
    (find-bar/find-next! fb)
    (let [sel1 (g/selection-offsets buf)]
      (find-bar/find-next! fb)
      (let [sel2 (g/selection-offsets buf)]
        (find-bar/find-next! fb)
        (let [sel3 (g/selection-offsets buf)]
          (check :find-next-wraps
                 (and (= [8 11] sel1) (= [16 19] sel2) (= [0 3] sel3))
                 {:sel1 sel1 :sel2 sel2 :sel3 sel3})
          (next))))))

(defn- row-find-prev
  "From the initial [0 3], find-prev! wraps back to the last match, [16 19]."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "foo")
    (let [initial (g/selection-offsets buf)]
      (find-bar/find-prev! fb)
      (let [sel (g/selection-offsets buf)]
        (check :find-prev (and (= [0 3] initial) (= [16 19] sel))
               {:initial initial :sel sel})
        (next)))))

(defn- row-find-no-match
  "A query with no occurrences shows \"No matches\" and leaves no
  search-match tag anywhere the earlier \"foo\" matches were."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "zzz")
    (let [label (g/label-text (:count-label fb))
          any-tag? (or (g/has-tag-at? buf 0 (:tag fb))
                       (g/has-tag-at? buf 8 (:tag fb))
                       (g/has-tag-at? buf 16 (:tag fb)))]
      (check :find-no-match (and (= "No matches" label) (not any-tag?))
             {:label label :any-tag any-tag?})
      (next))))

(defn- row-replace-one
  "replace-one! from the initial [0 3] match replaces only that occurrence
  (commands/replace-current), landing the selection on the shifted next
  match."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :replace)
    (g/gtk-editable-set-text (:query fb) "foo")
    (g/gtk-editable-set-text (:replacement fb) "X")
    (find-bar/replace-one! fb)
    (let [text (editor/editor-text ed)
          sel (g/selection-offsets buf)]
      (check :replace-one (and (= "X bar foo baz foo" text) (= [6 9] sel))
             {:text text :sel sel})
      (next))))

(defn- row-replace-all
  "replace-all! replaces every match in one edit (commands/replace-all,
  applied through one apply-op! call, so one undo restores every original
  occurrence)."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :replace)
    (g/gtk-editable-set-text (:query fb) "foo")
    (g/gtk-editable-set-text (:replacement fb) "X")
    (find-bar/replace-all! fb)
    (let [text (editor/editor-text ed)
          label (g/label-text (:count-label fb))]
      (g/gtk-text-buffer-undo buf)
      (let [restored (editor/editor-text ed)]
        (check :replace-all
               (and (= "X bar X baz X" text) (= "Replaced 3" label)
                    (= "foo bar foo baz foo" restored))
               {:text text :label label :restored restored})
        (next)))))

(defn- row-find-escape
  "Escape, driven through the bar's own handle-key! (never a synthesized
  keystroke), closes the bar, strips every search-match tag, and returns
  focus to the editor view."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed) win (:window ui)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "foo")
    (let [handled (find-bar/handle-key! fb KEY-ESCAPE 0)
          visible (g/gtk-widget-get-visible (:box fb))
          any-tag? (or (g/has-tag-at? buf 0 (:tag fb))
                       (g/has-tag-at? buf 8 (:tag fb))
                       (g/has-tag-at? buf 16 (:tag fb)))
          focus-is-editor? (= (g/gtk-root-get-focus win) (:view ed))]
      (check :find-escape
             (and (= 1 handled) (zero? visible) (not any-tag?) focus-is-editor?)
             {:handled handled :visible visible :any-tag any-tag?
              :focus-is-editor focus-is-editor?})
      (next))))

(defn- row-find-hangul
  "Hangul syllables are single characters on this Jolt/JVM string
  representation (not surrogate pairs), so \"안녕 hello 안녕\" matches
  \"안녕\" at character offsets [0 2] and [9 11] (measured)."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui)]
    (editor/set-text! ed "안녕 hello 안녕")
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "안녕")
    (let [matches @(:matches fb)]
      (check :find-hangul (= [[0 2] [9 11]] matches) {:matches matches})
      (next))))

;; --- P3.T4 fix round 1 ---

(defn- row-find-survives-repaint
  "Fix round 1, Important #1: editor.clj's repaint! (60ms debounce after
  the buffer's own \"changed\" signal) used to strip EVERY tag over the
  whole buffer via gtk_text_buffer_remove_all_tags, including
  search-match -- and since a repaint's own tag-only mutation never fires
  \"changed\" itself, nothing re-applied it afterward. Opens find, sets
  the query, types one character past the last match (so no match's own
  offsets move), then waits a FIXED delay well past the repaint debounce
  (there's no positive condition to poll for -- the tags either survive or
  they don't -- so this is the same one-shot-wait-then-check pattern
  row-run-selection-blank already uses) before checking the tags are still
  there."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "foo")
    (place-cursor! buf (count (editor/editor-text ed)))
    (g/gtk-text-buffer-insert-interactive-at-cursor buf "z" -1 1)
    (later 300
           (fn []
             (let [tags-survived? (and (g/has-tag-at? buf 0 (:tag fb))
                                       (g/has-tag-at? buf 8 (:tag fb))
                                       (g/has-tag-at? buf 16 (:tag fb)))]
               (check :find-survives-repaint tags-survived?
                      {:tags-survived tags-survived? :text (editor/editor-text ed)})
               (next))))))

(defn- row-find-preedit-yields
  "Fix round 1, Important #2: find_bar.clj's handle-key! is a capture-phase
  controller on the bar's own :box, so it used to consume Escape/Return
  before either entry's IM context ever saw it -- setting the atom
  directly proves the yield without needing a real IME, same pattern as
  editor.clj's row-edit-preedit-yields/row-editor-preedit-guard (the real
  preedit-changed wiring on each entry's GtkText delegate was verified
  separately with a throwaway probe, see ffi.clj's gtk-editable-get-
  delegate citation)."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "foo")
    (let [sel-before (g/selection-offsets buf)
          visible-before (g/gtk-widget-get-visible (:box fb))]
      (reset! (:preedit-active? fb) true)
      (let [return-result (find-bar/handle-key! fb KEY-RETURN 0)
            escape-result (find-bar/handle-key! fb KEY-ESCAPE 0)
            sel-after (g/selection-offsets buf)
            visible-after (g/gtk-widget-get-visible (:box fb))]
        (reset! (:preedit-active? fb) false)
        (check :find-preedit-yields
               (and (= 0 return-result) (= 0 escape-result)
                    (= sel-before sel-after) (= visible-before visible-after))
               {:return-result return-result :escape-result escape-result
                :sel-before sel-before :sel-after sel-after
                :visible-before visible-before :visible-after visible-after})
        (next)))))

(defn- row-find-prefill-selects
  "Fix round 1, Missing requirement: open! must select the whole prefilled
  query text (per the brief), not just prefill and focus it. Selects
  \"bar\" in the editor first, so open!'s own prefill (single-line
  selection) puts exactly that text in the query.

  Fix round 2: this used to read the query's selection synchronously, in
  the same tick as open! -- fails intermittently once the bar has already
  been through an earlier open! cycle (row-find-preedit-yields, right
  before this one, is exactly such a cycle): open!'s own recompute-matches!
  selects a match IN THE EDITOR, which revokes GTK's shared primary
  selection the query had just claimed, and open!'s fix (recompute-matches!
  now runs before the query's own select_region) makes that a non-issue,
  but a same-tick read can't tell \"fixed\" apart from \"just hasn't been
  revoked yet\" -- so this polls with a deadline instead, and genuinely
  fails (not hangs) if the selection never settles."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
    (editor/set-text! ed "foo bar foo baz foo")
    (select-range! buf 4 7) ; "bar"
    (find-bar/open! fb :find)
    (poll-until 500 20
                (fn [] (and (= "bar" (g/editable-text (:query fb)))
                            (= [0 3] (g/editable-selection-bounds (:query fb)))))
                (fn [ok?]
                  (check :find-prefill-selects ok?
                         {:query-text (g/editable-text (:query fb))
                          :sel (g/editable-selection-bounds (:query fb))})
                  (find-bar/close! fb)
                  (next)))))

;; --- P3.T5: Line/Col, ask before closing, bracket check before Run --------

(defn- row-line-col
  "place_cursor is one of probe 6's own two measured triggers for mark-set;
  the label update itself is synchronous (the buffer's \"mark-set\" signal
  fires inline from place-cursor!'s own call, not via an on-gui! drain), so
  this reads the label right back, no poll needed. \"ab\\ncd\" offset 4 is
  the 'd', 0-based [line 1, col 1] -> 1-based \"Ln 2, Col 2\"."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "ab\ncd")
    (place-cursor! (:buf ed) 4)
    (let [label (g/label-text (:line-col-label ui))]
      (check :line-col (= "Ln 2, Col 2" label) {:label label})
      (next))))

(defn- row-close-clean
  "Clean editor: close-request-fn's own else branch (would quit) fires
  immediately, no dialog. Driven with suppress-close? set (window.clj's
  close-request-fn docstring): the window is never really destroyed here,
  so later rows keep a live window -- row-command-chord-close (still the
  suite's sole deliberately-last row) is what actually exercises a real
  close ending the process. closing? becoming true is this row's stand-in
  for \"the window closes\"."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(+ 1 1)")
    (reset! (:suppress-close? ui) true)
    ((:request-close!* ui))
    (let [closing @(:closing? ui)
          dirty-after (editor/dirty? ed)]
      (reset! (:suppress-close? ui) false)
      (reset! (:closing? ui) false)
      (check :close-clean (and (not dirty-after) closing)
             {:dirty-after dirty-after :closing closing})
      (next))))

(defn- row-close-dirty-cancel
  "Dirty editor: request-close!* shows the real GtkAlertDialog (probe 3 --
  there is no programmatic way to pick a labelled button on it) and
  suppresses the close (returns 1) regardless of suppress-close?, since
  close-request-fn's dirty branch doesn't consult it at all. close-choice!
  is then driven with a synthetic {:index 2} (probe 3's own pattern) to
  verify the decision logic without a human: index 2 (Cancel) must do
  nothing, leaving the window open, the text unchanged, and still dirty.

  Fix round 1: request-close!* leaves a real, abandoned alert pending
  (close-choice! is driven synthetically here, never through the real
  dialog's own completion), so alert-pending? is reset by hand afterward
  -- otherwise the next dirty close-request row would find one still
  \"pending\" and never open its own alert at all (row-close-alert-
  pending-guard's own guard, applied to a row that isn't testing it)."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (let [dirty-before (editor/dirty? ed)
          text-before (editor/editor-text ed)]
      ((:request-close!* ui))
      ;; P3 fix wave (#7): suppress-close? so a Cancel that wrongly
      ;; confirmed the close shows up as closing? here instead of ending
      ;; the run.
      (reset! (:suppress-close? ui) true)
      ((:close-choice! ui) {:index 2})
      (let [dirty-after (editor/dirty? ed)
            text-after (editor/editor-text ed)
            closing @(:closing? ui)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (reset! (:alert-pending? ui) false)
        (check :close-dirty-cancel
               (and dirty-before (= text-before text-after) dirty-after (not closing))
               {:dirty-before dirty-before :dirty-after dirty-after
                :text-before text-before :text-after text-after :closing closing})
        (next)))))

(defn- row-close-dirty-discard
  "As close-dirty-cancel, but {:index 1} (Don't Save): close-choice! must
  confirm and finish-close! (gtk_window_close again, re-entering close-
  request-fn). suppress-close? intercepts the real close there (see
  window.clj's close-request-fn docstring) -- closing? becoming true,
  asserted through this test hook rather than an actual close, is the
  row's evidence that the confirm-and-close decision fired.

  Fix round 1: also resets alert-pending? -- see close-dirty-cancel's own
  note on why."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (let [dirty-before (editor/dirty? ed)]
      ((:request-close!* ui))
      (reset! (:suppress-close? ui) true)
      ((:close-choice! ui) {:index 1})
      (let [closing @(:closing? ui)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (reset! (:alert-pending? ui) false)
        (check :close-dirty-discard (and dirty-before closing)
               {:dirty-before dirty-before :closing closing})
        (next)))))

(defn- row-close-dirty-save
  "A remembered path (via the real :save-to! path, so close-choice!'s
  index-0 branch takes the has-a-path arm, not the file-dialog one) then
  dirtied again. {:index 0} must save the CURRENT (dirtied) text to that
  path and, since the save clears dirty?, confirm and close (suppressed,
  same as close-dirty-discard).

  Fix round 1: also resets alert-pending? -- see close-dirty-cancel's own
  note on why."
  [ui next]
  (let [ed (:editor ui)
        tmp (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-close-save-" (System/currentTimeMillis) ".clj")]
    (spit tmp "(+ 0 0)")
    (editor/set-text! ed "(+ 5 5)")
    ((:save-to! ui) tmp)
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (let [dirty-before (editor/dirty? ed)]
      ((:request-close!* ui))
      (reset! (:suppress-close? ui) true)
      ((:close-choice! ui) {:index 0})
      (let [closing @(:closing? ui)
            saved-content (slurp tmp)
            dirty-after (editor/dirty? ed)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (reset! (:alert-pending? ui) false)
        (check :close-dirty-save
               (and dirty-before closing (not dirty-after) (= "(+ 5 5)z" saved-content))
               {:dirty-before dirty-before :closing closing :dirty-after dirty-after
                :saved-content saved-content})
        (.delete (java.io.File. ^String tmp))
        (next)))))

(defn- row-pre-run-hidden-by-error
  "(undefined-fn) would throw an unresolved-symbol error at EVAL time if
  Run ever reached it -- the bracket check must run FIRST and skip
  evaluation entirely, so only the line-2 unclosed-opener message shows,
  never the eval error the first form would otherwise produce."
  [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(undefined-fn)\n(defn g [y]")
    ((:run!* ui))
    (poll-until 3000 20
                (fn [] (str/includes? (since ui mark) "The ( on line 2 is never closed."))
                (fn [ok]
                  (let [t (since ui mark)]
                    (check :pre-run-hidden-by-error
                           (and ok (not (str/includes? t "Unable to resolve symbol")))
                           {:console t})
                    (next))))))

(defn- row-pre-run-stray-closer
  "An extra closer with no opener at all (as opposed to an unclosed
  opener, row-run-unbalanced's own case)."
  [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(+ 1 2))")
    ((:run!* ui))
    (poll-until 3000 20
                (fn [] (str/includes? (since ui mark) "The ) on line 1 has no matching opener."))
                (fn [ok]
                  (check :pre-run-stray-closer ok {:console (since ui mark)})
                  (next)))))

;; --- P3.T5 fix round 1 ----------------------------------------------------

(defn- row-close-alert-pending-guard
  "Fix round 1, Important #1 (the guard half): a second close request
  while the first alert is still pending used to build a SECOND real
  GtkAlertDialog and silently hand it the shared 'current dialog' state,
  orphaning the first (row-close-alert-routing, below, covers the other
  half -- per-dialog completion routing). close-request-fn now refuses to
  open a second alert while one is pending. Two dirty :request-close!*
  calls in a row must build only ONE real dialog (alert-dialog-count);
  answering that one pending alert with a synthetic Don't Save must still
  confirm the close via :closing?."
  [ui next]
  (let [ed (:editor ui)
        count-before @(:alert-dialog-count ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    ((:request-close!* ui))
    ((:request-close!* ui))
    (let [dialogs-created (- @(:alert-dialog-count ui) count-before)]
      (reset! (:suppress-close? ui) true)
      ((:close-choice! ui) {:index 1})
      (let [closing @(:closing? ui)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (reset! (:alert-pending? ui) false)
        (check :close-alert-pending-guard
               (and (= 1 dialogs-created) closing)
               {:dialogs-created dialogs-created :closing closing})
        (next)))))

(defn- row-close-alert-routing
  "Fix round 1, Important #1 (the routing half): the completion callback
  must route by its OWN dialog identity (src -- GAsyncReadyCallback's own
  source_object argument for a real dialog), never a shared 'current
  dialog' atom. Drives complete-alert! directly with two distinct
  SYNTHETIC \"dialogs\" (plain keywords, never passed to any real GTK
  call) and a counting release stub swapped in via :alert-release! (so
  nothing GTK-side is ever touched, per the review's own crash-safety
  note), completing them in REVERSE registration order -- the shared-atom
  bug's exact failure shape: with :dialog-b registered after :dialog-a,
  completing :dialog-b first coincidentally looks right (it IS the
  'current' one), but completing :dialog-a next still uses the STALE
  'current' state (:dialog-b's, since nothing ever cleared it), so
  :dialog-a's own on-choice never fires and :dialog-b gets released a
  second time. A third, stray re-invocation of :dialog-a (already
  completed) must be a no-op, not another on-choice call or release."
  [ui next]
  (let [calls (atom [])
        released (atom [])
        prior-release @(:alert-release! ui)]
    (reset! (:alert-release! ui) (fn [d] (swap! released conj d)))
    ((:register-alert-choice-for-test! ui) :dialog-a (fn [r] (swap! calls conj [:a r])))
    ((:register-alert-choice-for-test! ui) :dialog-b (fn [r] (swap! calls conj [:b r])))
    ((:complete-alert-for-test! ui) :dialog-b {:index 0})
    ((:complete-alert-for-test! ui) :dialog-a {:index 1})
    ((:complete-alert-for-test! ui) :dialog-a {:index 1}) ; stray re-invocation
    (reset! (:alert-release! ui) prior-release)
    (check :close-alert-routing
           (and (= [[:b {:index 0}] [:a {:index 1}]] @calls)
                (= [:dialog-b :dialog-a] @released))
           {:calls @calls :released @released})
    (next)))

(defn- row-run-while-running-keeps-warning
  "Fix round 1, Important #2: a Run while one is already running used to
  run commands/pre-run-problem on the SECOND buffer regardless, so an
  unbalanced buffer set status to Ready (the FIRST program is still
  running) and appended a bracket :error into the middle of its output
  instead of P2's own 'already in progress' warning. Starts (loop []
  (recur)), waits for it to really be evaluating, THEN sets an unbalanced
  buffer and calls run!* again: the console must show the P2 warning and
  no bracket sentence, and the status must still read the running status,
  not Ready."
  [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(loop [] (recur))")
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  (editor/set-text! (:editor ui) "(+ 1 2))")
                  ((:run!* ui))
                  (poll-until 1000 20
                              (fn [] (str/includes? (since ui mark) "already in progress"))
                              (fn [warned?]
                                (let [t (since ui mark)
                                      status (g/label-text (:status-label ui))]
                                  ((:stop!* ui))
                                  (poll-until 3000 20
                                              (fn [] (str/includes? (since ui mark) "; interrupted"))
                                              (fn [stopped?]
                                                (check :run-while-running-keeps-warning
                                                       (and started? warned? stopped?
                                                            (= "Running…" status)
                                                            (not (str/includes? t "no matching opener")))
                                                       {:started started? :warned warned? :stopped stopped?
                                                        :status status :console t})
                                                (next))))))))))

;; --- P3.T4 fix round 3: Escape via the WINDOW's own dispatch -------------
;; (:handle-window-key! ui), never find-bar/handle-key! directly -- these
;; rows exercise find-bar-focused?'s own routing, not the bar's internal
;; key handling (already covered by find-escape/find-preedit-yields).

(defn- row-window-escape-closes-bar-while-running
  "Fix round 3, Important: window.clj's find-bar-focused? used to compare
  gtk_root_get_focus against the outer (:query fb)/(:replacement fb)
  widgets directly, which is always false (fix round 2's own diagnosis:
  the real focus widget is an entry's internal GtkText delegate, never the
  entry itself) -- so with a program running and the find bar's query
  focused, the window's own Escape clause never matched and fell through
  to Stop, stopping the program instead of closing the bar. Starts a real
  (loop [] (recur)), waits for it to actually be running, opens the find
  bar (which focuses the query), then drives the WINDOW's own key
  dispatch with Escape: the bar must close, focus must return to the
  editor, and the program must STILL be running (stopped in this row's
  own cleanup, not by the Escape press)."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) win (:window ui)]
    (editor/set-text! ed "(loop [] (recur))")
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  (find-bar/open! fb :find)
                  (let [handled ((:handle-window-key! ui) nil KEY-ESCAPE 0 0 nil)
                        bar-visible (g/gtk-widget-get-visible (:box fb))
                        focus-is-editor? (= (g/gtk-root-get-focus win) (:view ed))
                        still-running? (eval/evaluating? (:ctx (:session ui)))]
                    (check :window-escape-closes-bar-while-running
                           (and started? (= 1 handled) (zero? bar-visible)
                                focus-is-editor? still-running?)
                           {:started started? :handled handled :bar-visible bar-visible
                            :focus-is-editor focus-is-editor? :still-running still-running?})
                    ((:stop!* ui))
                    (poll-until 3000 20
                                (fn [] (not (eval/evaluating? (:ctx (:session ui)))))
                                (fn [_] (next))))))))

(defn- row-window-escape-yields-preedit-while-running
  "Fix round 3: with the find bar's own preedit flag set (fix round 1),
  window.clj's Escape clause must yield (0) too, not just find-bar/
  handle-key! in isolation -- the clause now returns handle-key!'s OWN
  result directly (fix round 1's own change), so this exercises that
  through the WINDOW dispatch specifically, with a program running.
  Starts a real running program, opens the bar, sets preedit-active?
  true, sends window Escape: must return 0, the bar must stay open, and
  the program must still be running."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui)]
    (editor/set-text! ed "(loop [] (recur))")
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  (find-bar/open! fb :find)
                  (reset! (:preedit-active? fb) true)
                  (let [handled ((:handle-window-key! ui) nil KEY-ESCAPE 0 0 nil)
                        bar-visible (g/gtk-widget-get-visible (:box fb))
                        still-running? (eval/evaluating? (:ctx (:session ui)))]
                    (reset! (:preedit-active? fb) false)
                    (check :window-escape-yields-preedit-while-running
                           (and started? (= 0 handled) (= 1 bar-visible) still-running?)
                           {:started started? :handled handled :bar-visible bar-visible
                            :still-running still-running?})
                    (find-bar/close! fb)
                    ((:stop!* ui))
                    (poll-until 3000 20
                                (fn [] (not (eval/evaluating? (:ctx (:session ui)))))
                                (fn [_] (next))))))))

(defn- row-window-escape-stops-when-editor-focused
  "Fix round 3: Escape must keep its P2 meaning (Stop) when focus is
  OUTSIDE the find bar -- find-bar-focused? must read false here.
  Explicitly grabs editor focus (not just relying on whatever an earlier
  row left focused), starts a real running program, sends window Escape:
  the program must stop."
  [ui next]
  (let [ed (:editor ui)]
    (editor/focus! ed)
    (editor/set-text! ed "(loop [] (recur))")
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  (let [mark (out-end-offset ui)
                        handled ((:handle-window-key! ui) nil KEY-ESCAPE 0 0 nil)]
                    (poll-until 3000 20
                                (fn [] (str/includes? (since ui mark) "; interrupted"))
                                (fn [stopped?]
                                  (check :window-escape-stops-when-editor-focused
                                         (and started? (= 1 handled) stopped?)
                                         {:started started? :handled handled :stopped stopped?})
                                  (next))))))))

;; --- P3 fix wave -----------------------------------------------------------

;; gtk-keys.md's shared table (gdkkeysyms.h) -- the product's copies are private.
(def ^:private KEY-F3 0xffc0)
(def ^:private KEY-G 0x067)

(defn- row-find-typing-keeps-caret
  "C1: typing in the editor while the bar is open must not move the
  selection. The buffer's \"changed\" signal used to run the full
  recompute, which selected (and scrolled to) the next match after the
  caret, so the NEXT keystroke's commit deleted that selection and
  replaced a match. Types \"z\" inside \"bar\" (away from every match)
  with insert_interactive_at_cursor, then asserts an empty selection with
  the caret right after the z. The second keystroke is reproduced the way
  GtkTextView's own commit_text does it (delete_selection, then insert at
  the cursor), and every \"foo\" must survive it."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "foo")
    (place-cursor! buf 5)
    (g/gtk-text-buffer-insert-interactive-at-cursor buf "z" -1 1)
    (let [sel1 (g/selection-offsets buf)
          caret1 (g/cursor-offset buf)]
      (g/gtk-text-buffer-delete-selection buf 1 1)
      (g/gtk-text-buffer-insert-interactive-at-cursor buf "q" -1 1)
      (let [text (editor/editor-text ed)
            matches @(:matches fb)]
        (find-bar/close! fb)
        (check :find-typing-keeps-caret
               (and (= [6 6] sel1) (= 6 caret1)
                    (= "foo bzqar foo baz foo" text) (= 3 (count matches)))
               {:sel1 sel1 :caret1 caret1 :text text :matches matches})
        (next)))))

(defn- row-find-return-in-replacement
  "I1: Return with focus in the replacement entry replaces the current
  match. gtk_root_get_focus returns the entry's internal GtkText delegate,
  so comparing it with the entry itself was always false and Return ran
  find-next! instead."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :replace)
    (g/gtk-editable-set-text (:query fb) "foo")
    (g/gtk-editable-set-text (:replacement fb) "X")
    (g/gtk-widget-grab-focus (:replacement fb))
    (let [handled (find-bar/handle-key! fb KEY-RETURN 0)
          text (editor/editor-text ed)
          sel (g/selection-offsets buf)]
      (find-bar/close! fb)
      (check :find-return-in-replacement
             (and (= 1 handled) (= "X bar foo baz foo" text) (= [6 9] sel))
             {:handled handled :text text :sel sel})
      (next))))

(defn- row-window-find-keys-editor-focused
  "I4: with the bar open and focus back in the editor, F3, Cmd+G and
  Shift+F3 still move between matches, through the WINDOW's own key
  dispatch (:handle-window-key!), not the bar's controller."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed) win (:window ui)]
    (editor/set-text! ed "foo bar foo baz foo")
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "foo")
    (editor/focus! ed)
    (let [focus-is-editor? (= (g/gtk-root-get-focus win) (:view ed))
          initial (g/selection-offsets buf)
          h1 ((:handle-window-key! ui) nil KEY-F3 0 0 nil)
          sel1 (g/selection-offsets buf)
          h2 ((:handle-window-key! ui) nil KEY-G 0 g/CONTROL-MASK nil)
          sel2 (g/selection-offsets buf)
          h3 ((:handle-window-key! ui) nil KEY-F3 0 g/SHIFT-MASK nil)
          sel3 (g/selection-offsets buf)]
      (find-bar/close! fb)
      (check :window-find-keys-editor-focused
             (and focus-is-editor? (= [0 3] initial)
                  (= 1 h1) (= [8 11] sel1)
                  (= 1 h2) (= [16 19] sel2)
                  (= 1 h3) (= [8 11] sel3))
             {:focus-is-editor focus-is-editor? :initial initial
              :h1 h1 :sel1 sel1 :h2 h2 :sel2 sel2 :h3 h3 :sel3 sel3})
      (next))))

(defn- row-line-col-typing
  "I2: an insert moves the insert mark by gravity without emitting
  mark-set, so Ln/Col must follow the buffer's notify::cursor-position
  instead. \"ab\\ncd\" with the caret at 4 reads \"Ln 2, Col 2\"; typing
  \"x\" there must advance it to \"Ln 2, Col 3\"."
  [ui next]
  (let [ed (:editor ui) buf (:buf ed)]
    (editor/set-text! ed "ab\ncd")
    (place-cursor! buf 4)
    (let [before (g/label-text (:line-col-label ui))]
      (g/gtk-text-buffer-insert-interactive-at-cursor buf "x" -1 1)
      (let [after (g/label-text (:line-col-label ui))]
        (check :line-col-typing (and (= "Ln 2, Col 2" before) (= "Ln 2, Col 3" after))
               {:before before :after after})
        (next)))))

(defn- row-close-dismissed
  "#7: a dismissed alert ({:error ..}, close-choice!'s error branch) leaves
  the window open, the buffer dirty and the text unchanged."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (reset! (:suppress-close? ui) true)
    (let [text-before (editor/editor-text ed)]
      ((:close-choice! ui) {:error "dismissed"})
      (let [closing @(:closing? ui)
            dirty-after (editor/dirty? ed)
            text-after (editor/editor-text ed)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (check :close-dismissed
               (and (not closing) dirty-after (= text-before text-after))
               {:closing closing :dirty-after dirty-after
                :text-before text-before :text-after text-after})
        (next)))))

(defn- row-close-save-failed
  "#7: Save chosen ({:index 0}) with a remembered path whose save fails
  (its directory was removed after the first save) must not close: the
  window stays open, the buffer stays dirty, the text is unchanged, and
  the failure reaches the console."
  [ui next]
  (let [ed (:editor ui)
        dir (java.io.File. (str (System/getProperty "java.io.tmpdir")
                                "/jonquil-selftest-close-fail-" (System/currentTimeMillis)))
        path (str (.getPath dir) "/f.clj")
        mark (out-end-offset ui)]
    (.mkdirs dir)
    (editor/set-text! ed "(+ 5 5)")
    ((:save-to! ui) path)
    (.delete (java.io.File. ^String path))
    (.delete dir)
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (reset! (:suppress-close? ui) true)
    (let [text-before (editor/editor-text ed)]
      ((:close-choice! ui) {:index 0})
      (let [closing @(:closing? ui)
            dirty-after (editor/dirty? ed)
            text-after (editor/editor-text ed)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (poll-until 1000 20
                    (fn [] (str/includes? (since ui mark) "; save:"))
                    (fn [reported?]
                      (check :close-save-failed
                             (and (not closing) dirty-after (= text-before text-after) reported?
                                  (not (.exists dir)))
                             {:closing closing :dirty-after dirty-after :text-before text-before
                              :text-after text-after :reported reported? :console (since ui mark)})
                      (next)))))))

(defn- row-close-alert-build-throws
  "#10: if building the alert throws after alert-pending? is set, the flag
  must reset (or every later close silently does nothing) and the error
  must reach the console. Forces the throw by redefining the alert's
  constructor for this one close request."
  [ui next]
  (let [ed (:editor ui)
        mark (out-end-offset ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (reset! (:suppress-close? ui) true)
    (with-redefs [g/g-object-new-with-properties
                  (fn [& _] (throw (ex-info "forced alert build failure" {})))]
      ((:request-close!* ui)))
    (let [pending @(:alert-pending? ui)
          closing @(:closing? ui)
          dirty-after (editor/dirty? ed)]
      (reset! (:suppress-close? ui) false)
      (reset! (:closing? ui) false)
      (reset! (:alert-pending? ui) false)
      (poll-until 1000 20
                  (fn [] (str/includes? (since ui mark) "forced alert build failure"))
                  (fn [reported?]
                    (check :close-alert-build-throws
                           (and (not pending) (not closing) dirty-after reported?)
                           {:pending pending :closing closing :dirty-after dirty-after
                            :reported reported? :console (since ui mark)})
                    (next))))))

(defn- row-quit-action-prompts
  "C3: the application's \"quit\" action, activated the way GTK's macOS
  delegate activates it on a Quit AppleEvent (Dock -> Quit, logout),
  must go through the close prompt: dirty opens the alert and does not
  close; clean closes (suppress-close? stands in for the real quit, as in
  close-clean)."
  [ui next]
  (let [ed (:editor ui)
        count-before @(:alert-dialog-count ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (reset! (:suppress-close? ui) true)
    ((:activate-quit-action!* ui))
    (let [dialogs (- @(:alert-dialog-count ui) count-before)
          pending @(:alert-pending? ui)
          closing-dirty @(:closing? ui)
          text-after (editor/editor-text ed)]
      (reset! (:alert-pending? ui) false)
      (editor/set-text! ed "(+ 1 1)")
      ((:activate-quit-action!* ui))
      (let [closing-clean @(:closing? ui)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (check :quit-action-prompts
               (and (= 1 dialogs) pending (not closing-dirty) (= "(+ 1 1)z" text-after)
                    closing-clean)
               {:dialogs dialogs :pending pending :closing-dirty closing-dirty
                :text-after text-after :closing-clean closing-clean})
        (next)))))

;; --- TH.T4: apply-theme! (retint tags, reload the CSS, flip prefer-dark) --
;; Every row here ends by restoring :light and zoom :reset, per the shared
;; rule (02-gtk.md) that later rows see the colours/size they expect.

(defn- row-theme-retints-tags
  "Reads each tag's colour back via rgba-property-hex/get-bool-property
  (probe rgba.clj), before and after switching theme, against the exact
  hex values measured from jonquil.theme/themes: navy's :keyword
  (#c792ea), [:rainbow 0] (#f78c6c), :unmatched (:red, #f07178), the
  console's :result (#c3e88d) and the find bar's :find-highlight
  (#4d4520); then light's same five (#cf222e, #953800, #cf222e, #0a3069,
  #fff2a8). Fix wave item 1: also the find bar tag's own foreground-rgba,
  which must equal the theme's :text (navy #c8cad8, light #24292f) so
  matched text stays readable on top of the highlight."
  [ui next]
  (let [ed (:editor ui) cv (:console ui) fb (:find-bar ui)
        kw-tag (get (:tags ed) :keyword)
        rb0-tag (get (:tags ed) [:rainbow 0])
        um-tag (get (:tags ed) :unmatched)
        result-tag (get (:tags cv) :result)
        fb-tag (:tag fb)]
    ((:set-theme!* ui) :navy)
    (let [kw-navy (g/rgba-property-hex kw-tag "foreground-rgba")
          rb0-navy (g/rgba-property-hex rb0-tag "foreground-rgba")
          um-navy (g/rgba-property-hex um-tag "foreground-rgba")
          result-navy (g/rgba-property-hex result-tag "foreground-rgba")
          fb-navy (g/rgba-property-hex fb-tag "background-rgba")
          fbfg-navy (g/rgba-property-hex fb-tag "foreground-rgba")]
      ((:set-theme!* ui) :light)
      (let [kw-light (g/rgba-property-hex kw-tag "foreground-rgba")
            rb0-light (g/rgba-property-hex rb0-tag "foreground-rgba")
            um-light (g/rgba-property-hex um-tag "foreground-rgba")
            result-light (g/rgba-property-hex result-tag "foreground-rgba")
            fb-light (g/rgba-property-hex fb-tag "background-rgba")
            fbfg-light (g/rgba-property-hex fb-tag "foreground-rgba")]
        (check :theme-retints-tags
               (and (= "#c792ea" kw-navy) (= "#f78c6c" rb0-navy) (= "#f07178" um-navy)
                    (= "#c3e88d" result-navy) (= "#4d4520" fb-navy) (= "#c8cad8" fbfg-navy)
                    (= "#cf222e" kw-light) (= "#953800" rb0-light) (= "#cf222e" um-light)
                    (= "#0a3069" result-light) (= "#fff2a8" fb-light) (= "#24292f" fbfg-light))
               {:kw-navy kw-navy :rb0-navy rb0-navy :um-navy um-navy
                :result-navy result-navy :fb-navy fb-navy :fbfg-navy fbfg-navy
                :kw-light kw-light :rb0-light rb0-light :um-light um-light
                :result-light result-light :fb-light fb-light :fbfg-light fbfg-light})
        ((:zoom!* ui) :reset)
        (next)))))

(defn- row-theme-css
  "dark's code-css (jonquil.theme/code-css) at the default font size, read
  back from the shared GtkCssProvider's OWN serialisation (fix wave item
  7: :code-css* now calls gtk_css_provider_to_string instead of reading
  the code-css atom mirror). Measured (2026-09-26): GTK normalises hex
  colours to rgb(r,g,b) and re-orders/re-indents the declarations, but
  keeps \"font-size: <n>px;\" verbatim -- background-color rgb(0,0,0),
  color rgb(224,224,224), font-size 13px."
  [ui next]
  ((:zoom!* ui) :reset)
  ((:set-theme!* ui) :dark)
  (let [css ((:code-css* ui))]
    (check :theme-css
           (and (str/includes? css "background-color: rgb(0,0,0);")
                (str/includes? css "color: rgb(224,224,224);")
                (str/includes? css "font-size: 13px;"))
           {:css css})
    ((:set-theme!* ui) :light)
    ((:zoom!* ui) :reset)
    (next)))

(defn- row-theme-prefer-dark
  "GtkSettings' colour scheme, read back via color-scheme-dark?: true for
  dark's :dark? true, false for solarized's :dark? false. Fix wave item 2:
  gtk-application-prefer-dark-theme is deprecated (GTK 4.20+, a
  g_warning from 4.23.1); apply-window-theme! now sets the replacement,
  gtk-interface-color-scheme, so this row reads that property instead
  (measured live to darken the chrome exactly as the old boolean did --
  see ffi.clj's own citation)."
  [ui next]
  ((:set-theme!* ui) :dark)
  (let [dark? (g/color-scheme-dark? (g/gtk-settings-get-default))]
    ((:set-theme!* ui) :solarized)
    (let [solarized-dark? (g/color-scheme-dark? (g/gtk-settings-get-default))]
      (check :theme-prefer-dark (and dark? (not solarized-dark?))
             {:dark dark? :solarized-dark solarized-dark?})
      ((:set-theme!* ui) :light)
      ((:zoom!* ui) :reset)
      (next))))

(defn- row-theme-keeps-text-and-undo
  "A theme switch never touches buffer text, the selection or undo
  history: inserts \"x\" interactively (an undoable step) after set-text!'s
  own irreversible \"(def a 1)\", selects [0 4], switches theme, then
  checks the text, the selection, get-can-undo and one undo's result are
  all exactly what they'd be without the switch."
  [ui next]
  (let [ed (:editor ui) buf (:buf ed)]
    (editor/set-text! ed "(def a 1)")
    (place-cursor! buf (count "(def a 1)"))
    (g/gtk-text-buffer-insert-interactive-at-cursor buf "x" -1 1)
    (select-range! buf 0 4)
    ((:set-theme!* ui) :navy)
    (let [text (editor/editor-text ed)
          sel (g/selection-offsets buf)
          can-undo (not (zero? (g/gtk-text-buffer-get-can-undo buf)))]
      (g/gtk-text-buffer-undo buf)
      (let [undone (editor/editor-text ed)]
        (check :theme-keeps-text-and-undo
               (and (= "(def a 1)x" text) (= [0 4] sel) can-undo (= "(def a 1)" undone))
               {:text text :sel sel :can-undo can-undo :undone undone})
        ((:set-theme!* ui) :light)
        ((:zoom!* ui) :reset)
        (next)))))

(defn- row-theme-keeps-find-highlight
  "A theme switch never touches the find bar's :matches: with the bar open
  on \"foo bar foo\" (matches at [0 3] and [8 11]), switching theme must
  leave both tagged, the count label unchanged, the tag's background
  retinted (dark's #5c4b00) and, fix wave item 1, its foreground equal to
  the theme's :text (dark #e0e0e0) so matched text stays readable. Also
  checks the search-match tag's tag-table priority against a syntax tag's
  (:special-form, present in the buffer as \"foo\" isn't a token but the
  tag object exists once the editor's paint plan has run) -- GTK's
  priority is add order and a higher priority wins a conflicting property
  on an overlapping range, so search-match (added after every editor tag
  in make-find-bar) must read a strictly higher priority."
  [ui next]
  (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)
        special-form-tag (get (:tags ed) :special-form)]
    (editor/set-text! ed "foo bar foo")
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "foo")
    (let [label-before (g/label-text (:count-label fb))]
      ((:set-theme!* ui) :dark)
      (let [tag0 (g/has-tag-at? buf 0 (:tag fb))
            tag8 (g/has-tag-at? buf 8 (:tag fb))
            label-after (g/label-text (:count-label fb))
            bg (g/rgba-property-hex (:tag fb) "background-rgba")
            fg (g/rgba-property-hex (:tag fb) "foreground-rgba")
            fb-priority (g/gtk-text-tag-get-priority (:tag fb))
            syntax-priority (g/gtk-text-tag-get-priority special-form-tag)]
        (find-bar/close! fb)
        (check :theme-keeps-find-highlight
               (and tag0 tag8 (= label-before label-after) (= "#5c4b00" bg) (= "#e0e0e0" fg)
                    (> fb-priority syntax-priority))
               {:tag0 tag0 :tag8 tag8 :label-before label-before :label-after label-after
                :bg bg :fg fg :fb-priority fb-priority :syntax-priority syntax-priority})
        ((:set-theme!* ui) :light)
        ((:zoom!* ui) :reset)
        (next)))))

(defn- row-theme-and-zoom-share-css
  "Zoom and theme reload the SAME shared provider and must not overwrite
  each other's part of the CSS: zoom :in (13->14px), switch to navy (css1:
  14px + navy's #1a1b2e background), zoom :in again (14->16px; css2: 16px
  and STILL navy's #1a1b2e, not the default theme's). Fix wave item 7:
  read from the provider's own serialisation (:code-css*), where navy's
  #1a1b2e prints as rgb(26,27,46) (measured 2026-09-26)."
  [ui next]
  ((:zoom!* ui) :reset)
  ((:zoom!* ui) :in)
  ((:set-theme!* ui) :navy)
  (let [css1 ((:code-css* ui))]
    ((:zoom!* ui) :in)
    (let [css2 ((:code-css* ui))]
      (check :theme-and-zoom-share-css
             (and (str/includes? css1 "font-size: 14px;") (str/includes? css1 "rgb(26,27,46)")
                  (str/includes? css2 "font-size: 16px;") (str/includes? css2 "rgb(26,27,46)"))
             {:css1 css1 :css2 css2})
      ((:set-theme!* ui) :light)
      ((:zoom!* ui) :reset)
      (next))))

(defn- row-theme-applies-to-new-text
  "Tags that repaint! applies later are the same tag objects apply-theme!
  already retinted, so new/pasted text needs no repaint of its own: sets
  navy, THEN types new text, waits for the debounce repaint (a tag over
  offset 1, \"defn\"), and checks that tag is :special-form at navy's
  #c792ea."
  [ui next]
  (let [ed (:editor ui) buf (:buf ed)
        tag (get (:tags ed) :special-form)]
    ((:set-theme!* ui) :navy)
    (editor/set-text! ed "(defn f [x] :k)")
    (poll-until 500 20
                (fn [] (g/has-tag-at? buf 1 tag))
                (fn [tagged?]
                  (let [fg (g/rgba-property-hex tag "foreground-rgba")]
                    (check :theme-applies-to-new-text
                           (and tagged? (= "#c792ea" fg))
                           {:tagged tagged? :fg fg})
                    ((:set-theme!* ui) :light)
                    ((:zoom!* ui) :reset)
                    (next))))))

;; --- TH.T5: the chord, startup loading, saving, isolation -----------------

(defn- row-theme-cycle
  "Four :cycle-theme!* calls with no program running walk theme/theme-names
  in order, starting from :light (this harness's own isolated settings
  path has no file yet, so activate!'s startup load defaults to :light).
  After the FIRST call the status label reads \"Theme: dark\" and the
  settings file at (:settings-path* ui) reads back {:theme :dark} through
  jonquil.settings/load-settings itself, not a raw slurp.

  set-status! (window.clj) is on-gui!-scheduled, the same async-drain
  lesson (I1) every console-text assertion in this file already respects
  -- a synchronous read right after :cycle-theme!* can race the queued
  update and see a PRIOR row's leftover status text, so status1 is polled
  for, not read once."
  [ui next]
  ((:cycle-theme!* ui))
  (poll-until 500 20
              (fn [] (= "Theme: dark" (g/label-text (:status-label ui))))
              (fn [status1-ok?]
                (let [name1 ((:theme-name* ui))
                      status1 (g/label-text (:status-label ui))
                      settings1 (settings/load-settings ((:settings-path* ui)))]
                  ((:cycle-theme!* ui))
                  (let [name2 ((:theme-name* ui))]
                    ((:cycle-theme!* ui))
                    (let [name3 ((:theme-name* ui))]
                      ((:cycle-theme!* ui))
                      (let [name4 ((:theme-name* ui))]
                        (check :theme-cycle
                               (and (= :dark name1) (= :solarized name2) (= :navy name3) (= :light name4)
                                    status1-ok? (= :dark (:theme settings1)))
                               {:name1 name1 :name2 name2 :name3 name3 :name4 name4
                                :status1 status1 :settings1 settings1})
                        ((:set-theme!* ui) :light)
                        ((:zoom!* ui) :reset)
                        (next))))))))

(defn- row-theme-chord
  "The window's own key dispatch, driven directly (never a synthesized
  keystroke): 0x04d (uppercase M, as Shift+m arrives) + CONTROL-MASK +
  SHIFT-MASK, then 0x06d (lowercase m) + META-MASK + SHIFT-MASK -- both
  Control and Meta count as the command chord (gtk-keys.md), so this
  covers Linux and macOS. Each call must return 1 and advance the theme
  one step from wherever the previous call (or an earlier row) left it."
  [ui next]
  (let [before ((:theme-name* ui))
        h1 ((:handle-window-key! ui) nil 0x04d 0 (bit-or g/CONTROL-MASK g/SHIFT-MASK) nil)
        after1 ((:theme-name* ui))
        h2 ((:handle-window-key! ui) nil 0x06d 0 (bit-or g/META-MASK g/SHIFT-MASK) nil)
        after2 ((:theme-name* ui))]
    (check :theme-chord
           (and (= 1 h1) (not= before after1) (= 1 h2) (not= after1 after2))
           {:before before :h1 h1 :after1 after1 :h2 h2 :after2 after2})
    ((:set-theme!* ui) :light)
    ((:zoom!* ui) :reset)
    (next)))

(defn- row-theme-chord-needs-shift
  "The same lower-cased 0x06d with CONTROL-MASK alone (no Shift) must not
  cycle the theme: the window handler returns 0 and the theme is
  unchanged."
  [ui next]
  (let [before ((:theme-name* ui))
        handled ((:handle-window-key! ui) nil 0x06d 0 g/CONTROL-MASK nil)
        after ((:theme-name* ui))]
    (check :theme-chord-needs-shift (and (= 0 handled) (= before after))
           {:handled handled :before before :after after})
    ((:set-theme!* ui) :light)
    ((:zoom!* ui) :reset)
    (next)))

(defn- row-settings-isolated
  "This window's own settings path (:settings-path* ui) lives under
  java.io.tmpdir and differs from the real one (jonquil.settings/
  settings-path, no env/home override -- the actual production path);
  cycling the theme, which writes to the WINDOW's path, must never touch
  the real file. Fix wave item 5: checked via .exists/.lastModified/
  .length only -- never a slurp of the real file's content, which used to
  read (and, on every check, print) whatever theme the user's own real
  settings.edn happens to hold."
  [ui next]
  (let [window-path ((:settings-path* ui))
        real-path (settings/settings-path)
        real-file (java.io.File. ^String real-path)
        existed-before (.exists real-file)
        mtime-before (.lastModified real-file)
        length-before (.length real-file)]
    ((:cycle-theme!* ui))
    (let [existed-after (.exists real-file)
          mtime-after (.lastModified real-file)
          length-after (.length real-file)]
      ((:set-theme!* ui) :light)
      ((:zoom!* ui) :reset)
      (check :settings-isolated
             (and (str/starts-with? window-path (System/getProperty "java.io.tmpdir"))
                  (not= window-path real-path)
                  (= existed-before existed-after)
                  (= mtime-before mtime-after)
                  (= length-before length-after))
             {:window-path window-path :real-path real-path
              :existed-before existed-before :existed-after existed-after
              :mtime-before mtime-before :mtime-after mtime-after
              :length-before length-before :length-after length-after})
      (next))))

(defn- row-settings-unreadable-warns
  "A settings file whose contents aren't readable EDN: (:load-settings!* ui)
  (activate!'s own startup-load fn, exposed so this row drives the exact
  same path) against it must still leave the theme at :light and append a
  warning to the console -- jonquil.settings/load-settings' own message,
  'couldn't read <path>, using light', prefixed with '; settings: '."
  [ui next]
  (let [mark (out-end-offset ui)
        tmp (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-bad-settings-" (System/currentTimeMillis) ".edn")]
    (spit tmp "{:theme")
    ((:load-settings!* ui) tmp)
    (poll-until 1000 20
                (fn [] (str/includes? (since ui mark) "; settings: couldn't read"))
                (fn [warned?]
                  (let [theme-name ((:theme-name* ui))]
                    (check :settings-unreadable-warns
                           (and (= :light theme-name) warned?)
                           {:theme-name theme-name :console (since ui mark)})
                    (.delete (java.io.File. ^String tmp))
                    ((:set-theme!* ui) :light)
                    ((:zoom!* ui) :reset)
                    (next))))))

(defn- row-theme-save-failure-reported
  "Test hook: :settings-path!* (TH.T5's own self-test-only addition,
  window.clj) repoints the window's own settings-path atom -- here at an
  IMPOSSIBLE path (a regular file used as the would-be parent directory,
  so io/make-parents can't create it and the temp-file spit inside it
  throws). :cycle-theme!* must still advance the theme and report the
  save failure on the console (jonquil.settings/save-settings!'s own
  'couldn't save <path>: ...' message, prefixed with '; settings: ')."
  [ui next]
  (let [mark (out-end-offset ui)
        original-path ((:settings-path* ui))
        blocking-file (str (System/getProperty "java.io.tmpdir")
                           "/jonquil-selftest-impossible-parent-" (System/currentTimeMillis))
        impossible-path (str blocking-file "/settings.edn")]
    (spit blocking-file "not a directory")
    (let [before ((:theme-name* ui))]
      ((:settings-path!* ui) impossible-path)
      ((:cycle-theme!* ui))
      (let [after ((:theme-name* ui))]
        (poll-until 1000 20
                    (fn [] (str/includes? (since ui mark) "; settings: couldn't save"))
                    (fn [reported?]
                      ((:settings-path!* ui) original-path)
                      (check :theme-save-failure-reported
                             (and (not= before after) reported?)
                             {:before before :after after :reported reported? :console (since ui mark)})
                      (.delete (java.io.File. ^String blocking-file))
                      ((:set-theme!* ui) :light)
                      ((:zoom!* ui) :reset)
                      (next)))))))

(defn- row-theme-switch-while-running
  "The locked status-bar decision (the brief): cycling the theme while a
  program is running must not overwrite a live Running…/Waiting for input
  with 'Theme: <name>'. Runs (loop [] (recur)), waits for the status to
  actually read Running…, cycles the theme, and requires the status to
  STILL read Running… right after, the theme to have advanced, and Escape
  (via :handle-window-key!, still the same real Stop path) to still stop
  the program.

  The brief's own row text says 'wait for Ready' at the end; measured
  against make-eval-opts' :on-complete (window.clj), an INTERRUPTED run's
  terminal status is 'Stopped', never 'Ready' -- :on-status-change sets
  'Ready' first (the :idle notification), then :on-complete immediately
  overwrites it with 'Stopped' since (:status result) is :interrupted here.
  'Ready' is only ever the terminal text for a run that finishes on its
  own (row-run-output's own case). This row waits for the settled terminal
  status instead, 'Stopped', which is what a poll for 'Ready' would never
  see after an Escape-stop -- confirmed live: with the literal brief text
  this row FAILed with :ready false every run."
  [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(loop [] (recur))")
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (= "Running…" (g/label-text (:status-label ui))))
                (fn [running?]
                  (let [before ((:theme-name* ui))]
                    ((:cycle-theme!* ui))
                    (let [after ((:theme-name* ui))
                          status-right-after (g/label-text (:status-label ui))
                          handled ((:handle-window-key! ui) nil KEY-ESCAPE 0 0 nil)]
                      (poll-until 3000 20
                                  (fn [] (str/includes? (since ui mark) "; interrupted"))
                                  (fn [stopped?]
                                    (poll-until 2000 20
                                                (fn [] (= "Stopped" (g/label-text (:status-label ui))))
                                                (fn [settled?]
                                                  (check :theme-switch-while-running
                                                         (and running? (not= before after)
                                                              (= "Running…" status-right-after)
                                                              (= 1 handled) stopped? settled?)
                                                         {:running running? :before before :after after
                                                          :status status-right-after :handled handled
                                                          :stopped stopped? :settled settled?
                                                          :console (since ui mark)})
                                                  ((:set-theme!* ui) :light)
                                                  ((:zoom!* ui) :reset)
                                                  (next)))))))))))

;; gtk-keys.md's shared table -- window.clj's own copy is private.
(def ^:private KEY-W 0x77)

;; --- P4.T3: the caret-anchored popover base, its theme colours, and the
;; window's key order -------------------------------------------------------

(defn- row-popover-at-caret
  "Probe 2: a test popover (autohide off, a plain label child), shown at
  caret offset 5 in \"(defn f [x] x)\". jonquil.gtk.popover's own fix
  (report: GtkTextView's dispose hangs on a directly-parented popover, see
  popover.clj's ns docstring) makes the popover's real GtkWidget parent
  the view's scrolled window, not the view -- so the expected x is
  g/caret-window-rect's x translated (g/compute-point) into THAT parent's
  coordinate space, the same computation show-at-caret! itself makes, read
  back independently against the popover's own pointing-to rectangle
  (g/popover-pointing-to, gtk_popover_get_pointing_to)."
  [ui next]
  (let [ed (:editor ui) view (:view ed)
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    (editor/set-text! ed "(defn f [x] x)")
    (place-cursor! (:buf ed) 5)
    (popover/set-child! pp (g/gtk-label-new "candidate"))
    (popover/show-at-caret! pp)
    (let [[cx cy] (g/caret-window-rect view)
          [expected-x] (g/compute-point view (g/gtk-widget-get-parent (:popover pp)) cx cy)
          [actual-x] (g/popover-pointing-to (:popover pp))
          visible (g/gtk-widget-get-visible (:popover pp))]
      (check :popover-at-caret
             (and (popover/open? pp) (= 1 visible) (= (Math/round (double expected-x)) actual-x))
             {:open (popover/open? pp) :visible visible
              :expected-x expected-x :actual-x actual-x})
      (popover/hide! pp)
      (g/gtk-widget-unparent (:popover pp))
      (next))))

(defn- row-popover-keys-stay-with-editor
  "Probe 1: with autohide FALSE, the view's own capture-phase key
  controller keeps every key regardless of whether a popover is showing --
  nothing in jonquil.gtk.popover filters keys, so editor/handle-key! with
  an ordinary letter behaves exactly as it would with no popover open (0,
  unhandled -- GTK's own default handling inserts it), and the popover the
  row opened stays open, since nothing here closes it."
  [ui next]
  (let [ed (:editor ui) view (:view ed)
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    (popover/set-child! pp (g/gtk-label-new "candidate"))
    (popover/show-at-caret! pp)
    (let [handled (editor/handle-key! ed KEY-A 0)]
      (check :popover-keys-stay-with-editor
             (and (= 0 handled) (popover/open? pp))
             {:handled handled :open (popover/open? pp)})
      (popover/hide! pp)
      (g/gtk-widget-unparent (:popover pp))
      (next))))

(defn- row-window-shift-f6-reaches-editor
  "P4.T3 key-order change #1 (probe 4): the window's F6 clause now also
  requires (not shift-held?), so Shift+F6 no longer matches it and falls
  through unconsumed -- returns 0, and focus (read via gtk_root_get_focus,
  the same pointer-identity check do-toggle-focus! itself uses) is
  unchanged. Grabs editor focus first so the row starts from a known
  state regardless of what an earlier row left focused."
  [ui next]
  (let [win (:window ui) ed (:editor ui)]
    (editor/focus! ed)
    (let [before (g/gtk-root-get-focus win)
          handled ((:handle-window-key! ui) nil KEY-F6 0 g/SHIFT-MASK nil)
          after (g/gtk-root-get-focus win)]
      (check :window-shift-f6-reaches-editor
             (and (= 0 handled) (= before after))
             {:handled handled :before before :after after})
      (next))))

(defn- row-window-f6-still-toggles
  "Plain F6 (no modifier) must still toggle focus after the Shift guard was
  added -- console.clj's view first, editor's back, same pattern
  row-toggle-focus already uses for the :toggle-focus!* hook, but driven
  through the WINDOW's own key dispatch (:handle-window-key!) instead,
  since that's the exact path a real F6 keystroke takes."
  [ui next]
  (let [win (:window ui) ed (:editor ui) cv (:console ui)]
    (editor/focus! ed)
    (let [h1 ((:handle-window-key! ui) nil KEY-F6 0 0 nil)
          after-first (g/gtk-root-get-focus win)
          h2 ((:handle-window-key! ui) nil KEY-F6 0 0 nil)
          after-second (g/gtk-root-get-focus win)]
      (check :window-f6-still-toggles
             (and (= 1 h1) (= after-first (:view cv)) (= 1 h2) (= after-second (:view ed)))
             {:h1 h1 :h2 h2 :after-first after-first :after-second after-second})
      (next))))

(defn- row-window-escape-closes-popover
  "P4.T3 key-order change #2 (probe 4): a new clause ahead of the
  evaluating? Stop clause closes any open P4 popover on Escape and returns
  1 without touching the program. Starts a real (loop [] (recur)), opens a
  test popover registered on the window's own registry
  (:register-popover!*, the same join completion/quick-doc/rename will use
  from P4.T4 on), sends window Escape: the popover must close and the
  program must still be running. A second Escape (nothing left open) must
  then fall through to the evaluating? clause and Stop it."
  [ui next]
  (let [ed (:editor ui) view (:view ed)
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    (editor/set-text! ed "(loop [] (recur))")
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  (popover/set-child! pp (g/gtk-label-new "candidate"))
                  (popover/show-at-caret! pp)
                  ((:register-popover!* ui) pp)
                  (let [mark (out-end-offset ui)
                        h1 ((:handle-window-key! ui) nil KEY-ESCAPE 0 0 nil)
                        closed-open? (not (popover/open? pp))
                        still-running-1? (eval/evaluating? (:ctx (:session ui)))
                        h2 ((:handle-window-key! ui) nil KEY-ESCAPE 0 0 nil)]
                    (poll-until 3000 20
                                (fn [] (str/includes? (since ui mark) "; interrupted"))
                                (fn [stopped?]
                                  (check :window-escape-closes-popover
                                         (and started? (= 1 h1) closed-open? still-running-1?
                                              (= 1 h2) stopped?)
                                         {:started started? :h1 h1 :closed-open closed-open?
                                          :still-running-1 still-running-1? :h2 h2 :stopped stopped?})
                                  ;; Belt-and-braces: hide! is idempotent, so
                                  ;; this leaves clean state for later rows
                                  ;; even if close-popovers-fn didn't fire.
                                  ;;
                                  ;; P4.T4 fix round 1: does NOT unparent pp
                                  ;; itself, unlike every other throwaway-
                                  ;; popover row in this file. pp is
                                  ;; REGISTERED (:register-popover!* above),
                                  ;; and unparenting a floating-ref GtkWidget
                                  ;; drops its last reference and finalizes
                                  ;; it right there (measured: this row used
                                  ;; to leave a dangling pointer in the
                                  ;; window's own registry for the rest of
                                  ;; the run, and window.clj's own new
                                  ;; unparent-registered-popovers! -- run
                                  ;; once, for real, at close-request-fn's
                                  ;; real-quit branch -- then called
                                  ;; gtk_widget_get_parent on that freed
                                  ;; pointer: \"Gtk-CRITICAL:
                                  ;; gtk_widget_get_parent: assertion
                                  ;; GTK_IS_WIDGET (widget) failed\"). Left
                                  ;; parented (hidden) here, it stays a
                                  ;; harmless, invisible, do-nothing widget
                                  ;; for the rest of the run and is cleaned
                                  ;; up exactly once, safely, by that same
                                  ;; function at real window teardown.
                                  (popover/hide! pp)
                                  (next))))))))

(defn- row-popover-dark-colours
  "Probe 7's measured selector, read back from the shared provider's OWN
  serialisation (:code-css*, gtk_css_provider_to_string) -- like row-
  theme-css, GTK normalises the theme's hex colours to rgb(r,g,b): dark's
  :background #000000 -> rgb(0,0,0), :selection #3e4451 -> rgb(62,68,81)."
  [ui next]
  (let [ed (:editor ui) view (:view ed)
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    ((:set-theme!* ui) :dark)
    (popover/set-child! pp (g/gtk-label-new "candidate"))
    (popover/show-at-caret! pp)
    (let [css ((:code-css* ui))]
      (check :popover-dark-colours
             (and (str/includes? css "popover.jonquil-popover")
                  (str/includes? css "rgb(0,0,0)")
                  (str/includes? css "rgb(62,68,81)"))
             {:css css})
      (popover/hide! pp)
      (g/gtk-widget-unparent (:popover pp))
      ((:set-theme!* ui) :light)
      (next))))

;; --- P4.T4: quick doc and jump to definition -------------------------------

;; gtk-keys.md's shared table, this file's own private copies (measured
;; against gdkkeysyms.h, same values editor.clj's own KEY-Q/KEY-B/KEY-F1/
;; KEY-F12 use).
(def ^:private KEY-Q 0x071)
(def ^:private KEY-B 0x062)
(def ^:private KEY-F1 0xffbe)
(def ^:private KEY-F12 0xffc9)

;; P4.T7: this file's own private copy (measured against gdkkeysyms.h,
;; same value jonquil.gtk.editor's own KEY-F2 uses); KEY-F6 above already
;; covers Shift+F6.
(def ^:private KEY-F2 0xffbf)

;; P4.T5: this file's own private copies (measured against gdkkeysyms.h,
;; same values jonquil.gtk.completion's own KEY-UP/KEY-DOWN/KEY-TAB use;
;; KEY-ESCAPE/KEY-RETURN above already cover Escape/Enter for this file).
(def ^:private KEY-SPACE 0x020)
(def ^:private KEY-UP 0xff52)
(def ^:private KEY-DOWN 0xff54)
(def ^:private KEY-TAB 0xff09)

;; I3 fix wave: this file's own private copies (measured against
;; gdkkeysyms.h) for the keys the completion popup must never swallow --
;; row-completion-keys-reach-editor/-closes-on-space and their console
;; counterparts below.
(def ^:private KEY-P 0x070)
(def ^:private KEY-BACKSPACE 0xff08)
(def ^:private KEY-LEFT 0xff51)

(defn- row-quick-doc-core
  "editor/handle-key! directly with Ctrl+Q (control-only?, no synthesized
  keystroke): the popover opens at the caret and its label -- read back
  via help-popover/quick-doc-text -- carries clojure.core/map's own
  arglists."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-Q g/CONTROL-MASK)
          text (help-popover/quick-doc-text hp)]
      (check :quick-doc-core
             (and (= 1 handled) (popover/open? hp)
                  (str/includes? text "clojure.core/map")
                  (str/includes? text "([f] [f coll]"))
             {:handled handled :open (popover/open? hp) :text text})
      (popover/hide! hp)
      (next))))

(defn- row-quick-doc-run-defined
  "Runs a real Run defining greet with a docstring, waits for it to
  actually land (run-and-wait!'s own I1 gating), then Shift+F1 on a fresh
  (greet 1) shows that docstring."
  [ui next]
  (run-and-wait! ui "(defn greet \"Says hi.\" [name] name)" 3000
                 (fn [t] (str/includes? t "=> #'user/greet"))
                 (fn [defined? _]
                   (let [ed (:editor ui) hp (:help-popover ui)]
                     (editor/set-text! ed "(greet 1)")
                     (place-cursor! (:buf ed) 2)
                     (let [handled (editor/handle-key! ed KEY-F1 g/SHIFT-MASK)
                           text (help-popover/quick-doc-text hp)]
                       (check :quick-doc-run-defined
                              (and defined? (= 1 handled) (str/includes? text "Says hi."))
                              {:defined defined? :handled handled :text text})
                       (popover/hide! hp)
                       (next))))))

(defn- row-quick-doc-after-reset
  "Continues from the same defined greet, then forces Reset-on-Run mode
  (session/set-mode!, since an earlier row in this file's own sequence
  left the session in :accumulate -- see row-reset-vs-accumulate) and
  Runs a program that does NOT define greet: reset-namespaces! removes
  the whole `user` ns, including greet, before evaluating it. A quick doc
  on the SAME (greet 1) text afterward must not throw (docs/symbol-doc's
  own `user`-missing fallback, P4.T2) and must read as not found -- and,
  since docs/symbol-doc memoises on [ns sym text] and this reuses the
  EXACT SAME key row-quick-doc-run-defined just cached with a real doc,
  this also depends on window.clj's clear-doc-cache! running after the
  second Run (P4.T2 review's Important)."
  [ui next]
  (run-and-wait! ui "(defn greet \"Says hi.\" [name] name)" 3000
                 (fn [t] (str/includes? t "=> #'user/greet"))
                 (fn [defined1? _]
                   (session/set-mode! (:session ui) :reset)
                   (run-and-wait! ui "(+ 1 1)" 3000
                                  (fn [t] (str/includes? t "=> 2"))
                                  (fn [reran? _]
                                    (let [ed (:editor ui) hp (:help-popover ui)]
                                      (editor/set-text! ed "(greet 1)")
                                      (place-cursor! (:buf ed) 2)
                                      (let [{:keys [handled threw]}
                                            (try {:handled (editor/handle-key! ed KEY-Q g/CONTROL-MASK)}
                                                 (catch Throwable e {:threw (ex-message e)}))
                                            text (help-popover/quick-doc-text hp)]
                                        (check :quick-doc-after-reset
                                               (and defined1? reran? (nil? threw) (= 1 handled)
                                                    (str/includes? text "No documentation found"))
                                               {:defined1 defined1? :reran reran?
                                                :handled handled :threw threw :text text})
                                        (popover/hide! hp)
                                        (next))))))))

(defn- row-quick-doc-closes
  "gtk_text_buffer_place_cursor's own cursor-position notify (synchronous,
  no on-gui! drain, per row-line-col) closes the popover the moment the
  caret moves -- no poll needed."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (place-cursor! (:buf ed) 2)
    (editor/handle-key! ed KEY-Q g/CONTROL-MASK)
    (let [opened? (popover/open? hp)]
      (place-cursor! (:buf ed) 3)
      (check :quick-doc-closes
             (and opened? (not (popover/open? hp)))
             {:opened opened? :still-open (popover/open? hp)})
      (next))))

(defn- row-quick-doc-closes-on-focus-loss
  "P4.T4 fix round 1 (Minor): calls every fn registered on ed's own
  :on-focus-fns directly -- the SAME ones GtkEventControllerFocus's own
  \"leave\" signal calls (editor.clj's make-editor) -- rather than trying
  to move real OS/window focus, which the self-test window can't reliably
  do (P4.T3's own has_focus finding: this window is never the OS's key
  window, so has_focus stays false throughout and a real focus transfer
  can't be observed here the way gtk_root_get_focus's own focus-WITHIN
  tracking can). Fails first with window.clj's hide-on-focus-lost closure
  temporarily removed (see the report)."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (place-cursor! (:buf ed) 2)
    (editor/handle-key! ed KEY-Q g/CONTROL-MASK)
    (let [opened? (popover/open? hp)]
      (doseq [f @(:on-focus-fns ed)] (f))
      (check :quick-doc-closes-on-focus-loss
             (and opened? (not (popover/open? hp)))
             {:opened opened? :still-open (popover/open? hp)})
      (next))))

(defn- row-quick-doc-cmd-does-nothing
  "0x71 with META-MASK only: control-only? rejects it (the P4 rule --
  CONTROL-MASK checked, META-MASK rejected), so the chord falls through
  unhandled and no popover opens."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-Q g/META-MASK)]
      (check :quick-doc-cmd-does-nothing
             (and (= 0 handled) (not (popover/open? hp)))
             {:handled handled :open (popover/open? hp)})
      (next))))

(defn- row-quick-doc-cmd-shift-f1-does-nothing
  "0xffbe (F1) with SHIFT and META together: shift-only? rejects it (fix
  round 1 -- it used to check shift-held? alone, so this chord opened
  quick doc same as plain Shift+F1, measured live before the fix), so
  the chord falls through unhandled and no popover opens."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-F1 (bit-or g/SHIFT-MASK g/META-MASK))]
      (check :quick-doc-cmd-shift-f1-does-nothing
             (and (= 0 handled) (not (popover/open? hp)))
             {:handled handled :open (popover/open? hp)})
      (next))))

(defn- row-quick-doc-after-define
  "T2 review's Important, widened (context clue #3): a lookup before
  anything defines the name must not keep serving :not-found once a REPL
  entry defines it. Looks up quickdocdefme -- a name unique to this row --
  before anything defines it (help/doc-text's own \"No documentation
  found\"), defines it through the SAME path the console's Return uses
  (an interactive insert + handle-return!, never a synthesized key), then
  looks it up again with the IDENTICAL buffer text: the memo key
  ([ns sym text]) is the same both times, so this fails if window.clj's
  clear-doc-cache! call after a completed REPL eval is removed -- verified
  live (see the report) by removing that call, confirming this row (and
  quick-doc-after-reset above, which depends on the SAME call site for its
  own Run) both fail, then restoring it."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui) cv (:console ui)
        code "(quickdocdefme 1)"]
    (editor/set-text! ed code)
    (place-cursor! (:buf ed) 2)
    (let [h1 (editor/handle-key! ed KEY-Q g/CONTROL-MASK)
          before (help-popover/quick-doc-text hp)]
      (popover/hide! hp)
      (let [mark (out-end-offset ui)]
        (g/gtk-text-buffer-insert-interactive-at-cursor
         (:buf cv) "(defn quickdocdefme \"Freshly defined.\" [n] n)" -1 1)
        (cview/handle-return! cv false)
        (poll-until 3000 20
                    (fn [] (and (idle? ui) (str/includes? (since ui mark) "=> #'user/quickdocdefme")))
                    (fn [defined?]
                      (editor/set-text! ed code)
                      (place-cursor! (:buf ed) 2)
                      (let [h2 (editor/handle-key! ed KEY-Q g/CONTROL-MASK)
                            after (help-popover/quick-doc-text hp)]
                        (check :quick-doc-after-define
                               (and (= 1 h1) (str/includes? before "No documentation found")
                                    defined? (= 1 h2) (str/includes? after "Freshly defined."))
                               {:h1 h1 :before before :defined defined? :h2 h2 :after after})
                        (popover/hide! hp)
                        (next))))))))

(defn- row-jump-top-level
  "\"(defn greet [n] n)\\n(greet 1)\", caret 22 (inside the SECOND greet,
  the usage) -- help/jump-target takes the symbol's START (P4.T1's
  jump-target docstring), F12 (no-modifiers?) lands the caret on the
  DEFN's own greet, offset 6."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(defn greet [n] n)\n(greet 1)")
    (place-cursor! (:buf ed) 22)
    (let [handled (editor/handle-key! ed KEY-F12 0)
          caret (g/cursor-offset (:buf ed))]
      (check :jump-top-level
             (and (= 1 handled) (= 6 caret))
             {:handled handled :caret caret})
      (next))))

(defn- row-jump-local-binding
  "\"(defn f [x] (let [y x] y))\", caret 23 (the LAST y, the usage inside
  the body) -- Ctrl+B (control-only?) lands on the BINDING y inside
  [y x], offset 18 (measured with the pure spike's help/jump-target, per
  the brief)."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(defn f [x] (let [y x] y))")
    (place-cursor! (:buf ed) 23)
    (let [handled (editor/handle-key! ed KEY-B g/CONTROL-MASK)
          caret (g/cursor-offset (:buf ed))]
      (check :jump-local-binding
             (and (= 1 handled) (= 18 caret))
             {:handled handled :caret caret})
      (next))))

(defn- row-jump-external
  "\"(map inc [1])\", caret 2, F12: find-definition finds nothing in the
  buffer, so jump-target falls to the :external case and the SAME quick-
  doc popover shows clojure.core/map's own doc."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-F12 0)
          text (help-popover/quick-doc-text hp)]
      (check :jump-external
             (and (= 1 handled) (popover/open? hp) (str/includes? text "clojure.core/map"))
             {:handled handled :open (popover/open? hp) :text text})
      (popover/hide! hp)
      (next))))

(defn- row-jump-none
  "\"(nope 1)\", caret 2, F12: a real symbol with no definition anywhere
  -- jump-target's own nil case -- reads as the status message, polled
  (P4.T4: do-jump! goes through set-status!, which enqueues via on-gui!
  same as every other status update in this file, never synchronous)."
  [ui next]
  (let [ed (:editor ui) status (:status-label ui)]
    (editor/set-text! ed "(nope 1)")
    (place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-F12 0)]
      (poll-until 1000 20
                  (fn [] (= "No definition found for nope." (g/label-text status)))
                  (fn [status-ok?]
                    (check :jump-none
                           (and (= 1 handled) status-ok?)
                           {:handled handled :status (g/label-text status)})
                    (next))))))

(defn- row-jump-shadowed-param
  "I1: \"(def x 1)\\n(defn f [x] x)\", caret 22 (the body's own x, a usage
  of the PARAMETER, not the top-level def) -- jonquil.help/jump-target's
  scope-aware search finds the enclosing param first, so F12 lands on the
  param at offset 19, never the top-level def's own x at offset 5 (measured
  live against the fix: test/jonquil/help_test.clj's own jump-target-
  scope-test carries the same fixture/offsets, pure)."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(def x 1)\n(defn f [x] x)")
    (place-cursor! (:buf ed) 22)
    (let [handled (editor/handle-key! ed KEY-F12 0)
          caret (g/cursor-offset (:buf ed))]
      (check :jump-shadowed-param
             (and (= 1 handled) (= 19 caret))
             {:handled handled :caret caret})
      (next))))

;; --- P4.T5: completion with a doc preview ----------------------------------

(defn- row-completion-opens
  "\"(ma\", caret 3, :complete!*: the popover opens and its rows equal
  (mapv :symbol (docs/candidates ns \"ma\" \"(ma\" 3)) in order (measured
  live: the first is macroexpand), and the preview contains
  clojure.core/<first row>."
  [ui next]
  (let [ed (:editor ui) c (:completion ui) ns-sym (:prompt-ns @(:state (:session ui)))]
    (editor/set-text! ed "(ma")
    (place-cursor! (:buf ed) 3)
    ((:complete!* ui))
    (let [st (completion/state c)
          expected (mapv :symbol (docs/candidates ns-sym "ma" "(ma" 3))
          rows (mapv :symbol (:candidates st))
          preview (g/label-text (:doc-label c))]
      (check :completion-opens
             (and (some? st) (seq rows) (= expected rows)
                  (str/includes? preview (str "clojure.core/" (first rows))))
             {:expected expected :rows rows :preview preview})
      (completion/close! c)
      (next))))

(defn- row-completion-single-inserts
  "\"(mapca\", caret 6, Ctrl+Space: docs/candidates for \"mapca\" is exactly
  [mapcat] (measured live), so it commits at once -- text \"(mapcat\", no
  popover."
  [ui next]
  (let [ed (:editor ui) c (:completion ui)]
    (editor/set-text! ed "(mapca")
    (place-cursor! (:buf ed) 6)
    (let [handled (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)]
      (check :completion-single-inserts
             (and (= 1 handled) (nil? (completion/state c)) (= "(mapcat" (editor/editor-text ed)))
             {:handled handled :state (completion/state c) :text (editor/editor-text ed)})
      (next))))

(defn- row-completion-no-prefix
  "\"( \", caret 2, Ctrl+Space: help/prefix-at is nil (the char before the
  caret is a space, not a symbol char) -- returns 1 so GTK never inserts a
  literal space, nothing changes, no popover."
  [ui next]
  (let [ed (:editor ui) c (:completion ui)]
    (editor/set-text! ed "( ")
    (place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)]
      (check :completion-no-prefix
             (and (= 1 handled) (= "( " (editor/editor-text ed)) (nil? (completion/state c)))
             {:handled handled :text (editor/editor-text ed) :state (completion/state c)})
      (next))))

(defn- row-completion-navigate-commit
  "Open on \"(ma\", Down, Enter: the selection moves to index 1, Enter
  commits the SECOND candidate (apply-op!, one undo step) -- text is
  \"(\" + that candidate's own symbol, and one gtk_text_buffer_undo gives
  back \"(ma\"."
  [ui next]
  (let [ed (:editor ui) c (:completion ui)]
    (editor/set-text! ed "(ma")
    (place-cursor! (:buf ed) 3)
    (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
    (let [st0 (completion/state c)
          second-sym (:symbol (nth (:candidates st0) 1))
          h1 (editor/handle-key! ed KEY-DOWN 0)
          st1 (completion/state c)
          h2 (editor/handle-key! ed KEY-RETURN 0)
          text (editor/editor-text ed)]
      (g/gtk-text-buffer-undo (:buf ed))
      (let [text-after-undo (editor/editor-text ed)]
        (check :completion-navigate-commit
               (and (some? st0) (>= (count (:candidates st0)) 2)
                    (= 1 h1) (some? st1) (= 1 (:index st1))
                    (= 1 h2) (nil? (completion/state c))
                    (= (str "(" second-sym) text)
                    (= "(ma" text-after-undo))
               {:candidate-count (count (:candidates st0)) :second-sym second-sym
                :h1 h1 :index1 (:index st1) :h2 h2 :text text :text-after-undo text-after-undo})
        (next)))))

(defn- row-completion-tab-commits
  "Open, Tab: commits the selected (first) candidate the same way Enter
  does, and closes the popover."
  [ui next]
  (let [ed (:editor ui) c (:completion ui)]
    (editor/set-text! ed "(ma")
    (place-cursor! (:buf ed) 3)
    (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
    (let [opened? (some? (completion/state c))
          h (editor/handle-key! ed KEY-TAB 0)]
      (check :completion-tab-commits
             (and opened? (= 1 h) (nil? (completion/state c)) (not= "(ma" (editor/editor-text ed)))
             {:opened opened? :h h :text (editor/editor-text ed)})
      (next))))

(defn- row-completion-keys-reach-editor
  "Open on \"(ma\": p, Backspace and Left must never be swallowed by the
  popup (I3 -- the old version of this row inserted \"p\" with
  insert_interactive_at_cursor alone, bypassing editor/handle-key! entirely,
  so it would still pass even if completion/handle-key! returned 1 for
  every one of these). completion/handle-key! itself is checked directly
  first and must return 0 for all three (none of Up/Down/PageUp/PageDown/
  Return/KP-Enter/Tab), then editor/handle-key! -- the view's real dispatch
  -- is called for real and must agree: 0 for every one of them, since
  editor.clj has no clause for a bare letter or for Left, and
  jonquil.edit/backspace-pair returns nil whenever the caret sits at the
  buffer's end (see backspace-interactive!'s own docstring), so its own
  KEY-BACKSPACE clause does nothing there either. A bare handle-key! call
  never triggers GTK's own default handling underneath a 0 return (there is
  no real key event driving it), so p's insert and Backspace's delete are
  still reproduced with insert/delete-interactive, the same stand-ins the
  rest of this file uses for GTK's default -- now gated on the checks
  above, unlike before the fix. The popover stays open and refines through
  both (still-valid?'s own prefix-start check holds for \"map\" then back to
  \"ma\"). Left has no observable buffer effect to check beyond its own 0
  return -- a bare call doesn't move the caret either."
  [ui next]
  (let [ed (:editor ui) c (:completion ui) ns-sym (:prompt-ns @(:state (:session ui)))]
    (editor/set-text! ed "(ma")
    (place-cursor! (:buf ed) 3)
    (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
    (let [opened? (some? (completion/state c))
          p-swallowed (completion/handle-key! c KEY-P 0)
          p-handled (editor/handle-key! ed KEY-P 0)]
      (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "p" -1 1)
      (let [text-p (editor/editor-text ed)
            st-p (completion/state c)
            expected-p (mapv :symbol (docs/candidates ns-sym "map" "(map" 4))
            rows-p (mapv :symbol (:candidates st-p))
            bs-swallowed (completion/handle-key! c KEY-BACKSPACE 0)
            bs-handled (editor/handle-key! ed KEY-BACKSPACE 0)]
        (backspace-interactive! (:buf ed) (g/cursor-offset (:buf ed)))
        (let [text-bs (editor/editor-text ed)
              st-bs (completion/state c)
              expected-bs (mapv :symbol (docs/candidates ns-sym "ma" "(ma" 3))
              rows-bs (mapv :symbol (:candidates st-bs))
              left-swallowed (completion/handle-key! c KEY-LEFT 0)
              left-handled (editor/handle-key! ed KEY-LEFT 0)]
          (check :completion-keys-reach-editor
                 (and opened?
                      (= 0 p-swallowed) (= 0 p-handled)
                      (= "(map" text-p) (some? st-p) (= expected-p rows-p)
                      (= 0 bs-swallowed) (= 0 bs-handled)
                      (= "(ma" text-bs) (some? st-bs) (= expected-bs rows-bs)
                      (= 0 left-swallowed) (= 0 left-handled))
                 {:opened opened? :p-swallowed p-swallowed :p-handled p-handled
                  :text-p text-p :expected-p expected-p :rows-p rows-p
                  :bs-swallowed bs-swallowed :bs-handled bs-handled
                  :text-bs text-bs :expected-bs expected-bs :rows-bs rows-bs
                  :left-swallowed left-swallowed :left-handled left-handled})
          (completion/close! c)
          (next))))))

(defn- row-completion-closes-on-space
  "Open, then completion/handle-key! and editor/handle-key! must both
  return 0 for a plain space (I3, the same check row-completion-keys-
  reach-editor makes for p/Backspace/Left -- control-only? in editor.clj's
  own Ctrl+Space clause rejects a bare space, mask 0). Only then insert a
  space with insert_interactive_at_cursor: help/prefix-at is nil right
  after the space, so refine! closes the popover -- no poll, same
  synchronous reasoning as completion-keys-reach-editor."
  [ui next]
  (let [ed (:editor ui) c (:completion ui)]
    (editor/set-text! ed "(ma")
    (place-cursor! (:buf ed) 3)
    (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
    (let [opened? (some? (completion/state c))
          swallowed (completion/handle-key! c KEY-SPACE 0)
          handled (editor/handle-key! ed KEY-SPACE 0)]
      (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) " " -1 1)
      (check :completion-closes-on-space
             (and opened? (= 0 swallowed) (= 0 handled) (nil? (completion/state c)))
             {:opened opened? :swallowed swallowed :handled handled
              :still-open (some? (completion/state c))})
      (next))))

(defn- row-completion-escape-while-running
  "Runs a real (loop [] (recur)), opens completion on \"l\" (many
  candidates), sends window Escape -- the SAME registry-based clause
  row-window-escape-closes-popover already proves for a test popover, now
  exercised through the real completion popover: the popup closes and the
  program keeps running; a second Escape (nothing left open) then falls
  through to the evaluating? clause and Stops it, same as that row."
  [ui next]
  (let [ed (:editor ui) c (:completion ui)]
    (editor/set-text! ed "(loop [] (recur))")
    (place-cursor! (:buf ed) 2)
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
                  (let [opened? (some? (completion/state c))
                        mark (out-end-offset ui)
                        h1 ((:handle-window-key! ui) nil KEY-ESCAPE 0 0 nil)
                        closed? (nil? (completion/state c))
                        still-running-1? (eval/evaluating? (:ctx (:session ui)))
                        h2 ((:handle-window-key! ui) nil KEY-ESCAPE 0 0 nil)]
                    (poll-until 3000 20
                                (fn [] (str/includes? (since ui mark) "; interrupted"))
                                (fn [stopped?]
                                  (check :completion-escape-while-running
                                         (and started? opened? (= 1 h1) closed? still-running-1?
                                              (= 1 h2) stopped?)
                                         {:started started? :opened opened? :h1 h1 :closed closed?
                                          :still-running-1 still-running-1? :h2 h2 :stopped stopped?})
                                  (completion/close! c)
                                  (next))))))))

(defn- row-completion-cmd-space-nothing
  "0x20 with META only: control-only? rejects it (CONTROL-MASK checked,
  META-MASK rejected, the P4 rule), so the chord falls through unhandled
  and no popover opens."
  [ui next]
  (let [ed (:editor ui) c (:completion ui)]
    (editor/set-text! ed "(ma")
    (place-cursor! (:buf ed) 3)
    (let [handled (editor/handle-key! ed KEY-SPACE g/META-MASK)]
      (check :completion-cmd-space-nothing
             (and (= 0 handled) (nil? (completion/state c)))
             {:handled handled :state (completion/state c)})
      (next))))

(defn- row-completion-preedit-yields
  "With ed's own preedit-active? flag set (I5, same flag editor.clj's
  handle-key! checks first for every key), Ctrl+Space returns 0 and opens
  nothing."
  [ui next]
  (let [ed (:editor ui) c (:completion ui)]
    (editor/set-text! ed "(ma")
    (place-cursor! (:buf ed) 3)
    (reset! (:preedit-active? ed) true)
    (let [handled (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)]
      (reset! (:preedit-active? ed) false)
      (check :completion-preedit-yields
             (and (= 0 handled) (nil? (completion/state c)))
             {:handled handled})
      (next))))

(defn- row-completion-closes-on-focus-loss
  "Context bullet #4 (not in the brief's own row table): calls every fn
  registered on ed's own :on-focus-fns directly -- the SAME ones
  GtkEventControllerFocus's \"leave\" signal calls -- mirroring
  row-quick-doc-closes-on-focus-loss, since this self-test window is never
  the OS's key window and can't move real focus (P4.T3's own has_focus
  finding)."
  [ui next]
  (let [ed (:editor ui) c (:completion ui)]
    (editor/set-text! ed "(ma")
    (place-cursor! (:buf ed) 3)
    ((:complete!* ui))
    (let [opened? (some? (completion/state c))]
      (doseq [f @(:on-focus-fns ed)] (f))
      (check :completion-closes-on-focus-loss
             (and opened? (nil? (completion/state c)))
             {:opened opened? :still-open (some? (completion/state c))})
      (next))))

(defn- row-completion-closes-other-popover
  "Context bullet #3 (not in the brief's own row table, which has no row
  for the two popovers together): opening completion while quick doc is
  showing closes quick doc, and opening quick doc while completion is
  showing closes completion -- both through close-popovers-fn (P4.T3's
  registry), never a pairwise special case naming the other popover."
  [ui next]
  (let [ed (:editor ui) c (:completion ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (place-cursor! (:buf ed) 2)
    (editor/handle-key! ed KEY-Q g/CONTROL-MASK)
    (let [quick-doc-open-1 (popover/open? hp)]
      ((:complete!* ui))
      (let [completion-open-1 (some? (completion/state c))
            quick-doc-closed-by-completion (not (popover/open? hp))]
        (completion/close! c)
        (editor/handle-key! ed KEY-Q g/CONTROL-MASK)
        (let [quick-doc-open-2 (popover/open? hp)]
          ((:complete!* ui))
          (let [completion-open-2 (some? (completion/state c))
                quick-doc-closed-by-completion-2 (not (popover/open? hp))]
            (check :completion-closes-other-popover
                   (and quick-doc-open-1 completion-open-1 quick-doc-closed-by-completion
                        quick-doc-open-2 completion-open-2 quick-doc-closed-by-completion-2)
                   {:quick-doc-open-1 quick-doc-open-1 :completion-open-1 completion-open-1
                    :quick-doc-closed-by-completion quick-doc-closed-by-completion
                    :quick-doc-open-2 quick-doc-open-2 :completion-open-2 completion-open-2
                    :quick-doc-closed-by-completion-2 quick-doc-closed-by-completion-2})
            (popover/hide! hp)
            (completion/close! c)
            (next)))))))

(defn- row-completion-commit-throw-consumes-key
  "P4.T5 fix round 1 (Important): forces jonquil.gtk.editor/apply-op! to
  throw for the duration of one Enter, through commit!'s own call to it --
  with-redefs on that var DOES reach the call completion.clj makes
  through its own `editor` alias (checked live before writing this row:
  without the fix, this exact with-redefs, run through editor/handle-key!,
  crashed the whole selftest process -- window.clj's :handle-key! wrapper
  caught the first throw and returned 0, so editor.clj's own handle-key!
  fell through to plain-return? -> handle-return!, whose OWN apply-op!
  call has no try/catch and hit the SAME redefined throw uncaught; see the
  report). With the fix, completion/handle-key! itself catches it and
  still returns 1: the buffer keeps its pre-commit text (no newline, no
  second edit), the popover is closed, and the status bar shows the
  error."
  [ui next]
  (let [ed (:editor ui) c (:completion ui) status (:status-label ui)]
    (editor/set-text! ed "(ma")
    (place-cursor! (:buf ed) 3)
    (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
    (let [opened? (some? (completion/state c))
          handled (with-redefs [editor/apply-op! (fn [& _] (throw (ex-info "forced commit failure" {})))]
                    (editor/handle-key! ed KEY-RETURN 0))
          text (editor/editor-text ed)
          closed? (nil? (completion/state c))]
      (poll-until 1000 20
                  (fn [] (str/includes? (g/label-text status) "forced commit failure"))
                  (fn [status-ok?]
                    (check :completion-commit-throw-consumes-key
                           (and opened? (= 1 handled) (= "(ma" text) closed? status-ok?)
                           {:opened opened? :handled handled :text text :closed closed?
                            :status (g/label-text status)})
                    (next))))))

(defn- row-plain-return-apply-op-throw-contained
  "Forces jonquil.gtk.editor/apply-op! to throw for the duration of one
  plain Return, through handle-return!'s own call to it -- the same
  with-redefs seam row-completion-commit-throw-consumes-key uses above.
  Calls editor/dispatch-key!, the exact fn on-key calls (never handle-key!
  directly, which has no try/catch of its own): the process survives, the
  key is consumed (1), the buffer keeps its pre-Return text, and the
  status bar shows the error."
  [ui next]
  (let [ed (:editor ui) status (:status-label ui)]
    (editor/set-text! ed "(+ 1 2)")
    (place-cursor! (:buf ed) 3)
    (let [handled (with-redefs [editor/apply-op! (fn [& _] (throw (ex-info "forced return failure" {})))]
                    (editor/dispatch-key! ed KEY-RETURN 0))
          text (editor/editor-text ed)]
      (poll-until 1000 20
                  (fn [] (str/includes? (g/label-text status) "forced return failure"))
                  (fn [status-ok?]
                    (check :plain-return-apply-op-throw-contained
                           (and (= 1 handled) (= "(+ 1 2)" text) status-ok?)
                           {:handled handled :text text :status (g/label-text status)})
                    (next))))))

;; --- P4.T6: completion in the REPL input -----------------------------------

(defn- row-repl-completion-opens
  "\"(ma\" in the console input, Ctrl+Space: the popover opens above the
  input and its rows equal (mapv :symbol (docs/candidates ns \"ma\" \"(ma\"
  3 history)) in order -- history being the exact REPL-history join do-
  repl-complete! computes, captured here at the same moment so this stays
  correct no matter how much earlier rows already left in the session's
  history (measured live: none of it starts with \"ma\", so the rows
  match what row-completion-opens measures for the editor -- first is
  macroexpand -- regardless)."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)
        ns-sym (:prompt-ns @(:state (:session ui)))
        history (str/join "\n" (:items @(:history (:ctx (:session ui)))))]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
    ((:repl-complete!* ui))
    (let [st (completion/state c)
          expected (mapv :symbol (docs/candidates ns-sym "ma" "(ma" 3 history))
          rows (mapv :symbol (:candidates st))
          preview (g/label-text (:doc-label c))]
      (check :repl-completion-opens
             (and (some? st) (popover/open? c) (seq rows) (= expected rows)
                  (str/includes? preview (str "clojure.core/" (first rows))))
             {:expected expected :rows rows :preview preview})
      (completion/close! c)
      (next))))

(defn- row-repl-completion-precedence
  "With history containing \"(inc 1)\", open on \"(ma\", Down, Enter, all
  through cview/handle-key! -- the SAME precedence-ordered dispatch the
  real key controller uses, never the raw actions, so a regression that
  let Down browse history or Enter evaluate would show up here exactly as
  it would for a real keystroke (see the report for the failing-first run
  against a deliberately unordered handle-key!). The reviewer clause:
  Enter must commit the SECOND candidate (apply-input-op!, one undo step),
  never evaluate \"(ma\" (unbalanced, so console/input-action would
  otherwise insert a newline, not evaluate it -- checked anyway, since
  \"nothing was evaluated\" is the brief's own wording) and never browse
  history (the history atom itself must come out exactly as history-add
  left it)."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)
        ctx (:ctx (:session ui))
        mark (out-end-offset ui)]
    (swap! (:history ctx) eval/history-add "(inc 1)")
    (let [hist-before @(:history ctx)]
      (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
      (let [h0 (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)
            st0 (completion/state c)
            second-sym (:symbol (nth (:candidates st0) 1))
            h1 (cview/handle-key! cv KEY-DOWN 0)
            st1 (completion/state c)
            h2 (cview/handle-key! cv KEY-RETURN 0)
            input (cview/input-text cv)
            hist-after @(:history ctx)]
        (check :repl-completion-precedence
               ;; mark = out-end-offset ui unchanged proves nothing was
               ;; evaluated: commit-input! (the ONLY thing that moves
               ;; out-end) only ever runs from a real :eval/:send-stdin,
               ;; never from completion's own commit (since's own "plus
               ;; the live prompt/input tail" caveat means `(since ui
               ;; mark)` is never actually "" -- it always includes that
               ;; tail -- so out-end-offset is the precise check here).
               (and (= 1 h0) (some? st0) (>= (count (:candidates st0)) 2)
                    (= 1 h1) (some? st1) (= 1 (:index st1))
                    (= 1 h2) (nil? (completion/state c))
                    (= (str "(" second-sym) input)
                    (= hist-before hist-after)
                    (= mark (out-end-offset ui)))
               {:h0 h0 :second-sym second-sym :h1 h1 :index1 (:index st1) :h2 h2
                :input input :hist-before hist-before :hist-after hist-after
                :out-end-before mark :out-end-after (out-end-offset ui)
                :console (since ui mark)})
        (next)))))

(defn- row-repl-history-after-close
  "Open on \"(ma\", Escape (via (:close-popovers!* ui), the SAME registry
  path a real Escape always uses, never completion/close! directly), then
  Up: the popup is really closed (state nil, not left stale-open) and Up
  browses history normally again -- the input becomes the last history
  entry, not a completion navigation (see the report for the failing-first
  run against repl-completion left out of the popover registry)."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)
        ctx (:ctx (:session ui))]
    (swap! (:history ctx) eval/history-add "(inc 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
    (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)
    (let [opened? (some? (completion/state c))
          escaped ((:close-popovers!* ui))
          closed? (nil? (completion/state c))
          h (cview/handle-key! cv KEY-UP 0)
          input (cview/input-text cv)]
      (check :repl-history-after-close
             (and opened? escaped closed? (= 1 h) (= "(inc 1)" input))
             {:opened opened? :escaped escaped :closed closed? :h h :input input})
      (next))))

(defn- row-repl-completion-history-symbols
  "Fix round 1 (Important, review finding): the ORIGINAL version of this
  row submitted \"(def zork 1)\" as a real REPL entry, which interns zork
  in `user` -- syntax/get-autocomplete-candidates has its own ns-publics
  source, entirely independent of :extra-context, so that row passed even
  with extra-context wired to a constant nil (measured by the reviewer:
  (docs/candidates 'user \"zo\" \"(zo\" 3 nil) => [\"zork\"]). It proved
  candidates come from the running namespace, not that history feeds
  docs/candidates at all.

  This version puts the symbol ONLY in history text, never evaluated:
  eval/history-add (the exact fn repl-history-context's own :items read,
  and submit-repl!'s own write path) records \"(let [wobbuquux 42]
  wobbuquux)\" straight on the session's history atom, the same shape/API
  repl-history-context reads. wobbuquux is never bound anywhere real, so
  ns-publics can't find it either -- only jonquil.syntax's own extra-
  context tokenizing can (measured: with that string as extra-context,
  docs/candidates for \"wobb\"/\"(wobb\"/5 is exactly [wobbuquux]; with nil
  extra-context it's [] and open! does nothing at all, not even a single-
  candidate commit). \"(wobb\" + Ctrl+Space (through cview/handle-key!,
  the real dispatch) then commits it at once (help/open-state's own
  :single? branch, one candidate, no popover) to \"(wobbuquux\" -- a
  result :extra-context alone can produce.

  Captures the history atom's value before adding the fabricated entry
  and resets it back verbatim afterward (not just popping the one item --
  simpler, and correct regardless of what :index/:draft eval/history-add
  itself touched), so later rows (repl-history-after-close in particular)
  see the same history they would have without this row ever running."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)
        ctx (:ctx (:session ui))
        hist-before @(:history ctx)]
    (swap! (:history ctx) eval/history-add "(let [wobbuquux 42] wobbuquux)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(wobb" -1 1)
    (let [handled (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)
          input (cview/input-text cv)
          st (completion/state c)]
      (reset! (:history ctx) hist-before)
      (check :repl-completion-history-symbols
             (and (= 1 handled) (nil? st) (= "(wobbuquux" input))
             {:handled handled :input input :state st}))
    (next)))

(defn- row-repl-completion-outside-input
  "Caret placed at offset 0 -- always before input-start (input-snapshot's
  own docstring: input-start starts at 2, right after the initial \"> \"
  prompt, and only ever moves further right as output/prompt accumulate)
  -- Ctrl+Space through the SAME cview/handle-key! dispatch: returns 0 and
  opens nothing, input and output both untouched. Restores focus (cview/
  focus!, back to the end of the input) before returning, the same way
  every other row here leaves the console ready for the next one -- a
  caret left outside the input would silently fail every later row's own
  Ctrl+Space too, for the wrong reason."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)
        mark (out-end-offset ui)
        input-before (cview/input-text cv)]
    (place-cursor! (:buf cv) 0)
    (let [handled (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)]
      (check :repl-completion-outside-input
             (and (= 0 handled) (nil? (completion/state c))
                  (= input-before (cview/input-text cv)) (= mark (out-end-offset ui)))
             {:handled handled :state (completion/state c)
              :input-unchanged (= input-before (cview/input-text cv))})
      (cview/focus! cv)
      (next))))

(defn- row-repl-completion-cmd-space-nothing
  "0x20 with META only, caret in the input: control-only? rejects it
  (CONTROL-MASK checked, META-MASK rejected, the same P4 rule the editor's
  own completion chord follows), so the chord falls through unhandled and
  no popover opens."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
    (let [handled (cview/handle-key! cv KEY-SPACE g/META-MASK)]
      (check :repl-completion-cmd-space-nothing
             (and (= 0 handled) (nil? (completion/state c)))
             {:handled handled :state (completion/state c)})
      (next))))

(defn- row-repl-completion-preedit-yields
  "With cv's own preedit-active? flag set (I5, the same flag console-
  view/handle-key!'s Ctrl+Space clause checks), Ctrl+Space returns 0 and
  opens nothing."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
    (reset! (:preedit-active? cv) true)
    (let [handled (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)]
      (reset! (:preedit-active? cv) false)
      (check :repl-completion-preedit-yields
             (and (= 0 handled) (nil? (completion/state c)))
             {:handled handled})
      (next))))

(defn- row-repl-completion-closes-on-focus-loss
  "Context bullet #3 (not in the brief's own row table): calls every fn
  registered on cv's own :on-focus-fns directly -- the SAME ones
  GtkEventControllerFocus's \"leave\" signal calls -- mirroring
  row-completion-closes-on-focus-loss, since this self-test window is
  never the OS's key window and can't move real focus (P4.T3's own
  has_focus finding)."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
    ((:repl-complete!* ui))
    (let [opened? (some? (completion/state c))]
      (doseq [f @(:on-focus-fns cv)] (f))
      (check :repl-completion-closes-on-focus-loss
             (and opened? (nil? (completion/state c)))
             {:opened opened? :still-open (some? (completion/state c))})
      (next))))

(defn- row-repl-completion-keys-reach-console
  "Same reasoning as row-completion-keys-reach-editor, through the
  console's own cview/handle-key! (I3: the REPL popup must not swallow
  keys the input needs either). Open on \"(ma\" in the input: completion/
  handle-key! itself returns 0 for p, Backspace and Left, and cview/
  handle-key! -- the console's real dispatch -- agrees: console-view.clj
  has no clause of its own for any of the three (unlike editor.clj's
  KEY-BACKSPACE clause, which also does nothing here once the caret sits
  at the buffer's end, per backspace-interactive!'s own docstring), so a
  bare call never triggers GTK's own default handling underneath a 0
  return -- p's insert and Backspace's delete are reproduced the same
  insert/delete-interactive way row-completion-keys-reach-editor's own
  stand-ins do, now gated on the checks above. Left has no observable
  buffer effect to check beyond its own 0 return."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)
        ns-sym (:prompt-ns @(:state (:session ui)))
        history (str/join "\n" (:items @(:history (:ctx (:session ui)))))]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
    (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)
    (let [opened? (some? (completion/state c))
          p-swallowed (completion/handle-key! c KEY-P 0)
          p-handled (cview/handle-key! cv KEY-P 0)]
      (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "p" -1 1)
      (let [input-p (cview/input-text cv)
            st-p (completion/state c)
            expected-p (mapv :symbol (docs/candidates ns-sym "map" "(map" 4 history))
            rows-p (mapv :symbol (:candidates st-p))
            bs-swallowed (completion/handle-key! c KEY-BACKSPACE 0)
            bs-handled (cview/handle-key! cv KEY-BACKSPACE 0)]
        (backspace-interactive! (:buf cv) (g/cursor-offset (:buf cv)))
        (let [input-bs (cview/input-text cv)
              st-bs (completion/state c)
              expected-bs (mapv :symbol (docs/candidates ns-sym "ma" "(ma" 3 history))
              rows-bs (mapv :symbol (:candidates st-bs))
              left-swallowed (completion/handle-key! c KEY-LEFT 0)
              left-handled (cview/handle-key! cv KEY-LEFT 0)]
          (check :repl-completion-keys-reach-console
                 (and opened?
                      (= 0 p-swallowed) (= 0 p-handled)
                      (= "(map" input-p) (some? st-p) (= expected-p rows-p)
                      (= 0 bs-swallowed) (= 0 bs-handled)
                      (= "(ma" input-bs) (some? st-bs) (= expected-bs rows-bs)
                      (= 0 left-swallowed) (= 0 left-handled))
                 {:opened opened? :p-swallowed p-swallowed :p-handled p-handled
                  :input-p input-p :expected-p expected-p :rows-p rows-p
                  :bs-swallowed bs-swallowed :bs-handled bs-handled
                  :input-bs input-bs :expected-bs expected-bs :rows-bs rows-bs
                  :left-swallowed left-swallowed :left-handled left-handled})
          (completion/close! c)
          (next))))))

(defn- row-repl-completion-closes-on-space
  "Same reasoning as row-completion-closes-on-space, through cview/
  handle-key! (I3): completion/handle-key! and cview/handle-key! both
  return 0 for a plain space (control-only? in console-view.clj's own
  Ctrl+Space clause rejects mask 0). Only then insert a space with
  insert_interactive_at_cursor: help/prefix-at is nil right after it, so
  refine! closes the popover."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
    (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)
    (let [opened? (some? (completion/state c))
          swallowed (completion/handle-key! c KEY-SPACE 0)
          handled (cview/handle-key! cv KEY-SPACE 0)]
      (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) " " -1 1)
      (check :repl-completion-closes-on-space
             (and opened? (= 0 swallowed) (= 0 handled) (nil? (completion/state c)))
             {:opened opened? :swallowed swallowed :handled handled
              :still-open (some? (completion/state c))})
      (next))))

;; --- P4.T7: rename and the P4 context menus --------------------------------

(def ^:private rename-fixture
  "\"(defn greet [n] n)\\n(greet 1) ; greet\\n\\\"greet\\\"\\n:greet greeting\"
  -- two CODE occurrences of greet (the defn and the call), one inside a
  comment, one inside a string, plus a keyword (:greet) and a longer symbol
  (greeting) that share the substring but are never themselves greet (I4:
  a rename that did a naive substring replace, or one that also touched the
  keyword, would still pass the OLD fixture, which had neither). Shared by
  every rename row below, each of which re-applies it fresh (run-row! only
  clears text/input, not this)."
  "(defn greet [n] n)\n(greet 1) ; greet\n\"greet\"\n:greet greeting")

(def ^:private rename-fixture-renamed
  "rename-fixture with edit/rename's own two code occurrences of greet
  applied as hello (measured: (edit/rename fixture caret) => {:edits [[6 11
  hello] [20 25 hello]] ...}) -- the comment, the string, the keyword and
  greeting are untouched. Shared by row-rename-applies/-one-undo so the
  expected renamed text is defined once."
  "(defn hello [n] n)\n(hello 1) ; greet\n\"greet\"\n:greet greeting")

(defn- setup-rename! [ed]
  (editor/set-text! ed rename-fixture)
  (place-cursor! (:buf ed) (inc (str/index-of rename-fixture "(greet 1)"))))

(defn- row-rename-applies
  "Caret inside the call's own greet, :rename-to!* \"hello\": edit/
  rename's own code-occurrence rule (never a comment, string, regex or
  keyword) renames exactly the defn and the call, one apply-op! (one undo
  step, checked separately by row-rename-one-undo), status the brief's
  own wording with the measured plural (2 occurrences, unchanged by the
  fixture's own :greet/greeting additions -- neither is a code occurrence
  of greet)."
  [ui next]
  (let [ed (:editor ui) status (:status-label ui)]
    (setup-rename! ed)
    (let [result ((:rename-to!* ui) "hello")]
      (poll-until 1000 20
                  (fn [] (= "Renamed 2 occurrences of 'greet' to 'hello'." (g/label-text status)))
                  (fn [status-ok?]
                    (check :rename-applies
                           (and (nil? result)
                                (= rename-fixture-renamed (editor/editor-text ed))
                                status-ok?)
                           {:result result :text (editor/editor-text ed)
                            :status (g/label-text status)})
                    (next))))))

(defn- row-rename-skips-comments-strings
  "Same fixture and rename as row-rename-applies, focused on the reviewer
  clause's own concern (I4): the WHOLE resulting text matches rename-
  fixture-renamed byte for byte, not just \"still contains ; greet and
  \\\"greet\\\"\" (true even when rename does nothing at all) -- the comment,
  the string, the keyword :greet and the symbol greeting all read back
  unchanged, and only the defn/call are hello now."
  [ui next]
  (let [ed (:editor ui)]
    (setup-rename! ed)
    ((:rename-to!* ui) "hello")
    (let [text (editor/editor-text ed)]
      (check :rename-skips-comments-strings (= rename-fixture-renamed text) {:text text})
      (next))))

(defn- row-rename-one-undo
  "The SAME rename as row-rename-applies, checked BEFORE undoing (I4: the
  old version of this row undid first and compared against the fixture,
  which also holds when rename applied nothing at all -- an untouched
  buffer undoes to itself), then ONE gtk_text_buffer_undo: apply-op! wraps
  every edit in one begin/end_user_action (its own contract), so one undo
  must restore the fixture verbatim."
  [ui next]
  (let [ed (:editor ui)]
    (setup-rename! ed)
    ((:rename-to!* ui) "hello")
    (let [renamed-text (editor/editor-text ed)]
      (g/gtk-text-buffer-undo (:buf ed))
      (let [text (editor/editor-text ed)]
        (check :rename-one-undo
               (and (= rename-fixture-renamed renamed-text) (= rename-fixture text))
               {:renamed-text renamed-text :text text})
        (next)))))

(defn- row-rename-invalid
  "Same fixture, :rename-to!* \"a b\" (not a valid Clojure symbol,
  edit/rename's own rejection -- test/jonquil/help_test.clj's own
  rename-problem-test measures this exact sentence): returns the problem
  sentence, applies nothing."
  [ui next]
  (let [ed (:editor ui)]
    (setup-rename! ed)
    (let [result ((:rename-to!* ui) "a b")]
      (check :rename-invalid
             (and (= "Can't rename 'greet' to 'a b'." result) (= rename-fixture (editor/editor-text ed)))
             {:result result :text (editor/editor-text ed)})
      (next))))

(defn- row-rename-keys
  "\"(map inc [1])\", caret 2 (row-quick-doc-core's own fixture/caret --
  \"map\" is the target either way): Shift+F6 opens the rename popover
  prefilled with \"map\", selected; closing it (popover/hide!, never
  Escape -- rename is autohide ON/modal, so this window can't post a real
  Escape into its grab) and F2 opens it again, same prefill."
  [ui next]
  (let [ed (:editor ui) rp (:rename-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (place-cursor! (:buf ed) 2)
    (let [h1 (editor/handle-key! ed KEY-F6 g/SHIFT-MASK)
          open1? (popover/open? rp)
          text1 (help-popover/rename-entry-text rp)]
      (popover/hide! rp)
      (let [h2 (editor/handle-key! ed KEY-F2 0)
            open2? (popover/open? rp)
            text2 (help-popover/rename-entry-text rp)]
        (check :rename-keys
               (and (= 1 h1) open1? (= "map" text1) (= 1 h2) open2? (= "map" text2))
               {:h1 h1 :open1 open1? :text1 text1 :h2 h2 :open2 open2? :text2 text2})
        (popover/hide! rp)
        (next)))))

(defn- row-rename-cmd-does-nothing
  "0xffbf (F2) with META only: no-modifiers? rejects it (the P4 rule),
  same reasoning as every other P4 chord's own cmd-does-nothing row, so
  the chord falls through unhandled and no popover opens."
  [ui next]
  (let [ed (:editor ui) rp (:rename-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-F2 g/META-MASK)]
      (check :rename-cmd-does-nothing
             (and (= 0 handled) (not (popover/open? rp)))
             {:handled handled :open (popover/open? rp)})
      (next))))

(defn- row-rename-cmd-shift-f6-does-nothing
  "0xffc3 (F6) with SHIFT and META together: shift-only? rejects it (fix
  round 1 -- it used to check shift-held? alone, so this chord opened
  rename same as plain Shift+F6, measured live before the fix), so the
  chord falls through unhandled and no popover opens."
  [ui next]
  (let [ed (:editor ui) rp (:rename-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-F6 (bit-or g/SHIFT-MASK g/META-MASK))]
      (check :rename-cmd-shift-f6-does-nothing
             (and (= 0 handled) (not (popover/open? rp)))
             {:handled handled :open (popover/open? rp)})
      (next))))

(defn- row-rename-closed-signal-syncs
  "Context bullet #1: gtk_popover_popdown called DIRECTLY (never
  jonquil.gtk.popover/hide!) still leaves :open? false -- jonquil.gtk.
  popover's own \"closed\" signal handler (make-popover's autohide
  branch) is what does that, not this row's own hide! (never called
  here). Polls rather than reading once, since a GTK close animation
  could defer the signal past this call returning."
  [ui next]
  (let [ed (:editor ui) rp (:rename-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (place-cursor! (:buf ed) 2)
    (editor/handle-key! ed KEY-F6 g/SHIFT-MASK)
    (let [opened? (popover/open? rp)]
      (g/gtk-popover-popdown (:popover rp))
      (poll-until 1000 20
                  (fn [] (not (popover/open? rp)))
                  (fn [closed?]
                    (check :rename-closed-signal-syncs
                           (and opened? closed?)
                           {:opened opened? :closed closed?})
                    (next))))))

(defn- row-menu-actions
  "The brief's own table row: :activate-menu-action!* for jump, quick-
  doc, toggle-comment, each against the fixture/caret its own existing
  row (row-jump-top-level/row-quick-doc-core/dev/gtk_selftest.clj's
  row-edit-toggle-comment) already established, but fired through the
  editor's own P4 GSimpleActionGroup (g_action_group_activate_action, per
  install-context-menu!'s own docstring) instead of the key."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui) activate! (:activate-menu-action!* ui)]
    (editor/set-text! ed "(defn greet [n] n)\n(greet 1)")
    (place-cursor! (:buf ed) 22)
    (activate! "jump")
    (let [jump-caret (g/cursor-offset (:buf ed))]
      (editor/set-text! ed "(map inc [1])")
      (place-cursor! (:buf ed) 2)
      (activate! "quick-doc")
      (let [quick-doc-open? (popover/open? hp)
            quick-doc-text (help-popover/quick-doc-text hp)]
        (popover/hide! hp)
        (editor/set-text! ed "(+ 1 2)")
        (place-cursor! (:buf ed) 0)
        (activate! "toggle-comment")
        (let [commented-text (editor/editor-text ed)]
          (check :menu-actions
                 (and (= 6 jump-caret)
                      quick-doc-open? (str/includes? quick-doc-text "clojure.core/map")
                      (= "; (+ 1 2)" commented-text))
                 {:jump-caret jump-caret :quick-doc-open quick-doc-open?
                  :quick-doc-text quick-doc-text :commented-text commented-text})
          (next))))))

(defn- row-menu-actions-console-complete
  "Extra row (not in the brief's own table): activates the REPL's own
  p4.complete (:repl-activate-menu-action!*, the console's own P4 action
  group) and checks it has the SAME effect Ctrl+Space does -- the
  completion popover opens over \"(ma\"'s own candidates."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
    ((:repl-activate-menu-action!* ui) "complete")
    (let [opened? (some? (completion/state c))]
      (check :menu-actions-console-complete opened? {:opened opened?})
      (completion/close! c)
      (next))))

(defn- row-menu-action-throw-is-contained
  "Fix round 1 (Important): forces jonquil.gtk.editor/do-toggle-comment!
  to throw for one activation (with-redefs, the seam row-completion-
  commit-throw-consumes-key already proved reaches through a closure the
  same shape), then activates p4.toggle-comment through :activate-menu-
  action!*. Without install-context-menu!'s own try/catch, the SAME
  throw crosses a GSimpleAction \"activate\" callback -- a Jolt foreign-
  callable -- and kills this whole self-test process (measured live with
  the fix reverted: exit 1, no SUMMARY). With the fix: the process
  survives, the status bar shows the error, and the buffer is untouched
  (do-toggle-comment! throws before any edit is ever applied)."
  [ui next]
  (let [ed (:editor ui) status (:status-label ui) activate! (:activate-menu-action!* ui)]
    (editor/set-text! ed "(+ 1 2)")
    (place-cursor! (:buf ed) 0)
    (with-redefs [editor/do-toggle-comment! (fn [& _] (throw (ex-info "forced menu-action failure" {})))]
      (activate! "toggle-comment"))
    (let [text (editor/editor-text ed)]
      (poll-until 1000 20
                  (fn [] (str/includes? (g/label-text status) "forced menu-action failure"))
                  (fn [status-ok?]
                    (check :menu-action-throw-is-contained
                           (and status-ok? (= "(+ 1 2)" text))
                           {:status (g/label-text status) :text text})
                    (next))))))

(defn- row-cursor-listener-throw-others-still-run
  "Registers two on-cursor-moved! listeners: the first throws once, the
  second flips a flag. Moving the caret proves editor.clj's own
  notify::cursor-position callback runs each listener in its own try --
  the second listener's flag still flips even though the first one threw
  right before it. Each listener stays registered for the rest of the
  run; the first one only ever throws this once (guarded by its own
  atom), so later rows are unaffected."
  [ui next]
  (let [ed (:editor ui)
        threw-once? (atom false)
        ran-second? (atom false)]
    (editor/set-text! ed "(+ 1 2)")
    (editor/on-cursor-moved!
     ed (fn [_lc]
          (when-not @threw-once?
            (reset! threw-once? true)
            (throw (ex-info "forced cursor-listener failure" {})))))
    (editor/on-cursor-moved! ed (fn [_lc] (reset! ran-second? true)))
    (place-cursor! (:buf ed) 3)
    (check :cursor-listener-throw-others-still-run
           (and @threw-once? @ran-second?)
           {:threw-once @threw-once? :ran-second @ran-second?})
    (next)))

(defn- row-command-chord-close
  "P2.T10 fix round 1, Important #1: closes WHILE a program is running,
  not idle -- the coordinator's own concern was exactly this combination
  (the eval worker's on-gui!/cview/append! calls racing gtk_window_close's
  widget teardown), so this row now starts (loop [] (recur)), waits for it
  to actually be evaluating, and only then sends the chord. A command
  chord (Control here) plus W closes the window, which already quits the
  app -- gtk_window_close fires the SAME close-request path a real
  close-button click or OS window-close would. Calls the exact fn the
  key-pressed signal calls (window.clj's :handle-window-key!), never a
  synthesized OS keystroke, same pattern as row-escape-yields-when-idle
  and row-command-chord-capslock above.

  This is deliberately the LAST row: gtk_window_close synchronously fires
  close-request, which calls g_application_quit, so the running
  g_application main loop stops once it returns to it and nothing after
  this row runs -- the CI-facing evidence that closing mid-run didn't hang
  or crash is this whole process exiting cleanly afterward, which
  run-rows!'s own destroy-signal fallback (below) now guarantees happens
  either way, pass or fail.

  P4.T4 fix round 2: also opens quick doc (Ctrl+Q on \"loop\") before
  starting the run, so THIS row's own real close -- the only one in this
  file that reaches gtk_window_close's actual teardown -- now covers the
  reviewer's own combination too: a program running AND the quick-doc
  popover still open when the close chord lands (an ordinary Ctrl+Q then
  Cmd+Q sequence for a user). :popover-was-open is read right at the
  close chord itself, alongside started?/handled -- neither of those two
  pre-existing assertions is weakened; this only adds a third. Nothing
  here can catch the GTK-CRITICAL a regression would print on its own
  (it's a log line, not a thrown exception) -- that's still the gates'
  own stderr grep -- but this makes sure the exact call sequence actually
  happens inside a real bb gtk:selftest run, not only the report's
  standalone probe."
  [ui next]
  (editor/set-text! (:editor ui) "(loop [] (recur))")
  (place-cursor! (:buf (:editor ui)) 2)
  ((:quick-doc!* ui))
  ((:run!* ui))
  (poll-until 2000 20
              (fn [] (eval/evaluating? (:ctx (:session ui))))
              (fn [started?]
                (let [popover-was-open? (popover/open? (:help-popover ui))
                      handled ((:handle-window-key! ui) nil KEY-W 0 g/CONTROL-MASK nil)]
                  (check :command-chord-close
                         (and started? (= 1 handled) popover-was-open?)
                         {:started started? :handled handled :popover-was-open popover-was-open?})
                  (next)))))

(def ^:private row-fns
  [row-run-output
   row-run-while-running
   row-stdin-roundtrip
   row-stop-while-reading
   row-run-unbalanced
   row-reset-vs-accumulate
   row-repl-after-run
   row-fast-repl-twice
   row-busy-hint-once
   row-console-history-down-empty
   row-escape-yields-when-idle
   row-command-chord-capslock
   row-repl-eof-no-editor-hint
   row-open-refuses-dirty
   row-open-invalid-utf8
   row-save-atomic
   row-save-follows-symlink
   row-save-executable-keeps-mode
   row-save-failure-leaves-original-intact
   row-dialog-dismissed-silent
   row-save-and-open
   row-run-selection-line
   row-run-selection-sel
   row-run-selection-blank
   row-toggle-focus
   row-clear-console
   row-zoom-in-out
   row-find-highlights
   row-find-next-wraps
   row-find-prev
   row-find-no-match
   row-replace-one
   row-replace-all
   row-find-escape
   row-find-hangul
   row-find-survives-repaint
   row-find-preedit-yields
   row-find-prefill-selects
   row-line-col
   row-close-clean
   row-close-dirty-cancel
   row-close-dirty-discard
   row-close-dirty-save
   row-pre-run-hidden-by-error
   row-pre-run-stray-closer
   row-close-alert-pending-guard
   row-close-alert-routing
   row-run-while-running-keeps-warning
   row-window-escape-closes-bar-while-running
   row-window-escape-yields-preedit-while-running
   row-window-escape-stops-when-editor-focused
   row-find-typing-keeps-caret
   row-find-return-in-replacement
   row-window-find-keys-editor-focused
   row-line-col-typing
   row-close-dismissed
   row-close-save-failed
   row-close-alert-build-throws
   row-quit-action-prompts
   row-theme-retints-tags
   row-theme-css
   row-theme-prefer-dark
   row-theme-keeps-text-and-undo
   row-theme-keeps-find-highlight
   row-theme-and-zoom-share-css
   row-theme-applies-to-new-text
   row-theme-cycle
   row-theme-chord
   row-theme-chord-needs-shift
   row-settings-isolated
   row-settings-unreadable-warns
   row-theme-save-failure-reported
   row-theme-switch-while-running
   row-popover-at-caret
   row-popover-keys-stay-with-editor
   row-window-shift-f6-reaches-editor
   row-window-f6-still-toggles
   row-window-escape-closes-popover
   row-popover-dark-colours
   row-quick-doc-core
   row-quick-doc-run-defined
   row-quick-doc-after-reset
   row-quick-doc-closes
   row-quick-doc-closes-on-focus-loss
   row-quick-doc-cmd-does-nothing
   row-quick-doc-cmd-shift-f1-does-nothing
   row-quick-doc-after-define
   row-jump-top-level
   row-jump-local-binding
   row-jump-external
   row-jump-none
   row-jump-shadowed-param
   row-completion-opens
   row-completion-single-inserts
   row-completion-no-prefix
   row-completion-navigate-commit
   row-completion-tab-commits
   row-completion-keys-reach-editor
   row-completion-closes-on-space
   row-completion-escape-while-running
   row-completion-cmd-space-nothing
   row-completion-preedit-yields
   row-completion-closes-on-focus-loss
   row-completion-closes-other-popover
   row-completion-commit-throw-consumes-key
   row-plain-return-apply-op-throw-contained
   row-repl-completion-opens
   row-repl-completion-precedence
   row-repl-history-after-close
   row-repl-completion-history-symbols
   row-repl-completion-outside-input
   row-repl-completion-cmd-space-nothing
   row-repl-completion-preedit-yields
   row-repl-completion-closes-on-focus-loss
   row-repl-completion-keys-reach-console
   row-repl-completion-closes-on-space
   row-rename-applies
   row-rename-skips-comments-strings
   row-rename-one-undo
   row-rename-invalid
   row-rename-keys
   row-rename-cmd-does-nothing
   row-rename-cmd-shift-f6-does-nothing
   row-rename-closed-signal-syncs
   row-menu-actions
   row-menu-actions-console-complete
   row-menu-action-throw-is-contained
   row-cursor-listener-throw-others-still-run
   row-command-chord-close])

;; --- driver --------------------------------------------------------------

(defn- run-row! [ui row-fn next]
  (editor/set-text! (:editor ui) "")
  (cview/clear-input! (:console ui))
  (poll-until 2000 20 (fn [] (idle? ui))
              (fn [_idle?] (row-fn ui next))))

;; P2.T10 fix round 1, Important #2: removing run-rows!'s old unconditional
;; close made row-command-chord-close the only way out, so a REGRESSION in
;; the close chord (on-window-key stops matching KEY-W, close-fn stops
;; being wired, etc.) would leave the g_application main loop running
;; forever with nothing to stop it -- hanging `bb gtk:selftest` instead of
;; failing it. window-destroyed? is set by a "destroy" signal this file
;; connects on the window itself (-main, below), independent of window.clj's
;; own close-request->quit wiring: a plain Clojure atom flip, safe to poll
;; from run-rows! without ever touching the (possibly already-destroyed)
;; widget pointer directly.
(defonce ^:private window-destroyed? (atom false))

;; P3 fix wave I3: set only where SUMMARY prints, i.e. when run-rows! reached
;; the end of row-fns. An early g_application_quit (a row that really closes
;; or quits) ends g_application_run with the remaining rows never run and no
;; SUMMARY, and every result recorded so far can still be a PASS -- -main
;; used to exit 0 then, and `bb gtk:selftest` reads only exit codes.
(defonce ^:private finished? (atom false))

(def ^:private WINDOW-CLOSE-FALLBACK-DEADLINE-MS
  ;; Generous: a working chord closes within one main-loop iteration
  ;; (microseconds), so this only ever actually waits out its deadline
  ;; when the chord is broken.
  2000)

(defn- run-rows! [ui fns]
  (if (empty? fns)
    ;; row-command-chord-close (the last row) already tried the product's
    ;; own close chord. Poll window-destroyed? rather than assuming it
    ;; worked: if the chord regressed (on-window-key stops matching,
    ;; close-fn stops being wired, or gtk_window_close/close-request stops
    ;; actually quitting the app), the window is still open here, and
    ;; without this fallback nothing would ever end the run. Forcing
    ;; gtk_window_close directly guarantees this process still exits
    ;; (non-zero, via the FAIL below) instead of hanging -- the chord row
    ;; itself stays a real check; this is only a backstop for when it
    ;; fails.
    (poll-until WINDOW-CLOSE-FALLBACK-DEADLINE-MS 50
                (fn [] @window-destroyed?)
                (fn [closed?]
                  (when-not closed?
                    (check :command-chord-close-forced false
                           {:reason "window still open after the close chord; forced closed to avoid hanging the run"})
                    (g/gtk-window-close (:window ui)))
                  (let [passed (count (filter second @results)) total (count @results)]
                    (reset! finished? true)
                    (println (str "SUMMARY " passed " of " total " passed")))))
    (run-row! ui (first fns) (fn [] (run-rows! ui (rest fns))))))

(defn -main [& _]
  (main/start!
   {:settings-path (fresh-settings-path)
    :on-ready (fn [ui]
                (g/g-signal-connect-data (:window ui) "destroy"
                                         (cb/callable (fn [_win _data] (reset! window-destroyed? true) nil)
                                                      [:pointer :pointer] :void)
                                         ffi/null ffi/null g/CONNECT-DEFAULT)
                (later 300 (fn [] (run-rows! ui row-fns))))})
  (when-not @finished?
    (binding [*out* *err*]
      (println "[gtk-selftest-window] run ended before the last row; no SUMMARY, exiting 1")))
  (System/exit (if (and @finished? (seq @results) (every? second @results)) 0 1)))
