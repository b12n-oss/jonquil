(ns jonquil.gtk.console-view
  "The unified DrRacket-style Interactions console: one GtkTextView whose
  buffer holds read-only output history, a read-only prompt, and an
  editable input region, laid out

    [ output history ......... ][ \"> \" ][ input ... ]
    ^0                          ^out-end ^input-start       ^end

  out-end is a mark with right gravity: append! inserts at it, so new
  output always lands before the prompt and the mark tracks past it.
  input-start is a mark with left gravity: text typed at it stays after it,
  and output inserted earlier in the buffer shifts it forward automatically.
  Both behaviours are plain GtkTextMark gravity -- this namespace never
  re-derives them by hand, only by choosing the right gravity at creation
  and, where it recreates the prompt itself, by moving marks explicitly
  instead of relying on gravity for that one internal step.

  Output history and the prompt carry a read-only tag (`editable` false);
  the read-only tag is what stops typing, pasting or Backspace from
  crossing into history -- GtkTextView's own interactive-edit path already
  refuses edits wherever that tag applies, so no key handler here needs to
  special-case Backspace or paste.

  append! is the one function here safe to call from any thread (an eval
  worker's output callback, in particular): it only enqueues and schedules
  a drain via jonquil.gtk.callbacks/on-gui!, coalescing consecutive
  same-kind chunks into one buffer mutation so a 100k-line flood does not
  starve GTK. Every other function here (handle-return!, handle-history!,
  clear-input!, the key controller) touches the buffer directly and must
  only ever run on the GTK thread -- reset-busy-hint! is the exception,
  since it is a plain atom reset with no GTK call in it.

  :waiting?/:evaluating? are functions supplied at construction, read live
  from the eval engine rather than mirrored into console-owned atoms:
  a mirror set by the caller AFTER starting an eval can lose a race against
  a fast eval's own completion clearing it first, stranding the console in
  :busy forever.

  The console hosts a second jonquil.gtk.completion instance, so
  handle-key! asks it first (set-completion-fns!, mirroring jonquil.gtk.
  editor's own registration) before Return-evaluates or Up/Down-browses-
  history ever run. input-snapshot is that instance's :snapshot-fn and the
  Ctrl+Space clause's own gate: a snapshot scoped to the input region
  (offsets relative to input-start, matching jonquil.gtk.editor/snapshot's
  shape), or nil once the caret sits before input-start -- help/prefix-at
  on a nil snapshot is itself nil, so refine!'s caller closes the popup
  exactly like any other out-of-word caret move, with no separate check.
  apply-input-op! is its :apply-op!: the same edits-back-to-front-then-
  place-cursor shape as jonquil.gtk.editor/apply-op!, offset by input-
  start and wrapped in one gtk_text_buffer_begin/end_user_action pair (one
  undo step), so a commit can never touch the read-only output region.
  on-input-changed!/on-focus-lost! are new registration lists, mirroring
  editor.clj's on-cursor-moved!/on-focus-lost! -- the console's own
  refine!/close! triggers."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.console :as console]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.ffi :as g]
            [jonquil.theme :as theme]))

(def MAX-OUTPUT-CHARS
  "A chosen bound on the output region, not a measurement (estimate: at 5 to
  10 chars per line, roughly 20k to 40k lines of scrollback)."
  200000)

(def default-prompt
  "The console's prompt before any evaluator sets it otherwise: `user=> `,
  clj style. set-prompt! (below) replaces it with `(str ns-name \"=> \")`
  once an evaluator reports its session's namespace."
  "user=> ")

;; --- low-level buffer/mark helpers ---------------------------------------

(defn- mark-offset
  "mark's current character offset in buf."
  [buf mark]
  (let [it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-buffer-get-iter-at-mark buf it mark)
      (g/gtk-text-iter-get-offset it)
      (finally (ffi/free it)))))

(defn- text-range
  "buf's text between character offsets a and b (ffi.clj's buffer-text,
  generalized from the whole buffer to an arbitrary range)."
  [buf a b]
  (g/with-iters* buf a b
    (fn [i1 i2]
      (let [p (g/gtk-text-buffer-get-text* buf i1 i2 0)]
        (try (ffi/ptr->string p) (finally (g/g-free p)))))))

;; --- public reads ----------------------------------------------------------

(defn input-text
  "The input region's text: from input-start to the end of the buffer."
  [cv]
  (let [buf (:buf cv)]
    (text-range buf (mark-offset buf (:input-start cv))
                (g/gtk-text-buffer-get-char-count buf))))

(defn input-snapshot
  "cv's completion-relevant snapshot, scoped to the input region the same
  way jonquil.gtk.editor/snapshot scopes to the whole Definitions buffer:
  :text is input-text, :caret/:sel-start/:sel-end are offsets relative to
  input-start (0 there). nil when the caret sits before input-start (the
  read-only output region) -- jonquil.gtk.completion's :snapshot-fn and
  jonquil.gtk.window's own Ctrl+Space clause both read nil as \"not in the
  input\"."
  [cv]
  (let [buf (:buf cv)
        start (mark-offset buf (:input-start cv))
        caret (g/cursor-offset buf)]
    (when (>= caret start)
      (let [[a b] (g/selection-offsets buf)]
        {:text (input-text cv) :caret (- caret start)
         :sel-start (- a start) :sel-end (- b start)}))))

(defn clear-input!
  "Deletes the input region back to empty, leaving the prompt untouched.
  GTK-thread only."
  [cv]
  (let [buf (:buf cv)
        start (mark-offset buf (:input-start cv))
        end (g/gtk-text-buffer-get-char-count buf)]
    (when (< start end)
      (g/with-iters* buf start end (fn [a b] (g/gtk-text-buffer-delete buf a b))))))

(defn set-input!
  "Replaces the input with text, the caret at caret (an offset from the
  input's start), and focuses the console: Edit and retry's way of handing
  code back. GTK-thread only."
  [cv text caret]
  (let [buf (:buf cv)
        start (mark-offset buf (:input-start cv))]
    (clear-input! cv)
    (g/with-iters* buf start start (fn [a _] (g/gtk-text-buffer-insert buf a text -1)))
    (g/gtk-widget-grab-focus (:view cv))
    (let [at (+ start (max 0 (min caret (count text))))]
      (g/with-iters* buf at at (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
    nil))

(defn apply-input-op!
  "Applies an edit-op result (jonquil.help/commit-op's own shape, as
  jonquil.gtk.editor/apply-op! applies it) to the input region: result's
  :edits/:caret are offsets relative to input-start (input-snapshot's own
  convention), translated back to real buffer offsets here and applied
  back to front inside one gtk_text_buffer_begin/end_user_action pair --
  one undo step, mirroring apply-op!'s own grouping, the only difference
  being this offset shift. input-start is read once, before any edit: it
  is a left-gravity mark (this namespace's own ns docstring) so inserting
  or deleting text at or after its own offset never moves it, and every
  edit here stays at or after that offset by construction (input-snapshot
  never hands out a negative one), so a commit can never reach into the
  output region. Completion is the only caller; its own result
  never carries a :sel, so this has no equivalent of apply-op!'s own
  selection branch for indent/unindent/toggle-comment."
  [cv result]
  (when result
    (let [buf (:buf cv)
          start (mark-offset buf (:input-start cv))
          caret (+ start (:caret result))]
      (g/gtk-text-buffer-begin-user-action buf)
      (try
        (doseq [[s e r] (rseq (vec (:edits result)))]
          (g/with-iters* buf (+ start s) (+ start e) (fn [a b] (g/gtk-text-buffer-delete buf a b)))
          (g/with-iters* buf (+ start s) (+ start s) (fn [a _] (g/gtk-text-buffer-insert buf a r -1))))
        (finally (g/gtk-text-buffer-end-user-action buf)))
      (g/with-iters* buf caret caret (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
    true))

;; --- live results (LM1): each ref'd result line's span ---------------------
;; Output only grows at out-end, after every recorded span, so a span moves
;; only when text before or over it is deleted: trim! (the front),
;; clear-last-output! (the tail) and clear-output! (all of it).

(defn- shift-spans!
  "Drops the spans that reach into [a b) and moves the ones after it back
  by its length."
  [cv a b]
  (swap! (:results cv)
         (fn [rs] (into [] (keep (fn [{:keys [start end] :as r}]
                                   (cond (<= end a) r
                                         (>= start b) (assoc r :start (- start (- b a)) :end (- end (- b a)))
                                         :else nil)))
                        rs))))

(defn result-at
  "The ref ({:id :kind}) of the result line holding offset, or nil."
  [cv offset]
  (some (fn [{:keys [start end ref]}] (when (and (<= start offset) (< offset end)) ref)) @(:results cv)))

(defn result-text-at
  "The text of the result line holding offset, or nil."
  [cv offset]
  (some (fn [{:keys [start end text]}] (when (and (<= start offset) (< offset end)) text)) @(:results cv)))

(defn clear-output!
  "Deletes the output history [0, out-end), keeping the prompt and any
  typed input untouched. GTK-thread only, like clear-input!. Allowed while
  a program runs (Cmd/Ctrl+L, upstream): new output keeps arriving above
  the prompt at out-end, which this never touches.

  Also deactivates the last-entry mark (mark-entry-start!/clear-last-
  output!, below): \"there's no last entry until the next one begins\"
  after a full Cmd+L, whatever a Shift+L right before it would have found.

  This never touches the QUEUE (:queue cv), only the live
  buffer and the flag, so a mark request still sitting there (mark-entry-
  start!, queued, not yet drained) is untouched too -- it still takes
  effect on its own next drain, at whatever out-end is BY THEN (i.e. after
  this clear), correctly re-arming :last-entry-active? for whichever entry
  that pending request actually belongs to. Nothing here needs to special-
  case that: reset! is simply the right default until a real mark lands."
  [cv]
  (let [buf (:buf cv)
        end (mark-offset buf (:out-end cv))]
    (when (pos? end)
      (g/with-iters* buf 0 end (fn [a b] (g/gtk-text-buffer-delete buf a b)))))
  (reset! (:results cv) [])
  (reset! (:last-entry-active? cv) false)
  nil)

(defn- set-entry-start-mark!
  "The actual GTK move: last-entry-start to out-end's CURRENT offset, plus
  arming :last-entry-active?. GTK-thread only.

  Two callers, each reading out-end at the exact moment that's correct for
  them: commit-input! (below) calls this DIRECTLY, synchronously, since
  its own new content (turning prompt+input into history, opening a fresh
  prompt) is written straight to the buffer, never through append! -- by
  the time any queued drain could run, commit-input!'s own work has
  already moved out-end past where the mark needs to sit, so reading it
  early, before that work happens, is the only correct offset. drain!
  (below) calls this for a queued :mark item instead (mark-entry-start!'s
  own citation), for echo-entry!/jonquil.gtk.window.run's do-run!, whose own
  new content DOES go through append! -- there, reading out-end THEN,
  once the drain reaches this item, is what makes it land after
  everything queued before it and before that entry's own echo/banner."
  [cv]
  (let [buf (:buf cv)
        offset (mark-offset buf (:out-end cv))]
    ;; where it was, for restore-entry-start! (a ! command isn't an entry)
    (reset! (:prev-entry cv) [(mark-offset buf (:last-entry-start cv)) @(:last-entry-active? cv)])
    (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-move-mark buf (:last-entry-start cv) a)))
    (reset! (:last-entry-active? cv) true)))

(defn restore-entry-start!
  "Puts the last-entry mark back where the submit before this one found
  it: a `!` command line (jonquil.gtk.window) is not an entry, so Clear the
  last entry's output still reaches the code entry before it. GTK-thread
  only."
  [cv]
  (when-let [[offset active?] @(:prev-entry cv)]
    (let [buf (:buf cv)
          offset (min offset (g/gtk-text-buffer-get-char-count buf))]
      (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-move-mark buf (:last-entry-start cv) a)))
      (reset! (:last-entry-active? cv) active?)))
  nil)

(defn clear-last-output!
  "Deletes the last entry's own block -- its echo (or the Run banner),
  everything it has printed so far, and its `=> ...`/error line -- up to
  (but not including) the live prompt: Cmd/Ctrl+Shift+L, CIDER's C-c C-o.
  GTK-thread only, like clear-output!/clear-input!.

  console/clear-last-range makes the actual [start end) decision: nil
  (nothing to clear, no throw) when there's no active last entry -- none
  has begun yet, a previous Shift+L already cleared it, a full Cmd+L ran
  since (clear-output!'s own reset above), or the output-cap trimmer
  swallowed the mark's own text (trim!'s own citation below) -- or when
  the entry is already empty.

  Allowed while a program runs, same as clear-output!: the deletion only
  ever reaches out-end's CURRENT offset, so output the program prints
  AFTER this call lands past it, unaffected. The mark is deactivated once
  cleared (never re-armed until the next mark-entry-start! actually
  resolves -- set-entry-start-mark!'s own citation), so calling this
  again right after finds nothing left to clear."
  [cv]
  (let [buf (:buf cv)
        start (when @(:last-entry-active? cv) (mark-offset buf (:last-entry-start cv)))
        end (mark-offset buf (:out-end cv))]
    (when-let [[a b] (console/clear-last-range start end)]
      (g/with-iters* buf a b (fn [x y] (g/gtk-text-buffer-delete buf x y)))
      (shift-spans! cv a b)
      (reset! (:last-entry-active? cv) false)))
  nil)

(defn- move-mark-to!
  "Moves mark to offset, explicitly (gtk_text_buffer_move_mark)."
  [buf mark offset]
  (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-move-mark buf mark a))))

(defn- tag-as-prompt!
  "Makes the span start..end of cv's buffer read-only and tagged :prompt."
  [{:keys [buf tags readonly-tag]} start end]
  (g/with-iters* buf start end
    (fn [a b]
      (g/gtk-text-buffer-apply-tag buf readonly-tag a b)
      (g/gtk-text-buffer-apply-tag buf (get tags :prompt) a b))))

(defn- rewrite-prompt-span!
  "Replaces the prompt span start..old-end with new-text: strips any
  inherited tag, tags the new text as a prompt, and moves out-end and
  input-start to their exact post-edit offsets."
  [{:keys [buf readonly-tag tags out-end input-start]} start old-end new-text]
  (g/with-iters* buf start old-end (fn [a b] (g/gtk-text-buffer-delete buf a b)))
  (g/with-iters* buf start start (fn [a _] (g/gtk-text-buffer-insert buf a new-text -1)))
  (let [end (+ start (count new-text))]
    (g/with-iters* buf start end
      (fn [a b]
        (g/gtk-text-buffer-remove-all-tags buf a b)
        (g/gtk-text-buffer-apply-tag buf readonly-tag a b)
        (g/gtk-text-buffer-apply-tag buf (get tags :prompt) a b)))
    (move-mark-to! buf out-end start)
    (move-mark-to! buf input-start end)))

(defn- prompt-rewrite-plan
  "Reads, before the prompt span is rewritten, what set-prompt! needs to
  put the selection back: {:start :old-end :caret :bound :remap :in-input?}.
  :remap maps an old buffer offset to its place after the span becomes
  new-text, and :in-input? is true when the caret or the selection's other
  end sat in the input."
  [{:keys [buf out-end input-start]} new-text]
  (let [start (mark-offset buf out-end)
        old-end (mark-offset buf input-start)
        caret (g/cursor-offset buf)
        [sel-a sel-b] (g/selection-offsets buf)
        bound (if (= caret sel-a) sel-b sel-a)
        delta (- (count new-text) (- old-end start))]
    {:start start :old-end old-end :caret caret :bound bound
     :remap (fn [o] (cond (>= o old-end) (+ o delta)
                          (>= o start) start
                          :else o))
     :in-input? (or (>= caret old-end) (>= bound old-end))}))

(defn set-prompt!
  "Rewrites the live prompt -- the region between out-end and input-start
  -- to (str ns-name \"=> \"), clj style. GTK-thread only. A no-op when
  that text already matches the current prompt (:prompt-text cv), so a
  caller that fires on every evaluation doesn't churn the buffer when the
  namespace hasn't changed.

  Keeps the typed input and the caret: this only ever touches the prompt
  span itself, never anything at or after the old input-start, so input
  text already on the buffer is never deleted or re-inserted. Every mark
  move is explicit (gtk_text_buffer_move_mark), mirroring commit-input!'s
  own discipline -- out-end and input-start are repositioned to their
  exact post-edit offsets rather than trusted to survive the delete+
  insert via gravity. The caret and the selection's other end are put
  back explicitly too, each shifted by the prompt's change in length, so
  a selection inside the input survives with its direction: the rewrite
  can land at any moment (an evaluation finishing, a connection
  dropping), not only on Return. That runs only when one of them sat in
  the input; one in the read-only history is left where GTK put it.

  The rewritten span is left read-only and tagged :prompt, same as
  commit-input!'s own fresh prompt -- remove-all-tags first strips any
  tag the new text might otherwise inherit from a continuous run on
  either side of the edit point (insert-chunk!'s own citation on why that
  can happen at a tag boundary)."
  [cv ns-name]
  (let [{:keys [buf prompt-text]} cv
        new-text (str ns-name "=> ")]
    (when (not= new-text @prompt-text)
      (let [{:keys [start old-end caret bound remap in-input?]} (prompt-rewrite-plan cv new-text)]
        (rewrite-prompt-span! cv start old-end new-text)
        (when in-input?
          (g/with-iters* buf (remap caret) (remap bound)
            (fn [a b] (g/gtk-text-buffer-select-range buf a b))))
        (reset! prompt-text new-text))))
  nil)

(defn focus!
  "Grabs focus for the console view and places the cursor at the end of the
  input, mirroring jonquil.gtk.editor/focus!. Used by F6 so typing
  continues at the end of whatever's already there, rather than wherever an
  earlier click left the caret."
  [cv]
  (g/gtk-widget-grab-focus (:view cv))
  (let [buf (:buf cv)
        end (g/gtk-text-buffer-get-char-count buf)]
    (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
  nil)

(defn apply-theme!
  "Retints every tag in (:tags cv) to theme t's colours: sets each
  tag's foreground to (:fg (console/style t k)). Only ever touches tag
  properties -- never buffer text, marks or undo history."
  [cv t]
  (doseq [[k tag] (:tags cv)]
    (g/set-string-property! tag "foreground" (:fg (console/style t k))))
  nil)

(defn reset-busy-hint!
  "Clears the once-per-run :busy hint flag, so the next run that stalls
  on a busy Return earns its own hint instead of staying silent because an
  earlier run already showed one. Safe from any thread: a plain atom reset."
  [cv]
  (reset! (:busy-hint-shown? cv) false))

;; Text typed for a program: note-run-start! remembers the input line when an
;; evaluation begins, note-run-end! flags whatever new text sits there when it
;; finishes, and handle-return! then asks for a second Return before it runs
;; that text as Clojure. Both are GTK-thread only.
(defn note-run-start!
  "Records the input text as it stands when an evaluation begins. Text
  already there belongs to the user's Clojure and is never flagged."
  [cv]
  (reset! (:run-start-input cv) (subs (input-text cv) 0)))

(defn note-run-end!
  "Flags the input text as a leftover when it is non-blank and differs from
  what note-run-start! recorded; clears the flag otherwise."
  [cv]
  (let [text (input-text cv)]
    (reset! (:leftover cv)
            (when (and (not (str/blank? text)) (not= text @(:run-start-input cv)))
              (subs text 0)))))

;; --- the console's own REPL completion registrations ----------------------

(defn set-completion-fns!
  "Registers fns ({:complete! (fn [] -> nil) :handle-key! (fn [keyval
  state] -> 0/1)}), the ui's own REPL completion actions -- mirroring
  jonquil.gtk.editor/set-completion-fns! exactly, single slot, same
  reasoning. handle-key! (below) asks :handle-key! FIRST, ahead of every
  other clause, for every key: jonquil.gtk.completion/handle-key! itself
  gates on whether its popup is actually open and returns 0 otherwise, so
  this is a safe, cheap no-op call before completion is wired or while the
  popup is closed."
  [cv fns]
  (reset! (:completion-fns cv) fns)
  nil)

(defn on-input-changed!
  "Registers f (fn [] -> nil) to be called, on the GTK thread, whenever the
  buffer's insert-mark position changes -- the same \"notify::cursor-
  position\" signal jonquil.gtk.editor/on-cursor-moved! registers against
  (it covers typing, deletion and every mark-set case in one signal),
  wired here for the console's own REPL
  completion popup to refine!/close! on, mirroring the editor's on-cursor-
  moved!. Multiple registrations fire in registration order."
  [cv f]
  (swap! (:on-input-fns cv) conj f)
  nil)

(defn on-focus-lost!
  "Registers f (fn [] -> nil) to be called, on the GTK thread, whenever the
  console's view (or a descendant) stops being the root's focus widget --
  GtkEventControllerFocus's own \"leave\" signal, mirroring jonquil.gtk.
  editor/on-focus-lost! exactly (same signal, same citation). Multiple
  registrations fire in registration order."
  [cv f]
  (swap! (:on-focus-fns cv) conj f)
  nil)

(defn set-error-fn!
  "Registers f (fn [msg] -> nil), the ui's own error reporter (set-status!
  on the window's status label), to run whenever a key dispatch or a
  listener call throws. A single slot, mirroring jonquil.gtk.editor/
  set-error-fn! exactly."
  [cv f]
  (reset! (:on-error-fn cv) f)
  nil)

;; --- draining output ---------------------------------------------------

(defn- coalesce
  "Consecutive same-kind [kind s] chunks merged into one, preserving order.
  A result chunk carrying a ref ([kind s ref], append-result!) stays its
  own chunk, so its span is exactly its line."
  [chunks]
  (mapv (fn [group] (let [[k _ ref] (first group)]
                      (cond-> [k (apply str (map second group))] ref (conj ref))))
        (partition-by (fn [[k _ ref]] (if ref [k ref] k)) chunks)))

(defn- clip-to-cap
  "text's last max characters, or text unchanged when it already fits.
  Applied per coalesced run before it's ever inserted: a print-forever
  program can queue a single batch far bigger than the cap before a drain
  gets a chance to run, and there's no point inserting (and read-only/
  style-tagging) the part trim! would delete a moment later anyway."
  [text max]
  (if (> (count text) max) (subs text (- (count text) max)) text))

(defn- insert-chunk!
  "Inserts text at out-end and tags it read-only plus its kind's style. Right
  gravity does the rest: the inserted text lands to out-end's left (before
  the prompt) and the mark ends up past it, ready for the next chunk.

  Once a REPL entry has been committed, out-end sits exactly on the
  boundary between the just-committed prompt+input span and the fresh
  \"> \" prompt right after it -- both tagged :prompt, with no untagged
  character between them. GtkTextBuffer treats inserting text at such a
  boundary as inserting into the INTERIOR of one continuous :prompt span
  (there is no gap for it to land in), so the new text automatically
  inherits :prompt, and gtk_text_buffer_apply_tag below only ADDS the
  kind's own tag on top rather than replacing it. Overlapping tags are
  resolved by table priority (later gtk_text_tag_table_add wins), and
  console/styles' declaration order (out, err, prompt, result, error,
  system) puts :out ahead of :prompt in the table, so leaked :prompt
  silently wins over :out's own tag -- while :result/:error/:system,
  added after :prompt, happen to win and look unaffected. Removing every
  tag from exactly the inserted range before retagging it strips any such
  inherited tag first, so the kind's own tag is the only one left."
  [cv kind text]
  (let [{:keys [buf tags readonly-tag out-end]} cv
        start (mark-offset buf out-end)
        end (+ start (count text))]
    (g/with-iters* buf start start (fn [a _] (g/gtk-text-buffer-insert buf a text -1)))
    (g/with-iters* buf start end
      (fn [a b]
        (g/gtk-text-buffer-remove-all-tags buf a b)
        (g/gtk-text-buffer-apply-tag buf readonly-tag a b)
        (when-let [tag (get tags kind)] (g/gtk-text-buffer-apply-tag buf tag a b))))
    [start end]))

(defn- trim!
  "When the output region (0..out-end) is over MAX-OUTPUT-CHARS, deletes
  characters from its front so it fits, reusing console/trim-length's
  rule directly instead of duplicating its cut-after-newline math (two
  copies of the same rule could silently drift apart).

  Measures the region's length from out-end's own offset
  (gtk_text_iter_get_offset, via mark-offset) instead of copying the whole
  region just to measure it, then reads only the small tail near the
  cut point: exactly MAX-OUTPUT-CHARS + 1 characters, from-to-n below. That
  exact size is what makes reuse work without passing trim-length the real
  total length n: trim-length's own internal `must` (its length minus
  max-chars) then always works out to exactly 1 in the no-newline case,
  which is exactly what a 1-character offset from `from` needs to equal
  the true cut point (checked against a hand-rolled copy of the math
  across 6 cases including a ~589k-char flood).

  Also deactivates the last-entry mark (mark-entry-start!/clear-last-
  output!'s own citations) when the deletion is about to reach past it:
  read BEFORE the delete, since gtk_text_buffer_delete repositions any
  mark caught inside [0, cut) to offset 0, at which point there is no way
  left to tell that apart from a legitimate entry that really does start
  at 0. A mark at or past cut survives the delete and stays valid (GTK
  slides it down by the deleted amount, same as out-end itself), so only
  the < case needs treating as gone.

  Called only from drain!, AFTER its own doseq (above) has
  already resolved every :mark item in THIS batch via set-entry-start-
  mark! -- so :last-entry-active?/:last-entry-start here always reflect
  whatever this batch's own queue actually settled on, never a stale
  value from a still-pending request. A pending mark in the queue still
  takes effect: nothing here (or in append!/mark-entry-start!) can skip
  or drop a queued :mark item, so it always resolves on its own next
  drain regardless of how many trims happen before or after it."
  [cv]
  (let [buf (:buf cv)
        n (mark-offset buf (:out-end cv))]
    (when (> n MAX-OUTPUT-CHARS)
      (let [from (dec (- n MAX-OUTPUT-CHARS))
            tail (text-range buf from n)
            cut (+ from (console/trim-length tail MAX-OUTPUT-CHARS))]
        (when (and @(:last-entry-active? cv) (< (mark-offset buf (:last-entry-start cv)) cut))
          (reset! (:last-entry-active? cv) false))
        (g/with-iters* buf 0 cut (fn [a b] (g/gtk-text-buffer-delete buf a b)))
        (shift-spans! cv 0 cut)))))

(defn- scroll-to-end!
  "Scrolls the view to the buffer's end via a dedicated mark, since
  gtk_text_view_scroll_to_mark takes a mark, not an iter."
  [cv]
  (let [{:keys [buf view end-mark]} cv
        end (g/gtk-text-buffer-get-char-count buf)]
    (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-move-mark buf end-mark a)))
    (g/gtk-text-view-scroll-to-mark view end-mark 0.0 1 0.0 1.0)))

(defn- drain-chunk!
  "Applies one coalesced queue entry in order: a :mark resolves
  set-entry-start-mark!, anything else is clipped to the cap and inserted,
  recording its span when it carries a result ref."
  [cv [kind text ref]]
  (if (= kind :mark)
    (set-entry-start-mark! cv)
    (let [text (clip-to-cap text MAX-OUTPUT-CHARS)
          [start end] (insert-chunk! cv kind text)]
      (when ref
        (swap! (:results cv) conj {:start start :end end :ref ref :text text})))))

(defn- drain!
  "Runs on the GTK thread via on-gui!. Flips the scheduled flag BEFORE
  taking the queue (as jonquil.gtk.callbacks/drain-pending does), so a chunk that lands in the gap between the flip and the take
  is never stranded: it either lands in the vector this reset-vals! takes,
  or its own append! wins the next compare-and-set! and schedules a second,
  harmless drain. One drain handles everything queued: it coalesces, clips
  each run to MAX-OUTPUT-CHARS before inserting it (a print-forever
  program can queue a batch far bigger than the cap between drains), then
  trims once and scrolls once.

  A coalesced :mark entry (mark-entry-start!'s
  own queued request) is never inserted as text -- it resolves set-entry-
  start-mark! right here, in order, exactly where the doseq reaches it, so
  it lands after every chunk queued before it (already inserted, moving
  out-end past them by this point) and before whatever's queued after it.
  coalesce only merges CONSECUTIVE same-kind entries, so :mark never
  merges with a :prompt/:system chunk on either side of it -- only with
  another :mark, which resolves to the same single move either way."
  [cv]
  (reset! (:drain-scheduled? cv) false)
  (let [chunks (first (reset-vals! (:queue cv) []))]
    (when (seq chunks)
      (doseq [chunk (coalesce chunks)]
        (drain-chunk! cv chunk))
      (trim! cv)
      (scroll-to-end! cv))))

(defn append!
  "Safe from ANY thread: enqueues [kind s] and schedules one drain on the
  GTK thread via on-gui!. Never blocks and never touches GTK itself."
  [cv kind s]
  (swap! (:queue cv) conj [kind s])
  (when (compare-and-set! (:drain-scheduled? cv) false true)
    (on-gui! (fn [] (drain! cv)))))

(defn append-result!
  "append! for a result line that keeps a handle to its value (LM1): ref
  is the evaluator's {:id :kind}, and the line's span is recorded for
  result-at."
  [cv kind s ref]
  (swap! (:queue cv) conj [kind s ref])
  (when (compare-and-set! (:drain-scheduled? cv) false true)
    (on-gui! (fn [] (drain! cv)))))

(defn mark-entry-start!
  "Queues a \"mark the last entry start here\" request through append!'s
  own FIFO queue (:mark, a kind drain! above special-cases instead of
  inserting as text), so it resolves (set-entry-start-mark!, above) only
  once the queue actually reaches it -- landing after everything queued
  before it (a PREVIOUS entry's own output, still undrained) and before
  whatever this call's caller queues right after it.

  Reading out-end SYNCHRONOUSLY, right here, would race a previous
  entry's own still-queued-but-undrained output:
  append! only enqueues, a drain doesn't actually move out-end until it
  runs (jonquil.gtk.callbacks/on-gui!'s own g_idle_add citation -- always
  a LATER main-loop iteration, never this same call stack), so a
  synchronous read here could land BEFORE that pending content, making a
  later Shift+L delete it along with the new entry. Enqueuing instead
  means this request's own resolution is strictly ordered after anything
  already in the queue, by construction -- no read races anything.

  The two callers: echo-entry! (below -- Run Selection and Run Form both
  echo through it, so one call covers both) and jonquil.gtk.window's
  do-run!, for the \"; --- Running Definitions ---\" banner, which doesn't
  go through echo-entry! at all. Both queue their OWN new content via
  append! too, right after this call, which is exactly what makes the
  queued read correct for them.

  commit-input! (below) does NOT call this: its own new content bypasses
  append! entirely (set-entry-start-mark!'s own citation, above) -- a
  queued read there would land too late, after commit-input!'s own
  synchronous work has already moved out-end past where the mark needs to
  sit. It calls set-entry-start-mark! directly instead."
  [cv]
  (append! cv :mark nil))

(defn echo-entry!
  "Adds \"<current prompt>text\" to the read-only output history above the
  prompt, in the :prompt style, exactly as if it had been typed and
  submitted -- but leaves the current input untouched. Goes through
  append! (the same queue+drain path an eval's own output uses), so Run
  Selection's echo and whatever the eval later prints land in the console
  in the order they were called, even though the eval's own output
  arrives asynchronously from the eval worker thread.

  mark? gates whether this ALSO marks the
  console's own \"last entry start\" (mark-entry-start!, above), since
  Run Selection and Run Form both echo through here -- one call covers
  both. Callers pass (not (ev/evaluating? @ev*)), mirroring jonquil.gtk.
  window's do-run! own idle/busy split: Cmd+E/Cmd+Enter while a program is
  STILL running must echo (existing behaviour, kept unconditionally) but
  must NOT steal the mark from that running entry -- a later Shift+L
  needs to keep clearing the WHOLE run, output printed after the mid-run
  echo included, not just up to where the echo landed."
  [cv text mark?]
  (when mark? (mark-entry-start! cv))
  (append! cv :prompt (str @(:prompt-text cv) text "\n")))

;; --- Return ----------------------------------------------------------------

(defn- commit-input!
  "The :eval/:send-stdin steps that turn the current prompt+input into
  read-only history and open a fresh prompt at the end. Every mark move
  here is explicit (gtk_text_buffer_move_mark), not gravity: gravity only
  needs to govern append! and ordinary typing, and relying on it for the
  prompt's own recreation would put the freshly-inserted prompt text on
  the wrong side of out-end (right gravity means text inserted AT out-
  end's position ends up before it, which is exactly backwards for a
  prompt that must sit after out-end). The fresh prompt's length is read
  from cv's own :prompt-text (set-prompt!'s value), never a fixed 2 --
  set-prompt! itself never runs mid-commit, so this always sees the
  prompt as it stood when the entry was submitted.

  Also marks the console's own \"last entry start\" (set-entry-start-
  mark!, called directly, not the queued mark-entry-start! -- see its own
  citation on why) at prompt-start -- the REPL-submit choke point for
  Cmd/Ctrl+Shift+L, called before anything else here moves out-end."
  [cv]
  (set-entry-start-mark! cv)
  (let [{:keys [buf out-end input-start prompt-text]} cv
        prompt @prompt-text
        prompt-len (count prompt)
        prompt-start (mark-offset buf out-end)
        old-end (g/gtk-text-buffer-get-char-count buf)]
    (g/with-iters* buf old-end old-end (fn [a _] (g/gtk-text-buffer-insert buf a "\n" -1)))
    (let [end (inc old-end)
          fresh-end (+ end prompt-len)]
      ;; 1: prompt + input + its closing newline -> read-only, :prompt style
      (tag-as-prompt! cv prompt-start end)
      ;; 3: a fresh prompt at the end, out-end moved to just before it
      (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-insert buf a prompt -1)))
      (move-mark-to! buf out-end end)
      (tag-as-prompt! cv end fresh-end)
      (move-mark-to! buf input-start fresh-end)
      (g/with-iters* buf fresh-end fresh-end
        (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))))

(defn- apply-input-action!
  "Carries out the console/input-action result for text, and returns the
  key result: 0 when GTK should insert a newline, 1 when handled."
  [cv action text]
  (case action
    :newline 0
    :noop 1
    :leftover (do (append! cv :system "This was typed while the program ran. Press Return again to evaluate it.\n")
                  1)
    :busy (do (when (compare-and-set! (:busy-hint-shown? cv) false true)
                (append! cv :system "A program is running. Press Stop (Esc) to cancel it.\n"))
              1)
    (:eval :send-stdin) (do (commit-input! cv)
                            ((:on-submit cv) {:action action :text text})
                            1)))

(defn handle-return!
  "console/input-action applied to cv's live state, with the real Shift bit
  from the key event. Both the key controller and self-test rows call this
  instead of synthesizing a keystroke. Returns 0 when GTK should insert a
  plain newline itself, 1 when this function already handled the key.

  :waiting?/:evaluating? are read by CALLING the fns supplied at
  construction, not derefing a console-owned mirror atom: the engine
  is the source of truth, so there's nothing here for a fast eval's
  on-complete to race against and strand.

  :busy hints once per run: the first busy Return after a run starts
  appends a :system reminder; later ones stay silent until
  reset-busy-hint! (called from on-complete) re-arms it for the next run.

  Returns 0 unconditionally while an IME preedit composition is active
  before even reading input-action: the capture-phase key controller
  runs before the IM context filters the key, so consuming Return here
  would split or lose a pending Hangul/CJK composition."
  [cv shift?]
  (if @(:preedit-active? cv)
    0
    (let [text (input-text cv)
          action (console/input-action {:input text
                                        :waiting? ((:waiting? cv))
                                        :evaluating? ((:evaluating? cv))
                                        :shift? shift?
                                        :leftover @(:leftover cv)})]
      (reset! (:leftover cv) nil)
      (apply-input-action! cv action text))))

;; --- Up/Down -----------------------------------------------------------

(defn- on-first-line-of-input?
  "True when there's no newline between input-start and caret -- Up only
  browses history from the input's first line; on any other line GTK
  moves the caret up within a multi-line entry instead."
  [cv caret]
  (let [buf (:buf cv)
        start (mark-offset buf (:input-start cv))]
    (not (str/includes? (text-range buf start caret) "\n"))))

(defn- on-last-line-of-input?
  "True when there's no newline between caret and the buffer's end -- Down's
  half of the same gate."
  [cv caret]
  (let [buf (:buf cv)
        end (g/gtk-text-buffer-get-char-count buf)]
    (not (str/includes? (text-range buf caret end) "\n"))))

(defn- replace-input!
  "Replaces the input, which starts at start, with text, the caret at its
  end."
  [cv start text]
  (let [buf (:buf cv)
        end (+ start (count text))]
    (clear-input! cv)
    (g/with-iters* buf start start (fn [a _] (g/gtk-text-buffer-insert buf a text -1)))
    (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))))

(defn- history-key-applies?
  "Whether Up/Down (dir :prev or :next) browses history: caret at or after
  start, not waiting on stdin, and on the input's first (Up) or last (Down)
  line. The caret test goes first, see handle-history!."
  [cv dir caret start]
  (and (>= caret start) (not ((:waiting? cv)))
       (case dir
         :prev (on-first-line-of-input? cv caret)
         :next (on-last-line-of-input? cv caret))))

(defn- history-replacement
  "history-prev's (of the current input) or history-next's result, or nil."
  [cv dir]
  (case dir
    :prev ((:history-prev cv) (input-text cv))
    :next ((:history-next cv))))

(defn handle-history!
  "Up/Down in the console input. dir is :prev or :next. Replaces the input
  with history-prev/history-next's result when ALL of: the caret is at or
  after input-start, the console isn't waiting on stdin, and the caret sits
  on the input's first line (Up) or last line (Down) -- otherwise a
  multi-line entry's own Up/Down caret movement would be hijacked into
  browsing history instead. A nil replacement (no history to move to,
  or not currently browsing) leaves the input unchanged. Returns true when
  it replaced the input (the key controller returns 1), false otherwise
  (the key controller returns 0, so GTK's normal Up/Down caret movement
  applies). Also false while an IME preedit composition is active,
  same reasoning as handle-return!.

  Checks caret >= start BEFORE computing on-edge-line?:
  on-first/last-line-of-input? each read a text-range
  from the caret out to input-start or the buffer's end. With the caret
  somewhere in the (potentially ~200k-char) output history and input-start
  far away, gtk_text_buffer_get_text silently reorders a start > end pair,
  so that read would span the WHOLE output region -- for a key the caret
  gate was always going to refuse anyway. Gating first means that read
  never happens for a click into the output area."
  [cv dir]
  (if @(:preedit-active? cv)
    false
    (let [buf (:buf cv)
          caret (g/cursor-offset buf)
          start (mark-offset buf (:input-start cv))]
      (if-let [replacement (when (history-key-applies? cv dir caret start)
                             (history-replacement cv dir))]
        (do (replace-input! cv start replacement) true)
        false))))

;; --- keys: Return/KP-Enter, a command-chord D, Up/Down, Ctrl+Space --------

(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-KP-ENTER 0xff8d)
(def ^:private KEY-UP 0xff52)
(def ^:private KEY-DOWN 0xff54)
(def ^:private KEY-D 0x64)

;; gdkkeysyms.h:380, the editor keymap's "space":
;; Ctrl+Space opens/commits the REPL completion popup.
(def ^:private KEY-SPACE 0x020)

(defn- command-chord?
  "A command chord is Control or Meta, so the same shortcut works on
  Linux and macOS."
  [state]
  (not (zero? (bit-and state (bit-or g/CONTROL-MASK g/META-MASK)))))

(defn- control-only?
  "The completion chord's rule (the same one the editor keymap spells
  :mods #{:ctrl} :not #{:meta} for quick doc/jump/completion): Control
  held and Meta NOT held,
  unlike command-chord? above which treats either as the same chord."
  [state]
  (and (not (zero? (bit-and state g/CONTROL-MASK)))
       (zero? (bit-and state g/META-MASK))))

(defn- completion-key-handled?
  "True when the completion popup's own key handler (cfns' :handle-key!)
  consumed the key; never while an IME preedit composition is active."
  [cv cfns keyval state]
  (boolean (and (not @(:preedit-active? cv))
                (when-let [f (:handle-key! cfns)] (= 1 (f keyval state))))))

(defn- eof-chord?
  "Ctrl/Cmd+D while the program waits on stdin."
  [cv keyval state]
  (and (= keyval KEY-D) (command-chord? state) ((:waiting? cv))))

(defn- complete-chord?
  "Ctrl+Space with the caret inside the input, outside a preedit."
  [cv keyval state]
  (and (not @(:preedit-active? cv)) (control-only? state) (= keyval KEY-SPACE)
       (some? (input-snapshot cv))))

(defn handle-key!
  "The console's key dispatch, first match wins -- mirrors jonquil.gtk.
  editor/handle-key!'s own shape (a public fn, so self-test rows call this
  directly with a synthetic keyval/state, never a synthesized OS
  keystroke); on-key (below) is the real GTK callback's thin wrapper.

  The REPL completion popup's own nav/commit/escape keys get first
  look, ahead of Return-evaluates and Up/Down-browses-history below,
  whenever the popup is open -- jonquil.gtk.completion/handle-key! gates on
  that itself and returns 0 otherwise, so this clause is a safe no-op the
  rest of the time, same reasoning as set-completion-fns!'s own docstring.
  Guarded against an IME preedit composition, same as the Ctrl+Space
  clause below -- Return/Up/Down already guard themselves inside handle-
  return!/handle-history!, so they are left exactly as they were.

  Ctrl+Space (control-only?, not command-chord?, rejecting Meta) opens or
  commits the popup, but only when the caret is at or after input-start
  (input-snapshot non-nil) -- outside the input region this whole clause
  is skipped and the key falls through to :else, unhandled, same as any
  other key GTK's own default handling would get (and the read-only output
  region refuses a literal space anyway)."
  [cv keyval state]
  (let [cfns @(:completion-fns cv)]
    (cond
      (completion-key-handled? cv cfns keyval state)
      1

      (contains? #{KEY-RETURN KEY-KP-ENTER} keyval)
      (handle-return! cv (not (zero? (bit-and state g/SHIFT-MASK))))

      (eof-chord? cv keyval state)
      (do ((:on-submit cv) {:action :eof}) 1)

      (= keyval KEY-UP) (if (handle-history! cv :prev) 1 0)
      (= keyval KEY-DOWN) (if (handle-history! cv :next) 1 0)

      (complete-chord? cv keyval state)
      (do (when-let [f (:complete! cfns)] (f)) 1)

      :else 0)))

(defn dispatch-key!
  "Wraps handle-key! so a throw from any dispatch clause is reported
  through the error hook (set-error-fn!) and the key still counts as
  consumed (1), instead of escaping into GTK's own signal emission and
  killing the process. on-key (below) is the real \"key-pressed\" callback
  and always calls this; a self-test row can call it directly with a
  synthetic keyval/state, exactly as on-key does."
  [cv keyval state]
  (try
    (handle-key! cv keyval state)
    (catch :default e
      (when-let [f @(:on-error-fn cv)] (f (str "; key: " (ex-message e))))
      1)))

(defn- on-key [cv]
  (fn [_ctl keyval _keycode state _data]
    (dispatch-key! cv keyval state)))

;; --- construction ------------------------------------------------------

(defn- make-tags!
  "One GtkTextTag per jonquil.console/styles key, the same tag-building
  idiom jonquil.gtk.editor/make-tags! uses for jonquil.paint's styles: fg
  via set-string-property!, bold via set-int-property! weight 700, italic
  via set-italic!. Tags start with theme/default-theme's colours;
  apply-theme! retints them in place, never recreating them."
  [buf]
  (let [table (g/gtk-text-buffer-get-tag-table buf)]
    (into {}
          (map (fn [k]
                 (let [{:keys [fg bold italic]} (console/style (theme/theme theme/default-theme) k)
                       tag (g/gtk-text-tag-new (name k))]
                   (g/set-string-property! tag "foreground" fg)
                   (when bold (g/set-int-property! tag "weight" g/PANGO-WEIGHT-BOLD))
                   (when italic (g/set-italic! tag))
                   (g/gtk-text-tag-table-add table tag)
                   [k tag])))
          (keys console/styles))))

(defn- create-mark! [buf mark-name offset left-gravity?]
  (let [it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-buffer-get-iter-at-offset buf it offset)
      (g/gtk-text-buffer-create-mark buf mark-name it (if left-gravity? 1 0))
      (finally (ffi/free it)))))

(defn- set-up-view!
  "Gives the console its read-only tag, margins, wrapping and scroller."
  [{:keys [view buf scroll readonly-tag]}]
  (g/set-bool-property! readonly-tag "editable" false)
  (g/gtk-text-tag-table-add (g/gtk-text-buffer-get-tag-table buf) readonly-tag)
  (g/gtk-text-view-set-editable view 1)
  (g/gtk-text-view-set-monospace view 1)
  (g/gtk-text-view-set-left-margin view 8)
  (g/gtk-text-view-set-top-margin view 4)
  ;; GTK_WRAP_NONE let any line wider than
  ;; the view open a horizontal scroll range, and scroll-to-end!'s xalign
  ;; 0.0 then scrolled right past the prompt on every drain, clipping
  ;; every later line by its width. Word-char wrap removes the range
  ;; outright, so xalign has nothing left to move. The editor stays
  ;; unwrapped -- code editing expects that -- so this is console-only.
  (g/gtk-text-view-set-wrap-mode view g/GTK-WRAP-WORD-CHAR)
  (g/gtk-scrolled-window-set-child scroll view))

(defn- insert-default-prompt!
  "Writes the default prompt, tags it, and parks the caret after it."
  [buf readonly-tag tags]
  (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-insert buf a default-prompt -1)))
  (g/with-iters* buf 0 (count default-prompt)
    (fn [a b]
      (g/gtk-text-buffer-apply-tag buf readonly-tag a b)
      (g/gtk-text-buffer-apply-tag buf (get tags :prompt) a b)))
  (g/with-iters* buf (count default-prompt) (count default-prompt)
    (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))

(defn- console-marks!
  "The console's four buffer marks, as {:out-end :input-start :end-mark
  :last-entry-start}."
  [buf]
  (let [out-end (create-mark! buf "jonquil-console-out-end" 0 false)
        input-start (create-mark! buf "jonquil-console-input-start" (count default-prompt) true)
        end-mark (create-mark! buf "jonquil-console-scroll-end" (count default-prompt) false)
        ;; Cmd/Ctrl+Shift+L: left gravity, like input-start -- text
        ;; inserted at its own position (this entry's own output,
        ;; landing right where it sits) must end up AFTER it, so the
        ;; mark stays fixed at the entry's start throughout its own
        ;; lifetime. :last-entry-active? is false until mark-entry-
        ;; start! first arms it; see that fn's own docstring.
        last-entry-start (create-mark! buf "jonquil-console-last-entry-start" 0 true)]
    {:out-end out-end :input-start input-start :end-mark end-mark
     :last-entry-start last-entry-start}))

(defn- console-atoms
  "The console's fresh mutable state, as a map of atoms."
  []
  {:prompt-text (atom default-prompt)
   :last-entry-active? (atom false)
   :prev-entry (atom nil)
   :queue (atom [])
   ;; live results (LM1): [{:start :end :ref :text}], oldest first
   :results (atom [])
   :drain-scheduled? (atom false)
   :busy-hint-shown? (atom false)
   :run-start-input (atom "")
   :leftover (atom nil)
   :preedit-active? (atom false)
   ;; Single-slot completion registration (set-
   ;; completion-fns!) plus the on-input-changed!/on-focus-
   ;; lost! registration lists -- see the ns docstring.
   :completion-fns (atom nil)
   :on-input-fns (atom [])
   :on-focus-fns (atom [])
   ;; Single-slot registration for error reporting -- see
   ;; set-error-fn!. nil until jonquil.gtk.window wires it.
   :on-error-fn (atom nil)})

(defn- console-state
  "The console's state map: its marks, queues and registrations."
  [{:keys [view buf scroll tags readonly-tag on-submit history-prev history-next
           evaluating? waiting?]}]
  (merge {:view view
          :buf buf
          :scroll scroll
          :tags tags
          :readonly-tag readonly-tag
          :waiting? waiting?
          :evaluating? evaluating?
          :on-submit on-submit
          :history-prev history-prev
          :history-next history-next}
         (console-marks! buf)
         (console-atoms)))

(defn- connect-input!
  "Connects the key controller (capture phase) and the preedit signal."
  [{:keys [view] :as cv} keyctl]
  (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
  (g/g-signal-connect-data keyctl "key-pressed"
                           (callable (on-key cv) [:pointer :uint :uint :uint :pointer] :int)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-widget-add-controller view keyctl)
  ;; Same GtkTextView::preedit-changed signal as editor.clj (see
  ;; that namespace's citation for the exact gir/header sourcing).
  (g/g-signal-connect-data view "preedit-changed"
                           (callable (fn [_view preedit _data]
                                       (reset! (:preedit-active? cv) (not (empty? preedit)))
                                       nil)
                                     [:pointer :string :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- run-each!
  "Calls each fn in the atom fns, reporting a throw through the console's
  error fn as \"; what: ...\" and going on to the next."
  [cv fns what]
  (doseq [f @fns]
    (try
      (f)
      (catch :default e
        (when-let [ef @(:on-error-fn cv)]
          (ef (str "; " what ": " (ex-message e))))))))

(defn- connect-notify!
  "Connects the cursor-position notify and the focus controller's leave."
  [{:keys [view buf] :as cv} focusctl]
  ;; The buffer's own "notify::cursor-position" property notify
  ;; (covers typing, deletion and every mark-set case in one signal)
  ;; drives on-input-
  ;; changed!'s registrants -- the console's own refine!/close! trigger
  ;; for its REPL completion popup.
  (g/g-signal-connect-data buf "notify::cursor-position"
                           (callable (fn [_buf _pspec _data] (run-each! cv (:on-input-fns cv) "cursor") nil)
                                     [:pointer :pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  ;; Mirrors jonquil.gtk.editor/make-editor's own focusctl wiring
  ;; -- see on-focus-lost!'s own docstring.
  (g/g-signal-connect-data focusctl "leave"
                           (callable (fn [_ctl _data] (run-each! cv (:on-focus-fns cv) "focus") nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-widget-add-controller view focusctl))

(defn make-console-view
  "Builds the console: an editable, monospace GtkTextView with an 8px left /
  4px top margin, inside a GtkScrolledWindow, starting as a bare
  default-prompt (`user=> `) with an empty input after it. set-prompt!
  (below) rewrites it once an evaluator reports its session's namespace.

  on-submit receives {:action :eval|:send-stdin|:eof :text s}. history-prev
  is (fn [current-input] -> string|nil); history-next is (fn [] ->
  string|nil) -- the caller wires both to the session's history
  atom. evaluating?/waiting? are (fn [] bool), read live from the eval
  engine -- default (constantly false) for callers with no engine at
  all (dev/gtk_selftest.clj's bare console rows).

  Returns {:view :buf :scroll} plus internal bookkeeping, mirroring
  jonquil.gtk.editor/make-editor's shape."
  [{:keys [on-submit history-prev history-next evaluating? waiting?]
    :or {evaluating? (constantly false) waiting? (constantly false)}}]
  (let [view (g/gtk-text-view-new)
        buf (g/gtk-text-view-get-buffer view)
        scroll (g/gtk-scrolled-window-new)
        keyctl (g/gtk-event-controller-key-new)
        ;; GtkEventControllerFocus's own "leave" signal, the same
        ;; one jonquil.gtk.editor/make-editor wires for on-focus-lost!.
        focusctl (g/gtk-event-controller-focus-new)
        tags (make-tags! buf)
        readonly-tag (g/gtk-text-tag-new "console-readonly")]
    (set-up-view! {:view view :buf buf :scroll scroll :readonly-tag readonly-tag})
    (insert-default-prompt! buf readonly-tag tags)
    (let [cv (console-state {:view view :buf buf :scroll scroll :tags tags
                             :readonly-tag readonly-tag :on-submit on-submit
                             :history-prev history-prev :history-next history-next
                             :evaluating? evaluating? :waiting? waiting?})]
      (connect-input! cv keyctl)
      (connect-notify! cv focusctl)
      cv)))
