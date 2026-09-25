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
  from the eval engine (I2) rather than mirrored into console-owned atoms:
  a mirror set by the caller AFTER starting an eval can lose a race against
  a fast eval's own completion clearing it first, stranding the console in
  :busy forever."
  (:require [clojure.string :as str]
            [jonquil.console :as console]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.ffi :as g]))

(def MAX-OUTPUT-CHARS
  "A chosen bound on the output region, not a measurement (estimate: at 5 to
  10 chars per line, roughly 20k to 40k lines of scrollback)."
  200000)

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

(defn clear-input!
  "Deletes the input region back to empty, leaving the prompt untouched.
  GTK-thread only."
  [cv]
  (let [buf (:buf cv)
        start (mark-offset buf (:input-start cv))
        end (g/gtk-text-buffer-get-char-count buf)]
    (when (< start end)
      (g/with-iters* buf start end (fn [a b] (g/gtk-text-buffer-delete buf a b))))))

(defn clear-output!
  "Deletes the output history [0, out-end), keeping the prompt and any
  typed input untouched. GTK-thread only, like clear-input!. Allowed while
  a program runs (Cmd/Ctrl+L, upstream): new output keeps arriving above
  the prompt at out-end, which this never touches."
  [cv]
  (let [buf (:buf cv)
        end (mark-offset buf (:out-end cv))]
    (when (pos? end)
      (g/with-iters* buf 0 end (fn [a b] (g/gtk-text-buffer-delete buf a b))))))

(defn focus!
  "Grabs focus for the console view and places the cursor at the end of the
  input, mirroring jonquil.gtk.editor/focus!. Used by F6 (P3.T3) so typing
  continues at the end of whatever's already there, rather than wherever an
  earlier click left the caret."
  [cv]
  (g/gtk-widget-grab-focus (:view cv))
  (let [buf (:buf cv)
        end (g/gtk-text-buffer-get-char-count buf)]
    (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
  nil)

(defn reset-busy-hint!
  "Clears the once-per-run :busy hint flag (M3), so the next run that stalls
  on a busy Return earns its own hint instead of staying silent because an
  earlier run already showed one. Safe from any thread: a plain atom reset."
  [cv]
  (reset! (:busy-hint-shown? cv) false))

;; --- draining output ---------------------------------------------------

(defn- coalesce
  "Consecutive same-kind [kind s] chunks merged into one, preserving order."
  [chunks]
  (mapv (fn [group] [(ffirst group) (apply str (map second group))])
        (partition-by first chunks)))

(defn- clip-to-cap
  "text's last max characters, or text unchanged when it already fits (I7).
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
        (when-let [tag (get tags kind)] (g/gtk-text-buffer-apply-tag buf tag a b))))))

(defn- trim!
  "When the output region (0..out-end) is over MAX-OUTPUT-CHARS, deletes
  characters from its front so it fits, reusing console/trim-length's
  reviewed rule directly instead of duplicating its cut-after-newline math
  (P2-close follow-up item 3 -- the duplication was itself a defect: two
  copies of the same rule that could silently drift apart).

  Measures the region's length from out-end's own offset
  (gtk_text_iter_get_offset, via mark-offset) instead of copying the whole
  region just to measure it (I7), then reads only the small tail near the
  cut point: exactly MAX-OUTPUT-CHARS + 1 characters, from-to-n below. That
  exact size is what makes reuse work without passing trim-length the real
  total length n: trim-length's own internal `must` (its length minus
  max-chars) then always works out to exactly 1 in the no-newline case,
  which is exactly what a 1-character offset from `from` needs to equal
  the true cut point -- verified against the original duplicated math
  across 6 cases including the real ~589k-char flood size (see the
  fix-wave report's Follow-up section)."
  [cv]
  (let [buf (:buf cv)
        n (mark-offset buf (:out-end cv))]
    (when (> n MAX-OUTPUT-CHARS)
      (let [from (dec (- n MAX-OUTPUT-CHARS))
            tail (text-range buf from n)
            cut (+ from (console/trim-length tail MAX-OUTPUT-CHARS))]
        (g/with-iters* buf 0 cut (fn [a b] (g/gtk-text-buffer-delete buf a b)))))))

(defn- scroll-to-end!
  "Scrolls the view to the buffer's end via a dedicated mark, since
  gtk_text_view_scroll_to_mark takes a mark, not an iter."
  [cv]
  (let [{:keys [buf view end-mark]} cv
        end (g/gtk-text-buffer-get-char-count buf)]
    (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-move-mark buf end-mark a)))
    (g/gtk-text-view-scroll-to-mark view end-mark 0.0 1 0.0 1.0)))

(defn- drain!
  "Runs on the GTK thread via on-gui!. Flips the scheduled flag BEFORE
  taking the queue -- the same fix jonquil.gtk.callbacks/drain-pending
  applies -- so a chunk that lands in the gap between the flip and the take
  is never stranded: it either lands in the vector this reset-vals! takes,
  or its own append! wins the next compare-and-set! and schedules a second,
  harmless drain. One drain handles everything queued: it coalesces, clips
  each run to MAX-OUTPUT-CHARS before inserting it (I7 -- a print-forever
  program can queue a batch far bigger than the cap between drains), then
  trims once and scrolls once."
  [cv]
  (reset! (:drain-scheduled? cv) false)
  (let [chunks (first (reset-vals! (:queue cv) []))]
    (when (seq chunks)
      (doseq [[kind text] (coalesce chunks)]
        (insert-chunk! cv kind (clip-to-cap text MAX-OUTPUT-CHARS)))
      (trim! cv)
      (scroll-to-end! cv))))

(defn append!
  "Safe from ANY thread: enqueues [kind s] and schedules one drain on the
  GTK thread via on-gui!. Never blocks and never touches GTK itself."
  [cv kind s]
  (swap! (:queue cv) conj [kind s])
  (when (compare-and-set! (:drain-scheduled? cv) false true)
    (on-gui! (fn [] (drain! cv)))))

(defn echo-entry!
  "Adds \"> text\" to the read-only output history above the prompt, in the
  :prompt style, exactly as if it had been typed and submitted -- but
  leaves the current input untouched. Goes through append! (the same
  queue+drain path an eval's own output uses), so Run Selection's echo and
  whatever the eval later prints land in the console in the order they
  were called, even though the eval's own output arrives asynchronously
  from the eval worker thread. Safe from any thread, like append!."
  [cv text]
  (append! cv :prompt (str "> " text "\n")))

;; --- Return ----------------------------------------------------------------

(defn- commit-input!
  "The :eval/:send-stdin steps that turn the current prompt+input into
  read-only history and open a fresh prompt at the end. Every mark move
  here is explicit (gtk_text_buffer_move_mark), not gravity: gravity only
  needs to govern append! and ordinary typing, and relying on it for the
  prompt's own recreation would put the freshly-inserted \"> \" on the wrong
  side of out-end (right gravity means text inserted AT out-end's position
  ends up before it, which is exactly backwards for a prompt that must sit
  after out-end)."
  [cv]
  (let [{:keys [buf tags readonly-tag out-end input-start]} cv
        prompt-start (mark-offset buf out-end)
        old-end (g/gtk-text-buffer-get-char-count buf)]
    (g/with-iters* buf old-end old-end (fn [a _] (g/gtk-text-buffer-insert buf a "\n" -1)))
    (let [end (inc old-end)]
      ;; 1: prompt + input + its closing newline -> read-only, :prompt style
      (g/with-iters* buf prompt-start end
        (fn [a b]
          (g/gtk-text-buffer-apply-tag buf readonly-tag a b)
          (g/gtk-text-buffer-apply-tag buf (get tags :prompt) a b)))
      ;; 3: a fresh "> " at the end, out-end moved to just before it
      (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-insert buf a "> " -1)))
      (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-move-mark buf out-end a)))
      (g/with-iters* buf end (+ end 2)
        (fn [a b]
          (g/gtk-text-buffer-apply-tag buf readonly-tag a b)
          (g/gtk-text-buffer-apply-tag buf (get tags :prompt) a b)))
      (g/with-iters* buf (+ end 2) (+ end 2)
        (fn [a _] (g/gtk-text-buffer-move-mark buf input-start a)))
      (g/with-iters* buf (+ end 2) (+ end 2)
        (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))))

(defn handle-return!
  "console/input-action applied to cv's live state, with the real Shift bit
  from the key event. Both the key controller and self-test rows call this
  instead of synthesizing a keystroke. Returns 0 when GTK should insert a
  plain newline itself, 1 when this function already handled the key.

  :waiting?/:evaluating? are read by CALLING the fns supplied at
  construction (I2), not derefing a console-owned mirror atom: the engine
  is the source of truth, so there's nothing here for a fast eval's
  on-complete to race against and strand.

  :busy hints once per run (M3): the first busy Return after a run starts
  appends a :system reminder; later ones stay silent until
  reset-busy-hint! (called from on-complete) re-arms it for the next run.

  Returns 0 unconditionally while an IME preedit composition is active
  (I5), before even reading input-action: the capture-phase key controller
  runs before the IM context filters the key, so consuming Return here
  would split or lose a pending Hangul/CJK composition."
  [cv shift?]
  (if @(:preedit-active? cv)
    0
    (let [text (input-text cv)
          action (console/input-action {:input text
                                        :waiting? ((:waiting? cv))
                                        :evaluating? ((:evaluating? cv))
                                        :shift? shift?})]
      (case action
        :newline 0
        :noop 1
        :busy (do (when (compare-and-set! (:busy-hint-shown? cv) false true)
                    (append! cv :system "A program is running. Press Stop (Esc) to cancel it.\n"))
                  1)
        (:eval :send-stdin) (do (commit-input! cv)
                                ((:on-submit cv) {:action action :text text})
                                1)))))

;; --- Up/Down -----------------------------------------------------------

(defn- on-first-line-of-input?
  "True when there's no newline between input-start and caret -- Up only
  browses history from the input's first line (I3); on any other line GTK
  moves the caret up within a multi-line entry instead."
  [cv caret]
  (let [buf (:buf cv)
        start (mark-offset buf (:input-start cv))]
    (not (str/includes? (text-range buf start caret) "\n"))))

(defn- on-last-line-of-input?
  "True when there's no newline between caret and the buffer's end -- Down's
  half of the same I3 gate."
  [cv caret]
  (let [buf (:buf cv)
        end (g/gtk-text-buffer-get-char-count buf)]
    (not (str/includes? (text-range buf caret end) "\n"))))

(defn handle-history!
  "Up/Down in the console input. dir is :prev or :next. Replaces the input
  with history-prev/history-next's result when ALL of: the caret is at or
  after input-start, the console isn't waiting on stdin, and the caret sits
  on the input's first line (Up) or last line (Down) -- otherwise a
  multi-line entry's own Up/Down caret movement would be hijacked into
  browsing history instead (I3). A nil replacement (no history to move to,
  or not currently browsing) leaves the input unchanged. Returns true when
  it replaced the input (the key controller returns 1), false otherwise
  (the key controller returns 0, so GTK's normal Up/Down caret movement
  applies). Also false while an IME preedit composition is active (I5),
  same reasoning as handle-return!.

  Checks caret >= start BEFORE computing on-edge-line? (Minor #4 of the
  P2-close follow-up): on-first/last-line-of-input? each read a text-range
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
      (if (and (>= caret start) (not ((:waiting? cv)))
               (case dir
                 :prev (on-first-line-of-input? cv caret)
                 :next (on-last-line-of-input? cv caret)))
        (if-let [replacement (case dir
                               :prev ((:history-prev cv) (input-text cv))
                               :next ((:history-next cv)))]
          (do (clear-input! cv)
              (g/with-iters* buf start start (fn [a _] (g/gtk-text-buffer-insert buf a replacement -1)))
              (let [end (+ start (count replacement))]
                (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
              true)
          false)
        false))))

;; --- keys: Return/KP-Enter, a command-chord D, Up/Down ---------------------

(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-KP-ENTER 0xff8d)
(def ^:private KEY-UP 0xff52)
(def ^:private KEY-DOWN 0xff54)
(def ^:private KEY-D 0x64)

(defn- command-chord?
  "A command chord is Control or Meta (gtk-keys.md), so the same shortcut
  works on Linux and macOS."
  [state]
  (not (zero? (bit-and state (bit-or g/CONTROL-MASK g/META-MASK)))))

(defn- on-key [cv]
  (fn [_ctl keyval _keycode state _data]
    (cond
      (contains? #{KEY-RETURN KEY-KP-ENTER} keyval)
      (handle-return! cv (not (zero? (bit-and state g/SHIFT-MASK))))

      (and (= keyval KEY-D) (command-chord? state) ((:waiting? cv)))
      (do ((:on-submit cv) {:action :eof}) 1)

      (= keyval KEY-UP) (if (handle-history! cv :prev) 1 0)
      (= keyval KEY-DOWN) (if (handle-history! cv :next) 1 0)

      :else 0)))

;; --- construction ------------------------------------------------------

(defn- make-tags!
  "One GtkTextTag per jonquil.console/styles key, the same tag-building
  idiom jonquil.gtk.editor/make-tags! uses for jonquil.paint's styles: fg
  via set-string-property!, bold via set-int-property! weight 700, italic
  via set-italic!."
  [buf]
  (let [table (g/gtk-text-buffer-get-tag-table buf)]
    (into {}
          (map (fn [[k {:keys [fg bold italic]}]]
                 (let [tag (g/gtk-text-tag-new (name k))]
                   (g/set-string-property! tag "foreground" fg)
                   (when bold (g/set-int-property! tag "weight" g/PANGO-WEIGHT-BOLD))
                   (when italic (g/set-italic! tag))
                   (g/gtk-text-tag-table-add table tag)
                   [k tag])))
          console/styles)))

(defn- create-mark! [buf mark-name offset left-gravity?]
  (let [it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-buffer-get-iter-at-offset buf it offset)
      (g/gtk-text-buffer-create-mark buf mark-name it (if left-gravity? 1 0))
      (finally (ffi/free it)))))

(defn make-console-view
  "Builds the console: an editable, monospace GtkTextView with an 8px left /
  4px top margin (global.md), inside a GtkScrolledWindow, starting as a bare
  \"> \" prompt with an empty input after it.

  on-submit receives {:action :eval|:send-stdin|:eof :text s}. history-prev
  is (fn [current-input] -> string|nil); history-next is (fn [] ->
  string|nil) -- the caller (P2.T7) wires both to the session's history
  atom. evaluating?/waiting? are (fn [] bool), read live from the eval
  engine (I2) -- default (constantly false) for callers with no engine at
  all (dev/gtk_selftest.clj's bare console rows).

  Returns {:view :buf :scroll} plus internal bookkeeping, mirroring
  jonquil.gtk.editor/make-editor's shape."
  [{:keys [on-submit history-prev history-next evaluating? waiting?]
    :or {evaluating? (constantly false) waiting? (constantly false)}}]
  (let [view (g/gtk-text-view-new)
        buf (g/gtk-text-view-get-buffer view)
        scroll (g/gtk-scrolled-window-new)
        keyctl (g/gtk-event-controller-key-new)
        tags (make-tags! buf)
        readonly-tag (g/gtk-text-tag-new "console-readonly")]
    (g/set-bool-property! readonly-tag "editable" false)
    (g/gtk-text-tag-table-add (g/gtk-text-buffer-get-tag-table buf) readonly-tag)
    (g/gtk-text-view-set-editable view 1)
    (g/gtk-text-view-set-monospace view 1)
    (g/gtk-text-view-set-left-margin view 8)
    (g/gtk-text-view-set-top-margin view 4)
    (g/gtk-scrolled-window-set-child scroll view)
    (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-insert buf a "> " -1)))
    (g/with-iters* buf 0 2
      (fn [a b]
        (g/gtk-text-buffer-apply-tag buf readonly-tag a b)
        (g/gtk-text-buffer-apply-tag buf (get tags :prompt) a b)))
    (g/with-iters* buf 2 2 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
    (let [cv {:view view
              :buf buf
              :scroll scroll
              :tags tags
              :readonly-tag readonly-tag
              :out-end (create-mark! buf "jonquil-console-out-end" 0 false)
              :input-start (create-mark! buf "jonquil-console-input-start" 2 true)
              :end-mark (create-mark! buf "jonquil-console-scroll-end" 2 false)
              :queue (atom [])
              :drain-scheduled? (atom false)
              :waiting? waiting?
              :evaluating? evaluating?
              :busy-hint-shown? (atom false)
              :preedit-active? (atom false)
              :on-submit on-submit
              :history-prev history-prev
              :history-next history-next}]
      (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
      (g/g-signal-connect-data keyctl "key-pressed"
                               (callable (on-key cv) [:pointer :uint :uint :uint :pointer] :int)
                               ffi/null ffi/null g/CONNECT-DEFAULT)
      (g/gtk-widget-add-controller view keyctl)
      ;; I5: same GtkTextView::preedit-changed signal as editor.clj (see
      ;; that namespace's citation for the exact gir/header sourcing).
      (g/g-signal-connect-data view "preedit-changed"
                               (callable (fn [_view preedit _data]
                                           (reset! (:preedit-active? cv) (not (empty? preedit)))
                                           nil)
                                         [:pointer :string :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT)
      cv)))
