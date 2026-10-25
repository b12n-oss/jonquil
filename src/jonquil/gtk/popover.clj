(ns jonquil.gtk.popover
  "P4: a caret-anchored GtkPopover base, shared by completion, quick doc and
  rename (P4.T4-T7). Anchored to the view it's built for (the editor or the
  REPL input), so it moves with whichever pane actually has the caret --
  but its actual GtkWidget parent (gtk_widget_set_parent) is that view's
  own immediate container, never the view itself. Parenting straight to
  the GtkTextView (what the P4 probes' own code does --
  spikes/p4/p4_1_popover_focus.clj, p4_2_caret_anchor.clj,
  p4_7_popover_colours.clj) hangs a real window close: GtkTextView's own
  dispose walks its structural children to detach them, doesn't recognise
  a foreign (non-anchor) child, logs \"GtkPopover is not a child of
  GtkTextView\" every iteration, and never actually detaches it, so the
  main thread pins at 100% CPU in gtk_text_view_dispose ->
  gtk_text_view_remove -> g_log_structured_standard forever (measured via
  `sample <pid> 3` on a hung bb gtk:selftest). The probes themselves never
  hit this, since every probe process ends with System/exit, which skips
  GTK's own dispose cascade entirely, so none of them ever destroyed the
  window they built. A plain GtkScrolledWindow (the view's actual parent
  in both the editor and the console -- GTK4's GtkTextView implements
  GtkScrollable directly, so gtk_scrolled_window_set_child attaches it
  with no extra GtkViewport in between) has no such override and destroys
  cleanly, measured against the same real teardown path. jonquil.gtk.
  window's own registry (:close-popovers!*) still closes (popdown, not
  unparent) whichever P4 popover is open; nothing here unparents on
  hide!, since a production popover is built once and shown/hidden for
  the rest of the session.

  Probe 1 (P4 findings #1): with autohide FALSE the popover stays visible
  and mapped, the window's focus widget stays the view, and the view's own
  capture-phase key controller keeps receiving every key -- so a
  completion/quick-doc popover (autohide off) never steals typing; nothing
  here needs to filter keys. Autohide TRUE is modal (GTK grabs the
  keyboard and closes on Escape/click-outside itself) -- the shape rename
  wants (P4.T7). Either way this namespace never closes a popover on its
  own initiative; jonquil.gtk.window's Escape handling
  (:close-popovers!*) does that, since the window's capture-phase
  controller runs before a popover ever sees the key (probe 4).

  Probe 2 (P4 findings #2): show-at-caret! points the popover at the
  view's insert-mark rectangle via gtk_text_view_get_iter_location then
  gtk_text_view_buffer_to_window_coords (GTK_TEXT_WINDOW_TEXT) --
  jonquil.gtk.ffi/caret-window-rect -- then, since the popover's real
  parent is the view's container rather than the view (above),
  jonquil.gtk.ffi/compute-point translates that point into the parent's
  own coordinate space before gtk_popover_set_pointing_to: GIR's own doc
  for set_pointing_to says the rectangle is in the coordinate space of
  the popover's actual GtkWidget parent. Measured against a scrolled,
  margined view matching the editor's own setup: the translated point
  equalled the untranslated one exactly (no border between the view and
  its scrolled window in this theme), but the translation is applied
  unconditionally rather than assumed, since a
  theme with a scrolled-window frame would make it nonzero.

  P4.T7: make-popover's autohide branch connects the popover's own
  \"closed\" signal (GtkPopover, Gtk-4.0.gir: emitted whenever the popover
  closes, for any reason) to reset :open? to false. Rename (autohide ON)
  is the first caller this matters for: GTK itself closes an autohide
  popover on Escape or a click outside, with no call into this namespace
  at all, so without this the :open? atom -- which hide! is otherwise the
  only writer of -- would keep reading true after a GTK-side close.
  hide!'s own gtk_popover_popdown call also ends in this SAME \"closed\"
  signal, so an explicit hide! sets :open? false once directly and once
  more (redundantly, harmlessly) when \"closed\" fires -- both writes
  agree, and reset! has no failure mode from running twice with the same
  value. Every non-autohide popover (completion, quick doc) never has
  anything connected here and is unaffected."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.ffi :as g]))

(defn- rect-alloc [[x y w h]]
  (let [r (ffi/alloc 16)]
    (ffi/write r :int x 0)
    (ffi/write r :int y 4)
    (ffi/write r :int w 8)
    (ffi/write r :int h 12)
    r))

(defn make-popover
  "A GtkPopover carrying CSS class jonquil-popover (so
  jonquil.theme/code-css's popover rules apply), with autohide and position
  set as given -- (:bottom the editor's completion/quick-doc anchor below
  the caret, :top the REPL input's anchor above it, per probe 2). Its
  GtkWidget parent is (gtk_widget_get_parent view) -- view's own immediate
  container, not view itself (see the ns docstring) -- so view must already
  be inside one (true for the editor's and the console's views by the time
  P4.T4-T7 or a self-test row ever calls this). Returns {:popover :view
  :open?}; :open? is this popover's own visibility bit, true only between
  show-at-caret! and hide! -- and, for an autohide popover, also kept
  false by the \"closed\" signal handler this connects when GTK closes it
  on its own (see the ns docstring's P4.T7 paragraph)."
  [view {:keys [autohide? position]}]
  (let [pop (g/gtk-popover-new)
        open? (atom false)]
    (g/gtk-widget-set-parent pop (g/gtk-widget-get-parent view))
    (g/gtk-widget-add-css-class pop "jonquil-popover")
    ;; Fix wave item 5 (M2's left-edge finding): GTK_ALIGN_START on the
    ;; popover's OWN widget, not just its child, moves its left edge to the
    ;; caret instead of centring the popover on the pointing-to rect --
    ;; measured live against the completion scene with a caret three
    ;; columns from the editor's own left margin: image width dropped from
    ;; 2082px to 1800px (exactly 900 logical x 2, the window's own width),
    ;; and the popover's own rounded corner now sits inside the window
    ;; frame instead of past it. Holds for every position (:top/:bottom),
    ;; since GTK centres a BOTTOM/TOP popover along the perpendicular axis
    ;; regardless of halign only when halign is left at its FILL default.
    (g/gtk-widget-set-halign pop g/ALIGN-START)
    (g/gtk-popover-set-autohide pop (if autohide? 1 0))
    (g/gtk-popover-set-position pop (if (= position :top)
                                      g/POPOVER-POS-TOP
                                      g/POPOVER-POS-BOTTOM))
    (when autohide?
      (g/g-signal-connect-data pop "closed"
                               (callable (fn [_pop _data] (reset! open? false) nil)
                                         [:pointer :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT))
    {:popover pop :view view :open? open?}))

(defn set-child!
  "Sets pp's popover child widget (gtk_popover_set_child)."
  [pp widget]
  (g/gtk-popover-set-child (:popover pp) widget)
  nil)

(defn make-doc-scroll
  "A scrolled, top-aligned, word-wrapped label (CSS class jonquil-doc, so
  jonquil.theme/code-css's popover rules give it the theme's monospace
  font), shared by jonquil.gtk.help-popover's quick-doc label and
  jonquil.gtk.completion's doc-preview label -- fix wave item 5 (M2):
  gtk_label_set_wrap plus PANGO_WRAP_WORD_CHAR (falling back to a
  character break only where a single word doesn't fit its own line) in
  place of the old clipped, unwrapped label; GTK_POLICY_NEVER on the
  scroller's own horizontal axis, so a long unbroken token scrolls
  vertically with the rest of the text instead of falling back to a
  horizontal scrollbar the macOS overlay style hides; GTK_ALIGN_START on
  the label's own valign, so short text sits at the top instead of the
  default centre; and gtk_scrolled_window_set_propagate_natural_height
  plus set_max_content_height in place of a fixed height request, so the
  popover sizes to its actual content up to max-height and only scrolls
  beyond that -- no more tall empty box under a one-line doc. width is
  still a fixed size request (height -1, so only the max-content-height
  clamp bounds the vertical size), keeping each popover's own width
  exactly as before. With {:fit-width? n}, the scroller takes the label's
  natural width instead, capped at n characters (gtk_label_set_max_width_
  chars, so the cap follows the zoom), so a one-line doc gets a narrow
  popover rather than the full 80 columns (P4 parked item). The cap is on
  the label because the scroller's own max-content-width doesn't apply
  while its horizontal policy is NEVER: measured, a long doc grew to
  1175 px. The completion preview keeps the fixed width, so its
  popup doesn't change size as the selection moves. Returns {:label
  :scroll}; callers set the label's text and append :scroll into their
  own box (gtk_box_append)."
  ([width max-height] (make-doc-scroll width max-height {}))
  ([width max-height {:keys [fit-width?]}]
   (let [label (g/gtk-label-new "")
         scroll (g/gtk-scrolled-window-new)]
     (g/gtk-widget-add-css-class label "jonquil-doc")
     (g/gtk-label-set-xalign label 0.0)
     (g/gtk-label-set-wrap label 1)
     (g/gtk-label-set-wrap-mode label g/PANGO-WRAP-WORD-CHAR)
     (g/gtk-widget-set-valign label g/ALIGN-START)
     (if fit-width?
       (do (g/gtk-label-set-max-width-chars label fit-width?)
           (g/gtk-scrolled-window-set-propagate-natural-width scroll 1))
       (g/gtk-widget-set-size-request scroll width -1))
     (g/gtk-scrolled-window-set-policy scroll g/POLICY-NEVER g/POLICY-AUTOMATIC)
     (g/gtk-scrolled-window-set-propagate-natural-height scroll 1)
     (g/gtk-scrolled-window-set-max-content-height scroll max-height)
     (g/gtk-scrolled-window-set-child scroll label)
     {:label label :scroll scroll})))

(defn show-at-caret!
  "Points pp's popover at view's caret and pops it up. probe 2:
  g/caret-window-rect on the view, its origin translated
  (g/compute-point) from the view's own coordinates into the popover's
  actual GtkWidget parent's (see the ns docstring), then
  gtk_popover_set_pointing_to. Callers set the popover's child
  (set-child!) before calling this, same order the probes used."
  [pp]
  (let [{:keys [popover view open?]} pp
        anchor (g/gtk-widget-get-parent popover)
        [x y w h] (g/caret-window-rect view)
        [tx ty] (or (g/compute-point view anchor x y) [x y])
        r (rect-alloc [(Math/round (double tx)) (Math/round (double ty)) w h])]
    (try
      (g/gtk-popover-set-pointing-to popover r)
      (finally (ffi/free r)))
    (g/gtk-popover-popup popover)
    (reset! open? true))
  nil)

(defn hide!
  "Closes pp's popover (gtk_popover_popdown), idempotent when it's already
  closed. Never unparents -- a production popover is built once by
  make-popover and shown/hidden for the rest of the session."
  [pp]
  (g/gtk-popover-popdown (:popover pp))
  (reset! (:open? pp) false)
  nil)

(defn caret-visible?
  "True when view's caret rectangle intersects its visible text rectangle
  (both in buffer coordinates)."
  [view]
  (let [[cx cy cw ch] (g/caret-buffer-rect view)
        [vx vy vw vh] (g/text-view-visible-rect view)]
    (and (< cx (+ vx vw)) (> (+ cx (max 1 cw)) vx)
         (< cy (+ vy vh)) (> (+ cy ch) vy))))

(defn follow-caret!
  "A scroll moved the text under pp's OPEN popover: re-points it at the
  caret's new rectangle (show-at-caret!, the clean repositioning path), or
  hides it once the caret left the visible text, so it never floats over
  unrelated lines. A no-op while closed. GTK-thread only, like show and
  hide (value-changed handlers run there)."
  [pp]
  (when @(:open? pp)
    (if (caret-visible? (:view pp))
      (show-at-caret! pp)
      (hide! pp)))
  nil)

(defn open?
  "True between show-at-caret! and hide! on this popover."
  [pp]
  @(:open? pp))

(defn- refocus!
  "Gives view focus back, unless something other than view's own container
  (where GTK leaves it when a popover parented there unmaps) holds it, or
  view is no longer in a window (its tab closed while the popover was
  open, or by a command run from it)."
  [view]
  (let [root (g/gtk-widget-get-root view)]
    (when-not (ffi/null? root)
      (let [f (g/gtk-root-get-focus root)]
        (when (or (ffi/null? f) (= f (g/gtk-widget-get-parent view)))
          (g/gtk-widget-grab-focus view))))))

(defn refocus-view-on-close!
  "Connects pp's \"closed\" signal so that, whichever path closed it (hide!,
  GTK's own Escape or click outside), its view gets keyboard focus back.

  Posted (on-gui!), not done in the handler: GTK moves focus to the
  popover's parent (the view's scrolled window) once the popover unmaps,
  after \"closed\" has run, so a grab in the handler is undone (measured
  with the command palette in slice S8, and then on the rename popover:
  the root's focus was the scrolled window, not the view). And only while
  focus is still there or nowhere, so a popover a command just opened
  keeps the focus it took. The posted thunk holds its own ref on the view,
  since closing a tab can finalize it before the thunk runs."
  [pp]
  (let [view (:view pp)]
    (g/g-signal-connect-data (:popover pp) "closed"
                             (callable (fn [_pop _data]
                                         (g/g-object-ref view)
                                         (on-gui! #(try (refocus! view) (finally (g/g-object-unref view))))
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    nil))
