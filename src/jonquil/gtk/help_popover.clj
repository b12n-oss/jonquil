(ns jonquil.gtk.help-popover
  "The quick-doc popover (P4.T4): a jonquil.gtk.popover carrying a symbol's
  documentation in a scrollable, monospace label. Built once per editor by
  jonquil.gtk.window's activate! and reused for the rest of the session --
  show-doc! just replaces the label text and pops it up again; the popover
  itself is never rebuilt.

  P4.T7 adds the rename popover to this namespace: autohide ON (probe 1's
  modal case -- GTK grabs the keyboard and closes the popover itself on
  Escape or a click outside, unlike the quick-doc popover above, which is
  autohide OFF and closed only by window.clj's own registry). Built once
  per editor the same way; show-rename!/show-rename-problem! replace its
  entry text/problem label and (for show-rename!) pop it up again, never
  rebuilding it. Context bullet #2: the entry takes keyboard focus while
  it's open, so make-rename-popover connects a SECOND \"closed\" handler
  (jonquil.gtk.popover/make-popover already connects its own, to keep
  :open? in sync -- see that namespace's docstring) that gives focus back
  to view once rename closes, whatever closed it."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]))

;; Fix wave item 2: wide enough for a real docstring line's hard break to
;; decide where the line ends, not the label's own soft wrap. Measured live
;; (jonquil.gtk.ffi/gtk-widget-measure, HORIZONTAL, on this exact label once
;; the popover is mapped): monospace 13px (commands/DEFAULT-FONT-PX) runs
;; ~7.83px/char here, so 80 columns needs 627px, and the doc-scroll's own
;; width-request lands on the label with NO scrolled-window overhead
;; (allocated width read back as exactly the width-request at the old
;; DOC-WIDTH 420). 660 clears 80 columns (627px) with room to spare --
;; clojure.core/map's own longest line (measured at 70 columns, 548px)
;; fits with margin, and the cushion reaches 84 columns (658px) before a
;; line would soft-wrap at all.
(def ^:private DOC-WIDTH 660)
(def ^:private DOC-HEIGHT 260)
(def ^:private RENAME-WIDTH 260)

(defn make-help-popover
  "Builds the quick-doc popover anchored to view (the editor's
  GtkTextView): autohide off, position :bottom -- below the caret, per
  jonquil.gtk.popover's own docstring and P4 probe 2 -- so it never steals
  keys from the editor (probe 1). Its child is jonquil.gtk.popover/make-
  doc-scroll (fix wave item 5: wrapped, top-aligned, no horizontal
  scrolling, sized to its content up to DOC-HEIGHT and scrollable beyond
  that) at DOC-WIDTH. Returns the jonquil.gtk.popover map ({:popover :view
  :open?}) plus :label, the label widget show-doc!/quick-doc-text act on."
  [view]
  (let [{:keys [label scroll]} (popover/make-doc-scroll DOC-WIDTH DOC-HEIGHT)
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    (popover/set-child! pp scroll)
    (assoc pp :label label)))

(defn show-doc!
  "Sets hp's label to text and pops the popover up at view's caret
  (popover/show-at-caret!)."
  [hp text]
  (g/gtk-label-set-text (:label hp) text)
  (popover/show-at-caret! hp)
  nil)

(defn quick-doc-text
  "hp's label text -- for rows; gtk_label_get_text returns a borrowed
  string (g/label-text), never reflects anything this namespace itself
  doesn't set."
  [hp]
  (g/label-text (:label hp)))

;; --- rename (P4.T7) ---------------------------------------------------------

(defn make-rename-popover
  "Builds the rename popover: autohide ON, position :bottom -- same caret
  anchor as make-help-popover above, but modal (probe 1's autohide-TRUE
  case), the shape rename wants since its entry must receive typing. Its
  child is a vertical box: a GtkEntry (the new name) over a left-aligned
  problem label (class jonquil-doc, same styling as the quick-doc label
  above), hidden while there's nothing to report (fix wave item 5: an
  empty label still claims a row of vertical space in a GtkBox, which
  looked like dead space under the entry -- gtk_widget_set_visible FALSE
  removes it entirely; show-rename-problem! below shows it again the
  moment there's real text). Connects a SECOND
  \"closed\" handler (jonquil.gtk.popover/make-popover's own autohide
  branch already connects the first, to keep :open? in sync) that gives
  keyboard focus back to view -- every close path (Enter's own hide!,
  GTK's own Escape/click-outside) ends in this same signal, so this is the
  one place that needs to run regardless of which path closed it. Returns
  the jonquil.gtk.popover map plus :entry and :problem-label."
  [view]
  (let [entry (g/gtk-entry-new)
        problem-label (g/gtk-label-new "")
        box (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        pp (popover/make-popover view {:autohide? true :position :bottom})]
    (g/gtk-widget-set-size-request entry RENAME-WIDTH -1)
    (g/gtk-widget-add-css-class problem-label "jonquil-doc")
    (g/gtk-label-set-xalign problem-label 0.0)
    (g/gtk-widget-set-visible problem-label 0)
    (g/gtk-box-append box entry)
    (g/gtk-box-append box problem-label)
    (popover/set-child! pp box)
    (g/g-signal-connect-data (:popover pp) "closed"
                             (callable (fn [_pop _data] (g/gtk-widget-grab-focus view) nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (assoc pp :entry entry :problem-label problem-label)))

(defn show-rename!
  "Prefills rp's entry with symbol and selects all of it (GIR's own doc
  for gtk_editable_select_region: a negative end_pos selects to the end),
  clears any earlier problem text and hides the (now empty) problem label
  (fix wave item 5), pops the popover up at the caret (popover/show-at-
  caret!, same as show-doc! above) and only THEN selects/focuses the
  entry: a later call touching selection can revoke an earlier widget's,
  so every selection-touching call here happens last."
  [rp symbol]
  (g/gtk-editable-set-text (:entry rp) symbol)
  (g/gtk-label-set-text (:problem-label rp) "")
  (g/gtk-widget-set-visible (:problem-label rp) 0)
  (popover/show-at-caret! rp)
  (g/gtk-editable-select-region (:entry rp) 0 -1)
  (g/gtk-widget-grab-focus (:entry rp))
  nil)

(defn show-rename-problem!
  "Sets rp's problem label to text and shows it (fix wave item 5: hidden
  again once text is empty, same as make-rename-popover's own initial
  state) -- the ui's own :rename-to!* shows rename-problem's sentence
  here instead of closing the popover."
  [rp text]
  (g/gtk-label-set-text (:problem-label rp) text)
  (g/gtk-widget-set-visible (:problem-label rp) (if (empty? text) 0 1))
  nil)

(defn rename-entry-text
  "rp's entry text (g/editable-text), or \"\" when it reads back nil --
  for the entry's own \"activate\" handler and for rows."
  [rp]
  (or (g/editable-text (:entry rp)) ""))

(defn rename-problem-text
  "rp's problem label text -- for rows."
  [rp]
  (g/label-text (:problem-label rp)))
