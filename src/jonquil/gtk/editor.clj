(ns jonquil.gtk.editor
  "The Definitions editor: an editable, monospace GtkTextView owned by GTK,
  wired to jonquil.paint for live highlighting and jonquil.edit for smart
  Enter (global.md: the native widget owns the text; the core only ever
  sees a snapshot and returns edits, applied back to front inside one
  gtk_text_buffer_begin/end_user_action).

  One GtkTextTag per paint/style key. Typing debounces through a 20ms
  g_timeout_add tick: it repaints once 60ms have passed since the buffer's
  last `changed` signal, and on every tick it also recomputes dirty?
  against the last-saved text and fires on-dirty-changed! exactly on a
  flip. dirty? itself is computed live from the buffer text vs the saved
  text, so it reads correctly the instant set-text!/mark-saved! runs,
  without waiting on the tick -- and because repaint! only ever touches
  tags, never buffer text, a repaint can never itself flip dirty?.

  P3.T4 fix round 1: repaint! removes and reapplies only the tags it owns
  (this namespace's own paint/rainbow/unmatched set, keyed in :tags), never
  gtk_text_buffer_remove_all_tags over the whole buffer -- that used to
  strip ANY other tag applied over the same range too, including
  jonquil.gtk.find-bar's search-match tag, and since a repaint's own
  tag-only mutation never itself fires `changed`, nothing re-applied it
  afterward; the search-match highlight silently vanished up to 60-80ms
  after every keystroke while the find bar stayed open.

  Every GTK call here already runs on the GTK thread: signal emission and
  g_timeout_add callbacks are both delivered by GTK's own main loop, so
  nothing in this namespace needs jonquil.gtk.callbacks/on-gui! to get
  there."
  (:require [jolt.ffi :as ffi]
            [jonquil.commands :as commands]
            [jonquil.edit :as edit]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.paint :as paint]
            [jonquil.theme :as theme]))

;; --- tags ----------------------------------------------------------------

(defn- tag-key-name [k]
  (if (vector? k) (str "rainbow-" (second k)) (name k)))

(defn- make-tags!
  "One GtkTextTag per paint/style key: every paint/token-styles key,
  [:rainbow 0..6], and :unmatched. :bold sets weight 700; :italic goes
  through set-italic! (the S2 italic gap the p2c probe closed). Tags start
  with theme/default-theme's colours; apply-theme! (TH.T4) retints them in
  place, never recreating them."
  [buf]
  (let [table (g/gtk-text-buffer-get-tag-table buf)
        ks (concat (keys paint/token-styles)
                   (map (fn [i] [:rainbow i]) (range paint/rainbow-count))
                   [:unmatched])]
    (into {}
          (map (fn [k]
                 (let [{:keys [fg bold italic]} (paint/style (theme/theme theme/default-theme) k)
                       tag (g/gtk-text-tag-new (tag-key-name k))]
                   (g/set-string-property! tag "foreground" fg)
                   (when bold (g/set-int-property! tag "weight" g/PANGO-WEIGHT-BOLD))
                   (when italic (g/set-italic! tag))
                   (g/gtk-text-tag-table-add table tag)
                   [k tag])))
          ks)))

;; --- reading and writing the buffer ---------------------------------------

(defn editor-text
  "The editor's whole buffer as a string."
  [ed]
  (g/buffer-text (:buf ed)))

(defn mark-saved!
  "Records the current text as saved, so dirty? reads false until the next
  change. Marks a dirty recompute needed (M7): this changes what dirty?
  will return without any buffer mutation, so no `changed` signal fires on
  its own to trigger tick!'s usual recompute trigger."
  [ed]
  (reset! (:saved-text ed) (editor-text ed))
  (reset! (:dirty-check-needed? ed) true)
  nil)

(defn set-text!
  "Replaces the whole buffer inside begin/end_irreversible_action, so it
  leaves no undo step, and records s as the saved text in the same call --
  before the next tick can run -- so set-text! never itself marks dirty or
  fires on-dirty-changed!. Also marks a dirty recompute needed (M7),
  belt-and-braces alongside the `changed` signal set-text buffer itself
  emits."
  [ed s]
  (let [buf (:buf ed)]
    (g/gtk-text-buffer-begin-irreversible-action buf)
    (g/gtk-text-buffer-set-text buf s -1)
    (g/gtk-text-buffer-end-irreversible-action buf)
    (reset! (:saved-text ed) s))
  (reset! (:dirty-check-needed? ed) true)
  ;; P3.T5: belt-and-braces alongside the buffer's own cursor notify (below),
  ;; same reasoning as the dirty-check-needed? line above it.
  (doseq [f @(:on-cursor-fns ed)] (f (g/cursor-line-col (:buf ed))))
  nil)

(defn dirty?
  "True when the buffer's text differs from the last-saved text. Computed
  live against the buffer on every call, never cached, so it is correct
  immediately after set-text!/mark-saved! and can't be fooled by a
  tag-only repaint."
  [ed]
  (not= (editor-text ed) @(:saved-text ed)))

(defn on-dirty-changed!
  "Registers f to be called with the new dirty? value, on the GTK thread,
  whenever dirty? flips. Multiple registrations are supported; all fire in
  registration order."
  [ed f]
  (swap! (:on-dirty-fns ed) conj f)
  nil)

(defn on-cursor-moved!
  "Registers f (fn [[line col]], both 0-based) to be called, on the GTK
  thread, whenever the insert mark's position changes: on the buffer's
  own \"notify::cursor-position\" (P3 fix wave I2, replacing P3.T5's
  mark-set, which an insert or delete never emits), and on set-text! (which
  resets the cursor without necessarily firing mark-set itself, same
  belt-and-braces reasoning as dirty-check-needed?). Multiple
  registrations fire in registration order, mirroring on-dirty-changed!."
  [ed f]
  (swap! (:on-cursor-fns ed) conj f)
  nil)

(defn on-focus-lost!
  "Registers f (fn [] -> nil) to be called, on the GTK thread, whenever
  the editor's view (or a descendant) stops being the root's focus widget
  -- GtkEventControllerFocus's own \"leave\" signal (P4.T4, ffi.clj's own
  citation), which tracks focus-within independent of OS window-
  activation, unlike gtk_widget_has_focus (window.clj's do-toggle-focus!
  docstring). Multiple registrations fire in registration order, mirroring
  on-dirty-changed!/on-cursor-moved!."
  [ed f]
  (swap! (:on-focus-fns ed) conj f)
  nil)

(defn set-quick-doc-fn!
  "Registers f (fn [] -> nil), the ui's own :quick-doc!* action, to run
  for the editor's quick-doc chords (Ctrl+Q, Shift+F1). A single slot, not
  a list like on-dirty-changed!/on-cursor-moved!/on-focus-lost! above --
  there is exactly one owner (jonquil.gtk.window's activate!), set once
  after the quick-doc popover and its status wiring exist; ed itself is
  built before those, so this is a late registration, same shape either
  way."
  [ed f]
  (reset! (:quick-doc-fn ed) f)
  nil)

(defn set-jump-fn!
  "Registers f (fn [] -> nil), the ui's own :jump!* action, to run for the
  editor's jump chords (F12, Ctrl+B). See set-quick-doc-fn!."
  [ed f]
  (reset! (:jump-fn ed) f)
  nil)

(defn set-rename-fn!
  "Registers f (fn [] -> nil), the ui's own :rename!* action, to run for
  the editor's rename chords (Shift+F6, F2). See set-quick-doc-fn!."
  [ed f]
  (reset! (:rename-fn ed) f)
  nil)

(defn set-completion-fns!
  "Registers fns ({:complete! (fn [] -> nil) :handle-key! (fn [keyval
  state] -> 0/1)}), the ui's own completion actions (P4.T5): :complete! is
  the editor's Ctrl+Space chord's own action (jonquil.gtk.window's
  :complete!*, single slot, same reasoning as set-quick-doc-fn!/
  set-jump-fn!'s own docstrings). :handle-key! is asked FIRST, ahead of
  every other clause in handle-key!'s own cond, for every key that isn't a
  preedit -- jonquil.gtk.completion/handle-key! itself gates on whether
  completion is actually open and returns 0 for every key otherwise, so
  this is a safe, cheap no-op call before completion is wired (this
  namespace's own pre-P4.T5 self-test callers) or while the popup is
  closed. Completion's OWN close conditions (a caret move out of the word,
  focus lost, another popover opening) are wired by jonquil.gtk.window
  through the EXISTING on-cursor-moved!/on-focus-lost! hooks above, not a
  third slot here -- see jonquil.gtk.completion's own ns docstring."
  [ed fns]
  (reset! (:completion-fns ed) fns)
  nil)

(defn focus! [ed]
  (g/gtk-widget-grab-focus (:view ed))
  nil)

(defn apply-theme!
  "Retints every tag in (:tags ed) to theme t's colours (TH.T4): sets each
  tag's foreground to (:fg (paint/style t k)). Only ever touches tag
  properties -- never buffer text, the selection or undo history -- so
  repaint! keeps working unchanged against the same tag objects, which
  already carry the new colours by the time any later repaint runs."
  [ed t]
  (doseq [[k tag] (:tags ed)]
    (g/set-string-property! tag "foreground" (:fg (paint/style t k))))
  nil)

;; --- snapshot / apply-op!, from the S2 spike ------------------------------

(defn snapshot
  "{:text :caret :sel-start :sel-end} for the core edit fns in jonquil.edit."
  [ed]
  (let [buf (:buf ed)
        [a b] (g/selection-offsets buf)]
    {:text (g/buffer-text buf) :caret (g/cursor-offset buf) :sel-start a :sel-end b}))

(defn apply-op!
  "Applies an edit-op result inside one user action, back to front, then
  places the caret -- or, when result has :sel [a b] with a < b (P3.T2:
  indent/unindent/toggle-comment on a selection), selects that range
  instead, so a chained op (a second Tab) sees the same selection through
  snapshot. gtk_text_buffer_select_range's first iter argument becomes the
  insert mark (probe 6: reading the insert mark back after select_range(a,
  b) shows it lands on a, the FIRST argument). Fix round 1: :caret is NOT
  always the selection's high end (b) -- indent/unindent/insert-open do
  put it there, but toggle-comment's :caret comes from shift-offset on the
  raw pre-edit caret, which can land on EITHER end depending on which way
  the user dragged the selection (a Cmd+/ on a backward-drag selection has
  :caret = a, not b; verified against (edit/toggle-comment {:text \"(+ 1
  2)\" :caret 0 :sel-start 0 :sel-end 7}) => {:caret 2 :sel [2 9]}, where
  2 = a). So the iter that matches :caret goes first, whichever end that
  is, not always b. Returns true when the op applied, nil when result was
  nil."
  [ed result]
  (when result
    (let [buf (:buf ed)
          [sel-a sel-b] (:sel result)
          caret (:caret result)]
      (g/gtk-text-buffer-begin-user-action buf)
      (try
        (doseq [[s e r] (rseq (vec (:edits result)))]
          (g/with-iters* buf s e (fn [a b] (g/gtk-text-buffer-delete buf a b)))
          (g/with-iters* buf s s (fn [a _] (g/gtk-text-buffer-insert buf a r -1))))
        (finally (g/gtk-text-buffer-end-user-action buf)))
      (if (and sel-a sel-b (< sel-a sel-b))
        (let [caret-at-a? (= caret sel-a)
              ins (if caret-at-a? sel-a sel-b)
              bound (if caret-at-a? sel-b sel-a)]
          (g/with-iters* buf ins bound
            (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2))))
        (g/with-iters* buf caret caret
          (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))))
    true))

(defn handle-return!
  "Applies jonquil.edit/smart-enter to the current snapshot as one user
  action. Returns nil, applying nothing, while an IME preedit composition
  is active (I5): the capture-phase key controller runs before the IM
  context gets to filter the key, so consuming Return here would split or
  lose a pending Hangul/CJK composition. handle-key! (below) treats this
  the same as any other unhandled key: 0, so GTK/the IM context gets it."
  [ed]
  (when-not @(:preedit-active? ed)
    (apply-op! ed (edit/smart-enter (snapshot ed)))))

;; --- repaint + the debounce/dirty tick -------------------------------------

(defn- repaint! [ed]
  (let [{:keys [buf tags]} ed
        text (g/buffer-text buf)
        plan (paint/paint-plan text)
        len (count text)]
    ;; P3.T4 fix round 1: remove only OUR OWN tags, one at a time, never
    ;; remove_all_tags -- a tag another namespace applies over the same
    ;; range (jonquil.gtk.find-bar's search-match) must survive a repaint.
    (doseq [tag (vals tags)]
      (g/with-iters* buf 0 len (fn [a b] (g/gtk-text-buffer-remove-tag buf tag a b))))
    (doseq [[k s e] plan]
      (when-let [tag (get tags k)]
        (g/with-iters* buf s e (fn [a b] (g/gtk-text-buffer-apply-tag buf tag a b)))))))

(defn- tick! [ed]
  ;; M7: dirty? copies and compares the whole buffer, which used to run
  ;; every 20ms regardless of whether anything changed. Only recompute when
  ;; a `changed` happened since the last check (or set-text!/mark-saved!
  ;; ran) -- both set dirty-check-needed?; this consumes it. dirty-check-
  ;; count is bumped on every ACTUAL recompute, purely so a self-test row
  ;; can observe that idle ticks skip it (there's no other externally
  ;; visible difference: dirty? itself is still correct on every call, and
  ;; on-dirty-fns still only fires on a real flip either way).
  (when @(:dirty-check-needed? ed)
    (reset! (:dirty-check-needed? ed) false)
    (swap! (:dirty-check-count ed) inc)
    (let [cur (dirty? ed)
          last-dirty? (:last-dirty? ed)]
      (when (not= cur @last-dirty?)
        (reset! last-dirty? cur)
        (doseq [f @(:on-dirty-fns ed)] (f cur)))))
  (when-let [t @(:dirty-at ed)]
    (when (>= (- (System/currentTimeMillis) t) 60)
      (reset! (:dirty-at ed) nil)
      (repaint! ed)))
  1)

;; --- keys: Return/KP-Enter with no Shift/Control/Meta ---------------------

(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-KP-ENTER 0xff8d)

(defn- plain-return?
  "Return or KP-Enter with none of Shift, Control or Meta held (gtk-keys.md:
  a command chord is Control or Meta; Shift+Return is left alone so GTK
  inserts a plain newline, per global.md)."
  [keyval state]
  (and (contains? #{KEY-RETURN KEY-KP-ENTER} keyval)
       (zero? (bit-and state (bit-or g/SHIFT-MASK g/CONTROL-MASK g/META-MASK)))))

;; --- keys: the rest of the dispatch (P3.T2, gtk-keys.md) --------------------

(def ^:private KEY-PARENLEFT 0x028)
(def ^:private KEY-PARENRIGHT 0x029)
(def ^:private KEY-BRACKETLEFT 0x05b)
(def ^:private KEY-BRACKETRIGHT 0x05d)
(def ^:private KEY-BRACELEFT 0x07b)
(def ^:private KEY-BRACERIGHT 0x07d)
(def ^:private KEY-QUOTEDBL 0x022)
(def ^:private KEY-BACKSPACE 0xff08)
(def ^:private KEY-TAB 0xff09)
(def ^:private KEY-ISO-LEFT-TAB 0xfe20)
(def ^:private KEY-SLASH 0x02f)
(def ^:private KEY-SEMICOLON 0x03b)
(def ^:private KEY-F 0x066)
(def ^:private KEY-L 0x06c)
(def ^:private KEY-Y 0x079)

;; P4.T4 additions (gdkkeysyms.h, measured 2026-09-26 -- see the brief's key
;; table): Ctrl+Q quick doc, F12/Ctrl+B jump to definition, Shift+F1 quick
;; doc's second chord.
(def ^:private KEY-Q 0x071)
(def ^:private KEY-B 0x062)
(def ^:private KEY-F1 0xffbe)
(def ^:private KEY-F12 0xffc9)

;; P4.T5 addition (gdkkeysyms.h:380, re-verified against the installed
;; header): Ctrl+Space opens/commits completion.
(def ^:private KEY-SPACE 0x020)

;; P4.T7 additions (gdkkeysyms.h, re-verified against the installed
;; header, matching the brief's own measured values): Shift+F6/F2 open
;; the rename popover.
(def ^:private KEY-F6 0xffc3)
(def ^:private KEY-F2 0xffbf)

;; macOS hardware keycodes for the physical F and L keys (measured live
;; this session, pid-driven cgevent cmd+option+f/l against this build:
;; keycode 3 and 37 respectively, state carrying both ALT-MASK and
;; META-MASK correctly). These match Carbon/HIToolbox's own kVK_ANSI_F =
;; 0x03 and kVK_ANSI_L = 0x25 (37) -- Events.h:200 and :232 in the macOS
;; SDK's HIToolbox headers -- so they're the stable, well-known scan
;; positions for those two physical keys, not something guessed. See
;; handle-key!'s docstring for why keyval alone can't be used here.
(def ^:private MAC-KEYCODE-F 3)
(def ^:private MAC-KEYCODE-L 37)

(def ^:private open-chars
  {KEY-PARENLEFT \( KEY-BRACKETLEFT \[ KEY-BRACELEFT \{ KEY-QUOTEDBL \"})

(def ^:private close-chars
  {KEY-PARENRIGHT \) KEY-BRACKETRIGHT \] KEY-BRACERIGHT \}})

(defn- command-chord?
  "A command chord is Control or Meta (gtk-keys.md), as in Phase 2
  (jonquil.gtk.window/command-chord?, duplicated here since it's private
  there too)."
  [state]
  (not (zero? (bit-and state (bit-or g/CONTROL-MASK g/META-MASK)))))

(defn- shift-held? [state]
  (not (zero? (bit-and state g/SHIFT-MASK))))

(defn- alt-held? [state]
  (not (zero? (bit-and state g/ALT-MASK))))

(defn- no-command-modifiers?
  "True when none of Control, Meta or Alt is held -- the guard on row 7's
  bracket/quote/BackSpace/Tab keys, so they never shadow a command chord
  that happens to share a keyval."
  [state]
  (zero? (bit-and state (bit-or g/CONTROL-MASK g/META-MASK g/ALT-MASK))))

(defn- control-only?
  "P4 keys (quick doc, jump): Control held and Meta NOT held -- the P4
  rule (00-overview.md's global constraints) that these chords check
  CONTROL-MASK only and reject META-MASK, unlike command-chord? above
  which treats either as the same chord."
  [state]
  (and (not (zero? (bit-and state g/CONTROL-MASK)))
       (zero? (bit-and state g/META-MASK))))

(defn- no-modifiers?
  "True when none of Shift, Control, Meta or Alt is held -- F12's own P4
  requirement (\"no modifiers\"), stricter than no-command-modifiers?
  above, which doesn't check Shift."
  [state]
  (zero? (bit-and state (bit-or g/SHIFT-MASK g/CONTROL-MASK g/META-MASK g/ALT-MASK))))

(defn- apply-edit! [ed result]
  (if (apply-op! ed result) 1 0))

;; P4.T7: named so the context menu's own items (window.clj) call the
;; EXACT same function as their key, per the brief's own reviewer clause
;; -- these five used to be inline lambdas inside handle-key!'s cond
;; below, which a menu item could only have duplicated, never reused.
;; Each does exactly what its own inline body used to.

(defn do-toggle-comment! [ed]
  (apply-edit! ed (edit/toggle-comment (snapshot ed))))

(defn do-format-all! [ed]
  (apply-edit! ed (commands/format-all-op (snapshot ed))))

(defn do-format-selection! [ed]
  (apply-edit! ed (commands/format-selection-op (snapshot ed))))

(defn do-indent! [ed]
  (apply-edit! ed (edit/indent (snapshot ed))))

(defn do-unindent! [ed]
  (apply-op! ed (edit/unindent (snapshot ed))))

(defn- alt-chord-f-or-l?
  "True for Cmd/Ctrl+Alt+F or Cmd/Ctrl+Alt+L, on either platform.

  On Linux GDK, Option/Alt doesn't remap the keyval, so keyval alone
  (already normalised to lowercase by the caller) matches KEY-F/KEY-L
  directly -- the historical check.

  On macOS, GDK reports the OPTION-TRANSLATED character as keyval, not
  the plain letter (measured live this session, pid-driven cgevent:
  Cmd+Option+F arrives as keyval 0x8f6 = GDK_KEY_function, the florin
  sign; Cmd+Option+L arrives as keyval 0xac = GDK_KEY_notsign -- both
  confirmed against gdkkeysyms.h:1143 and :490). gdk_keyval_to_lower
  doesn't help: neither is an uppercase variant of f/l. The hardware
  keycode is unaffected by Option's translation and stays MAC-KEYCODE-F/
  MAC-KEYCODE-L (also measured live, matching kVK_ANSI_F/kVK_ANSI_L), so
  it's the fallback match.

  The `(< keyval 0x1000)` guard keeps that fallback from ever firing on a
  modifier key's own key-press event (Control_L's keyval is 0xffe3, way
  above this range) -- on standard XKB/evdev Linux keycodes, hardware
  keycode 37 belongs to Control_L and keycode 3 is unassigned, so without
  this guard a Ctrl+Alt press sequence could in principle mis-fire on the
  Control_L keystroke itself; the guard makes that impossible regardless
  of keyval-lower's actual value that day."
  [keyval keycode]
  (or (contains? #{KEY-F KEY-L} keyval)
      (and (< keyval 0x1000) (contains? #{MAC-KEYCODE-F MAC-KEYCODE-L} keycode))))

(defn handle-key!
  "The editor's key dispatch (P3.T2 gtk-keys.md), first match wins. Returns
  1 when it handled the key, 0 otherwise -- the key controller (on-key,
  below) only calls this, so self-test rows can call the exact same
  function with a synthetic keyval/keycode/state, never a synthesized OS
  keystroke. Yields (0) on every key while a preedit is active (I5), not
  just Return, since GTK/the IM context need every key while composing.
  keyval0 is normalised with gdk_keyval_to_lower before every comparison
  (M4, gtk-keys.md: same reason window.clj's on-window-key does it -- Caps
  Lock reports the uppercase keyval with no Shift bit set).

  Two arities: the 3-arg form (no keycode) is every pre-existing caller,
  defaulting keycode to 0 -- harmless, since 0 never matches
  MAC-KEYCODE-F/MAC-KEYCODE-L. on-key (below), the real GTK callback,
  always uses the 4-arg form so alt-chord-f-or-l?'s macOS fallback has a
  real hardware keycode to check."
  ([ed keyval0 state] (handle-key! ed keyval0 0 state))
  ([ed keyval0 keycode state]
   (let [keyval (g/gdk-keyval-to-lower keyval0)
         cfns @(:completion-fns ed)]
     (cond
       @(:preedit-active? ed) 0

       ;; P4.T5: completion's own nav/commit/escape keys (Up/Down/
       ;; PageUp/PageDown/Enter/Tab/Escape) get first look, ahead of
       ;; plain-return?/KEY-TAB below, whenever the popup is open --
       ;; jonquil.gtk.completion/handle-key! gates on that itself and
       ;; returns 0 otherwise, so this clause is a safe no-op the rest
       ;; of the time. See set-completion-fns!'s docstring.
       (when-let [f (:handle-key! cfns)] (= 1 (f keyval state)))
       1

       (plain-return? keyval state)
       (if (handle-return! ed) 1 0)

       ;; P4.T5: Ctrl+Space opens/commits completion. control-only? (not
       ;; command-chord?), the same P4 rule as quick doc/jump below --
       ;; CONTROL-MASK checked, META-MASK rejected. Always returns 1 once
       ;; the chord matches, whether or not a popup actually opens
       ;; (help/prefix-at nil, or a single candidate committed at once),
       ;; so GTK never inserts a literal space.
       (and (control-only? state) (= keyval KEY-SPACE))
       (do (when-let [f (:complete! cfns)] (f)) 1)

       (and (command-chord? state) (contains? #{KEY-SLASH KEY-SEMICOLON} keyval))
       (do-toggle-comment! ed)

       (and (command-chord? state) (shift-held? state) (= keyval KEY-F))
       (do-format-all! ed)

       (and (command-chord? state) (alt-held? state) (alt-chord-f-or-l? keyval keycode))
       (do-format-selection! ed)

       (and (command-chord? state) (= keyval KEY-Y))
       (if (not (zero? (g/gtk-text-buffer-get-can-redo (:buf ed))))
         (do (g/gtk-text-buffer-redo (:buf ed)) 1)
         0)

       ;; P4.T4: quick doc and jump. control-only? (not command-chord?)
       ;; because the P4 global constraint is CONTROL-MASK only, rejecting
       ;; META-MASK -- unlike every chord above, which treats Control and
       ;; Meta as the same command chord. F12 additionally requires NO
       ;; modifiers at all (no-modifiers?, stricter than no-command-
       ;; modifiers? above, which doesn't check Shift). Each runs the fn
       ;; jonquil.gtk.window registered (set-quick-doc-fn!/set-jump-fn!,
       ;; below) and returns 1 regardless of what that fn finds -- "acted"
       ;; means the chord matched, not that a symbol/definition turned up.
       (and (control-only? state) (= keyval KEY-Q))
       (do (when-let [f @(:quick-doc-fn ed)] (f)) 1)

       (and (shift-held? state) (= keyval KEY-F1))
       (do (when-let [f @(:quick-doc-fn ed)] (f)) 1)

       (and (no-modifiers? state) (= keyval KEY-F12))
       (do (when-let [f @(:jump-fn ed)] (f)) 1)

       (and (control-only? state) (= keyval KEY-B))
       (do (when-let [f @(:jump-fn ed)] (f)) 1)

       ;; P4.T7: rename. Shift+F6/F2, neither gated by control-only? --
       ;; matching Shift+F1/F12's own precedent just above (P4.T4), which
       ;; checks shift-held?/no-modifiers? alone with no Meta exclusion.
       ;; window.clj's own F6 clause already lets Shift+F6 fall through to
       ;; here (P4.T3's own key-order change); F2 has no other clause to
       ;; collide with.
       (and (shift-held? state) (= keyval KEY-F6))
       (do (when-let [f @(:rename-fn ed)] (f)) 1)

       (and (no-modifiers? state) (= keyval KEY-F2))
       (do (when-let [f @(:rename-fn ed)] (f)) 1)

       (and (no-command-modifiers? state) (contains? open-chars keyval))
       (apply-edit! ed (edit/insert-open (snapshot ed) (get open-chars keyval)))

       (and (no-command-modifiers? state) (contains? close-chars keyval))
       (apply-edit! ed (edit/insert-close (snapshot ed) (get close-chars keyval)))

       (and (no-command-modifiers? state) (= keyval KEY-BACKSPACE))
       (apply-edit! ed (edit/backspace-pair (snapshot ed)))

       ;; P3 fix wave C2: consumed (1) even when unindent returns nil (no
       ;; spanned line has leading whitespace), the way Tab always is.
       ;; Returning 0 handed the key to GtkTextView's own handler, which
       ;; commits a literal "\t" for Tab, KP_Tab and ISO_Left_Tab alike,
       ;; deleting the selection (gtk/gtktextview.c at the 4.22.4 tag,
       ;; lines 5744-5764).
       (and (no-command-modifiers? state)
            (or (= keyval KEY-ISO-LEFT-TAB) (and (= keyval KEY-TAB) (shift-held? state))))
       (do (do-unindent! ed) 1)

       (and (no-command-modifiers? state) (= keyval KEY-TAB))
       (do-indent! ed)

       :else 0))))

(defn- on-key [ed]
  (fn [_ctl keyval keycode state _data]
    (handle-key! ed keyval keycode state)))

;; --- construction ----------------------------------------------------------

(defn make-editor
  "Builds the Definitions editor: an editable, monospace GtkTextView with
  an 8px left / 4px top margin and undo enabled, inside a
  GtkScrolledWindow. Returns {:view :buf :scroll} plus internal
  bookkeeping keys."
  []
  (let [view (g/gtk-text-view-new)
        buf (g/gtk-text-view-get-buffer view)
        scroll (g/gtk-scrolled-window-new)
        keyctl (g/gtk-event-controller-key-new)
        focusctl (g/gtk-event-controller-focus-new)
        ed {:view view
            :buf buf
            :scroll scroll
            :tags (make-tags! buf)
            :saved-text (atom "")
            :last-dirty? (atom false)
            :on-dirty-fns (atom [])
            :on-cursor-fns (atom [])
            :on-focus-fns (atom [])
            :dirty-at (atom nil)
            :preedit-active? (atom false)
            ;; M7: starts true so the first tick always checks, establishing
            ;; a baseline; `changed` (below), set-text! and mark-saved! are
            ;; the only writers after that.
            :dirty-check-needed? (atom true)
            :dirty-check-count (atom 0)
            ;; P4.T4: single-slot registrations for the quick-doc/jump
            ;; chords -- see set-quick-doc-fn!/set-jump-fn!.
            :quick-doc-fn (atom nil)
            :jump-fn (atom nil)
            ;; P4.T7: single-slot registration for the rename chords --
            ;; see set-rename-fn!.
            :rename-fn (atom nil)
            ;; P4.T5: single-slot registration for completion -- see
            ;; set-completion-fns!. nil until jonquil.gtk.window wires it.
            :completion-fns (atom nil)}]
    (g/gtk-text-view-set-editable view 1)
    (g/gtk-text-view-set-monospace view 1)
    (g/gtk-text-view-set-left-margin view 8)
    (g/gtk-text-view-set-top-margin view 4)
    (g/gtk-text-buffer-set-enable-undo buf 1)
    (g/gtk-scrolled-window-set-child scroll view)
    (g/g-signal-connect-data buf "changed"
                             (callable (fn [_buf _data]
                                         (reset! (:dirty-at ed) (System/currentTimeMillis))
                                         (reset! (:dirty-check-needed? ed) true)
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; P3 fix wave I2: the buffer's "cursor-position" property notify, in
    ;; place of P3.T5's "mark-set" filtered to the insert mark. An insert or
    ;; delete moves the insert mark by gravity without emitting mark-set,
    ;; so Ln/Col went stale while typing. GtkTextBuffer notifies
    ;; cursor-position from real_insert_text, real_delete_range and
    ;; real_mark_set when the mark is the insert mark
    ;; (gtk/gtktextbuffer.c:1293, 2090 and 3067 at the 4.22.4 tag), so this
    ;; one signal covers typing, deletion and every mark-set case.
    ;; GObject::notify's callback is (instance, GParamSpec*, user_data).
    (g/g-signal-connect-data buf "notify::cursor-position"
                             (callable (fn [_buf _pspec _data]
                                         (doseq [f @(:on-cursor-fns ed)]
                                           (f (g/cursor-line-col buf)))
                                         nil)
                                       [:pointer :pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; I5: GtkTextView's OWN "preedit-changed" signal (distinct from
    ;; GtkIMContext's identically-named one) -- gtk/gtktextview.c:1529 per
    ;; Gtk-4.0.gir (not declared in the public gtktextview.h header; GTK
    ;; signals are registered at runtime and documented via GIR/gtk-doc,
    ;; not exposed as C symbols to grep for). Callback signature (instance,
    ;; gchar* preedit, user_data): [:pointer :string :pointer] :void.
    (g/g-signal-connect-data view "preedit-changed"
                             (callable (fn [_view preedit _data]
                                         (reset! (:preedit-active? ed) (not (empty? preedit)))
                                         nil)
                                       [:pointer :string :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; P4.T4: GtkEventControllerFocus's own "leave" signal (ffi.clj's
    ;; citation) -- fires whenever view (or a descendant) stops being the
    ;; root's focus widget. No signal args beyond instance/user_data.
    (g/g-signal-connect-data focusctl "leave"
                             (callable (fn [_ctl _data]
                                         (doseq [f @(:on-focus-fns ed)] (f))
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller view focusctl)
    (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
    (g/g-signal-connect-data keyctl "key-pressed"
                             (callable (on-key ed) [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller view keyctl)
    (g/g-timeout-add 20 (callable (fn [_data] (tick! ed)) [:pointer] :int) ffi/null)
    ed))
