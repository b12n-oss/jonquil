(ns gtk-selftest.edit-keys
  "Editor key rows: every row calls editor/handle-key! with a synthetic keyval and state, never a synthesized OS keystroke."
  (:require [clojure.core.async :refer [<! timeout]]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest.support :refer [check]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.keymap :as km]))

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

(defn row-edit-auto-pair [ed]
  (go-row :edit-auto-pair check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "")
              (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (let [handled (editor/handle-key! ed KEY-PARENLEFT 0)
                    text (editor/editor-text ed)
                    caret (g/cursor-offset buf)]
                (check :edit-auto-pair (and (= 1 handled) (= "()" text) (= 1 caret))
                       {:handled handled :text text :caret caret}))))))

(defn row-edit-wrap-selection [ed]
  (go-row :edit-wrap-selection check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "abc")
              (g/with-iters* buf 0 3 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
              (let [handled (editor/handle-key! ed KEY-BRACKETLEFT 0)
                    text (editor/editor-text ed)]
                (check :edit-wrap-selection (and (= 1 handled) (= "[abc]" text))
                       {:handled handled :text text}))))))

(defn row-edit-type-over [ed]
  (go-row :edit-type-over check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "()")
              (g/with-iters* buf 1 1 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (let [handled (editor/handle-key! ed KEY-PARENRIGHT 0)
                    text (editor/editor-text ed)
                    caret (g/cursor-offset buf)]
                (check :edit-type-over (and (= 1 handled) (= "()" text) (= 2 caret))
                       {:handled handled :text text :caret caret}))))))

(defn row-edit-backspace-pair [ed]
  (go-row :edit-backspace-pair check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "()")
              (g/with-iters* buf 1 1 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (let [handled (editor/handle-key! ed KEY-BACKSPACE 0)
                    text (editor/editor-text ed)]
                (check :edit-backspace-pair (and (= 1 handled) (= "" text))
                       {:handled handled :text text}))))))

(defn row-edit-backspace-plain [ed]
  (go-row :edit-backspace-plain check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "ab")
              (g/with-iters* buf 2 2 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (let [handled (editor/handle-key! ed KEY-BACKSPACE 0)
                    text (editor/editor-text ed)]
                (check :edit-backspace-plain (and (= 0 handled) (= "ab" text))
                       {:handled handled :text text}))))))

(defn row-edit-tab-selection [ed]
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

(defn row-edit-shift-tab [ed]
  (go-row :edit-shift-tab check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "  ab\n  cd")
              (g/with-iters* buf 0 9 (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
              (let [handled (editor/handle-key! ed KEY-ISO-LEFT-TAB 0)
                    text (editor/editor-text ed)]
                (check :edit-shift-tab (and (= 1 handled) (= "ab\ncd" text))
                       {:handled handled :text text}))))))

(defn row-edit-shift-tab-unindented
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

(defn row-edit-toggle-comment
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

(defn row-edit-toggle-comment-forward
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

(defn row-edit-structural-keys
  "Structural editing on its shipped keys, through the editor's own key
  handler: Ctrl+Alt+Right slurps twice, Ctrl+Alt+Left barfs, Ctrl+Shift+
  Delete kills the form at the caret, and one undo takes the kill back.
  Ctrl+Alt+S splices and Ctrl+Alt+Shift+P wraps, sent the way each system
  sends a letter held with Alt."
  [ed]
  (go-row :edit-structural-keys check
    (on-gtk (let [buf (:buf ed)
                  ctrl-alt (bit-or g/CONTROL-MASK g/ALT-MASK)
                  press! (fn [key state]
                           [(editor/handle-key! ed (km/key-value key) state) (editor/editor-text ed)])
                  place! (fn [o] (g/with-iters* buf o o (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))]
              (editor/set-text! ed "(a) b c")
              (place! 2)
              (let [slurp1 (press! "Right" ctrl-alt)
                    slurp2 (press! "Right" ctrl-alt)
                    barf (press! "Left" ctrl-alt)
                    _ (place! 3)
                    kill (press! "Delete" (bit-or g/CONTROL-MASK g/SHIFT-MASK))
                    caret (g/cursor-offset buf)
                    _ (g/gtk-text-buffer-undo buf)
                    undone (editor/editor-text ed)
                    ;; nothing to slurp: the chord is still used up, the text unchanged
                    _ (editor/set-text! ed "(a)")
                    _ (place! 2)
                    none (press! "Right" ctrl-alt)
                    ;; the letter keys hold Alt. On macOS Option+S arrives as
                    ;; sharp s (0xdf) and Option+Shift+P as U+220F, so only the
                    ;; keycode (1 and 35) names the key; elsewhere the letter does
                    mac? (= "Mac OS X" (System/getProperty "os.name"))
                    letter! (fn [mac-keyval mac-keycode ch state]
                              (if mac?
                                (editor/handle-key! ed mac-keyval mac-keycode state)
                                (editor/handle-key! ed (int ch) state))
                              (editor/editor-text ed))
                    _ (editor/set-text! ed "(a [b c])")
                    _ (place! 5)
                    spliced (letter! 0xdf 1 \s ctrl-alt)
                    _ (place! 3)
                    wrapped (letter! 0x100220f 35 \p (bit-or ctrl-alt g/SHIFT-MASK))
                    ;; the second batch: Alt+Down drags, Ctrl+Alt+Backspace kills
                    ;; the form before, Ctrl+Shift+J joins
                    _ (editor/set-text! ed "(a b c)")
                    _ (place! 1)
                    dragged (second (press! "Down" g/ALT-MASK))
                    _ (editor/set-text! ed "(a b c)")
                    _ (place! 4)
                    killed-back (second (press! "BackSpace" ctrl-alt))
                    _ (editor/set-text! ed "(a) (b)")
                    _ (place! 3)
                    joined (second (press! "j" (bit-or g/CONTROL-MASK g/SHIFT-MASK)))]
                (check :edit-structural-keys
                       (and (= [1 "(a b) c"] slurp1) (= [1 "(a b c)"] slurp2) (= [1 "(a b) c"] barf)
                            (= [1 "(a) c"] kill) (= 2 caret) (= "(a b) c" undone)
                            (= [1 "(a)"] none)
                            (= "(a b c)" spliced) (= "(a (b) c)" wrapped)
                            (= "(b a c)" dragged) (= "(a  c)" killed-back) (= "(a b)" joined))
                       {:slurp1 slurp1 :slurp2 slurp2 :barf barf :kill kill :caret caret
                        :undone undone :none none :spliced spliced :wrapped wrapped
                        :dragged dragged :killed-back killed-back :joined joined}))))))

(defn row-edit-format-all [ed]
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

(defn row-edit-format-keeps-caret-in-view
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

(defn row-edit-format-selection [ed]
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

(defn row-edit-format-selection-macos-cmd-option-f
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

(defn row-edit-format-selection-macos-cmd-option-l
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

(defn row-edit-redo-y [ed]
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

(defn row-edit-preedit-yields [ed]
  (go-row :edit-preedit-yields check
    (on-gtk (editor/set-text! ed "abc")
            (reset! (:preedit-active? ed) true)
            (let [handled (editor/handle-key! ed KEY-PARENLEFT 0)
                  text (editor/editor-text ed)]
              (reset! (:preedit-active? ed) false)
              (check :edit-preedit-yields (and (= 0 handled) (= "abc" text))
                     {:handled handled :text text})))))
