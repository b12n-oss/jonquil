(ns gtk-selftest-window.run-repl
  "Run/Stop, the REPL and stdin, run-selection, and console clearing.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch). Each GTK step
  runs through on-gtk, each wait parks in await-until, and each row records
  exactly one check."
  (:require [clojure.core.async :refer [<! timeout]]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.eval :as eval]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.session :as session]))

(defn row-run-output [ui]
  (go-row :run-output support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(println :hi) (+ 1 2)")
                         ((:run!* ui))
                         m))
          ok (await-until 5000 20 #(let [t (support/since ui mark)]
                                     (and (str/includes? t ":hi") (str/includes? t "=> 3"))))
          ;; Poll the status label too, rather than a single read, for the
          ;; same reason: consistency with every other condition here being
          ;; a poll-with-deadline, never a one-shot check.
          ready? (await-until 2000 20 #(= "Ready" (g/label-text (:status-label ui))))]
      (on-gtk (support/check :run-output (and ok ready?)
                             {:console (support/since ui mark) :status (g/label-text (:status-label ui))})))))

(defn row-run-while-running [ui]
  (go-row :run-while-running support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(loop [] (recur))")
                         ((:run!* ui))
                         m))
          started? (await-until 2000 20 #(eval/evaluating? (:ctx (:session ui))))
          _ (on-gtk ((:run!* ui)))
          warned? (await-until 2000 20 #(str/includes? (support/since ui mark) "already in progress"))
          _ (on-gtk ((:stop!* ui)))
          stopped? (await-until 3000 20 #(str/includes? (support/since ui mark) "; interrupted"))]
      ;; M1: the refused second Run must not print a second banner --
      ;; exactly one for the whole row.
      (on-gtk (let [banner-count (count (re-seq #"; --- Running Definitions ---"
                                                (support/since ui mark)))]
                (support/check :run-while-running
                               (and started? warned? stopped? (= 1 banner-count))
                               {:started started? :warned warned? :stopped stopped?
                                :banner-count banner-count
                                :console (support/since ui mark)}))))))

(defn row-stdin-roundtrip [ui]
  (go-row :stdin-roundtrip support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(let [n (read-line)] (str \"Hello, \" n))")
                         ((:run!* ui))
                         m))
          waiting? (await-until 3000 20 #((:waiting? (:console ui))))
          _ (on-gtk (let [cv (:console ui)]
                      (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "World" -1 1)
                      (cview/handle-return! cv false)))
          ok (await-until 3000 20 #(str/includes? (support/since ui mark) "=> \"Hello, World\""))]
      (on-gtk (support/check :stdin-roundtrip (and waiting? ok)
                             {:waiting waiting? :console (support/since ui mark)})))))

(defn row-stop-while-reading [ui]
  (go-row :stop-while-reading support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(let [n (read-line)] (str \"Hello, \" n))")
                         ((:run!* ui))
                         m))
          waiting-before (await-until 3000 20 #((:waiting? (:console ui))))
          _ (on-gtk ((:stop!* ui)))
          interrupted? (await-until 3000 20 #(str/includes? (support/since ui mark) "; interrupted"))
          ;; waiting? is a plain worker-thread atom reset (no on-gui!
          ;; scheduling), so it should already be false once "; interrupted"
          ;; -- itself only visible after an on-gui! drain -- has landed.
          ;; Poll it too rather than reading once, so a real lag shows up as
          ;; a slow pass instead of a silent-looking false failure.
          t0 (System/currentTimeMillis)
          waiting-cleared? (await-until 2000 20 #(not ((:waiting? (:console ui)))))]
      (on-gtk (let [cv (:console ui)
                    waiting-after ((:waiting? cv))
                    ms (- (System/currentTimeMillis) t0)
                    input-before (cview/input-text cv)
                    inserted (not (zero? (g/gtk-text-buffer-insert-interactive-at-cursor
                                          (:buf cv) "z" -1 1)))
                    input-after (cview/input-text cv)]
                (support/check :stop-while-reading
                               (and waiting-before interrupted? waiting-cleared?
                                    (= "" input-before) inserted (= "z" input-after))
                               {:waiting-before waiting-before :interrupted interrupted?
                                :waiting-cleared waiting-cleared? :waiting-after waiting-after
                                :ms-to-clear ms :input-before input-before
                                :inserted inserted :input-after input-after}))))))

(defn row-reset-vs-accumulate
  "Every step's own assertion is scoped (via run-and-await's mark) to the
  output THAT step produced. Without this, run-row's reset (editor + input
  only, never the console's output history) would let the first (greet)'s
  own leftover error line -- or even a PRIOR row's -- satisfy first-error?
  regardless of whether Reset actually removed anything; see the fix-round-1
  can-fail proof in the P2.T7 report. Each run-and-await also gates on its
  OWN expected text (I1), not just engine-idle, so mark1/mark3 are captured
  only once the previous step's result is actually visible."
  [ui]
  (go-row :reset-vs-accumulate support/check
    (let [[defined1? _] (support/run-and-await ui "(defn greet [] :hi)" 3000
                                               #(str/includes? % "=> #'user/greet"))
          [first-error? mark1] (support/run-and-await ui "(greet)" 3000
                                                      #(str/includes? % "Error ["))
          _ (on-gtk (session/set-mode! (:session ui) :accumulate))
          [defined2? _] (support/run-and-await ui "(defn greet [] :hi)" 3000
                                               #(str/includes? % "=> #'user/greet"))
          [second-ok? mark3] (support/run-and-await ui "(greet)" 3000
                                                    #(str/includes? % "=> :hi"))]
      (on-gtk (support/check :reset-vs-accumulate
                             (and defined1? first-error? defined2? second-ok?)
                             {:defined1 defined1? :first-error first-error?
                              :defined2 defined2? :second-ok second-ok?
                              :first-console (support/since ui mark1)
                              :second-console (support/since ui mark3)})))))

(defn row-repl-after-run [ui]
  (go-row :repl-after-run support/check
    (let [[defined? _] (support/run-and-await ui "(def x 41)" 3000 #(str/includes? % "=> #'user/x"))
          mark (on-gtk (let [cv (:console ui)
                             m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(inc x)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          ;; I1 again: gate on the text itself, not idle? alone.
          ok (await-until 3000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "=> 42")))]
      (on-gtk (support/check :repl-after-run (and defined? ok)
                             {:defined defined? :console (support/since ui mark)})))))

(defn row-fast-repl-twice
  "I2: a fast REPL eval (nil, completing in microseconds) must not strand
  the console in :busy. With the old console-mirrored evaluating? atom, a
  fast on-complete could clear the mirror BEFORE do-repl-eval!'s own
  `set-evaluating! true` ran, leaving it stuck true and every later Return
  silently hitting :busy. Submits nil twice in a row and requires both
  results to actually appear."
  [ui]
  (go-row :fast-repl-twice support/check
    (let [cv (:console ui)
          [mark1 got1] (on-gtk (let [m (support/out-end-offset ui)]
                                 (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "nil" -1 1)
                                 [m (cview/handle-return! cv false)]))
          ok1 (await-until 2000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark1) "=> nil")))
          [mark2 got2] (on-gtk (let [m (support/out-end-offset ui)]
                                 (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "nil" -1 1)
                                 [m (cview/handle-return! cv false)]))
          ok2 (await-until 2000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark2) "=> nil")))]
      (on-gtk (support/check :fast-repl-twice
                             (and (= 1 got1) ok1 (= 1 got2) ok2)
                             {:got1 got1 :ok1 ok1 :got2 got2 :ok2 ok2
                              :console1 (support/since ui mark1) :console2 (support/since ui mark2)})))))

(defn row-busy-hint-once
  "M3: Return while a program runs and isn't waiting on stdin does nothing
  silently by default; the FIRST such Return during a run appends a hint,
  later ones during the SAME run stay silent."
  [ui]
  (go-row :busy-hint-once support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(loop [] (recur))")
                         ((:run!* ui))
                         m))
          started? (await-until 2000 20 #(eval/evaluating? (:ctx (:session ui))))
          [got1 got2] (on-gtk (let [cv (:console ui)
                                    got1 (cview/handle-return! cv false)
                                    got2 (cview/handle-return! cv false)]
                                [got1 got2]))
          hinted? (await-until 1000 20 #(str/includes? (support/since ui mark) "A program is running"))
          hint-count (on-gtk (let [n (count (re-seq #"A program is running" (support/since ui mark)))]
                               ((:stop!* ui))
                               n))
          stopped? (await-until 3000 20 #(str/includes? (support/since ui mark) "; interrupted"))]
      (on-gtk (support/check :busy-hint-once
                             (and started? (= 1 got1) (= 1 got2)
                                  hinted? (= 1 hint-count) stopped?)
                             {:started started? :got1 got1 :got2 got2
                              :hinted hinted? :hint-count hint-count :stopped stopped?
                              :console (support/since ui mark)})))))

(defn row-typed-ahead-not-evaluated
  "Text typed for a program (here, while it waits on read-line) is still on
  the input line when the program is stopped. The first Return prints a hint
  and evaluates nothing, the text stays, and a second Return evaluates it.
  Nothing else runs between the two Returns."
  [ui]
  (go-row :typed-ahead-not-evaluated support/check
    (let [typed "(def leftover-ran 1)"
          probe "(str \"V=\" (some-> (resolve 'leftover-ran) deref))"
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(read-line)")
                         ((:run!* ui))
                         m))
          waiting? (await-until 3000 20 #((:waiting? (:console ui))))
          _ (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf (:console ui)) typed -1 1))
          _ (on-gtk ((:stop!* ui)))
          stopped? (await-until 3000 20 #(str/includes? (support/since ui mark) "; interrupted"))
          mark2 (on-gtk (let [m (support/out-end-offset ui)]
                          (cview/handle-return! (:console ui) false)
                          m))
          hinted? (await-until 1000 20 #(str/includes? (support/since ui mark2) "Press Return again"))
          input-kept (on-gtk (cview/input-text (:console ui)))
          ;; Nothing may evaluate between the two Returns: a probe run would
          ;; clear :leftover by itself and hide a handle-return! that forgot to.
          undefined? (not (str/includes? (support/since ui mark2) "#'user/leftover-ran"))
          mark4 (on-gtk (let [m (support/out-end-offset ui)]
                          (cview/handle-return! (:console ui) false)
                          m))
          evaluated? (await-until 3000 20 #(str/includes? (support/since ui mark4) "#'user/leftover-ran"))
          [probe2? mark5] (support/run-and-await ui probe 3000 #(str/includes? % "V="))
          defined? (str/includes? (support/since ui mark5) "\"V=1\"")]
      (on-gtk (support/check :typed-ahead-not-evaluated
                             (and waiting? stopped? hinted? (= typed input-kept)
                                  undefined? evaluated? probe2? defined?)
                             {:waiting waiting? :stopped stopped? :hinted hinted?
                              :input-kept input-kept :undefined undefined?
                              :evaluated evaluated? :probe2 probe2? :defined defined?
                              :console (support/since ui mark)})))))

(defn row-console-history-down-empty
  "I3: eval/history-next returns \"\" for both an empty history and a
  not-currently-browsing index; the window.clj adapter must tell those
  apart from a real replacement, or Down erases whatever the user typed.
  Forces a fresh, empty history on THIS window's real session (not a stub)
  so the real adapter -- not console_view.clj's own nil handling, already
  covered by the multiline-caret row -- is what's under test."
  [ui]
  (go-row :console-history-down-empty support/check
    (on-gtk (let [cv (:console ui)
                  ctx (:ctx (:session ui))]
              (reset! (:history ctx) (eval/create-history))
              (cview/clear-input! cv)
              (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "typed" -1 1)
              (let [got (cview/handle-history! cv :next)
                    input (cview/input-text cv)]
                (support/check :console-history-down-empty
                               (and (not got) (= "typed" input))
                               {:got got :input input}))))))

(defn row-run-selection-line
  "Caret on line 2, no selection: commands/run-selection-text (P3.T1) sends
  the whole line. Asserts BOTH the echoed \"user=> (inc 41)\" (console-view/
  echo-entry!) and the real result \"=> 42\", so a path that evaluates
  without echoing (or echoes without evaluating) still fails."
  [ui]
  (go-row :run-selection-line support/check
    (let [mark (on-gtk (let [ed (:editor ui)
                             m (support/out-end-offset ui)
                             text "(def a 1)\n(inc 41)"]
                         (editor/set-text! ed text)
                         (support/place-cursor! (:buf ed) (count text))
                         ((:run-selection!* ui))
                         m))
          ok (await-until 3000 20 #(let [t (support/since ui mark)]
                                     (and (str/includes? t "user=> (inc 41)") (str/includes? t "=> 42"))))]
      (on-gtk (support/check :run-selection-line ok {:console (support/since ui mark)})))))

(defn row-run-selection-sel
  "A selection inside a longer line sends only the selection, not the whole
  line."
  [ui]
  (go-row :run-selection-sel support/check
    (let [mark (on-gtk (let [ed (:editor ui)
                             m (support/out-end-offset ui)
                             text "foo (+ 1 2) bar"]
                         (editor/set-text! ed text)
                         (support/select-range! (:buf ed) 4 11) ; "(+ 1 2)"
                         ((:run-selection!* ui))
                         m))
          ok (await-until 3000 20 #(str/includes? (support/since ui mark) "=> 3"))]
      (on-gtk (support/check :run-selection-sel ok {:console (support/since ui mark)})))))

(defn row-run-selection-blank [ui]
  ;; since is always non-empty (it includes the live "user=> " prompt and any
  ;; typed input), so "nothing appended" is asserted against a BASELINE
  ;; captured right before the action, not literal "".
  (go-row :run-selection-blank support/check
    (let [[mark baseline] (on-gtk (let [ed (:editor ui)
                                        m (support/out-end-offset ui)
                                        text "(def a 1)\n\n(inc 41)"]
                                    (editor/set-text! ed text)
                                    (support/place-cursor! (:buf ed) (count "(def a 1)\n")) ; the blank line 2
                                    (let [baseline (support/since ui m)]
                                      ((:run-selection!* ui))
                                      [m baseline])))]
      ;; Absence has no positive condition to poll for, so this waits out
      ;; the full window rather than polling for something that should
      ;; never appear.
      (<! (timeout 500))
      (on-gtk (let [t (support/since ui mark)]
                (support/check :run-selection-blank (= baseline t) {:console t :baseline baseline}))))))

(defn row-clear-console
  "Produces real output, types \"abc\" at the (uncommitted) prompt, then
  clears. clear-output! only deletes [0, out-end) -- the whole buffer
  should read exactly the fresh prompt plus the still-typed input, and the
  input itself must be untouched."
  [ui]
  (go-row :clear-console support/check
    (let [cv (:console ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 1)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          produced? (await-until 3000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "=> 2")))]
      (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "abc" -1 1)
              ((:clear-console!* ui))
              (let [text (support/console-text ui)
                    input (cview/input-text cv)]
                (support/check :clear-console
                               (and produced? (= "user=> abc" text) (= "abc" input))
                               {:produced produced? :text text :input input}))))))

;; The shared key table (gdkkeysyms.h);
;; window.clj keeps none, since its keys live in jonquil/keymap.edn.
(def ^:private KEY-L 0x06c)

(defn row-clear-last-two-entries
  "Cmd/Ctrl+Shift+L (CIDER's C-c C-o, brief Test 1): two REPL entries
  submitted in turn, then the chord removes only the SECOND entry's own
  echo+output. console-view's mark-entry-start! re-marks at each
  commit-input!, so the mark always points at whichever entry is actually
  last -- the first entry's own prompt+result stay untouched, the fresh
  prompt stays, and text typed but not yet submitted survives the clear,
  exactly like Cmd+L's own clear-output! leaves it."
  [ui]
  (go-row :clear-last-two-entries support/check
    (let [cv (:console ui)
          mark1 (on-gtk (let [m (support/out-end-offset ui)]
                          (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 1)" -1 1)
                          (cview/handle-return! cv false)
                          m))
          produced1? (await-until 3000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark1) "=> 2")))
          before-second (on-gtk (support/console-text ui))
          mark2 (on-gtk (let [m (support/out-end-offset ui)]
                          (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 2 2)" -1 1)
                          (cview/handle-return! cv false)
                          m))
          produced2? (await-until 3000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark2) "=> 4")))]
      (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "typed" -1 1)
              (let [handled ((:handle-window-key! ui) nil KEY-L 0 (bit-or g/CONTROL-MASK g/SHIFT-MASK) nil)
                    text (support/console-text ui)
                    input (cview/input-text cv)]
                (support/check :clear-last-two-entries
                               (and produced1? produced2? (= 1 handled)
                                    (= text (str before-second "typed"))
                                    (= "typed" input))
                               {:produced1 produced1? :produced2 produced2? :handled handled
                                :before-second before-second :text text :input input}))))))

(defn row-clear-last-while-running
  "Cmd/Ctrl+Shift+L while a program is STILL printing (brief Test 2): the
  chord clears what it has printed SO FAR -- everything since the Run
  banner, which is exactly where window.clj's do-run! marks the entry's
  own start -- without needing the program to finish, and output the
  program prints AFTER the clear keeps landing normally, since clear-last-
  output! only ever deletes up to out-end's offset AT THE TIME of the key."
  [ui]
  (go-row :clear-last-while-running support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(loop [] (println :tick) (Thread/sleep 200) (recur))")
                         ((:run!* ui))
                         m))
          started? (await-until 2000 20 #(eval/evaluating? (:ctx (:session ui))))
          printed-once? (await-until 2000 20 #(str/includes? (support/since ui mark) ":tick"))
          handled (on-gtk ((:handle-window-key! ui) nil KEY-L 0 (bit-or g/CONTROL-MASK g/SHIFT-MASK) nil))
          ;; mark sits at the run banner's own start (do-run!'s own
          ;; mark-entry-start! reads out-end at that same instant), so a
          ;; full clear leaves exactly the live "user=> " prompt from mark
          ;; onward -- not "", which since-mark never reads once ANY
          ;; prompt exists past mark.
          cleared? (on-gtk (= "user=> " (support/since ui mark)))
          printed-after? (await-until 2000 20 #(str/includes? (support/since ui mark) ":tick"))
          _ (on-gtk ((:stop!* ui)))
          stopped? (await-until 3000 20 #(str/includes? (support/since ui mark) "; interrupted"))]
      (on-gtk (support/check :clear-last-while-running
                             (and started? printed-once? (= 1 handled) cleared? printed-after? stopped?)
                             {:started started? :printed-once printed-once? :handled handled
                              :cleared cleared? :printed-after printed-after? :stopped stopped?})))))

(defn row-clear-last-twice-noop
  "A second Shift+L immediately after the first (brief Test 3): clear-
  last-output! deactivates the mark on the first clear, and nothing has
  begun a NEW entry since, so the second press finds nothing left to
  clear -- the buffer must come out byte-for-byte identical to what the
  first clear already left, and the key still counts as handled (1),
  never falling through to insert an L."
  [ui]
  (go-row :clear-last-twice-noop support/check
    (let [cv (:console ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 3 3)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          produced? (await-until 3000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "=> 6")))]
      (on-gtk (let [handled1 ((:handle-window-key! ui) nil KEY-L 0 (bit-or g/CONTROL-MASK g/SHIFT-MASK) nil)
                    text-after-first (support/console-text ui)
                    handled2 ((:handle-window-key! ui) nil KEY-L 0 (bit-or g/CONTROL-MASK g/SHIFT-MASK) nil)
                    text-after-second (support/console-text ui)]
                (support/check :clear-last-twice-noop
                               (and produced? (= 1 handled1) (= 1 handled2)
                                    (= text-after-first text-after-second))
                               {:produced produced? :handled1 handled1 :handled2 handled2
                                :text-after-first text-after-first :text-after-second text-after-second}))))))

(defn row-clear-console-key-regression
  "Regression check (brief Test 4): with the new Shift-qualified clause
  now sitting ahead of it in the window keymap, plain Cmd/Ctrl+L (no
  Shift) must still clear the WHOLE output history, exactly as before.
  Driven through the real key-controller fn (:handle-window-key!), not
  the raw :clear-console!* hook row-clear-console above already covers,
  so a regression in the cond ORDERING itself (not just in do-clear-
  console!) would be caught here."
  [ui]
  (go-row :clear-console-key-regression support/check
    (let [cv (:console ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 5 5)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          produced? (await-until 3000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "=> 10")))]
      (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "abc" -1 1)
              (let [handled ((:handle-window-key! ui) nil KEY-L 0 g/CONTROL-MASK nil)
                    text (support/console-text ui)
                    input (cview/input-text cv)]
                (support/check :clear-console-key-regression
                               (and produced? (= 1 handled) (= "user=> abc" text) (= "abc" input))
                               {:produced produced? :handled handled :text text :input input}))))))

(defn row-clear-last-menu-item
  "The console's right-click 'Clear Last Output' item does exactly what
  the key does (brief Test 5) -- fired through :repl-activate-menu-
  action!* (install-context-menu!'s own unprefixed action-name
  activation), the same path rename-menus.clj's own row-menu-actions-
  console-complete already exercises for the console's Complete item."
  [ui]
  (go-row :clear-last-menu-item support/check
    (let [cv (:console ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 4 4)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          produced? (await-until 3000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "=> 8")))]
      (on-gtk (let [before (support/since ui mark)]
                ((:repl-activate-menu-action!* ui) "clear-last-output")
                ;; mark sits at the entry's own start (commit-input!'s own
                ;; mark-entry-start!), so a clear leaves exactly the live
                ;; "user=> " prompt from mark onward, not "".
                (let [after (support/since ui mark)]
                  (support/check :clear-last-menu-item
                                 (and produced? (str/includes? before "=> 8") (= "user=> " after))
                                 {:produced produced? :before before :after after})))))))

(defn row-clear-last-run-selection-mid-run-keeps-mark
  "Fix round 1, Important #1: Cmd+E (Run Selection) while a program is
  STILL running must NOT move the last-entry mark -- only do-run!'s own
  idle-only marking may, and do-run-selection!/do-run-form! now pass
  echo-entry! a mark? flag gated on (not (ev/evaluating? @ev*)), mirroring
  do-run!'s own busy/idle branching. Starts a printing loop (marked at the
  banner), Runs Selection on OTHER text mid-run (echoes as before, but
  must not re-mark), waits for MORE loop output past that echo, then
  Shift+L: the WHOLE run's own entry -- banner, every tick, the mid-run
  echo and its own busy warning -- must clear as one block, never cut off
  at the Cmd+E point."
  [ui]
  (go-row :clear-last-run-selection-mid-run-keeps-mark support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(loop [] (println :tick) (Thread/sleep 200) (recur))")
                         ((:run!* ui))
                         m))
          started? (await-until 2000 20 #(eval/evaluating? (:ctx (:session ui))))
          printed-once? (await-until 2000 20 #(str/includes? (support/since ui mark) ":tick"))
          _ (on-gtk (editor/set-text! (:editor ui) "(+ 9 9)")
                    ((:run-selection!* ui)))
          echoed? (await-until 2000 20 #(str/includes? (support/since ui mark) "(+ 9 9)"))
          ;; more :tick output must still arrive AFTER the mid-run echo,
          ;; proving the loop kept running and printing normally through
          ;; it -- both must end up inside the SAME entry as the banner.
          printed-after-echo? (await-until 2000 20
                                           #(let [t (support/since ui mark)
                                                  i (str/index-of t "(+ 9 9)")]
                                              (and i (str/includes? (subs t i) ":tick"))))
          handled (on-gtk ((:handle-window-key! ui) nil KEY-L 0 (bit-or g/CONTROL-MASK g/SHIFT-MASK) nil))
          cleared? (on-gtk (= "user=> " (support/since ui mark)))
          _ (on-gtk ((:stop!* ui)))
          stopped? (await-until 3000 20 #(str/includes? (support/since ui mark) "; interrupted"))]
      (on-gtk (support/check :clear-last-run-selection-mid-run-keeps-mark
                             (and started? printed-once? echoed? printed-after-echo?
                                  (= 1 handled) cleared? stopped?)
                             {:started started? :printed-once printed-once? :echoed echoed?
                              :printed-after-echo printed-after-echo? :handled handled
                              :cleared cleared? :stopped stopped?})))))

(defn row-clear-last-mark-lands-after-queued-tail
  "Fix round 1, Important #2: mark-entry-start! must not read out-end
  synchronously while a PREVIOUS entry's own output is still queued (via
  append!) but not yet drained -- doing so would place the mark BEFORE
  that pending tail, so a later Shift+L would delete it along with the
  new entry. Manufactures exactly that queued-but-undrained state BY
  CONSTRUCTION, all three calls inside ONE on-gtk step: append! OLDTAIL
  (queues it, schedules a drain that can't possibly run yet -- still the
  SAME synchronous call stack, and jonquil.gtk.callbacks/on-gui!'s own
  g_idle_add citation defers every drain to a later main-loop iteration),
  THEN mark-entry-start! (queues :mark right behind it, in the SAME
  queue), THEN append! NEWENTRY (queues it right behind the mark) --
  OLDTAIL is provably still undrained at the exact instant the mark
  request is queued, since nothing between them ever returns to the main
  loop. Doesn't also assert this from a SEPARATE on-gtk step afterward:
  measured live, GTK's own idle processing can service more than one
  ready g_idle_add source between two round trips this harness makes, so
  a same-tick undrained check would be flaky, not deterministic, on
  exactly the axis this row needs to be trustworthy on. Instead it only
  asserts the OUTCOME, via await-until (never assumed same-tick): once
  everything actually drains, Shift+L must find OLDTAIL surviving (the
  PREVIOUS entry, before the mark) and NEWENTRY gone (the marked entry,
  after it)."
  [ui]
  (go-row :clear-last-mark-lands-after-queued-tail support/check
    (let [cv (:console ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (cview/append! cv :out "OLDTAIL\n")
                         (cview/mark-entry-start! cv)
                         (cview/append! cv :prompt "NEWENTRY\n")
                         m))
          drained? (await-until 2000 20 #(let [t (support/since ui mark)]
                                           (and (str/includes? t "OLDTAIL")
                                                (str/includes? t "NEWENTRY"))))
          _ (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "typed" -1 1))
          handled (on-gtk ((:handle-window-key! ui) nil KEY-L 0 (bit-or g/CONTROL-MASK g/SHIFT-MASK) nil))
          final (on-gtk (support/since ui mark))]
      (on-gtk (support/check :clear-last-mark-lands-after-queued-tail
                             (and drained? (= 1 handled) (= "OLDTAIL\nuser=> typed" final))
                             {:drained drained? :handled handled :final final})))))
