(ns jonquil.gtk.find-bar
  "The find and replace bar: a horizontal GtkBox above the Definitions
  editor, hidden until opened. Holds a GtkSearchEntry for the query, a
  GtkEntry plus \"Replace\"/\"Replace All\" buttons visible only in :replace
  mode, and a match-count label.

  Every match gets a \"search-match\" GtkTextTag (background #fff2a8, probe
  2). Matches are recomputed over the editor text on every query change and
  on every editor buffer change while the bar is open (both wired as GTK
  \"changed\" signals here); the tag is stripped on every recompute and on
  close, using gtk_text_buffer_remove_tag (not remove_all_tags, which would
  also strip the editor's own syntax-highlighting tags wherever they
  overlap a match).

  Return/F3/command+g move to the next match, Shift+Return/Shift+F3/
  command+shift+g to the previous one; probe 1 found gtk_widget_activate
  does not fire GtkSearchEntry's \"activate\", so all of this goes through
  handle-key!, wired to the bar's own capture-phase key controller (never
  the GTK signal) -- rows call handle-key! directly.

  P3.T4 fix round 1: handle-key! yields (0) while either entry has an IME
  preedit composition active, the same global constraint editor.clj and
  console_view.clj already follow. Both the query and replacement entries
  delegate their GtkEditable implementation to an internal GtkText
  (gtk_editable_get_delegate), which is where GTK's own \"preedit-changed\"
  signal actually lives -- neither GtkSearchEntry nor GtkEntry re-exposes
  it directly."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.commands :as commands]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.syntax :as syntax]))

;; --- keys (gtk-keys.md, values from gdkkeysyms.h 2026-09-24) --------------

(def ^:private KEY-ESCAPE 0xff1b)
(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-F3 0xffc0)
(def ^:private KEY-G 0x067)

(def ^:private SEARCH-MATCH-BG "#fff2a8")

(defn- command-chord?
  "A command chord is Control or Meta (gtk-keys.md), duplicated here since
  it's private in jonquil.gtk.window/jonquil.gtk.editor too."
  [state]
  (not (zero? (bit-and state (bit-or g/CONTROL-MASK g/META-MASK)))))

(defn- shift-held? [state]
  (not (zero? (bit-and state g/SHIFT-MASK))))

;; --- matches / highlighting -------------------------------------------

(defn- open? [fb]
  (not (zero? (g/gtk-widget-get-visible (:box fb)))))

(defn- clear-tags!
  "Strips every search-match tag from the whole buffer -- gtk_text_buffer_
  remove_tag, never remove_all_tags (see the ns docstring)."
  [fb]
  (let [ed (:ed fb) buf (:buf ed) tag (:tag fb)
        len (count (editor/editor-text ed))]
    (g/with-iters* buf 0 len (fn [a b] (g/gtk-text-buffer-remove-tag buf tag a b)))))

(defn- apply-tags! [fb matches]
  (let [buf (:buf (:ed fb)) tag (:tag fb)]
    (doseq [[s e] matches]
      (g/with-iters* buf s e (fn [a b] (g/gtk-text-buffer-apply-tag buf tag a b))))))

(defn- match-index [matches m]
  (first (keep-indexed (fn [i x] (when (= x m) i)) matches)))

(defn- update-count-label! [fb matches current]
  (g/gtk-label-set-text
   (:count-label fb)
   (cond
     (empty? matches) "No matches"
     current (str (inc (match-index matches current)) " of " (count matches))
     :else (str "0 of " (count matches)))))

(defn- select-match!
  "Selects [s e] in the editor and scrolls it into view (probe 2)."
  [fb [s e]]
  (let [ed (:ed fb) buf (:buf ed)]
    (g/with-iters* buf s e (fn [a b] (g/gtk-text-buffer-select-range buf a b)))
    (g/with-iters* buf s s
      (fn [a _] (g/gtk-text-view-scroll-to-iter (:view ed) a 0.0 0 0.0 0.0)))))

(defn- recompute-matches!
  "Recomputes matches for the current query over the editor text, reapplies
  the search-match tag, selects and scrolls to the nearest match from the
  editor's current caret (wrapping to the first), and updates the count
  label. A no-op while the bar is closed."
  [fb]
  (when (open? fb)
    (let [ed (:ed fb)
          text (editor/editor-text ed)
          query (or (g/editable-text (:query fb)) "")
          matches (syntax/find-text-matches text query {:case-sensitive? false})
          caret (:caret (editor/snapshot ed))]
      (clear-tags! fb)
      (apply-tags! fb matches)
      (reset! (:matches fb) matches)
      (if (seq matches)
        (let [m (commands/next-match matches caret)]
          (select-match! fb m)
          (update-count-label! fb matches m))
        (update-count-label! fb matches nil)))))

;; --- open / close --------------------------------------------------------

(defn- single-line-selection
  "The editor's selected text, or nil when there's no selection or it spans
  more than one line."
  [{:keys [text sel-start sel-end]}]
  (when (and sel-start sel-end (< sel-start sel-end))
    (let [s (subs text sel-start sel-end)]
      (when-not (str/includes? s "\n") s))))

(defn open!
  "Opens the bar in :find or :replace mode: shows the replacement entry and
  its two buttons only in :replace mode, prefills the query with the
  editor's selection when it's exactly one line, selects the WHOLE query
  text (fix round 1, per the brief -- so typing immediately overtypes it,
  whether it was just prefilled or is leftover from a previous open!),
  focuses the query entry, and recomputes matches (which also
  selects/scrolls to the nearest one)."
  [fb mode]
  (g/gtk-widget-set-visible (:box fb) 1)
  (let [replace? (= mode :replace)]
    (doseq [w (:replace-widgets fb)] (g/gtk-widget-set-visible w (if replace? 1 0))))
  (when-let [sel (single-line-selection (editor/snapshot (:ed fb)))]
    (g/gtk-editable-set-text (:query fb) sel))
  (g/gtk-editable-select-region (:query fb) 0 (count (or (g/editable-text (:query fb)) "")))
  (g/gtk-widget-grab-focus (:query fb))
  (recompute-matches! fb)
  nil)

(defn close!
  "Hides the bar, strips every search-match tag, and returns focus to the
  editor."
  [fb]
  (clear-tags! fb)
  (reset! (:matches fb) [])
  (g/gtk-widget-set-visible (:box fb) 0)
  (editor/focus! (:ed fb))
  nil)

;; --- moving between matches -----------------------------------------------

(defn find-next!
  "Selects the match after the current selection's end, wrapping to the
  first."
  [fb]
  (let [ed (:ed fb)
        matches @(:matches fb)
        {:keys [sel-end]} (editor/snapshot ed)
        m (commands/next-match matches sel-end)]
    (when m
      (select-match! fb m)
      (update-count-label! fb matches m))
    nil))

(defn find-prev!
  "Selects the match before the current selection's start, wrapping to the
  last."
  [fb]
  (let [ed (:ed fb)
        matches @(:matches fb)
        {:keys [sel-start]} (editor/snapshot ed)
        m (commands/prev-match matches sel-start)]
    (when m
      (select-match! fb m)
      (update-count-label! fb matches m))
    nil))

;; --- replacing --------------------------------------------------------

(defn replace-one!
  "Replaces the current selection when it is exactly one of the current
  matches (commands/replace-current), applied through editor/apply-op! as
  one undo step -- the editor buffer's own \"changed\" signal then
  recomputes matches over the new text. When the selection isn't a match,
  finds the next match instead, per the brief."
  [fb]
  (let [ed (:ed fb)
        matches @(:matches fb)
        replacement (or (g/editable-text (:replacement fb)) "")
        result (commands/replace-current (editor/snapshot ed) matches replacement)]
    (if result
      (editor/apply-op! ed result)
      (find-next! fb))
    nil))

(defn replace-all!
  "Replaces every current match (commands/replace-all), applied through
  editor/apply-op! as one undo step, then shows \"Replaced n\" (overwriting
  whatever the post-edit recompute -- via the same \"changed\" signal --
  already set the label to)."
  [fb]
  (let [matches @(:matches fb)
        replacement (or (g/editable-text (:replacement fb)) "")
        result (commands/replace-all matches replacement)]
    (when result
      (editor/apply-op! (:ed fb) result)
      (g/gtk-label-set-text (:count-label fb) (str "Replaced " (:count result))))
    nil))

;; --- keys inside the bar ---------------------------------------------------

(defn- focused-widget
  "The widget gtk_root_get_focus reports for the bar's own root -- reached
  via gtk_widget_get_root on the box itself, since make-find-bar's
  single-arg interface never receives the window handle."
  [fb]
  (g/gtk-root-get-focus (g/gtk-widget-get-root (:box fb))))

(defn handle-key!
  "Keys inside the bar, wired to its own capture-phase key controller: 1
  when handled, 0 otherwise -- always 0 while the bar is closed, and
  always 0 while either entry has an IME preedit composition active (fix
  round 1), so a composing Hangul/CJK syllable's own Return/Escape reaches
  the entry's IM context instead of being reinterpreted as find-next!/
  close!. Escape closes the bar. Return in the replacement entry replaces
  the current match; Return elsewhere in the bar, F3 and command+g go to
  the next match; Shift+Return, Shift+F3 and command+shift+g go to the
  previous one."
  [fb keyval0 state]
  (let [keyval (g/gdk-keyval-to-lower keyval0)]
    (cond
      @(:preedit-active? fb) 0
      (not (open? fb)) 0
      :else
      (cond
        (= keyval KEY-ESCAPE) (do (close! fb) 1)

        (and (= keyval KEY-RETURN) (= (focused-widget fb) (:replacement fb)))
        (do (replace-one! fb) 1)

        (and (= keyval KEY-RETURN) (shift-held? state)) (do (find-prev! fb) 1)
        (= keyval KEY-RETURN) (do (find-next! fb) 1)

        (and (= keyval KEY-F3) (shift-held? state)) (do (find-prev! fb) 1)
        (= keyval KEY-F3) (do (find-next! fb) 1)

        (and (command-chord? state) (shift-held? state) (= keyval KEY-G)) (do (find-prev! fb) 1)
        (and (command-chord? state) (= keyval KEY-G)) (do (find-next! fb) 1)

        :else 0))))

;; --- construction ----------------------------------------------------------

(defn make-find-bar
  "Builds the find-and-replace bar over ed (a jonquil.gtk.editor map).
  Returns {:box :query :replacement :replace-btn :replace-all-btn
  :count-label :ed :tag :matches :preedit-active?}; :box is hidden until
  open! is called."
  [ed]
  (let [box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        query (g/gtk-search-entry-new)
        replacement (g/gtk-entry-new)
        replace-btn (g/gtk-button-new-with-label "Replace")
        replace-all-btn (g/gtk-button-new-with-label "Replace All")
        count-label (g/gtk-label-new "")
        table (g/gtk-text-buffer-get-tag-table (:buf ed))
        tag (g/gtk-text-tag-new "search-match")
        keyctl (g/gtk-event-controller-key-new)
        fb {:box box :query query :replacement replacement
            :replace-btn replace-btn :replace-all-btn replace-all-btn
            :replace-widgets [replacement replace-btn replace-all-btn]
            :count-label count-label
            :ed ed :tag tag :matches (atom [])
            :preedit-active? (atom false)}]
    (g/set-string-property! tag "background" SEARCH-MATCH-BG)
    (g/gtk-text-tag-table-add table tag)
    (g/gtk-box-append box query)
    (g/gtk-box-append box replacement)
    (g/gtk-box-append box replace-btn)
    (g/gtk-box-append box replace-all-btn)
    (g/gtk-box-append box count-label)
    (g/gtk-widget-set-visible box 0)
    (doseq [w (:replace-widgets fb)] (g/gtk-widget-set-visible w 0))
    (g/g-signal-connect-data query "changed"
                             (callable (fn [_ _] (recompute-matches! fb)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data (:buf ed) "changed"
                             (callable (fn [_ _] (recompute-matches! fb)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data replace-btn "clicked"
                             (callable (fn [_ _] (replace-one! fb)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data replace-all-btn "clicked"
                             (callable (fn [_ _] (replace-all! fb)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; P3.T4 fix round 1: both entries delegate their GtkEditable
    ;; implementation to an internal GtkText (gtk_editable_get_delegate),
    ;; and it's THAT delegate, not the entry itself, that carries the real
    ;; "preedit-changed" signal -- verified empirically, see the binding's
    ;; own citation in ffi.clj.
    (doseq [entry [query replacement]]
      (g/g-signal-connect-data
       (g/gtk-editable-get-delegate entry) "preedit-changed"
       (callable (fn [_ preedit _] (reset! (:preedit-active? fb) (not (empty? preedit))))
                 [:pointer :string :pointer] :void)
       ffi/null ffi/null g/CONNECT-DEFAULT))
    (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
    (g/g-signal-connect-data keyctl "key-pressed"
                             (callable (fn [_ctl keyval _keycode state _data] (handle-key! fb keyval state))
                                       [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller box keyctl)
    fb))
