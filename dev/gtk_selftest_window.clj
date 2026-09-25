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
            [jonquil.eval :as eval]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.main :as main]
            [jonquil.session :as session]))

(defonce results (atom []))

(defn- check [row ok detail]
  (swap! results conj [row (boolean ok) detail])
  (binding [*out* *err*]
    (println "[gtk-selftest-window]" (name row) (if ok "PASS" "FAIL") (pr-str detail))))

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
      ((:close-choice! ui) {:index 2})
      (let [dirty-after (editor/dirty? ed)
            text-after (editor/editor-text ed)]
        (reset! (:alert-pending? ui) false)
        (check :close-dirty-cancel
               (and dirty-before (= text-before text-after) dirty-after)
               {:dirty-before dirty-before :dirty-after dirty-after
                :text-before text-before :text-after text-after})
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

;; gtk-keys.md's shared table -- window.clj's own copy is private.
(def ^:private KEY-W 0x77)

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
  either way, pass or fail."
  [ui next]
  (editor/set-text! (:editor ui) "(loop [] (recur))")
  ((:run!* ui))
  (poll-until 2000 20
              (fn [] (eval/evaluating? (:ctx (:session ui))))
              (fn [started?]
                (let [handled ((:handle-window-key! ui) nil KEY-W 0 g/CONTROL-MASK nil)]
                  (check :command-chord-close (and started? (= 1 handled))
                         {:started started? :handled handled})
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
                    (println (str "SUMMARY " passed " of " total " passed")))))
    (run-row! ui (first fns) (fn [] (run-rows! ui (rest fns))))))

(defn -main [& _]
  (main/start!
   {:on-ready (fn [ui]
                (g/g-signal-connect-data (:window ui) "destroy"
                                         (cb/callable (fn [_win _data] (reset! window-destroyed? true) nil)
                                                      [:pointer :pointer] :void)
                                         ffi/null ffi/null g/CONNECT-DEFAULT)
                (later 300 (fn [] (run-rows! ui row-fns))))})
  (System/exit (if (and (seq @results) (every? second @results)) 0 1)))
