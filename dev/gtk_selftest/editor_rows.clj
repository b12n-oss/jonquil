(ns gtk-selftest.editor-rows
  "Editor rows: colouring, undo, smart enter, dirty tracking, emoji offsets, preedit and concurrent GUI calls."
  (:require [clojure.core.async :refer [<! timeout]]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest.support :refer [check await-fire-count-settled true-fire-count]]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

;; --- rows ------------------------------------------------------------------
;; Each row is (fn [ed] ch) -- a go block (gtk-async/go-row) that runs its
;; actions against ed and records exactly one check.
(defn row-editor-colours [ed]
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

(defn row-editor-undo-text-only
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

(defn row-editor-smart-enter [ed]
  (go-row :editor-smart-enter check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "(defn f [x]")
              (g/with-iters* buf 11 11 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (editor/handle-return! ed)
              (let [text (editor/editor-text ed) caret (g/cursor-offset buf)]
                (check :editor-smart-enter (and (= "(defn f [x]\n  " text) (= 14 caret))
                       {:text text :caret caret}))))))

(defn row-editor-dirty [ed]
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

(defn row-editor-emoji-offsets [ed]
  (go-row :editor-emoji-offsets check
    (let [buf (:buf ed) tags (:tags ed)]
      (on-gtk (editor/set-text! ed "😀(a)"))
      (let [ok (await-until 1000 20 #(and (g/has-tag-at? buf 1 (get tags [:rainbow 0]))
                                          (not (g/has-tag-at? buf 0 (get tags [:rainbow 0])))))]
        (on-gtk (check :editor-emoji-offsets ok
                       {:text (editor/editor-text ed)
                        :rainbow0-at-1 (g/has-tag-at? buf 1 (get tags [:rainbow 0]))
                        :rainbow0-at-0 (g/has-tag-at? buf 0 (get tags [:rainbow 0]))}))))))

(defn row-on-gui-concurrent
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

(defn row-editor-preedit-guard
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

(defn row-editor-tick-skips-unchanged
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
