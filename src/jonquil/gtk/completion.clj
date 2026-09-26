(ns jonquil.gtk.completion
  "The completion popover (P4.T5): a jonquil.gtk.popover carrying a
  GtkListBox of candidates over a doc-preview label (class jonquil-doc,
  same as jonquil.gtk.help-popover's), anchored to the editor's view and
  built once per editor by jonquil.gtk.window's activate! -- open!/close!/
  refine! show, hide and update it for the rest of the session, the popover
  itself is never rebuilt.

  Unlike jonquil.gtk.help-popover, this namespace owns real interaction
  state (:state, a jonquil.help open-state map or nil) on top of the
  popover's own :open? -- (popover/open? c) is the single source of truth
  for \"is completion active\": (state c) reads @(:state c) only while the
  popover is actually open, so a close driven from OUTSIDE this namespace
  (jonquil.gtk.window's registry-wide close-popovers!, used both for Escape
  and for the \"opening another popover closes this one\" rule below, which
  only ever calls the generic popover/hide!, never knows this namespace's
  :state atom exists) still reads back as closed everywhere here, even
  though the atom itself is left stale until the next open!/refine! resets
  it. handle-key!/refine!/commit! all gate on (popover/open? c) for the
  same reason: a stale :state must never drive a swallowed key or a
  reopened popup once the widget itself is hidden.

  ns-fn (opts, at construction) is called at every lookup, not captured
  once -- the running program's namespace can change between Runs, same
  reasoning as window.clj's own (:prompt-ns @(:state session)) call sites.
  close-others! (opts) is jonquil.gtk.window's own close-popovers-fn
  (P4.T3): open!'s own multi-candidate branch calls it right before
  showing, so opening completion closes quick doc/jump's popover the same
  way Escape closes any of them -- through the registry, never a pairwise
  special case. It is NOT called from refine! (already-open, only its
  CONTENT changes) or from the single-candidate commit branch (no popover
  ever shows).

  refine! is wired by jonquil.gtk.window into editor/on-cursor-moved!
  (P3.T5's own Ln/Col mechanism), not a new editor.clj hook: that signal
  already fires for every insert, delete and mark-set on the insert mark
  (editor.clj's own on-cursor-moved! docstring), which covers BOTH of the
  brief's two closing conditions in one place -- a buffer change (typing
  narrows/widens the candidates) and a plain caret move out of the word
  (still-valid? false, no text touched at all, e.g. a Left arrow) -- with
  no separate 'buffer changed' registration needed. commit! (Enter/Tab)
  closes BEFORE calling editor/apply-op!, deliberately not the order the
  brief's prose reads word for word: apply-op!'s own insert fires that same
  cursor-position notify, and if the popover were still marked open at
  that instant, refine! would recompute candidates for the JUST-INSERTED
  symbol (often more than one, e.g. \"map\" itself matches five other
  candidates) and reopen the popup a keystroke after the user asked it to
  close. Closing first makes that refine! call a no-op (popover/open? c)
  already false), so the observable result the brief describes -- one
  undo step, popup closed -- still holds exactly."
  (:require [jonquil.docs :as docs]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.help :as help]))

(def ^:private LIST-WIDTH 320)
(def ^:private LIST-HEIGHT 160)
(def ^:private DOC-HEIGHT 110)

;; gdkkeysyms.h (re-verified against the installed header, matching every
;; other key table in this project): Up 0xff52, Down 0xff54, Page_Up
;; 0xff55, Page_Down 0xff56, Return 0xff0d, KP_Enter 0xff8d, Tab 0xff09,
;; Escape 0xff1b. Return/KP-Enter/Tab match editor.clj's own KEY-RETURN/
;; KEY-KP-ENTER/KEY-TAB values; kept as private copies here the same way
;; window.clj keeps its own KEY-Q/KEY-B/KEY-F1/KEY-F12 rather than reaching
;; into editor.clj's private defs.
(def ^:private KEY-UP 0xff52)
(def ^:private KEY-DOWN 0xff54)
(def ^:private KEY-PAGE-UP 0xff55)
(def ^:private KEY-PAGE-DOWN 0xff56)
(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-KP-ENTER 0xff8d)
(def ^:private KEY-TAB 0xff09)
(def ^:private KEY-ESCAPE 0xff1b)

;; PageUp/PageDown move by 8, clamped -- the brief's own number, measured
;; against nothing further (a plain UI constant, not derived from a probe).
(def ^:private PAGE-STEP 8)

(defn make-completion
  "Builds the completion popover on view (the editor's GtkTextView):
  autohide off, position :bottom, per jonquil.gtk.popover's own docstring
  and P4 probe 2 -- same anchor as jonquil.gtk.help-popover, so it never
  steals keys from the editor (probe 1). Its child is a vertical box: a
  GtkListBox (BROWSE selection, so exactly one candidate row is always
  selected) inside a GtkScrolledWindow sized LIST-WIDTHxLIST-HEIGHT, over a
  left-aligned doc-preview label (class jonquil-doc, so jonquil.theme/
  code-css's popover rules give it the theme's monospace font and size)
  inside its own scroll sized LIST-WIDTHxDOC-HEIGHT -- mirroring help-
  popover's own scrolled-label shape, so a long docstring scrolls instead
  of growing the popover without bound. opts is {:ed :ns-fn :close-others!}
  -- see the ns docstring. Returns the jonquil.gtk.popover map ({:popover
  :view :open?}) plus :ed/:ns-fn/:close-others!/:list-box/:doc-label/
  :state (an atom, the jonquil.help open-state map or nil)."
  [view {:keys [ed ns-fn close-others!]}]
  (let [list-box (g/gtk-list-box-new)
        list-scroll (g/gtk-scrolled-window-new)
        doc-label (g/gtk-label-new "")
        doc-scroll (g/gtk-scrolled-window-new)
        box (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    (g/gtk-list-box-set-selection-mode list-box g/SELECTION-MODE-BROWSE)
    (g/gtk-widget-set-size-request list-scroll LIST-WIDTH LIST-HEIGHT)
    (g/gtk-scrolled-window-set-child list-scroll list-box)
    (g/gtk-widget-add-css-class doc-label "jonquil-doc")
    (g/gtk-label-set-xalign doc-label 0.0)
    (g/gtk-widget-set-size-request doc-scroll LIST-WIDTH DOC-HEIGHT)
    (g/gtk-scrolled-window-set-child doc-scroll doc-label)
    (g/gtk-box-append box list-scroll)
    (g/gtk-box-append box doc-scroll)
    (popover/set-child! pp box)
    (assoc pp
           :ed ed
           :ns-fn ns-fn
           :close-others! close-others!
           :list-box list-box
           :doc-label doc-label
           :state (atom nil))))

(defn state
  "The jonquil.help open-state map for c's current candidates/selection, or
  nil whenever the popover isn't actually showing -- see the ns docstring
  for why this reads (popover/open? c) rather than the raw atom."
  [c]
  (when (popover/open? c) @(:state c)))

(defn- render!
  "Rebuilds c's list-box rows from st's candidates (removing every old row
  first -- gtk_list_box_remove_all, 4.12+), selects st's own index, and
  sets the doc-preview label to help/doc-text for the selected candidate:
  docs/symbol-doc looked up with the CURRENT buffer text and the prefix's
  own start (mirroring window.clj's do-quick-doc!/do-jump!, which pass the
  target symbol's own start as pos)."
  [c st]
  (let [{:keys [list-box doc-label ed ns-fn]} c
        text (editor/editor-text ed)
        pos (get-in st [:prefix-info :start])
        sym (:symbol (help/selected st))]
    (g/gtk-list-box-remove-all list-box)
    (doseq [{:keys [symbol category]} (:candidates st)]
      (g/gtk-list-box-append list-box (g/gtk-label-new (str symbol "  " (name category)))))
    (g/gtk-list-box-select-row list-box (g/gtk-list-box-get-row-at-index list-box (:index st)))
    (g/gtk-label-set-text doc-label
                          (help/doc-text sym (docs/symbol-doc (ns-fn) sym text pos)))))

(defn close!
  "Hides c's popover (idempotent, same as jonquil.gtk.popover/hide!) and
  clears its own :state -- the ui's own :complete!*/handle-key!/refine!
  and window.clj's on-focus-lost! registration all call this."
  [c]
  (when (popover/open? c) (popover/hide! c))
  (reset! (:state c) nil)
  nil)

(defn open!
  "help/prefix-at on snapshot; nil does nothing. Otherwise docs/candidates
  in the program namespace (ns-fn) with snapshot's own text/prefix-end,
  then help/open-state: nil (no candidates) does nothing; a single
  candidate commits at once through editor/apply-op! (no popover, per the
  brief); otherwise close-others! (P4.T3's registry, so any OTHER open P4
  popover closes first -- see the ns docstring) then renders and shows."
  [c snapshot]
  (when-let [p (help/prefix-at snapshot)]
    (let [cands (docs/candidates ((:ns-fn c)) (:prefix p) (:text snapshot) (:end p))
          st (help/open-state p cands)]
      (cond
        (nil? st) nil
        (:single? st) (editor/apply-op! (:ed c) (help/commit-op st))
        :else (do ((:close-others! c))
                  (reset! (:state c) st)
                  (render! c st)
                  (popover/show-at-caret! c)))))
  nil)

(defn- move! [c delta]
  (when-let [st (state c)]
    (let [st2 (help/move st delta)]
      (reset! (:state c) st2)
      (render! c st2))))

(defn- commit! [c]
  (when-let [st (state c)]
    (close! c)
    (editor/apply-op! (:ed c) (help/commit-op st))))

(defn handle-key!
  "The navigation/commit/escape keys, while c's popup is open (gated on
  (state c), see the ns docstring): Up/Down move by 1, PageUp/PageDown by
  8 (help/move's own clamp), Enter/KP-Enter/Tab commit (one apply-op!, one
  undo step, then close -- see commit!'s ordering), Escape closes. Every
  other key, or any key at all while c isn't open, returns 0 so the
  editor's own handle-key! falls through to its normal dispatch (typing
  continues)."
  [c keyval state-mask]
  (if-not (state c)
    0
    (cond
      (= keyval KEY-UP) (do (move! c -1) 1)
      (= keyval KEY-DOWN) (do (move! c 1) 1)
      (= keyval KEY-PAGE-UP) (do (move! c (- PAGE-STEP)) 1)
      (= keyval KEY-PAGE-DOWN) (do (move! c PAGE-STEP) 1)
      (or (= keyval KEY-RETURN) (= keyval KEY-KP-ENTER) (= keyval KEY-TAB)) (do (commit! c) 1)
      (= keyval KEY-ESCAPE) (do (close! c) 1)
      :else 0)))

(defn refine!
  "Called on every editor/on-cursor-moved! tick (window.clj's own wiring,
  not a new editor.clj hook -- see the ns docstring): a no-op unless c is
  actually open. help/prefix-at on the editor's current snapshot; nil
  (caret left the word entirely, e.g. Left arrow past its start, or the
  text now has a selection) closes. Otherwise fresh docs/candidates for
  the new prefix, then help/refine -- nil (still-valid? false, e.g. a
  space landed right after the word) closes; a fresh state renders and
  re-anchors the popover at the caret's new position (popover/show-at-
  caret!, safe to call again on an already-open popover -- it repositions,
  it does not hide-then-reshow)."
  [c]
  (when (state c)
    (let [snap (editor/snapshot (:ed c))]
      (if-let [p (help/prefix-at snap)]
        (let [cands (docs/candidates ((:ns-fn c)) (:prefix p) (:text snap) (:end p))
              st2 (help/refine @(:state c) snap cands)]
          (if st2
            (do (reset! (:state c) st2)
                (render! c st2)
                (popover/show-at-caret! c))
            (close! c)))
        (close! c)))))
