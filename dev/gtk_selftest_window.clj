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
   row-save-and-open])

;; --- driver --------------------------------------------------------------

(defn- run-row! [ui row-fn next]
  (editor/set-text! (:editor ui) "")
  (cview/clear-input! (:console ui))
  (poll-until 2000 20 (fn [] (idle? ui))
              (fn [_idle?] (row-fn ui next))))

(defn- run-rows! [ui fns]
  (if (empty? fns)
    (let [passed (count (filter second @results)) total (count @results)]
      (println (str "SUMMARY " passed " of " total " passed"))
      ;; gtk_window_close triggers the SAME close-request path a real
      ;; user's close-button click would -- ending the run this way is
      ;; also a live check that closing the window quits the app.
      (later 50 (fn [] (g/gtk-window-close (:window ui)))))
    (run-row! ui (first fns) (fn [] (run-rows! ui (rest fns))))))

(defn -main [& _]
  (main/start! {:on-ready (fn [ui] (later 300 (fn [] (run-rows! ui row-fns))))})
  (System/exit (if (and (seq @results) (every? second @results)) 0 1)))
