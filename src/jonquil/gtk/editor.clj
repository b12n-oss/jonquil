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
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
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

(defn set-text!
  "Replaces the whole buffer inside begin/end_irreversible_action, so it
  leaves no undo step, and records s as the saved text in the same call --
  before the next tick can run -- so set-text! never itself marks dirty or
  fires on-dirty-changed!. Also marks a dirty recompute needed (M7),
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
    (when @(:read-only? ed)
      (g/with-iters* buf 0 (count s)
        (fn [a b] (g/gtk-text-buffer-apply-tag buf (:readonly-tag ed) a b)))))
  (reset! (:dirty-check-needed? ed) true)
  ;; P3.T5: belt-and-braces alongside the buffer's own cursor notify (below),
  ;; same reasoning as the dirty-check-needed? line above it.
  (doseq [f @(:on-cursor-fns ed)] (f (g/cursor-line-col (:buf ed))))
  nil)

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

(defn set-run-form-fn!
  "Registers f (fn [] -> nil), the ui's own :run-form!* action, to run for
  the editor's Run Form chord (Cmd/Ctrl+Enter, cljd plan P3.T2). See
  set-quick-doc-fn!."
  [ed f]
  (reset! (:run-form-fn ed) f)
  nil)

(defn set-flash-cancel-fn!
  "Registers f (fn [] -> nil), Run Form's own flash cancel (I2 fix wave:
  window.clj's make-flash-fn, called from handle-key! below before any
  other key handling). See set-quick-doc-fn!."
  [ed f]
  (reset! (:flash-cancel-fn ed) f)
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

  Deviation from the brief's step 3(g): the view's own editable property
  (set above) only gates the view's OWN key/IM/paste handling -- it has no
  effect on a direct buffer call like
  gtk_text_buffer_insert_interactive_at_cursor, which checks per-range
  `editable` tags against its own default_editable argument, independent of
  any view. Measured: the brief's verbatim set-read-only! (view-editable
  toggle only) left row-read-only-refuses-edits FAILing -- its buffer-level
  insert went through with the editor read-only. jonquil.gtk.console-view
  already solves exactly this for its own read-only regions with a
  `readonly-tag` (editable false); this editor applies that same tag over
  the whole buffer here, and set-text! (above) reapplies it after a full
  text replacement.

  Fix round 1: also supersedes any paint-async! still in flight (its
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
  nil. A read-only editor (set-read-only!) applies nothing and returns
  nil."
  [ed result]
  (when (and result (not @(:read-only? ed)))
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
    ;; P3.T4 fix round 1: remove only OUR OWN tags, one at a time, never
    ;; remove_all_tags -- a tag another namespace applies over the same
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

  Large-file speed T4: the tags are unknown while the paint runs, so it
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
  "The tick's repaint (large-file speed T4). With a baseline (:painted), it
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

;; --- keys: the rest of the dispatch (P3.T2) ---------------------------------

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

;; P4.T4 additions (gdkkeysyms.h): Ctrl+Q quick doc, F12/Ctrl+B jump to
;; definition, Shift+F1 quick doc's second chord.
(def ^:private KEY-Q 0x071)
(def ^:private KEY-B 0x062)
(def ^:private KEY-F1 0xffbe)
(def ^:private KEY-F12 0xffc9)

;; P4.T5 addition (gdkkeysyms.h:380, re-verified against the installed
;; header): Ctrl+Space opens/commits completion.
(def ^:private KEY-SPACE 0x020)

;; P4.T7 additions (gdkkeysyms.h, re-verified against the installed
;; header): Shift+F6/F2 open the rename popover.
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

;; os.name read once. The keycode fallback is macOS-only: on Linux X11
;; keycode 37 is Control_L.
(def ^:private OS-NAME (System/getProperty "os.name"))

(def ^:private open-chars
  {KEY-PARENLEFT \( KEY-BRACKETLEFT \[ KEY-BRACELEFT \{ KEY-QUOTEDBL \"})

(def ^:private close-chars
  {KEY-PARENRIGHT \) KEY-BRACKETRIGHT \] KEY-BRACERIGHT \}})

(defn- command-chord?
  "A command chord is Control or Meta, as in Phase 2
  (jonquil.gtk.window/command-chord?, duplicated here since it's private
  there too)."
  [state]
  (not (zero? (bit-and state (bit-or g/CONTROL-MASK g/META-MASK)))))

(defn- shift-held? [state]
  (not (zero? (bit-and state g/SHIFT-MASK))))

(defn- alt-held? [state]
  (not (zero? (bit-and state g/ALT-MASK))))

(defn- modifier-keyval?
  "True for a modifier key's own keyval -- Shift/Control/Meta/Alt/Super/
  Hyper, Caps Lock or Shift Lock, either side (gdkkeysyms.h 0xffe1-0xffee,
  a contiguous block). I2: the flash-cancel guard in handle-key! below
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

(defn- control-only?
  "P4 keys (quick doc, jump): Control held and Meta NOT held -- the P4
  rule (00-overview.md's global constraints) that these chords check
  CONTROL-MASK only and reject META-MASK, unlike command-chord? above
  which treats either as the same chord."
  [state]
  (and (not (zero? (bit-and state g/CONTROL-MASK)))
       (zero? (bit-and state g/META-MASK))))

(defn- shift-only?
  "P4 keys whose own chord is Shift-based (quick doc's Shift+F1, rename's
  Shift+F6): Shift held and NONE of Meta, Control or Alt, the same P4 rule
  control-only? applies to the Control-based chords above --
  Cmd/Ctrl/Alt+Shift+<key> must not also match (the parked P4 item:
  Ctrl+Shift+F1/F6 used to trigger quick doc and rename)."
  [state]
  (and (not (zero? (bit-and state g/SHIFT-MASK)))
       (zero? (bit-and state (bit-or g/META-MASK g/CONTROL-MASK g/ALT-MASK)))))

(defn- no-modifiers?
  "True when none of Shift, Control, Meta or Alt is held -- F12's own P4
  requirement (\"no modifiers\"), stricter than no-command-modifiers?
  above, which doesn't check Shift."
  [state]
  (zero? (bit-and state (bit-or g/SHIFT-MASK g/CONTROL-MASK g/META-MASK g/ALT-MASK))))

(defn- apply-edit! [ed result]
  (if (apply-op! ed result) 1 0))

;; P4.T7: named so the context menu's own items (window.clj) call the
;; EXACT same function as their key -- these five used to be inline
;; lambdas inside handle-key!'s cond below, which a menu item could only
;; have duplicated, never reused. Each does exactly what its own inline
;; body used to.

(defn do-toggle-comment! [ed]
  (apply-edit! ed (edit/toggle-comment (snapshot ed))))

(defn- scroll-to-caret!
  "Brings the insert mark into view. Formatting replaces the whole buffer,
  so the caret can land far from where the view was (ROADMAP P3).
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
  "apply-edit! for a format op, then the caret scrolled into view when the
  op changed anything."
  [ed op]
  (let [r (apply-edit! ed op)]
    (when (= 1 r) (scroll-to-caret! ed))
    r))

(defn do-format-all! [ed]
  (formatted! ed (commands/format-all-op (snapshot ed))))

(defn do-format-selection! [ed]
  (formatted! ed (commands/format-selection-op (snapshot ed))))

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
       (contains? #{MAC-KEYCODE-F MAC-KEYCODE-L} keycode)
       (alt-held? state)))

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
  [keyval keycode state]
  (or (contains? #{KEY-F KEY-L} keyval)
      (and (< keyval 0x1000) (keycode-fallback? OS-NAME keycode state))))


(defn handle-key!
  "The editor's key dispatch (P3.T2), first match wins. Returns
  1 when it handled the key, 0 otherwise -- the key controller (on-key,
  below) only calls this, so self-test rows can call the exact same
  function with a synthetic keyval/keycode/state, never a synthesized OS
  keystroke. Yields (0) on every key while a preedit is active (I5), not
  just Return, since GTK/the IM context need every key while composing.
  keyval0 is normalised with gdk_keyval_to_lower before every comparison
  (M4: same reason window.clj's on-window-key does it -- Caps
  Lock reports the uppercase keyval with no Shift bit set).

  Two arities: the 3-arg form (no keycode) is every pre-existing caller,
  defaulting keycode to 0 -- harmless, since 0 never matches
  MAC-KEYCODE-F/MAC-KEYCODE-L. on-key (below), the real GTK callback,
  always uses the 4-arg form so alt-chord-f-or-l?'s macOS fallback has a
  real hardware keycode to check.

  I2: before any of the cond clauses below run, a pending Run Form flash
  is cancelled at once (window.clj's set-flash-cancel-fn!, restoring the
  caret and clearing the selection) unless keyval is a bare modifier
  press (modifier-keyval?) -- otherwise a printable key, Backspace or Tab
  would act on the flash's own selection instead of the buffer's real
  one."
  ([ed keyval0 state] (handle-key! ed keyval0 0 state))
  ([ed keyval0 keycode state]
   (let [keyval (g/gdk-keyval-to-lower keyval0)
         cfns @(:completion-fns ed)]
     (when-not (modifier-keyval? keyval)
       (when-let [f @(:flash-cancel-fn ed)] (f)))
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

       ;; cljd plan P3.T2: Cmd/Ctrl+Enter (Return or KP-Enter with a command
       ;; chord) is Run Form. It sits ahead of plain-return?, so smart-Enter
       ;; never sees it, and it's always consumed (1), so GtkTextView never
       ;; inserts a newline for it either. Completion's own clause above
       ;; ignores any key held with Control or Meta, so an open popup
       ;; doesn't take it.
       (and (command-chord? state) (contains? #{KEY-RETURN KEY-KP-ENTER} keyval))
       (do (when-let [f @(:run-form-fn ed)] (f)) 1)

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

       (and (command-chord? state) (alt-held? state) (alt-chord-f-or-l? keyval keycode state))
       (do-format-selection! ed)

       (and (command-chord? state) (= keyval KEY-Y) (not @(:read-only? ed)))
       (if (not (zero? (g/gtk-text-buffer-get-can-redo (:buf ed))))
         (do (g/gtk-text-buffer-redo (:buf ed)) 1)
         0)

       ;; P4.T4: quick doc and jump. control-only? (not command-chord?)
       ;; because the P4 global constraint is CONTROL-MASK only, rejecting
       ;; META-MASK -- unlike every chord above, which treats Control and
       ;; Meta as the same command chord. Shift+F1 checks shift-only? for
       ;; the same reason, applied to its own Shift-based chord, so
       ;; Cmd+Shift+F1 doesn't also match it. F12 additionally requires
       ;; NO modifiers at all
       ;; (no-modifiers?, stricter than no-command-modifiers? above, which
       ;; doesn't check Shift). Each runs the fn jonquil.gtk.window
       ;; registered (set-quick-doc-fn!/set-jump-fn!, below) and returns 1
       ;; regardless of what that fn finds -- "acted" means the chord
       ;; matched, not that a symbol/definition turned up.
       (and (control-only? state) (= keyval KEY-Q))
       (do (when-let [f @(:quick-doc-fn ed)] (f)) 1)

       (and (shift-only? state) (= keyval KEY-F1))
       (do (when-let [f @(:quick-doc-fn ed)] (f)) 1)

       (and (no-modifiers? state) (= keyval KEY-F12))
       (do (when-let [f @(:jump-fn ed)] (f)) 1)

       (and (control-only? state) (= keyval KEY-B))
       (do (when-let [f @(:jump-fn ed)] (f)) 1)

       ;; P4.T7: rename. Shift+F6/F2. Shift+F6 checks shift-only? (fix
       ;; round 1: it used to check shift-held? alone, so Cmd+Shift+F6
       ;; also matched, the same gap Shift+F1 had above) rather than
       ;; control-only?, since this is a Shift-based chord, not a
       ;; Control-based one. window.clj's own F6 clause already lets
       ;; Shift+F6 fall through to here (P4.T3's own key-order change);
       ;; F2 has no other clause to collide with, and no-modifiers?
       ;; already rejects Meta on its own.
       (and (shift-only? state) (= keyval KEY-F6))
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
        ed {:view view
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
            ;; cljd plan P3.T2: single-slot registration for Run Form --
            ;; see set-run-form-fn!.
            :run-form-fn (atom nil)
            ;; I2 fix wave: single-slot registration for Run Form's flash
            ;; cancel -- see set-flash-cancel-fn!.
            :flash-cancel-fn (atom nil)
            ;; P4.T5: single-slot registration for completion -- see
            ;; set-completion-fns!. nil until jonquil.gtk.window wires it.
            :completion-fns (atom nil)
            ;; P1.T2 (cljd plan): the dialect profile whose sets paint
            ;; symbols -- see set-profile!. nil until the window sets it.
            :profile (atom nil)
            ;; Single-slot registration for error reporting -- see
            ;; set-error-fn!. nil until jonquil.gtk.window wires it, so a
            ;; throw before that point is silently dropped rather than
            ;; reported (there is no status label to report it to yet).
            :on-error-fn (atom nil)
            ;; Source panel: read-only mode (set-read-only!) and the
            ;; asynchronous paint's generation (paint-async!).
            :read-only? (atom false)
            :paint (atom {:gen 0 :done? true})
            ;; Large-file speed T4: the tick's incremental repaint (repaint!).
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
            :destroyed? (atom false)}]
    (g/set-bool-property! readonly-tag "editable" false)
    (g/gtk-text-tag-table-add (g/gtk-text-buffer-get-tag-table buf) readonly-tag)
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
                                         (swap! (:edit-gen ed) inc)
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; Large-file speed T4: insert-text and delete-range narrow
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
                                         (when @(:edit-bounds ed)
                                           (let [o (g/gtk-text-iter-get-offset iter)
                                                 n (g/gtk-text-buffer-get-char-count buf)]
                                             (swap! (:edit-bounds ed) narrow-bounds o (- n o))))
                                         nil)
                                       [:pointer :pointer :pointer :int :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data buf "delete-range"
                             (callable (fn [_buf start end _data]
                                         (when @(:edit-bounds ed)
                                           (let [o (g/gtk-text-iter-get-offset start)
                                                 e (g/gtk-text-iter-get-offset end)
                                                 n (g/gtk-text-buffer-get-char-count buf)]
                                             (swap! (:edit-bounds ed) narrow-bounds o (- n e))))
                                         nil)
                                       [:pointer :pointer :pointer :pointer] :void)
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
                                           (try
                                             (f (g/cursor-line-col buf))
                                             (catch :default e
                                               (when-let [ef @(:on-error-fn ed)]
                                                 (ef (str "; cursor: " (ex-message e)))))))
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
    ;; A paint (paint-async!) or the 20ms tick (tick!) can outlive the
    ;; editor's own window: a transient window (the source panel, F12's
    ;; jump target) can close while either is still running, and both read
    ;; buffer offsets computed against a buffer that no longer exists once
    ;; the view is gone. Connected ONCE, here, like every other signal in
    ;; this fn (mirrors jonquil.gtk.source-pane's own "destroy" backstop).
    (g/g-signal-connect-data view "destroy"
                             (callable (fn [_view _data] (reset! (:destroyed? ed) true) nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; P4.T4: GtkEventControllerFocus's own "leave" signal (ffi.clj's
    ;; citation) -- fires whenever view (or a descendant) stops being the
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
    (g/gtk-widget-add-controller view focusctl)
    (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
    (g/g-signal-connect-data keyctl "key-pressed"
                             (callable (on-key ed) [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller view keyctl)
    ;; on-throw 1 (G_SOURCE_CONTINUE): a throw in one tick must not stop
    ;; dirty tracking for the rest of the session.
    (g/g-timeout-add 20 (callable (fn [_data] (tick! ed)) [:pointer] :int :on-throw 1) ffi/null)
    ed))
