(ns jonquil.gtk.popover
  "P4: a caret-anchored GtkPopover base, shared by completion, quick doc and
  rename (P4.T4-T7). Anchored to the view it's built for (the editor or the
  REPL input), so it moves with whichever pane actually has the caret --
  but its actual GtkWidget parent (gtk_widget_set_parent) is that view's
  own immediate container, never the view itself. That deviates from the
  P4 probes' own code (spikes/p4/p4_1_popover_focus.clj,
  p4_2_caret_anchor.clj, p4_7_popover_colours.clj all parent straight to
  the GtkTextView) because of a live finding those probes never hit: every
  probe process ends with System/exit, which skips GTK's own dispose
  cascade entirely, so none of them ever destroyed the window they built.
  Doing that for real (2026-09-26, sample'd via `sample <pid> 3` on a
  hung bb gtk:selftest -- see the report) showed the main thread pinned at
  100% CPU in gtk_text_view_dispose -> gtk_text_view_remove ->
  g_log_structured_standard: GtkTextView's own dispose walks its
  structural children to detach them, doesn't recognise a foreign
  (non-anchor) child, logs \"GtkPopover is not a child of GtkTextView\"
  every iteration, and never actually detaches it -- the loop never ends,
  so closing the window (or the process exiting through the self-test's
  own real close chord) hangs forever. A plain GtkScrolledWindow (the
  view's actual parent in both the editor and the console -- GTK4's
  GtkTextView implements GtkScrollable directly, so
  gtk_scrolled_window_set_child attaches it with no extra GtkViewport in
  between) has no such override and destroys cleanly, measured against
  the same real teardown path. jonquil.gtk.window's own registry
  (:close-popovers!*) still closes (popdown, not unparent) whichever P4
  popover is open; nothing here unparents on hide!, since a production
  popover is built once and shown/hidden for the rest of the session.

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
  the popover's actual GtkWidget parent. Measured live (2026-09-26) against
  a scrolled, margined view matching the editor's own setup: the
  translated point equalled the untranslated one exactly (no border
  between the view and its scrolled window in this theme), but the
  translation is applied unconditionally rather than assumed, since a
  theme with a scrolled-window frame would make it nonzero."
  (:require [jolt.ffi :as ffi]
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
  show-at-caret! and hide!."
  [view {:keys [autohide? position]}]
  (let [pop (g/gtk-popover-new)]
    (g/gtk-widget-set-parent pop (g/gtk-widget-get-parent view))
    (g/gtk-widget-add-css-class pop "jonquil-popover")
    (g/gtk-popover-set-autohide pop (if autohide? 1 0))
    (g/gtk-popover-set-position pop (if (= position :top)
                                      g/POPOVER-POS-TOP
                                      g/POPOVER-POS-BOTTOM))
    {:popover pop :view view :open? (atom false)}))

(defn set-child!
  "Sets pp's popover child widget (gtk_popover_set_child)."
  [pp widget]
  (g/gtk-popover-set-child (:popover pp) widget)
  nil)

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

(defn open?
  "True between show-at-caret! and hide! on this popover."
  [pp]
  @(:open? pp))
