(ns gtk-selftest-window.run-repl
  "Run/Stop, the REPL and stdin, run-selection, and console clearing."
  (:require [clojure.string :as str]
            [gtk-selftest-window.support :as support]
            [jonquil.eval :as eval]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.session :as session]))

(defn row-run-output [ui next]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(println :hi) (+ 1 2)")
    ((:run!* ui))
    (support/poll-until 5000 20
                (fn [] (let [t (support/since ui mark)]
                         (and (str/includes? t ":hi") (str/includes? t "=> 3"))))
                (fn [ok]
                  ;; Poll the status label too, rather than a single read, for
                  ;; the same reason: consistency with every other condition
                  ;; here being a poll-with-deadline, never a one-shot check.
                  (support/poll-until 2000 20
                              (fn [] (= "Ready" (g/label-text (:status-label ui))))
                              (fn [ready?]
                                (support/check :run-output (and ok ready?)
                                       {:console (support/since ui mark) :status (g/label-text (:status-label ui))})
                                (next)))))))

(defn row-run-while-running [ui next]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(loop [] (recur))")
    ((:run!* ui))
    (support/poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  ((:run!* ui))
                  (support/poll-until 2000 20
                              (fn [] (str/includes? (support/since ui mark) "already in progress"))
                              (fn [warned?]
                                ((:stop!* ui))
                                (support/poll-until 3000 20
                                            (fn [] (str/includes? (support/since ui mark) "; interrupted"))
                                            (fn [stopped?]
                                              ;; M1: the refused second Run must not print a
                                              ;; second banner -- exactly one for the whole row.
                                              (let [banner-count (count (re-seq #"; --- Running Definitions ---"
                                                                                (support/since ui mark)))]
                                                (support/check :run-while-running
                                                       (and started? warned? stopped? (= 1 banner-count))
                                                       {:started started? :warned warned? :stopped stopped?
                                                        :banner-count banner-count
                                                        :console (support/since ui mark)})
                                                (next))))))))))

(defn row-stdin-roundtrip [ui next]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(let [n (read-line)] (str \"Hello, \" n))")
    ((:run!* ui))
    (support/poll-until 3000 20
                (fn [] ((:waiting? (:console ui))))
                (fn [waiting?]
                  (let [cv (:console ui)]
                    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "World" -1 1)
                    (cview/handle-return! cv false)
                    (support/poll-until 3000 20
                                (fn [] (str/includes? (support/since ui mark) "=> \"Hello, World\""))
                                (fn [ok]
                                  (support/check :stdin-roundtrip (and waiting? ok)
                                         {:waiting waiting? :console (support/since ui mark)})
                                  (next))))))))

(defn row-stop-while-reading [ui next]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(let [n (read-line)] (str \"Hello, \" n))")
    ((:run!* ui))
    (support/poll-until 3000 20
                (fn [] ((:waiting? (:console ui))))
                (fn [waiting-before]
                  ((:stop!* ui))
                  (support/poll-until 3000 20
                              (fn [] (str/includes? (support/since ui mark) "; interrupted"))
                              (fn [interrupted?]
                                ;; waiting? is a plain worker-thread atom reset (no on-gui!
                                ;; scheduling), so it should already be false once "; interrupted"
                                ;; -- itself only visible after an on-gui! drain -- has landed.
                                ;; Poll it too rather than reading once, so a real lag shows up as
                                ;; a slow pass instead of a silent-looking false failure.
                                (let [t0 (System/currentTimeMillis)]
                                  (support/poll-until 2000 20
                                              (fn [] (not ((:waiting? (:console ui)))))
                                              (fn [waiting-cleared?]
                                                (let [cv (:console ui)
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
                                                          :inserted inserted :input-after input-after})
                                                  (next)))))))))))

(defn row-reset-vs-accumulate
  "Every step's own assertion is scoped (via run-and-wait!'s mark) to the
  output THAT step produced. Without this, run-row!'s reset (editor + input
  only, never the console's output history) would let the first (greet)'s
  own leftover error line -- or even a PRIOR row's -- satisfy first-error?
  regardless of whether Reset actually removed anything; see the fix-round-1
  can-fail proof in the P2.T7 report. Each run-and-wait! also gates on its
  OWN expected text (I1), not just engine-idle, so mark1/mark2 are captured
  only once the previous step's result is actually visible."
  [ui next]
  (support/run-and-wait! ui "(defn greet [] :hi)" 3000
                 (fn [t] (str/includes? t "=> #'user/greet"))
                 (fn [defined1? _]
                   (support/run-and-wait! ui "(greet)" 3000
                                  (fn [t] (str/includes? t "Error ["))
                                  (fn [first-error? mark1]
                                    (session/set-mode! (:session ui) :accumulate)
                                    (support/run-and-wait! ui "(defn greet [] :hi)" 3000
                                                   (fn [t] (str/includes? t "=> #'user/greet"))
                                                   (fn [defined2? _]
                                                     (support/run-and-wait! ui "(greet)" 3000
                                                                    (fn [t] (str/includes? t "=> :hi"))
                                                                    (fn [second-ok? mark3]
                                                                      (support/check :reset-vs-accumulate
                                                                             (and defined1? first-error? defined2? second-ok?)
                                                                             {:defined1 defined1? :first-error first-error?
                                                                              :defined2 defined2? :second-ok second-ok?
                                                                              :first-console (support/since ui mark1)
                                                                              :second-console (support/since ui mark3)})
                                                                      (next))))))))))

(defn row-repl-after-run [ui next]
  (support/run-and-wait! ui "(def x 41)" 3000
                 (fn [t] (str/includes? t "=> #'user/x"))
                 (fn [defined? _]
                   (let [cv (:console ui)
                         mark (support/out-end-offset ui)]
                     (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(inc x)" -1 1)
                     (cview/handle-return! cv false)
                     ;; I1 again: gate on the text itself, not idle? alone.
                     (support/poll-until 3000 20
                                 (fn [] (and (support/idle? ui) (str/includes? (support/since ui mark) "=> 42")))
                                 (fn [ok]
                                   (support/check :repl-after-run (and defined? ok)
                                          {:defined defined? :console (support/since ui mark)})
                                   (next)))))))

(defn row-fast-repl-twice
  "I2: a fast REPL eval (nil, completing in microseconds) must not strand
  the console in :busy. With the old console-mirrored evaluating? atom, a
  fast on-complete could clear the mirror BEFORE do-repl-eval!'s own
  `set-evaluating! true` ran, leaving it stuck true and every later Return
  silently hitting :busy. Submits nil twice in a row and requires both
  results to actually appear."
  [ui next]
  (let [cv (:console ui)
        mark1 (support/out-end-offset ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "nil" -1 1)
    (let [got1 (cview/handle-return! cv false)]
      (support/poll-until 2000 20
                  (fn [] (and (support/idle? ui) (str/includes? (support/since ui mark1) "=> nil")))
                  (fn [ok1]
                    (let [mark2 (support/out-end-offset ui)]
                      (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "nil" -1 1)
                      (let [got2 (cview/handle-return! cv false)]
                        (support/poll-until 2000 20
                                    (fn [] (and (support/idle? ui) (str/includes? (support/since ui mark2) "=> nil")))
                                    (fn [ok2]
                                      (support/check :fast-repl-twice
                                             (and (= 1 got1) ok1 (= 1 got2) ok2)
                                             {:got1 got1 :ok1 ok1 :got2 got2 :ok2 ok2
                                              :console1 (support/since ui mark1) :console2 (support/since ui mark2)})
                                      (next))))))))))

(defn row-busy-hint-once
  "M3: Return while a program runs and isn't waiting on stdin does nothing
  silently by default; the FIRST such Return during a run appends a hint,
  later ones during the SAME run stay silent."
  [ui next]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(loop [] (recur))")
    ((:run!* ui))
    (support/poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  (let [cv (:console ui)
                        got1 (cview/handle-return! cv false)
                        got2 (cview/handle-return! cv false)]
                    (support/poll-until 1000 20
                                (fn [] (str/includes? (support/since ui mark) "A program is running"))
                                (fn [hinted?]
                                  (let [hint-count (count (re-seq #"A program is running" (support/since ui mark)))]
                                    ((:stop!* ui))
                                    (support/poll-until 3000 20
                                                (fn [] (str/includes? (support/since ui mark) "; interrupted"))
                                                (fn [stopped?]
                                                  (support/check :busy-hint-once
                                                         (and started? (= 1 got1) (= 1 got2)
                                                              hinted? (= 1 hint-count) stopped?)
                                                         {:started started? :got1 got1 :got2 got2
                                                          :hinted hinted? :hint-count hint-count :stopped stopped?
                                                          :console (support/since ui mark)})
                                                  (next)))))))))))

(defn row-console-history-down-empty
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
      (support/check :console-history-down-empty
             (and (not got) (= "typed" input))
             {:got got :input input})
      (next))))

(defn row-run-selection-line
  "Caret on line 2, no selection: commands/run-selection-text (P3.T1) sends
  the whole line. Asserts BOTH the echoed \"> (inc 41)\" (console-view/
  echo-entry!) and the real result \"=> 42\", so a path that evaluates
  without echoing (or echoes without evaluating) still fails."
  [ui next]
  (let [ed (:editor ui)
        mark (support/out-end-offset ui)
        text "(def a 1)\n(inc 41)"]
    (editor/set-text! ed text)
    (support/place-cursor! (:buf ed) (count text))
    ((:run-selection!* ui))
    (support/poll-until 3000 20
                (fn [] (let [t (support/since ui mark)]
                         (and (str/includes? t "> (inc 41)") (str/includes? t "=> 42"))))
                (fn [ok]
                  (support/check :run-selection-line ok {:console (support/since ui mark)})
                  (next)))))

(defn row-run-selection-sel
  "A selection inside a longer line sends only the selection, not the whole
  line."
  [ui next]
  (let [ed (:editor ui)
        mark (support/out-end-offset ui)
        text "foo (+ 1 2) bar"]
    (editor/set-text! ed text)
    (support/select-range! (:buf ed) 4 11) ; "(+ 1 2)"
    ((:run-selection!* ui))
    (support/poll-until 3000 20
                (fn [] (str/includes? (support/since ui mark) "=> 3"))
                (fn [ok]
                  (support/check :run-selection-sel ok {:console (support/since ui mark)})
                  (next)))))

(defn row-run-selection-blank [ui next]
  ;; since is always non-empty (it includes the live "> " prompt and any
  ;; typed input), so "nothing appended" is asserted against a BASELINE
  ;; captured right before the action, not literal "".
  (let [ed (:editor ui)
        mark (support/out-end-offset ui)
        text "(def a 1)\n\n(inc 41)"]
    (editor/set-text! ed text)
    (support/place-cursor! (:buf ed) (count "(def a 1)\n")) ; the blank line 2
    (let [baseline (support/since ui mark)]
      ((:run-selection!* ui))
      ;; Absence has no positive condition to poll for, so this waits
      ;; out the full window rather than polling for something that
      ;; should never appear.
      (support/later 500
             (fn []
               (let [t (support/since ui mark)]
                 (support/check :run-selection-blank (= baseline t) {:console t :baseline baseline})
                 (next)))))))

(defn row-clear-console
  "Produces real output, types \"abc\" at the (uncommitted) prompt, then
  clears. clear-output! only deletes [0, out-end) -- the whole buffer
  should read exactly the fresh prompt plus the still-typed input, and the
  input itself must be untouched."
  [ui next]
  (let [cv (:console ui)
        mark (support/out-end-offset ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 1)" -1 1)
    (cview/handle-return! cv false)
    (support/poll-until 3000 20
                (fn [] (and (support/idle? ui) (str/includes? (support/since ui mark) "=> 2")))
                (fn [produced?]
                  (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "abc" -1 1)
                  ((:clear-console!* ui))
                  (let [text (support/console-text ui)
                        input (cview/input-text cv)]
                    (support/check :clear-console
                           (and produced? (= "> abc" text) (= "abc" input))
                           {:produced produced? :text text :input input})
                    (next))))))
