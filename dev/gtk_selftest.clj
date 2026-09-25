(ns gtk-selftest
  "P2.T5/P2.T6 self-test: opens a real GtkApplicationWindow around the
  editor and the console under test, runs the rows on a timer chain (never
  a fixed sleep -- each wait polls a condition against a deadline), prints
  PASS/FAIL per row and a SUMMARY line, quits, and exits 0 or 1. Rows call
  the same functions the key handlers call; no keystroke is ever
  synthesized."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(defonce results (atom []))
(defonce true-fire-count (atom 0))

(defn- check [row ok detail]
  (swap! results conj [row (boolean ok) detail])
  (binding [*out* *err*]
    (println "[gtk-selftest]" (name row) (if ok "PASS" "FAIL") (pr-str detail))))

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

(defn- mark-offset
  "mark's current character offset in buf -- console-view keeps its marks
  (:out-end, :input-start) in its returned map, but doesn't expose an
  offset-of-mark reader, so rows that need one (to compute an offset
  relative to a mark rather than a hard-coded number) do it here the same
  way jonquil.gtk.ffi/cursor-offset does for the built-in insert mark."
  [buf mark]
  (let [it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-buffer-get-iter-at-mark buf it mark)
      (g/gtk-text-iter-get-offset it)
      (finally (ffi/free it)))))

(defn- tail
  "The last n characters of s, for a self-test detail map. row-console-flood
  leaves ~200k chars of accumulated output in the buffer, and no row resets
  output history (only input), so every row after it inherits that buffer
  -- printing it in full on a PASS/FAIL line would dump ~200 KB per row.
  Pass conditions elsewhere are computed against the real, untrimmed text;
  this only trims what gets reported."
  [s n]
  (if (> (count s) n) (subs s (- (count s) n)) s))

(defn- settle-fire-count!
  "Polls @true-fire-count until it has gone unchanged for two consecutive
  20ms ticks (>=40ms stable), or deadline-ms elapses, then calls (done
  settled?). A condition-based replacement for a fixed sleep: it waits for
  any dirty-flip left pending from an EARLIER row's edit to actually fire
  and be counted, so the caller's own baseline isn't captured mid-flip."
  [deadline-ms done]
  (let [t0 (System/currentTimeMillis)]
    (letfn [(step [last-seen stable-since]
              (let [now @true-fire-count
                    t (System/currentTimeMillis)]
                (cond
                  (not= now last-seen) (later 20 (fn [] (step now t)))
                  (>= (- t stable-since) 40) (done true)
                  (>= (- t t0) deadline-ms) (done false)
                  :else (later 20 (fn [] (step now stable-since))))))]
      (step @true-fire-count (System/currentTimeMillis)))))

;; --- rows ------------------------------------------------------------------
;; Each row is (fn [ed] (fn [next] ...)) -- it runs its actions against ed,
;; records exactly one check, then calls (next) to advance the chain.

(defn- row-editor-colours [ed]
  (fn [next]
    (let [buf (:buf ed) tags (:tags ed)]
      (editor/set-text! ed "")
      (g/gtk-text-buffer-insert-interactive-at-cursor buf "(defn f [x]\n  ; note\n  x)" -1 1)
      (poll-until 1000 20
                  (fn [] (and (g/has-tag-at? buf 1 (get tags :special-form))
                              (g/has-tag-at? buf 0 (get tags [:rainbow 0]))
                              (g/has-tag-at? buf 14 (get tags :comment))
                              (g/tag-italic? (get tags :comment))))
                  (fn [ok]
                    (check :editor-colours ok
                           {:text (editor/editor-text ed)
                            :special-form-at-1 (g/has-tag-at? buf 1 (get tags :special-form))
                            :rainbow0-at-0 (g/has-tag-at? buf 0 (get tags [:rainbow 0]))
                            :comment-at-14 (g/has-tag-at? buf 14 (get tags :comment))
                            :comment-tag-italic (g/tag-italic? (get tags :comment))})
                    (next))))))

(defn- row-editor-undo-text-only [ed]
  (fn [next]
    (let [buf (:buf ed)
          text-before (editor/editor-text ed)
          can-undo-before (not (zero? (g/gtk-text-buffer-get-can-undo buf)))
          dirty-before (editor/dirty? ed)]
      (g/gtk-text-buffer-undo buf)
      (let [text-after (editor/editor-text ed)]
        (check :editor-undo-text-only (= "" text-after)
               {:text text-after
                :text-before text-before
                :can-undo-before can-undo-before
                :dirty-before dirty-before
                :can-undo-after (not (zero? (g/gtk-text-buffer-get-can-undo buf)))})
        (next)))))

(defn- row-editor-smart-enter [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "(defn f [x]")
      (g/with-iters* buf 11 11 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
      (editor/handle-return! ed)
      (let [text (editor/editor-text ed) caret (g/cursor-offset buf)]
        (check :editor-smart-enter (and (= "(defn f [x]\n  " text) (= 14 caret))
               {:text text :caret caret}))
      (next))))

(defn- row-editor-dirty [ed]
  (fn [next]
    (editor/set-text! ed "a")
    (let [dirty-right-after (editor/dirty? ed)]
      ;; Condition-based replacement for a fixed sleep: wait for any
      ;; dirty-flip left pending from an earlier row's edit (tick runs
      ;; every 20ms) to actually fire and be counted, before this row's
      ;; own baseline is captured -- not a disguised fixed wait, since it
      ;; polls @true-fire-count for real stability rather than assuming
      ;; any fixed duration is enough.
      (settle-fire-count!
       1000
       (fn [settled?]
         (let [baseline @true-fire-count
               buf (:buf ed)
               t0 (System/currentTimeMillis)]
           (g/with-iters* buf 1 1 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
           (g/gtk-text-buffer-insert-interactive-at-cursor buf "b" -1 1)
           (poll-until 1000 20
                       (fn [] (and (editor/dirty? ed)
                                   (>= (- @true-fire-count baseline) 1)))
                       (fn [became-dirty]
                         (check :editor-dirty
                                (and (not dirty-right-after)
                                     became-dirty
                                     (= 1 (- @true-fire-count baseline)))
                                {:dirty-right-after-set-text dirty-right-after
                                 :settled-before-baseline settled?
                                 :became-dirty-within-deadline became-dirty
                                 :true-fires (- @true-fire-count baseline)
                                 :baseline-fire-count baseline
                                 :final-fire-count @true-fire-count
                                 :text-after-insert (editor/editor-text ed)
                                 :dirty-at-check (editor/dirty? ed)
                                 :elapsed-ms (- (System/currentTimeMillis) t0)})
                         (next)))))))))

(defn- row-editor-emoji-offsets [ed]
  (fn [next]
    (let [buf (:buf ed) tags (:tags ed)]
      (editor/set-text! ed "😀(a)")
      (poll-until 1000 20
                  (fn [] (and (g/has-tag-at? buf 1 (get tags [:rainbow 0]))
                              (not (g/has-tag-at? buf 0 (get tags [:rainbow 0])))))
                  (fn [ok]
                    (check :editor-emoji-offsets ok
                           {:text (editor/editor-text ed)
                            :rainbow0-at-1 (g/has-tag-at? buf 1 (get tags [:rainbow 0]))
                            :rainbow0-at-0 (g/has-tag-at? buf 0 (get tags [:rainbow 0]))})
                    (next))))))

(defn- row-on-gui-concurrent
  "P2.T6 will call on-gui! from worker threads at flood rates; this proves
  no thunk enqueued concurrently from several threads is ever stranded."
  [_ed]
  (fn [next]
    (let [n-threads 4
          per-thread 2500
          total (* n-threads per-thread)
          counter (atom 0)
          t0 (System/currentTimeMillis)]
      (dotimes [_ n-threads]
        (future
          (dotimes [_ per-thread]
            (cb/on-gui! (fn [] (swap! counter inc))))))
      (poll-until 10000 20
                  (fn [] (= total @counter))
                  (fn [ok]
                    (check :on-gui-concurrent ok
                           {:count @counter :total total
                            :ms (- (System/currentTimeMillis) t0)})
                    (next))))))

(defn- row-editor-preedit-guard
  "I5: while an IME preedit composition is active, Return must not apply
  smart-enter -- setting the atom directly proves the handlers yield
  without needing a real IME (the real preedit-changed wiring is a human
  smoke step)."
  [ed]
  (fn [next]
    (editor/set-text! ed "(defn f [x]")
    (g/with-iters* (:buf ed) 11 11 (fn [a _] (g/gtk-text-buffer-place-cursor (:buf ed) a)))
    (reset! (:preedit-active? ed) true)
    (let [result (editor/handle-return! ed)
          text (editor/editor-text ed)]
      (reset! (:preedit-active? ed) false)
      (check :editor-preedit-guard (and (nil? result) (= "(defn f [x]" text))
             {:result result :text text})
      (next))))

(defn- row-editor-tick-skips-unchanged
  "M7: tick! used to copy and compare the whole buffer every 20ms even when
  nothing changed. dirty-check-count only bumps on an actual recompute, so
  it must stay flat across ~200ms (10 ticks) of idle after a settled edit,
  then bump again once a real edit happens. dirty-check-count is a
  monotonic counter for the whole selftest run (earlier rows' own edits
  already pushed it well above 0), so the first poll compares against a
  pre-edit snapshot taken JUST before this row's own set-text! -- not
  pos?, which would already be true from those earlier rows and so
  wouldn't actually wait for anything."
  [ed]
  (fn [next]
    (let [pre-edit @(:dirty-check-count ed)]
      (editor/set-text! ed "abc")
      (poll-until 500 20
                  (fn [] (> @(:dirty-check-count ed) pre-edit))
                  (fn [recomputed-once?]
                    (let [baseline @(:dirty-check-count ed)]
                      (later 200
                             (fn []
                               (let [after-idle @(:dirty-check-count ed)]
                                 (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "d" -1 1)
                                 (poll-until 500 20
                                             (fn [] (> @(:dirty-check-count ed) after-idle))
                                             (fn [recomputed-after-edit?]
                                               (check :editor-tick-skips-unchanged
                                                      (and recomputed-once? (= baseline after-idle) recomputed-after-edit?)
                                                      {:pre-edit pre-edit :recomputed-once recomputed-once? :baseline baseline
                                                       :after-idle after-idle :after-edit @(:dirty-check-count ed)})
                                               (next))))))))))))

;; --- P3.T2: handle-key! dispatch rows --------------------------------------
;; gtk-keys.md's key table -- editor.clj's own copy is private, same pattern
;; gtk_selftest_window.clj already uses for its own KEY-ESCAPE/KEY-S-CAPS/
;; KEY-W. Every row calls editor/handle-key! directly with a synthetic
;; keyval/state, never a synthesized OS keystroke.
(def ^:private KEY-PARENLEFT 0x028)
(def ^:private KEY-PARENRIGHT 0x029)
(def ^:private KEY-BRACKETLEFT 0x05b)
(def ^:private KEY-BACKSPACE 0xff08)
(def ^:private KEY-TAB 0xff09)
(def ^:private KEY-ISO-LEFT-TAB 0xfe20)
(def ^:private KEY-SLASH 0x02f)
(def ^:private KEY-F 0x066)
(def ^:private KEY-Y 0x079)

(defn- line-of
  "The 0-based line `pos` sits on within `text` -- the number of newlines
  strictly before it. Used by row-edit-tab-selection to assert the
  selection apply-op! sets still spans both lines, without hard-coding the
  exact post-indent offsets."
  [text pos]
  (count (filter #(= % \newline) (subs text 0 pos))))

(defn- row-edit-auto-pair [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "")
      (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
      (let [handled (editor/handle-key! ed KEY-PARENLEFT 0)
            text (editor/editor-text ed)
            caret (g/cursor-offset buf)]
        (check :edit-auto-pair (and (= 1 handled) (= "()" text) (= 1 caret))
               {:handled handled :text text :caret caret})
        (next)))))

(defn- row-edit-wrap-selection [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "abc")
      (g/with-iters* buf 0 3 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
      (let [handled (editor/handle-key! ed KEY-BRACKETLEFT 0)
            text (editor/editor-text ed)]
        (check :edit-wrap-selection (and (= 1 handled) (= "[abc]" text))
               {:handled handled :text text})
        (next)))))

(defn- row-edit-type-over [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "()")
      (g/with-iters* buf 1 1 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
      (let [handled (editor/handle-key! ed KEY-PARENRIGHT 0)
            text (editor/editor-text ed)
            caret (g/cursor-offset buf)]
        (check :edit-type-over (and (= 1 handled) (= "()" text) (= 2 caret))
               {:handled handled :text text :caret caret})
        (next)))))

(defn- row-edit-backspace-pair [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "()")
      (g/with-iters* buf 1 1 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
      (let [handled (editor/handle-key! ed KEY-BACKSPACE 0)
            text (editor/editor-text ed)]
        (check :edit-backspace-pair (and (= 1 handled) (= "" text))
               {:handled handled :text text})
        (next)))))

(defn- row-edit-backspace-plain [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "ab")
      (g/with-iters* buf 2 2 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
      (let [handled (editor/handle-key! ed KEY-BACKSPACE 0)
            text (editor/editor-text ed)]
        (check :edit-backspace-plain (and (= 0 handled) (= "ab" text))
               {:handled handled :text text})
        (next)))))

(defn- row-edit-tab-selection [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "ab\ncd")
      (g/with-iters* buf 0 5 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
      (let [h1 (editor/handle-key! ed KEY-TAB 0)
            text1 (editor/editor-text ed)
            sel1 (g/selection-offsets buf)
            h2 (editor/handle-key! ed KEY-TAB 0)
            text2 (editor/editor-text ed)
            [sel-a sel-b] (g/selection-offsets buf)]
        (check :edit-tab-selection
               (and (= 1 h1) (= 1 h2) (= "    ab\n    cd" text2)
                    (= 0 (line-of text2 sel-a)) (= 1 (line-of text2 sel-b)))
               {:h1 h1 :text1 text1 :sel1 sel1 :h2 h2 :text2 text2 :sel2 [sel-a sel-b]})
        (next)))))

(defn- row-edit-shift-tab [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "  ab\n  cd")
      (g/with-iters* buf 0 9 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
      (let [handled (editor/handle-key! ed KEY-ISO-LEFT-TAB 0)
            text (editor/editor-text ed)]
        (check :edit-shift-tab (and (= 1 handled) (= "ab\ncd" text))
               {:handled handled :text text})
        (next)))))

(defn- row-edit-shift-tab-unindented
  "P3 fix wave C2: edit/unindent returns nil when no spanned line has
  leading whitespace. handle-key! used to return 0 then, handing
  ISO_Left_Tab to GtkTextView's own key handler, which commits a literal
  \"\\t\" over the selection (gtk/gtktextview.c at the 4.22.4 tag, the
  Tab/KP_Tab/ISO_Left_Tab branch). Shift+Tab must be consumed (1) and
  leave the text alone."
  [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "(a)\n(b)")
      (g/with-iters* buf 0 7 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
      (let [handled (editor/handle-key! ed KEY-ISO-LEFT-TAB 0)
            text (editor/editor-text ed)]
        (check :edit-shift-tab-unindented (and (= 1 handled) (= "(a)\n(b)" text))
               {:handled handled :text text})
        (next)))))

(defn- row-edit-toggle-comment
  "Fix round 1: the selection is built BACKWARD -- with-iters* buf 0 7
  passes offset 0 first to gtk_text_buffer_select_range, so the insert
  mark (probe 6: the FIRST iter argument) lands at 0, the LOW end, same as
  the reviewer's verified (edit/toggle-comment {:text \"(+ 1 2)\" :caret 0
  :sel-start 0 :sel-end 7}) => {:edits [[0 0 \"; \"]], :caret 2, :sel [2
  9]}. apply-op! must put the insert mark at 2 (where toggle-comment's own
  :caret says), not always at the selection's high end -- so this row
  reads back g/cursor-offset, not just the text, after each toggle."
  [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "(+ 1 2)")
      (g/with-iters* buf 0 7 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
      (let [h1 (editor/handle-key! ed KEY-SLASH g/CONTROL-MASK)
            text1 (editor/editor-text ed)
            caret1 (g/cursor-offset buf)
            sel1 (g/selection-offsets buf)
            h2 (editor/handle-key! ed KEY-SLASH g/CONTROL-MASK)
            text2 (editor/editor-text ed)
            caret2 (g/cursor-offset buf)
            sel2 (g/selection-offsets buf)]
        (check :edit-toggle-comment
               (and (= 1 h1) (= "; (+ 1 2)" text1) (= 2 caret1) (= [2 9] sel1)
                    (= 1 h2) (= "(+ 1 2)" text2) (= 0 caret2) (= [0 7] sel2))
               {:h1 h1 :text1 text1 :caret1 caret1 :sel1 sel1
                :h2 h2 :text2 text2 :caret2 caret2 :sel2 sel2})
        (next)))))

(defn- row-edit-toggle-comment-forward
  "Fix round 1's forward-direction counterpart: the selection is built
  FORWARD this time (with-iters* buf 7 0 -- offset 7 passed first, so the
  insert mark lands at 7, the HIGH end, matching a normal left-to-right
  drag). toggle-comment's own :caret then equals the selection's high end
  (sel-b), which is what the pre-fix apply-op! always assumed -- so this
  case passed even with the bug. Kept as the regression check that the fix
  (picking the iter that matches :caret, not always sel-b) didn't break
  the common case."
  [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "(+ 1 2)")
      (g/with-iters* buf 7 0 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
      (let [handled (editor/handle-key! ed KEY-SLASH g/CONTROL-MASK)
            text (editor/editor-text ed)
            caret (g/cursor-offset buf)
            sel (g/selection-offsets buf)]
        (check :edit-toggle-comment-forward
               (and (= 1 handled) (= "; (+ 1 2)" text) (= 9 caret) (= [2 9] sel))
               {:handled handled :text text :caret caret :sel sel})
        (next)))))

(defn- row-edit-format-all [ed]
  (fn [next]
    (let [buf (:buf ed)
          original "(defn f [x]\n(inc x))"]
      (editor/set-text! ed original)
      (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
      (let [handled (editor/handle-key! ed KEY-F (bit-or g/CONTROL-MASK g/SHIFT-MASK))
            text (editor/editor-text ed)]
        (g/gtk-text-buffer-undo buf)
        (let [undone (editor/editor-text ed)]
          (check :edit-format-all
                 (and (= 1 handled) (= "(defn f [x]\n  (inc x))" text) (= original undone))
                 {:handled handled :text text :undone undone})
          (next))))))

(defn- row-edit-format-selection [ed]
  (fn [next]
    (let [buf (:buf ed)
          original "(defn f [x]\n(inc x))"]
      (editor/set-text! ed original)
      ;; offset 15 is inside "(inc x))", line 2 -- format-selection-op
      ;; operates on the caret's whole line with no selection present.
      (g/with-iters* buf 15 15 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
      (let [handled (editor/handle-key! ed KEY-F (bit-or g/CONTROL-MASK g/ALT-MASK))
            text (editor/editor-text ed)]
        (g/gtk-text-buffer-undo buf)
        (let [undone (editor/editor-text ed)]
          (check :edit-format-selection
                 (and (= 1 handled) (= "(defn f [x]\n  (inc x))" text) (= original undone))
                 {:handled handled :text text :undone undone})
          (next))))))

(defn- row-edit-format-selection-macos-cmd-option-f
  "Bug fix (Cmd+Option+F did nothing in the built macOS app): the MEASURED
  macOS triple for Cmd+Option+F, from a pid-driven cgevent probe against
  this build (not assumed) -- keyval 0x8f6 (GDK_KEY_function, the florin
  sign Option+F types on a US keyboard, gdkkeysyms.h:1143), hardware
  keycode 3 (kVK_ANSI_F, macOS SDK Events.h:200), state META-MASK|ALT-MASK
  (Cmd reports as META on GDK's macOS backend -- command-chord?'s own
  check; the probe showed no CONTROL bit). Calls handle-key!'s 4-arg
  arity so alt-chord-f-or-l?'s hardware-keycode fallback has a real
  keycode to match -- this row FAILS on the pre-fix code, where the
  dispatch only ever compared keyval against #{KEY-F KEY-L}."
  [ed]
  (fn [next]
    (let [buf (:buf ed)
          original "(defn f [x]\n(inc x))"]
      (editor/set-text! ed original)
      ;; offset 15 is inside "(inc x))", line 2, same as row-edit-format-selection.
      (g/with-iters* buf 15 15 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
      (let [handled (editor/handle-key! ed 0x8f6 3 (bit-or g/META-MASK g/ALT-MASK))
            text (editor/editor-text ed)]
        (g/gtk-text-buffer-undo buf)
        (let [undone (editor/editor-text ed)]
          (check :edit-format-selection-macos-cmd-option-f
                 (and (= 1 handled) (= "(defn f [x]\n  (inc x))" text) (= original undone))
                 {:handled handled :text text :undone undone})
          (next))))))

(defn- row-edit-format-selection-macos-cmd-option-l
  "Same bug, the L chord: MEASURED macOS triple for Cmd+Option+L -- keyval
  0xac (GDK_KEY_notsign, the ¬ Option+L types, gdkkeysyms.h:490), hardware
  keycode 37 (kVK_ANSI_L, macOS SDK Events.h:232), state
  META-MASK|ALT-MASK, same as the F row above."
  [ed]
  (fn [next]
    (let [buf (:buf ed)
          original "(defn f [x]\n(inc x))"]
      (editor/set-text! ed original)
      (g/with-iters* buf 15 15 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
      (let [handled (editor/handle-key! ed 0xac 37 (bit-or g/META-MASK g/ALT-MASK))
            text (editor/editor-text ed)]
        (g/gtk-text-buffer-undo buf)
        (let [undone (editor/editor-text ed)]
          (check :edit-format-selection-macos-cmd-option-l
                 (and (= 1 handled) (= "(defn f [x]\n  (inc x))" text) (= original undone))
                 {:handled handled :text text :undone undone})
          (next))))))

(defn- row-edit-redo-y [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "")
      (g/gtk-text-buffer-insert-interactive-at-cursor buf "x" -1 1)
      (let [typed (editor/editor-text ed)]
        (g/gtk-text-buffer-undo buf)
        (let [undone (editor/editor-text ed)
              handled (editor/handle-key! ed KEY-Y g/CONTROL-MASK)
              redone (editor/editor-text ed)]
          (check :edit-redo-y (and (= "x" typed) (= "" undone) (= 1 handled) (= "x" redone))
                 {:typed typed :undone undone :handled handled :redone redone})
          (next))))))

(defn- row-edit-preedit-yields [ed]
  (fn [next]
    (editor/set-text! ed "abc")
    (reset! (:preedit-active? ed) true)
    (let [handled (editor/handle-key! ed KEY-PARENLEFT 0)
          text (editor/editor-text ed)]
      (reset! (:preedit-active? ed) false)
      (check :edit-preedit-yields (and (= 0 handled) (= "abc" text))
             {:handled handled :text text})
      (next))))

(def ^:private row-fns
  [row-editor-colours
   row-editor-undo-text-only
   row-editor-smart-enter
   row-editor-dirty
   row-editor-emoji-offsets
   row-editor-preedit-guard
   row-editor-tick-skips-unchanged
   row-on-gui-concurrent
   row-edit-auto-pair
   row-edit-wrap-selection
   row-edit-type-over
   row-edit-backspace-pair
   row-edit-backspace-plain
   row-edit-tab-selection
   row-edit-shift-tab
   row-edit-shift-tab-unindented
   row-edit-toggle-comment
   row-edit-toggle-comment-forward
   row-edit-format-all
   row-edit-format-selection
   row-edit-format-selection-macos-cmd-option-f
   row-edit-format-selection-macos-cmd-option-l
   row-edit-redo-y
   row-edit-preedit-yields])

;; --- P2.T6: console rows -------------------------------------------------
;; on-submit/history-prev/history-next are stubs recording their calls in
;; atoms; the rows call the same functions the key handlers call
;; (handle-return!/handle-history!), never a synthesized keystroke.

(defonce console-submits (atom []))
(defonce history-prev-calls (atom []))
(defonce history-next-calls (atom []))

(defn- stub-on-submit! [action] (swap! console-submits conj action))
(defn- stub-history-prev [current] (swap! history-prev-calls conj current) "(inc 1)")
(defn- stub-history-next [] (swap! history-next-calls conj :called) nil)

(defn- row-console-readonly [cv]
  (fn [next]
    (cview/append! cv :out "hello\n")
    (poll-until 1000 20
                (fn [] (str/includes? (g/buffer-text (:buf cv)) "hello\n"))
                (fn [drained]
                  (let [buf (:buf cv)
                        before (g/buffer-text buf)
                        it (ffi/alloc g/ITER-BYTES)
                        refused (try
                                  (g/gtk-text-buffer-get-iter-at-offset buf it 2)
                                  (zero? (g/gtk-text-buffer-insert-interactive buf it "X" -1 1))
                                  (finally (ffi/free it)))
                        after (g/buffer-text buf)]
                    (check :console-readonly (and drained refused (= before after))
                           {:drained drained :refused refused :before before :after after})
                    (next))))))

(defn- row-console-readonly-delete [cv]
  (fn [next]
    (cview/clear-input! cv)
    (cview/append! cv :out "guard\n")
    (poll-until 1000 20
                (fn [] (str/includes? (g/buffer-text (:buf cv)) "guard\n"))
                (fn [drained]
                  (let [buf (:buf cv)
                        before (g/buffer-text buf)
                        input-start-at (mark-offset buf (:input-start cv))
                        ;; the space just before input-start is the second
                        ;; char of the prompt's "> " -- still read-only.
                        prompt-space-at (dec input-start-at)
                        delete-at (fn [pos]
                                    (let [i1 (ffi/alloc g/ITER-BYTES)
                                          i2 (ffi/alloc g/ITER-BYTES)]
                                      (try
                                        (g/gtk-text-buffer-get-iter-at-offset buf i1 pos)
                                        (g/gtk-text-buffer-get-iter-at-offset buf i2 (inc pos))
                                        (not (zero? (g/gtk-text-buffer-delete-interactive buf i1 i2 1)))
                                        (finally (ffi/free i1) (ffi/free i2)))))
                        prompt-delete-succeeded (delete-at prompt-space-at)
                        after-prompt-delete (g/buffer-text buf)
                        history-delete-succeeded (delete-at 0)
                        after-history-delete (g/buffer-text buf)
                        _ (g/gtk-text-buffer-insert-interactive-at-cursor buf "z" -1 1)
                        input-delete-at (mark-offset buf (:input-start cv))
                        input-delete-succeeded (delete-at input-delete-at)
                        input-after (cview/input-text cv)]
                    (check :console-readonly-delete
                           (and drained
                                (not prompt-delete-succeeded) (= before after-prompt-delete)
                                (not history-delete-succeeded) (= before after-history-delete)
                                input-delete-succeeded (= "" input-after))
                           {:drained drained
                            :prompt-delete-succeeded prompt-delete-succeeded
                            :history-delete-succeeded history-delete-succeeded
                            :input-delete-succeeded input-delete-succeeded
                            :before before
                            :after-prompt-delete after-prompt-delete
                            :after-history-delete after-history-delete
                            :input-after input-after})
                    (next))))))

(defn- row-console-output-above-input [cv]
  (fn [next]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "abc" -1 1)
    (future (cview/append! cv :out "late\n"))
    (poll-until 1000 20
                (fn [] (str/includes? (g/buffer-text (:buf cv)) "late\n"))
                (fn [ok]
                  (let [text (g/buffer-text (:buf cv))
                        input (cview/input-text cv)
                        late-at (str/index-of text "late\n")
                        prompt-at (str/index-of text "> abc")]
                    (check :console-output-above-input
                           (and ok (str/ends-with? text "> abc")
                                (some? late-at) (some? prompt-at) (< late-at prompt-at)
                                (= "abc" input))
                           {:text text :input input :late-at late-at :prompt-at prompt-at})
                    (next))))))

(defn- row-console-submit [cv]
  (fn [next]
    (cview/clear-input! cv)
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 2)" -1 1)
    ;; the offset of the submitted text's first char, captured from
    ;; input-start BEFORE handle-return! moves the mark to the fresh
    ;; prompt -- never a hard-coded number.
    (let [submitted-at (mark-offset (:buf cv) (:input-start cv))
          got (cview/handle-return! cv false)
          input-after (cview/input-text cv)
          text (g/buffer-text (:buf cv))
          history-readonly (g/has-tag-at? (:buf cv) submitted-at (:readonly-tag cv))]
      (check :console-submit
             (and (= 1 got)
                  (= {:action :eval :text "(+ 1 2)"} (last @console-submits))
                  history-readonly
                  (= "" input-after)
                  (str/ends-with? text "> "))
             {:return got :last-submit (last @console-submits)
              :history-readonly history-readonly :submitted-at submitted-at
              :input-after input-after :text text})
      (next))))

(defn- row-console-style-after-submit
  "P2.T9 round-1 fix: once a REPL entry is committed, out-end sits exactly
  on the boundary between the just-committed prompt+input span and the
  fresh \"> \" prompt right after it -- both tagged :prompt, no untagged
  character between them. GtkTextBuffer treats inserting text at such a
  boundary as inside one continuous :prompt span and extends it onto the
  new text; insert-chunk! used to only ADD its kind's own tag on top via
  gtk_text_buffer_apply_tag, never stripping a tag that leaked in from
  the insertion boundary. Submits an entry (so out-end lands on that
  boundary), appends plain :out text, and asserts the appended text
  carries :out but NOT :prompt -- and, the reverse direction, that the
  just-submitted input still carries its own :prompt and stays
  read-only."
  [cv]
  (fn [next]
    (cview/clear-input! cv)
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 3 3)" -1 1)
    (let [submitted-at (mark-offset (:buf cv) (:input-start cv))
          got (cview/handle-return! cv false)]
      (future (cview/append! cv :out "plain\n"))
      (poll-until 1000 20
                  (fn [] (str/includes? (g/buffer-text (:buf cv)) "plain\n"))
                  (fn [ok]
                    (let [buf (:buf cv)
                          text (g/buffer-text buf)
                          plain-at (str/index-of text "plain\n")
                          out-tag (get (:tags cv) :out)
                          prompt-tag (get (:tags cv) :prompt)
                          plain-out? (g/has-tag-at? buf plain-at out-tag)
                          plain-prompt? (g/has-tag-at? buf plain-at prompt-tag)
                          submitted-prompt? (g/has-tag-at? buf submitted-at prompt-tag)
                          submitted-readonly? (g/has-tag-at? buf submitted-at (:readonly-tag cv))]
                      (check :console-style-after-submit
                             (and (= 1 got) ok plain-out? (not plain-prompt?)
                                  submitted-prompt? submitted-readonly?)
                             {:return got :plain-at plain-at :plain-out plain-out? :plain-prompt plain-prompt?
                              :submitted-at submitted-at :submitted-prompt submitted-prompt?
                              :submitted-readonly submitted-readonly? :text (tail text 200)})
                      (next)))))))

(defn- row-console-unbalanced-newline [cv]
  (fn [next]
    (cview/clear-input! cv)
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(defn f [x]" -1 1)
    (let [before-count (count @console-submits)
          got (cview/handle-return! cv false)
          after-count (count @console-submits)]
      (check :console-unbalanced-newline
             (and (= 0 got) (= before-count after-count))
             {:return got :before-count before-count :after-count after-count})
      (next))))

(defn- row-console-flood [cv]
  (fn [next]
    ;; row-console-unbalanced-newline deliberately leaves "(defn f [x]" in
    ;; the input (its :newline branch never touches the buffer); clear it
    ;; so output-chars below measures the output region, not output plus
    ;; that leftover input.
    (cview/clear-input! cv)
    (let [samples (atom [(System/currentTimeMillis)])
          active? (atom true)
          tick (fn tick []
                 (when @active?
                   (swap! samples conj (System/currentTimeMillis))
                   (later 20 tick)))]
      (later 20 tick)
      (future (dotimes [i 100000] (cview/append! cv :out (str i "\n"))))
      (poll-until 60000 50
                  (fn [] (str/includes? (g/buffer-text (:buf cv)) "99999\n"))
                  (fn [ok]
                    (reset! active? false)
                    (let [gaps (map - (rest @samples) @samples)
                          max-gap (if (seq gaps) (apply max gaps) 0)
                          out-chars (- (count (g/buffer-text (:buf cv))) 2)]
                      ;; I7: drain! no longer inserts a whole oversized batch
                      ;; before trimming, and trim! measures out-end's own
                      ;; offset instead of copying the output region just to
                      ;; count it -- both bounded costs, so the GTK main
                      ;; loop's tick cadence should stay well under 1s even
                      ;; under this flood (measured 90-144ms pre-fix, 99-111ms
                      ;; post-fix across this fix wave's own runs).
                      (check :console-flood (and ok (<= out-chars cview/MAX-OUTPUT-CHARS) (< max-gap 1000))
                             {:last-line-visible ok
                              :output-chars out-chars
                              :max-output-chars cview/MAX-OUTPUT-CHARS
                              :longest-tick-gap-ms max-gap})
                      (next)))))))

(defn- row-console-unicode [cv]
  (fn [next]
    (cview/clear-input! cv)
    (cview/append! cv :out "😀한글\n")
    (poll-until 1000 20
                (fn [] (str/includes? (g/buffer-text (:buf cv)) "한글\n"))
                (fn [drained]
                  (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "한" -1 1)
                  (let [input (cview/input-text cv)
                        buf (:buf cv)
                        text (g/buffer-text buf)
                        prompt-at (str/index-of text "> ")
                        it (ffi/alloc g/ITER-BYTES)
                        refused (try
                                  (g/gtk-text-buffer-get-iter-at-offset buf it prompt-at)
                                  (zero? (g/gtk-text-buffer-insert-interactive buf it "X" -1 1))
                                  (finally (ffi/free it)))]
                    (check :console-unicode
                           (and drained (= "한" input) refused)
                           {:drained drained :input input :refused refused :text (tail text 200)})
                    (next))))))

(defn- row-console-history [cv]
  (fn [next]
    (cview/clear-input! cv)
    (let [handled (cview/handle-history! cv :prev)
          input (cview/input-text cv)]
      (check :console-history (and handled (= "(inc 1)" input))
             {:handled handled :input input})
      (next))))

(defn- row-console-history-multiline-caret [cv]
  (fn [next]
    (cview/clear-input! cv)
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "line1\nline2" -1 1)
    ;; the insert leaves the caret at the end of "line2" -- the input's
    ;; second (last) line -- so Up must be gated off entirely (I3): a
    ;; multi-line entry's own caret movement must not be hijacked into
    ;; browsing history. history-prev must never even be CALLED.
    (let [before-calls (count @history-prev-calls)
          got (cview/handle-history! cv :prev)
          input (cview/input-text cv)]
      (check :console-history-multiline-caret
             (and (not got) (= "line1\nline2" input) (= before-calls (count @history-prev-calls)))
             {:got got :input input
              :calls-before before-calls :calls-after (count @history-prev-calls)})
      (next))))

(defn- row-console-preedit-guard
  "I5: an active IME preedit composition makes Return and Up/Down no-ops
  (setting the atom directly proves the yield; the real preedit-changed
  wiring is a human smoke step)."
  [cv]
  (fn [next]
    (cview/clear-input! cv)
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 2)" -1 1)
    (reset! (:preedit-active? cv) true)
    (let [return-got (cview/handle-return! cv false)
          history-got (cview/handle-history! cv :prev)
          input (cview/input-text cv)]
      (reset! (:preedit-active? cv) false)
      (check :console-preedit-guard
             (and (= 0 return-got) (false? history-got) (= "(+ 1 2)" input))
             {:return-got return-got :history-got history-got :input input})
      (next))))

(def ^:private console-row-fns
  [row-console-readonly
   row-console-readonly-delete
   row-console-output-above-input
   row-console-submit
   row-console-style-after-submit
   row-console-unbalanced-newline
   row-console-flood
   row-console-unicode
   row-console-history-multiline-caret
   row-console-preedit-guard
   row-console-history])

;; --- driver ------------------------------------------------------------

(defn- run-rows! [app fns]
  (if (empty? fns)
    (let [passed (count (filter second @results)) total (count @results)]
      (println (str "SUMMARY " passed " of " total " passed"))
      (later 50 (fn [] (g/g-application-quit app))))
    ((first fns) (fn [] (run-rows! app (rest fns))))))

(defn- activate [app _data]
  (let [win (g/gtk-application-window-new app)
        ed (editor/make-editor)
        cv (cview/make-console-view {:on-submit stub-on-submit!
                                     :history-prev stub-history-prev
                                     :history-next stub-history-next})
        paned (g/gtk-paned-new g/ORIENTATION-VERTICAL)]
    (g/gtk-paned-set-start-child paned (:scroll ed))
    (g/gtk-paned-set-end-child paned (:scroll cv))
    (g/gtk-paned-set-position paned 300)
    (g/gtk-window-set-title win "Jonquil P2.T5+T6 self-test")
    (g/gtk-window-set-default-size win 700 700)
    (g/gtk-window-set-child win paned)
    (editor/on-dirty-changed! ed (fn [dirty] (when dirty (swap! true-fire-count inc))))
    (g/gtk-window-present win)
    (editor/focus! ed)
    (later 300 (fn [] (run-rows! app (into (mapv (fn [f] (f ed)) row-fns)
                                           (mapv (fn [f] (f cv)) console-row-fns)))))))

(defn -main [& _]
  (let [app (g/gtk-application-new "net.b12n.jonquil.selftest" g/APPLICATION-DEFAULT-FLAGS)]
    (g/g-signal-connect-data app "activate"
                             (cb/callable activate [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-application-run app 0 ffi/null)
    (System/exit (if (and (seq @results) (every? second @results)) 0 1))))
