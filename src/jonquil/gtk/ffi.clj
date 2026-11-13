(ns jonquil.gtk.ffi
  "Raw GTK4/GLib/GObject/pango bindings for Jonquil, lifted from a sibling
  Jolt project's GTK FFI. The layer keeps that sibling's idioms: pointers are jolt
  numbers, signal handlers are :collect-safe foreign-callables because GTK
  invokes them from inside the :blocking g_application_run loop.

  ABI rule: no variadic C functions (the FFI layer is fixed-arity
  only). That excludes
  g_object_set and gtk_text_buffer_create_tag: properties go through the
  fixed-arity GValue path (g_value_init/set_string +
  g_object_set_property), fonts/colors through a CSS provider.

  GtkTextIter is an opaque struct the CALLER allocates and GTK fills by
  pointer (~80 bytes on 64-bit; we allocate 128 for headroom), no
  by-value passing anywhere, so no C shim."
  (:require [jolt.ffi :as ffi]))

;; --- constants ---------------------------------------------------------------
(def APPLICATION-DEFAULT-FLAGS 0)
(def CONNECT-DEFAULT 0)
(def GTK-PHASE-CAPTURE 1)          ; GtkPropagationPhase
;; gtkenums.h:1036-1039 (GTK 4.22.4): the rows view's own key controller
;; runs in the BUBBLE phase (unlike every window/editor/console key
;; controller, all CAPTURE), so it sees keys only after nothing upstream
;; claimed them.
(def GTK-PHASE-BUBBLE 2)           ; GtkPropagationPhase
(def G-TYPE-STRING (* 16 4))       ; fundamental GType: 16 << 2
(def STYLE-PROVIDER-PRIORITY-APPLICATION 600)

;; GdkModifierType (GTK4)
(def SHIFT-MASK (bit-shift-left 1 0))
(def CONTROL-MASK (bit-shift-left 1 2))
(def ALT-MASK (bit-shift-left 1 3))
(def SUPER-MASK (bit-shift-left 1 26))
(def META-MASK (bit-shift-left 1 28))

(def ITER-BYTES 128)               ; GtkTextIter allocation (see ns docstring)
(def GVALUE-BYTES 24)              ; GValue: GType + 2x8 data, zero-initialized

;; GtkOrientation
(def ORIENTATION-HORIZONTAL 0)     ; a horizontal separator (used for an hsplit's divider)
(def ORIENTATION-VERTICAL 1)       ; a vertical separator (used for a vsplit's divider)

;; --- application / main loop -------------------------------------------------
(ffi/defcfn gtk-application-new "gtk_application_new" [:string :uint] :pointer)
(ffi/defcfn g-set-prgname "g_set_prgname" [:string] :void)
(ffi/defcfn g-get-prgname "g_get_prgname" [] :pointer)
(ffi/defcfn g-application-run "g_application_run" [:pointer :int :pointer] :int :blocking)
(ffi/defcfn g-application-quit "g_application_quit" [:pointer] :void)
(ffi/defcfn g-signal-connect-data "g_signal_connect_data"
  [:pointer :string :pointer :pointer :pointer :int] :ulong)
(ffi/defcfn g-idle-add "g_idle_add" [:pointer :pointer] :uint)
(ffi/defcfn g-timeout-add "g_timeout_add" [:uint :pointer :pointer] :uint)

;; --- window / layout ---------------------------------------------------------
(ffi/defcfn gtk-application-window-new "gtk_application_window_new" [:pointer] :pointer)
(ffi/defcfn gtk-window-set-title "gtk_window_set_title" [:pointer :string] :void)
(ffi/defcfn gtk-window-set-default-size "gtk_window_set_default_size" [:pointer :int :int] :void)
(ffi/defcfn gtk-window-set-child "gtk_window_set_child" [:pointer :pointer] :void)
(ffi/defcfn gtk-window-present "gtk_window_present" [:pointer] :void)
(ffi/defcfn gtk-box-new "gtk_box_new" [:int :int] :pointer)   ; orientation, spacing
(ffi/defcfn gtk-box-append "gtk_box_append" [:pointer :pointer] :void)
(ffi/defcfn gtk-widget-set-vexpand "gtk_widget_set_vexpand" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-size-request "gtk_widget_set_size_request" [:pointer :int :int] :void)
(ffi/defcfn gtk-scrolled-window-new "gtk_scrolled_window_new" [] :pointer)
(ffi/defcfn gtk-scrolled-window-set-child "gtk_scrolled_window_set_child" [:pointer :pointer] :void)
;; gtkbox.h gtk_box_remove (GtkBox*, GtkWidget* child), gtkwidget.h
;; gtk_widget_get_first_child (GtkWidget*) -> GtkWidget* (NULL for none) and
;; gtkscrolledwindow.h gtk_scrolled_window_get_vadjustment (GtkScrolledWindow*)
;; -> GtkAdjustment*. All fixed arity; the docs panel empties its examples
;; box with the first two and scrolls a new page to its top with the third.
(ffi/defcfn gtk-box-remove "gtk_box_remove" [:pointer :pointer] :void)
(ffi/defcfn gtk-widget-get-first-child "gtk_widget_get_first_child" [:pointer] :pointer)
(ffi/defcfn gtk-scrolled-window-get-vadjustment "gtk_scrolled_window_get_vadjustment" [:pointer] :pointer)
;; gtkscrolledwindow.h (GTK 4.22.4, installed headers): set_policy:111-113,
;; set_max_content_height:161-162, set_propagate_natural_height:173-174. All
;; fixed arity.
(ffi/defcfn gtk-scrolled-window-set-policy "gtk_scrolled_window_set_policy" [:pointer :int :int] :void)
(ffi/defcfn gtk-scrolled-window-set-max-content-height "gtk_scrolled_window_set_max_content_height"
  [:pointer :int] :void)
(ffi/defcfn gtk-scrolled-window-set-propagate-natural-height "gtk_scrolled_window_set_propagate_natural_height"
  [:pointer :int] :void)
(ffi/defcfn gtk-label-set-max-width-chars "gtk_label_set_max_width_chars" [:pointer :int] :void)
(ffi/defcfn gtk-scrolled-window-set-propagate-natural-width "gtk_scrolled_window_set_propagate_natural_width"
  [:pointer :int] :void)
;; GtkPolicyType: gtkscrolledwindow.h:85-88 (ALWAYS=0, AUTOMATIC=1, NEVER=2,
;; EXTERNAL=3).
(def POLICY-NEVER 2)
(def POLICY-AUTOMATIC 1)
(ffi/defcfn gtk-fixed-new "gtk_fixed_new" [] :pointer)
(ffi/defcfn gtk-fixed-put "gtk_fixed_put" [:pointer :pointer :double :double] :void)     ; fixed, widget, x, y
(ffi/defcfn gtk-fixed-move "gtk_fixed_move" [:pointer :pointer :double :double] :void)   ; reposition an existing child
(ffi/defcfn gtk-fixed-remove "gtk_fixed_remove" [:pointer :pointer] :void)               ; fixed, widget
(ffi/defcfn gtk-separator-new "gtk_separator_new" [:int] :pointer)                       ; GtkOrientation

;; --- labels (minibuffer / status line) ---------------------------------------
(ffi/defcfn gtk-label-new "gtk_label_new" [:string] :pointer)
(ffi/defcfn gtk-label-set-selectable "gtk_label_set_selectable" [:pointer :int] :void)
(ffi/defcfn gtk-label-set-text "gtk_label_set_text" [:pointer :string] :void)
(ffi/defcfn gtk-label-set-xalign "gtk_label_set_xalign" [:pointer :float] :void)
;; gtklabel.h (GTK 4.22.4, installed headers):
;; set_wrap:115-116, set_wrap_mode:120-121. Both fixed arity.
(ffi/defcfn gtk-label-set-wrap "gtk_label_set_wrap" [:pointer :int] :void)
(ffi/defcfn gtk-label-set-wrap-mode "gtk_label_set_wrap_mode" [:pointer :int] :void)
;; PangoWrapMode: pango/pango-layout.h:91-93 (WORD=0, CHAR=1, WORD_CHAR=2 --
;; falls back to character wrap only where a single word doesn't fit).
(def PANGO-WRAP-WORD-CHAR 2)
;; gtkwidget.h (GTK 4.22.4, installed headers): set_halign:581-582,
;; set_valign:586-587. Both fixed arity.
(ffi/defcfn gtk-widget-set-halign "gtk_widget_set_halign" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-valign "gtk_widget_set_valign" [:pointer :int] :void)
;; GtkAlign: gtkenums.h (FILL=0, START=1, END=2, CENTER=3).
(def ALIGN-START 1)

;; gtklabel.h:95 -- void gtk_label_set_ellipsize (GtkLabel*,
;; PangoEllipsizeMode). Fixed arity. The inspector pane's header label
;; ellipsizes a long :path at its own START, per PangoEllipsizeMode
;; (pango/pango-layout.h:99-116: NONE=0, START=1, MIDDLE=2, END=3).
(ffi/defcfn gtk-label-set-ellipsize "gtk_label_set_ellipsize" [:pointer :int] :void)
(def PANGO-ELLIPSIZE-START 1)
(def PANGO-ELLIPSIZE-END 3)

;; --- text view / buffer ------------------------------------------------------
(ffi/defcfn gtk-text-view-new "gtk_text_view_new" [] :pointer)
(ffi/defcfn gtk-text-view-get-buffer "gtk_text_view_get_buffer" [:pointer] :pointer)
(ffi/defcfn gtk-text-view-set-editable "gtk_text_view_set_editable" [:pointer :int] :void)
(ffi/defcfn gtk-text-view-set-cursor-visible "gtk_text_view_set_cursor_visible" [:pointer :int] :void)
(ffi/defcfn gtk-text-view-set-monospace "gtk_text_view_set_monospace" [:pointer :int] :void)
(ffi/defcfn gtk-text-view-scroll-mark-onscreen "gtk_text_view_scroll_mark_onscreen" [:pointer :pointer] :void)

;; Text GTK is handed while it can still call back into Jolt goes over as a C
;; string in foreign memory, not as a :string argument. A :string argument is
;; a byte buffer the collector may move, and these functions emit a signal
;; (insert-text, delete-text, changed) before they have copied the text: the
;; editor's own insert-text handler runs in between. A collection during that
;; handler moved the buffer and GTK read freed memory, which showed as
;; "invalid memory reference" or as GTK's assertion that the text wasn't
;; valid UTF-8, now and then, mostly while another thread was allocating
;; (measured 2026-11-14 with a forced collection in an insert-text handler:
;; a crash within 300 inserts as :string, none as a C string). The four
;; wrappers below are the calls that take text and emit before copying it.
(ffi/defcfn ^:private gtk-text-buffer-set-text* "gtk_text_buffer_set_text" [:pointer :pointer :int] :void)

(defn gtk-text-buffer-set-text
  "gtk_text_buffer_set_text with text as a C string (see above). len is in
  bytes, or -1 for the whole of text."
  [buf text len]
  (ffi/with-c-string [p text] (gtk-text-buffer-set-text* buf p len)))

(ffi/defcfn gtk-text-buffer-get-start-iter "gtk_text_buffer_get_start_iter" [:pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-get-end-iter "gtk_text_buffer_get_end_iter" [:pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-get-iter-at-offset "gtk_text_buffer_get_iter_at_offset" [:pointer :pointer :int] :void)
(ffi/defcfn gtk-text-buffer-place-cursor "gtk_text_buffer_place_cursor" [:pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-get-insert "gtk_text_buffer_get_insert" [:pointer] :pointer)
(ffi/defcfn gtk-text-buffer-get-selection-bound "gtk_text_buffer_get_selection_bound" [:pointer] :pointer)
(ffi/defcfn gtk-text-buffer-apply-tag "gtk_text_buffer_apply_tag" [:pointer :pointer :pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-remove-all-tags "gtk_text_buffer_remove_all_tags" [:pointer :pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-get-tag-table "gtk_text_buffer_get_tag_table" [:pointer] :pointer)

;; --- tags via the fixed-arity GValue path ------------------------------------
(ffi/defcfn gtk-text-tag-new "gtk_text_tag_new" [:string] :pointer)
(ffi/defcfn gtk-text-tag-table-add "gtk_text_tag_table_add" [:pointer :pointer] :int)
(ffi/defcfn g-value-init "g_value_init" [:pointer :size_t] :pointer)
(ffi/defcfn g-value-set-string "g_value_set_string" [:pointer :string] :void)
(ffi/defcfn g-value-unset "g_value_unset" [:pointer] :void)
(ffi/defcfn g-object-set-property "g_object_set_property" [:pointer :string :pointer] :void)

;; --- key input ---------------------------------------------------------------
(ffi/defcfn gtk-event-controller-key-new "gtk_event_controller_key_new" [] :pointer)
(ffi/defcfn gtk-event-controller-set-propagation-phase "gtk_event_controller_set_propagation_phase" [:pointer :int] :void)
(ffi/defcfn gtk-widget-add-controller "gtk_widget_add_controller" [:pointer :pointer] :void)

;; GtkEventControllerFocus -- gtkeventcontrollerfocus.h:46. Its own
;; "leave" signal (Gtk-4.0.gir: class EventControllerFocus, glib:signal
;; leave -- no signal args beyond instance/user_data) closes the
;; quick-doc popover when focus moves off the editor's view. Tracks
;; focus-within at the GtkRoot level (like gtk_root_get_focus,
;; jonquil.gtk.window.keys's do-toggle-focus!), not gtk_widget_has_focus's
;; OS-window-active dependency.
(ffi/defcfn gtk-event-controller-focus-new "gtk_event_controller_focus_new" [] :pointer)

;; GtkEventControllerScroll --
;; gtkeventcontrollerscroll.h:61-69 (GtkEventControllerScrollFlags;
;; BOTH_AXES, line 68, is VERTICAL (1 << 0) | HORIZONTAL (1 << 1) = 3)
;; and :75 (the constructor, gtk_event_controller_scroll_new (flags)).
;; Lets the source panel notice a person's own scroll over its editor and
;; cancel its own re-scroll settling (jonquil.gtk.source-pane/arm-
;; scroll-settle!) instead of fighting them. Its "scroll" signal is
;; (GtkEventControllerScroll*, double dx, double dy, gpointer) -> gboolean
;; (GTK docs); FALSE/0 lets GTK still perform the real scroll.
(ffi/defcfn gtk-event-controller-scroll-new "gtk_event_controller_scroll_new" [:int] :pointer)
(def EVENT-CONTROLLER-SCROLL-BOTH-AXES 3) ;; VERTICAL (1) | HORIZONTAL (2)

(ffi/defcfn gtk-widget-get-width "gtk_widget_get_width" [:pointer] :int)
(ffi/defcfn gtk-widget-get-height "gtk_widget_get_height" [:pointer] :int)
(ffi/defcfn gdk-keyval-to-unicode "gdk_keyval_to_unicode" [:uint] :uint)

;; --- CSS styling (fonts + colors, variadic-free) ------------------------------
(ffi/defcfn gtk-css-provider-new "gtk_css_provider_new" [] :pointer)
(ffi/defcfn gtk-css-provider-load-from-data "gtk_css_provider_load_from_data" [:pointer :string :long] :void)
(ffi/defcfn gdk-display-get-default "gdk_display_get_default" [] :pointer)
(ffi/defcfn gtk-style-context-add-provider-for-display "gtk_style_context_add_provider_for_display" [:pointer :pointer :uint] :void)

(defn set-string-property!
  "Set a string GObject property via the fixed-arity GValue path
  (the variadic g_object_set is ABI-unsafe here)."
  [obj prop s]
  (let [v (ffi/alloc GVALUE-BYTES)]
    (try
      ;; GValue must start zeroed before g_value_init
      ;; (write p t value offset) since jolt v0.7.29-25, was (p t offset value)
      (doseq [i (range GVALUE-BYTES)] (ffi/write v :uint8 0 i))
      (g-value-init v G-TYPE-STRING)
      (g-value-set-string v s)
      (g-object-set-property obj prop v)
      (g-value-unset v)
      (finally (ffi/free v)))))

;; --- an EDITABLE text view -------------------------------------------------
;; Signatures checked against gtk-4.0 (gtk4 4.22.4).
(def G-TYPE-INT (* 6 4))           ; fundamental GType: 6 << 2
(def PANGO-WEIGHT-BOLD 700)

(ffi/defcfn g-free "g_free" [:pointer] :void)
(ffi/defcfn g-value-set-int "g_value_set_int" [:pointer :int] :void)
(ffi/defcfn gtk-text-buffer-get-text* "gtk_text_buffer_get_text" [:pointer :pointer :pointer :int] :pointer)
(ffi/defcfn ^:private gtk-text-buffer-insert* "gtk_text_buffer_insert" [:pointer :pointer :pointer :int] :void)
(ffi/defcfn ^:private gtk-text-buffer-insert-interactive-at-cursor*
  "gtk_text_buffer_insert_interactive_at_cursor" [:pointer :pointer :int :int] :int)

(defn gtk-text-buffer-insert
  "gtk_text_buffer_insert with text as a C string (see gtk-text-buffer-set-text)."
  [buf iter text len]
  (ffi/with-c-string [p text] (gtk-text-buffer-insert* buf iter p len)))

(defn gtk-text-buffer-insert-interactive-at-cursor
  "gtk_text_buffer_insert_interactive_at_cursor with text as a C string (see
  gtk-text-buffer-set-text)."
  [buf text len default-editable]
  (ffi/with-c-string [p text] (gtk-text-buffer-insert-interactive-at-cursor* buf p len default-editable)))

(ffi/defcfn gtk-text-buffer-delete "gtk_text_buffer_delete" [:pointer :pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-begin-user-action "gtk_text_buffer_begin_user_action" [:pointer] :void)
(ffi/defcfn gtk-text-buffer-end-user-action "gtk_text_buffer_end_user_action" [:pointer] :void)
(ffi/defcfn gtk-text-buffer-begin-irreversible-action "gtk_text_buffer_begin_irreversible_action" [:pointer] :void)
(ffi/defcfn gtk-text-buffer-end-irreversible-action "gtk_text_buffer_end_irreversible_action" [:pointer] :void)
(ffi/defcfn gtk-text-buffer-get-selection-bounds "gtk_text_buffer_get_selection_bounds" [:pointer :pointer :pointer] :int)
(ffi/defcfn gtk-text-buffer-select-range "gtk_text_buffer_select_range" [:pointer :pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-get-iter-at-mark "gtk_text_buffer_get_iter_at_mark" [:pointer :pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-set-enable-undo "gtk_text_buffer_set_enable_undo" [:pointer :int] :void)
(ffi/defcfn gtk-text-buffer-get-can-undo "gtk_text_buffer_get_can_undo" [:pointer] :int)
(ffi/defcfn gtk-text-buffer-undo "gtk_text_buffer_undo" [:pointer] :void)
(ffi/defcfn gtk-text-buffer-get-line-count "gtk_text_buffer_get_line_count" [:pointer] :int)
(ffi/defcfn gtk-text-buffer-get-char-count "gtk_text_buffer_get_char_count" [:pointer] :int)
(ffi/defcfn gtk-text-iter-get-offset "gtk_text_iter_get_offset" [:pointer] :int)
(ffi/defcfn gtk-text-iter-has-tag "gtk_text_iter_has_tag" [:pointer :pointer] :int)
(ffi/defcfn gtk-paned-new "gtk_paned_new" [:int] :pointer)
(ffi/defcfn gtk-paned-set-start-child "gtk_paned_set_start_child" [:pointer :pointer] :void)
(ffi/defcfn gtk-paned-set-end-child "gtk_paned_set_end_child" [:pointer :pointer] :void)
(ffi/defcfn gtk-paned-set-position "gtk_paned_set_position" [:pointer :int] :void)
(ffi/defcfn gtk-button-new-with-label "gtk_button_new_with_label" [:string] :pointer)
(ffi/defcfn gtk-button-set-label "gtk_button_set_label" [:pointer :string] :void)
;; gtkbutton.h gtk_button_get_label (GtkButton*) -> const char* (borrowed)
(ffi/defcfn gtk-button-get-label "gtk_button_get_label" [:pointer] :pointer)

(defn set-int-property!
  "Set an int GObject property via the fixed-arity GValue path."
  [obj prop n]
  (let [v (ffi/alloc GVALUE-BYTES)]
    (try
      (doseq [i (range GVALUE-BYTES)] (ffi/write v :uint8 0 i))
      (g-value-init v G-TYPE-INT)
      (g-value-set-int v n)
      (g-object-set-property obj prop v)
      (g-value-unset v)
      (finally (ffi/free v)))))

(defn buffer-text
  "The whole buffer as a Clojure string. get_text returns a newly allocated
  char* the caller must g_free, so it comes back as a pointer."
  [buf]
  (let [i1 (ffi/alloc ITER-BYTES) i2 (ffi/alloc ITER-BYTES)]
    (try
      (gtk-text-buffer-get-start-iter buf i1)
      (gtk-text-buffer-get-end-iter buf i2)
      (let [p (gtk-text-buffer-get-text* buf i1 i2 0)]
        (try (ffi/ptr->string p) (finally (g-free p))))
      (finally (ffi/free i1) (ffi/free i2)))))

(defn with-iters*
  "Calls (f i1 i2) with two iters at character offsets a and b."
  [buf a b f]
  (let [i1 (ffi/alloc ITER-BYTES) i2 (ffi/alloc ITER-BYTES)]
    (try
      (gtk-text-buffer-get-iter-at-offset buf i1 a)
      (gtk-text-buffer-get-iter-at-offset buf i2 b)
      (f i1 i2)
      (finally (ffi/free i1) (ffi/free i2)))))

(defn buffer-text-range
  "The buffer's text between character offsets a and b, buffer-text's
  ranged sibling: the same get_text call and caller-owned g_free, over
  with-iters*'s iters instead of the buffer's bounds."
  [buf a b]
  (with-iters* buf a b
    (fn [i1 i2]
      (let [p (gtk-text-buffer-get-text* buf i1 i2 0)]
        (try (ffi/ptr->string p) (finally (g-free p)))))))

(defn cursor-offset [buf]
  (let [it (ffi/alloc ITER-BYTES)]
    (try (gtk-text-buffer-get-iter-at-mark buf it (gtk-text-buffer-get-insert buf))
         (gtk-text-iter-get-offset it)
         (finally (ffi/free it)))))

(defn selection-offsets
  "[start end] in characters; equal when there is no selection."
  [buf]
  (let [i1 (ffi/alloc ITER-BYTES) i2 (ffi/alloc ITER-BYTES)]
    (try (gtk-text-buffer-get-selection-bounds buf i1 i2)
         [(gtk-text-iter-get-offset i1) (gtk-text-iter-get-offset i2)]
         (finally (ffi/free i1) (ffi/free i2)))))

(defn has-tag-at? [buf offset tag]
  (let [it (ffi/alloc ITER-BYTES)]
    (try (gtk-text-buffer-get-iter-at-offset buf it offset)
         (not (zero? (bit-and 1 (gtk-text-iter-has-tag it tag))))
         (finally (ffi/free it)))))

;; --- async file dialogs (GTK 4.10+) ------------------------------------------
(ffi/defcfn gtk-file-dialog-new "gtk_file_dialog_new" [] :pointer)
(ffi/defcfn gtk-file-dialog-open "gtk_file_dialog_open" [:pointer :pointer :pointer :pointer :pointer] :void)
(ffi/defcfn gtk-file-dialog-open-finish "gtk_file_dialog_open_finish" [:pointer :pointer :pointer] :pointer)
(ffi/defcfn gtk-file-dialog-save "gtk_file_dialog_save" [:pointer :pointer :pointer :pointer :pointer] :void)
(ffi/defcfn gtk-file-dialog-save-finish "gtk_file_dialog_save_finish" [:pointer :pointer :pointer] :pointer)
(ffi/defcfn g-file-get-path* "g_file_get_path" [:pointer] :pointer)
(ffi/defcfn g-object-unref "g_object_unref" [:pointer] :void)
(ffi/defcfn g-error-free "g_error_free" [:pointer] :void)
(ffi/defcfn g-cancellable-new "g_cancellable_new" [] :pointer)
(ffi/defcfn g-cancellable-cancel "g_cancellable_cancel" [:pointer] :void)
;; gtk_window_get_toplevels (gtkwindow.h): a GListModel of every toplevel,
;; owned by GTK; g_list_model_get_n_items counts it (the self-test counts
;; alert dialogs with these)
(ffi/defcfn gtk-window-get-toplevels "gtk_window_get_toplevels" [] :pointer)
(ffi/defcfn gtk-window-maximize "gtk_window_maximize" [:pointer] :void)
(ffi/defcfn g-list-model-get-n-items "g_list_model_get_n_items" [:pointer] :uint)

(defn toplevel-count
  "How many toplevel windows GTK has: the app's windows and any open dialog."
  []
  (g-list-model-get-n-items (gtk-window-get-toplevels)))

(defn finish-file
  "Completes an open/save. Returns {:path s} or {:error msg :code n :domain q}.
  GError is {GQuark domain; gint code; gchar *message}
  (glib-2.0/glib/gerror.h:45-50): domain at byte 0,
  code at byte 4, message pointer at byte 8. :domain lets a caller tell
  apart error codes that collide across domains
  (GTK_DIALOG_ERROR_DISMISSED and G_IO_ERROR_EXISTS are both 2)."
  [finish-fn dialog result]
  (let [slot (ffi/alloc 8)]
    (try
      (ffi/write slot :pointer ffi/null 0)
      (let [file (finish-fn dialog result slot)]
        (if (ffi/null? file)
          (let [err (ffi/read slot :pointer)]
            (if (ffi/null? err)
              {:error "no file and no GError"}
              (try {:error (ffi/ptr->string (ffi/read err :pointer 8))
                    :code (ffi/read err :int 4)
                    :domain (ffi/read err :uint 0)}
                   (finally (g-error-free err)))))
          (try (let [p (g-file-get-path* file)]
                 (try {:path (ffi/ptr->string p)} (finally (g-free p))))
               (finally (g-object-unref file)))))
      (finally (ffi/free slot)))))

;; --- GValue scalars, pango style, margins ---
(def G-TYPE-BOOLEAN (* 5 4))
(def PANGO-STYLE-ITALIC 2)
(ffi/defcfn g-value-set-boolean "g_value_set_boolean" [:pointer :int] :void)
(ffi/defcfn g-value-set-enum "g_value_set_enum" [:pointer :int] :void)
(ffi/defcfn pango-style-get-type "pango_style_get_type" [] :size_t)
(ffi/defcfn gtk-text-view-set-left-margin "gtk_text_view_set_left_margin" [:pointer :int] :void)
(ffi/defcfn gtk-text-view-set-top-margin "gtk_text_view_set_top_margin" [:pointer :int] :void)
(ffi/defcfn gtk-widget-grab-focus "gtk_widget_grab_focus" [:pointer] :int)

(defn- set-gvalue! [obj prop gtype setter]
  (let [v (ffi/alloc GVALUE-BYTES)]
    (try
      (doseq [i (range GVALUE-BYTES)] (ffi/write v :uint8 0 i))
      (g-value-init v gtype)
      (setter v)
      (g-object-set-property obj prop v)
      (g-value-unset v)
      (finally (ffi/free v)))))

(defn set-bool-property! [obj prop b]
  (set-gvalue! obj prop G-TYPE-BOOLEAN (fn [v] (g-value-set-boolean v (if b 1 0)))))

(defn set-italic! [tag]
  (set-gvalue! tag "style" (pango-style-get-type) (fn [v] (g-value-set-enum v PANGO-STYLE-ITALIC))))

;; --- the console prompt marks ---
(ffi/defcfn gtk-text-buffer-create-mark "gtk_text_buffer_create_mark" [:pointer :string :pointer :int] :pointer)
(ffi/defcfn gtk-text-buffer-move-mark "gtk_text_buffer_move_mark" [:pointer :pointer :pointer] :void)
;; gtk_text_buffer_delete_mark: gtk-4.0/gtk/gtktextbuffer.h:327
(ffi/defcfn gtk-text-buffer-delete-mark "gtk_text_buffer_delete_mark" [:pointer :pointer] :void)

(defn mark-offset
  "The character offset of mark in buf."
  [buf mark]
  (let [it (ffi/alloc ITER-BYTES)]
    (try (gtk-text-buffer-get-iter-at-mark buf it mark)
         (gtk-text-iter-get-offset it)
         (finally (ffi/free it)))))
(ffi/defcfn gtk-text-view-scroll-to-mark "gtk_text_view_scroll_to_mark" [:pointer :pointer :double :int :double :double] :void)

;; --- reading a tag's style back ---
;; Signatures checked against glib-2.0/gobject
;; (glib 2.0, gobject-2.0 installed via homebrew).
;; g_value_get_enum: gobject/genums.h:248 -- gint g_value_get_enum (const GValue *value)
(ffi/defcfn g-value-get-enum "g_value_get_enum" [:pointer] :int)
;; g_object_get_property: gobject/gobject.h:493 -- void g_object_get_property
;;   (GObject *object, const gchar *property_name, GValue *value)
(ffi/defcfn g-object-get-property "g_object_get_property" [:pointer :string :pointer] :void)

(defn- get-gvalue!
  "Reads obj's prop through the fixed-arity GValue path (get-property's
  counterpart to set-gvalue!'s set-property), returning (getter v)."
  [obj prop gtype getter]
  (let [v (ffi/alloc GVALUE-BYTES)]
    (try
      (doseq [i (range GVALUE-BYTES)] (ffi/write v :uint8 0 i))
      (g-value-init v gtype)
      (g-object-get-property obj prop v)
      (let [result (getter v)]
        (g-value-unset v)
        result)
      (finally (ffi/free v)))))

(defn tag-italic?
  "True when tag's `style` property reads back as PANGO_STYLE_ITALIC --
  set-italic!'s read-back counterpart."
  [tag]
  (= PANGO-STYLE-ITALIC (get-gvalue! tag "style" (pango-style-get-type) g-value-get-enum)))

;; --- interactive insert/delete with an explicit iter ---
;; gtk_text_buffer_insert_interactive: gtk/gtktextbuffer.h:221-225 (gtk4
;; 4.22.4 headers).
;; Unlike gtk-text-buffer-insert-interactive-at-cursor above, this one takes
;; an explicit iter, so a self-test row can target a specific offset instead
;; of wherever the cursor happens to be.
(ffi/defcfn gtk-text-buffer-insert-interactive "gtk_text_buffer_insert_interactive"
  [:pointer :pointer :string :int :int] :int)

;; gtk_text_buffer_delete_interactive: gtk/gtktextbuffer.h:272-275 (same
;; header/version as above). Used to probe that Backspace/Delete at
;; input-start and inside output history are refused, same as insert.
(ffi/defcfn gtk-text-buffer-delete-interactive "gtk_text_buffer_delete_interactive"
  [:pointer :pointer :pointer :int] :int)

;; --- the Reset-on-Run toggle ---
(ffi/defcfn gtk-check-button-new-with-label "gtk_check_button_new_with_label" [:string] :pointer)
(ffi/defcfn gtk-check-button-get-active "gtk_check_button_get_active" [:pointer] :int)
(ffi/defcfn gtk-check-button-set-active "gtk_check_button_set_active" [:pointer :int] :void)
(ffi/defcfn gtk-check-button-set-label "gtk_check_button_set_label" [:pointer :string] :void)
(ffi/defcfn gtk-check-button-get-label "gtk_check_button_get_label" [:pointer] :pointer)
;; g_signal_emit_by_name (gsignal.h) is variadic; bound with no trailing
;; arguments, it emits a signal that takes none and returns nothing, such
;; as a GtkButton's "clicked", synchronously. (gtk_widget_activate on a
;; GtkButton defers "clicked" behind its press animation instead, so a
;; caller can't observe the handler within the same step.) Never pass it
;; a signal's own arguments through this binding: true varargs need a
;; variadic-aware declaration, and this one has fixed arguments only.
(ffi/defcfn g-signal-emit-by-name "g_signal_emit_by_name" [:pointer :string] :void)

;; --- status/title read-back and a self-test-driven close.
;; Signatures checked against gtk-4.0/gtk/{gtklabel,
;; gtkwindow}.h -- gtk_label_get_text (gtklabel.h:51), gtk_window_get_title
;; (gtkwindow.h:99) and gtk_window_close (gtkwindow.h:209). The self-tests
;; read the status label and the title back, and gtk_window_close ends a
;; self-test run cleanly: it triggers the SAME close-request path a real
;; close-button click would, so it is also a live check that closing the
;; window quits the app.
(ffi/defcfn gtk-label-get-text "gtk_label_get_text" [:pointer] :pointer)
(ffi/defcfn gtk-window-get-title "gtk_window_get_title" [:pointer] :pointer)
(ffi/defcfn gtk-window-close "gtk_window_close" [:pointer] :void)

(defn check-button-label
  "A check button's label. gtk_check_button_get_label returns a borrowed
  const char*, so no g_free."
  [btn]
  (ffi/ptr->string (gtk-check-button-get-label btn)))

(defn label-text
  "label's text. gtk_label_get_text returns a borrowed const char* (unlike
  gtk_text_buffer_get_text's caller-owned copy) -- no g_free here."
  [label]
  (ffi/ptr->string (gtk-label-get-text label)))

(defn window-title
  "window's title, or nil if none was ever set. Also a borrowed const
  char* -- no g_free."
  [window]
  (let [p (gtk-window-get-title window)]
    (when-not (ffi/null? p) (ffi/ptr->string p))))

;; --- dialog error codes, keyvals, application flags ---
;; GTK_DIALOG_ERROR_DISMISSED: gtk-4.0/gtk/gtkdialogerror.h:54-56
;; (enum GtkDialogError { GTK_DIALOG_ERROR_FAILED, GTK_DIALOG_ERROR_CANCELLED,
;; GTK_DIALOG_ERROR_DISMISSED } -- DISMISSED is the third value, 2). The
;; file-dialog result handlers ignore this one code silently.
(def GTK-DIALOG-ERROR-DISMISSED 2)

;; gdk_keyval_to_lower: gtk-4.0/gdk/gdkkeys.h:47
;; (guint gdk_keyval_to_lower (guint keyval)). Normalises a keyval before
;; comparing it to a shortcut's letter, so Caps Lock (which reports the
;; uppercase keyval with no Shift bit set) doesn't break Ctrl+R/O/S.
(ffi/defcfn gdk-keyval-to-lower "gdk_keyval_to_lower" [:uint] :uint)

;; gdk_keyval_name: gtk-4.0/gdk/gdkkeys.h:36
;; (const char *gdk_keyval_name (guint keyval)). A static string, never freed.
(ffi/defcfn gdk-keyval-name* "gdk_keyval_name" [:uint] :pointer)

(defn printable-key?
  "True when keyval yields a printable character (not a control code)."
  [keyval]
  (let [c (gdk-keyval-to-unicode keyval)]
    (and (>= c 0x20) (not= c 0x7f))))

(defn keyval-name
  "keyval's GDK name (\"s\", \"F6\", \"Shift_L\"), or nil for a keyval with
  none."
  [keyval]
  (let [p (gdk-keyval-name* keyval)]
    (when-not (ffi/null? p) (ffi/ptr->string p))))

;; G_APPLICATION_NON_UNIQUE: glib-2.0/gio/gioenums.h:1572
;; (GApplicationFlags enum, G_APPLICATION_NON_UNIQUE = 1 << 5 = 32). Passed
;; to gtk_application_new so a second launch doesn't just forward its
;; "activate" into an already-running instance over D-Bus.
(def G-APPLICATION-NON-UNIQUE (bit-shift-left 1 5))

;; G_APPLICATION_HANDLES_OPEN: glib-2.0/gio/gioenums.h:1578
;; (GApplicationFlags enum, G_APPLICATION_HANDLES_OPEN = 1 << 2 = 4). Lets
;; the app emit "open" for files from Finder's Open With and Dock drops.
(def G-APPLICATION-HANDLES-OPEN (bit-shift-left 1 2))

;; g_application_open: glib-2.0/gio/gapplication.h:218-221
;; (void g_application_open (GApplication*, GFile **files, gint n_files, const gchar *hint)).
(ffi/defcfn g-application-open "g_application_open" [:pointer :pointer :int :string] :void)

;; g_application_get_flags: glib-2.0/gio/gapplication.h:159-160
;; (GApplicationFlags g_application_get_flags (GApplication *application)).
;; Round-trip readback for G-APPLICATION-NON-UNIQUE's own unit test -- doesn't
;; need a display, only a constructed GApplication object.
(ffi/defcfn g-application-get-flags "g_application_get_flags" [:pointer] :uint)

;; gtk_dialog_error_quark: gtk-4.0/gtk/gtkdialogerror.h:60
;; (GQuark gtk_dialog_error_quark (void)). GQuark is guint32
;; (glib-2.0/glib/gquark.h:38). The code alone
;; isn't enough to identify GTK_DIALOG_ERROR_DISMISSED, since G_IO_ERROR_EXISTS
;; is ALSO 2 (glib-2.0/gio/gioenums.h:543-544, third
;; enumerator after G_IO_ERROR_FAILED/NOT_FOUND) -- a GError's domain quark
;; (finish-file's :domain) must match too.
(ffi/defcfn gtk-dialog-error-quark "gtk_dialog_error_quark" [] :uint)

;; --- redo ---
;; gtk_text_buffer_redo / gtk_text_buffer_get_can_redo: redo is undo's
;; counterpart (jonquil.gtk.ffi already binds gtk-text-buffer-undo and
;; gtk-text-buffer-get-can-undo); get-can-redo gates handle-key!'s
;; command-chord+y dispatch so redo is only invoked when there is
;; something to redo.
(ffi/defcfn gtk-text-buffer-redo "gtk_text_buffer_redo" [:pointer] :void)
(ffi/defcfn gtk-text-buffer-get-can-redo "gtk_text_buffer_get_can_redo" [:pointer] :int)

;; --- focus, CSS classes, measuring ---
;; gtk_root_get_focus: gtk/gtkroot.h:43 (gtk4 4.22.4). Returns the widget
;; that currently holds keyboard focus within the root, or NULL. F6
;; (jonquil.gtk.window.keys/do-toggle-focus!) compares against THIS pointer
;; rather than gtk_widget_has_focus -- probing measured has_focus reading
;; false on a window that never became the OS key window, which a
;; self-test window usually isn't; grab_focus still moves the root's own
;; focus-widget bookkeeping regardless.
(ffi/defcfn gtk-root-get-focus "gtk_root_get_focus" [:pointer] :pointer)

;; gtk_css_provider_load_from_string / gtk_widget_add_css_class:
;; gtkcssprovider.h:53 (GDK_AVAILABLE_IN_4_12,
;; satisfied by gtk4 4.22.4; the non-deprecated alternative to the
;; already-bound gtk-css-provider-load-from-data) and gtkwidget.h:922. The
;; display getter (gdk-display-get-default) and
;; gtk-style-context-add-provider-for-display this needs are both already
;; bound above (the "CSS styling" section near the top of this file).
(ffi/defcfn gtk-css-provider-load-from-string "gtk_css_provider_load_from_string" [:pointer :string] :void)
(ffi/defcfn gtk-widget-add-css-class "gtk_widget_add_css_class" [:pointer :string] :void)

;; gtk_widget_measure: gtkwidget.h:310-316 (natural-height helper below,
;; GTK_ORIENTATION_VERTICAL = 1, for_size -1). The zoom-in-out self-test row needs a numeric proxy for
;; "did the CSS font-size actually change the layout";
;; gtk_widget_get_height reads the widget's last ALLOCATED size instead of
;; its natural size and isn't guaranteed to track a CSS-only reload the
;; same way.
(ffi/defcfn gtk-widget-measure "gtk_widget_measure" [:pointer :int :int :pointer :pointer :pointer :pointer] :void)

(defn natural-height
  "widget's natural height via gtk_widget_measure (GTK_ORIENTATION_VERTICAL
  = 1, for_size -1: no cross-axis constraint) -- a numeric proxy for
  \"did the CSS font-size actually apply\". Measured 0 for a widget
  that hadn't been through a real main-loop layout pass since the CSS
  reload; callers must re-measure on a LATER tick (a poll-until, not a
  fixed sleep), never the same tick as the reload."
  [widget]
  (let [out (ffi/alloc 16)]
    (try
      (gtk-widget-measure widget 1 -1 out (+ out 4) (+ out 8) (+ out 12))
      (ffi/read out :int 4)
      (finally (ffi/free out)))))

;; --- find bar entries ---
;; gtk_search_entry_new / gtk_entry_new: gtksearchentry.h:48, gtkentry.h:102
;; (gtk4 4.22.4). The query entry is a GtkSearchEntry, the replacement
;; entry a plain GtkEntry.
(ffi/defcfn gtk-search-entry-new "gtk_search_entry_new" [] :pointer)
(ffi/defcfn gtk-entry-new "gtk_entry_new" [] :pointer)
;; gtk_entry_set_placeholder_text: gtkentry.h; the review panel's note.
(ffi/defcfn gtk-entry-set-placeholder-text "gtk_entry_set_placeholder_text" [:pointer :string] :void)
;; gtk_editable_get_text / gtk_editable_set_text: gtkeditable.h:82, 84-85.
;; get_text returns a borrowed const char* (no transfer-full annotation) --
;; no g_free, unlike gtk_text_buffer_get_text's caller-owned copy
;; buffer-text already frees. Both GtkSearchEntry and GtkEntry
;; implement GtkEditable directly.
(ffi/defcfn gtk-editable-get-text "gtk_editable_get_text" [:pointer] :pointer)
(ffi/defcfn ^:private gtk-editable-set-text* "gtk_editable_set_text" [:pointer :pointer] :void)

(defn gtk-editable-set-text
  "gtk_editable_set_text with text as a C string: it deletes the old text
  first, which emits to an entry's handlers (see gtk-text-buffer-set-text)."
  [editable text]
  (ffi/with-c-string [p text] (gtk-editable-set-text* editable p)))

;; gtk_widget_set_visible / gtk_widget_get_visible: gtkwidget.h:442-443, 445.
;; The find bar's whole box is hidden until opened; the replacement entry
;; and its two buttons are hidden outside :replace mode.
(ffi/defcfn gtk-widget-set-visible "gtk_widget_set_visible" [:pointer :int] :void)
(ffi/defcfn gtk-widget-get-visible "gtk_widget_get_visible" [:pointer] :int)

;; gtk_widget_set_sensitive / get_sensitive and set_tooltip_text /
;; get_tooltip_text: gtkwidget.h:434-437 and 685-688 (GTK 4.22.4). The
;; Reset on Run checkbox is insensitive, with a tooltip saying why, for an
;; attach runtime. Measured on jolt v0.8.13: a nil :string
;; argument reaches GTK as NULL, which clears the tooltip (get_tooltip_text
;; then answers NULL and has-tooltip reads 0).
(ffi/defcfn gtk-widget-set-sensitive "gtk_widget_set_sensitive" [:pointer :int] :void)
(ffi/defcfn gtk-widget-get-sensitive "gtk_widget_get_sensitive" [:pointer] :int)
(ffi/defcfn gtk-widget-set-tooltip-text "gtk_widget_set_tooltip_text" [:pointer :string] :void)
(ffi/defcfn gtk-widget-get-tooltip-text* "gtk_widget_get_tooltip_text" [:pointer] :pointer)

(defn tooltip-text
  "widget's tooltip text, or nil. A borrowed const char* (gtkwidget.h:688),
  so no g_free, as label-text."
  [widget]
  (let [p (gtk-widget-get-tooltip-text* widget)]
    (when-not (ffi/null? p) (ffi/ptr->string p))))

;; gtk_text_view_scroll_to_iter: gtktextview.h:197-202. Scrolls the
;; current match into view.
(ffi/defcfn gtk-text-view-scroll-to-iter "gtk_text_view_scroll_to_iter"
  [:pointer :pointer :double :int :double :double] :int)

(defn editable-text
  "editable's text (a GtkSearchEntry or GtkEntry). nil-safe borrowed-string
  read, editable-get-text's counterpart to buffer-text/label-text/
  window-title above -- no g_free."
  [editable]
  (let [p (gtk-editable-get-text editable)]
    (when-not (ffi/null? p) (ffi/ptr->string p))))

;; gtk_text_buffer_remove_tag: gtktextbuffer.h:363-366 (same signature shape
;; as the already-bound gtk_text_buffer_apply_tag above). Added because gtk_text_buffer_remove_all_tags would strip the editor's own syntax tags
;; too wherever a match happens to overlap them; this removes only the
;; search-match tag, so recomputing/closing never touches paint/syntax
;; highlighting.
(ffi/defcfn gtk-text-buffer-remove-tag "gtk_text_buffer_remove_tag"
  [:pointer :pointer :pointer :pointer] :void)

;; gtk_widget_get_root: gtkwidget.h:463. Lets the find bar's own handle-key!
;; read gtk_root_get_focus (bound above) from a widget it owns, without
;; needing the window handle make-find-bar's single-arg interface doesn't
;; take -- the only way to tell "Return in the query" apart from "Return in
;; the replacement entry" (neither entry's own \"activate\" signal is
;; relied on).
(ffi/defcfn gtk-widget-get-root "gtk_widget_get_root" [:pointer] :pointer)

;; gtk_editable_get_selection_bounds: gtkeditable.h:101-103. The ENTRY's own
;; selection (distinct from gtk_text_buffer_get_selection_bounds above,
;; which reads the EDITOR's buffer selection) -- both out-params come back
;; identical when there's no selection, per its own doc comment, the same
;; convention selection-offsets already relies on for a GtkTextBuffer.
(ffi/defcfn gtk-editable-get-selection-bounds "gtk_editable_get_selection_bounds"
  [:pointer :pointer :pointer] :int)

(defn editable-selection-bounds
  "[start end] in characters -- editable's own selection, mirroring
  selection-offsets above."
  [editable]
  (let [s (ffi/alloc 4) e (ffi/alloc 4)]
    (try
      (gtk-editable-get-selection-bounds editable s e)
      [(ffi/read s :int 0) (ffi/read e :int 0)]
      (finally (ffi/free s) (ffi/free e)))))

;; gtk_editable_select_region: gtkeditable.h:107-109.
(ffi/defcfn gtk-editable-select-region "gtk_editable_select_region" [:pointer :int :int] :void)

;; gtk_editable_get_delegate: gtkeditable.h:181. GtkSearchEntry and GtkEntry
;; both delegate their GtkEditable implementation to an internal GtkText --
;; confirmed empirically against this gtk4 build: gtk_editable_get_delegate returned a non-null pointer for
;; both a fresh GtkSearchEntry and a fresh GtkEntry, and connecting
;; "preedit-changed" on each returned delegate (GtkText's own signal --
;; Gtk-4.0.gir, gtk/gtktext.c:1257, same (instance, gchar* preedit,
;; user_data) shape GtkTextView's "preedit-changed" already uses in
;; editor.clj) returned a real non-zero handler id on both, with no GLib
;; critical warning.
(ffi/defcfn gtk-editable-get-delegate "gtk_editable_get_delegate" [:pointer] :pointer)

;; --- line/column ---
;; gtk_text_iter_get_line / gtk_text_iter_get_line_offset: gtktextiter.h:119,
;; 121 (gtk4 4.22.4). Both 0-based; the status bar adds 1 for display.
(ffi/defcfn gtk-text-iter-get-line "gtk_text_iter_get_line" [:pointer] :int)
(ffi/defcfn gtk-text-iter-get-line-offset "gtk_text_iter_get_line_offset" [:pointer] :int)

(defn cursor-line-col
  "[line col], both 0-based, at buf's insert mark -- cursor-offset's
  sibling, for the status bar's Ln/Col label."
  [buf]
  (let [it (ffi/alloc ITER-BYTES)]
    (try
      (gtk-text-buffer-get-iter-at-mark buf it (gtk-text-buffer-get-insert buf))
      [(gtk-text-iter-get-line it) (gtk-text-iter-get-line-offset it)]
      (finally (ffi/free it)))))

;; gtk_widget_set_hexpand: gtkwidget.h:556. Lets the status bar's message
;; label claim the box's extra horizontal space, pushing the Ln/Col label to
;; the right edge
;; -- GTK4's GtkBox has no per-child "expand" packing option of its own, a
;; child's own hexpand is what it honours.
(ffi/defcfn gtk-widget-set-hexpand "gtk_widget_set_hexpand" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-margin-start "gtk_widget_set_margin_start" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-margin-end "gtk_widget_set_margin_end" [:pointer :int] :void)

;; g_object_new_with_properties: gobject/gobject.h:436-439. The fixed-arity
;; GtkAlertDialog constructor -- gtk_alert_dialog_new is variadic (printf-
;; style) and is never bound (the no-variadic ABI rule in the ns
;; docstring).
(ffi/defcfn g-object-new-with-properties "g_object_new_with_properties"
  [:size_t :uint :pointer :pointer] :pointer)
;; gtkalertdialog.h (gtk4 4.22.4, GDK_AVAILABLE_IN_4_10): gtk_alert_dialog_get_type
;; via the GTK_TYPE_ALERT_DIALOG macro (line 30, calls gtk_alert_dialog_get_type());
;; set_message:50-51, set_buttons:65-66, set_cancel_button:72-73,
;; set_default_button:78-79, choose:82-86, choose_finish:89-91.
(ffi/defcfn gtk-alert-dialog-get-type "gtk_alert_dialog_get_type" [] :size_t)
(ffi/defcfn gtk-alert-dialog-set-message "gtk_alert_dialog_set_message" [:pointer :string] :void)
(ffi/defcfn gtk-alert-dialog-set-detail "gtk_alert_dialog_set_detail" [:pointer :string] :void)
(ffi/defcfn gtk-alert-dialog-set-buttons "gtk_alert_dialog_set_buttons" [:pointer :pointer] :void)
(ffi/defcfn gtk-alert-dialog-set-cancel-button "gtk_alert_dialog_set_cancel_button" [:pointer :int] :void)
(ffi/defcfn gtk-alert-dialog-set-default-button "gtk_alert_dialog_set_default_button" [:pointer :int] :void)
(ffi/defcfn gtk-alert-dialog-choose "gtk_alert_dialog_choose"
  [:pointer :pointer :pointer :pointer :pointer] :void)
(ffi/defcfn gtk-alert-dialog-choose-finish "gtk_alert_dialog_choose_finish"
  [:pointer :pointer :pointer] :int)

(defn finish-choice
  "Completes gtk_alert_dialog_choose. Returns {:index n} or {:error msg
  :code n :domain q} -- the same GError layout finish-file already
  documents (domain@0, code@4, message-ptr@8), reused here since
  gtk_alert_dialog_choose_finish takes the same GError** out-param shape."
  [dialog result]
  (let [slot (ffi/alloc 8)]
    (try
      (ffi/write slot :pointer ffi/null 0)
      (let [idx (gtk-alert-dialog-choose-finish dialog result slot)]
        (if (neg? idx)
          (let [err (ffi/read slot :pointer)]
            (if (ffi/null? err)
              {:error "no index and no GError"}
              (try {:error (ffi/ptr->string (ffi/read err :pointer 8))
                    :code (ffi/read err :int 4)
                    :domain (ffi/read err :uint 0)}
                   (finally (g-error-free err)))))
          {:index idx}))
      (finally (ffi/free slot)))))

;; gtk_widget_is_ancestor: gtkwidget.h:609-610 (gtk4 4.22.4, GDK_AVAILABLE_IN_ALL,
;; not deprecated -- gtk_widget_translate_coordinates right after it is the
;; deprecated one). True when widget is somewhere inside ancestor, through
;; any number of intermediate containers. Used to test whether the current
;; focus widget is anywhere inside the find bar's own box, WITHOUT needing
;; to know that gtk_root_get_focus actually returns an entry's internal
;; GtkText delegate rather than the entry itself -- measured:
;; gtk_widget_is_ancestor(gtk_root_get_focus(win), box) read true with
;; focus grabbed on the query entry and on the replacement entry, false
;; with focus grabbed on the editor and on the console.
(ffi/defcfn gtk-widget-is-ancestor "gtk_widget_is_ancestor" [:pointer :pointer] :int)
(ffi/defcfn gtk-widget-has-focus "gtk_widget_has_focus" [:pointer] :int)
(ffi/defcfn gtk-widget-is-focus "gtk_widget_is_focus" [:pointer] :int)
(ffi/defcfn gtk-window-is-active "gtk_window_is_active" [:pointer] :int)

;; gtk_text_buffer_delete_selection: gtktextbuffer.h:476-479 (gtk4 4.22.4,
;; GDK_AVAILABLE_IN_ALL). Fixed arity: (buffer, interactive, default_editable)
;; -> gboolean. GtkTextView's own gtk_text_view_commit_text calls exactly this
;; before inserting a typed character (gtk/gtktextview.c, 4.22.4 tag), so a
;; self-test row pairs it with insert_interactive_at_cursor to reproduce what
;; one keystroke does to a live selection, without synthesizing the key.
(ffi/defcfn gtk-text-buffer-delete-selection "gtk_text_buffer_delete_selection"
  [:pointer :int :int] :int)

;; --- theme: settings and colour read-back ---
;; gtk_settings_get_default: gtksettings.h:39 (gtk4 4.22.4); prefer-dark
;; read/write.
(ffi/defcfn gtk-settings-get-default "gtk_settings_get_default" [] :pointer)

;; gdk_rgba_get_type: gdkrgba.h:46 (reading a tag's colour back).
(ffi/defcfn gdk-rgba-get-type "gdk_rgba_get_type" [] :size_t)

;; g_value_get_boxed: gboxed.h:90.
(ffi/defcfn g-value-get-boxed "g_value_get_boxed" [:pointer] :pointer)

;; g_value_get_boolean: gvaluetypes.h:212.
(ffi/defcfn g-value-get-boolean "g_value_get_boolean" [:pointer] :int)

(defn rgba-property-hex
  "obj's prop (a *-rgba GdkRGBA property, e.g. \"foreground-rgba\") read
  back as \"#rrggbb\", or nil when the property comes back unset: a GdkRGBA of four floats at byte offsets 0, 4, 8 (and 12 for
  alpha), each channel (Math/round (* 255.0 f)). Reuses get-gvalue!
  and the already-bound g-object-get-property -- the getter itself reads
  the boxed pointer's floats and formats the hex string before
  get-gvalue!'s own g-value-unset runs, since unset can free the boxed
  value."
  [obj prop]
  (get-gvalue! obj prop (gdk-rgba-get-type)
               (fn [v]
                 (let [p (g-value-get-boxed v)]
                   (when-not (ffi/null? p)
                     (let [ch (fn [off] (Math/round (* 255.0 (ffi/read p :float off))))]
                       (format "#%02x%02x%02x" (ch 0) (ch 4) (ch 8))))))))

;; An application "quit" action. GTK's macOS delegate
;; (gtk/gtkapplication-quartz.c:80-95 at the 4.22.4 tag) answers
;; applicationShouldTerminate: -- Dock Quit, logout, shutdown, the Quit
;; AppleEvent -- with NSTerminateNow unless the app has a "quit" action, in
;; which case it activates that action and returns NSTerminateCancel.
;; glib 2.90.0 headers: g_simple_action_new gio/gsimpleaction.h:43-44
;; (name, const GVariantType* parameter_type, NULL for none);
;; g_action_map_add_action gio/gactionmap.h:84-85 (GtkApplication implements
;; GActionMap); g_action_group_activate_action gio/gactiongroup.h:131-133 (the
;; call the quartz delegate itself makes; used by a self-test row). All
;; fixed-arity. GSimpleAction's "activate" signal callback is (GSimpleAction*,
;; GVariant* parameter, gpointer user_data) -> void.
(ffi/defcfn g-simple-action-new "g_simple_action_new" [:string :pointer] :pointer)
(ffi/defcfn g-action-map-add-action "g_action_map_add_action" [:pointer :pointer] :void)
(ffi/defcfn g-action-group-activate-action "g_action_group_activate_action"
  [:pointer :string :pointer] :void)

;; --- gtk-interface-color-scheme replaces the deprecated
;; gtk-application-prefer-dark-theme boolean. Deprecated since GTK 4.20;
;; from 4.23.1 each set logs a g_warning ("Use GtkSettings:gtk-interface-
;; color-scheme instead"). gtk_interface_color_scheme_get_type:
;; gtktypebuiltins.h:247 (gtk4 4.22.4, GDK_AVAILABLE_IN_ALL, and exported --
;; `nm -gU libgtk-4.dylib` lists _gtk_interface_color_scheme_get_type).
;; GtkInterfaceColorScheme (gtkenums.h:1998-2020, "Since: 4.20"):
;; UNSUPPORTED=0, DEFAULT=1, DARK=2, LIGHT=3.
;;
;; Probe (2026-09-26): gtk_init then GValue set/get through this GType
;; round-tripped DARK(2) and LIGHT(3) exactly. A real window's toolbar
;; (unthemed Adwaita default CSS) sampled identically under both
;; mechanisms: rgb(53,53,53) under gtk-application-prefer-dark-theme=true
;; AND under gtk-interface-color-scheme=DARK(2); rgb(246,245,244) under
;; prefer-dark-theme=false/DEFAULT-baseline AND under explicit LIGHT(3).
;; So apply-window-theme! uses the new property.
(ffi/defcfn gtk-interface-color-scheme-get-type "gtk_interface_color_scheme_get_type" [] :size_t)
(def GTK-INTERFACE-COLOR-SCHEME-DARK 2)
(def GTK-INTERFACE-COLOR-SCHEME-LIGHT 3)

(defn set-color-scheme!
  "Sets GtkSettings' gtk-interface-color-scheme to DARK when dark? is
  truthy, else explicit LIGHT (never DEFAULT, which would track the OS
  appearance instead of Jonquil's own per-theme choice)."
  [settings dark?]
  (set-gvalue! settings "gtk-interface-color-scheme" (gtk-interface-color-scheme-get-type)
               (fn [v] (g-value-set-enum v (if dark?
                                             GTK-INTERFACE-COLOR-SCHEME-DARK
                                             GTK-INTERFACE-COLOR-SCHEME-LIGHT)))))

(ffi/defcfn g-value-get-int "g_value_get_int" [:pointer] :int)

(defn double-click-time
  "GtkSettings' gtk-double-click-time in ms: the longest gap between two
  clicks that GTK still counts as a double-click. On macOS GTK fills it
  from the system's double-click speed (com.apple.mouse.doubleClickThreshold,
  probed 2026-11-02 with GTK 4.24.1: 0.5 s read 500, 0.8 s read 800)."
  [settings]
  (get-gvalue! settings "gtk-double-click-time" G-TYPE-INT g-value-get-int))

(defn color-scheme-dark?
  "True when gtk-interface-color-scheme reads back DARK."
  [settings]
  (= GTK-INTERFACE-COLOR-SCHEME-DARK
     (get-gvalue! settings "gtk-interface-color-scheme" (gtk-interface-color-scheme-get-type)
                  g-value-get-enum)))

;; --- the provider's own serialisation, for the CSS self-test rows.
;; gtk_css_provider_to_string:
;; gtkcssprovider.h:46 (gtk4 4.22.4, GDK_AVAILABLE_IN_ALL), fixed arity
;; (provider) -> a newly allocated char* the caller frees -- buffer-text's
;; own pattern (:pointer, then ptr->string, then g_free).
(ffi/defcfn gtk-css-provider-to-string "gtk_css_provider_to_string" [:pointer] :pointer)

(defn css-provider-string
  "provider's current stylesheet as a Clojure string. Measured 2026-09-26:
  GTK's own serialisation normalises colours (#000000 -> rgb(0,0,0)) and
  re-orders/re-indents the declarations, but keeps \"font-size: <n>px;\"
  verbatim."
  [provider]
  (let [p (gtk-css-provider-to-string provider)]
    (try (ffi/ptr->string p) (finally (g-free p)))))

;; --- tag priority: verify (never assume) that the find bar's
;; search-match tag wins tag-table priority over a syntax tag on the same
;; range. gtk_text_tag_set_priority: gtktexttag.h:100-101 (gtk4 4.22.4,
;; GDK_AVAILABLE_IN_ALL), fixed arity (tag, priority) -> void. Its getter
;; counterpart, gtk_text_tag_get_priority: gtktexttag.h:97-98, fixed arity
;; (tag) -> int -- a tag's priority IS its 0-based tag-table add order
;; (GTK docs: "text tags added later have higher priority", and a
;; conflicting property, e.g. two tags both setting foreground, is decided
;; by whichever overlapping tag has the higher priority), so comparing the
;; two tags' priorities is a direct, non-visual way to confirm which one's
;; foreground actually renders. See find_bar.clj and the
;; theme-keeps-find-highlight self-test row for whether set-priority is
;; called.
(ffi/defcfn gtk-text-tag-get-priority "gtk_text_tag_get_priority" [:pointer] :int)
(ffi/defcfn gtk-text-tag-set-priority "gtk_text_tag_set_priority" [:pointer :int] :void)

;; --- popovers and coordinates ---
;; Header lines checked against gtk-4.0/gtk (gtk4
;; 4.22.4).
;; gtk_widget_set_parent: gtkwidget.h:457 -- void gtk_widget_set_parent
;; (GtkWidget*, GtkWidget *parent). Fixed arity.
(ffi/defcfn gtk-widget-set-parent "gtk_widget_set_parent" [:pointer :pointer] :void)
;; gtk_widget_get_parent: gtkwidget.h:460 -- GtkWidget* (GtkWidget*).
;; gtk_widget_unparent: gtkwidget.h:272 -- void (GtkWidget*). Both fixed
;; arity. Measured 2026-09-26:
;; parenting a popover DIRECTLY to a GtkTextView hangs the whole process the
;; first time the window is actually destroyed -- sample'd main-thread
;; stack sits at 100% CPU in gtk_text_view_dispose -> gtk_text_view_remove
;; -> g_log_structured_standard, an infinite loop: GtkTextView's own
;; dispose walks its structural children to detach them, doesn't recognise
;; a foreign (non-anchor) child, warns "GtkPopover is not a child of
;; GtkTextView" every iteration and never actually detaches it, so the
;; loop never terminates. Parenting to the view's own immediate container
;; instead (measured: a plain GtkScrolledWindow, no special dispose
;; override) destroys cleanly. jonquil.gtk.popover/make-popover uses this
;; to resolve that container from the view it's handed.
(ffi/defcfn gtk-widget-get-parent "gtk_widget_get_parent" [:pointer] :pointer)
(ffi/defcfn gtk-widget-unparent "gtk_widget_unparent" [:pointer] :void)
;; gtk_widget_compute_point: gtkwidget.h:493-496 -- gboolean (...)
;; (GtkWidget*, GtkWidget *target, const graphene_point_t*, graphene_point_t*).
;; graphene_point_t is two floats. Fixed arity.
(ffi/defcfn gtk-widget-compute-point "gtk_widget_compute_point" [:pointer :pointer :pointer :pointer] :int)

(defn compute-point
  "[x y] in widget's own coordinates, translated into target's -- nil when
  GTK can't compute a transform (gtk_widget_compute_point's own documented
  failure case). Needed because gtk_popover_set_pointing_to's rectangle is
  in the coordinate space of the popover's actual GtkWidget parent (GIR
  doc), which jonquil.gtk.popover now resolves to the view's own container,
  not the view itself -- see gtk-widget-get-parent's citation above."
  [widget target x y]
  (let [in (ffi/alloc 8) out (ffi/alloc 8)]
    (try
      ;; (double ...), not (float ...): jolt v0.8.20's foreign-set! took
      ;; only a Double for a :float slot and rejected a java.lang.Float
      ;; (measured 2026-10-09). v0.8.21 accepts both again (jolt-lang/jolt
      ;; #1298, #1300); a double works on every version.
      (ffi/write in :float (double x) 0)
      (ffi/write in :float (double y) 4)
      (when (pos? (gtk-widget-compute-point widget target in out))
        [(ffi/read out :float 0) (ffi/read out :float 4)])
      (finally (ffi/free in) (ffi/free out)))))
;; gtkpopover.h: gtk_popover_new:62, set_child:65, set_pointing_to:71,
;; get_pointing_to:74, set_position:77, set_autohide:83, popup:101,
;; popdown:103. All fixed arity.
(ffi/defcfn gtk-popover-new "gtk_popover_new" [] :pointer)
(ffi/defcfn gtk-popover-set-child "gtk_popover_set_child" [:pointer :pointer] :void)
(ffi/defcfn gtk-popover-set-pointing-to "gtk_popover_set_pointing_to" [:pointer :pointer] :void)
(ffi/defcfn gtk-popover-get-pointing-to "gtk_popover_get_pointing_to" [:pointer :pointer] :int)
(ffi/defcfn gtk-popover-set-position "gtk_popover_set_position" [:pointer :int] :void)
(ffi/defcfn gtk-popover-set-autohide "gtk_popover_set_autohide" [:pointer :int] :void)
(ffi/defcfn gtk-popover-popup "gtk_popover_popup" [:pointer] :void)
(ffi/defcfn gtk-popover-popdown "gtk_popover_popdown" [:pointer] :void)
;; GtkPositionType: gtkenums.h:489-495 (enum starts GTK_POS_LEFT=0,
;; RIGHT=1, TOP=2, BOTTOM=3).
(def POPOVER-POS-TOP 2)
(def POPOVER-POS-BOTTOM 3)
;; gtk_text_view_get_iter_location: gtktextview.h:241-243 -- void (...)
;; (GtkTextView*, const GtkTextIter*, GdkRectangle*). Fixed arity.
(ffi/defcfn gtk-text-view-get-iter-location "gtk_text_view_get_iter_location"
  [:pointer :pointer :pointer] :void)
;; gtk_text_view_buffer_to_window_coords: gtktextview.h:267-272 -- void (...)
;; (GtkTextView*, GtkTextWindowType, int buffer_x, int buffer_y, int*
;; window_x, int* window_y). Fixed arity.
(ffi/defcfn gtk-text-view-buffer-to-window-coords "gtk_text_view_buffer_to_window_coords"
  [:pointer :int :int :int :pointer :pointer] :void)
;; GtkTextWindowType: gtktextview.h:57-58 (WIDGET=1, TEXT=2, per the enum's
;; leading comment block at line 45).
(def TEXT-WINDOW-TEXT 2)
;; The inspector pane's double-click handler goes the OTHER
;; direction from caret-window-rect above -- a GtkGestureClick's own event
;; coordinates are in the rows view's WIDGET window (gtkgestureclick.c's
;; own doc comment: "in widget allocation coordinates"), translated to
;; buffer coordinates via gtk-text-view-window-to-buffer-coords, below.
(def TEXT-WINDOW-WIDGET 1)
;; GTK_TEXT_WINDOW_LEFT, the left border window a gutter widget fills
;; (gtktextview.h:57-62: WIDGET=1, TEXT, LEFT, RIGHT, TOP, BOTTOM).
(def TEXT-WINDOW-LEFT 3)
;; gtk_text_view_set_gutter (GtkTextView*, GtkTextWindowType, GtkWidget*)
;; and gtk_text_view_get_line_yrange (GtkTextView*, const GtkTextIter*,
;; int* y, int* height), y in buffer coordinates: gtktextview.h:256, 313.
(ffi/defcfn gtk-text-view-set-gutter "gtk_text_view_set_gutter" [:pointer :int :pointer] :void)
(ffi/defcfn gtk-text-view-get-line-yrange "gtk_text_view_get_line_yrange" [:pointer :pointer :pointer :pointer] :void)
;; GtkDrawingArea: gtkdrawingarea.h:62-66, 103. The draw func is
;; (GtkDrawingArea*, cairo_t*, int width, int height, gpointer).
(ffi/defcfn gtk-drawing-area-new "gtk_drawing_area_new" [] :pointer)
(ffi/defcfn gtk-drawing-area-set-draw-func "gtk_drawing_area_set_draw_func" [:pointer :pointer :pointer :pointer] :void)
(ffi/defcfn gtk-widget-queue-draw "gtk_widget_queue_draw" [:pointer] :void)
;; cairo, which GTK links, so its symbols resolve with GTK loaded (measured
;; 2026-11-02 on an image surface: a filled rectangle's pixels came back)
(ffi/defcfn cairo-set-source-rgb "cairo_set_source_rgb" [:pointer :double :double :double] :void)
(ffi/defcfn cairo-rectangle "cairo_rectangle" [:pointer :double :double :double :double] :void)
(ffi/defcfn cairo-fill "cairo_fill" [:pointer] :void)
;; cairo.h -- cairo_arc (cr, xc, yc, radius, angle1, angle2) and
;; cairo_new_sub_path (cr): the gutter's breakpoint dots (LM5)
(ffi/defcfn cairo-arc "cairo_arc" [:pointer :double :double :double :double :double] :void)
(ffi/defcfn cairo-new-sub-path "cairo_new_sub_path" [:pointer] :void)
;; cairo.h -- cairo_line_to (cr, x, y) and cairo_close_path (cr): the
;; gutter's arrow on the paused line (LM5's stepping)
(ffi/defcfn cairo-line-to "cairo_line_to" [:pointer :double :double] :void)
(ffi/defcfn cairo-close-path "cairo_close_path" [:pointer] :void)
(ffi/defcfn cairo-move-to "cairo_move_to" [:pointer :double :double] :void)
;; pango/pangocairo.h -- pango_cairo_show_layout (cairo_t*, PangoLayout*);
;; pangocairo comes with GTK, as cairo does (the gutter's line counts)
(ffi/defcfn pango-cairo-show-layout "pango_cairo_show_layout" [:pointer :pointer] :void)

(defn caret-window-rect
  "The insert mark's rectangle in view's TEXT-window coordinates:
  gtk_text_view_get_iter_location on the buffer's insert-mark iter
  (buffer coords), then gtk_text_view_buffer_to_window_coords with
  GTK_TEXT_WINDOW_TEXT. Returns [x y w h]. GdkRectangle is four ints (x y w
  h), 16 bytes."
  [view]
  (let [buf (gtk-text-view-get-buffer view)
        it (ffi/alloc ITER-BYTES)
        loc (ffi/alloc 16)
        out (ffi/alloc 8)]
    (try
      (gtk-text-buffer-get-iter-at-mark buf it (gtk-text-buffer-get-insert buf))
      (gtk-text-view-get-iter-location view it loc)
      (let [bx (ffi/read loc :int 0) by (ffi/read loc :int 4)
            w (ffi/read loc :int 8) h (ffi/read loc :int 12)]
        (gtk-text-view-buffer-to-window-coords view TEXT-WINDOW-TEXT bx by out (+ out 4))
        [(ffi/read out :int 0) (ffi/read out :int 4) w h])
      (finally (ffi/free it) (ffi/free loc) (ffi/free out)))))

;; gtk_text_view_get_visible_rect: gtktextview.h -- void
;; gtk_text_view_get_visible_rect (GtkTextView*, GdkRectangle*). Fixed
;; arity; the rectangle comes back in BUFFER coordinates (the visible
;; window of the buffer), the same space get_iter_location uses.
(ffi/defcfn gtk-text-view-get-visible-rect "gtk_text_view_get_visible_rect"
  [:pointer :pointer] :void)

(defn text-view-visible-rect
  "The buffer-coordinate rectangle of view's visible text, [x y w h]."
  [view]
  (let [loc (ffi/alloc 16)]
    (try
      (gtk-text-view-get-visible-rect view loc)
      [(ffi/read loc :int 0) (ffi/read loc :int 4)
       (ffi/read loc :int 8) (ffi/read loc :int 12)]
      (finally (ffi/free loc)))))

(defn caret-buffer-rect
  "The insert mark's rectangle in view's BUFFER coordinates, [x y w h]:
  caret-window-rect without the window translation, for comparing against
  text-view-visible-rect."
  [view]
  (let [buf (gtk-text-view-get-buffer view)
        it (ffi/alloc ITER-BYTES)
        loc (ffi/alloc 16)]
    (try
      (gtk-text-buffer-get-iter-at-mark buf it (gtk-text-buffer-get-insert buf))
      (gtk-text-view-get-iter-location view it loc)
      [(ffi/read loc :int 0) (ffi/read loc :int 4)
       (ffi/read loc :int 8) (ffi/read loc :int 12)]
      (finally (ffi/free it) (ffi/free loc)))))

(defn popover-pointing-to
  "Reads back popover's own pointing-to rectangle
  (gtk_popover_get_pointing_to) as [x y w h]."
  [popover]
  (let [r (ffi/alloc 16)]
    (try
      (gtk-popover-get-pointing-to popover r)
      [(ffi/read r :int 0) (ffi/read r :int 4) (ffi/read r :int 8) (ffi/read r :int 12)]
      (finally (ffi/free r)))))

;; --- GtkListBox (completion's candidate list) --------------------------------
;; gtklistbox.h (GTK 4.22.4, installed headers): new:261, append:174 ("Append a widget to the list", per
;; the Gtk-4.0.gir doc on gtklistbox.c:3028 -- GTK4 wraps a plain GtkWidget
;; child in its own GtkListBoxRow automatically, so nothing here ever binds
;; gtk_list_box_row_new/gtk_list_box_prepend/insert), remove_all:184
;; (GDK_AVAILABLE_IN_4_12; this GTK is 4.22.4, so it's present),
;; get_row_at_index:189, select_row:195, set_selection_mode:225. All fixed
;; arity.
(ffi/defcfn gtk-list-box-new "gtk_list_box_new" [] :pointer)
(ffi/defcfn gtk-list-box-append "gtk_list_box_append" [:pointer :pointer] :void)
(ffi/defcfn gtk-list-box-remove-all "gtk_list_box_remove_all" [:pointer] :void)
(ffi/defcfn gtk-list-box-get-row-at-index "gtk_list_box_get_row_at_index" [:pointer :int] :pointer)
(ffi/defcfn gtk-list-box-row-get-child "gtk_list_box_row_get_child" [:pointer] :pointer)
(ffi/defcfn gtk-list-box-select-row "gtk_list_box_select_row" [:pointer :pointer] :void)
(ffi/defcfn gtk-list-box-set-selection-mode "gtk_list_box_set_selection_mode" [:pointer :int] :void)
;; GtkSelectionMode: gtkenums.h:557-559 (NONE=0, SINGLE=1, BROWSE=2).
;; BROWSE (gtkenums.h:542: "Exactly one element is selected... the user
;; can't deselect a currently selected element except by selecting
;; another") matches completion's own always-one-highlighted-candidate UI;
;; nothing here ever selects by click, only gtk-list-box-select-row.
(def SELECTION-MODE-BROWSE 2)

;; --- context menus (GMenu/GSimpleActionGroup) and rename --------------------
;; Header citations checked against the installed headers
;; (gtk4 4.22.4, glib as installed by homebrew, 2026-09-26).
;;
;; glib-2.0/gio/gmenu.h:47 GMenu *g_menu_new (void); gmenu.h:79-81 void
;; g_menu_append (GMenu*, const gchar *label, const gchar
;; *detailed_action). Both fixed arity.
(ffi/defcfn g-menu-new "g_menu_new" [] :pointer)
(ffi/defcfn g-menu-append "g_menu_append" [:pointer :string :string] :void)
;; glib-2.0/gio/gsimpleactiongroup.h:70 GSimpleActionGroup *
;; g_simple_action_group_new (void). Fixed arity. GSimpleActionGroup
;; implements both GActionMap and GActionGroup, so the existing
;; g-action-map-add-action/g-action-group-activate-action bindings (the
;; quit-action citation above) already work on it unchanged -- no new binding
;; needed for either.
(ffi/defcfn g-simple-action-group-new "g_simple_action_group_new" [] :pointer)
;; gtk-4.0/gtk/gtktextview.h:429-430 void gtk_text_view_set_extra_menu
;; (GtkTextView*, GMenuModel*). Fixed arity; a GMenu* is a GMenuModel, so
;; g-menu-new's own return passes here unchanged.
(ffi/defcfn gtk-text-view-set-extra-menu "gtk_text_view_set_extra_menu" [:pointer :pointer] :void)
;; gtk-4.0/gtk/gtkwidget.h:864-866 void gtk_widget_insert_action_group
;; (GtkWidget*, const char *name, GActionGroup*). Fixed arity.
;; gtk_widget_activate_action (gtkwidget.h:869-872) is variadic (a
;; trailing `...` format-string argument) and is never bound here --
;; every activation goes through g_action_group_activate_action instead
;; (already bound above), called directly on the
;; GSimpleActionGroup with its own unprefixed action name, which measured
;; equivalent to gtk_widget_activate_action_variant on the
;; prefixed "p4.<name>" (gtkwidget.h:874-876, also fixed arity, but not
;; needed here since the direct GActionGroup call already covers it).
(ffi/defcfn gtk-widget-insert-action-group "gtk_widget_insert_action_group" [:pointer :string :pointer] :void)

;; --- the console TextView uses GTK_WRAP_WORD_CHAR rather than
;; GTK_WRAP_NONE, so a wide line never opens a horizontal scroll range for
;; scroll-to-end!'s xalign 0.0 to shift right into.
;;
;; gtk_text_view_set_wrap_mode: gtktextview.h:340-341 (gtk4 4.22.4,
;; installed headers):
;; void gtk_text_view_set_wrap_mode (GtkTextView*, GtkWrapMode). Fixed
;; arity.
(ffi/defcfn gtk-text-view-set-wrap-mode "gtk_text_view_set_wrap_mode" [:pointer :int] :void)
;; GtkWrapMode: gtkenums.h:577-583 (NONE=0, CHAR=1, WORD=2, WORD_CHAR=3) --
;; a different enum from PangoWrapMode above, where PANGO-WRAP-WORD-CHAR is
;; 2: reusing that constant here would silently select GTK_WRAP_WORD
;; instead of GTK_WRAP_WORD_CHAR.
(def GTK-WRAP-WORD-CHAR 3)

;; gtk_scrollable_get_hadjustment: gtkscrollable.h:51 -- GtkAdjustment
;; *gtk_scrollable_get_hadjustment (GtkScrollable*). GtkTextView implements
;; GtkScrollable directly, so the view itself is a valid argument.
;; gtk_adjustment_get_value: gtkadjustment.h:82 -- double
;; gtk_adjustment_get_value (GtkAdjustment*). Both fixed arity. Used by
;; the console-wraps-long-lines self-test row: with wrapping on, the
;; view's horizontal adjustment value must stay 0 even after a line wider
;; than the view is appended.
(ffi/defcfn gtk-scrollable-get-hadjustment "gtk_scrollable_get_hadjustment" [:pointer] :pointer)
(ffi/defcfn gtk-scrollable-get-vadjustment "gtk_scrollable_get_vadjustment" [:pointer] :pointer)
(ffi/defcfn gtk-adjustment-get-value "gtk_adjustment_get_value" [:pointer] :double)
;; gtk_adjustment_set_value: gtkadjustment.h:84-85 -- void
;; gtk_adjustment_set_value (GtkAdjustment*, double). Fixed arity. Used
;; by the source panel's user-scroll-wins self-test row: it sets the
;; vadjustment directly, the same effect a real scroll has, without
;; driving an actual GdkScroll event.
(ffi/defcfn gtk-adjustment-set-value "gtk_adjustment_set_value" [:pointer :double] :void)

;; --- the inspector pane --------------------------------------------------
;; Header lines checked against the installed headers (gtk4
;; 4.22.4, pango as installed by homebrew).
;;
;; gtkwidget.h:648-649 -- PangoLayout *gtk_widget_create_pango_layout
;; (GtkWidget*, const char *text). Fixed arity. measure (below) uses this
;; to lay out a fixed 10-char string and read its own pixel size back, the
;; only way to get a real character cell size out of a GtkTextView (which
;; has no "char width" property of its own).
(ffi/defcfn gtk-widget-create-pango-layout "gtk_widget_create_pango_layout" [:pointer :string] :pointer)
;; pango/pango-layout.h:343-346 -- void pango_layout_get_pixel_size
;; (PangoLayout*, int *width, int *height). Fixed arity.
(ffi/defcfn pango-layout-get-pixel-size "pango_layout_get_pixel_size" [:pointer :pointer :pointer] :void)

;; gtkgestureclick.h:44 -- GtkGesture *gtk_gesture_click_new (void). Fixed
;; arity. Its own "pressed" signal (gtkgestureclick.c's g_signal_new:
;; (GtkGestureClick*, gint n_press,
;; gdouble x, gdouble y, gpointer) -> void) is wired directly by
;; jonquil.gtk.inspector-pane, no binding needed for a signal.
(ffi/defcfn gtk-gesture-click-new "gtk_gesture_click_new" [] :pointer)
;; gtk_widget_get_clipboard (GtkWidget*) -> GdkClipboard*, and
;; gdk_clipboard_set_text (GdkClipboard*, const char*): gtkwidget.h,
;; gdkclipboard.h, both fixed arity.
(ffi/defcfn gtk-widget-get-clipboard "gtk_widget_get_clipboard" [:pointer] :pointer)
(ffi/defcfn gdk-clipboard-set-text "gdk_clipboard_set_text" [:pointer :string] :void)

;; gtktextview.h:275-280 -- void gtk_text_view_window_to_buffer_coords
;; (GtkTextView*, GtkTextWindowType, int window_x, int window_y, int
;; *buffer_x, int *buffer_y). The inverse of the already-bound
;; gtk-text-view-buffer-to-window-coords above. Fixed arity.
(ffi/defcfn gtk-text-view-window-to-buffer-coords "gtk_text_view_window_to_buffer_coords"
  [:pointer :int :int :int :pointer :pointer] :void)
;; gtktextview.h:245-248 -- gboolean gtk_text_view_get_iter_at_location
;; (GtkTextView*, GtkTextIter*, int x, int y). Fixed arity; the gboolean
;; return (whether x,y actually falls inside the buffer) is read as :int,
;; same convention as gtk-text-buffer-insert-interactive-at-cursor above.
(ffi/defcfn gtk-text-view-get-iter-at-location "gtk_text_view_get_iter_at_location"
  [:pointer :pointer :int :int] :int)

;; gtkpaned.h:69-71 / 75-77 -- void gtk_paned_set_resize_end_child
;; (GtkPaned*, gboolean) / void gtk_paned_set_shrink_end_child (GtkPaned*,
;; gboolean). Both fixed arity; jonquil.gtk.ffi already binds
;; gtk-paned-new/set-start-child/set-end-child/set-position but not
;; these -- for a caller that wants the inspector pane's own end of a
;; paned split to keep a fixed width instead of growing/shrinking with the
;; window.
(ffi/defcfn gtk-paned-set-resize-end-child "gtk_paned_set_resize_end_child" [:pointer :int] :void)
(ffi/defcfn gtk-paned-set-shrink-end-child "gtk_paned_set_shrink_end_child" [:pointer :int] :void)

;; gtkwindow.h:94 -- GtkWidget *gtk_window_new (void). Fixed arity, no
;; GtkApplication argument (unlike the already-bound
;; gtk-application-window-new) -- the self-test harness's own transient
;; top-level for measuring the inspector pane's real allocated size
;; outside the main Jonquil window.
(ffi/defcfn gtk-window-new "gtk_window_new" [] :pointer)

;; --- tabs --------------------------------------------------------------------
;; Header citations are under gtk-4.0/gtk (gtk4 4.22.4).
;; The switch-page and page-removed signal callbacks are
;; [:pointer :pointer :uint :pointer] :void.

;; gtknotebook.h:70 GtkWidget * gtk_notebook_new (void)
(ffi/defcfn gtk-notebook-new "gtk_notebook_new" [] :pointer)
;; gtknotebook.h:72 int gtk_notebook_append_page (GtkNotebook*, GtkWidget *child, GtkWidget *tab_label)
(ffi/defcfn gtk-notebook-append-page "gtk_notebook_append_page" [:pointer :pointer :pointer] :int)
;; gtknotebook.h:90 int gtk_notebook_insert_page (GtkNotebook*, GtkWidget*, GtkWidget*, int position)
(ffi/defcfn gtk-notebook-insert-page "gtk_notebook_insert_page" [:pointer :pointer :pointer :int] :int)
;; gtknotebook.h:101 void gtk_notebook_remove_page (GtkNotebook*, int page_num)
(ffi/defcfn gtk-notebook-remove-page "gtk_notebook_remove_page" [:pointer :int] :void)
;; gtknotebook.h:121 int gtk_notebook_get_current_page (GtkNotebook*)
(ffi/defcfn gtk-notebook-get-current-page "gtk_notebook_get_current_page" [:pointer] :int)
;; gtknotebook.h:123 GtkWidget* gtk_notebook_get_nth_page (GtkNotebook*, int)
(ffi/defcfn gtk-notebook-get-nth-page "gtk_notebook_get_nth_page" [:pointer :int] :pointer)
;; gtknotebook.h:126 int gtk_notebook_get_n_pages (GtkNotebook*)
(ffi/defcfn gtk-notebook-get-n-pages "gtk_notebook_get_n_pages" [:pointer] :int)
;; gtknotebook.h:128 int gtk_notebook_page_num (GtkNotebook*, GtkWidget *child)
(ffi/defcfn gtk-notebook-page-num "gtk_notebook_page_num" [:pointer :pointer] :int)
;; gtknotebook.h:131 void gtk_notebook_set_current_page (GtkNotebook*, int)
(ffi/defcfn gtk-notebook-set-current-page "gtk_notebook_set_current_page" [:pointer :int] :void)
;; gtknotebook.h:143 void gtk_notebook_set_show_border (GtkNotebook*, gboolean)
(ffi/defcfn gtk-notebook-set-show-border "gtk_notebook_set_show_border" [:pointer :int] :void)
;; gtknotebook.h:148 void gtk_notebook_set_show_tabs (GtkNotebook*, gboolean)
(ffi/defcfn gtk-notebook-set-show-tabs "gtk_notebook_set_show_tabs" [:pointer :int] :void)
;; gtknotebook.h:151 gboolean gtk_notebook_get_show_tabs (GtkNotebook*)
(ffi/defcfn gtk-notebook-get-show-tabs "gtk_notebook_get_show_tabs" [:pointer] :int)
;; gtknotebook.h:158 void gtk_notebook_set_scrollable (GtkNotebook*, gboolean)
(ffi/defcfn gtk-notebook-set-scrollable "gtk_notebook_set_scrollable" [:pointer :int] :void)
;; gtknotebook.h:180 void gtk_notebook_set_tab_label (GtkNotebook*, GtkWidget *child, GtkWidget *tab_label)
(ffi/defcfn gtk-notebook-set-tab-label "gtk_notebook_set_tab_label" [:pointer :pointer :pointer] :void)
;; gtknotebook.h:205 void gtk_notebook_reorder_child (GtkNotebook*, GtkWidget *child, int position)
(ffi/defcfn gtk-notebook-reorder-child "gtk_notebook_reorder_child" [:pointer :pointer :int] :void)
;; gtkbutton.h:88 void gtk_button_set_has_frame (GtkButton*, gboolean)
(ffi/defcfn gtk-button-set-has-frame "gtk_button_set_has_frame" [:pointer :int] :void)
;; gtkwidget.h:395 void gtk_widget_set_focus_on_click (GtkWidget*, gboolean)
(ffi/defcfn gtk-widget-set-focus-on-click "gtk_widget_set_focus_on_click" [:pointer :int] :void)
;; gtkwidget.h:928 gboolean gtk_widget_has_css_class (GtkWidget*, const char *css_class)
(ffi/defcfn gtk-widget-has-css-class "gtk_widget_has_css_class" [:pointer :string] :int)
;; gobject.h:513 gpointer g_object_ref (gpointer object). The tab rows hold a
;; popover alive across dispose-popovers!, whose unparent drops the last ref.
(ffi/defcfn g-object-ref "g_object_ref" [:pointer] :pointer)

;; --- file tree ---------------------------------------------------------------
;; Header citations under gtk-4.0/gtk and glib-2.0/gio
;; (gtk4 4.22.4).

;; gtklistbox.h:148 int gtk_list_box_row_get_index (GtkListBoxRow*)
(ffi/defcfn gtk-list-box-row-get-index "gtk_list_box_row_get_index" [:pointer] :int)
;; gtklistbox.h:187 GtkListBoxRow* gtk_list_box_get_selected_row (GtkListBox*)
(ffi/defcfn gtk-list-box-get-selected-row "gtk_list_box_get_selected_row" [:pointer] :pointer)
;; gtklistbox.h:222 void gtk_list_box_unselect_all (GtkListBox*)
(ffi/defcfn gtk-list-box-unselect-all "gtk_list_box_unselect_all" [:pointer] :void)
;; gtktogglebutton.h:71 GtkWidget* gtk_toggle_button_new_with_label (const char*)
(ffi/defcfn gtk-toggle-button-new-with-label "gtk_toggle_button_new_with_label" [:string] :pointer)
;; gtktogglebutton.h:75 void gtk_toggle_button_set_active (GtkToggleButton*, gboolean)
(ffi/defcfn gtk-toggle-button-set-active "gtk_toggle_button_set_active" [:pointer :int] :void)
;; gtktogglebutton.h:78 gboolean gtk_toggle_button_get_active (GtkToggleButton*)
(ffi/defcfn gtk-toggle-button-get-active "gtk_toggle_button_get_active" [:pointer] :int)
;; gtkpaned.h:81 int gtk_paned_get_position (GtkPaned*)
(ffi/defcfn gtk-paned-get-position "gtk_paned_get_position" [:pointer] :int)
;; gtkpaned.h:51 void gtk_paned_set_resize_start_child (GtkPaned*, gboolean)
(ffi/defcfn gtk-paned-set-resize-start-child "gtk_paned_set_resize_start_child" [:pointer :int] :void)
;; gtkpaned.h:63 void gtk_paned_set_shrink_start_child (GtkPaned*, gboolean)
(ffi/defcfn gtk-paned-set-shrink-start-child "gtk_paned_set_shrink_start_child" [:pointer :int] :void)
;; gtkfiledialog.h:71 void gtk_file_dialog_set_initial_folder (GtkFileDialog*, GFile*)
(ffi/defcfn gtk-file-dialog-set-initial-folder "gtk_file_dialog_set_initial_folder" [:pointer :pointer] :void)
;; gtkfiledialog.h:108 void gtk_file_dialog_select_folder (GtkFileDialog*, GtkWindow*, GCancellable*, GAsyncReadyCallback, gpointer)
(ffi/defcfn gtk-file-dialog-select-folder "gtk_file_dialog_select_folder" [:pointer :pointer :pointer :pointer :pointer] :void)
;; gtkfiledialog.h:115 GFile* gtk_file_dialog_select_folder_finish (GtkFileDialog*, GAsyncResult*, GError**)
(ffi/defcfn gtk-file-dialog-select-folder-finish "gtk_file_dialog_select_folder_finish" [:pointer :pointer :pointer] :pointer)
;; gio/gfile.h:611 GFile* g_file_new_for_path (const char*)
(ffi/defcfn g-file-new-for-path "g_file_new_for_path" [:string] :pointer)
;; gio/gfile.h:613 GFile* g_file_new_for_uri (const char*)
(ffi/defcfn g-file-new-for-uri "g_file_new_for_uri" [:string] :pointer)
;; gio/gfile.h:663 char* g_file_get_uri (GFile*); free the result with g_free
(ffi/defcfn g-file-get-uri* "g_file_get_uri" [:pointer] :pointer)

;; --- file tree operations ---
;; gio/gfile.h:917 gboolean g_file_trash (GFile*, GCancellable*, GError**)
(ffi/defcfn g-file-trash "g_file_trash" [:pointer :pointer :pointer] :int)
;; gtk/gtkgesturesingle.h:61 void gtk_gesture_single_set_button (GtkGestureSingle*, guint)
(ffi/defcfn gtk-gesture-single-set-button "gtk_gesture_single_set_button" [:pointer :uint] :void)
;; gtk/gtklistbox.h:192 GtkListBoxRow* gtk_list_box_get_row_at_y (GtkListBox*, gint)
(ffi/defcfn gtk-list-box-get-row-at-y "gtk_list_box_get_row_at_y" [:pointer :int] :pointer)

;; --- reopen after the first frame ---
;; gtk_widget_get_frame_clock is NULL until the widget is realized, so it's
;; read after gtk_window_present. Its "after-paint" signal is how startup
;; tells the window's first frame has been drawn.
(ffi/defcfn gtk-widget-get-frame-clock "gtk_widget_get_frame_clock" [:pointer] :pointer)
(ffi/defcfn g-signal-handler-disconnect "g_signal_handler_disconnect" [:pointer :ulong] :void)
