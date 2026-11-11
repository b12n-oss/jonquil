(ns jonquil.gtk.completion
  "The completion popover: a jonquil.gtk.popover carrying a
  GtkListBox of candidates over a doc-preview label (class jonquil-doc,
  same as jonquil.gtk.help-popover's), anchored to the editor's or the
  console's view and built once per view by jonquil.gtk.window.help (the
  editor's) and jonquil.gtk.window.console (the console's) -- open!/close!/
  refine! show, hide and update it for the rest of the session, the popover
  itself is never rebuilt.

  A second instance lives on the console's input region. open!'s
  snapshot argument and refine!'s own :snapshot-fn opt are already generic
  ({:text :caret :sel-start :sel-end}, optionally :extra-context for docs/
  candidates' 5-arity) -- jonquil.gtk.editor/snapshot is only the DEFAULT
  source of one, not a hard dependency. :apply-op! is the matching write
  hook: (fn [edit-op] -> nil), defaulting to jonquil.gtk.editor/apply-op!
  on :ed. Both default closures reproduce the editor instance's exact prior
  behaviour; the console instance overrides them to read and write its own
  input region (jonquil.gtk.console-view/input-snapshot and its own grouped-
  edit apply fn) instead of :ed. :position (:bottom default, :top for the
  console) is the third opt this reuse needed.

  Unlike jonquil.gtk.help-popover, this namespace owns real interaction
  state (:state, a jonquil.help open-state map or nil) on top of the
  popover's own :open? -- (popover/open? c) is the single source of truth
  for \"is completion active\": (state c) reads @(:state c) only while the
  popover is actually open, so a close driven from OUTSIDE this namespace
  (jonquil.gtk.window.help's registry-wide close-popovers!, used both for
  Escape and for the \"opening another popover closes this one\" rule
  below, which only ever calls the generic popover/hide!, never knows this
  namespace's :state atom exists) still reads back as closed everywhere
  here, even though the atom itself is left stale until the next
  open!/refine! resets it. handle-key!/refine!/commit! all gate on
  (popover/open? c) for the same reason: a stale :state must never drive
  a swallowed key or a reopened popup once the widget itself is hidden.

  ns-fn (opts, at construction) is only the default :candidates-fn/:doc-fn
  closures' own source of the running namespace, called at every lookup
  by them, not captured once (same reasoning as the window's own
  (ev/prompt-ns @ev*) call sites) -- render!/open!/refine! call
  :candidates-fn/:doc-fn themselves, never docs/candidates or
  docs/symbol-doc directly, so jonquil.gtk.window.help and
  jonquil.gtk.window.console can route both through
  jonquil.evaluator instead while ns-fn keeps its old meaning for any
  caller that still passes it.

  close-others! (opts) is jonquil.gtk.window.help's own close-popovers-fn:
  open!'s own multi-candidate branch calls it right before
  showing, so opening completion closes quick doc/jump's popover the same
  way Escape closes any of them -- through the registry, never a pairwise
  special case. It is NOT called from refine! (already-open, only its
  CONTENT changes) or from the single-candidate commit branch (no popover
  ever shows). on-error (opts) is the window's own (fn [msg] ->
  set-status!) -- handle-key!'s own try/catch (below) is the only caller.

  handle-key! wraps its whole cond in try/catch and still returns 1 on a
  throw from move!/commit!/close! -- editor.clj's own handle-key! only
  falls through to plain-return?/Tab-indent when the completion delegate
  returns 0, so an uncaught throw here would fall through into a SECOND,
  unrelated edit (handle-return!'s own apply-op!, or Tab's indent) on top
  of whatever commit! partially did, or crash the process outright, since
  handle-return!'s own apply-op! call has no try/catch of its own either.
  Once a key is one completion owns (state c was non-nil when handle-key!
  was entered), the key is consumed regardless of what the owning branch
  does.

  refine! is wired by jonquil.gtk.window.help into editor/on-cursor-moved!
  (the Ln/Col mechanism), not a new editor.clj hook: that signal
  already fires for every insert, delete and mark-set on the insert mark
  (editor.clj's own on-cursor-moved! docstring), which covers BOTH
  closing conditions in one place -- a buffer change (typing
  narrows/widens the candidates) and a plain caret move out of the word
  (still-valid? false, no text touched at all, e.g. a Left arrow) -- with
  no separate 'buffer changed' registration needed. The console instance is
  wired the same way, into jonquil.gtk.console-view/on-input-changed!.

  commit! (Enter/Tab) closes BEFORE calling c's own :apply-op!: an insert
  through apply-op! fires that same cursor-position notify, and if the
  popover were still marked open at that instant, refine! would recompute
  candidates for the JUST-INSERTED symbol (often more than one, e.g.
  \"map\" itself matches five other candidates) and reopen the popup a
  keystroke after the user asked it to close. Closing first makes that
  refine! call a no-op (popover/open? c) already false), so the
  observable result -- one undo step, popup closed -- still holds
  exactly.

  A server evaluator (:server-ev, a fn returning the window's evaluator
  when it is an ev/ServerHelp, else nil) is asked through ev/ask-help!:
  an answer within ev/help-grace-ms is used at once, exactly as the
  synchronous path does, single-candidate commit included. A later answer
  leaves the popup open on the offline names (or none, with
  waiting-text in the doc area) and is merged in by arrived! on the GTK
  thread, when it's still the newest request, the popup is still open,
  and the text and caret haven't changed. The doc preview works the same
  way, with loading-text until its answer lands."
  (:require [jonquil.docs :as docs]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.help :as help]
            [jonquil.syntax :as syntax]))

(def ^:private LIST-WIDTH 320)
(def ^:private LIST-HEIGHT 160)
(def ^:private DOC-HEIGHT 110)
;; The doc-preview label's own width, independent of LIST-WIDTH (the
;; candidate list column stays LIST-WIDTH-wide; only the doc area below
;; it widens) -- see jonquil.gtk.help-popover's DOC-WIDTH
;; comment for the measurement this shares (monospace 13px, ~7.83px/char,
;; 660 covers an 80-column docstring line with a cushion).
(def ^:private DOC-WIDTH 660)

;; gdkkeysyms.h (re-verified against the installed header, matching every
;; other key table in this project): Up 0xff52, Down 0xff54, Page_Up
;; 0xff55, Page_Down 0xff56, Return 0xff0d, KP_Enter 0xff8d, Tab 0xff09.
;; Return/KP-Enter/Tab match editor.clj's own KEY-RETURN/KEY-KP-ENTER/
;; KEY-TAB values; kept as private copies here rather than reaching into
;; editor.clj's private defs.
(def ^:private KEY-UP 0xff52)
(def ^:private KEY-DOWN 0xff54)
(def ^:private KEY-PAGE-UP 0xff55)
(def ^:private KEY-PAGE-DOWN 0xff56)
(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-KP-ENTER 0xff8d)
(def ^:private KEY-TAB 0xff09)

;; PageUp/PageDown move by 8, clamped.
(def ^:private PAGE-STEP 8)

(def ^:private waiting-text "Asking the server...")

(defn- loading-text [sym] (str "Loading docs for " sym "..."))

(defn make-completion
  "Builds the completion popover on view (the editor's or the console's
  GtkTextView): autohide off, position given by opts (:bottom default),
  per jonquil.gtk.popover's own docstring -- same anchor as
  jonquil.gtk.help-popover, so it never steals keys from its view.
  Its child is a vertical box: a
  GtkListBox (BROWSE selection, so exactly one candidate row is always
  selected) inside a GtkScrolledWindow LIST-WIDTH wide and as tall as its
  rows up to LIST-HEIGHT, over
  jonquil.gtk.popover/make-doc-scroll's own doc-preview label+scroll at
  DOC-WIDTHxDOC-HEIGHT (wrapped, top-aligned, no
  horizontal scrolling, sized to content up to DOC-HEIGHT; DOC-WIDTH is
  its own constant, wider than LIST-WIDTH, so a docstring's
  own hard breaks decide its line ends instead of an early soft wrap --
  see DOC-WIDTH's own comment) -- mirroring help-popover's own doc popover
  shape, so a long docstring scrolls instead of growing the popover without
  bound. opts is {:ed :ns-fn :candidates-fn :doc-fn
  :close-others! :on-error :position :snapshot-fn :apply-op! :server-ev} -- see the ns docstring.
  :position is :bottom (default, the editor's own anchor) or :top
  (the console's). :snapshot-fn (fn [] -> a snapshot map, optionally
  carrying :extra-context) defaults to (fn [] (editor/snapshot ed)) and is
  refine!'s only source of a fresh one. :apply-op! (fn [edit-op] -> nil)
  defaults to (fn [result] (editor/apply-op! ed result)) and is the only
  way open!'s single-candidate branch and commit! ever write an edit.
  Returns the jonquil.gtk.popover map ({:popover :view :open?}) plus
  :ed/:ns-fn/:candidates-fn/:doc-fn/:close-others!/:on-error/:snapshot-fn/
  :apply-op!/:list-box/
  :doc-label/:state (an atom, the jonquil.help open-state map, extended
  with :text for render!'s own doc-preview lookup, or nil)."
  [view {:keys [ed ns-fn candidates-fn doc-fn close-others! on-error position snapshot-fn apply-op! server-ev]
         :or {position :bottom
              candidates-fn (fn [prefix text pos extra] (docs/candidates (ns-fn) prefix text pos extra))
              doc-fn (fn [sym text pos] (docs/symbol-doc (ns-fn) sym text pos))
              snapshot-fn (fn [] (editor/snapshot ed))
              apply-op! (fn [result] (editor/apply-op! ed result))}}]
  (let [list-box (g/gtk-list-box-new)
        list-scroll (g/gtk-scrolled-window-new)
        {doc-label :label doc-scroll :scroll} (popover/make-doc-scroll DOC-WIDTH DOC-HEIGHT)
        box (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        pp (popover/make-popover view {:autohide? false :position position})]
    (g/gtk-list-box-set-selection-mode list-box g/SELECTION-MODE-BROWSE)
    ;; Sized to its rows up to LIST-HEIGHT (eight rows), scrolling beyond,
    ;; so two candidates don't leave a six-row gap above the doc preview.
    (g/gtk-widget-set-size-request list-scroll LIST-WIDTH -1)
    (g/gtk-scrolled-window-set-propagate-natural-height list-scroll 1)
    (g/gtk-scrolled-window-set-max-content-height list-scroll LIST-HEIGHT)
    (g/gtk-scrolled-window-set-child list-scroll list-box)
    (g/gtk-box-append box list-scroll)
    (g/gtk-box-append box doc-scroll)
    (popover/set-child! pp box)
    (assoc pp
           :ed ed
           :ns-fn ns-fn
           :candidates-fn candidates-fn
           :doc-fn doc-fn
           :close-others! close-others!
           :on-error on-error
           :snapshot-fn snapshot-fn
           :apply-op! apply-op!
           :list-box list-box
           :doc-label doc-label
           :server-ev server-ev
           :gen (atom 0)
           :doc-gen (atom 0)
           :state (atom nil))))

(defn state
  "The jonquil.help open-state map for c's current candidates/selection, or
  nil whenever the popover isn't actually showing -- see the ns docstring
  for why this reads (popover/open? c) rather than the raw atom."
  [c]
  (when (popover/open? c) @(:state c)))

(defn- server-ev [c] (when-let [f (:server-ev c)] (f)))

(defn- gone?
  "True once c's editor has been destroyed (its tab closed); a late answer
  must not read its buffer then. The console's instance has no :ed and
  lives as long as the window."
  [c]
  (boolean (some-> (:ed c) :destroyed? deref)))

(defn- current?
  "Whether a late answer from evaluator e for token (from gen-key) still
  applies: the newest request, from the window's current evaluator, to an
  open popup whose editor is still there."
  [c gen-key token e]
  (and (= token @(gen-key c)) (identical? e (server-ev c)) (not (gone? c)) (popover/open? c)))

(defn- doc-label-text
  "help/doc-text for doc, or the error line when the request threw."
  [sym doc]
  (if-let [msg (ev/help-error doc)]
    (str "; docs: " msg)
    (help/doc-text sym doc)))

(defn- doc-arrived!
  "A late doc-preview answer, on the GTK thread: shown only while it's
  still the newest doc request and sym is still the selected name of an
  open popup."
  [c e token sym doc]
  (try
    (when (and (current? c :doc-gen token e)
               (= sym (:symbol (help/selected (state c)))))
      (g/gtk-label-set-text (:doc-label c) (doc-label-text sym doc)))
    (catch :default e
      ((:on-error c) (str "; complete: " (ex-message e))))))

(defn- select!
  "Selects st's own index row and sets the doc-preview label to help/
  doc-text for the selected candidate: docs/symbol-doc looked up with st's
  own :text (the snapshot text open!/refine! saw when they built st --
  mirroring jonquil.gtk.window.help's do-quick-doc!/do-jump!, which pass
  the target symbol's own start as pos) and the prefix's own start. The
  rows stay as they are, so an arrow key costs one select, not a list
  rebuild. Nothing scrolls the row into view here, same as render!
  before it. An empty list (a popup still waiting for the server) shows
  waiting-text; a
  server evaluator's doc comes through ev/ask-help!, with loading-text
  until a late answer lands (doc-arrived!)."
  [c st]
  (let [{:keys [list-box doc-label doc-fn]} c
        text (:text st)
        pos (get-in st [:prefix-info :start])
        sym (:symbol (help/selected st))]
    (if-not sym
      (g/gtk-label-set-text doc-label waiting-text)
      (do
        (g/gtk-list-box-select-row list-box (g/gtk-list-box-get-row-at-index list-box (:index st)))
        (let [token (swap! (:doc-gen c) inc)]
          (if-let [e (server-ev c)]
            ;; A request a newer one superseded before it went out is
            ;; skipped, so a slow connection doesn't queue stale lookups
            ;; ahead of the current one.
            (let [[k doc] (ev/ask-help! ev/help-grace-ms
                                        (fn [] (when (= token @(:doc-gen c)) (ev/lookup e sym text pos)))
                                        (fn [doc] (on-gui! (fn [] (doc-arrived! c e token sym doc)))))]
              (g/gtk-label-set-text doc-label (if (= k :now) (doc-label-text sym doc) (loading-text sym))))
            (g/gtk-label-set-text doc-label
                                  (help/doc-text sym (doc-fn sym text pos)))))))))

(defn- render!
  "Rebuilds c's list-box rows from st's candidates (removing every old row
  first -- gtk_list_box_remove_all, 4.12+), selects st's own index, and
  sets the doc-preview label to help/doc-text for the selected candidate:
  docs/symbol-doc looked up with st's own :text (the snapshot text open!/
  refine! saw when they built st -- mirroring jonquil.gtk.window.help's
  do-quick-doc!/do-jump!, which pass the target symbol's own start as pos)
  and the prefix's own start. Each row's label gets xalign
  0 and halign START -- GTK_ALIGN_FILL's default centres a GtkListBoxRow's
  child when the row is wider than the label's own natural size, which is
  every row here (list-box is LIST-WIDTH wide, a candidate name is not)."
  [c st]
  (let [list-box (:list-box c)
        defs (syntax/find-buffer-definitions (or (:text st) ""))]
    (g/gtk-list-box-remove-all list-box)
    (doseq [{:keys [symbol] :as cand} (:candidates st)]
      (let [row-label (g/gtk-label-new (str symbol "  " (help/candidate-label cand defs)))]
        (g/gtk-label-set-xalign row-label 0.0)
        (g/gtk-widget-set-halign row-label g/ALIGN-START)
        (g/gtk-list-box-append list-box row-label)))
    (select! c st)))

(defn close!
  "Hides c's popover (idempotent, same as jonquil.gtk.popover/hide!) and
  clears its own :state -- the ui's own :complete!*/handle-key!/refine!
  and jonquil.gtk.window.tabs's on-focus-lost! registration all call
  this."
  [c]
  (when (popover/open? c) (popover/hide! c))
  (reset! (:state c) nil)
  nil)

(defn- show!
  "Shows st (carrying snapshot's :text, see open!) in c's popover, closing
  every other popover first."
  [c snapshot st]
  (let [st (assoc st :text (:text snapshot))]
    ((:close-others! c) c)
    (reset! (:state c) st)
    (render! c st)
    (popover/show-at-caret! c)))

(defn- show-or-commit!
  "open!'s answer in hand: nothing for no candidates, a single one
  committed at once through :apply-op! (no popover), else shown."
  [c snapshot st]
  (cond
    (nil? st) nil
    (:single? st) ((:apply-op! c) (help/commit-op st))
    :else (show! c snapshot st)))

(defn- same-snapshot? [a b]
  (and a b (= (:text a) (:text b)) (= (:caret a) (:caret b))))

(defn- offline-on-error
  "cands, or, when the request threw (ev/help-error), offline after saying
  so through :on-error."
  [c offline cands]
  (if-let [msg (ev/help-error cands)]
    (do ((:on-error c) (str "; complete: " msg)) offline)
    (or cands offline)))

(defn- arrived!
  "A late candidates answer, on the GTK thread: merged into the open popup
  (help/arrive, the selected name kept) only while it's still the newest
  request, the popup is still open and the text and caret are snapshot's.
  An answer with no names closes the popup; nil (the request threw) keeps
  what's showing, unless that's nothing."
  [c e token snapshot cands]
  (try
    (when (and (current? c :gen token e)
               (same-snapshot? snapshot ((:snapshot-fn c))))
      (let [st (help/arrive (state c) (offline-on-error c (:candidates (state c)) cands))]
        (if (empty? (:candidates st))
          (close! c)
          (do (reset! (:state c) st)
              (render! c st)))))
    (catch :default e
      ((:on-error c) (str "; complete: " (ex-message e))))))

(defn- ask-candidates!
  "e's candidates for prefix p in snapshot, through ev/ask-help!: [:now
  cands] when they land within the grace wait, else [:later], and the
  late answer goes to arrived! on the GTK thread."
  [c e snapshot p]
  (let [token (swap! (:gen c) inc)
        offline (fn [] (ev/offline-candidates e (:prefix p) (:text snapshot) (:end p) (:extra-context snapshot)))
        [k cands] (ev/ask-help! ev/help-grace-ms
                                ;; skipped when superseded before it went out
                                (fn [] (when (= token @(:gen c))
                                         (ev/complete e (:prefix p) (:text snapshot) (:end p) (:extra-context snapshot))))
                                (fn [cands] (on-gui! (fn [] (arrived! c e token snapshot cands)))))]
    (if (= k :now)
      [:now (offline-on-error c (offline) cands)]
      [:later])))

(defn open!
  "help/prefix-at on snapshot; nil does nothing. Otherwise docs/candidates
  in the program namespace (ns-fn) with snapshot's own text/prefix-end/
  extra-context, then help/open-state: nil (no candidates) does nothing; a
  single candidate commits at once through c's own :apply-op! (no
  popover); otherwise close-others! (the popover registry, so any OTHER open
  popover closes first -- see the ns docstring) then renders and
  shows. The state kept for a shown popover carries snapshot's own :text
  alongside help/open-state's own keys, so render!/refine! never need a
  second way to read \"the text this popup is showing candidates for\".
  A server evaluator is asked through ask-candidates! instead: in time,
  the same; late, the popup opens on its offline names (help/
  waiting-state) and arrived! merges the answer in."
  [c snapshot]
  (when-let [p (help/prefix-at snapshot)]
    (if-let [e (server-ev c)]
      (let [[k cands] (ask-candidates! c e snapshot p)]
        (if (= k :now)
          (show-or-commit! c snapshot (help/open-state p cands))
          (show! c snapshot (help/waiting-state p (ev/offline-candidates e (:prefix p) (:text snapshot) (:end p)
                                                                         (:extra-context snapshot))))))
      (do (swap! (:gen c) inc)
          (show-or-commit! c snapshot (help/open-state p ((:candidates-fn c) (:prefix p) (:text snapshot) (:end p)
                                                                             (:extra-context snapshot)))))))
  nil)

(defn- move! [c delta]
  (when-let [st (state c)]
    (let [st2 (help/move st delta)]
      (reset! (:state c) st2)
      (select! c st2))))

(defn- commit! [c]
  (when-let [st (state c)]
    (when (help/selected st)
      (close! c)
      ((:apply-op! c) (help/commit-op st)))))

(defn- nav-modifier-held?
  "True when Control, Meta or Alt is held -- handle-key! ignores every
  navigation/commit key while any of them is held (Ctrl+Up, Cmd+Return
  and the like fall through to the editor's own dispatch instead of
  being swallowed here). Shift is deliberately not checked: Shift+Up and
  friends still navigate."
  [state-mask]
  (not (zero? (bit-and state-mask (bit-or g/CONTROL-MASK g/META-MASK g/ALT-MASK)))))

(defn handle-key!
  "The navigation/commit keys, while c's popup is open (gated on (state
  c), see the ns docstring) and none of Control/Meta/Alt is held
  (nav-modifier-held?): Up/Down move by 1, PageUp/PageDown by 8 (help/
  move's own clamp), Enter/KP-Enter/Tab commit (one apply-op!, one undo
  step, then close -- see commit!'s ordering). Every other key, any key
  held with a nav modifier, or any key at all while c isn't open, returns
  0 so the editor's own handle-key! falls through to its normal dispatch
  (typing continues).

  Once state c is non-nil (this key IS one completion owns), the whole
  cond runs inside try/catch and still returns 1 on a throw from move!/
  commit!/close! -- reporting it through on-error rather than letting it
  escape to editor.clj's own dispatch, which would otherwise treat the
  key as unhandled and fall through into a second, unrelated edit -- see
  the ns docstring."
  [c keyval state-mask]
  (if (or (not (state c)) (empty? (:candidates (state c))) (nav-modifier-held? state-mask))
    0
    (try
      (cond
        (= keyval KEY-UP) (do (move! c -1) 1)
        (= keyval KEY-DOWN) (do (move! c 1) 1)
        (= keyval KEY-PAGE-UP) (do (move! c (- PAGE-STEP)) 1)
        (= keyval KEY-PAGE-DOWN) (do (move! c PAGE-STEP) 1)
        (or (= keyval KEY-RETURN) (= keyval KEY-KP-ENTER) (= keyval KEY-TAB)) (do (commit! c) 1)
        :else 0)
      (catch :default e
        ((:on-error c) (str "; complete: " (ex-message e)))
        1))))

(defn refine!
  "Called on every editor/on-cursor-moved! tick (jonquil.gtk.window.tabs's
  own wiring, not a new editor.clj hook -- see the ns docstring): a no-op
  unless c is actually open. c's own :snapshot-fn is the only source of the
  current snapshot (editor/snapshot on :ed by default; nil is a valid answer --
  the console's own input-snapshot returns nil once the caret has left the
  input region entirely, and help/prefix-at on a nil snapshot is itself
  nil, so that closes exactly like any other out-of-word caret move, with
  no separate nil check needed here). help/prefix-at on it; nil (caret
  left the word entirely, e.g. Left arrow past its start, or the text now
  has a selection) closes. Otherwise fresh docs/candidates for the new
  prefix and extra-context, then help/refine -- nil (still-valid? false,
  e.g. a space landed right after the word) closes; a fresh state (again
  carrying the snapshot's own :text, see open!'s own docstring) renders and
  re-anchors the popover at the caret's new position (popover/show-at-
  caret!, safe to call again on an already-open popover -- it repositions,
  it does not hide-then-reshow). A server evaluator is asked through
  ask-candidates!, as open! does, and a late answer keeps the popup open
  on the offline names (help/refine-waiting) until arrived! merges it."
  [c]
  (when (state c)
    (let [snap ((:snapshot-fn c))
          st @(:state c)]
      (if-let [p (help/prefix-at snap)]
        (let [e (server-ev c)
              st2 (cond
                    (not e)
                    (do (swap! (:gen c) inc)
                        (help/refine st snap ((:candidates-fn c) (:prefix p) (:text snap) (:end p)
                                                                 (:extra-context snap))))
                    (not (help/still-valid? st snap)) nil
                    :else
                    (let [[k cands] (ask-candidates! c e snap p)]
                      (if (= k :now)
                        (help/refine st snap cands)
                        (help/refine-waiting st snap (ev/offline-candidates e (:prefix p) (:text snap) (:end p)
                                                                            (:extra-context snap))))))]
          (if st2
            (let [st2 (assoc st2 :text (:text snap))]
              (reset! (:state c) st2)
              (render! c st2)
              (popover/show-at-caret! c))
            (close! c)))
        (close! c)))))
