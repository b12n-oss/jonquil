(ns gtk-selftest-window.find-replace
  "The find/replace bar: highlighting, wrap, replace-one/-all, Escape and
  preedit, and its routing through the window's own key dispatch."
  (:require [clojure.core.async :refer [<! timeout]]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.eval :as eval]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]))

;; --- find and replace bar ------------------------------------------
;; Each row is self-contained (its own editor/set-text!, open! and query)
;; rather than chaining off a prior row's leftover selection, since the
;; driver's own run-row resets the editor text before every row anyway.

(defn row-find-highlights
  "\"foo bar foo baz foo\" has three occurrences of \"foo\", at character
  offsets [0 3], [8 11] and [16 19] (measured). Opening find and setting
  the query to \"foo\" must tag all three, show \"1 of 3\", and select the
  first."
  [ui]
  (go-row :find-highlights support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
              (editor/set-text! ed "foo bar foo baz foo")
              (find-bar/open! fb :find)
              (g/gtk-editable-set-text (:query fb) "foo")
              (let [tag0 (g/has-tag-at? buf 0 (:tag fb))
                    tag8 (g/has-tag-at? buf 8 (:tag fb))
                    tag16 (g/has-tag-at? buf 16 (:tag fb))
                    label (g/label-text (:count-label fb))
                    sel (g/selection-offsets buf)]
                (support/check :find-highlights
                               (and tag0 tag8 tag16 (= "1 of 3" label) (= [0 3] sel))
                               {:tag0 tag0 :tag8 tag8 :tag16 tag16 :label label :sel sel}))))))

(defn row-find-next-wraps
  "From the initial [0 3] (find-highlights' own first match), three find-next!
  calls step to [8 11], [16 19], then wrap back to [0 3]."
  [ui]
  (go-row :find-next-wraps support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
              (editor/set-text! ed "foo bar foo baz foo")
              (find-bar/open! fb :find)
              (g/gtk-editable-set-text (:query fb) "foo")
              (find-bar/find-next! fb)
              (let [sel1 (g/selection-offsets buf)]
                (find-bar/find-next! fb)
                (let [sel2 (g/selection-offsets buf)]
                  (find-bar/find-next! fb)
                  (let [sel3 (g/selection-offsets buf)]
                    (support/check :find-next-wraps
                                   (and (= [8 11] sel1) (= [16 19] sel2) (= [0 3] sel3))
                                   {:sel1 sel1 :sel2 sel2 :sel3 sel3}))))))))

(defn row-find-prev
  "From the initial [0 3], find-prev! wraps back to the last match, [16 19]."
  [ui]
  (go-row :find-prev support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
              (editor/set-text! ed "foo bar foo baz foo")
              (find-bar/open! fb :find)
              (g/gtk-editable-set-text (:query fb) "foo")
              (let [initial (g/selection-offsets buf)]
                (find-bar/find-prev! fb)
                (let [sel (g/selection-offsets buf)]
                  (support/check :find-prev (and (= [0 3] initial) (= [16 19] sel))
                                 {:initial initial :sel sel})))))))

(defn row-find-no-match
  "A query with no occurrences shows \"No matches\" and leaves no
  search-match tag anywhere the earlier \"foo\" matches were."
  [ui]
  (go-row :find-no-match support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
              (editor/set-text! ed "foo bar foo baz foo")
              (find-bar/open! fb :find)
              (g/gtk-editable-set-text (:query fb) "zzz")
              (let [label (g/label-text (:count-label fb))
                    any-tag? (or (g/has-tag-at? buf 0 (:tag fb))
                                 (g/has-tag-at? buf 8 (:tag fb))
                                 (g/has-tag-at? buf 16 (:tag fb)))]
                (support/check :find-no-match (and (= "No matches" label) (not any-tag?))
                               {:label label :any-tag any-tag?}))))))

(defn row-replace-one
  "replace-one! from the initial [0 3] match replaces only that occurrence
  (commands/replace-current), landing the selection on the shifted next
  match."
  [ui]
  (go-row :replace-one support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
              (editor/set-text! ed "foo bar foo baz foo")
              (find-bar/open! fb :replace)
              (g/gtk-editable-set-text (:query fb) "foo")
              (g/gtk-editable-set-text (:replacement fb) "X")
              (find-bar/replace-one! fb)
              (let [text (editor/editor-text ed)
                    sel (g/selection-offsets buf)]
                (support/check :replace-one (and (= "X bar foo baz foo" text) (= [6 9] sel))
                               {:text text :sel sel}))))))

(defn row-find-past-an-emoji
  "Past an emoji, everything the bar does is in GTK's characters. In
  \"😀 foo 😀 foo\" the matches are characters [2 5] and [8 11], each emoji
  one character as GTK counts it. With [8 11] selected, open! prefills \"foo\",
  tags both and labels \"2 of 2\", the match at the caret; find-prev!
  selects [2 5] and find-next! [8 11]. From [2 5], replace-one! with
  \"😀b\" (two characters for three) lands the selection on the next match
  shifted one left, [7 10]."
  [ui]
  (go-row :find-past-an-emoji support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
              (editor/set-text! ed "😀 foo 😀 foo")
              (g/with-iters* buf 8 11 (fn [a b] (g/gtk-text-buffer-select-range buf a b)))
              (find-bar/open! fb :replace)
              (let [query (g/editable-text (:query fb))
                    tag2 (g/has-tag-at? buf 2 (:tag fb))
                    tag8 (g/has-tag-at? buf 8 (:tag fb))
                    tag7 (g/has-tag-at? buf 7 (:tag fb))
                    label (g/label-text (:count-label fb))
                    _ (find-bar/find-prev! fb)
                    sel0 (g/selection-offsets buf)
                    _ (find-bar/find-next! fb)
                    sel1 (g/selection-offsets buf)
                    _ (find-bar/find-prev! fb)
                    _ (g/gtk-editable-set-text (:replacement fb) "😀b")
                    _ (find-bar/replace-one! fb)
                    text (editor/editor-text ed)
                    sel2 (g/selection-offsets buf)]
                (support/check :find-past-an-emoji
                               (and (= "foo" query) tag2 tag8 (not tag7) (= "2 of 2" label)
                                    (= [2 5] sel0) (= [8 11] sel1)
                                    (= "😀 😀b 😀 foo" text) (= [7 10] sel2))
                               {:query query :tag2 tag2 :tag8 tag8 :tag7 tag7 :label label
                                :sel0 sel0 :sel1 sel1 :text text :sel2 sel2}))))))

(defn row-replace-all
  "replace-all! replaces every match in one edit (commands/replace-all,
  applied through one apply-op! call, so one undo restores every original
  occurrence)."
  [ui]
  (go-row :replace-all support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
              (editor/set-text! ed "foo bar foo baz foo")
              (find-bar/open! fb :replace)
              (g/gtk-editable-set-text (:query fb) "foo")
              (g/gtk-editable-set-text (:replacement fb) "X")
              (find-bar/replace-all! fb)
              (let [text (editor/editor-text ed)
                    label (g/label-text (:count-label fb))]
                (g/gtk-text-buffer-undo buf)
                (let [restored (editor/editor-text ed)]
                  (support/check :replace-all
                                 (and (= "X bar X baz X" text) (= "Replaced 3" label)
                                      (= "foo bar foo baz foo" restored))
                                 {:text text :label label :restored restored})))))))

(defn row-find-escape
  "Escape, driven through the bar's own handle-key! (never a synthesized
  keystroke), closes the bar, strips every search-match tag, and returns
  focus to the editor view."
  [ui]
  (go-row :find-escape support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed) win (:window ui)]
              (editor/set-text! ed "foo bar foo baz foo")
              (find-bar/open! fb :find)
              (g/gtk-editable-set-text (:query fb) "foo")
              (let [handled (find-bar/handle-key! fb support/KEY-ESCAPE 0)
                    visible (g/gtk-widget-get-visible (:box fb))
                    any-tag? (or (g/has-tag-at? buf 0 (:tag fb))
                                 (g/has-tag-at? buf 8 (:tag fb))
                                 (g/has-tag-at? buf 16 (:tag fb)))
                    focus-is-editor? (= (g/gtk-root-get-focus win) (:view ed))]
                (support/check :find-escape
                               (and (= 1 handled) (zero? visible) (not any-tag?) focus-is-editor?)
                               {:handled handled :visible visible :any-tag any-tag?
                                :focus-is-editor focus-is-editor?}))))))

(defn row-find-hangul
  "Hangul syllables are single characters on this Jolt/JVM string
  representation (not surrogate pairs), so \"안녕 hello 안녕\" matches
  \"안녕\" at character offsets [0 2] and [9 11] (measured)."
  [ui]
  (go-row :find-hangul support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui)]
              (editor/set-text! ed "안녕 hello 안녕")
              (find-bar/open! fb :find)
              (g/gtk-editable-set-text (:query fb) "안녕")
              (let [matches @(:matches fb)]
                (support/check :find-hangul (= [[0 2] [9 11]] matches) {:matches matches}))))))

(defn row-find-survives-repaint
  "editor.clj's repaint! (60ms debounce after the buffer's own \"changed\"
  signal) must not strip search-match: a whole-buffer
  gtk_text_buffer_remove_all_tags would, and since a repaint's own
  tag-only mutation never fires \"changed\" itself, nothing would re-apply
  it afterward. Opens find, sets
  the query, types one character past the last match (so no match's own
  offsets move), then waits a FIXED delay well past the repaint debounce
  (there's no positive condition to poll for -- the tags either survive or
  they don't -- so this is the same one-shot-wait-then-check pattern
  row-run-selection-blank already uses) before checking the tags are still
  there."
  [ui]
  (go-row :find-survives-repaint support/check
    (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)
          _ (on-gtk (editor/set-text! ed "foo bar foo baz foo")
                    (find-bar/open! fb :find)
                    (g/gtk-editable-set-text (:query fb) "foo")
                    (support/place-cursor! buf (count (editor/editor-text ed)))
                    (g/gtk-text-buffer-insert-interactive-at-cursor buf "z" -1 1))]
      (<! (timeout 300))
      (on-gtk (let [tags-survived? (and (g/has-tag-at? buf 0 (:tag fb))
                                        (g/has-tag-at? buf 8 (:tag fb))
                                        (g/has-tag-at? buf 16 (:tag fb)))]
                (support/check :find-survives-repaint tags-survived?
                               {:tags-survived tags-survived? :text (editor/editor-text ed)}))))))

(defn row-find-preedit-yields
  "find_bar.clj's handle-key! is a capture-phase
  controller on the bar's own :box, so it would consume Escape/Return
  before either entry's IM context ever saw it unless it yields -- setting the atom
  directly proves the yield without needing a real IME, same pattern as
  editor.clj's row-edit-preedit-yields/row-editor-preedit-guard (the real
  preedit-changed wiring on each entry's GtkText delegate was verified
  separately with a throwaway probe, see ffi.clj's gtk-editable-get-
  delegate)."
  [ui]
  (go-row :find-preedit-yields support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
              (editor/set-text! ed "foo bar foo baz foo")
              (find-bar/open! fb :find)
              (g/gtk-editable-set-text (:query fb) "foo")
              (let [sel-before (g/selection-offsets buf)
                    visible-before (g/gtk-widget-get-visible (:box fb))]
                (reset! (:preedit-active? fb) true)
                (let [return-result (find-bar/handle-key! fb support/KEY-RETURN 0)
                      escape-result (find-bar/handle-key! fb support/KEY-ESCAPE 0)
                      sel-after (g/selection-offsets buf)
                      visible-after (g/gtk-widget-get-visible (:box fb))]
                  (reset! (:preedit-active? fb) false)
                  (support/check :find-preedit-yields
                                 (and (= 0 return-result) (= 0 escape-result)
                                      (= sel-before sel-after) (= visible-before visible-after))
                                 {:return-result return-result :escape-result escape-result
                                  :sel-before sel-before :sel-after sel-after
                                  :visible-before visible-before :visible-after visible-after})))))))

(defn row-find-return-on-a-button-yields
  "Return with focus on Replace All is left to GTK, which presses the
  button, rather than taken as next match: handle-key! answers 0 and the
  editor's selection doesn't move. The same Return with focus in the
  query still moves to the next match."
  [ui]
  (go-row :find-return-on-a-button-yields support/check
    (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)
          _ (on-gtk (editor/set-text! ed "foo bar foo baz foo")
                    (find-bar/open! fb :replace)
                    (g/gtk-editable-set-text (:query fb) "foo"))
          focused? (on-gtk (g/gtk-widget-grab-focus (:replace-all-btn fb))
                           (= (:replace-all-btn fb) (g/gtk-root-get-focus (g/gtk-widget-get-root (:box fb)))))
          [result before after] (on-gtk (let [b (g/selection-offsets buf)
                                              r (find-bar/handle-key! fb support/KEY-RETURN 0)]
                                          [r b (g/selection-offsets buf)]))
          [query-result moved] (on-gtk (g/gtk-widget-grab-focus (:query fb))
                                       (let [b (g/selection-offsets buf)
                                             r (find-bar/handle-key! fb support/KEY-RETURN 0)]
                                         [r (not= b (g/selection-offsets buf))]))]
      (on-gtk (find-bar/close! fb)
              (support/check :find-return-on-a-button-yields
                             (and focused? (= 0 result) (= before after) (= 1 query-result) moved)
                             {:focused focused? :result result :before before :after after
                              :query-result query-result :moved moved})))))

(defn row-find-prefill-selects
  "open! must select the whole prefilled query text, not just prefill
  and focus it. Selects \"bar\" in the editor first, so open!'s own
  prefill (single-line selection) puts exactly that text in the query.

  Reading the query's selection synchronously, in the same tick as
  open!, fails intermittently once the bar has already been through an
  earlier open! cycle (row-find-preedit-yields, right before this one,
  is exactly such a cycle): open!'s own recompute-matches! selects a
  match IN THE EDITOR, which revokes GTK's shared primary selection the
  query had just claimed, and open! runs recompute-matches! before
  the query's own select_region, but a same-tick read still can't tell
  \"already settled\" apart from \"just hasn't been revoked yet\" -- so
  this polls with a deadline instead, and genuinely fails (not hangs) if
  the selection never settles."
  [ui]
  (go-row :find-prefill-selects support/check
    (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)
          _ (on-gtk (editor/set-text! ed "foo bar foo baz foo")
                    (support/select-range! buf 4 7) ; "bar"
                    (find-bar/open! fb :find))
          ok? (await-until 500 20 #(and (= "bar" (g/editable-text (:query fb)))
                                        (= [0 3] (g/editable-selection-bounds (:query fb)))))]
      (on-gtk (support/check :find-prefill-selects ok?
                             {:query-text (g/editable-text (:query fb))
                              :sel (g/editable-selection-bounds (:query fb))})
              (find-bar/close! fb)))))

;; --- Escape via the WINDOW's own dispatch -------------------------
;; (:handle-window-key! ui), never find-bar/handle-key! directly -- these
;; rows exercise find-bar-focused?'s own routing, not the bar's internal
;; key handling (already covered by find-escape/find-preedit-yields).

(defn row-window-escape-closes-bar-while-running
  "jonquil.gtk.window.keys's find-bar-focused? must compare
  gtk_root_get_focus against the entry's internal GtkText delegate, never
  the outer (:query fb)/(:replacement fb) widgets directly -- comparing
  against the outer widgets is always false, so with a program running
  and the find bar's query focused, the window's own Escape clause would
  never match and would fall through to Stop, stopping the program
  instead of closing the bar. Starts a real (loop [] (recur)), waits for it to actually be running, opens the find
  bar (which focuses the query), then drives the WINDOW's own key
  dispatch with Escape: the bar must close, focus must return to the
  editor, and the program must STILL be running (stopped in this row's
  own cleanup, not by the Escape press).

  The bar must be open and hold focus before Escape is sent, and a wrong
  Stop is read from the context's stop-requested flag, which stop-eval!
  sets at once, in the same step as the Escape. This row once opened the
  bar, sent Escape and read still-running? in one GTK step, and so passed
  when the bar never opened: Escape then fell through to Stop, whose
  interrupt hadn't landed yet."
  [ui]
  (go-row :window-escape-closes-bar-while-running support/check
    (let [ed (:editor ui) fb (:find-bar ui) win (:window ui)
          bar-focused? #(let [f (g/gtk-root-get-focus win)]
                          (and (not (ffi/null? f)) (not (zero? (g/gtk-widget-is-ancestor f (:box fb))))))
          _ (on-gtk (editor/set-text! ed "(loop [] (recur))")
                    ((:run!* ui)))
          started? (await-until 2000 20 #(eval/evaluating? (:ctx (:session ui))))
          _ (on-gtk (find-bar/open! fb :find))
          opened? (await-until 2000 20 #(and (not (zero? (g/gtk-widget-get-visible (:box fb))))
                                             (bar-focused?)))
          [handled stop-requested?]
          (on-gtk [((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil)
                   (boolean (some-> @(:running-state (:ctx (:session ui))) :stop-requested deref))])
          closed? (await-until 2000 20 #(and (zero? (g/gtk-widget-get-visible (:box fb)))
                                             (= (g/gtk-root-get-focus win) (:view ed))))
          still-running? (on-gtk (eval/evaluating? (:ctx (:session ui))))]
      (support/check :window-escape-closes-bar-while-running
                     (and started? opened? (= 1 handled) (not stop-requested?) closed? still-running?)
                     {:started started? :opened opened? :handled handled :stop-requested stop-requested?
                      :closed closed? :still-running still-running?})
      (on-gtk (find-bar/close! fb)
              ((:stop!* ui)))
      (await-until 3000 20 #(support/idle? ui)))))

(defn row-window-escape-yields-preedit-while-running
  "With the find bar's own preedit flag set, the Escape clause in
  jonquil.gtk.window.keys must yield (0) too, not just find-bar/handle-key!
  in isolation -- the clause returns handle-key!'s OWN result directly, so
  this exercises that through the WINDOW dispatch specifically, with a
  program running.
  Starts a real running program, opens the bar, sets preedit-active?
  true, sends window Escape: must return 0, the bar must stay open, and
  the program must still be running."
  [ui]
  (go-row :window-escape-yields-preedit-while-running support/check
    (let [ed (:editor ui) fb (:find-bar ui)
          _ (on-gtk (editor/set-text! ed "(loop [] (recur))")
                    ((:run!* ui)))
          started? (await-until 2000 20 #(eval/evaluating? (:ctx (:session ui))))]
      (on-gtk (find-bar/open! fb :find)
              (reset! (:preedit-active? fb) true)
              (let [handled ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil)
                    bar-visible (g/gtk-widget-get-visible (:box fb))
                    still-running? (eval/evaluating? (:ctx (:session ui)))]
                (reset! (:preedit-active? fb) false)
                (support/check :window-escape-yields-preedit-while-running
                               (and started? (= 0 handled) (= 1 bar-visible) still-running?)
                               {:started started? :handled handled :bar-visible bar-visible
                                :still-running still-running?})
                (find-bar/close! fb)
                ((:stop!* ui))))
      (await-until 3000 20 #(support/idle? ui)))))

(defn row-window-escape-stops-when-editor-focused
  "Escape must keep its Stop meaning when focus is OUTSIDE the find
  bar -- find-bar-focused? must read false here.
  Explicitly grabs editor focus (not just relying on whatever an earlier
  row left focused), starts a real running program, sends window Escape:
  the program must stop."
  [ui]
  (go-row :window-escape-stops-when-editor-focused support/check
    (let [ed (:editor ui)
          _ (on-gtk (editor/focus! ed)
                    (editor/set-text! ed "(loop [] (recur))")
                    ((:run!* ui)))
          started? (await-until 2000 20 #(eval/evaluating? (:ctx (:session ui))))
          [mark handled] (on-gtk (let [m (support/out-end-offset ui)
                                       handled ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil)]
                                   [m handled]))
          stopped? (await-until 3000 20 #(str/includes? (support/since ui mark) "; interrupted"))]
      (support/check :window-escape-stops-when-editor-focused
                     (and started? (= 1 handled) stopped?)
                     {:started started? :handled handled :stopped stopped?}))))

;; --- find bar caret and key routing ----------------------------------

;; The shared key table (gdkkeysyms.h) -- the product's copies are private.
(def ^:private KEY-F3 0xffc0)
(def ^:private KEY-G 0x067)

(defn row-find-typing-keeps-caret
  "Typing in the editor while the bar is open must not move the
  selection. If the buffer's \"changed\" signal ran the full recompute, it
  would select (and scroll to) the next match after the caret, so the
  NEXT keystroke's commit would delete that selection and replace a
  match. Types \"z\" inside \"bar\" (away from every match)
  with insert_interactive_at_cursor, then asserts an empty selection with
  the caret right after the z. The second keystroke is reproduced the way
  GtkTextView's own commit_text does it (delete_selection, then insert at
  the cursor), and every \"foo\" must survive it."
  [ui]
  (go-row :find-typing-keeps-caret support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)]
              (editor/set-text! ed "foo bar foo baz foo")
              (find-bar/open! fb :find)
              (g/gtk-editable-set-text (:query fb) "foo")
              (support/place-cursor! buf 5)
              (g/gtk-text-buffer-insert-interactive-at-cursor buf "z" -1 1)
              (let [sel1 (g/selection-offsets buf)
                    caret1 (g/cursor-offset buf)]
                (g/gtk-text-buffer-delete-selection buf 1 1)
                (g/gtk-text-buffer-insert-interactive-at-cursor buf "q" -1 1)
                (let [text (editor/editor-text ed)
                      matches @(:matches fb)]
                  (find-bar/close! fb)
                  (support/check :find-typing-keeps-caret
                                 (and (= [6 6] sel1) (= 6 caret1)
                                      (= "foo bzqar foo baz foo" text) (= 3 (count matches)))
                                 {:sel1 sel1 :caret1 caret1 :text text :matches matches})))))))

(defn row-find-return-in-replacement
  "Return with focus in the replacement entry replaces the current
  match. gtk_root_get_focus returns the entry's internal GtkText delegate,
  so the focus check goes through gtk_widget_is_ancestor: comparing with
  the entry itself is always false and Return would run find-next!
  instead."
  [ui]
  (go-row :find-return-in-replacement support/check
    (let [fb (:find-bar ui)
          in-replacement? (fn [] (let [f (g/gtk-root-get-focus (:window ui))]
                                   (and (not (ffi/null? f))
                                        (not (zero? (g/gtk-widget-is-ancestor f (:replacement fb)))))))
          _ (on-gtk (let [ed (:editor ui)]
                      (editor/set-text! ed "foo bar foo baz foo")
                      (find-bar/open! fb :replace)
                      (g/gtk-editable-set-text (:query fb) "foo")
                      (g/gtk-editable-set-text (:replacement fb) "X")
                      (g/gtk-widget-grab-focus (:replacement fb))))
          ;; Return reads where focus is, so wait for it to land rather than
          ;; pressing in the same step (failed 1 run in 3 under load:
          ;; Return ran find-next)
          focused? (await-until 2000 20 in-replacement?)]
      (on-gtk (let [ed (:editor ui) buf (:buf ed)
                    handled (find-bar/handle-key! fb support/KEY-RETURN 0)
                    text (editor/editor-text ed)
                    sel (g/selection-offsets buf)]
                (find-bar/close! fb)
                (support/check :find-return-in-replacement
                               (and focused? (= 1 handled) (= "X bar foo baz foo" text) (= [6 9] sel))
                               {:focused focused? :handled handled :text text :sel sel}))))))

(defn row-find-user-caret-at-match-edge
  "A caret the user puts at a match's start in the editor stays a caret:
  Return in the replacement entry then finds the match, never replaces
  it (only a collapse from focus moving into the bar reads as the match
  the bar selected), in an active window or not; the detail says which."
  [ui]
  (go-row :find-user-caret-at-match-edge support/check
    (let [fb (:find-bar ui)
          focus-in? (fn [w] (let [f (g/gtk-root-get-focus (:window ui))]
                              (and (not (ffi/null? f))
                                   (or (= f w) (not (zero? (g/gtk-widget-is-ancestor f w)))))))
          _ (on-gtk (let [ed (:editor ui)]
                      (editor/set-text! ed "foo bar foo baz foo")
                      (find-bar/open! fb :replace)
                      (g/gtk-editable-set-text (:query fb) "foo")
                      (g/gtk-editable-set-text (:replacement fb) "X")
                      (editor/focus! ed)))
          in-editor? (await-until 2000 20 #(focus-in? (:view (:editor ui))))
          probe (on-gtk (let [buf (:buf (:editor ui)) view (:view (:editor ui))]
                          (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                          (let [p {:active (g/gtk-window-is-active (:window ui))
                                   :has-focus (g/gtk-widget-has-focus view)
                                   :is-focus (g/gtk-widget-is-focus view)}]
                            (g/gtk-widget-grab-focus (:replacement fb))
                            p)))
          in-replacement? (await-until 2000 20 #(focus-in? (:replacement fb)))]
      (on-gtk (let [ed (:editor ui) buf (:buf ed)
                    handled (find-bar/handle-key! fb support/KEY-RETURN 0)
                    text (editor/editor-text ed)
                    sel (g/selection-offsets buf)]
                (find-bar/close! fb)
                (support/check :find-user-caret-at-match-edge
                               (and in-editor? in-replacement? (= 1 handled) (= "foo bar foo baz foo" text) (= [0 3] sel))
                               (merge probe {:in-editor in-editor? :in-replacement in-replacement? :handled handled :text text :sel sel})))))))

(defn row-find-editor-next-then-replace
  "F3 with focus in the editor selects the next match; moving focus to the
  replacement entry collapses that selection, and Return there still
  replaces the match F3 selected."
  [ui]
  (go-row :find-editor-next-then-replace support/check
    (let [fb (:find-bar ui)
          focus-in? (fn [w] (let [f (g/gtk-root-get-focus (:window ui))]
                              (and (not (ffi/null? f))
                                   (or (= f w) (not (zero? (g/gtk-widget-is-ancestor f w)))))))
          _ (on-gtk (let [ed (:editor ui)]
                      (editor/set-text! ed "foo bar foo baz foo")
                      (find-bar/open! fb :replace)
                      (g/gtk-editable-set-text (:query fb) "foo")
                      (g/gtk-editable-set-text (:replacement fb) "X")
                      (editor/focus! ed)))
          in-editor? (await-until 2000 20 #(focus-in? (:view (:editor ui))))
          selected (on-gtk (find-bar/find-next! fb)
                           (g/selection-offsets (:buf (:editor ui))))
          _ (on-gtk (g/gtk-widget-grab-focus (:replacement fb)))
          in-replacement? (await-until 2000 20 #(focus-in? (:replacement fb)))]
      (on-gtk (let [ed (:editor ui)
                    handled (find-bar/handle-key! fb support/KEY-RETURN 0)
                    text (editor/editor-text ed)]
                (find-bar/close! fb)
                (support/check :find-editor-next-then-replace
                               (and in-editor? in-replacement? (= [8 11] selected) (= 1 handled) (= "foo bar X baz foo" text))
                               {:in-editor in-editor? :in-replacement in-replacement? :selected selected :handled handled :text text}))))))

(defn row-window-find-keys-editor-focused
  "With the bar open and focus back in the editor, F3, Cmd+G and
  Shift+F3 still move between matches, through the WINDOW's own key
  dispatch (:handle-window-key!), not the bar's controller."
  [ui]
  (go-row :window-find-keys-editor-focused support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed) win (:window ui)]
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
                (support/check :window-find-keys-editor-focused
                               (and focus-is-editor? (= [0 3] initial)
                                    (= 1 h1) (= [8 11] sel1)
                                    (= 1 h2) (= [16 19] sel2)
                                    (= 1 h3) (= [8 11] sel3))
                               {:focus-is-editor focus-is-editor? :initial initial
                                :h1 h1 :sel1 sel1 :h2 h2 :sel2 sel2 :h3 h3 :sel3 sel3}))))))

(defn row-find-preedit-ends-at-close
  "A composition whose end the input method never reported (focus left the
  bar mid-composition) leaves the preedit flag set while the bar stays
  open, and every key but typing yields; Cmd/Ctrl+F or H opening the bar
  again is the way out, so open! starts with none. The flag is set by hand
  here, then the bar is opened again: F3 goes to the next match, moving
  from the first foo to the second. Before F3 the check is the bar's own
  current match: in an active window, the query entry taking focus
  collapses the editor's visible selection (jonquil.gtk.find-bar/snapshot)."
  [ui]
  (go-row :find-preedit-ends-at-close support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui)]
              (try
                (editor/set-text! ed "foo bar foo")
                (find-bar/open! fb :find)
                (g/gtk-editable-set-text (:query fb) "foo")
                (reset! (:preedit-active? fb) true)
                (find-bar/open! fb :find)
                (let [before @(:current fb)
                      f3 (find-bar/handle-key! fb 0xffc0 0)
                      after (g/selection-offsets (:buf ed))]
                  (support/check :find-preedit-ends-at-close
                                 (and (= 1 f3) (= [0 3] before) (= [8 11] after))
                                 {:f3 f3 :before before :after after}))
                (finally
                  (reset! (:preedit-active? fb) false)
                  (find-bar/close! fb)))))))

(defn row-find-bar-buttons
  "Replace and Replace All reach replace-one! and replace-all!; a throw
  from each is reported in the status bar as \"; replace: ...\" and
  \"; replace all: ...\"."
  [ui]
  (go-row :find-bar-buttons support/check
    (let [fb (:find-bar ui) status (:status-label ui)
          calls (atom [])
          _ (on-gtk (find-bar/open! fb :replace)
                    (with-redefs-fn {#'find-bar/replace-one! (fn [_] (swap! calls conj :one))
                                     #'find-bar/replace-all! (fn [_] (swap! calls conj :all))}
                      (fn []
                        (g/g-signal-emit-by-name (:replace-btn fb) "clicked")
                        (g/g-signal-emit-by-name (:replace-all-btn fb) "clicked"))))
          _ (on-gtk (with-redefs-fn {#'find-bar/replace-one! (fn [_] (throw (ex-info "boom" {})))
                                     #'find-bar/replace-all! (fn [_] (throw (ex-info "boom" {})))}
                      (fn [] (g/g-signal-emit-by-name (:replace-btn fb) "clicked"))))
          one-ok? (await-until 1000 20 #(str/includes? (g/label-text status) "; replace: boom"))
          _ (on-gtk (with-redefs-fn {#'find-bar/replace-one! (fn [_] (throw (ex-info "boom" {})))
                                     #'find-bar/replace-all! (fn [_] (throw (ex-info "boom" {})))}
                      (fn [] (g/g-signal-emit-by-name (:replace-all-btn fb) "clicked"))))
          all-ok? (await-until 1000 20 #(str/includes? (g/label-text status) "; replace all: boom"))]
      (on-gtk (find-bar/close! fb)
              (support/check :find-bar-buttons
                             (and (= [:one :all] @calls) one-ok? all-ok?)
                             {:calls @calls :one one-ok? :all all-ok? :status (g/label-text status)})))))
