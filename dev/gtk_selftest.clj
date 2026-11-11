(ns gtk-selftest
  "Self-test: opens a real GtkApplicationWindow around the
  editor and the console under test, runs the rows as go blocks (never a
  fixed sleep -- each wait polls a condition against a deadline via
  gtk-async/await-until), prints PASS/FAIL per row and a SUMMARY line,
  quits, and exits 0 or 1. Rows call the same functions the key handlers
  call; no keystroke is ever synthesized.

  The driver is gtk-async/run-rows: rows run strictly in order, and a row
  that doesn't finish within PER-ROW-MS is recorded as a :row-timeout FAIL
  while the run goes on."
  (:require [clojure.core.async :refer [<! go timeout]]
            [clojure.string :as str]
            [gtk-async :as ga :refer [await-until go-row on-gtk]]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.commands :as commands]
            [jonquil.theme :as theme]
            [jonquil.edit :as jonquil.edit]
            [jonquil.paint :as paint]
            [jonquil.parinfer-edit :as pe]))

(defonce results (atom []))
(defonce true-fire-count (atom 0))

;; Set only where SUMMARY prints. An early g_application_quit (a row that
;; really quits) ends g_application_run with the remaining rows never run
;; and no SUMMARY, while every result recorded so far can still be a PASS;
;; -main would exit 0 in that case. The window group's own finished? is the same.
(defonce ^:private finished? (atom false))

(defn- check [row ok detail]
  (swap! results conj [row (boolean ok) detail])
  (binding [*out* *err*]
    (println "[gtk-selftest]" (name row) (if ok "PASS" "FAIL") (pr-str detail))))

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

(defn- await-fire-count-settled
  "Go-style settle-fire-count!: parks until @true-fire-count has gone
  unchanged for two consecutive 20ms ticks (>=40ms stable), or deadline-ms
  elapses; true or false. A condition-based replacement for a fixed sleep:
  it waits for any dirty-flip left pending from an EARLIER row's edit to
  actually fire and be counted, so the caller's own baseline isn't captured
  mid-flip. The stability window is wall-clock, which can't prove the main
  loop turned (a frozen loop reads as stable), so a trailing on-gtk round
  trip proves it, as settle's does."
  [deadline-ms]
  (let [t0 (System/currentTimeMillis)]
    (loop [last-seen @true-fire-count
           stable-since t0]
      (<! (timeout 20))
      (let [now @true-fire-count
            t (System/currentTimeMillis)]
        (cond
          (not= now last-seen) (recur now t)
          (>= (- t stable-since) 40) (do (on-gtk nil) true)
          (>= (- t t0) deadline-ms) false
          :else (recur now stable-since))))))

;; --- rows ------------------------------------------------------------------
;; Each row is (fn [ed] ch) -- a go block (gtk-async/go-row) that runs its
;; actions against ed and records exactly one check.

(defn- row-editor-colours [ed]
  (go-row :editor-colours check
    (let [buf (:buf ed) tags (:tags ed)]
      (on-gtk (editor/set-text! ed "")
              (g/gtk-text-buffer-insert-interactive-at-cursor buf "(defn f [x]\n  ; note\n  x)" -1 1))
      (let [ok (await-until 1000 20 #(and (g/has-tag-at? buf 1 (get tags :special-form))
                                          (g/has-tag-at? buf 0 (get tags [:rainbow 0]))
                                          (g/has-tag-at? buf 14 (get tags :comment))
                                          (g/tag-italic? (get tags :comment))))]
        (on-gtk (check :editor-colours ok
                       {:text (editor/editor-text ed)
                        :special-form-at-1 (g/has-tag-at? buf 1 (get tags :special-form))
                        :rainbow0-at-0 (g/has-tag-at? buf 0 (get tags [:rainbow 0]))
                        :comment-at-14 (g/has-tag-at? buf 14 (get tags :comment))
                        :comment-tag-italic (g/tag-italic? (get tags :comment))}))))))

(defn- row-editor-undo-text-only
  "One interactive insert into an emptied buffer, then, once its colouring
  has landed (tags aren't undo steps, which is what this row pins), one
  undo empties the buffer again. Self-contained: undoing whatever the row
  before left let a stray keystroke between the two (a space after the
  text, with no code path that inserts one) fail it as an undo bug. The text is checked in the same GTK step as the undo, and a
  buffer that changed after the insert is reported as :stray-edit."
  [ed]
  (go-row :editor-undo-text-only check
    (let [buf (:buf ed) tags (:tags ed)
          code "(defn f [x]\n  ; note\n  x)"]
      (on-gtk (editor/set-text! ed "")
              (g/gtk-text-buffer-insert-interactive-at-cursor buf code -1 1))
      (let [coloured? (await-until 1000 20 #(g/has-tag-at? buf 1 (get tags :special-form)))]
        (on-gtk (let [text-before (editor/editor-text ed)
                      can-undo-before (not (zero? (g/gtk-text-buffer-get-can-undo buf)))]
                  (g/gtk-text-buffer-undo buf)
                  (let [text-after (editor/editor-text ed)]
                    (check :editor-undo-text-only
                           (and coloured? (= code text-before) (= "" text-after))
                           (cond-> {:text text-after
                                    :coloured coloured?
                                    :can-undo-before can-undo-before
                                    :can-undo-after (not (zero? (g/gtk-text-buffer-get-can-undo buf)))}
                             (not= code text-before) (assoc :stray-edit text-before))))))))))

(defn- row-editor-smart-enter [ed]
  (go-row :editor-smart-enter check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "(defn f [x]")
              (g/with-iters* buf 11 11 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (editor/handle-return! ed)
              (let [text (editor/editor-text ed) caret (g/cursor-offset buf)]
                (check :editor-smart-enter (and (= "(defn f [x]\n  " text) (= 14 caret))
                       {:text text :caret caret}))))))

(defn- row-editor-dirty [ed]
  (go-row :editor-dirty check
    (let [dirty-right-after (on-gtk (editor/set-text! ed "a")
                                    (editor/dirty? ed))
          ;; Condition-based replacement for a fixed sleep: wait for any
          ;; dirty-flip left pending from an earlier row's edit (tick runs
          ;; every 20ms) to actually fire and be counted, before this row's
          ;; own baseline is captured -- not a disguised fixed wait, since it
          ;; polls @true-fire-count for real stability rather than assuming
          ;; any fixed duration is enough.
          settled? (await-fire-count-settled 1000)
          [baseline t0] (on-gtk (let [baseline @true-fire-count
                                      buf (:buf ed)
                                      t0 (System/currentTimeMillis)]
                                  (g/with-iters* buf 1 1 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                                  (g/gtk-text-buffer-insert-interactive-at-cursor buf "b" -1 1)
                                  [baseline t0]))
          became-dirty (await-until 1000 20 #(and (editor/dirty? ed)
                                                  (>= (- @true-fire-count baseline) 1)))]
      (on-gtk (check :editor-dirty
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
                      :elapsed-ms (- (System/currentTimeMillis) t0)})))))

(defn- row-editor-emoji-offsets [ed]
  (go-row :editor-emoji-offsets check
    (let [buf (:buf ed) tags (:tags ed)]
      (on-gtk (editor/set-text! ed "😀(a)"))
      (let [ok (await-until 1000 20 #(and (g/has-tag-at? buf 1 (get tags [:rainbow 0]))
                                          (not (g/has-tag-at? buf 0 (get tags [:rainbow 0])))))]
        (on-gtk (check :editor-emoji-offsets ok
                       {:text (editor/editor-text ed)
                        :rainbow0-at-1 (g/has-tag-at? buf 1 (get tags [:rainbow 0]))
                        :rainbow0-at-0 (g/has-tag-at? buf 0 (get tags [:rainbow 0]))}))))))

(defn- row-on-gui-concurrent
  "Worker threads call on-gui! at flood rates; this proves
  no thunk enqueued concurrently from several threads is ever stranded."
  [_ed]
  (go-row :on-gui-concurrent check
    (let [n-threads 4
          per-thread 2500
          total (* n-threads per-thread)
          counter (atom 0)
          t0 (System/currentTimeMillis)]
      (dotimes [_ n-threads]
        (future
          (dotimes [_ per-thread]
            (cb/on-gui! (fn [] (swap! counter inc))))))
      (let [ok (await-until 10000 20 #(= total @counter))]
        (check :on-gui-concurrent ok
               {:count @counter :total total
                :ms (- (System/currentTimeMillis) t0)})))))

(defn- row-editor-preedit-guard
  "While an IME preedit composition is active, Return must not apply
  smart-enter -- setting the atom directly proves the handlers yield
  without needing a real IME (the real preedit-changed wiring is a human
  smoke step)."
  [ed]
  (go-row :editor-preedit-guard check
    (on-gtk (editor/set-text! ed "(defn f [x]")
            (g/with-iters* (:buf ed) 11 11 (fn [a _] (g/gtk-text-buffer-place-cursor (:buf ed) a)))
            (reset! (:preedit-active? ed) true)
            (let [result (editor/handle-return! ed)
                  text (editor/editor-text ed)]
              (reset! (:preedit-active? ed) false)
              (check :editor-preedit-guard (and (nil? result) (= "(defn f [x]" text))
                     {:result result :text text})))))

(defn- row-editor-tick-skips-unchanged
  "tick! must not copy and compare the whole buffer every 20ms when
  nothing changed. dirty-check-count only bumps on an actual recompute, so
  it must stay flat across ~200ms (10 ticks) of idle after a settled edit,
  then bump again once a real edit happens. dirty-check-count is a
  monotonic counter for the whole selftest run (earlier rows' own edits
  already pushed it well above 0), so the first poll compares against a
  pre-edit snapshot taken JUST before this row's own set-text! -- not
  pos?, which would already be true from those earlier rows and so
  wouldn't actually wait for anything."
  [ed]
  (go-row :editor-tick-skips-unchanged check
    (let [pre-edit (on-gtk (let [n @(:dirty-check-count ed)]
                             (editor/set-text! ed "abc")
                             n))
          recomputed-once? (await-until 500 20 #(> @(:dirty-check-count ed) pre-edit))
          baseline @(:dirty-check-count ed)
          _ (<! (timeout 200))
          after-idle (on-gtk (let [n @(:dirty-check-count ed)]
                               (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "d" -1 1)
                               n))
          recomputed-after-edit? (await-until 500 20 #(> @(:dirty-check-count ed) after-idle))]
      (check :editor-tick-skips-unchanged
             (and recomputed-once? (= baseline after-idle) recomputed-after-edit?)
             {:pre-edit pre-edit :recomputed-once recomputed-once? :baseline baseline
              :after-idle after-idle :after-edit @(:dirty-check-count ed)}))))

;; --- handle-key! dispatch rows --------------------------------------
;; Key values from gdkkeysyms.h -- editor.clj's own copy is private, same pattern
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
  (go-row :edit-auto-pair check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "")
              (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (let [handled (editor/handle-key! ed KEY-PARENLEFT 0)
                    text (editor/editor-text ed)
                    caret (g/cursor-offset buf)]
                (check :edit-auto-pair (and (= 1 handled) (= "()" text) (= 1 caret))
                       {:handled handled :text text :caret caret}))))))

(defn- row-edit-wrap-selection [ed]
  (go-row :edit-wrap-selection check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "abc")
              (g/with-iters* buf 0 3 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
              (let [handled (editor/handle-key! ed KEY-BRACKETLEFT 0)
                    text (editor/editor-text ed)]
                (check :edit-wrap-selection (and (= 1 handled) (= "[abc]" text))
                       {:handled handled :text text}))))))

(defn- row-edit-type-over [ed]
  (go-row :edit-type-over check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "()")
              (g/with-iters* buf 1 1 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (let [handled (editor/handle-key! ed KEY-PARENRIGHT 0)
                    text (editor/editor-text ed)
                    caret (g/cursor-offset buf)]
                (check :edit-type-over (and (= 1 handled) (= "()" text) (= 2 caret))
                       {:handled handled :text text :caret caret}))))))

(defn- row-edit-backspace-pair [ed]
  (go-row :edit-backspace-pair check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "()")
              (g/with-iters* buf 1 1 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (let [handled (editor/handle-key! ed KEY-BACKSPACE 0)
                    text (editor/editor-text ed)]
                (check :edit-backspace-pair (and (= 1 handled) (= "" text))
                       {:handled handled :text text}))))))

(defn- row-edit-backspace-plain [ed]
  (go-row :edit-backspace-plain check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "ab")
              (g/with-iters* buf 2 2 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (let [handled (editor/handle-key! ed KEY-BACKSPACE 0)
                    text (editor/editor-text ed)]
                (check :edit-backspace-plain (and (= 0 handled) (= "ab" text))
                       {:handled handled :text text}))))))

(defn- row-edit-tab-selection [ed]
  (go-row :edit-tab-selection check
    (on-gtk (let [buf (:buf ed)]
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
                       {:h1 h1 :text1 text1 :sel1 sel1 :h2 h2 :text2 text2 :sel2 [sel-a sel-b]}))))))

(defn- row-edit-shift-tab [ed]
  (go-row :edit-shift-tab check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "  ab\n  cd")
              (g/with-iters* buf 0 9 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
              (let [handled (editor/handle-key! ed KEY-ISO-LEFT-TAB 0)
                    text (editor/editor-text ed)]
                (check :edit-shift-tab (and (= 1 handled) (= "ab\ncd" text))
                       {:handled handled :text text}))))))

(defn- row-edit-shift-tab-unindented
  "edit/unindent returns nil when no spanned line has leading whitespace.
  handle-key! must not return 0 then, which would hand
  ISO_Left_Tab to GtkTextView's own key handler, which commits a literal
  \"\\t\" over the selection (gtk/gtktextview.c at the 4.22.4 tag, the
  Tab/KP_Tab/ISO_Left_Tab branch). Shift+Tab must be consumed (1) and
  leave the text alone."
  [ed]
  (go-row :edit-shift-tab-unindented check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "(a)\n(b)")
              (g/with-iters* buf 0 7 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
              (let [handled (editor/handle-key! ed KEY-ISO-LEFT-TAB 0)
                    text (editor/editor-text ed)]
                (check :edit-shift-tab-unindented (and (= 1 handled) (= "(a)\n(b)" text))
                       {:handled handled :text text}))))))

(defn- row-edit-toggle-comment
  "The selection is built BACKWARD -- with-iters* buf 0 7
  passes offset 0 first to gtk_text_buffer_select_range, so the insert
  mark (the FIRST iter argument) lands at 0, the LOW end, same as
  (edit/toggle-comment {:text \"(+ 1 2)\" :caret 0
  :sel-start 0 :sel-end 7}) => {:edits [[0 0 \"; \"]], :caret 2, :sel [2
  9]}. apply-op! must put the insert mark at 2 (where toggle-comment's own
  :caret says), not always at the selection's high end -- so this row
  reads back g/cursor-offset, not just the text, after each toggle."
  [ed]
  (go-row :edit-toggle-comment check
    (on-gtk (let [buf (:buf ed)]
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
                        :h2 h2 :text2 text2 :caret2 caret2 :sel2 sel2}))))))

(defn- row-edit-toggle-comment-forward
  "The forward-direction counterpart: the selection is built
  FORWARD this time (with-iters* buf 7 0 -- offset 7 passed first, so the
  insert mark lands at 7, the HIGH end, matching a normal left-to-right
  drag). toggle-comment's own :caret then equals the selection's high end
  (sel-b), which is what the pre-fix apply-op! always assumed -- so this
  case passed even with the bug. Kept as the regression check that the fix
  (picking the iter that matches :caret, not always sel-b) didn't break
  the common case."
  [ed]
  (go-row :edit-toggle-comment-forward check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "(+ 1 2)")
              (g/with-iters* buf 7 0 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
              (let [handled (editor/handle-key! ed KEY-SLASH g/CONTROL-MASK)
                    text (editor/editor-text ed)
                    caret (g/cursor-offset buf)
                    sel (g/selection-offsets buf)]
                (check :edit-toggle-comment-forward
                       (and (= 1 handled) (= "; (+ 1 2)" text) (= 9 caret) (= [2 9] sel))
                       {:handled handled :text text :caret caret :sel sel}))))))

(defn- row-edit-format-all [ed]
  (go-row :edit-format-all check
    (on-gtk (let [buf (:buf ed)
                  original "(defn f [x]\n(inc x))"]
              (editor/set-text! ed original)
              (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (let [handled (editor/handle-key! ed KEY-F (bit-or g/CONTROL-MASK g/SHIFT-MASK))
                    text (editor/editor-text ed)]
                (g/gtk-text-buffer-undo buf)
                (let [undone (editor/editor-text ed)]
                  (check :edit-format-all
                         (and (= 1 handled) (= "(defn f [x]\n  (inc x))" text) (= original undone))
                         {:handled handled :text text :undone undone})))))))

(defn- row-edit-format-keeps-caret-in-view
  "Format All on a long buffer with the caret near its end and the view
  scrolled to the top: the caret stays right before the same (inc x) (its
  old offset would now sit elsewhere, since every line gains an indent),
  and the view scrolls so the caret is on screen."
  [ed]
  (go-row :edit-format-keeps-caret-in-view check
    (let [buf (:buf ed)
          text (str "(defn f [x]\n" (apply str (repeat 398 "(inc x)\n")) "(dec x))")
          caret (str/last-index-of text "(dec")
          _ (on-gtk (editor/set-text! ed text)
                    (g/with-iters* buf caret caret (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
          _ (<! (timeout 200))
          top? (on-gtk (let [adj (g/gtk-scrollable-get-vadjustment (:view ed))]
                         (g/gtk-adjustment-set-value adj 0.0)
                         (zero? (g/gtk-adjustment-get-value adj))))
          _ (<! (timeout 100))
          handled (on-gtk (editor/handle-key! ed KEY-F (bit-or g/CONTROL-MASK g/SHIFT-MASK)))
          visible? (await-until 5000 20
                                #(let [[_ y _ h] (g/caret-window-rect (:view ed))]
                                   (and (>= y 0) (<= (+ y h) (g/gtk-widget-get-height (:view ed))))))
          [after at] (on-gtk (let [t (editor/editor-text ed) c (g/cursor-offset buf)]
                               [t (subs t c (min (count t) (+ c 4)))]))]
      (on-gtk (g/gtk-text-buffer-undo buf)
              (check :edit-format-keeps-caret-in-view
                     (and top? (= 1 handled) (not= text after) (= "(dec" at) visible?)
                     {:top top? :handled handled :at at :visible visible?
                      :rect (g/caret-window-rect (:view ed)) :height (g/gtk-widget-get-height (:view ed))
                      :adj (g/gtk-adjustment-get-value (g/gtk-scrollable-get-vadjustment (:view ed)))})))))

(defn- row-edit-format-selection [ed]
  (go-row :edit-format-selection check
    (on-gtk (let [buf (:buf ed)
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
                         {:handled handled :text text :undone undone})))))))

(def ^:private os-name (System/getProperty "os.name"))
(def ^:private mac? (= "Mac OS X" os-name))

(defn- row-edit-format-selection-macos-cmd-option-f
  "Bug fix (Cmd+Option+F did nothing in the built macOS app): the MEASURED
  macOS triple for Cmd+Option+F, from a pid-driven cgevent probe against
  this build (not assumed) -- keyval 0x8f6 (GDK_KEY_function, the florin
  sign Option+F types on a US keyboard, gdkkeysyms.h:1143), hardware
  keycode 3 (kVK_ANSI_F, macOS SDK Events.h:200), state META-MASK|ALT-MASK
  (Cmd reports as META on GDK's macOS backend -- the keymap's :cmd
  reads it; the probe showed no CONTROL bit). Calls handle-key!'s 4-arg
  arity so physical-key's hardware-keycode fallback has a real
  keycode to match -- this row FAILS on the pre-fix code, where the
  dispatch only ever compared keyval against #{KEY-F KEY-L}."
  [ed]
  (go-row :edit-format-selection-macos-cmd-option-f check
    (on-gtk (let [buf (:buf ed)
                  original "(defn f [x]\n(inc x))"]
              (editor/set-text! ed original)
              ;; offset 15 is inside "(inc x))", line 2, same as row-edit-format-selection.
              (g/with-iters* buf 15 15 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (let [handled (editor/handle-key! ed 0x8f6 3 (bit-or g/META-MASK g/ALT-MASK))
                    text (editor/editor-text ed)]
                (if mac?
                  (do
                    (g/gtk-text-buffer-undo buf)
                    (let [undone (editor/editor-text ed)]
                      (check :edit-format-selection-macos-cmd-option-f
                             (and (= 1 handled) (= "(defn f [x]\n  (inc x))" text) (= original undone))
                             {:handled handled :text text :undone undone})))
                  ;; Off macOS the keycode fallback is off (keycode 37 is
                  ;; Control_L on X11): the chord must not format.
                  (check :edit-format-selection-macos-cmd-option-f
                         (and (= 0 handled) (= original text))
                         {:handled handled :text text :os os-name})))))))

(defn- row-edit-format-selection-macos-cmd-option-l
  "Same bug, the L chord: MEASURED macOS triple for Cmd+Option+L -- keyval
  0xac (GDK_KEY_notsign, the ¬ Option+L types, gdkkeysyms.h:490), hardware
  keycode 37 (kVK_ANSI_L, macOS SDK Events.h:232), state
  META-MASK|ALT-MASK, same as the F row above."
  [ed]
  (go-row :edit-format-selection-macos-cmd-option-l check
    (on-gtk (let [buf (:buf ed)
                  original "(defn f [x]\n(inc x))"]
              (editor/set-text! ed original)
              (g/with-iters* buf 15 15 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (let [handled (editor/handle-key! ed 0xac 37 (bit-or g/META-MASK g/ALT-MASK))
                    text (editor/editor-text ed)]
                (if mac?
                  (do
                    (g/gtk-text-buffer-undo buf)
                    (let [undone (editor/editor-text ed)]
                      (check :edit-format-selection-macos-cmd-option-l
                             (and (= 1 handled) (= "(defn f [x]\n  (inc x))" text) (= original undone))
                             {:handled handled :text text :undone undone})))
                  ;; Off macOS the keycode fallback is off (keycode 37 is
                  ;; Control_L on X11): the chord must not format.
                  (check :edit-format-selection-macos-cmd-option-l
                         (and (= 0 handled) (= original text))
                         {:handled handled :text text :os os-name})))))))

(defn- row-edit-redo-y [ed]
  (go-row :edit-redo-y check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "")
              (g/gtk-text-buffer-insert-interactive-at-cursor buf "x" -1 1)
              (let [typed (editor/editor-text ed)]
                (g/gtk-text-buffer-undo buf)
                (let [undone (editor/editor-text ed)
                      handled (editor/handle-key! ed KEY-Y g/CONTROL-MASK)
                      redone (editor/editor-text ed)]
                  (check :edit-redo-y (and (= "x" typed) (= "" undone) (= 1 handled) (= "x" redone))
                         {:typed typed :undone undone :handled handled :redone redone})))))))

(defn- row-edit-preedit-yields [ed]
  (go-row :edit-preedit-yields check
    (on-gtk (editor/set-text! ed "abc")
            (reset! (:preedit-active? ed) true)
            (let [handled (editor/handle-key! ed KEY-PARENLEFT 0)
                  text (editor/editor-text ed)]
              (reset! (:preedit-active? ed) false)
              (check :edit-preedit-yields (and (= 0 handled) (= "abc" text))
                     {:handled handled :text text})))))

(defn- with-fresh-editor
  "Builds a fresh editor in its own transient GtkWindow, shows it, calls
  (f ed) from the calling go block, then closes the window. Every GTK call
  runs on the GTK thread; f may park."
  [f]
  (let [{:keys [win ed]} (on-gtk (let [win (g/gtk-window-new)
                                       ed (editor/make-editor)]
                                   (g/gtk-window-set-default-size win 500 400)
                                   (g/gtk-window-set-child win (:scroll ed))
                                   (g/gtk-window-present win)
                                   {:win win :ed ed}))]
    (try (f ed)
         (finally (on-gtk (g/gtk-window-close win))))))

(defn- row-read-only-refuses-edits
  "A read-only editor changes nothing for typing, Return, an opening
  bracket, Backspace, Tab, Cmd+/ or Cmd+Y, never marks itself dirty, and
  edits again once read-only is off."
  [_ed]
  (go-row :read-only-refuses-edits check
    (with-fresh-editor
      (fn [ed]
        (let [text "(defn f [x]\n  x)\n"
              r (on-gtk
                 (let [buf (:buf ed)]
                   (editor/set-text! ed text)
                   (editor/set-read-only! ed true)
                   (g/with-iters* buf 4 4 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                   (g/gtk-text-buffer-insert-interactive-at-cursor buf "zz" -1 1)
                   (editor/dispatch-key! ed 0xff0d 0)
                   (editor/dispatch-key! ed 0x028 0)
                   (editor/dispatch-key! ed 0xff08 0)
                   (editor/dispatch-key! ed 0xff09 0)
                   (editor/dispatch-key! ed 0x02f g/CONTROL-MASK)
                   (editor/dispatch-key! ed 0x079 g/CONTROL-MASK)
                   (let [after (editor/editor-text ed)
                         dirty (editor/dirty? ed)]
                     (editor/set-read-only! ed false)
                     (g/gtk-text-buffer-insert-interactive-at-cursor buf "q" -1 1)
                     {:after after :dirty dirty :editable-again (editor/editor-text ed)})))]
          (on-gtk (check :read-only-refuses-edits
                         (and (= text (:after r)) (false? (:dirty r))
                              (not= text (:editable-again r)))
                         r)))))))

(defn- big-text
  "n lines, each with a string and a keyword, so every line gets tags."
  [n]
  (apply str (map (fn [i] (str "(defn f" i " [x] (str \"s" i "\" :k" i " x))\n")) (range n))))

(defn- row-paint-async-keeps-gtk-responsive
  "paint-async! on 8,000 lines finishes within 10 s, colours a string on the
  first and the last line, and while it runs the GTK thread answers an
  on-gtk round trip in under 150 ms every time (estimate: one 2,000-span
  chunk is ~8 ms by the measured 4 µs a span; 150 ms leaves room for GTK's
  own layout passes). The 20 ms tick never repaints a read-only editor.
  The timing starts once GTK has laid out the new text: on the dev Mac the
  first frame after set-text! of 8,000 lines holds the GTK thread 101-167 ms
  with no paint at all, and timing from set-text! charged that to the paint
  (the row missed on most runs). Measured after that settle,
  the paint's own worst gap was 86-94 ms. Under CPU emulation (JONQUIL_SELFTEST_EMULATED, set by jolt linux:selftest
  for another arch than the host's) the budget is 5x: x86_64 emulated on
  arm64 measured 194 ms and 481 ms worst cases, which time the emulator."
  [_ed]
  (go-row :paint-async-keeps-gtk-responsive check
    (with-fresh-editor
      (fn [ed]
        (let [budget-ms (if (System/getenv "JONQUIL_SELFTEST_EMULATED") 750.0 150.0)
              text (big-text 8000)
              last-line-start (inc (str/last-index-of text "\n" (- (count text) 2)))
              first-str (inc (str/index-of text "\""))
              last-str (inc (str/index-of text "\"" last-line-start))
              string-tag (get (:tags ed) :string)
              _ (on-gtk (editor/set-read-only! ed true)
                        (editor/set-text! ed text))
              ;; GTK's own layout of the new text: round trips until one
              ;; comes back under 20 ms (2 s at most), before any paint.
              _ (loop [n 0]
                  (let [t0 (System/nanoTime)
                        _ (on-gtk nil)]
                    (when (and (> (/ (- (System/nanoTime) t0) 1e6) 20) (< n 200))
                      (<! (timeout 10))
                      (recur (inc n)))))
              _ (on-gtk (editor/paint-async! ed))
              start (System/nanoTime)
              worst (loop [worst 0]
                      (if (or (:done? @(:paint ed)) (> (- (System/nanoTime) start) 10e9))
                        worst
                        (let [t0 (System/nanoTime)
                              _ (on-gtk nil)
                              ms (/ (- (System/nanoTime) t0) 1e6)]
                          (<! (timeout 10))
                          (recur (max worst ms)))))
              [a b] (on-gtk [(g/has-tag-at? (:buf ed) first-str string-tag)
                             (g/has-tag-at? (:buf ed) last-str string-tag)])]
          (on-gtk (check :paint-async-keeps-gtk-responsive
                         (and (:done? @(:paint ed)) a b (< worst budget-ms))
                         {:done (:done? @(:paint ed)) :first a :last b :worst-ms worst
                          :budget-ms budget-ms})))))))

(defn- row-paint-async-superseded
  "A paint still in flight never lands on newer text: paint text A (all
  strings), then at once set-text! B (all keywords) and paint again. When
  the second paint is done, B carries keyword tags and no string tag.
  Failed once in about 20 runs (2026-11-02: done at the right gen, yet no
  keyword tag at offset 1); the detail also counts the keyword tags over
  B, lists every tag at offset 1 and shows (:paint ed), so a repeat says
  whether the tags never landed or were removed."
  [_ed]
  (go-row :paint-async-superseded check
    (with-fresh-editor
      (fn [ed]
        (let [a-text (apply str (repeat 4000 "\"aaaa\"\n"))
              b-text (apply str (repeat 400 ":bbbb\n"))
              string-tag (get (:tags ed) :string)
              keyword-tag (get (:tags ed) :keyword)
              gen (on-gtk (editor/set-read-only! ed true)
                          (editor/set-text! ed a-text)
                          (editor/paint-async! ed)
                          (editor/set-text! ed b-text)
                          (editor/paint-async! ed)
                          (:gen @(:paint ed)))
              done? (await-until 10000 20 #(:done? @(:paint ed)))
              _ (<! (timeout 200))
              r (on-gtk {:gen-now (:gen @(:paint ed))
                         :kw (g/has-tag-at? (:buf ed) 1 keyword-tag)
                         :str-anywhere (some (fn [o] (g/has-tag-at? (:buf ed) o string-tag))
                                             (range 0 (count b-text) 97))
                         :kw-n (count (filter (fn [o] (g/has-tag-at? (:buf ed) o keyword-tag)) (range 1 (count b-text) 6)))
                         :paint (dissoc @(:paint ed) :profile)
                         :len (g/gtk-text-buffer-get-char-count (:buf ed))
                         :tags-at-1 (vec (keep (fn [[k t]] (when (g/has-tag-at? (:buf ed) 1 t) k)) (:tags ed)))})]
          (on-gtk (check :paint-async-superseded
                         (and done? (= gen (:gen-now r)) (:kw r) (not (:str-anywhere r)))
                         (assoc r :done done? :gen gen))))))))

(defn- row-read-only-off-supersedes-paint
  "set-read-only! supersedes any paint-async! still in flight
  (its :gen moves on), the same way set-text! does -- without that,
  toggling read-only off mid-paint re-permits apply-op! edits, and a
  stale chunk from the old paint can still land afterward at offsets
  computed against text that no longer exists. Paints a large all-strings
  text read-only, then within the SAME on-gtk turn (so no paint chunk can
  have run yet) flips read-only off and replaces the buffer directly --
  not through set-text!, so only set-read-only!'s own gen bump is under
  test -- with a short plain text. No string tag may ever land on it. The
  row detects a stale paint through the moved generation and the absence
  of any string tag on the new text."
  [_ed]
  (go-row :read-only-off-supersedes-paint check
    (with-fresh-editor
      (fn [ed]
        (let [a-text (apply str (repeat 4000 "\"aaaa\"\n"))
              short-text "x y z\n"
              string-tag (get (:tags ed) :string)
              old-gen (on-gtk (editor/set-read-only! ed true)
                              (editor/set-text! ed a-text)
                              (editor/paint-async! ed)
                              (let [g (:gen @(:paint ed))]
                                (editor/set-read-only! ed false)
                                (g/gtk-text-buffer-set-text (:buf ed) short-text -1)
                                g))
              _ (<! (timeout 1000))
              r (on-gtk {:gen-now (:gen @(:paint ed))
                         :text (editor/editor-text ed)
                         :str-anywhere (some (fn [o] (g/has-tag-at? (:buf ed) o string-tag))
                                             (range 0 (count short-text)))})]
          (on-gtk (check :read-only-off-supersedes-paint
                         (and (not= old-gen (:gen-now r))
                              (= short-text (:text r))
                              (not (:str-anywhere r)))
                         (assoc r :old-gen old-gen))))))))

(defn- row-paint-async-stops-on-destroy
  "A paint still in flight must stop touching the buffer once its own
  window is destroyed: a transient window
  (the source panel's own, F12's jump target) can close mid-paint, and
  every chunk after that was still landing on a buffer whose widget was
  already gone. Builds its own editor in its own window (not
  with-fresh-editor, which only closes the window AFTER its callback
  returns -- here the window has to close WHILE the paint is still
  running), reads/paints 8,000 lines, closes the window at once, then
  waits up to 5 s and checks the paint never finished and :destroyed?
  went true. The wait is measured, not a flat 1 s: mutation-checked
  (editor/paint-async!'s current? reverted to drop the destroyed? check)
  with this same 8,000-line text, (:done? @(:paint ed)) went true, but
  only after ~1.8 s -- every one of the ~24,000 no-op spans against the
  destroyed buffer still logs its own Gtk-CRITICAL line, and flushing
  that volume to the suite's own log file is most of the delay. 5 s
  leaves comfortable margin over that measurement; await-until returns
  as soon as :done? flips, so the fixed code (where it never does) is
  the only path that pays the full 5 s.
  Mutation check: without that gate, this row FAILs with :done? true."
  [_ed]
  (go-row :paint-async-stops-on-destroy check
    (let [{:keys [win ed]} (on-gtk (let [win (g/gtk-window-new)
                                         ed (editor/make-editor)]
                                     (g/gtk-window-set-default-size win 500 400)
                                     (g/gtk-window-set-child win (:scroll ed))
                                     (g/gtk-window-present win)
                                     {:win win :ed ed}))
          text (big-text 8000)]
      (on-gtk (editor/set-read-only! ed true)
              (editor/set-text! ed text)
              (editor/paint-async! ed))
      (ga/close-window-bounded! win (:destroyed? ed) 2000 (fn [] nil))
      (let [done? (await-until 5000 100 #(:done? @(:paint ed)))
            destroyed? @(:destroyed? ed)]
        (check :paint-async-stops-on-destroy
               (and (false? done?) (true? destroyed?))
               {:done done? :destroyed destroyed?})))))

(defn- row-editor-dirty-roundtrip [ed]
  (go-row :editor-dirty-roundtrip check
    (let [buf (:buf ed)
          text "(+ 1 2)"
          end (count text)
          place-end! (fn [] (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
          r (on-gtk
             (editor/set-text! ed text)
             (place-end!)
             (g/gtk-text-buffer-insert-interactive-at-cursor buf "x" -1 1)
             (let [typed (editor/dirty? ed)]
               (g/with-iters* buf end (inc end) (fn [a b] (g/gtk-text-buffer-delete-interactive buf a b 1)))
               (let [deleted (editor/dirty? ed)
                     text-deleted (editor/editor-text ed)]
                 (place-end!)
                 (g/gtk-text-buffer-insert-interactive-at-cursor buf "y" -1 1)
                 (let [retyped (editor/dirty? ed)]
                   (editor/mark-saved! ed)
                   {:typed typed :deleted deleted :text-deleted text-deleted
                    :retyped retyped :saved (editor/dirty? ed)}))))]
      (on-gtk (check :editor-dirty-roundtrip
                     (and (true? (:typed r)) (false? (:deleted r))
                          (true? (:retyped r)) (false? (:saved r)))
                     r)))))

;; --- the incremental repaint ---------------------------

(defn- expected-tags
  "{offset #{style-key}} at each offset in offs (a sorted set): the per-char
  model of a full repaint of text under profile, built only where sampled."
  [profile text offs]
  (reduce (fn [m [k s e]] (reduce (fn [m o] (update m o conj k)) m (subseq offs >= s < e)))
          (zipmap offs (repeat #{}))
          (paint/paint-plan profile text)))

(defn- tag-mismatches
  "Up to 5 [offset key expected?] where a tag in (:tags ed) disagrees with
  expected. Runs on the GTK thread."
  [ed expected]
  (let [buf (:buf ed)]
    (vec (take 5 (for [[o ks] (sort expected)
                       [k tag] (:tags ed)
                       :when (not= (contains? ks k) (g/has-tag-at? buf o tag))]
                   [o k (contains? ks k)])))))

(defn- compare-with-full
  "Parks. Compares ed's tags with a full repaint of its current text, at the
  offsets (offs-fn text) returns. The paint plan is computed off the GTK thread.
  Returns tag-mismatches' vector, empty when they agree."
  [ed offs-fn]
  (let [[text profile] (on-gtk [(editor/editor-text ed) @(:profile ed)])
        expected (expected-tags profile text (into (sorted-set) (offs-fn text)))]
    (on-gtk (tag-mismatches ed expected))))

(defn- sampled-offsets
  "2,000 seeded random offsets into text plus every offset within 300 chars
  of caret."
  [caret]
  (fn [text]
    (let [n (count text)
          r (java.util.Random. 4)]
      (concat (repeatedly 2000 (fn [] (.nextInt r n)))
              (range (max 0 (- caret 300)) (min n (+ caret 300)))))))

(defn- painted-settled?
  "The repaint has caught up with the buffer: :painted holds its current
  text. Runs on the GTK thread."
  [ed]
  (let [p @(:painted ed)]
    (and p (= (:text p) (editor/editor-text ed)))))

(defn- line-start [text line]
  (loop [pos 0 k 0]
    (if (= k line) pos (recur (inc (str/index-of text "\n" pos)) (inc k)))))

(def ^:private clojure-sample
  (apply str (map (fn [i]
                    (str ";; block " i "\n"
                         "(defn greet" i "\n  \"Says hello.\"\n  [name & {:keys [loud?]}]\n"
                         "  (let [s (str \"Hello, \" name \\!)]\n"
                         "    (if loud? (str/upper-case s) s)))\n\n"
                         "(def table" i " {:a 1 :b [2 3] :c #{4 5} :d 'sym})\n\n"))
                  (range 10))))

(defn- row-incremental-matches-full
  "After each of 12 scripted edits, once the repaint settles, every tag in
  (:tags ed) at every offset matches a full repaint of the new text. The
  edits cover a char inside a symbol, ( typed just before an existing (, a
  \" opening a string, its deletion, a newline, a 200-char paste, an undo,
  a deletion across brackets, a ; commenting out a line, an opened #{, a
  deletion at offset 0 and an unclosed form typed at the end. No full
  repaint may run during the edits (the :full-gen paint-async! records stays
  put), so the diff path did the work."
  [_ed]
  (go-row :incremental-matches-full check
    (with-fresh-editor
      (fn [ed]
        (let [buf (:buf ed)
              text clojure-sample
              at (fn [needle] (str/index-of (editor/editor-text ed) needle))
              ins! (fn [o s] (g/with-iters* buf o o (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                     (g/gtk-text-buffer-insert-interactive-at-cursor buf s -1 1))
              del! (fn [a b] (g/with-iters* buf a b (fn [i j] (g/gtk-text-buffer-delete-interactive buf i j 1))))
              paste (apply str (repeat 8 "(f :k \"s\" [1 2])\n"))
              edits [[:symbol-char (fn [] (ins! (+ 3 (at "greet3")) "x"))]
                     [:paren-before-paren (fn [] (ins! (at "(if loud?") "("))]
                     [:open-string (fn [] (ins! (line-start (editor/editor-text ed) 3) "\""))]
                     [:delete-quote (fn [] (let [o (line-start (editor/editor-text ed) 3)] (del! o (inc o))))]
                     [:newline (fn [] (ins! (at "[2 3]") "\n"))]
                     [:paste (fn [] (ins! (at ";; block 5") (subs (str paste paste) 0 200)))]
                     [:undo (fn [] (g/gtk-text-buffer-undo buf))]
                     [:delete-across-brackets (fn [] (let [o (at "[name & {")] (del! o (+ o 30))))]
                     [:comment-out (fn [] (ins! (at "(def table7") ";"))]
                     [:open-set (fn [] (ins! (at ":d 'sym") "#{"))]
                     [:delete-first (fn [] (del! 0 1))]
                     [:unclosed-at-end (fn [] (ins! (count (editor/editor-text ed)) "(foo \"bar"))]]
              _ (on-gtk (editor/set-text! ed text) (editor/paint-async! ed))
              ready? (await-until 5000 20 #(painted-settled? ed))
              full-gen (on-gtk (:full-gen @(:paint ed)))
              results (loop [es edits acc []]
                        (if-let [[id f] (first es)]
                          (do (on-gtk (f))
                              (let [settled? (await-until 5000 20 #(painted-settled? ed))
                                    bad (compare-with-full ed (fn [t] (range (count t))))]
                                (recur (rest es) (conj acc {:edit id :settled settled? :mismatches bad}))))
                          acc))
              failed (remove (fn [r] (and (:settled r) (empty? (:mismatches r)))) results)
              full-gen-after (on-gtk (:full-gen @(:paint ed)))]
          (check :incremental-matches-full (and ready? (empty? failed) (= full-gen full-gen-after))
                 {:ready ready? :edits (count results) :failed (vec failed)
                  :full-gen full-gen :full-gen-after full-gen-after}))))))

(defn- row-incremental-supersede
  "30 chars typed 150 ms apart into a 20,000-line buffer. Each lands after
  its predecessor's 60 ms debounce but while that tick's plan future
  (~650 ms) is still in flight, so at least one tick result is dropped
  (:repaint-drops). Once settled, the tags match a full repaint at 2,000
  sampled offsets and around the caret."
  [_ed]
  (go-row :incremental-supersede check
    (with-fresh-editor
      (fn [ed]
        (let [buf (:buf ed)
              text (big-text 20000)
              typed "(let [a \"b\"] (str a :k 12)) ;x"
              _ (on-gtk (editor/set-text! ed text) (editor/paint-async! ed))
              ready? (await-until 60000 50 #(painted-settled? ed))
              start (line-start text 10)
              drops0 (on-gtk (g/with-iters* buf start start (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                             @(:repaint-drops ed))
              _ (loop [cs (seq typed)]
                  (when-let [c (first cs)]
                    (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor buf (str c) -1 1))
                    (<! (timeout 150))
                    (recur (rest cs))))
              settled? (await-until 60000 50 #(painted-settled? ed))
              caret (on-gtk (g/cursor-offset buf))
              bad (compare-with-full ed (sampled-offsets caret))
              drops (- (on-gtk @(:repaint-drops ed)) drops0)]
          (check :incremental-supersede (and ready? settled? (pos? drops) (empty? bad))
                 {:ready ready? :settled settled? :typed (count typed) :drops drops
                  :mismatches bad}))))))

(defn- row-incremental-open-string
  "A \" typed at line 10 of a 2,000-line buffer turns what follows into
  strings. Within 200 ms of the first chunk landing (the quote itself
  coloured) lines 10 to 40 match a full repaint's :string tag, and once
  settled every tag matches at the sampled offsets."
  [_ed]
  (go-row :incremental-open-string check
    (with-fresh-editor
      (fn [ed]
        (let [buf (:buf ed)
              text (big-text 2000)
              string-tag (get (:tags ed) :string)
              _ (on-gtk (editor/set-text! ed text) (editor/paint-async! ed))
              ready? (await-until 30000 20 #(painted-settled? ed))
              o (line-start text 10)
              new-text (str (subs text 0 o) "\"" (subs text o))
              visible (into (sorted-set) (range o (line-start new-text 40)))
              want (expected-tags @(:profile ed) new-text visible)
              visible-right? (fn [] (every? (fn [[p ks]] (= (contains? ks :string) (g/has-tag-at? buf p string-tag)))
                                            want))
              _ (on-gtk (g/with-iters* buf o o (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                        (g/gtk-text-buffer-insert-interactive-at-cursor buf "\"" -1 1))
              first? (await-until 10000 5 #(g/has-tag-at? buf o string-tag))
              t1 (System/currentTimeMillis)
              visible? (await-until 10000 5 visible-right?)
              ms (- (System/currentTimeMillis) t1)
              settled? (await-until 30000 20 #(painted-settled? ed))
              bad (compare-with-full ed (sampled-offsets (inc o)))]
          (check :incremental-open-string
                 (and ready? first? visible? (<= ms 200) settled? (empty? bad))
                 {:ready ready? :first-chunk first? :visible visible? :visible-ms ms
                  :settled settled? :mismatches bad}))))))

(defn- row-paint-async-drops-after-edit
  "An edit while a paint-async! is in flight drops the rest of that paint:
  its spans carry the old text's offsets. Paints 8,000 lines with the
  caret at 0, waits for the first chunk, then inserts 3 chars at offset 0
  in the next turn. 40 ms later, before the edit's 60 ms debounce can
  start the tick, no :string tag may sit on an offset that is a string
  only in the old text: GTK moved the tags already applied along with
  their chars, so a :string there can only come from a stale chunk. Once
  settled, the tags match a full repaint at the sampled offsets."
  [_ed]
  (go-row :paint-async-drops-after-edit check
    (with-fresh-editor
      (fn [ed]
        (let [buf (:buf ed)
              text (big-text 8000)
              new-text (str "xx " text)
              string-tag (get (:tags ed) :string)
              str-offs (fn [t] (set (mapcat (fn [[k s e]] (when (= k :string) (range s e)))
                                            (paint/paint-plan t))))
              old-only (sort (remove (str-offs new-text) (str-offs text)))
              _ (on-gtk (editor/set-text! ed text)
                        (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                        (editor/paint-async! ed))
              first? (await-until 10000 2 #(g/has-tag-at? buf (inc (str/index-of text "\"")) string-tag))
              _ (on-gtk (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-insert buf a "xx " -1))))
              _ (<! (timeout 40))
              stale (on-gtk (vec (take 5 (filter (fn [o] (g/has-tag-at? buf o string-tag)) old-only))))
              settled? (await-until 30000 20 #(painted-settled? ed))
              bad (compare-with-full ed (sampled-offsets 0))]
          (check :paint-async-drops-after-edit
                 (and first? (seq old-only) (empty? stale) settled? (empty? bad))
                 {:first-chunk first? :old-only (count old-only) :stale stale
                  :settled settled? :mismatches bad}))))))


;; --- Parinfer rows --------------------------------------------
;; Each row turns the mode on through editor/set-parinfer-fns! (what
;; jonquil.gtk.window.tabs wires per tab) and makes the edit the way GTK's own
;; commit does: one begin/end-user-action around an insert or delete, so
;; the end-user-action hook runs. The last step of every row turns
;; Parinfer back off, so the other rows are untouched.

(defn- parinfer-on! [ed mode] (editor/set-parinfer-fns! ed (constantly mode) nil))
(defn- parinfer-off! [ed] (editor/set-parinfer-fns! ed (constantly :off) nil))

(defn- type-str! [ed s]
  (let [buf (:buf ed)]
    (g/gtk-text-buffer-begin-user-action buf)
    (g/gtk-text-buffer-insert-interactive-at-cursor buf s -1 1)
    (g/gtk-text-buffer-end-user-action buf)))

(defn- delete-action! [ed a b]
  (let [buf (:buf ed)]
    (g/gtk-text-buffer-begin-user-action buf)
    (g/with-iters* buf a b (fn [i j] (g/gtk-text-buffer-delete buf i j)))
    (g/gtk-text-buffer-end-user-action buf)))

(defn- caret! [ed pos]
  (g/with-iters* (:buf ed) pos pos (fn [a _] (g/gtk-text-buffer-place-cursor (:buf ed) a))))

(defn- row-parinfer-open-paren [ed]
  (go-row :parinfer-open-paren check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (caret! ed 0)
                 (type-str! ed "(")
                 (let [text (editor/editor-text ed)
                       caret (g/cursor-offset (:buf ed))]
                   (check :parinfer-open-paren (and (= "()" text) (= 1 caret))
                          {:text text :caret caret}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-dedent-undo-redo [ed]
  (go-row :parinfer-dedent-undo-redo check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a\n  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)       ; the user dedents b
                 (let [buf (:buf ed)
                       dedented (editor/editor-text ed)
                       _ (g/gtk-text-buffer-undo buf)
                       undone (editor/editor-text ed)
                       _ (g/gtk-text-buffer-redo buf)
                       redone (editor/editor-text ed)]
                   (check :parinfer-dedent-undo-redo
                          (and (= "(a)\nb" dedented)
                               (= "(a\n  b)" undone)
                               (= "(a)\nb" redone))
                          {:dedented dedented :undone undone :redone redone}))
                 (finally (parinfer-off! ed))))))

(defn- row-reload-text-undoable [ed]
  (go-row :reload-text-undoable check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "(a)\n")
              (caret! ed 4)
              (type-str! ed "x")
              (editor/reload-text! ed "(b)\n")
              (let [text (editor/editor-text ed)
                    clean? (not (editor/dirty? ed))
                    caret (g/cursor-offset buf)
                    _ (g/gtk-text-buffer-undo buf)
                    undone (editor/editor-text ed)
                    dirty? (editor/dirty? ed)
                    _ (g/gtk-text-buffer-undo buf)
                    undone2 (editor/editor-text ed)]
                (check :reload-text-undoable
                       (and (= "(b)\n" text) clean? (= 4 caret)
                            (= "(a)\nx" undone) dirty? (= "(a)\n" undone2))
                       {:text text :clean clean? :caret caret :undone undone :dirty dirty? :undone2 undone2}))))))

(defn- row-reload-text-caret-clamps [ed]
  (go-row :reload-text-caret-clamps check
    (on-gtk (editor/set-text! ed "(defn long-name [])\n")
            (caret! ed 18)
            (editor/reload-text! ed "(a)")
            (let [caret (g/cursor-offset (:buf ed))]
              (check :reload-text-caret-clamps (= 3 caret) {:caret caret})))))

(defn- row-reload-text-parinfer-held-off [ed]
  (go-row :reload-text-parinfer-held-off check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (editor/reload-text! ed "(a\n  b")
                 (let [text (editor/editor-text ed)]
                   (check :reload-text-parinfer-held-off (= "(a\n  b" text) {:text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-mcp-and-reload-unchanged [ed]
  (go-row :parinfer-mcp-and-reload-unchanged check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (editor/apply-op-plain! ed {:edits [[0 0 "(fn [a b]\n     (h a))"]] :caret 0})
                 (let [mcp-text (editor/editor-text ed)]
                   (editor/set-text! ed "(x\n  y)")   ; a reload
                   (let [reload-text (editor/editor-text ed)]
                     (check :parinfer-mcp-and-reload-unchanged
                            (and (= "(fn [a b]\n     (h a))" mcp-text)
                                 (= "(x\n  y)" reload-text))
                            {:mcp mcp-text :reload reload-text})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-unstable-form-only [ed]
  (go-row :parinfer-unstable-form-only check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(fn [a b]\n     (h a))\n(q [c]\n     d)")
                 (caret! ed 19)                 ; just after "a" in (h a)
                 (type-str! ed "x")
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-unstable-form-only
                          (= "(fn [a b]\n    (h ax))\n(q [c]\n     d)" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-off-no-change [ed]
  (go-row :parinfer-off-no-change check
    (on-gtk (editor/set-text! ed "(a\n  b)")
            (caret! ed 5)
            (delete-action! ed 3 5)
            (let [text (editor/editor-text ed)]
              (check :parinfer-off-no-change (= "(a\nb)" text) {:text text})))))

(defn- row-parinfer-too-long-form-says-so [ed]
  (go-row :parinfer-too-long-form-says-so check
    (on-gtk (let [status (atom [])]
              (try (editor/set-parinfer-fns! ed (constantly :smart) (fn [msg] (swap! status conj msg)))
                   (editor/set-text! ed (str "(a\n" (apply str (repeat 301 "  b\n")) ")"))
                   (caret! ed 5)                ; before the first b
                   (type-str! ed "x")
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-too-long-form-says-so
                            (and (= [(str "over 300 lines here, not adjusted")] @status)
                                 (= (str "(a\n  xb\n" (apply str (repeat 300 "  b\n")) ")") text))
                            {:status @status}))
                   (finally (parinfer-off! ed)))))))


(defn- row-parinfer-paren-mode-keeps-pairs [ed]
  (go-row :parinfer-paren-mode-keeps-pairs check
    (on-gtk (try (parinfer-on! ed :paren)
                 (editor/set-text! ed "")
                 (caret! ed 0)
                 ;; Paren mode never balances, so the auto-pair stays on
                 ;; (handle-key! takes the key and inserts the pair).
                 (let [handled (editor/handle-key! ed 0x028 0)
                       text (editor/editor-text ed)]
                   (check :parinfer-paren-mode-keeps-pairs
                          (and (= 1 handled) (= "()" text))
                          {:handled handled :text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-quote-backspace-keeps-pair [ed]
  (go-row :parinfer-quote-backspace-keeps-pair check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a \"\")")
                 (caret! ed 4)
                 ;; Backspace in an empty string pair still removes both
                 ;; quotes: a lone quote would unclose the form.
                 (let [handled (editor/handle-key! ed 0xff08 0)
                       text (editor/editor-text ed)]
                   (check :parinfer-quote-backspace-keeps-pair
                          (and (= 1 handled) (= "(a )" text))
                          {:handled handled :text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-op-keeps-caret [ed]
  (go-row :parinfer-op-keeps-caret check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a\n  b)")
                 (caret! ed 7)
                 ;; Toggle comment through apply-op!: Parinfer moves the
                 ;; closer, and the op's caret must survive it.
                 (editor/do-toggle-comment! ed)
                 (let [text (editor/editor-text ed)
                       caret (g/cursor-offset (:buf ed))]
                   (check :parinfer-op-keeps-caret
                          (and (= "(a)\n;   b)" text) (= (count text) caret))
                          {:text text :caret caret}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-op-keeps-selection [ed]
  (go-row :parinfer-op-keeps-selection check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a\n  b\n  c)")
                 (g/with-iters* (:buf ed) 5 11
                   (fn [i1 i2] (g/gtk-text-buffer-select-range (:buf ed) i1 i2)))
                 ;; Shift+Tab over b and c: the closer moves up, and the
                 ;; selection still covers the two dedented lines, so a
                 ;; second Shift+Tab has nothing left to remove.
                 (editor/do-unindent! ed)
                 (let [text (editor/editor-text ed)
                       [a b] (g/selection-offsets (:buf ed))
                       sel (when (and a b (< a b)) (subs text a b))]
                   (check :parinfer-op-keeps-selection
                          (and (= "(a)\nb\nc" text) (= "b\nc" sel))
                          {:text text :sel sel}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-engine-failure-named-once [ed]
  (go-row :parinfer-engine-failure-named-once check
    (on-gtk (let [errors (atom [])]
              (try (parinfer-on! ed :smart)
                   (editor/set-error-fn! ed (fn [msg] (swap! errors conj msg)))
                   (editor/set-text! ed "(a)")
                   (caret! ed 2)
                   ;; A Jolt shim gap surfaces as an "unhandled" engine
                   ;; result; the hook names it once and the text stays as
                   ;; typed.
                   (with-redefs-fn {#'pe/adjust
                                    (fn [_] {:engine-error "boom"})}
                     #(type-str! ed "x"))
                   (let [after-fail (editor/editor-text ed)]
                     (type-str! ed "(")
                     (check :parinfer-engine-failure-named-once
                            (and (= ["; parinfer: boom"] @errors)
                                 (= "(ax)" after-fail)
                                 (= "(ax())" (editor/editor-text ed)))
                            {:errors @errors :after-fail after-fail
                             :text (editor/editor-text ed)}))
                   (finally (editor/set-error-fn! ed (fn [_]))
                            (parinfer-off! ed)))))))

(defn- row-parinfer-throw-clears-the-prime [ed]
  (go-row :parinfer-throw-clears-the-prime check
    (on-gtk (let [errors (atom [])]
              (try (parinfer-on! ed :smart)
                   (editor/set-error-fn! ed (fn [msg] (swap! errors conj msg)))
                   (editor/set-text! ed "(a)")
                   (caret! ed 2)
                   ;; primes the cache
                   (type-str! ed "x")
                   ;; a throw (not an engine-error result) lands in the
                   ;; hook's catch: it names the error once and drops the
                   ;; cache and the outside span, so the next keystroke
                   ;; starts from scratch
                   (reset! errors [])
                   (with-redefs-fn {#'pe/adjust
                                    (fn [_] (throw (ex-info "kaput" {})))}
                     #(type-str! ed "y"))
                   (let [after (editor/editor-text ed)
                         cache @(:parinfer-prev-cache ed)
                         span @(:parinfer-outside-span ed)]
                     (type-str! ed "(")
                     (check :parinfer-throw-clears-the-prime
                            (and (= ["; parinfer: kaput"] @errors)
                                 (= "(axy)" after)
                                 (nil? cache)
                                 (nil? span)
                                 (= "(axy())" (editor/editor-text ed)))
                            {:errors @errors :after after :cache cache
                             :span span :text (editor/editor-text ed)}))
                   (finally (editor/set-error-fn! ed (fn [_]))
                            (parinfer-off! ed)))))))

(defn- row-editor-cursor-fn-throw-named
  "A registrant of on-cursor-moved! that throws must not break the cursor
  move that called it: the editor reports it through its error fn as
  \"; cursor: <message>\" and leaves its text alone."
  [ed]
  (go-row :editor-cursor-fn-throw-named check
    (on-gtk (let [saved @(:on-cursor-fns ed)
                  errors (atom [])]
              (try
                (editor/set-error-fn! ed (fn [msg] (swap! errors conj msg)))
                (editor/set-text! ed "abc")
                (editor/on-cursor-moved! ed (fn [_] (throw (ex-info "bad" {}))))
                ;; set-text! may notify too; only the caret move may satisfy the check
                (reset! errors [])
                (caret! ed 1)
                (check :editor-cursor-fn-throw-named
                       (and (some #{"; cursor: bad"} @errors)
                            (= "abc" (editor/editor-text ed)))
                       {:errors @errors :text (editor/editor-text ed)})
                (finally
                  (reset! (:on-cursor-fns ed) saved)
                  (editor/set-error-fn! ed (fn [_]))))))))

;; --- Parinfer speedup rows ---------------------
;; Each row asserts the edit's visible result equals what the fallback
;; (cache-less) path computes on copied strings, so a stale cache or a
;; wrong span shows up as a text mismatch, not just a slow path.

(defn- row-parinfer-cache-steady-typing [ed]
  (go-row :parinfer-cache-steady-typing check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (caret! ed 0)
                 (type-str! ed "(")
                 (type-str! ed "a")
                 (type-str! ed "[")
                 (let [text (editor/editor-text ed)
                       cache @(:parinfer-prev-cache ed)]
                   (check :parinfer-cache-steady-typing
                          (and (= "(a[])" text)
                               (= text (:text cache))
                               (= (:gen cache) @(:edit-gen ed)))
                          {:text text :cache-gen (:gen cache) :gen @(:edit-gen ed)}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-cache-invalidated-by-undo [ed]
  (go-row :parinfer-cache-invalidated-by-undo check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a\n  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)          ; -> "(a)\nb", cached
                 (g/gtk-text-buffer-undo (:buf ed))  ; outside the hook
                 (let [stale? (= (:gen @(:parinfer-prev-cache ed)) @(:edit-gen ed))]
                   (caret! ed 5)
                   (delete-action! ed 3 5)        ; must work from the real buffer
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-cache-invalidated-by-undo
                            (and (not stale?) (= "(a)\nb" text))
                            {:was-stale stale? :text text})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-cache-reprimed-by-set-text [ed]
  (go-row :parinfer-cache-reprimed-by-set-text check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (caret! ed 0)
                 (type-str! ed "(")               ; primes the cache
                 (editor/set-text! ed "(x\n  y)") ; a reload re-primes
                 ;; pin the re-prime itself, not just the edit that follows it
                 (let [c @(:parinfer-prev-cache ed)
                       primed? (and (= "(x\n  y)" (:text c))
                                    (= (:gen c) @(:edit-gen ed)))]
                   (caret! ed 5)
                   (delete-action! ed 3 5)
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-cache-reprimed-by-set-text
                            (and primed? (= "(x)\ny" text))
                            {:primed primed? :cache (update c :text pr-str) :text text})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-cache-invalidated-by-plain-op [ed]
  (go-row :parinfer-cache-invalidated-by-plain-op check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (caret! ed 0)
                 (type-str! ed "(")               ; primes the cache
                 (editor/apply-op-plain! ed {:edits [[0 2 "(fn [a b]\n     (h a))"]] :caret 0})
                 (caret! ed 19)
                 (type-str! ed "x")               ; first edit: must re-indent
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-cache-invalidated-by-plain-op
                          (= "(fn [a b]\n    (h ax))" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-caret-move-keeps-first-edit-reindent [ed]
  (go-row :parinfer-caret-move-keeps-first-edit-reindent check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(q)
(fn [a b]
     (h a))")
                 (caret! ed 2)
                 (type-str! ed "q")               ; edit form A: cache primes
                 ;; click into the misindented form B: a caret move, no
                 ;; edit, so the gen still matches and the cache hits
                 (caret! ed 24)
                 (type-str! ed "x")               ; first edit of B
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-caret-move-keeps-first-edit-reindent
                          (= "(qq)
(fn [a b]
    (h ax))" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-splice-equals-buffer [ed]
  (go-row :parinfer-splice-equals-buffer check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; set-text! bumps the gen, so the FIRST action after it is
                 ;; a cache miss; each sequence below primes with one
                 ;; throwaway action, then measures a HIT action, counting
                 ;; the ranged reads to prove the splice really ran
                                  (let [splices (atom 0)
                       real g/buffer-text-range
                       eqs (atom [])]
                   (with-redefs-fn {#'g/buffer-text-range
                                    (fn [& a] (swap! splices inc) (apply real a))}
                     #(do
                        ;; typing beside an emoji, through a hit
                        (editor/set-text! ed "(a 🙂 b)")
                        (caret! ed 7)
                        (type-str! ed "x")      ; prime (miss)
                        (type-str! ed "(")      ; hit, splice
                        (swap! eqs conj (= (:text @(:parinfer-prev-cache ed))
                                           (editor/editor-text ed)))
                        ;; an op with a fix, through a hit
                        (editor/set-text! ed "(a
  b)")
                        (caret! ed 6)
                        (type-str! ed "z")      ; prime (miss)
                        (editor/do-toggle-comment! ed)   ; hit, fix lands
                        (swap! eqs conj (= (:text @(:parinfer-prev-cache ed))
                                           (editor/editor-text ed)))
                        ;; a one-action selection replace, through a hit
                        (editor/set-text! ed "(a bb c)")
                        (caret! ed 5)
                        (type-str! ed "y")      ; prime (miss)
                        (let [buf (:buf ed)]
                          (g/gtk-text-buffer-begin-user-action buf)
                          (g/with-iters* buf 3 6 (fn [i j] (g/gtk-text-buffer-delete buf i j)))
                          (g/with-iters* buf 3 3 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                          (g/gtk-text-buffer-insert-interactive-at-cursor buf "(" -1 1)
                          (g/gtk-text-buffer-end-user-action buf))
                        (swap! eqs conj (= (:text @(:parinfer-prev-cache ed))
                                           (editor/editor-text ed)))))
                   (check :parinfer-splice-equals-buffer
                          ;; six: the three primes splice too (a fresh begin
                          ;; read is ground truth), plus the three measured hits
                          (and (= [true true true] @eqs) (= 6 @splices))
                          {:eqs @eqs :splices @splices
                           :t3 (editor/editor-text ed)}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-plain-op-updates-a-valid-cache [ed]
  (go-row :parinfer-plain-op-updates-a-valid-cache check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; an MCP or plugin write knows its own edits: when the
                 ;; cache is valid at that moment, apply-edits on the cached
                 ;; string keeps it valid with no buffer read, and the next
                 ;; keystroke is a hit
                 (editor/set-text! ed "(a)")      ; primes
                 (editor/apply-op-plain! ed {:edits [[1 2 "fn [a b]
     (h a)"]] :caret 0})
                 (let [fulls (atom 0)
                       real g/buffer-text]
                   (with-redefs-fn {#'g/buffer-text
                                    (fn [& a] (swap! fulls inc) (apply real a))}
                     #(do (caret! ed 18)
                          (type-str! ed "x")))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-plain-op-updates-a-valid-cache
                            (and (= "(fn [a b]
    (h xa))" text)
                                 eq (zero? @fulls))
                            {:text text :eq eq :full-reads @fulls})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-plain-op-leaves-an-invalid-cache [ed]
  (go-row :parinfer-plain-op-leaves-an-invalid-cache check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; after an undo the cache is stale; a plain op then must
                 ;; NOT half-trust it, and the next keystroke misses
                 ;; honestly and still lands the right text
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (g/gtk-text-buffer-undo (:buf ed))   ; cache now stale
                 (editor/apply-op-plain! ed {:edits [[0 0 "(q)
"]] :caret 0})
                 (caret! ed 9)
                 (delete-action! ed 7 9)
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-plain-op-leaves-an-invalid-cache
                          (= "(q)
(a)
b" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-nul-reload-clears-the-prime [ed]
  (go-row :parinfer-nul-reload-clears-the-prime check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; GTK takes C strings, so a NUL truncates what the buffer
                 ;; keeps; the prime must notice the counts disagree and
                 ;; clear, or the next keystroke splices from a string the
                 ;; buffer never held
                 (editor/set-text! ed (str "(a)" (char 0) "(b)"))
                 (caret! ed 1)
                 (type-str! ed "x")
                 (let [text (editor/editor-text ed)
                       eq (= (:text @(:parinfer-prev-cache ed)) text)]
                   (check :parinfer-nul-reload-clears-the-prime
                          (and (= "(xa)" text) eq)
                          {:text text :eq eq}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-nul-plain-op-clears-the-cache [ed]
  (go-row :parinfer-nul-plain-op-clears-the-cache check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; an op's replacement text carrying a NUL is refused by
                 ;; apply-op! outright (never landed truncated). The refusal still
                 ;; clears the cache (one honest miss), and the next
                 ;; keystroke lands on the untouched text with the cache
                 ;; coherent again.
                 (editor/set-text! ed "(a)")
                 (let [r (editor/apply-op-plain!
                          ed {:edits [[1 2 (str "x" (char 0) "y")]] :caret 0})
                       ;; pin the clear itself (the accepted one honest
                       ;; miss), so keeping the cache would have to be a
                       ;; deliberate change
                       cleared? (nil? @(:parinfer-prev-cache ed))]
                   (caret! ed 1)
                   (type-str! ed "z")
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-nul-plain-op-clears-the-cache
                            (and (nil? r) cleared? (= "(za)" text) eq)
                            {:r r :cleared cleared? :text text :eq eq})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-redo-splices-the-end-read [ed]
  (go-row :parinfer-redo-splices-the-end-read check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; redo is the undo row's mirror: a miss whose begin read
                 ;; is ground truth, so the end-side splice must still run
                 ;; and land the fallback's text
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (g/gtk-text-buffer-undo (:buf ed))
                 (g/gtk-text-buffer-redo (:buf ed))
                 (let [splices (atom 0)
                       real g/buffer-text-range]
                   (with-redefs-fn {#'g/buffer-text-range
                                    (fn [& a] (swap! splices inc) (apply real a))}
                     #(do (caret! ed 2)
                          (type-str! ed "x")))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-redo-splices-the-end-read
                            (and (= "(ax)
b" text) eq (pos? @splices))
                            {:text text :eq eq :splices @splices})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-undo-recovery-reads-nothing [ed]
  (go-row :parinfer-undo-recovery-reads-nothing check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; undo replays through the buffer's own insert and delete,
                 ;; so the armed outside-span records what changed since the
                 ;; prime: the next keystroke rebuilds prev from the cache
                 ;; plus one ranged read, no full copy
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (g/gtk-text-buffer-undo (:buf ed))
                 (let [fulls (atom 0) ranged (atom 0)
                       real-f g/buffer-text real-r g/buffer-text-range]
                   (with-redefs-fn {#'g/buffer-text
                                    (fn [& a] (swap! fulls inc) (apply real-f a))
                                    #'g/buffer-text-range
                                    (fn [& a] (swap! ranged inc) (apply real-r a))}
                     #(do (caret! ed 5)
                          (delete-action! ed 3 5)))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-undo-recovery-reads-nothing
                            (and (= "(a)
b" text) eq (zero? @fulls) (pos? @ranged))
                            {:text text :eq eq :full-reads @fulls
                             :ranged-reads @ranged})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-undo-redo-interleaving [ed]
  (go-row :parinfer-undo-redo-interleaving check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; type, undo, type, redo, type: every measured keystroke
                 ;; lands on exactly the real buffer with no full read (the
                 ;; second type clears the redo stack, so the redo is dead:
                 ;; no signals, no gen bump, the next type stays a hit)
                 (editor/set-text! ed "(q)
(fn [a b]
     (h a))")
                 (caret! ed 22)
                 (type-str! ed "x")
                 (let [fulls (atom 0) real-f g/buffer-text
                       eqs (atom [])
                       ;; through the captured reader, so the row's own
                       ;; equality checks don't count as the hook's reads
                       eq! (fn [] (swap! eqs conj
                                         (= (:text @(:parinfer-prev-cache ed))
                                            (real-f (:buf ed)))))]
                   (with-redefs-fn {#'g/buffer-text
                                    (fn [& a] (swap! fulls inc) (apply real-f a))}
                     #(do (g/gtk-text-buffer-undo (:buf ed))
                          (caret! ed 22) (type-str! ed "y") (eq!)
                          (g/gtk-text-buffer-redo (:buf ed))
                          (caret! ed 22) (type-str! ed "w") (eq!)))
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-undo-redo-interleaving
                            (and (= "(q)
(fn [a b]
    (h ywa))" text)
                                 (every? true? @eqs) (= 2 (count @eqs))
                                 (zero? @fulls))
                            {:text text :eqs @eqs :full-reads @fulls})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-undo-past-a-reload [ed]
  (go-row :parinfer-undo-past-a-reload check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; set-text! is an irreversible action: it empties the undo
                 ;; history, so an undo straight after it must change
                 ;; nothing, and the next keystroke lands on the real buffer
                 ;; whatever the span says
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (editor/set-text! ed "(zz)")
                 (g/gtk-text-buffer-undo (:buf ed))
                 (caret! ed 3)
                 (type-str! ed "x")
                 (let [text (editor/editor-text ed)
                       eq (= (:text @(:parinfer-prev-cache ed)) text)]
                   (check :parinfer-undo-past-a-reload
                          (and (= "(zzx)" text) eq)
                          {:text text :eq eq}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-undo-keeps-first-edit-reindent [ed]
  (go-row :parinfer-undo-keeps-first-edit-reindent check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; a recovered prev never carries :edit-at, so a form
                 ;; reached through undo still re-indents on its first
                 ;; edit: recovery never fakes consistency (the probe is the
                 ;; user's first-edit re-indent)
                 (editor/set-text! ed "(q)
(fn [a b]
     (h a))")
                 (caret! ed 2)
                 (type-str! ed "z")
                 (g/gtk-text-buffer-undo (:buf ed))
                 (caret! ed 22)
                 (type-str! ed "x")
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-undo-keeps-first-edit-reindent
                          (= "(q)
(fn [a b]
    (h xa))" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-reload-primes-the-cache [ed]
  (go-row :parinfer-reload-primes-the-cache check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; set-text! hands the new text in, so it primes the cache
                 ;; for free: the next keystroke is a hit
                 ;; (zero full reads), and the first-edit re-indent still
                 ;; lands because no consistency offset is stored
                 (editor/set-text! ed "(q)
(fn [a b]
     (h a))")
                 (let [fulls (atom 0)
                       real g/buffer-text]
                   (with-redefs-fn {#'g/buffer-text
                                    (fn [& a] (swap! fulls inc) (apply real a))}
                     #(do (caret! ed 22)
                          (type-str! ed "x")))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-reload-primes-the-cache
                            (and (= "(q)
(fn [a b]
    (h xa))" text)
                                 eq (zero? @fulls))
                            {:text text :eq eq :full-reads @fulls})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-reload-double-flip [ed]
  (go-row :parinfer-reload-double-flip check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; two reloads back to back: the prime must carry the LAST
                 ;; text with the LAST gen, or the next keystroke trusts a
                 ;; stale string
                 (editor/set-text! ed "(zz)")
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-reload-double-flip
                          (= "(a)
b" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-miss-still-splices-the-end-read [ed]
  (go-row :parinfer-miss-still-splices-the-end-read check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; a miss's begin read is ground truth, so the end-side
                 ;; splice is just as exact there: after
                 ;; an undo (a miss), the keystroke must still splice and
                 ;; land the fallback's text
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (g/gtk-text-buffer-undo (:buf ed))
                 (let [splices (atom 0)
                       real g/buffer-text-range]
                   (with-redefs-fn {#'g/buffer-text-range
                                    (fn [& a] (swap! splices inc) (apply real a))}
                     #(do (caret! ed 5)
                          (delete-action! ed 3 5)))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-miss-still-splices-the-end-read
                            (and (= "(a)
b" text) eq (pos? @splices))
                            {:text text :eq eq :splices @splices})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-fix-moves-the-edit-offset [ed]
  (go-row :parinfer-fix-moves-the-edit-offset check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; the first edit's fix re-indents EARLIER lines, moving
                 ;; text before the edit offset; the cached offset must
                 ;; follow it, or the next form inherits the consistency
                 ;; flag
                 (editor/set-text! ed "(f [a b]
        a)
(fn [a b]
     (h a))")
                 (caret! ed 18)
                 (type-str! ed "x")
                 (caret! ed (+ 3 (.indexOf (editor/editor-text ed) "(h a")))
                 (type-str! ed "y")
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-fix-moves-the-edit-offset
                          (= "(f [a b]
   ax)
(fn [a b]
    (h ya))" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-noop-action-marks-nothing [ed]
  (go-row :parinfer-noop-action-marks-nothing check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; stepping over a closing quote is a user action that
                 ;; changes nothing; it must not mark the form consistent
                                  (editor/set-text! ed "(q)\n(fn [a b]\n     (h \"a\"))")
                 (caret! ed 24)                      ; just before the closing quote
                 (editor/handle-key! ed 0x022 0)     ; quote over the closer: step past
                 (type-str! ed "x")
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-noop-action-marks-nothing
                          (= "(q)\n(fn [a b]\n    (h \"a\"x))" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-selection-replace-bounds [ed]
  (go-row :parinfer-selection-replace-bounds check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a bb c)")
                 ;; one user action, delete then insert, as a selection
                 ;; replace emits: the span must union both signals
                 (let [buf (:buf ed)]
                   (g/gtk-text-buffer-begin-user-action buf)
                   (g/with-iters* buf 3 5 (fn [i j] (g/gtk-text-buffer-delete buf i j)))
                   (g/with-iters* buf 3 3 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                   (g/gtk-text-buffer-insert-interactive-at-cursor buf "(" -1 1)
                   (g/gtk-text-buffer-end-user-action buf))
                 ;; the fallback path on copied strings is the oracle
                 (let [r (pe/adjust {:mode :smart
                                     :prev-text (apply str "(a bb c)") :prev-caret 3
                                     :text (apply str "(a ( c)") :caret 4})
                       want (jonquil.edit/apply-edits "(a ( c)" (:edits r))
                       text (editor/editor-text ed)]
                   (check :parinfer-selection-replace-bounds
                          (= want text)
                          {:want want :text text}))
                 (finally (parinfer-off! ed))))))

;; --- Parinfer and Format All interplay rows -----
;; A format is a deliberate whole-text operation: with Parinfer on its
;; result lands through the plain path, already made Paren-stable by the
;; op builder, so the hook never rewrites it and never shows the too-long
;; note; with Parinfer off the formatter's output is byte-identical to
;; before.

(def ^:private fi-unstable "(def a\n  [{:x 1}\n    {:y 2}])")
(def ^:private fi-stable "(def a\n  [{:x 1}\n   {:y 2}])")
(def ^:private fi-misindented "(def a\n[{:x 1}\n{:y 2}])")

(defn- row-parinfer-format-stabilizes-clean-text [ed]
  (go-row :parinfer-format-stabilizes-clean-text check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; formatter-clean but Paren-unstable text: a format with
                 ;; Parinfer on now lands the Paren-stable form, and one
                 ;; undo restores the input (one user action)
                 (editor/set-text! ed fi-unstable)
                 (caret! ed 0)
                 (editor/do-format-all! ed)
                 (let [text (editor/editor-text ed)]
                   (g/gtk-text-buffer-undo (:buf ed))
                   (let [undone (editor/editor-text ed)]
                     (check :parinfer-format-stabilizes-clean-text
                            (and (= fi-stable text) (= fi-unstable undone))
                            {:text text :undone undone})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-format-lands-untouched [ed]
  (go-row :parinfer-format-lands-untouched check
    (on-gtk (let [status (atom [])]
              (try (editor/set-parinfer-fns! ed (constantly :smart)
                                             (fn [msg] (swap! status conj msg)))
                   ;; the op builder makes the result Paren-stable, so the
                   ;; hook has nothing to do: no rewrite action, and the
                   ;; status line is never touched (today the hook clears it)
                   (editor/set-text! ed fi-misindented)
                   (caret! ed 0)
                   (editor/do-format-all! ed)
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-format-lands-untouched
                            (and (= fi-stable text) (= [] @status))
                            {:text text :status @status}))
                   (finally (parinfer-off! ed)))))))

(defn- row-parinfer-format-too-long-quietly [ed]
  (go-row :parinfer-format-too-long-quietly check
    (on-gtk (let [status (atom [])
                  big-mis (str "(def big\n[{:x 1}\n"
                               (apply str (repeat 300 "{:y 2}\n")) "{:y 3}])")
                  big-stable (str "(def big\n  [{:x 1}\n"
                                  (apply str (repeat 300 "   {:y 2}\n")) "   {:y 3}])")]
              (try (editor/set-parinfer-fns! ed (constantly :smart)
                                             (fn [msg] (swap! status conj msg)))
                   ;; a format whose edit spans more than 300 lines: no
                   ;; too-long note any more (the hook never runs), and the
                   ;; over-limit form is stabilized too -- the user accepted
                   ;; the format's cost over a 300-line carve-out
                   (editor/set-text! ed (str big-mis "\n\n" fi-misindented))
                   (caret! ed 0)
                   (editor/do-format-all! ed)
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-format-too-long-quietly
                            (and (= (str big-stable "\n\n" fi-stable) text)
                                 (= [] @status))
                            {:text-tail (tail text 40) :status @status}))
                   (finally (parinfer-off! ed)))))))

(defn- row-parinfer-format-off-byte-identical [ed]
  (go-row :parinfer-format-off-byte-identical check
    (on-gtk (try (parinfer-off! ed)
                 ;; with Parinfer off, format output is the formatter's
                 ;; alone, byte for byte
                 (editor/set-text! ed fi-misindented)
                 (caret! ed 0)
                 (editor/do-format-all! ed)
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-format-off-byte-identical
                          (= fi-unstable text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-format-selection-scoped [ed]
  (go-row :parinfer-format-selection-scoped check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; Format Selection stabilizes only the top-level forms the
                 ;; selection touches, and keeps its selection semantics
                 ;; (the selection collapses to the carried caret, as today)
                 (editor/set-text! ed (str fi-unstable "\n\n"
                                           "(def b\n  [{:x 3}\n    {:y 4}])"))
                 (g/with-iters* (:buf ed) 2 10
                   (fn [i j] (g/gtk-text-buffer-select-range (:buf ed) i j)))
                 (editor/do-format-selection! ed)
                 (let [text (editor/editor-text ed)
                       [sa sb] (g/selection-offsets (:buf ed))]
                   (check :parinfer-format-selection-scoped
                          (and (= (str fi-stable "\n\n"
                                       "(def b\n  [{:x 3}\n    {:y 4}])") text)
                               (= sa sb))
                          {:text text :sel [sa sb]}))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-format-second-is-noop [ed]
  (go-row :parinfer-format-second-is-noop check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; a second format finds the text already Paren-stable and
                 ;; applies nothing: no second undo step, so one undo after
                 ;; two formats restores the input
                 (editor/set-text! ed fi-misindented)
                 (caret! ed 0)
                 (editor/do-format-all! ed)
                 (editor/do-format-all! ed)
                 (let [text (editor/editor-text ed)]
                   (g/gtk-text-buffer-undo (:buf ed))
                   (let [undone (editor/editor-text ed)]
                     (check :parinfer-format-second-is-noop
                            (and (= fi-stable text) (= fi-misindented undone))
                            {:text text :undone undone})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-format-next-keystroke-hits [ed]
  (go-row :parinfer-format-next-keystroke-hits check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; the plain path primes the cache from the op, so the
                 ;; keystroke after a format is a hit (zero full reads) --
                 ;; and a no-op second format must leave that prime alone
                 (editor/set-text! ed fi-misindented)
                 (caret! ed 0)
                 (editor/do-format-all! ed)
                 (let [fulls (atom 0) real g/buffer-text
                       ;; only the keystrokes are counted: a format's own
                       ;; snapshot is a full read by design (the formatter
                       ;; needs the text), so it runs outside the counter
                       count! (fn [f]
                                (with-redefs-fn
                                  {#'g/buffer-text
                                   (fn [& a] (swap! fulls inc) (apply real a))}
                                  f))]
                   (count! #(do (caret! ed 6) (type-str! ed "x")))
                   (editor/do-format-all! ed)
                   (count! #(do (caret! ed 6) (type-str! ed "y")))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-format-next-keystroke-hits
                            (and (str/includes? text "ayx") eq (zero? @fulls))
                            {:text text :eq eq :full-reads @fulls})))
                 (finally (parinfer-off! ed))))))

(defn- row-parinfer-format-style-pin-paren-stable [ed]
  (go-row :parinfer-format-style-pin-paren-stable check
    (on-gtk (try (parinfer-off! ed)
                 ;; a project's :format-style :paren-stable runs the pass
                 ;; with the mode off, so a repo formats one way for
                 ;; everyone
                 (editor/set-format-style-fn! ed (constantly :paren-stable))
                 (editor/set-text! ed fi-misindented)
                 (caret! ed 0)
                 (editor/do-format-all! ed)
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-format-style-pin-paren-stable
                          (= fi-stable text) {:text text}))
                 (finally (editor/set-format-style-fn! ed (constantly nil))
                          (parinfer-off! ed))))))

(defn- row-parinfer-format-style-pin-plain [ed]
  (go-row :parinfer-format-style-pin-plain check
    (on-gtk (let [status (atom [])]
              (try (editor/set-parinfer-fns! ed (constantly :smart)
                                             (fn [msg] (swap! status conj msg)))
                   ;; :plain keeps the plain formatter's bytes under a live
                   ;; mode, still with no hook and no status noise
                   (editor/set-format-style-fn! ed (constantly :plain))
                   (editor/set-text! ed fi-misindented)
                   (caret! ed 0)
                   (editor/do-format-all! ed)
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-format-style-pin-plain
                            (and (= fi-unstable text) (= [] @status))
                            {:text text :status @status}))
                   (finally (editor/set-format-style-fn! ed (constantly nil))
                            (parinfer-off! ed)))))))

(defn- row-apply-op-refuses-nul [ed]
  (go-row :apply-op-refuses-nul check
    (on-gtk
     ;; GTK takes C strings, so an op whose edit text holds a NUL would
     ;; land truncated. The apply-op! funnel refuses
     ;; it like a read-only editor: nil, buffer untouched. Every op path
     ;; (plugin :edit commands, MCP replace) goes through this funnel.
     (editor/set-text! ed "(+ 1 1)")
     (let [r (editor/apply-op-plain! ed {:edits [[0 0 "x\u0000y"]] :caret 0})
           text (editor/editor-text ed)]
       (check :apply-op-refuses-nul
              (and (nil? r) (= "(+ 1 1)" text))
              {:r r :text text})))))

(def ^:private row-fns
  [row-editor-colours
   row-editor-undo-text-only
   row-editor-smart-enter
   row-editor-dirty
   row-editor-emoji-offsets
   row-editor-preedit-guard
   row-editor-cursor-fn-throw-named
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
   row-edit-format-keeps-caret-in-view
   row-edit-format-selection
   row-edit-format-selection-macos-cmd-option-f
   row-edit-format-selection-macos-cmd-option-l
   row-edit-redo-y
   row-edit-preedit-yields
   row-read-only-refuses-edits
   row-paint-async-keeps-gtk-responsive
   row-paint-async-superseded
   row-read-only-off-supersedes-paint
   row-paint-async-stops-on-destroy
   row-editor-dirty-roundtrip
   row-incremental-matches-full
   row-incremental-supersede
   row-incremental-open-string
   row-paint-async-drops-after-edit
   row-parinfer-open-paren
   row-parinfer-dedent-undo-redo
   row-reload-text-undoable
   row-reload-text-caret-clamps
   row-reload-text-parinfer-held-off
   row-parinfer-mcp-and-reload-unchanged
   row-parinfer-unstable-form-only
   row-parinfer-off-no-change
   row-parinfer-too-long-form-says-so
   row-parinfer-paren-mode-keeps-pairs
   row-parinfer-quote-backspace-keeps-pair
   row-parinfer-op-keeps-caret
   row-parinfer-op-keeps-selection
   row-parinfer-engine-failure-named-once
   row-parinfer-throw-clears-the-prime
   row-parinfer-cache-steady-typing
   row-parinfer-cache-invalidated-by-undo
   row-parinfer-cache-reprimed-by-set-text
   row-parinfer-cache-invalidated-by-plain-op
   row-parinfer-selection-replace-bounds
   row-parinfer-caret-move-keeps-first-edit-reindent
   row-parinfer-fix-moves-the-edit-offset
   row-parinfer-noop-action-marks-nothing
   row-parinfer-splice-equals-buffer
   row-parinfer-miss-still-splices-the-end-read
   row-parinfer-reload-primes-the-cache
   row-parinfer-reload-double-flip
   row-parinfer-plain-op-updates-a-valid-cache
   row-parinfer-plain-op-leaves-an-invalid-cache
   row-parinfer-nul-reload-clears-the-prime
   row-parinfer-nul-plain-op-clears-the-cache
   row-parinfer-redo-splices-the-end-read
   row-parinfer-undo-recovery-reads-nothing
   row-parinfer-undo-redo-interleaving
   row-parinfer-undo-past-a-reload
   row-parinfer-undo-keeps-first-edit-reindent
   row-parinfer-format-stabilizes-clean-text
   row-parinfer-format-lands-untouched
   row-parinfer-format-too-long-quietly
   row-parinfer-format-off-byte-identical
   row-parinfer-format-selection-scoped
   row-parinfer-format-second-is-noop
   row-parinfer-format-next-keystroke-hits
   row-parinfer-format-style-pin-paren-stable
   row-parinfer-format-style-pin-plain
   row-apply-op-refuses-nul])

;; --- console rows -------------------------------------------------
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
  (go-row :console-readonly check
    (cview/append! cv :out "hello\n")
    (let [drained (await-until 1000 20 #(str/includes? (g/buffer-text (:buf cv)) "hello\n"))]
      (on-gtk (let [buf (:buf cv)
                    before (g/buffer-text buf)
                    it (ffi/alloc g/ITER-BYTES)
                    refused (try
                              (g/gtk-text-buffer-get-iter-at-offset buf it 2)
                              (zero? (g/gtk-text-buffer-insert-interactive buf it "X" -1 1))
                              (finally (ffi/free it)))
                    after (g/buffer-text buf)]
                (check :console-readonly (and drained refused (= before after))
                       {:drained drained :refused refused :before before :after after}))))))

(defn- row-console-readonly-delete [cv]
  (go-row :console-readonly-delete check
    (on-gtk (cview/clear-input! cv))
    (cview/append! cv :out "guard\n")
    (let [drained (await-until 1000 20 #(str/includes? (g/buffer-text (:buf cv)) "guard\n"))]
      (on-gtk (let [buf (:buf cv)
                    before (g/buffer-text buf)
                    input-start-at (mark-offset buf (:input-start cv))
                    ;; the space just before input-start is the second
                    ;; char of the prompt's "user=> " -- still read-only.
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
                        :input-after input-after}))))))

(defn- row-console-output-above-input [cv]
  (go-row :console-output-above-input check
    (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "abc" -1 1))
    (future (cview/append! cv :out "late\n"))
    (let [ok (await-until 1000 20 #(str/includes? (g/buffer-text (:buf cv)) "late\n"))]
      (on-gtk (let [text (g/buffer-text (:buf cv))
                    input (cview/input-text cv)
                    late-at (str/index-of text "late\n")
                    prompt-at (str/index-of text "user=> abc")]
                (check :console-output-above-input
                       (and ok (str/ends-with? text "user=> abc")
                            (some? late-at) (some? prompt-at) (< late-at prompt-at)
                            (= "abc" input))
                       {:text text :input input :late-at late-at :prompt-at prompt-at}))))))

(defn- row-console-submit [cv]
  (go-row :console-submit check
    (on-gtk (cview/clear-input! cv)
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
                          (str/ends-with? text "\nuser=> "))
                     {:return got :last-submit (last @console-submits)
                      :history-readonly history-readonly :submitted-at submitted-at
                      :input-after input-after :text text})))))

(defn- row-console-style-after-submit
  "Once a REPL entry is committed, out-end sits exactly
  on the boundary between the just-committed prompt+input span and the
  fresh \"user=> \" prompt right after it -- both tagged :prompt, no untagged
  character between them. GtkTextBuffer treats inserting text at such a
  boundary as inside one continuous :prompt span and extends it onto the
  new text; insert-chunk! must not only ADD its kind's own tag on top via
  gtk_text_buffer_apply_tag but also strip a tag that leaked in from
  the insertion boundary. Submits an entry (so out-end lands on that
  boundary), appends plain :out text, and asserts the appended text
  carries :out but NOT :prompt -- and, the reverse direction, that the
  just-submitted input still carries its own :prompt and stays
  read-only."
  [cv]
  (go-row :console-style-after-submit check
    (let [[submitted-at got] (on-gtk (cview/clear-input! cv)
                                     (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 3 3)" -1 1)
                                     (let [submitted-at (mark-offset (:buf cv) (:input-start cv))
                                           got (cview/handle-return! cv false)]
                                       [submitted-at got]))]
      (future (cview/append! cv :out "plain\n"))
      (let [ok (await-until 1000 20 #(str/includes? (g/buffer-text (:buf cv)) "plain\n"))]
        (on-gtk (let [buf (:buf cv)
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
                          :submitted-readonly submitted-readonly? :text (tail text 200)})))))))

(defn- row-console-unbalanced-newline [cv]
  (go-row :console-unbalanced-newline check
    (on-gtk (cview/clear-input! cv)
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(defn f [x]" -1 1)
            (let [before-count (count @console-submits)
                  got (cview/handle-return! cv false)
                  after-count (count @console-submits)]
              (check :console-unbalanced-newline
                     (and (= 0 got) (= before-count after-count))
                     {:return got :before-count before-count :after-count after-count})))))

(defn- row-console-flood [cv]
  (go-row :console-flood check
    ;; row-console-unbalanced-newline deliberately leaves "(defn f [x]" in
    ;; the input (its :newline branch never touches the buffer); clear it
    ;; so output-chars below measures the output region, not output plus
    ;; that leftover input.
    (on-gtk (cview/clear-input! cv))
    (let [samples (atom [(System/currentTimeMillis)])
          active? (atom true)
          ;; The sampler measures the GTK main loop itself, so it stays a GTK
          ;; timer rather than a go loop (which would time on-gui! round
          ;; trips instead): one retained callable that re-arms by returning
          ;; 1 until the flood is over.
          _ (on-gtk (g/g-timeout-add 20 (cb/callable (fn [_data]
                                                       (if @active?
                                                         (do (swap! samples conj (System/currentTimeMillis)) 1)
                                                         0))
                                                     [:pointer] :int)
                                     ffi/null))
          _ (future (dotimes [i 100000] (cview/append! cv :out (str i "\n"))))
          ;; active? must always be reset, even if await-until itself
          ;; throws (its predicate reads g/buffer-text, which could) --
          ;; otherwise the sampler's 20 ms GTK timer callable keeps
          ;; re-arming (its own (if @active? ... 1) branch) for the rest
          ;; of the run instead of stopping with this row.
          ok (try
               (await-until 60000 50 #(str/includes? (g/buffer-text (:buf cv)) "99999\n"))
               (finally (reset! active? false)))]
      (on-gtk (let [gaps (map - (rest @samples) @samples)
                    max-gap (if (seq gaps) (apply max gaps) 0)
                    out-chars (- (count (g/buffer-text (:buf cv))) (count @(:prompt-text cv)))]
                ;; drain! doesn't insert a whole oversized batch
                ;; before trimming, and trim! measures out-end's own
                ;; offset instead of copying the output region just to
                ;; count it -- both bounded costs, so the GTK main
                ;; loop's tick cadence should stay well under 1s even
                ;; under this flood (measured 99-111ms).
                (check :console-flood (and ok (<= out-chars cview/MAX-OUTPUT-CHARS) (< max-gap 1000))
                       {:last-line-visible ok
                        :output-chars out-chars
                        :max-output-chars cview/MAX-OUTPUT-CHARS
                        :longest-tick-gap-ms max-gap}))))))

(defn- row-console-unicode [cv]
  (go-row :console-unicode check
    (on-gtk (cview/clear-input! cv))
    (cview/append! cv :out "😀한글\n")
    (let [drained (await-until 1000 20 #(str/includes? (g/buffer-text (:buf cv)) "한글\n"))]
      (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "한" -1 1)
              (let [input (cview/input-text cv)
                    buf (:buf cv)
                    text (g/buffer-text buf)
                    prompt-at (str/last-index-of text "user=> ")
                    it (ffi/alloc g/ITER-BYTES)
                    refused (try
                              (g/gtk-text-buffer-get-iter-at-offset buf it prompt-at)
                              (zero? (g/gtk-text-buffer-insert-interactive buf it "X" -1 1))
                              (finally (ffi/free it)))]
                (check :console-unicode
                       (and drained (= "한" input) refused)
                       {:drained drained :input input :refused refused :text (tail text 200)}))))))

(defn- row-console-history [cv]
  (go-row :console-history check
    (on-gtk (cview/clear-input! cv)
            (let [handled (cview/handle-history! cv :prev)
                  input (cview/input-text cv)]
              (check :console-history (and handled (= "(inc 1)" input))
                     {:handled handled :input input})))))

(defn- row-console-history-multiline-caret [cv]
  (go-row :console-history-multiline-caret check
    (on-gtk (cview/clear-input! cv)
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "line1\nline2" -1 1)
            ;; the insert leaves the caret at the end of "line2" -- the input's
            ;; second (last) line -- so Up must be gated off entirely: a
            ;; multi-line entry's own caret movement must not be hijacked into
            ;; browsing history. history-prev must never even be CALLED.
            (let [before-calls (count @history-prev-calls)
                  got (cview/handle-history! cv :prev)
                  input (cview/input-text cv)]
              (check :console-history-multiline-caret
                     (and (not got) (= "line1\nline2" input) (= before-calls (count @history-prev-calls)))
                     {:got got :input input
                      :calls-before before-calls :calls-after (count @history-prev-calls)})))))

(defn- row-console-preedit-guard
  "An active IME preedit composition makes Return and Up/Down no-ops
  (setting the atom directly proves the yield; the real preedit-changed
  wiring is a human smoke step)."
  [cv]
  (go-row :console-preedit-guard check
    (on-gtk (cview/clear-input! cv)
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 2)" -1 1)
            (reset! (:preedit-active? cv) true)
            (let [return-got (cview/handle-return! cv false)
                  history-got (cview/handle-history! cv :prev)
                  input (cview/input-text cv)]
              (reset! (:preedit-active? cv) false)
              (check :console-preedit-guard
                     (and (= 0 return-got) (false? history-got) (= "(+ 1 2)" input))
                     {:return-got return-got :history-got history-got :input input})))))

(defn- row-console-set-prompt-keeps-selection
  "set-prompt! runs whenever an evaluation or a connection change moves the
  namespace, so it can land while someone has part of their input
  selected. The rewrite keeps that selection, shifted by the prompt's new
  length, and keeps the typed text and the read-only prompt."
  [cv]
  (go-row :console-set-prompt-keeps-selection check
    (on-gtk (let [buf (:buf cv)]
              (cview/clear-input! cv)
              (g/gtk-text-buffer-insert-interactive-at-cursor buf "abcdef" -1 1)
              (let [at (mark-offset buf (:input-start cv))]
                ;; "bcd", selected right to left: the caret sits at its start
                (g/with-iters* buf (+ at 4) (+ at 1)
                  (fn [a b] (g/gtk-text-buffer-select-range buf b a))))
              (cview/set-prompt! cv "demo.sel")
              (let [at (mark-offset buf (:input-start cv))
                    text (g/buffer-text buf)
                    sel (g/selection-offsets buf)
                    caret (g/cursor-offset buf)
                    input (cview/input-text cv)
                    prompt-readonly (g/has-tag-at? buf (dec at) (:readonly-tag cv))]
                (cview/set-prompt! cv "user")
                (cview/clear-input! cv)
                (check :console-set-prompt-keeps-selection
                       (and (str/ends-with? text "demo.sel=> abcdef")
                            (= "abcdef" input)
                            (= [(+ at 1) (+ at 4)] sel)
                            (= (+ at 1) caret)
                            prompt-readonly)
                       {:tail (subs text (max 0 (- (count text) 40))) :input input :at at :sel sel :caret caret
                        :prompt-readonly prompt-readonly}))))))

(defn- row-callable-contains-throw
  "callable's backstop, through the real FFI boundary: two GLib timers
  whose callbacks throw. One names on-throw 1 (G_SOURCE_CONTINUE), so GLib
  keeps calling it until it stops throwing and answers 0 itself; the other
  takes the :int default, 0, so GLib removes it after its one throw. Both
  throws are reported on *err*, and the process carries on."
  [_cv]
  (go-row :callable-contains-throw check
    (let [kept (atom 0)
          dropped (atom 0)]
      (on-gtk
       (g/g-timeout-add 10 (cb/callable (fn [_data]
                                          (if (< (swap! kept inc) 3)
                                            (throw (ex-info "kept timer throws" {}))
                                            0))
                                        [:pointer] :int :on-throw 1)
                        ffi/null)
       (g/g-timeout-add 10 (cb/callable (fn [_data]
                                          (swap! dropped inc)
                                          (throw (ex-info "dropped timer throws" {})))
                                        [:pointer] :int)
                        ffi/null))
      (let [ok (await-until 2000 20 #(= 3 @kept))]
        (<! (timeout 200))
        (on-gtk (check :callable-contains-throw (and ok (= 3 @kept) (= 1 @dropped))
                       {:kept @kept :dropped @dropped}))))))

(defn- row-console-input-fn-throw-named
  "A registrant of on-input-changed! that throws must not break the cursor
  move that called it: the console reports it through its error fn as
  \"; cursor: <message>\" and leaves its text alone."
  [cv]
  (go-row :console-input-fn-throw-named check
    (on-gtk (let [buf (:buf cv)
                  saved-fns @(:on-input-fns cv)
                  saved-error-fn @(:on-error-fn cv)
                  errors (atom [])]
              (try
                (cview/set-error-fn! cv (fn [msg] (swap! errors conj msg)))
                (cview/on-input-changed! cv (fn [] (throw (ex-info "bad" {}))))
                (cview/clear-input! cv)
                (g/gtk-text-buffer-insert-interactive-at-cursor buf "ab" -1 1)
                ;; the insert itself fires the throwing fn; only the caret moves may satisfy the check
                (reset! errors [])
                (let [before (g/buffer-text buf)
                      end (g/gtk-text-buffer-get-char-count buf)]
                  (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                  (g/with-iters* buf (dec end) (dec end) (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                  (check :console-input-fn-throw-named
                         (and (some #{"; cursor: bad"} @errors)
                              (= before (g/buffer-text buf)))
                         {:errors @errors :before-chars (count before)
                          :after-chars (count (g/buffer-text buf))}))
                (finally
                  (cview/clear-input! cv)
                  (reset! (:on-input-fns cv) saved-fns)
                  (reset! (:on-error-fn cv) saved-error-fn)))))))

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
   row-console-set-prompt-keeps-selection
   row-console-input-fn-throw-named
   row-callable-contains-throw
   row-console-preedit-guard
   row-console-history])

;; --- driver ------------------------------------------------------------

(def ^:private PER-ROW-MS
  ;; row-console-flood's own deadline is 60000 ms; nothing else waits longer
  ;; than row-on-gui-concurrent's 10000 ms. The whole run takes about 3 s.
  90000)

(defn- run-all
  "Runs every row strictly in order (ga/run-rows), prints SUMMARY, then
  quits the app on the GTK thread."
  [app steps]
  (go (<! (ga/run-rows steps PER-ROW-MS check))
      (let [passed (count (filter second @results)) total (count @results)]
        (reset! finished? true)
        (println (str "SUMMARY " passed " of " total " passed")))
      (<! (timeout 50))
      (on-gtk (g/g-application-quit app))))

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
    ;; the navy theme, easier on the eyes; JONQUIL_SELFTEST_THEME picks
    ;; another (an unknown name falls back to navy)
    (let [k (some-> (System/getenv "JONQUIL_SELFTEST_THEME") keyword)
          t (theme/theme (if (some #{k} theme/theme-names) k :navy))
          provider (g/gtk-css-provider-new)]
      (editor/apply-theme! ed t)
      (cview/apply-theme! cv t)
      (g/gtk-widget-add-css-class (:view ed) "jonquil-code")
      (g/gtk-widget-add-css-class (:view cv) "jonquil-code")
      (g/gtk-css-provider-load-from-string provider (theme/code-css t commands/DEFAULT-FONT-PX))
      (g/gtk-style-context-add-provider-for-display
       (g/gdk-display-get-default) provider g/STYLE-PROVIDER-PRIORITY-APPLICATION)
      (g/set-color-scheme! (g/gtk-settings-get-default) (:dark? t)))
    (g/gtk-window-set-title win "Jonquil editor self-test")
    (g/gtk-window-set-default-size win 700 700)
    (g/gtk-window-set-child win paned)
    (editor/on-dirty-changed! ed (fn [dirty] (when dirty (swap! true-fire-count inc))))
    (g/gtk-window-present win)
    ;; recording a demo: JONQUIL_SELFTEST_MAXIMIZE fills the screen
    (when (System/getenv "JONQUIL_SELFTEST_MAXIMIZE") (g/gtk-window-maximize win))
    (editor/focus! ed)
    (go (<! (timeout 300))
        (<! (run-all app (into (mapv (fn [row] (fn [] (row ed))) row-fns)
                               (mapv (fn [row] (fn [] (row cv))) console-row-fns)))))))

(defn -main [& _]
  (let [app (g/gtk-application-new "net.b12n.jonquil.selftest" g/APPLICATION-DEFAULT-FLAGS)]
    (g/g-signal-connect-data app "activate"
                             (cb/callable activate [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-application-run app 0 ffi/null)
    (when-not @finished?
      (binding [*out* *err*]
        (println "[gtk-selftest] run ended before the last row; no SUMMARY, exiting 1")))
    (System/exit (if (and @finished? (seq @results) (every? second @results)) 0 1))))
