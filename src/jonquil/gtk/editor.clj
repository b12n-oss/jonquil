(ns jonquil.gtk.editor
  "The Definitions editor: an editable, monospace GtkTextView owned by GTK,
  wired to jonquil.paint for live highlighting and jonquil.edit for smart
  Enter (the native widget owns the text; the core only ever
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

  repaint! removes and reapplies only the tags it owns (this namespace's
  paint/rainbow/unmatched set, keyed in :tags), never
  gtk_text_buffer_remove_all_tags over the whole buffer: that would strip
  any other tag over the same range too, including jonquil.gtk.find-bar's
  search-match tag, and since a tag-only repaint never fires `changed`,
  nothing would re-apply it.

  Every GTK call here already runs on the GTK thread: signal emission and
  g_timeout_add callbacks are both delivered by GTK's own main loop, so
  nothing in this namespace needs jonquil.gtk.callbacks/on-gui! to get
  there."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.commands :as commands]
            [jonquil.edit :as edit]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.ffi :as g]
            [jonquil.paint :as paint]
            [jonquil.parinfer-edit :as pe]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.theme :as theme]))

;; --- tags ----------------------------------------------------------------

(defn- tag-key-name [k]
  (if (vector? k) (str "rainbow-" (second k)) (name k)))

(defn- make-tags!
  "One GtkTextTag per paint/style key: every paint/token-styles key,
  [:rainbow 0..6], and :unmatched. :bold sets weight 700; :italic goes
  through set-italic!. Tags start with theme/default-theme's colours;
  apply-theme! retints them in place, never recreating them."
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

(defn rebase-saved!
  "Records s as the saved text without touching the buffer (a dirty
  tab's file was rewritten by a plugin step, so what's on disk is the
  baseline now). Marks a dirty recompute needed, as mark-saved! does."
  [ed s]
  (reset! (:saved-text ed) s)
  (reset! (:dirty-check-needed? ed) true)
  nil)

(defn mark-saved!
  "Records the current text as saved, so dirty? reads false until the next
  change. Marks a dirty recompute needed: this changes what dirty?
  will return without any buffer mutation, so no `changed` signal fires to
  trigger tick!'s usual recompute."
  [ed]
  (reset! (:saved-text ed) (editor-text ed))
  (reset! (:dirty-check-needed? ed) true)
  nil)

(defn- set-painted!
  "Records text and plan as what our tags show now (:painted), and restarts
  :edit-bounds at text's full length: no edit since. The two always move
  together, in one GTK-thread step."
  [ed text plan]
  (let [n (count text)]
    (reset! (:painted ed) {:text text :plan plan})
    (reset! (:edit-bounds ed) {:prefix n :suffix n})))

(defn- forget-painted!
  "Clears :painted and :edit-bounds: what our tags show is unknown, so the
  next tick repaints in full."
  [ed]
  (reset! (:painted ed) nil)
  (reset! (:edit-bounds ed) nil))

(defn- narrow-bounds
  "bounds after an edit that left the first prefix and last suffix chars of
  the pre-edit text alone. nil stays nil."
  [bounds prefix suffix]
  (when bounds
    {:prefix (min (:prefix bounds) prefix) :suffix (min (:suffix bounds) suffix)}))

(defn- arm-outside-span!
  "Arms the record of changes arriving outside the hook's sight, full
  length n, in the same step as a cache prime: the hook's own fix signals
  have already fired by then, so no busy flag is needed and every LATER
  signal narrows it. nil clears it alongside a
  cleared cache: no cache, nothing to recover from."
  [ed n]
  (reset! (:parinfer-outside-span ed) (when n {:prefix n :suffix n})))

(defn set-text!
  "Replaces the whole buffer inside begin/end_irreversible_action, so it
  leaves no undo step, and records s as the saved text in the same call --
  before the next tick can run -- so set-text! never itself marks dirty or
  fires on-dirty-changed!. Also marks a dirty recompute needed,
  belt-and-braces alongside the `changed` signal set-text buffer itself
  emits. Also supersedes any paint-async! still in flight (its :gen moves
  on). set-text! fully replaces the buffer, so a read-only editor
  (set-read-only!) reapplies its own read-only tag over the new text --
  the tag doesn't survive gtk_text_buffer_set_text, since that leaves no
  old text for the new text to inherit tags from. It also clears the
  tick's baseline (:painted), so the next repaint is a full one."
  [ed s]
  (swap! (:paint ed) update :gen inc)
  (forget-painted! ed)
  (let [buf (:buf ed)]
    (g/gtk-text-buffer-begin-irreversible-action buf)
    (g/gtk-text-buffer-set-text buf s -1)
    (g/gtk-text-buffer-end-irreversible-action buf)
    (reset! (:saved-text ed) s)
    ;; The new text is in hand, so the Parinfer cache primes for free and
    ;; the next keystroke is a hit. The gen is read after
    ;; the set, in this same step, so the prime can't be stale; no :edit-at,
    ;; so the first-edit re-indent still fires. GTK takes a C string, so a
    ;; NUL in s truncates what the buffer keeps: prime only when the counts
    ;; agree, else clear and the next keystroke misses honestly.
    (let [ok? (= (count s) (g/gtk-text-buffer-get-char-count buf))]
      (reset! (:parinfer-prev-cache ed)
              (when ok? {:text s :gen @(:edit-gen ed)}))
      (arm-outside-span! ed (when ok? (count s))))
    (when @(:read-only? ed)
      (g/with-iters* buf 0 (count s)
        (fn [a b] (g/gtk-text-buffer-apply-tag buf (:readonly-tag ed) a b)))))
  (reset! (:dirty-check-needed? ed) true)
  ;; Belt-and-braces alongside the buffer's own cursor notify (below), same
  ;; reasoning as the dirty-check-needed? line above it.
  (doseq [f @(:on-cursor-fns ed)] (f (g/cursor-line-col (:buf ed))))
  nil)

(defn reload-text!
  "Replaces the whole buffer with s, the file's text on disk, as ONE user
  action, so Cmd/Ctrl+Z right after brings back what the tab held (and
  the tab then reads dirty) while earlier undo history survives. Parinfer
  is held off through :parinfer-busy?, so it never adjusts the reloaded
  text. s becomes the saved text; the caret keeps its offset, clamped to
  the new end. A read-only editor goes through set-text! instead (no edit
  to undo there, and set-text! reapplies the read-only tag). The caller
  keeps NUL-bearing text out: GTK would truncate it."
  [ed s]
  (let [buf (:buf ed)
        at (min (g/cursor-offset buf) (count s))]
    (if @(:read-only? ed)
      (set-text! ed s)
      (do (swap! (:paint ed) update :gen inc)
          (forget-painted! ed)
          (reset! (:parinfer-busy? ed) true)
          (try
            (g/gtk-text-buffer-begin-user-action buf)
            (try
              (g/with-iters* buf 0 (g/gtk-text-buffer-get-char-count buf)
                (fn [a b] (g/gtk-text-buffer-delete buf a b)))
              (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-insert buf a s -1)))
              (g/with-iters* buf at at (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
              (finally (g/gtk-text-buffer-end-user-action buf)))
            (finally (reset! (:parinfer-busy? ed) false)))
          (rebase-saved! ed s)))
    (when @(:read-only? ed)
      (g/with-iters* buf at at (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
    nil))

(defn dirty?
  "True when the buffer's text differs from the last-saved text. Computed
  live against the buffer on every call, never cached, so it is correct
  immediately after set-text!/mark-saved! and can't be fooled by a
  tag-only repaint. A char-count mismatch answers true without copying the
  buffer, which is what keeps the per-edit tick cheap on large files."
  [ed]
  (let [saved @(:saved-text ed)]
    (if (not= (g/gtk-text-buffer-get-char-count (:buf ed)) (count saved))
      true
      (not= (editor-text ed) saved))))

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
  own \"notify::cursor-position\" (not mark-set, which an insert or delete
  never emits), and on set-text! (which
  resets the cursor without necessarily firing mark-set itself, same
  belt-and-braces reasoning as dirty-check-needed?). Multiple
  registrations fire in registration order, mirroring on-dirty-changed!."
  [ed f]
  (swap! (:on-cursor-fns ed) conj f)
  nil)

(defn on-focus-lost!
  "Registers f (fn [] -> nil) to be called, on the GTK thread, whenever
  the editor's view (or a descendant) stops being the root's focus widget
  -- GtkEventControllerFocus's own \"leave\" signal, which tracks
  focus-within independent of OS window-activation, unlike
  gtk_widget_has_focus (jonquil.gtk.window.keys's do-toggle-focus!
  docstring). Multiple registrations fire in registration order, mirroring
  on-dirty-changed!/on-cursor-moved!."
  [ed f]
  (swap! (:on-focus-fns ed) conj f)
  nil)

(defn set-error-fn!
  "Registers f (fn [msg] -> nil), the ui's own error reporter (set-status!
  on the window's status label), to run whenever a key dispatch or a
  listener call throws. A single slot, same late-registration shape as
  set-quick-doc-fn! below -- ed is built before the status label exists."
  [ed f]
  (reset! (:on-error-fn ed) f)
  nil)

(defn set-quick-doc-fn!
  "Registers f (fn [] -> nil), the ui's own :quick-doc!* action, to run
  for the editor's quick-doc chords (Ctrl+Q, Shift+F1). A single slot, not
  a list like on-dirty-changed!/on-cursor-moved!/on-focus-lost! above --
  there is exactly one owner (jonquil.gtk.window.tabs's wire-tab!), set once
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

(defn set-run-form-fn!
  "Registers f (fn [] -> nil), the ui's own :run-form!* action, to run for
  the editor's Run Form chord (Cmd/Ctrl+Enter). See
  set-quick-doc-fn!."
  [ed f]
  (reset! (:run-form-fn ed) f)
  nil)

(defn set-flash-cancel-fn!
  "Registers f (fn [] -> nil), Run Form's own flash cancel
  (jonquil.gtk.window.run's make-flash-fn, called from handle-key! below
  before any other key handling). See set-quick-doc-fn!."
  [ed f]
  (reset! (:flash-cancel-fn ed) f)
  nil)

(defn set-completion-fns!
  "Registers fns ({:complete! (fn [] -> nil) :handle-key! (fn [keyval
  state] -> 0/1)}), the ui's own completion actions: :complete! is
  the editor's Ctrl+Space chord's own action (jonquil.gtk.window's
  :complete!*, single slot, same reasoning as set-quick-doc-fn!/
  set-jump-fn!'s own docstrings). :handle-key! is asked FIRST, ahead of
  every other clause in handle-key!'s own cond, for every key that isn't a
  preedit -- jonquil.gtk.completion/handle-key! itself gates on whether
  completion is actually open and returns 0 for every key otherwise, so
  this is a safe, cheap no-op call before completion is wired (this
  namespace's own self-test callers) or while the popup is
  closed. Completion's OWN close conditions (a caret move out of the word,
  focus lost, another popover opening) are wired by jonquil.gtk.window
  through the EXISTING on-cursor-moved!/on-focus-lost! hooks above, not a
  third slot here -- see jonquil.gtk.completion's own ns docstring."
  [ed fns]
  (reset! (:completion-fns ed) fns)
  nil)

(defn set-profile!
  "Paints ed with profile's own :special-forms and :builtins (a
  jonquil.dialect profile; nil paints with jonquil.syntax's, the Jolt
  profile's). The repaint happens on the next 20 ms tick, the same path a
  keystroke's repaint takes, so this is safe from the GTK thread at any
  point, including before the view is shown."
  [ed profile]
  (reset! (:profile ed) profile)
  (reset! (:dirty-at ed) 0)
  nil)

(defn focus! [ed]
  (g/gtk-widget-grab-focus (:view ed))
  nil)

(defn set-read-only!
  "Makes ed read-only (true) or editable again (false). Read-only: the
  view refuses typing, paste, cut and GTK's own undo (its editable
  property is off); every key handle-key! turns into an edit is a no-op
  (apply-op! and the redo clause check this flag); and the 20 ms tick never
  repaints, since a read-only editor paints through paint-async! instead.
  Caret movement, selection, copy and the F12/Ctrl+B jump slot still work.

  The view's own editable property (set above) only gates the view's OWN key/IM/paste handling -- it has no
  effect on a direct buffer call like
  gtk_text_buffer_insert_interactive_at_cursor, which checks per-range
  `editable` tags against its own default_editable argument, independent of
  any view. Toggling only the view's editable property let a buffer-level insert
  through with the editor read-only (measured). jonquil.gtk.console-view
  already solves exactly this for its own read-only regions with a
  `readonly-tag` (editable false); this editor applies that same tag over
  the whole buffer here, and set-text! (above) reapplies it after a full
  text replacement.

  Also supersedes any paint-async! still in flight (its
  :gen moves on), the same way set-text! does -- toggling read-only off
  mid-paint re-permits apply-op! edits, and a chunk from the old paint
  landing afterward would apply tags at offsets computed from text that
  no longer exists."
  [ed read-only?]
  (reset! (:read-only? ed) (boolean read-only?))
  (swap! (:paint ed) update :gen inc)
  (g/gtk-text-view-set-editable (:view ed) (if read-only? 0 1))
  (let [buf (:buf ed)
        tag (:readonly-tag ed)
        len (count (g/buffer-text buf))]
    (g/with-iters* buf 0 len
      (fn [a b]
        (if read-only?
          (g/gtk-text-buffer-apply-tag buf tag a b)
          (g/gtk-text-buffer-remove-tag buf tag a b)))))
  nil)

(defn apply-theme!
  "Retints every tag in (:tags ed) to theme t's colours: sets each
  tag's foreground to (:fg (paint/style t k)). Only ever touches tag
  properties -- never buffer text, the selection or undo history -- so
  repaint! keeps working unchanged against the same tag objects, which
  already carry the new colours by the time any later repaint runs."
  [ed t]
  (doseq [[k tag] (:tags ed)]
    (g/set-string-property! tag "foreground" (:fg (paint/style t k))))
  nil)

;; --- snapshot / apply-op! ------------------------------

(defn snapshot
  "{:text :caret :sel-start :sel-end} for the core edit fns in jonquil.edit."
  [ed]
  (let [buf (:buf ed)
        [a b] (g/selection-offsets buf)]
    {:text (g/buffer-text buf) :caret (g/cursor-offset buf) :sel-start a :sel-end b}))

(defn apply-op!
  "Applies an edit-op result inside one user action, back to front, then
  places the caret -- or, when result has :sel [a b] with a < b (indent,
  unindent, toggle-comment on a selection), selects that range
  instead, so a chained op (a second Tab) sees the same selection through
  snapshot. gtk_text_buffer_select_range's first iter argument becomes the
  insert mark (measured: reading the insert mark back after select_range(a,
  b) shows it lands on a, the FIRST argument). :caret is NOT
  always the selection's high end (b) -- indent/unindent/insert-open do
  put it there, but toggle-comment's :caret comes from shift-offset on the
  raw pre-edit caret, which can land on EITHER end depending on which way
  the user dragged the selection (a Cmd+/ on a backward-drag selection has
  :caret = a, not b; verified against (edit/toggle-comment {:text \"(+ 1
  2)\" :caret 0 :sel-start 0 :sel-end 7}) => {:caret 2 :sel [2 9]}, where
  2 = a). So the iter that matches :caret goes first, whichever end that
  is, not always b. Returns true when the op applied, nil when result was
  nil. A read-only editor (set-read-only!) applies nothing and returns
  nil. An op whose edit text holds a NUL byte is refused the same way:
  GTK text is C strings, so the insert would silently cut everything
  after it. Every op that brings outside text in (commands,
  plugin :edit ops, MCP and plugin replaces) funnels through here, so none
  can truncate a buffer. The console's input ops are a separate funnel, but
  nothing saves from the console."
  [ed result]
  (when (and result
             (not @(:read-only? ed))
             (not-any? (fn [[_ _ r]] (str/includes? r "\u0000")) (:edits result)))
    (let [buf (:buf ed)
          [sel-a sel-b] (:sel result)
          caret (:caret result)]
      (g/gtk-text-buffer-begin-user-action buf)
      (try
        (doseq [[s e r] (rseq (vec (:edits result)))]
          (g/with-iters* buf s e (fn [a b] (g/gtk-text-buffer-delete buf a b)))
          (g/with-iters* buf s s (fn [a _] (g/gtk-text-buffer-insert buf a r -1))))
        ;; The caret or selection lands INSIDE the user action:
        ;; the Parinfer hook runs at end-user-action, so it must see the
        ;; op's caret, and anything it places must land last.
        (if (and sel-a sel-b (< sel-a sel-b))
          (let [caret-at-a? (= caret sel-a)
                ins (if caret-at-a? sel-a sel-b)
                bound (if caret-at-a? sel-b sel-a)]
            (g/with-iters* buf ins bound
              (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2))))
          (g/with-iters* buf caret caret
            (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
        (finally (g/gtk-text-buffer-end-user-action buf))))
    true))

(defn- parinfer-mode
  "The Parinfer mode this edit runs under, or nil when Parinfer leaves the
  buffer alone: off, the hook's own apply, an exempt edit (MCP), a
  read-only editor, or an IME preedit composing."
  [ed]
  (let [m ((deref (:parinfer-mode-fn ed)))]
    (when (and (contains? #{:smart :indent :paren} m)
               (not @(:parinfer-busy? ed))
               (not @(:parinfer-skip? ed))
               (not @(:read-only? ed))
               (not @(:preedit-active? ed)))
      m)))

(defn- parinfer-asides?
  "Whether the typed-bracket and pair-Backspace step-asides are on: Smart
  and Indent balance brackets, but Paren mode doesn't, so there they'd
  leave the buffer unbalanced."
  [ed]
  (contains? #{:smart :indent} (parinfer-mode ed)))

(defn- empty-string-pair?
  "Whether snap's caret sits inside an empty \"\": Backspace
  must take both quotes, since a lone quote uncloses the form."
  [{:keys [text caret]}]
  (and (pos? caret) (< caret (count text))
       (= \" (nth text (dec caret)))
       (= \" (nth text caret))))

(defn- post-action-text
  "The buffer's text after the action, for parinfer-after-action!."
  [buf prev span]
  ;; On a cache hit with an armed span, the post-action text is
  ;; the cached head, the changed middle read from the buffer,
  ;; and the cached tail: three exact pieces (the span's head
  ;; and tail are untouched by construction), 0.12 ms against
  ;; the 10.8 ms full read. The length guard
  ;; only catches a ranged read disagreeing with GTK's char
  ;; count (embedded objects, hidden text); a wrong span is
  ;; invisible to it, which is why the span must stay sound.
  ;; A miss's prev is a fresh read of the begin-time buffer,
  ;; ground truth by construction, so the splice doesn't need
  ;; the cache.
  (or (when span
        (let [pt ^String (:text prev)
              n (count pt)
              m (g/gtk-text-buffer-get-char-count buf)
              p (min (:prefix span) n m)
              q (min (:suffix span) (- n p) (- m p))
              spliced (str (subs pt 0 p)
                           (g/buffer-text-range buf p (- m q))
                           (subs pt (- n q)))]
          (when (= (count spliced) m) spliced)))
      (g/buffer-text buf)))

(defn- prev-consistent?
  "Whether the last action's form, by its edit start, contains this
  edit's start, for pe/adjust's :prev-consistent?."
  [prev span]
  (boolean
   (and (:cached? prev) span (some? (:edit-at prev))
        ;; one region call, on the prev text the
        ;; painter's tick has already bracketed:
        ;; the last action's form, by its edit
        ;; start, must contain this edit's start
        (let [pt (:text prev)
              n (count pt)
              [ls le] (pe/region pt (min (:edit-at prev) n))]
          (<= ls (:prefix span) le)))))

(defn- apply-fix-keeping-selection!
  "Applies the fix r inside a nested user action and selects [sa sb]
  mapped through it."
  [{:keys [buf text r sa sb]}]
  (let [fixed (edit/apply-edits text (:edits r))
        remap (fn [x] (pe/remap-offset text fixed x))]
    (g/gtk-text-buffer-begin-user-action buf)
    (try
      (doseq [[s e rep'] (rseq (vec (:edits r)))]
        (g/with-iters* buf s e (fn [a b] (g/gtk-text-buffer-delete buf a b)))
        (g/with-iters* buf s s (fn [a _] (g/gtk-text-buffer-insert buf a rep' -1))))
      (g/with-iters* buf (remap sa) (remap sb)
        (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
      (finally (g/gtk-text-buffer-end-user-action buf)))))

(defn- apply-fix!
  "Applies an adjusted result r with the hook's own apply guarded by
  :parinfer-busy?."
  [ed {:keys [buf text r]}]
  (when (map? r)
    (reset! (:parinfer-busy? ed) true)
    (try
      (let [[sa sb] (g/selection-offsets buf)]
        (if (and sa sb (< sa sb))
          ;; The op selected a range (indent, toggle-comment
          ;; on lines): the selection survives the fix, each
          ;; end mapped through the fix's one edit. A delete would swallow the marks, so
          ;; following GTK's own mark motion isn't enough.
          (apply-fix-keeping-selection! {:buf buf :text text :r r :sa sa :sb sb})
          (apply-op! ed r)))
      (finally (reset! (:parinfer-busy? ed) false)))))

(defn- use-result!
  "Says a too-long form or an engine error, or applies r and clears the
  status."
  [ed {:keys [buf text r status]}]
  (cond
    (= ::pe/too-long r)
    (when status
      (status (str "over " pe/default-max-lines " lines here, not adjusted")))

    ;; A Jolt shim gap the engine caught (parinfer-edit): name it
    ;; once; the buffer stays as typed.
    (:engine-error r)
    (when-let [f @(:on-error-fn ed)]
      (f (str "; parinfer: " (:engine-error r))))

    :else
    (do (apply-fix! ed {:buf buf :text text :r r})
        (when status (status nil)))))

(defn- prime-next-action!
  "Primes the next action's prev from the final text and arms the
  outside span."
  [ed {:keys [text r prev span]}]
  ;; prime the next action's prev: the final text without another
  ;; buffer copy, with the gen read after our own apply bumped it
  (let [fixed? (and (map? r) (:edits r))
        final (if fixed? (edit/apply-edits text (:edits r)) text)]
    (reset! (:parinfer-prev-cache ed)
            {:text final :gen @(:edit-gen ed)
             ;; Where this action edited, in final coordinates, and
             ;; only when Parinfer really probed a changed form:
             ;; the fix can move text before the offset, so it's
             ;; carried across by line and column, and
             ;; a no-op action, a too-long skip or an engine error
             ;; probed nothing, so nothing is marked consistent.
             :edit-at (when (and span
                                 (not= text (:text prev))
                                 (or (nil? r) fixed?))
                        (if fixed?
                          (pe/remap-offset text final (:prefix span))
                          (:prefix span)))})
    ;; after the prime, in this same step: the fix's own signals
    ;; have fired, so everything narrowing it from here on really
    ;; is outside the hook's sight
    (arm-outside-span! ed (count final))))

(defn- parinfer-failed!
  "A throw in the hook: drops the cache and the outside span, and names
  the error."
  [ed e]
  (reset! (:parinfer-prev-cache ed) nil)
  (arm-outside-span! ed nil)
  (when-let [f @(:on-error-fn ed)]
    (f (str "; parinfer: " (ex-message e)))))

(defn- parinfer-after-action!
  "The Parinfer hook at end-user-action: parinfer-edit/adjust from the
  begin-user-action snapshot, applied inside a nested user action, which
  GTK folds into the keystroke's own undo group (probed), so one
  undo reverts the keystroke and the fix together. ::pe/too-long goes to
  :parinfer-status-fn, which it clears again on the next adjusted edit; an
  {:engine-error msg} (a Jolt shim gap) is named through :on-error-fn, and
  a throw is reported the same way with the buffer left as typed."
  [ed]
  (when-let [prev @(:parinfer-prev ed)]
    (reset! (:parinfer-prev ed) nil)
    (let [span @(:parinfer-span ed)]
      (reset! (:parinfer-span ed) nil)
      (when-let [mode (parinfer-mode ed)]
        (try
          (let [buf (:buf ed)
                text (post-action-text buf prev span)
                r (pe/adjust {:mode mode
                              :prev-text (:text prev) :prev-caret (:caret prev)
                              :text text :caret (g/cursor-offset buf)
                              ;; the signals' untouched head/tail replaces the
                              ;; whole-buffer diff. The re-indent probe skips
                              ;; only when this edit starts in the SAME form
                              ;; the previous action edited: a cache hit
                              ;; proves the buffer is our last output, not
                              ;; that the form under the caret ever met
                              ;; Parinfer (a caret move changes no gen), so
                              ;; same-form is the part that keeps the user's
                              ;; first-edit re-indent.
                              :bounds (when span [(:prefix span) (:suffix span)])
                              :prev-consistent? (prev-consistent? prev span)})
                status @(:parinfer-status-fn ed)]
            (use-result! ed {:buf buf :text text :r r :status status})
            (prime-next-action! ed {:text text :r r :prev prev :span span}))
          (catch :default e
            (parinfer-failed! ed e)))))))

(defn set-parinfer-fns!
  "Wires the window's Parinfer reads: mode-fn answers the current mode
  keyword, and status-fn takes one line for the status bar (nil clears a
  line it set)."
  [ed mode-fn status-fn]
  (reset! (:parinfer-mode-fn ed) mode-fn)
  (reset! (:parinfer-status-fn ed) status-fn))

(defn set-format-style-fn!
  "Wires the window's per-tab read of .jonquil.edn's :format-style:
  style-fn answers :paren-stable, :plain or nil (follow the Parinfer
  mode, the default)."
  [ed style-fn]
  (reset! (:format-style-fn ed) style-fn))

(defn apply-op-plain!
  "apply-op! with the Parinfer hook skipped, so an MCP edit or another
  programmatic change lands exactly as sent. The op knows its own edits,
  so a cache that is valid going in stays valid coming out, applied to
  the cached string with no buffer read; an invalid one
  is cleared, never half-trusted, and the next keystroke misses honestly."
  [ed result]
  (reset! (:parinfer-skip? ed) true)
  (try
    (let [cache @(:parinfer-prev-cache ed)
          valid? (and cache (= (:gen cache) @(:edit-gen ed)))
          r (apply-op! ed result)]
      (let [v (when (and valid? r)
                ;; The count guard: keep the update only when the cached
                ;; string's length matches what the buffer really kept,
                ;; else clear, never half-trust. A NUL op no
                ;; longer reaches here (apply-op! refuses it); this guards
                ;; any future path that lands text the buffer shortened.
                (let [t (edit/apply-edits (:text cache) (:edits result))]
                  (when (= (count t)
                           (g/gtk-text-buffer-get-char-count (:buf ed)))
                    {:text t :gen @(:edit-gen ed)})))]
        (reset! (:parinfer-prev-cache ed) v)
        (arm-outside-span! ed (some-> v :text count)))
      r)
    (finally (reset! (:parinfer-skip? ed) false))))

(defn handle-return!
  "Applies jonquil.edit/smart-enter to the current snapshot as one user
  action. Returns nil, applying nothing, while an IME preedit composition
  is active: the capture-phase key controller runs before the IM
  context gets to filter the key, so consuming Return here would split or
  lose a pending Hangul/CJK composition. handle-key! (below) treats this
  the same as any other unhandled key: 0, so GTK/the IM context gets it."
  [ed]
  (when-not @(:preedit-active? ed)
    ;; With Parinfer on, the closer is Parinfer's to manage, so the
    ;; three-line between-pair open steps aside.
    (apply-op! ed (edit/smart-enter (snapshot ed)
                                    {:between-pair? (not (parinfer-asides? ed))}))))

;; --- repaint + the debounce/dirty tick -------------------------------------

(def ^:private PAINT-CHUNK
  ;; estimate: a full repaint of clojure/core.clj (8,233 lines, 26,816
  ;; spans) measured 383-415 ms, paint-plan alone 280-357 ms, so applying
  ;; tags costs ~4 us a span; 2,000 spans is ~8 ms of GTK time per turn.
  2000)

(defn- nearest-first
  "spans sorted by how far each starts from caret, nearest first, so the
  visible region colours first."
  [caret spans]
  (sort-by (fn [[_ s _]] (Math/abs (long (- s caret)))) spans))

(defn- report-paint-error!
  "Reports a paint throw through set-error-fn!'s reporter. GTK thread."
  [ed e]
  (when-let [f @(:on-error-fn ed)]
    (f (str "; paint: " (ex-message e)))))

(defn- paint-chunked!
  "Removes our own tags over [a b), then applies spans PAINT-CHUNK at a
  time: the first chunk in this same GTK turn, so a small re-tag never
  shows uncoloured text, and each later chunk in its own on-gui! turn.
  With :defer-first? the first chunk also gets its own turn, which keeps a
  full-buffer remove pass and a chunk out of the same turn. A later turn
  first asks (current?), and on false drops the rest and calls
  (superseded!). (done!) runs in the turn that lands the last chunk. A
  throw in a later turn goes to (fail! e). Shared by paint-async!'s full
  paint and the tick's incremental one. Call it on the GTK thread."
  [ed [a b] spans {:keys [current? done! superseded! fail! defer-first?]}]
  (letfn [(later [chunks]
            (on-gui! (fn []
                       (try
                         (if (current?) (step chunks) (superseded!))
                         (catch :default e (fail! e))))))
          (step [chunks]
            (doseq [[k s e] (first chunks)]
              (when-let [tag (get (:tags ed) k)]
                (g/with-iters* (:buf ed) s e (fn [i j] (g/gtk-text-buffer-apply-tag (:buf ed) tag i j)))))
            (if-let [more (next chunks)]
              (later more)
              (done!)))]
    ;; Remove only OUR OWN tags, one at a time, never remove_all_tags: a tag another namespace applies over the same
    ;; range (jonquil.gtk.find-bar's search-match) must survive a repaint.
    (doseq [tag (vals (:tags ed))]
      (g/with-iters* (:buf ed) a b (fn [i j] (g/gtk-text-buffer-remove-tag (:buf ed) tag i j))))
    (let [chunks (partition-all PAINT-CHUNK spans)]
      (if defer-first? (later chunks) (step chunks)))))

(defn paint-async!
  "Repaints ed without holding the GTK thread for the whole text:
  paint-plan runs on a future, then its spans are applied PAINT-CHUNK at a
  time, each chunk its own on-gui! turn, nearest the caret first so the
  visible region colours first. A later paint-async! or set-text!
  supersedes one still in flight: every turn checks the generation it
  belongs to. (:paint ed) reads {:gen n :done? bool :full-gen n :edit-gen n
  :profile p}. Call it on the GTK
  thread, right after set-text! and placing the caret. A throw is
  reported through set-error-fn!'s reporter as \"; paint: ...\".

  current? also requires the editor not be destroyed: a chunk's own
  on-gui! turn can land after the window that owns ed's view has closed
  (F12 into a large file, then close that window before colouring
  finishes), and every GTK call below -- the remove-tag pass and each
  chunk's apply-tag -- would otherwise run against a buffer whose widget
  is already gone. A superseded-by-destroy paint never marks itself
  :done?, the same as any other superseded paint, so nothing but the
  :destroyed? flag need be read to tell the two apart.

  The tags are unknown while the paint runs, so it
  clears the tick's baseline (:painted) at the start, and records the
  baseline when its last chunk lands, unless an edit came in meanwhile
  (:edit-gen moved), since its text would then be stale. (:paint ed) also
  carries :full-gen, :edit-gen and :profile, which tell the tick a full
  paint is already on its way (full-paint-pending?). An edit while it runs
  supersedes it too, since its spans carry the old text's offsets: the
  rest is dropped, the baseline stays nil and :dirty-at is re-armed, so the
  next tick repaints.

  The first chunk gets its own turn (:defer-first? true), so the colours
  the paint clears are not on screen for a frame with nothing applied. The
  tick's fallback passes {:defer-first? false}: typing that supersedes a
  chunked apply would otherwise show a frame with every colour removed."
  ([ed] (paint-async! ed {:defer-first? true}))
  ([ed {:keys [defer-first?]}]
   (let [{:keys [buf]} ed
         text (g/buffer-text buf)
         caret (g/cursor-offset buf)
         profile @(:profile ed)
         edit-gen @(:edit-gen ed)
         gen (:gen (swap! (:paint ed) (fn [p]
                                        (let [n (inc (:gen p))]
                                          {:gen n :done? false :full-gen n
                                           :edit-gen edit-gen :profile profile}))))
         len (count text)
         current? (fn [] (and (= gen (:gen @(:paint ed)))
                              (= edit-gen @(:edit-gen ed))
                              (not @(:destroyed? ed))))
        ;; An edit, a newer paint or a destroy drops the rest. The baseline
        ;; is already nil, so re-arming the tick makes it repaint.
         superseded! (fn [] (reset! (:dirty-at ed) (System/currentTimeMillis)))
         report (fn [e] (on-gui! (fn [] (report-paint-error! ed e))))]
     (forget-painted! ed)
     (future
       (try
         (let [plan (paint/paint-plan profile text)
               spans (nearest-first caret plan)]
           (on-gui! (fn []
                      (try
                        (if-not (current?)
                          (superseded!)
                          (paint-chunked! ed [0 len] spans
                                          {:current? current?
                                           :done! (fn []
                                                    (swap! (:paint ed) (fn [p] (if (= gen (:gen p)) (assoc p :done? true) p)))
                                                    (when (= edit-gen @(:edit-gen ed))
                                                      (set-painted! ed text plan)))
                                           :superseded! superseded!
                                           :fail! report
                                           :defer-first? defer-first?}))
                        (catch :default e (report e))))))
         (catch :default e (report e))))
     nil)))

(defn- full-paint-pending?
  "True while a paint-async! is in flight that will record the baseline
  when it lands: nothing has superseded it, and neither the text nor the
  profile has changed since it started."
  [ed]
  (let [p @(:paint ed)]
    (and (not (:done? p))
         (= (:gen p) (:full-gen p))
         (= (:edit-gen p) @(:edit-gen ed))
         (= (:profile p) @(:profile ed)))))

(defn- repaint!
  "The tick's repaint. With a baseline (:painted), it
  reads the text, :edit-gen, :edit-bounds and the profile here, on the GTK
  thread, then computes the plan and paint/plan-diff against the baseline
  on a future. Back on the GTK thread the result lands only when no edit
  came in, the editor is alive and the baseline is the same object the
  future diffed against. A nil diff only moves the baseline. A diff clears
  our tags over its :range and applies its :apply spans through
  paint-chunked!, nearest the caret first. While that takes more than one
  turn the baseline is nil, and it is recorded when the last chunk lands.
  A dropped result, a superseded chunked apply or a throw re-arms
  :dirty-at so the next tick tries again; a throw also clears the
  baseline, so that try is a full repaint. A drop re-arms only when no
  later tick has started its own future, which will land in its place.
  Without a baseline it starts paint-async!, unless one is already on its
  way."
  [ed]
  (if-let [painted @(:painted ed)]
    (let [{:keys [buf]} ed
          text (g/buffer-text buf)
          caret (g/cursor-offset buf)
          edit-gen @(:edit-gen ed)
          bounds @(:edit-bounds ed)
          profile @(:profile ed)
          req (swap! (:repaint-req ed) inc)
          rearm! (fn [] (reset! (:dirty-at ed) (System/currentTimeMillis)))
          fail! (fn [e]
                  (when-not @(:destroyed? ed) (report-paint-error! ed e))
                  (forget-painted! ed)
                  (rearm!))
          current? (fn [] (and (= edit-gen @(:edit-gen ed)) (not @(:destroyed? ed))))]
      (future
        (try
          (let [plan (paint/paint-plan profile text)
                diff (paint/plan-diff (:text painted) (:plan painted) text plan bounds)
                spans (nearest-first caret (:apply diff))]
            (on-gui!
             (fn []
               (try
                 (cond
                   (not (and (current?) (identical? painted @(:painted ed))))
                   (do (swap! (:repaint-drops ed) inc)
                       (when (= req @(:repaint-req ed)) (rearm!)))

                   (nil? diff) (set-painted! ed text plan)

                   :else
                   (let [gen (:gen (swap! (:paint ed) (fn [p] (assoc p :gen (inc (:gen p)) :done? false))))]
                     (forget-painted! ed)
                     (paint-chunked! ed (:range diff) spans
                                     {:current? (fn [] (and (current?) (= gen (:gen @(:paint ed)))))
                                      :done! (fn []
                                               (swap! (:paint ed) assoc :done? true)
                                               (set-painted! ed text plan))
                                      :superseded! rearm!
                                      :fail! fail!})))
                 (catch :default e (fail! e))))))
          (catch :default e (on-gui! (fn [] (fail! e)))))))
    (when-not (full-paint-pending? ed)
      (paint-async! ed {:defer-first? false}))))

(defn- tick! [ed]
  ;; The g_timeout_add that calls this every 20ms outlives the editor's own
  ;; window (it's never removed on destroy), so a tick can still fire after
  ;; the view -- and its buffer -- are gone, same class of bug as
  ;; paint-async!'s current? check above. Once :destroyed? is true, touch
  ;; nothing (not even dirty?, which reads the buffer) and answer
  ;; G_SOURCE_REMOVE (0) so GTK stops calling this source at all, instead
  ;; of G_SOURCE_CONTINUE (1).
  (if @(:destroyed? ed)
    0
    (do
      ;; dirty? copies and compares the whole buffer, so it must not run
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
          (when-not @(:read-only? ed) (repaint! ed))))
      1)))

;; --- keys: Return/KP-Enter with no Shift/Control/Meta ---------------------

(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-KP-ENTER 0xff8d)

(defn- plain-return?
  "Return or KP-Enter with none of Shift, Control or Meta held (a command
  chord is Control or Meta; Shift+Return is left alone so GTK inserts a
  plain newline)."
  [keyval state]
  (and (contains? #{KEY-RETURN KEY-KP-ENTER} keyval)
       (zero? (bit-and state (bit-or g/SHIFT-MASK g/CONTROL-MASK g/META-MASK)))))

;; --- keys: the rest of the dispatch ---------------------------------

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

;; macOS hardware keycodes for the physical F and L keys (measured live with
;; pid-driven cgevent cmd+option+f/l against this build:
;; keycode 3 and 37 respectively, state carrying both ALT-MASK and
;; META-MASK correctly). These match Carbon/HIToolbox's own kVK_ANSI_F =
;; 0x03 and kVK_ANSI_L = 0x25 (37) -- Events.h:200 and :232 in the macOS
;; SDK's HIToolbox headers -- so they're the stable, well-known scan
;; positions for those two physical keys, not something guessed. See
;; physical-key's docstring for why keyval alone can't be used here.
(def ^:private MAC-KEYCODE-F 3)
(def ^:private MAC-KEYCODE-L 37)
(def ^:private MAC-KEYCODE-P 35)
;; the digit row's keycodes, the panel keys' Cmd+Option+1..8; Apple's
;; kVK_ANSI_1..8, the table F, L and P above were measured against
(def ^:private MAC-KEYCODE-DIGITS {18 "1" 19 "2" 20 "3" 21 "4" 23 "5" 22 "6" 26 "7" 28 "8"})

;; os.name read once. The keycode fallback is macOS-only: on Linux X11
;; keycode 37 is Control_L.
(def ^:private OS-NAME (System/getProperty "os.name"))

(def ^:private open-chars
  {KEY-PARENLEFT \( KEY-BRACKETLEFT \[ KEY-BRACELEFT \{ KEY-QUOTEDBL \"})

(def ^:private close-chars
  {KEY-PARENRIGHT \) KEY-BRACKETRIGHT \] KEY-BRACERIGHT \}})

(defn- shift-held? [state]
  (not (zero? (bit-and state g/SHIFT-MASK))))

(defn- alt-held? [state]
  (not (zero? (bit-and state g/ALT-MASK))))

(defn- modifier-keyval?
  "True for a modifier key's own keyval -- Shift/Control/Meta/Alt/Super/
  Hyper, Caps Lock or Shift Lock, either side (gdkkeysyms.h 0xffe1-0xffee,
  a contiguous block). The flash-cancel guard in handle-key! below
  must never fire on the Cmd/Ctrl/Shift/Alt press itself, only on the key
  that follows it."
  [keyval]
  (<= 0xffe1 keyval 0xffee))

(defn- no-command-modifiers?
  "True when none of Control, Meta or Alt is held -- the guard on row 7's
  bracket/quote/BackSpace/Tab keys, so they never shadow a command chord
  that happens to share a keyval."
  [state]
  (zero? (bit-and state (bit-or g/CONTROL-MASK g/META-MASK g/ALT-MASK))))

(defn- apply-edit! [ed result]
  (if (apply-op! ed result) 1 0))

;; Named (not inline lambdas in handle-key!) so the context menu's items
;; (jonquil.gtk.window.tabs) call the EXACT same function as their key.

(defn do-toggle-comment! [ed]
  (apply-edit! ed (edit/toggle-comment (snapshot ed))))

(defn- scroll-to-caret!
  "Brings the insert mark into view. Formatting replaces the whole buffer,
  so the caret can land far from where the view was.
  scroll_to_mark rather than scroll_mark_onscreen: right after a
  whole-buffer replace the layout isn't valid yet, and scroll_mark_onscreen
  scrolls by it at once (measured: the caret stayed 5,000 px below a
  300 px view), while scroll_to_mark waits for the layout. No alignment,
  so it scrolls only as far as it has to. Once more from an idle turn,
  too: on a slow first run the deferred scroll still used estimated line
  heights and stopped short (measured: 300 px of the 5,304 it needed), and
  GTK validates the text layout at a higher priority than an idle, so the
  second scroll sees real heights."
  [ed]
  (let [scroll! (fn [] (g/gtk-text-view-scroll-to-mark (:view ed) (g/gtk-text-buffer-get-insert (:buf ed)) 0.0 0 0.0 0.0))]
    (scroll!)
    (on-gui! scroll!)))

(defn- formatted!
  "A format op through the plain path, then the caret scrolled into view
  when the op changed anything. Plain, not the hook path: the op's text is
  already Paren-stable while Parinfer is on (the builder's pass), so the
  hook has nothing to add, and the too-long note would only mislead after
  a deliberate format. A nil op never reaches
  apply-op-plain!, which would clear a cache the no-op left valid."
  [ed op]
  (let [r (if (and op (apply-op-plain! ed op)) 1 0)]
    (when (= 1 r) (scroll-to-caret! ed))
    r))

(defn- format-opts
  "The format op builders' opts. The project's .jonquil.edn :format-style
  pins the choice, :paren-stable always running the pass and :plain never,
  so a repo formats one way for everyone; without a pin, the pass runs
  whenever the Parinfer hook would run on an ordinary keystroke. Passed as
  a function because the plugin checker embeds jonquil.commands' closure,
  which must not grow by the Parinfer engine."
  [ed]
  {:stabilize (when (case ((deref (:format-style-fn ed)))
                      :paren-stable true
                      :plain false
                      (some? (parinfer-mode ed)))
                pe/paren-stable)})

(defn do-format-all! [ed]
  (formatted! ed (commands/format-all-op (snapshot ed) (format-opts ed))))

(defn do-format-selection! [ed]
  (formatted! ed (commands/format-selection-op (snapshot ed) (format-opts ed))))

(defn do-indent! [ed]
  (apply-edit! ed (edit/indent (snapshot ed))))

(defn do-unindent! [ed]
  (apply-op! ed (edit/unindent (snapshot ed))))

(defn keycode-fallback?
  "True when os is \"Mac OS X\", keycode is the physical F or L key
  (MAC-KEYCODE-F/MAC-KEYCODE-L) and Alt/Option is held in state. Pure, so a
  test can pass any os. Off macOS it is always false: on Linux X11 keycode
  37 is Control_L, and the keycode numbering means nothing there."
  [os keycode state]
  (and (= "Mac OS X" os)
       (contains? #{MAC-KEYCODE-F MAC-KEYCODE-L MAC-KEYCODE-P} keycode)
       (alt-held? state)))

(defn physical-key
  "The physical key's name, \"f\", \"l\", \"p\" or a digit 1 to 6, for
  the :physical? bindings (the editor's Cmd/Ctrl+Alt+F and L format
  chords, the window's Cmd/Ctrl+Alt+P Parinfer chord and its Cmd/Ctrl+Alt+1
  to 6 panel keys), or nil. Pure, so a test can
  pass any os.

  On Linux, Alt doesn't remap the keyval, and the binding's plain f and l
  match. On macOS GDK reports the Option-translated character instead
  (measured live with pid-driven cgevent: Cmd+Option+F arrives as 0x8f6,
  GDK_KEY_function, and Cmd+Option+L as 0xac, GDK_KEY_notsign), and
  gdk_keyval_to_lower doesn't help. The hardware keycode stays
  MAC-KEYCODE-F/MAC-KEYCODE-L, so it names the key.

  The (< keyval 0x1000) guard keeps a modifier key's own press out:
  Control_L is 0xffe3, and on Linux keycode 37 is Control_L."
  [os keyval keycode state]
  (cond
    (and (< keyval 0x1000) (keycode-fallback? os keycode state))
    (condp = keycode MAC-KEYCODE-F "f" MAC-KEYCODE-L "l" "p")
    ;; a digit: Option+5 types U+221E, whose keyval (0x100221e) is above
    ;; the guard, so only the function and modifier keyvals are kept out
    (and (= "Mac OS X" os) (alt-held? state) (contains? MAC-KEYCODE-DIGITS keycode)
         (not (<= 0xfd00 keyval 0xffff)))
    (MAC-KEYCODE-DIGITS keycode)))

(defn- typing-key!
  "The typing stages of the plugins design, 1 when one took the key, else
  0: smart enter on plain Return first, then auto-pairs, Backspace, and
  Tab/Shift+Tab indent. They run only once nothing earlier in
  jonquil.command-registry/editor-answer (the preedit yield, the
  completion popup, the editor keymap) took the key."
  [ed keyval state]
  (cond
    (plain-return? keyval state)
    (if (handle-return! ed) 1 0)

    ;; With Parinfer on, brackets and pair Backspace are Parinfer's
    ;; (upstream's rule): 0 lets GTK insert the bare key and
    ;; the end-user-action hook balance it. The quote keeps pairing.
    (and (no-command-modifiers? state) (contains? open-chars keyval))
    (if (and (parinfer-asides? ed) (not= keyval KEY-QUOTEDBL))
      0
      (apply-edit! ed (edit/insert-open (snapshot ed) (get open-chars keyval))))

    (and (no-command-modifiers? state) (contains? close-chars keyval))
    (if (parinfer-asides? ed)
      0
      (apply-edit! ed (edit/insert-close (snapshot ed) (get close-chars keyval))))

    (and (no-command-modifiers? state) (= keyval KEY-BACKSPACE))
    (let [snap (snapshot ed)]
      (if (and (parinfer-asides? ed) (not (empty-string-pair? snap)))
        0
        (apply-edit! ed (edit/backspace-pair snap))))

    ;; Consumed (1) even when unindent returns nil (no
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

    :else 0))

(defn key-ctx
  "The editor keymap's ctx (jonquil.command-registry/editor-commands) for
  ed: thunks, so each reads its atom when its command runs. handle-key!
  builds it per key, and the command palette builds the same one to run an
  editor command as its key would."
  [ed]
  (let [cfns @(:completion-fns ed)]
    {:complete! #(when-let [f (:complete! cfns)] (f))
     :toggle-comment! #(do-toggle-comment! ed)
     :format-all! #(do-format-all! ed)
     :format-selection! #(do-format-selection! ed)
     :redo! #(if (not (zero? (g/gtk-text-buffer-get-can-redo (:buf ed))))
               (do (g/gtk-text-buffer-redo (:buf ed)) 1)
               0)
     :writable? #(not @(:read-only? ed))
     :quick-doc! #(when-let [f @(:quick-doc-fn ed)] (f))
     :jump! #(when-let [f @(:jump-fn ed)] (f))
     :rename! #(when-let [f @(:rename-fn ed)] (f))
     :preedit-active? #(deref (:preedit-active? ed))
     :run-form! #(when-let [f @(:run-form-fn ed)] (f))
     ;; no key reads these two (Tab is a typing stage); the context menu's
     ;; and the palette's Indent and Unindent do
     :indent! #(do-indent! ed)
     :unindent! #(do-unindent! ed)
     ;; a plugin's :edit command (jonquil.plugin-registry) reads the
     ;; buffer as a snapshot and has its op applied as one user action
     :snapshot #(snapshot ed)
     :apply-op! #(apply-op! ed %)}))

(defn handle-key!
  "The editor's key dispatch. Returns 1 when it handled the key,
  0 otherwise -- the key controller (on-key, below) only calls this, so
  self-test rows can call the exact same function with a synthetic
  keyval/keycode/state, never a synthesized OS keystroke. Yields (0) on
  every key while a preedit is active, not just Return, since GTK/the
  IM context need every key while composing. keyval0 is normalised with
  gdk_keyval_to_lower before every comparison (same reason window.clj's
  on-window-key does it: Caps Lock reports the uppercase keyval with no
  Shift bit set).

  Two arities: the 3-arg form (no keycode) defaults keycode to 0, which is
  harmless since 0 never matches
  MAC-KEYCODE-F/MAC-KEYCODE-L. on-key (below), the real GTK callback,
  always uses the 4-arg form so physical-key's macOS fallback has a real
  hardware keycode to check.

  Before the pipeline below runs, a pending Run Form flash is
  cancelled at once (jonquil.gtk.window.tabs's set-flash-cancel-fn!,
  restoring the caret and clearing the selection) unless keyval is a bare
  modifier press (modifier-keyval?) -- otherwise a printable key,
  Backspace or Tab would act on the flash's own selection instead of the buffer's real
  one.

  The rest is
  jonquil.command-registry/editor-answer's pipeline, preedit yield,
  completion popup, the :editor keymap in jonquil/keymap.edn (Run Form,
  completion, comment, the two formats, redo, quick doc, jump, rename) over
  jonquil.command-registry/editor-commands, then typing-key!'s stages.
  ectx carries the editor's state as thunks."
  ([ed keyval0 state] (handle-key! ed keyval0 0 state))
  ([ed keyval0 keycode state]
   (let [keyval (g/gdk-keyval-to-lower keyval0)
         cfns @(:completion-fns ed)
         ectx (key-ctx ed)
         ;; The live plugins' bindings ahead of the shipped ones,
         ;; over the built-in commands and theirs; with none, the shipped tables
         plugins @plugin-registry/live
         cmds (merge cmd-registry/editor-commands (plugin-registry/commands plugins))]
     (when-not (modifier-keyval? keyval)
       (when-let [f @(:flash-cancel-fn ed)] (f)))
     (cmd-registry/editor-answer
      (plugin-registry/editor-bindings plugins) keyval state
      {:holds? (cmd-registry/holds-fn cmd-registry/editor-conditions ectx)
       :run! #(cmd-registry/run-command! cmds % ectx)
       :physical (physical-key OS-NAME keyval keycode state)
       ;; Completion's own nav/commit/escape keys (Up/Down/
       ;; PageUp/PageDown/Enter/Tab/Escape) get first look, ahead of smart
       ;; enter and Tab, whenever the popup is open --
       ;; jonquil.gtk.completion/handle-key! gates on that itself and
       ;; returns 0 otherwise, so this stage is a safe no-op the rest of
       ;; the time. See set-completion-fns!'s docstring.
       :popup-key! #(if-let [f (:handle-key! cfns)] (f keyval state) 0)
       :typing-key! #(typing-key! ed keyval state)}))))

(defn dispatch-key!
  "Wraps handle-key! so a throw from any dispatch clause is reported
  through the error hook (set-error-fn!) and the key still counts as
  consumed (1), instead of escaping into GTK's own signal emission and
  killing the process. on-key (below) is the real \"key-pressed\" callback
  and always calls this; a self-test row can call it directly with a
  synthetic keyval/keycode/state, exactly as on-key does.

  Two arities, mirroring handle-key!'s own."
  ([ed keyval0 state] (dispatch-key! ed keyval0 0 state))
  ([ed keyval0 keycode state]
   (try
     (handle-key! ed keyval0 keycode state)
     (catch :default e
       (when-let [f @(:on-error-fn ed)] (f (str "; key: " (ex-message e))))
       1))))

(defn- on-key [ed]
  (fn [_ctl keyval keycode state _data]
    (dispatch-key! ed keyval keycode state)))

;; --- construction ----------------------------------------------------------

(defn- editor-state
  "The editor's state map, its tags made on buf."
  [{:keys [view buf scroll readonly-tag]}]
  {:view view
   :buf buf
   :scroll scroll
   :tags (make-tags! buf)
   :readonly-tag readonly-tag
   :saved-text (atom "")
   :last-dirty? (atom false)
   :on-dirty-fns (atom [])
   :on-cursor-fns (atom [])
   :on-focus-fns (atom [])
   :dirty-at (atom nil)
   :preedit-active? (atom false)
   ;; Starts true so the first tick always checks, establishing
   ;; a baseline; `changed` (below), set-text! and mark-saved! are
   ;; the only writers after that.
   :dirty-check-needed? (atom true)
   :dirty-check-count (atom 0)
   ;; Single-slot registrations for the quick-doc/jump chords: see
   ;; set-quick-doc-fn!/set-jump-fn!.
   :quick-doc-fn (atom nil)
   :jump-fn (atom nil)
   ;; Single-slot registration for the rename chords: see
   ;; set-rename-fn!.
   :rename-fn (atom nil)
   ;; Single-slot registration for Run Form: see set-run-form-fn!.
   :run-form-fn (atom nil)
   ;; Single-slot registration for Run Form's flash cancel: see
   ;; set-flash-cancel-fn!.
   :flash-cancel-fn (atom nil)
   ;; Single-slot registration for completion: see
   ;; set-completion-fns!. nil until jonquil.gtk.window.tabs wires it.
   :completion-fns (atom nil)
   ;; The dialect profile whose sets paint
   ;; symbols -- see set-profile!. nil until the window sets it.
   :profile (atom nil)
   ;; Single-slot registration for error reporting -- see
   ;; set-error-fn!. nil until jonquil.gtk.window.tabs wires it,
   ;; so a throw before that point is silently dropped rather
   ;; than reported (there is no status label to report it to
   ;; yet).
   :on-error-fn (atom nil)
   ;; Source panel: read-only mode (set-read-only!) and the
   ;; asynchronous paint's generation (paint-async!).
   :read-only? (atom false)
   ;; Parinfer: :parinfer-mode-fn answers the window's
   ;; mode (set-parinfer-fns!); :parinfer-prev is the text and
   ;; caret at begin-user-action; :parinfer-busy? guards the
   ;; hook's own nested apply; :parinfer-skip? exempts one edit
   ;; (apply-op-plain!, the MCP path).
   :parinfer-mode-fn (atom (constantly :off))
   ;; :format-style-fn answers the tab's .jonquil.edn
   ;; :format-style (set-format-style-fn!): :paren-stable or
   ;; :plain pins what a format produces, nil follows the mode.
   :format-style-fn (atom (constantly nil))
   :parinfer-status-fn (atom nil)
   :parinfer-prev (atom nil)
   :parinfer-busy? (atom false)
   :parinfer-skip? (atom false)
   ;; The speedup slots: :parinfer-prev-cache holds {:text s :gen n}, the buffer as
   ;; the hook last saw it with :edit-gen at that moment, so the
   ;; next action starts without a 262k copy when the gen hasn't
   ;; moved (any outside write bumps the gen through `changed`).
   ;; :parinfer-span accumulates the action's untouched head and
   ;; tail from the insert/delete signals, feeding adjust's
   ;; :bounds; nil when unarmed.
   ;; :parinfer-outside-span records what changed SINCE the last
   ;; cache prime, armed full in the same step as each prime and
   ;; narrowed by the same two signals, undo and redo replays
   ;; included, so the begin handler can rebuild prev from the
   ;; cache plus one ranged read; nil when
   ;; there is no cache to recover from.
   :parinfer-prev-cache (atom nil)
   :parinfer-span (atom nil)
   :parinfer-outside-span (atom nil)
   :paint (atom {:gen 0 :done? true})
   ;; The tick's incremental repaint (repaint!).
   ;; :painted is {:text :plan} of what our tags show now, or nil
   ;; when unknown. :edit-gen is bumped by every `changed`.
   ;; :edit-bounds is {:prefix m :suffix n}, no edit since :painted
   ;; touched the first m or last n chars, nil when :painted is.
   ;; :repaint-req counts the tick's futures, so only the latest
   ;; dropped result re-arms the debounce. Without it, every result
   ;; dropped because typing moved :edit-gen would re-arm, and each
   ;; re-arm starts another tick: another full text copy (~54 ms at
   ;; 20,000 lines) and another plan future, which is dropped in turn
   ;; while a newer future is already on its way.
   :painted (atom nil)
   :edit-gen (atom 0)
   :edit-bounds (atom nil)
   :repaint-req (atom 0)
   ;; Counts the tick's dropped results; only the self-test reads it.
   :repaint-drops (atom 0)
   ;; True once view's own "destroy" signal has fired (connected
   ;; once, below). A paint (paint-async!) or the 20ms tick
   ;; (tick!) can outlive the editor's own window -- F12 into a
   ;; large file, then close that window before colouring
   ;; finishes -- and both check this flag before touching the
   ;; buffer, so neither runs a GTK call against a destroyed
   ;; widget.
   :destroyed? (atom false)})

(defn- set-up-view!
  "Registers the read-only tag and sets up the view, its buffer and its scroller."
  [{:keys [view buf scroll readonly-tag]}]
  (g/set-bool-property! readonly-tag "editable" false)
  (g/gtk-text-tag-table-add (g/gtk-text-buffer-get-tag-table buf) readonly-tag)
  (g/gtk-text-view-set-editable view 1)
  (g/gtk-text-view-set-monospace view 1)
  (g/gtk-text-view-set-left-margin view 8)
  (g/gtk-text-view-set-top-margin view 4)
  (g/gtk-text-buffer-set-enable-undo buf 1)
  (g/gtk-scrolled-window-set-child scroll view))

(defn- connect-changed!
  "Connects the buffer's changed handler, which marks the edit for the tick."
  [{:keys [buf] :as ed}]
  (g/g-signal-connect-data buf "changed"
                           (callable (fn [_buf _data]
                                       (reset! (:dirty-at ed) (System/currentTimeMillis))
                                       (reset! (:dirty-check-needed? ed) true)
                                       (swap! (:edit-gen ed) inc)
                                       nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- narrow-spans!
  "Narrows the edit bounds and both Parinfer spans that are armed by an edit at o."
  [ed o tail]
  (when @(:edit-bounds ed)
    (swap! (:edit-bounds ed) narrow-bounds o tail))
  ;; the parinfer spans see the same edits
  (when @(:parinfer-span ed)
    (swap! (:parinfer-span ed) narrow-bounds o tail))
  (when @(:parinfer-outside-span ed)
    (swap! (:parinfer-outside-span ed) narrow-bounds o tail)))

(defn- connect-insert-text!
  "Connects the buffer's insert-text handler, which narrows the spans."
  [{:keys [buf] :as ed}]
  ;; insert-text and delete-range (connect-delete-range!) narrow
  ;; :edit-bounds. Connected without G_CONNECT_AFTER, both run before
  ;; GTK's default handler, so the iters and the char count are pre-edit.
  ;; Probe 2026-10-14: inserting "XYé" at 3 into "abcdef" read offset
  ;; 3, len 4 and char count 6; deleting [1, 4) from the 9-char result
  ;; read 1, 4 and 9; `changed` then read 9 and 6. len is in bytes, so it
  ;; goes unused. Callbacks: (buf, GtkTextIter*, char*, int, data) and
  ;; (buf, GtkTextIter* start, GtkTextIter* end, data).
  ;; Any other way of inserting into the buffer (insert-paintable,
  ;; insert-child-anchor) must also narrow :edit-bounds, or the tick
  ;; repaints the wrong range and colours go wrong.
  (g/g-signal-connect-data buf "insert-text"
                           (callable (fn [_buf iter _text _len _data]
                                       (let [o (g/gtk-text-iter-get-offset iter)
                                             n (g/gtk-text-buffer-get-char-count buf)]
                                         (narrow-spans! ed o (- n o)))
                                       nil)
                                     [:pointer :pointer :pointer :int :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- recovered-prev-text
  "The pre-action text rebuilt from the cache plus one ranged read, or
  the full read when the lengths disagree."
  [{:keys [buf cache os nc]}]
  (let [pt ^String (:text cache)
        m (g/gtk-text-buffer-get-char-count buf)
        p (min (:prefix os) nc m)
        q (min (:suffix os) (- nc p) (- m p))
        rec (str (subs pt 0 p)
                 (g/buffer-text-range buf p (- m q))
                 (subs pt (- nc q)))]
    (if (= (count rec) m) rec (g/buffer-text buf))))

(defn- snapshot-prev!
  "Records Parinfer's pre-action text and caret when Parinfer is on,
  and arms :parinfer-span at full length (no edit yet narrowed it)."
  [{:keys [buf] :as ed}]
  (when (parinfer-mode ed)
    (let [cache @(:parinfer-prev-cache ed)
          os @(:parinfer-outside-span ed)
          nc (when cache (count ^String (:text cache)))
          ;; a hit also needs the outside-span still at
          ;; full length, a free cross-check: narrowed
          ;; would mean a signal fired without the gen
          ;; moving
          hit? (and cache (= (:gen cache) @(:edit-gen ed))
                    (= os {:prefix nc :suffix nc}))
          text (cond
                 hit? (:text cache)
                 ;; outside recovery:
                 ;; cached head, the changed middle read
                 ;; from the buffer, cached tail; the end
                 ;; splice's argument with prev and the
                 ;; live buffer swapping roles, so the
                 ;; same induction carries it, and the
                 ;; same length guard turns any surprise
                 ;; into the full read. Only on a NARROWED
                 ;; span: a moved gen with a still-full
                 ;; span means a change the signals never
                 ;; saw (a child anchor), so the full read
                 (and cache os
                      (not= os {:prefix nc :suffix nc}))
                 (recovered-prev-text {:buf buf :cache cache :os os :nc nc})
                 :else (g/buffer-text buf))
          n (count text)]
      (reset! (:parinfer-span ed) {:prefix n :suffix n})
      (reset! (:parinfer-prev ed)
              {:text text
               :caret (g/cursor-offset buf)
               :cached? hit?
               :edit-at (when hit? (:edit-at cache))}))))

(defn- connect-user-actions!
  "Connects Parinfer's snapshot at begin-user-action and its hook at end-user-action."
  [{:keys [buf] :as ed}]
  ;; Parinfer: the snapshot at begin-user-action and the hook at
  ;; end-user-action. Every typed key, paste, cut and apply-op! is one
  ;; user action; GTK's own undo and redo emit neither signal (probed), and set-text! is an irreversible action, so loads,
  ;; reloads, undo and redo never reach the hook.
  (g/g-signal-connect-data buf "begin-user-action"
                           (callable (fn [_buf _data] (snapshot-prev! ed) nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/g-signal-connect-data buf "end-user-action"
                           (callable (fn [_buf _data]
                                       (parinfer-after-action! ed)
                                       nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-delete-range!
  "Connects the buffer's delete-range handler, which narrows the spans."
  [{:keys [buf] :as ed}]
  (g/g-signal-connect-data buf "delete-range"
                           (callable (fn [_buf start end _data]
                                       (let [o (g/gtk-text-iter-get-offset start)
                                             e (g/gtk-text-iter-get-offset end)
                                             n (g/gtk-text-buffer-get-char-count buf)]
                                         (narrow-spans! ed o (- n e)))
                                       nil)
                                     [:pointer :pointer :pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-cursor-notify!
  "Connects the cursor-position notify, which runs the cursor fns."
  [{:keys [buf] :as ed}]
  ;; The buffer's "cursor-position" property notify, in place of
  ;; "mark-set" filtered to the insert mark. An insert or
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
                                         (try
                                           (f (g/cursor-line-col buf))
                                           (catch :default e
                                             (when-let [ef @(:on-error-fn ed)]
                                               (ef (str "; cursor: " (ex-message e)))))))
                                       nil)
                                     [:pointer :pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-view-signals!
  "Connects the view's preedit-changed and destroy handlers."
  [{:keys [view] :as ed}]
  ;; GtkTextView's OWN "preedit-changed" signal (distinct from
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
  ;; A paint (paint-async!) or the 20ms tick (tick!) can outlive the
  ;; editor's own window: a transient window (the source panel, F12's
  ;; jump target) can close while either is still running, and both read
  ;; buffer offsets computed against a buffer that no longer exists once
  ;; the view is gone. Connected ONCE, here, like every other signal
  ;; make-editor connects (mirrors jonquil.gtk.source-pane's own "destroy" backstop).
  (g/g-signal-connect-data view "destroy"
                           (callable (fn [_view _data] (reset! (:destroyed? ed) true) nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-focus-leave!
  "Connects the focus controller's leave handler and adds the controller to view."
  [ed view focusctl]
  ;; GtkEventControllerFocus's own "leave" signal: fires whenever view (or a descendant) stops being the
  ;; root's focus widget. No signal args beyond instance/user_data.
  (g/g-signal-connect-data focusctl "leave"
                           (callable (fn [_ctl _data]
                                       (doseq [f @(:on-focus-fns ed)]
                                         (try
                                           (f)
                                           (catch :default e
                                             (when-let [ef @(:on-error-fn ed)]
                                               (ef (str "; focus: " (ex-message e)))))))
                                       nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-widget-add-controller view focusctl))

(defn- add-key-controller!
  "Connects the key handler in the capture phase and adds the controller to view."
  [ed view keyctl]
  (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
  (g/g-signal-connect-data keyctl "key-pressed"
                           (callable (on-key ed) [:pointer :uint :uint :uint :pointer] :int)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-widget-add-controller view keyctl))

(defn- start-tick!
  "Starts the editor's 20ms tick."
  [ed]
  ;; on-throw 1 (G_SOURCE_CONTINUE): a throw in one tick must not stop
  ;; dirty tracking for the rest of the session.
  (g/g-timeout-add 20 (callable (fn [_data] (tick! ed)) [:pointer] :int :on-throw 1) ffi/null))

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
        ;; Source panel: the read-only region tag (editable false), same
        ;; mechanism jonquil.gtk.console-view's own readonly-tag uses --
        ;; see set-read-only!'s docstring for why the view's own editable
        ;; property alone isn't enough.
        readonly-tag (g/gtk-text-tag-new "editor-read-only")
        ed (editor-state {:view view :buf buf :scroll scroll :readonly-tag readonly-tag})]
    (set-up-view! ed)
    (connect-changed! ed)
    (connect-insert-text! ed)
    (connect-user-actions! ed)
    (connect-delete-range! ed)
    (connect-cursor-notify! ed)
    (connect-view-signals! ed)
    (connect-focus-leave! ed view focusctl)
    (add-key-controller! ed view keyctl)
    (start-tick! ed)
    ed))
