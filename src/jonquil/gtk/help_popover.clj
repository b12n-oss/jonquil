(ns jonquil.gtk.help-popover
  "The quick-doc popover (P4.T4): a jonquil.gtk.popover carrying a symbol's
  documentation in a scrollable, monospace label. Built once per editor by
  jonquil.gtk.window's activate! and reused for the rest of the session --
  show-doc! just replaces the label text and pops it up again; the popover
  itself is never rebuilt. P4.T7 adds rename to this namespace."
  (:require [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]))

(def ^:private DOC-WIDTH 420)
(def ^:private DOC-HEIGHT 260)

(defn make-help-popover
  "Builds the quick-doc popover anchored to view (the editor's
  GtkTextView): autohide off, position :bottom -- below the caret, per
  jonquil.gtk.popover's own docstring and P4 probe 2 -- so it never steals
  keys from the editor (probe 1). Its child is a left-aligned label (CSS
  class jonquil-doc, so jonquil.theme/code-css's popover rules give it the
  theme's monospace font) inside a GtkScrolledWindow sized DOC-WIDTHxDOC-
  HEIGHT, so a long docstring scrolls instead of growing the popover
  without bound. Returns the jonquil.gtk.popover map ({:popover :view
  :open?}) plus :label, the label widget show-doc!/quick-doc-text act on."
  [view]
  (let [label (g/gtk-label-new "")
        scroll (g/gtk-scrolled-window-new)
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    (g/gtk-widget-add-css-class label "jonquil-doc")
    (g/gtk-label-set-xalign label 0.0)
    (g/gtk-widget-set-size-request scroll DOC-WIDTH DOC-HEIGHT)
    (g/gtk-scrolled-window-set-child scroll label)
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
