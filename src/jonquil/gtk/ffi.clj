(ns jonquil.gtk.ffi
  "Raw GTK4/GLib/GObject/pango bindings for Jonquil. Lifted from liquid-jlt's liq.gtk.ffi and extended by the P0 S2/S4 spikes and the P2 plan probe.
  layer patterned on glimmer's glimmer.ffi (same idioms: pointers are jolt
  numbers, signal handlers are :collect-safe foreign-callables because GTK
  invokes them from inside the :blocking g_application_run loop).

  ABI rule (AGENTS.md): no variadic C functions. That excludes
  g_object_set and gtk_text_buffer_create_tag — properties go through the
  fixed-arity GValue path (g_value_init/set_string +
  g_object_set_property), fonts/colors through a CSS provider.

  GtkTextIter is an opaque struct the CALLER allocates and GTK fills by
  pointer (~80 bytes on 64-bit; we allocate 128 for headroom) — no
  by-value passing anywhere, so no C shim."
  (:require [jolt.ffi :as ffi]))

;; --- constants ---------------------------------------------------------------
(def APPLICATION-DEFAULT-FLAGS 0)
(def CONNECT-DEFAULT 0)
(def GTK-PHASE-CAPTURE 1)          ; GtkPropagationPhase
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
(ffi/defcfn gtk-fixed-new "gtk_fixed_new" [] :pointer)
(ffi/defcfn gtk-fixed-put "gtk_fixed_put" [:pointer :pointer :double :double] :void)     ; fixed, widget, x, y
(ffi/defcfn gtk-fixed-move "gtk_fixed_move" [:pointer :pointer :double :double] :void)   ; reposition an existing child
(ffi/defcfn gtk-fixed-remove "gtk_fixed_remove" [:pointer :pointer] :void)               ; fixed, widget
(ffi/defcfn gtk-separator-new "gtk_separator_new" [:int] :pointer)                       ; GtkOrientation

;; --- labels (minibuffer / status line) ---------------------------------------
(ffi/defcfn gtk-label-new "gtk_label_new" [:string] :pointer)
(ffi/defcfn gtk-label-set-text "gtk_label_set_text" [:pointer :string] :void)
(ffi/defcfn gtk-label-set-xalign "gtk_label_set_xalign" [:pointer :float] :void)

;; --- text view / buffer ------------------------------------------------------
(ffi/defcfn gtk-text-view-new "gtk_text_view_new" [] :pointer)
(ffi/defcfn gtk-text-view-get-buffer "gtk_text_view_get_buffer" [:pointer] :pointer)
(ffi/defcfn gtk-text-view-set-editable "gtk_text_view_set_editable" [:pointer :int] :void)
(ffi/defcfn gtk-text-view-set-cursor-visible "gtk_text_view_set_cursor_visible" [:pointer :int] :void)
(ffi/defcfn gtk-text-view-set-monospace "gtk_text_view_set_monospace" [:pointer :int] :void)
(ffi/defcfn gtk-text-view-scroll-mark-onscreen "gtk_text_view_scroll_mark_onscreen" [:pointer :pointer] :void)

(ffi/defcfn gtk-text-buffer-set-text "gtk_text_buffer_set_text" [:pointer :string :int] :void)
(ffi/defcfn gtk-text-buffer-get-start-iter "gtk_text_buffer_get_start_iter" [:pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-get-end-iter "gtk_text_buffer_get_end_iter" [:pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-get-iter-at-offset "gtk_text_buffer_get_iter_at_offset" [:pointer :pointer :int] :void)
(ffi/defcfn gtk-text-buffer-place-cursor "gtk_text_buffer_place_cursor" [:pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-get-insert "gtk_text_buffer_get_insert" [:pointer] :pointer)
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

;; --- S2 additions: an EDITABLE text view -----------------------------------
;; Signatures checked against /opt/homebrew/include/gtk-4.0 (gtk4 4.22.4).
(def G-TYPE-INT (* 6 4))           ; fundamental GType: 6 << 2
(def PANGO-WEIGHT-BOLD 700)

(ffi/defcfn g-free "g_free" [:pointer] :void)
(ffi/defcfn g-value-set-int "g_value_set_int" [:pointer :int] :void)
(ffi/defcfn gtk-text-buffer-get-text* "gtk_text_buffer_get_text" [:pointer :pointer :pointer :int] :pointer)
(ffi/defcfn gtk-text-buffer-insert "gtk_text_buffer_insert" [:pointer :pointer :string :int] :void)
(ffi/defcfn gtk-text-buffer-insert-interactive-at-cursor "gtk_text_buffer_insert_interactive_at_cursor" [:pointer :string :int :int] :int)
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

;; --- S4: async file dialogs (GTK 4.10+) --------------------------------------
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

(defn finish-file
  "Completes an open/save. Returns {:path s} or {:error msg :code n}.
  GError is {GQuark domain; gint code; gchar *message}: code at byte 4,
  message pointer at byte 8."
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
                    :code (ffi/read err :int 4)}
                   (finally (g-error-free err)))))
          (try (let [p (g-file-get-path* file)]
                 (try {:path (ffi/ptr->string p)} (finally (g-free p))))
               (finally (g-object-unref file)))))
      (finally (ffi/free slot)))))

;; --- P2 probe additions ---
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

;; --- P2: the console prompt marks ---
(ffi/defcfn gtk-text-buffer-create-mark "gtk_text_buffer_create_mark" [:pointer :string :pointer :int] :pointer)
(ffi/defcfn gtk-text-buffer-move-mark "gtk_text_buffer_move_mark" [:pointer :pointer :pointer] :void)
(ffi/defcfn gtk-text-view-scroll-to-mark "gtk_text_view_scroll_to_mark" [:pointer :pointer :double :int :double :double] :void)

;; --- P2.T5: reading a tag's style back ---
;; Signatures checked against /opt/homebrew/include/glib-2.0/gobject
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

;; --- P2.T6 ---
;; gtk_text_buffer_insert_interactive: gtk/gtktextbuffer.h:221-225 (gtk4
;; 4.22.4, /opt/homebrew/Cellar/gtk4/4.22.4/include/gtk-4.0/gtk/gtktextbuffer.h).
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
