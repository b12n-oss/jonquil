(ns jonquil.gtk.find-bar
  "The find and replace bar: a horizontal GtkBox above the Definitions
  editor, hidden until opened. Holds a GtkSearchEntry for the query, a
  GtkEntry plus \"Replace\"/\"Replace All\" buttons visible only in :replace
  mode, and a match-count label.

  Every match gets a \"search-match\" GtkTextTag (background from the
  active theme's :find-highlight, and foreground from the
  theme's :text so matched text stays readable; starts
  with theme/default-theme's colours and apply-theme! retints it in
  place). Matches are
  recomputed over the editor text on every query change and
  on every editor buffer change while the bar is open (both wired as GTK
  \"changed\" signals here), but only a query change, open!, next/prev and
  a replace move the editor's selection; the tag is stripped on every
  recompute and on close, using gtk_text_buffer_remove_tag (not remove_all_tags, which would
  also strip the editor's own syntax-highlighting tags wherever they
  overlap a match).

  Return/F3/command+g move to the next match, Shift+Return/Shift+F3/
  command+shift+g to the previous one; gtk_widget_activate
  does not fire GtkSearchEntry's \"activate\" (measured), so all of this goes through
  handle-key!, wired to the bar's own capture-phase key controller (never
  the GTK signal) -- rows call handle-key! directly.

  handle-key! yields (0) while either entry has an IME
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
            [jonquil.syntax :as syntax]
            [jonquil.theme :as theme]))

;; --- keys (values from gdkkeysyms.h 2026-09-24) ---------------------------

(def ^:private KEY-ESCAPE 0xff1b)
(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-F3 0xffc0)
(def ^:private KEY-G 0x067)

(defn- command-chord?
  "A command chord is Control or Meta, duplicated here since it's private
  in jonquil.gtk.editor too (the keymap's :cmd means the same)."
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
  "Selects [s e] in the editor and scrolls it into view, and remembers it
  as the current match (snapshot, below)."
  [fb [s e]]
  (reset! (:current fb) [s e])
  (let [ed (:ed fb) buf (:buf ed)]
    (reset! (:selecting? fb) true)
    (try
      (g/with-iters* buf s e (fn [a b] (g/gtk-text-buffer-select-range buf a b)))
      (finally (reset! (:selecting? fb) false)))
    (g/with-iters* buf s s
      (fn [a _] (g/gtk-text-view-scroll-to-iter (:view ed) a 0.0 0 0.0 0.0)))))

(defn- snapshot
  "The editor's snapshot, with the selection put back on the current match
  when focus collapsed it there. Focusing an entry (the query or the
  replacement) selects its text, which takes the primary selection, and the
  editor's buffer then drops its own: measured 2026-11-02, a GtkTextView's
  [0 3] became [0 0] once a GtkEntry took focus in an active window (with
  gtk-entry-select-on-focus off it stays [0 3]). So a collapsed selection
  at an edge of the match this bar last selected, while it is still a
  match, still means that match. Any selection change the user makes in
  the editor forgets that match first (on-selection-set), so a caret they
  put at its edge stays a caret."
  [fb]
  (let [snap (editor/snapshot (:ed fb))
        {:keys [sel-start sel-end]} snap
        [s e :as cur] @(:current fb)]
    (if (and cur (= sel-start sel-end) (#{s e} sel-start) (some #{cur} @(:matches fb)))
      (assoc snap :sel-start s :sel-end e)
      snap)))

(defn- refresh-matches!
  "Recomputes matches for the current query over the editor text,
  reapplies the search-match tag and updates the :matches atom and the
  count label, never touching the editor's selection or scroll position.
  The label counts the current selection as the current match only when
  it is exactly one. Returns the new matches, or nil while the bar is
  closed (a no-op then).

  This is all the editor buffer's own \"changed\" signal runs.
  recompute-matches! would select the next match after the caret on every
  keystroke, so the NEXT keystroke's commit would delete that selection
  and replace a match the user never chose."
  [fb]
  (when (open? fb)
    (let [ed (:ed fb)
          text (editor/editor-text ed)
          query (or (g/editable-text (:query fb)) "")
          matches (syntax/find-text-matches text query {:case-sensitive? false})
          {:keys [sel-start sel-end]} (editor/snapshot ed)
          current (first (filter (fn [m] (= m [sel-start sel-end])) matches))]
      (clear-tags! fb)
      (apply-tags! fb matches)
      (reset! (:matches fb) matches)
      (update-count-label! fb matches current)
      matches)))

(defn- recompute-matches!
  "refresh-matches!, then selects and scrolls to the nearest match from
  the editor's current caret (wrapping to the first) and labels it as the
  current one. Runs only on a query change and open!, never on a plain
  edit. A no-op while the bar is closed."
  [fb]
  (when-let [matches (seq (refresh-matches! fb))]
    (let [m (commands/next-match (vec matches) (:caret (editor/snapshot (:ed fb))))]
      (select-match! fb m)
      (update-count-label! fb matches m))))

(defn- on-selection-set
  "The editor buffer's \"mark-set\" handler: the insert or selection-bound
  mark moving while the editor is its window's focus widget, other than
  select-match!'s own move, is the user's selection, so the bar forgets its
  current match unless the selection is still exactly it. A collapse from
  focus moving into the bar happens once the entry is the focus widget, so
  it keeps the match (snapshot). gtk_widget_is_focus, not has_focus: the
  latter is false in an inactive window (measured 2026-11-02: is-focus 1,
  has-focus 0), where a caret the user placed was then read as the match."
  [fb mark]
  (let [ed (:ed fb) buf (:buf ed)]
    (when (and @(:current fb)
               (not @(:selecting? fb))
               (or (= mark (g/gtk-text-buffer-get-insert buf))
                   (= mark (g/gtk-text-buffer-get-selection-bound buf)))
               (not (zero? (g/gtk-widget-is-focus (:view ed))))
               (not= @(:current fb) (g/selection-offsets buf)))
      (reset! (:current fb) nil))))

(defn- on-buffer-changed
  "The editor buffer's \"changed\" handler: refresh only, and nothing
  at all while a replace is applying its edits, since replace-one!/
  replace-all! recompute once themselves when the whole edit has landed
  (not once per delete and once per insert)."
  [fb]
  (when-not @(:replacing? fb)
    (refresh-matches! fb)))

(defn- apply-replace!
  "Applies a replace result through editor/apply-op! with the buffer's
  \"changed\" handler held off (see on-buffer-changed), then refreshes
  once. The result's own :sel already selects the next match
  (commands/replace-current), so this only scrolls to it when the new
  selection is exactly a match."
  [fb result]
  (reset! (:replacing? fb) true)
  (try
    (editor/apply-op! (:ed fb) result)
    (finally (reset! (:replacing? fb) false)))
  (let [matches (refresh-matches! fb)
        {:keys [sel-start sel-end]} (editor/snapshot (:ed fb))]
    (when (some #{[sel-start sel-end]} matches)
      (select-match! fb [sel-start sel-end]))))

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
  editor's selection when it's exactly one line, recomputes matches (which
  selects/scrolls to the nearest one IN THE EDITOR), then selects the WHOLE
  query text (so typing immediately overtypes
  it, whether it was just prefilled or is leftover from a previous open!)
  and focuses the query entry.

  recompute-matches! must run BEFORE the query's own
  select_region/grab_focus, not after. Measured: once this find bar has been through one earlier open! cycle,
  recompute-matches!'s own select-match! -- which calls
  gtk_text_buffer_select_range on the EDITOR's buffer to highlight the
  nearest match -- silently clears whatever selection the QUERY entry was
  just given a moment earlier (GTK's shared primary-selection ownership:
  selecting text on one widget revokes the visible/logical selection on
  whichever other widget held it). The reorder makes the query's own
  select_region the LAST thing to touch selection at all, so nothing after
  it can revoke it -- independent of grab_focus order or window/OS focus
  timing, which measurement showed is not the deciding factor here.

  The preedit flag starts false. If focus left the bar mid-composition and
  the input method never reported the composition's end, the flag stays
  set while the bar stays open, and every key but typing yields (Escape,
  F3, Return; nav-ready? is false too, so F3 from the editor does
  nothing). Cmd/Ctrl+F or H, which call this, are the way out."
  [fb mode]
  (reset! (:preedit-active? fb) false)
  (g/gtk-widget-set-visible (:box fb) 1)
  (let [replace? (= mode :replace)]
    (doseq [w (:replace-widgets fb)] (g/gtk-widget-set-visible w (if replace? 1 0))))
  (when-let [sel (single-line-selection (editor/snapshot (:ed fb)))]
    (g/gtk-editable-set-text (:query fb) sel))
  (recompute-matches! fb)
  (g/gtk-editable-select-region (:query fb) 0 (count (or (g/editable-text (:query fb)) "")))
  (g/gtk-widget-grab-focus (:query fb))
  nil)

(defn close!
  "Hides the bar, strips every search-match tag, and returns focus to the
  editor."
  [fb]
  (clear-tags! fb)
  (reset! (:matches fb) [])
  (reset! (:current fb) nil)
  (g/gtk-widget-set-visible (:box fb) 0)
  (editor/focus! (:ed fb))
  nil)

(defn apply-theme!
  "Sets the search-match tag's background to theme t's :find-highlight and
  its foreground to t's :text (a comment or a
  keyword inside a match otherwise kept its own syntax colour on top of
  the highlight, which measured unreadable in every theme, 1.82-2.68).
  Only ever touches the tag's colour -- never the editor's matches, its
  buffer text or its selection."
  [fb t]
  (g/set-string-property! (:tag fb) "background" (:find-highlight t))
  (g/set-string-property! (:tag fb) "foreground" (:text t))
  nil)

;; --- moving between matches -----------------------------------------------

(defn find-next!
  "Selects the match after the current selection's end, wrapping to the
  first."
  [fb]
  (let [matches @(:matches fb)
        {:keys [sel-end]} (snapshot fb)
        m (commands/next-match matches sel-end)]
    (when m
      (select-match! fb m)
      (update-count-label! fb matches m))
    nil))

(defn find-prev!
  "Selects the match before the current selection's start, wrapping to the
  last."
  [fb]
  (let [matches @(:matches fb)
        {:keys [sel-start]} (snapshot fb)
        m (commands/prev-match matches sel-start)]
    (when m
      (select-match! fb m)
      (update-count-label! fb matches m))
    nil))

;; --- replacing --------------------------------------------------------

(defn replace-one!
  "Replaces the current selection when it is exactly one of the current
  matches (commands/replace-current), applied through editor/apply-op! as
  one undo step, which also selects the next match; matches are then
  recomputed over the new text once (apply-replace!). When the selection
  isn't a match, finds the next match instead."
  [fb]
  (let [matches @(:matches fb)
        replacement (or (g/editable-text (:replacement fb)) "")
        result (commands/replace-current (snapshot fb) matches replacement)]
    (if result
      (apply-replace! fb result)
      (find-next! fb))
    nil))

(defn replace-all!
  "Replaces every current match (commands/replace-all), applied through
  editor/apply-op! as one undo step with one refresh at the end
  (apply-replace!), then shows \"Replaced n\" (overwriting whatever that
  recompute set the label to)."
  [fb]
  (let [matches @(:matches fb)
        replacement (or (g/editable-text (:replacement fb)) "")
        result (commands/replace-all matches replacement)]
    (when result
      (apply-replace! fb result)
      (g/gtk-label-set-text (:count-label fb) (str "Replaced " (:count result))))
    nil))

;; --- keys inside the bar ---------------------------------------------------

(defn- focused-widget
  "The widget gtk_root_get_focus reports for the bar's own root -- reached
  via gtk_widget_get_root on the box itself, since make-find-bar's
  single-arg interface never receives the window handle."
  [fb]
  (g/gtk-root-get-focus (g/gtk-widget-get-root (:box fb))))

(defn- replacement-focused?
  "True when keyboard focus is in the replacement entry.
  gtk_root_get_focus returns the entry's internal GtkText delegate, never
  the GtkEntry itself (measured), so this asks
  gtk_widget_is_ancestor(focus, replacement), the same test
  jonquil.gtk.window.keys's find-bar-focused? uses against the whole bar."
  [fb]
  (let [f (focused-widget fb)]
    (and (not (ffi/null? f))
         (or (= f (:replacement fb))
             (not (zero? (g/gtk-widget-is-ancestor f (:replacement fb))))))))

(defn- button-focused?
  "True when keyboard focus is on the Replace or Replace All button, where
  Return belongs to GTK's own activation of that button."
  [fb]
  (let [f (focused-widget fb)]
    (and (not (ffi/null? f))
         (or (= f (:replace-btn fb)) (= f (:replace-all-btn fb))))))

(defn nav-ready?
  "True when find-next/previous can act: the bar is open and neither entry
  has an IME preedit composing. The window's keymap asks this before F3 and
  command+g; handle-nav-key! below uses the same guard."
  [fb]
  (and (not @(:preedit-active? fb)) (open? fb)))

(defn handle-nav-key!
  "F3/command+g (next match) and Shift+F3/command+shift+g (previous) while
  the bar is open: 1 when handled, 0 otherwise, and always 0 while either
  entry has an IME preedit active. handle-key! (below) delegates to it
  for keys inside the bar. With focus in the editor or the console the
  window's keymap does the same work, through nav-ready?,
  find-next! and find-prev!."
  [fb keyval0 state]
  (let [keyval (g/gdk-keyval-to-lower keyval0)]
    (cond
      (not (nav-ready? fb)) 0
      (and (= keyval KEY-F3) (shift-held? state)) (do (find-prev! fb) 1)
      (= keyval KEY-F3) (do (find-next! fb) 1)
      (and (command-chord? state) (shift-held? state) (= keyval KEY-G)) (do (find-prev! fb) 1)
      (and (command-chord? state) (= keyval KEY-G)) (do (find-next! fb) 1)
      :else 0)))

(defn handle-key!
  "Keys inside the bar, wired to its own capture-phase key controller: 1
  when handled, 0 otherwise -- always 0 while the bar is closed, and
  always 0 while either entry has an IME preedit composition active, so a composing Hangul/CJK syllable's own Return/Escape reaches
  the entry's IM context instead of being reinterpreted as find-next!/
  close!. Escape closes the bar. Return on a focused Replace or Replace
  All button is left to GTK, which presses that button; Return in the
  replacement entry replaces the current match; Return elsewhere in the
  bar, F3 and command+g go to the next match; Shift+Return, Shift+F3 and
  command+shift+g go to the previous one."
  [fb keyval0 state]
  (let [keyval (g/gdk-keyval-to-lower keyval0)]
    (cond
      @(:preedit-active? fb) 0
      (not (open? fb)) 0
      :else
      (cond
        (= keyval KEY-ESCAPE) (do (close! fb) 1)

        (and (= keyval KEY-RETURN) (button-focused? fb)) 0

        (and (= keyval KEY-RETURN) (replacement-focused? fb))
        (do (replace-one! fb) 1)

        (and (= keyval KEY-RETURN) (shift-held? state)) (do (find-prev! fb) 1)
        (= keyval KEY-RETURN) (do (find-next! fb) 1)

        :else (handle-nav-key! fb keyval0 state)))))

;; --- construction ----------------------------------------------------------

(defn set-error-fn!
  "Registers f (fn [msg] -> nil), the window's error reporter, to run
  whenever one of the bar's own handlers throws. A single slot, the same
  late-registration shape as jonquil.gtk.editor/set-error-fn!."
  [fb f]
  (reset! (:on-error-fn fb) f)
  nil)

(defn- contained
  "Runs thunk for the bar fb's handler named :where. A throw goes to the
  bar's error reporter as \"; <where>: <message>\" (or *err* before one
  is registered) and the handler answers :fallback, so GTK carries on."
  [{:keys [fb where fallback]} thunk]
  (try (thunk)
       (catch :default e
         (let [msg (str "; " where ": " (ex-message e))]
           (if-let [f @(:on-error-fn fb)]
             (try (f msg) (catch :default _ nil))
             (binding [*out* *err*] (println msg))))
         fallback)))

(defn- style-match-tag!
  "Colors the search-match tag from the default theme and adds it to the buffer's tag table."
  [tag table]
  (let [default-t (theme/theme theme/default-theme)]
    (g/set-string-property! tag "background" (:find-highlight default-t))
    (g/set-string-property! tag "foreground" (:text default-t)))
  (g/gtk-text-tag-table-add table tag)
  ;; Measured, not assumed: the editor's own syntax
  ;; tags are added to this SAME tag table before make-find-bar ever
  ;; runs (activate! builds ed, then fb), and GTK's priority is add
  ;; order, so search-match already outranks them. theme-keeps-find-
  ;; highlight's own row reads both tags' gtk_text_tag_get_priority and
  ;; measured search-match at 18 against :special-form's 6 -- search-
  ;; match wins already, so no explicit gtk_text_tag_set_priority call
  ;; is needed (it stays bound in ffi.clj in case a future tag order
  ;; change ever needs it).
  )

(defn- pack-bar!
  "Packs the bar's widgets into its box and hides the box and the replace widgets until the bar opens."
  [{:keys [box query replacement replace-btn replace-all-btn count-label] :as fb}]
  (g/gtk-box-append box query)
  (g/gtk-box-append box replacement)
  (g/gtk-box-append box replace-btn)
  (g/gtk-box-append box replace-all-btn)
  (g/gtk-box-append box count-label)
  (g/gtk-widget-set-visible box 0)
  (doseq [w (:replace-widgets fb)] (g/gtk-widget-set-visible w 0)))

(defn- connect-edits!
  "Connects the query's \"changed\" signal and the buffer's \"changed\" and \"mark-set\" signals to recompute the matches."
  [{:keys [query ed] :as fb}]
  (g/g-signal-connect-data query "changed"
                           (callable (fn [_ _] (contained {:fb fb :where "find" :fallback nil} #(recompute-matches! fb))) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/g-signal-connect-data (:buf ed) "changed"
                           (callable (fn [_ _] (contained {:fb fb :where "find" :fallback nil} #(on-buffer-changed fb))) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/g-signal-connect-data (:buf ed) "mark-set"
                           (callable (fn [_buf _iter mark _data]
                                       (contained {:fb fb :where "find" :fallback nil} #(on-selection-set fb mark)))
                                     [:pointer :pointer :pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-replace-btns!
  "Connects the Replace and Replace All buttons' \"clicked\" signals to their actions."
  [{:keys [replace-btn replace-all-btn] :as fb}]
  (g/g-signal-connect-data replace-btn "clicked"
                           (callable (fn [_ _] (contained {:fb fb :where "replace" :fallback nil} #(replace-one! fb))) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/g-signal-connect-data replace-all-btn "clicked"
                           (callable (fn [_ _] (contained {:fb fb :where "replace all" :fallback nil} #(replace-all! fb))) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-preedit-and-keys!
  "Connects both entries' preedit tracking and the capture-phase key controller, then adds the controller to the box."
  [{:keys [query replacement box] :as fb} keyctl]
  ;; Both entries delegate their GtkEditable
  ;; implementation to an internal GtkText (gtk_editable_get_delegate),
  ;; and it's THAT delegate, not the entry itself, that carries the real
  ;; "preedit-changed" signal -- verified empirically, see the binding's
  ;; own citation in ffi.clj.
  (doseq [entry [query replacement]]
    (g/g-signal-connect-data
     (g/gtk-editable-get-delegate entry) "preedit-changed"
     (callable (fn [_ preedit _]
                 (contained {:fb fb :where "find" :fallback nil} #(reset! (:preedit-active? fb) (not (empty? preedit)))))
               [:pointer :string :pointer] :void)
     ffi/null ffi/null g/CONNECT-DEFAULT))
  (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
  (g/g-signal-connect-data keyctl "key-pressed"
                           (callable (fn [_ctl keyval _keycode state _data]
                                       (contained {:fb fb :where "find" :fallback 0} #(handle-key! fb keyval state)))
                                     [:pointer :uint :uint :uint :pointer] :int)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-widget-add-controller box keyctl))

(defn make-find-bar
  "Builds the find-and-replace bar over ed (a jonquil.gtk.editor map).
  Returns {:box :query :replacement :replace-btn :replace-all-btn
  :count-label :ed :tag :matches :preedit-active? :on-error-fn}; :box is
  hidden until open! is called. Every handler it connects runs through
  contained, so a throw is reported (set-error-fn!) instead of escaping."
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
            ;; the match this bar last selected (select-match!)
            :current (atom nil)
            :selecting? (atom false)
            :preedit-active? (atom false)
            :replacing? (atom false)
            :on-error-fn (atom nil)}]
    (style-match-tag! tag table)
    (pack-bar! fb)
    (connect-edits! fb)
    (connect-replace-btns! fb)
    (connect-preedit-and-keys! fb keyctl)
    fb))
