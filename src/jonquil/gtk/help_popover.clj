(ns jonquil.gtk.help-popover
  "The quick-doc popover: a jonquil.gtk.popover carrying a symbol's
  documentation in a scrollable, monospace label. Built once per editor by
  jonquil.gtk.window's activate! and reused for the rest of the session --
  show-doc! just replaces the label text and pops it up again; the popover
  itself is never rebuilt.

  This namespace also holds the rename popover: autohide ON (modal: GTK grabs the keyboard and closes the popover itself on
  Escape or a click outside, unlike the quick-doc popover above, which is
  autohide OFF and closed only by window.clj's own registry). Built once
  per editor the same way; show-rename!/show-rename-problem! replace its
  entry text/problem label and (for show-rename!) pop it up again, never
  rebuilding it. The entry takes keyboard focus while
  it's open, so make-rename-popover connects a SECOND \"closed\" handler
  (jonquil.gtk.popover/make-popover already connects its own, to keep
  :open? in sync -- see that namespace's docstring) that gives focus back
  to view once rename closes, whatever closed it.

  It also holds the full-doc popover: a SECOND quick-doc-shaped popover
  (make-full-doc-popover), for jonquil.gtk.window's do-quick-doc! to show
  in on a second press over the SAME target -- an evaluator's
  jonquil.evaluator/FullDoc, backed by the server's own doc op, answers
  more than quick doc's summary. show-full-doc! raises its max content
  height (60% of the window's own height) before popping it up, since
  unlike the quick-doc popover it can't size itself once at construction."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.doc-examples :as doc-examples]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]))

;; Wide enough for a real docstring line's hard break to
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

;; The quick-doc popover's cap in characters, a cap rather than a fixed
;; width (it fits its text): DOC-WIDTH's 660 px was about 84 columns, an
;; 80-column docstring plus a cushion, and max-width-chars works from
;; Pango's approximate character width, so the cushion stays.
(def ^:private DOC-COLUMNS 84)
(def ^:private DOC-HEIGHT 260)
(def ^:private RENAME-WIDTH 260)

(defn make-help-popover
  "Builds the quick-doc popover anchored to view (the editor's
  GtkTextView): autohide off, position :bottom -- below the caret, per
  jonquil.gtk.popover's own docstring -- so it never steals
  keys from the editor. Its child is jonquil.gtk.popover/make-
  doc-scroll (wrapped, top-aligned, no horizontal
  scrolling, sized to its content up to DOC-HEIGHT and scrollable beyond
  that) at DOC-WIDTH. Returns the jonquil.gtk.popover map ({:popover :view
  :open?}) plus :label, the label widget show-doc!/quick-doc-text act on."
  [view]
  (let [{:keys [label scroll]} (popover/make-doc-scroll DOC-WIDTH DOC-HEIGHT {:fit-width? DOC-COLUMNS})
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    (popover/set-child! pp scroll)
    (assoc pp :label label :scroll scroll)))

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

;; --- full doc ---------------------------------------------------------------

(defn- focus-inside?
  "True when the root's window is active and its focus widget is view or
  inside popover. An inactive window (Cmd+Tab, another window) keeps its
  focus widget, so the focus widget alone would leave the popover open over
  a background window."
  [pp]
  (let [view (:view pp)
        root (g/gtk-widget-get-root view)]
    (and (not (ffi/null? root))
         (not (zero? (g/gtk-window-is-active root)))
         (let [f (g/gtk-root-get-focus root)]
           (and (not (ffi/null? f))
                (or (= f view)
                    (not (zero? (g/gtk-widget-is-ancestor f (:popover pp))))))))))

(defn hide-unless-focused!
  "Hides fp, unless the root's focus is the editor view or something inside
  fp (an example's button). Posted (on-gui!): GTK updates the root's focus
  after the leave handler that calls this has run (see
  jonquil.gtk.popover/refocus!). A no-op while fp is closed."
  [fp]
  (let [view (:view fp)]
    (g/g-object-ref view)
    (on-gui! (fn []
               (try
                 (when (and (popover/open? fp) (not (focus-inside? fp)))
                   (popover/hide! fp))
                 (finally (g/g-object-unref view)))))))

(defn make-full-doc-popover
  "Builds the full-doc popover: a SECOND popover, built once per editor
  the same way make-help-popover above builds the quick-doc one (same
  DOC-WIDTH, autohide off, position :bottom), for jonquil.gtk.window's
  do-quick-doc! to show a second quick-doc press's full documentation in.
  Its own map also keeps :scroll, unlike make-help-popover's
  -- show-full-doc! below raises this popover's max content height at
  every show (60% of the window's own height, computed by the
  caller), something the quick-doc popover never needs to do. It also
  keeps :text* and :examples*, what show-full-doc! last showed, and a focus
  controller on the popover that closes it (hide-unless-focused!) when
  focus leaves it for anything but the editor."
  [view]
  (let [{:keys [label scroll]} (popover/make-doc-scroll DOC-WIDTH DOC-HEIGHT)
        pp (popover/make-popover view {:autohide? false :position :bottom})
        fp (assoc pp :label label :scroll scroll :text* (atom "") :examples* (atom []))
        focusctl (g/gtk-event-controller-focus-new)]
    (popover/set-child! pp scroll)
    ;; Escape or any other close with focus on an example's button returns
    ;; focus to the editor
    (popover/refocus-view-on-close! pp)
    ;; the scroll swaps its child between this label and a per-show box; the
    ;; extra ref keeps the label alive while the box is the child
    (g/g-object-ref label)
    (g/g-signal-connect-data focusctl "leave"
                             (callable (fn [_ctl _data] (hide-unless-focused! fp) nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller (:popover pp) focusctl)
    fp))

(defn- doc-label
  "A selectable, wrapped, monospace label (class jonquil-doc) holding text."
  [text]
  (let [l (g/gtk-label-new text)]
    (g/gtk-widget-add-css-class l "jonquil-doc")
    (g/gtk-label-set-xalign l 0.0)
    (g/gtk-label-set-wrap l 1)
    (g/gtk-label-set-wrap-mode l g/PANGO-WRAP-WORD-CHAR)
    (g/gtk-label-set-selectable l 1)
    (g/gtk-widget-set-halign l g/ALIGN-START)
    l))

(defn- connect-click! [btn f]
  (g/g-signal-connect-data btn "clicked"
                           (callable (fn [_btn _data] (f) nil) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- example-widgets
  "Appends one example's heading, code label and buttons to box. Returns
  {:n :code :run :copy}; the buttons are nil for blank code."
  [box {:keys [n body code]} {:keys [on-run on-copy]}]
  (let [head (g/gtk-label-new (str "Example " n))
        ex {:n n :code code}]
    (g/gtk-widget-set-halign head g/ALIGN-START)
    (g/gtk-box-append box head)
    (g/gtk-box-append box (doc-label body))
    (if (empty? (str/trim code))
      (assoc ex :run nil :copy nil)
      (let [row (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
            run (g/gtk-button-new-with-label "Run in Interactions")
            copy (g/gtk-button-new-with-label "Copy to Definitions")]
        (connect-click! run #(when on-run (on-run code)))
        (connect-click! copy #(when on-copy (on-copy code)))
        (g/gtk-box-append row run)
        (g/gtk-box-append row copy)
        (g/gtk-box-append box row)
        (assoc ex :run run :copy copy)))))

(defn show-full-doc!
  "Raises fp's max content height (gtk_scrolled_window_set_max_content_
  height) to max-height, shows text, then pops it up at view's caret
  (popover/show-at-caret!) -- same shape as show-doc! above, with the
  height set first since it changes on every show.

  The 3-arity puts text in the single label. The 4-arity splits text
  (jonquil.doc-examples/split): without examples that is the label path;
  with them the scroll holds a vertical box built per show: the doc, then
  per example a heading, its raw body (selectable) and buttons \"Run in
  Interactions\" and \"Copy to Definitions\", whose clicks call (on-run
  code) / (on-copy code) with the cleaned code. full-doc-text keeps the
  whole text either way."
  ([fp text max-height] (show-full-doc! fp text max-height nil))
  ([fp text max-height handlers]
   (g/gtk-scrolled-window-set-max-content-height (:scroll fp) max-height)
   ;; a throw in the split shows the plain text, as a doc without examples
   (let [{:keys [doc title examples]} (try (doc-examples/split text)
                                           (catch :default _ {:examples []}))]
     (reset! (:text* fp) text)
     (if (or (nil? handlers) (empty? examples))
       (do (g/gtk-label-set-text (:label fp) text)
           (g/gtk-scrolled-window-set-child (:scroll fp) (:label fp))
           (reset! (:examples* fp) []))
       (let [box (g/gtk-box-new g/ORIENTATION-VERTICAL 6)]
         (g/gtk-box-append box (doc-label (str doc "\n\n== " title)))
         (reset! (:examples* fp) (mapv #(example-widgets box % handlers) examples))
         (g/gtk-scrolled-window-set-child (:scroll fp) box))))
   (popover/show-at-caret! fp)
   nil))

(defn full-doc-text
  "fp's text last shown by show-full-doc!, whole -- for rows, same
  contract as quick-doc-text above."
  [fp]
  @(:text* fp))

(defn full-doc-examples
  "[{:n :code :run :copy}] for the examples fp last showed (the buttons are
  nil for blank code), [] when it showed the single label. For rows."
  [fp]
  @(:examples* fp))

;; --- rename -----------------------------------------------------------------

(defn make-rename-popover
  "Builds the rename popover: autohide ON, position :bottom -- same caret
  anchor as make-help-popover above, but modal (autohide TRUE),
  the shape rename wants since its entry must receive typing. Its
  child is a vertical box: a GtkEntry (the new name) over a left-aligned
  problem label (class jonquil-doc, same styling as the quick-doc label
  above), hidden while there's nothing to report (an
  empty label still claims a row of vertical space in a GtkBox, which
  looked like dead space under the entry -- gtk_widget_set_visible FALSE
  removes it entirely; show-rename-problem! below shows it again the
  moment there's real text). Connects a SECOND
  \"closed\" handler (jonquil.gtk.popover/make-popover's own autohide
  branch already connects the first, to keep :open? in sync) that gives
  keyboard focus back to view -- every close path (Enter's own hide!,
  GTK's own Escape/click-outside) ends in this same signal. It's
  jonquil.gtk.popover/refocus-view-on-close!, which posts the grab until
  GTK has finished closing; a grab in the handler itself was undone. Returns
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
    (popover/refocus-view-on-close! pp)
    (assoc pp :entry entry :problem-label problem-label)))

(defn show-rename!
  "Prefills rp's entry with symbol and selects all of it (GIR's own doc
  for gtk_editable_select_region: a negative end_pos selects to the end),
  clears any earlier problem text and hides the (now empty) problem label,
  pops the popover up at the caret (popover/show-at-
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
  "Sets rp's problem label to text and shows it (hidden
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
