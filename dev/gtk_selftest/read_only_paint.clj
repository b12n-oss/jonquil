(ns gtk-selftest.read-only-paint
  "Editor rows for read-only editors, the asynchronous repaint and the dirty round trip."
  (:require [clojure.core.async :refer [<! timeout]]
            [clojure.string :as str]
            [gtk-async :as ga :refer [await-until go-row on-gtk]]
            [gtk-selftest.support :refer [check with-fresh-editor big-text]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [selftest-keys]))

(defn row-read-only-refuses-edits
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

(defn row-paint-async-keeps-gtk-responsive
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

(defn row-paint-async-superseded
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

(defn row-read-only-off-supersedes-paint
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

(defn row-paint-async-stops-on-destroy
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
                                     (selftest-keys/watch! win)
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

(defn row-editor-dirty-roundtrip [ed]
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
