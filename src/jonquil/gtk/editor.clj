(ns jonquil.gtk.editor
  "The Definitions editor: an editable, monospace GtkTextView owned by GTK,
  wired to jonquil.paint for live highlighting and jonquil.edit for smart
  Enter (global.md: the native widget owns the text; the core only ever
  sees a snapshot and returns edits, applied back to front inside one
  gtk_text_buffer_begin/end_user_action).

  One GtkTextTag per paint/style key. Typing debounces through a 20ms
  g_timeout_add tick: it repaints (remove all tags, reapply the paint plan)
  once 60ms have passed since the buffer's last `changed` signal, and on
  every tick it also recomputes dirty? against the last-saved text and
  fires on-dirty-changed! exactly on a flip. dirty? itself is computed live
  from the buffer text vs the saved text, so it reads correctly the instant
  set-text!/mark-saved! runs, without waiting on the tick -- and because
  repaint! only ever touches tags, never buffer text, a repaint can never
  itself flip dirty?.

  Every GTK call here already runs on the GTK thread: signal emission and
  g_timeout_add callbacks are both delivered by GTK's own main loop, so
  nothing in this namespace needs jonquil.gtk.callbacks/on-gui! to get
  there."
  (:require [jolt.ffi :as ffi]
            [jonquil.edit :as edit]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.paint :as paint]))

;; --- tags ----------------------------------------------------------------

(defn- tag-key-name [k]
  (if (vector? k) (str "rainbow-" (second k)) (name k)))

(defn- make-tags!
  "One GtkTextTag per paint/style key: every paint/token-styles key,
  [:rainbow 0..6], and :unmatched. :bold sets weight 700; :italic goes
  through set-italic! (the S2 italic gap the p2c probe closed)."
  [buf]
  (let [table (g/gtk-text-buffer-get-tag-table buf)
        ks (concat (keys paint/token-styles)
                   (map (fn [i] [:rainbow i]) (range (count paint/rainbow)))
                   [:unmatched])]
    (into {}
          (map (fn [k]
                 (let [{:keys [fg bold italic]} (paint/style k)
                       tag (g/gtk-text-tag-new (tag-key-name k))]
                   (g/set-string-property! tag "foreground" fg)
                   (when bold (g/set-int-property! tag "weight" g/PANGO-WEIGHT-BOLD))
                   (when italic (g/set-italic! tag))
                   (g/gtk-text-tag-table-add table tag)
                   [k tag])))
          ks)))

;; --- reading and writing the buffer ---------------------------------------

(defn editor-text
  "The editor's whole buffer as a string."
  [ed]
  (g/buffer-text (:buf ed)))

(defn mark-saved!
  "Records the current text as saved, so dirty? reads false until the next
  change."
  [ed]
  (reset! (:saved-text ed) (editor-text ed))
  nil)

(defn set-text!
  "Replaces the whole buffer inside begin/end_irreversible_action, so it
  leaves no undo step, and records s as the saved text in the same call --
  before the next tick can run -- so set-text! never itself marks dirty or
  fires on-dirty-changed!."
  [ed s]
  (let [buf (:buf ed)]
    (g/gtk-text-buffer-begin-irreversible-action buf)
    (g/gtk-text-buffer-set-text buf s -1)
    (g/gtk-text-buffer-end-irreversible-action buf)
    (reset! (:saved-text ed) s))
  nil)

(defn dirty?
  "True when the buffer's text differs from the last-saved text. Computed
  live against the buffer on every call, never cached, so it is correct
  immediately after set-text!/mark-saved! and can't be fooled by a
  tag-only repaint."
  [ed]
  (not= (editor-text ed) @(:saved-text ed)))

(defn on-dirty-changed!
  "Registers f to be called with the new dirty? value, on the GTK thread,
  whenever dirty? flips. Multiple registrations are supported; all fire in
  registration order."
  [ed f]
  (swap! (:on-dirty-fns ed) conj f)
  nil)

(defn focus! [ed]
  (g/gtk-widget-grab-focus (:view ed))
  nil)

;; --- snapshot / apply-op!, from the S2 spike ------------------------------

(defn snapshot
  "{:text :caret :sel-start :sel-end} for the core edit fns in jonquil.edit."
  [ed]
  (let [buf (:buf ed)
        [a b] (g/selection-offsets buf)]
    {:text (g/buffer-text buf) :caret (g/cursor-offset buf) :sel-start a :sel-end b}))

(defn apply-op!
  "Applies an edit-op result inside one user action, back to front, then
  places the caret. Returns true when the op applied, nil when result was
  nil."
  [ed result]
  (when result
    (let [buf (:buf ed)]
      (g/gtk-text-buffer-begin-user-action buf)
      (try
        (doseq [[s e r] (rseq (vec (:edits result)))]
          (g/with-iters* buf s e (fn [a b] (g/gtk-text-buffer-delete buf a b)))
          (g/with-iters* buf s s (fn [a _] (g/gtk-text-buffer-insert buf a r -1))))
        (finally (g/gtk-text-buffer-end-user-action buf)))
      (g/with-iters* buf (:caret result) (:caret result)
        (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
    true))

(defn handle-return!
  "Applies jonquil.edit/smart-enter to the current snapshot as one user
  action. Returns nil, applying nothing, while an IME preedit composition
  is active (I5): the capture-phase key controller runs before the IM
  context gets to filter the key, so consuming Return here would split or
  lose a pending Hangul/CJK composition. on-key (below) treats this the
  same as any other unhandled key: 0, so GTK/the IM context gets it."
  [ed]
  (when-not @(:preedit-active? ed)
    (apply-op! ed (edit/smart-enter (snapshot ed)))))

;; --- repaint + the debounce/dirty tick -------------------------------------

(defn- repaint! [ed]
  (let [{:keys [buf tags]} ed
        text (g/buffer-text buf)
        plan (paint/paint-plan text)]
    (g/with-iters* buf 0 (count text) (fn [a b] (g/gtk-text-buffer-remove-all-tags buf a b)))
    (doseq [[k s e] plan]
      (when-let [tag (get tags k)]
        (g/with-iters* buf s e (fn [a b] (g/gtk-text-buffer-apply-tag buf tag a b)))))))

(defn- tick! [ed]
  (let [cur (dirty? ed)
        last-dirty? (:last-dirty? ed)]
    (when (not= cur @last-dirty?)
      (reset! last-dirty? cur)
      (doseq [f @(:on-dirty-fns ed)] (f cur))))
  (when-let [t @(:dirty-at ed)]
    (when (>= (- (System/currentTimeMillis) t) 60)
      (reset! (:dirty-at ed) nil)
      (repaint! ed)))
  1)

;; --- keys: Return/KP-Enter with no Shift/Control/Meta ---------------------

(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-KP-ENTER 0xff8d)

(defn- plain-return?
  "Return or KP-Enter with none of Shift, Control or Meta held (gtk-keys.md:
  a command chord is Control or Meta; Shift+Return is left alone so GTK
  inserts a plain newline, per global.md)."
  [keyval state]
  (and (contains? #{KEY-RETURN KEY-KP-ENTER} keyval)
       (zero? (bit-and state (bit-or g/SHIFT-MASK g/CONTROL-MASK g/META-MASK)))))

(defn- on-key [ed]
  (fn [_ctl keyval _keycode state _data]
    (if (and (plain-return? keyval state) (handle-return! ed))
      1
      0)))

;; --- construction ----------------------------------------------------------

(defn make-editor
  "Builds the Definitions editor: an editable, monospace GtkTextView with
  an 8px left / 4px top margin and undo enabled, inside a
  GtkScrolledWindow. Returns {:view :buf :scroll} plus internal
  bookkeeping keys."
  []
  (let [view (g/gtk-text-view-new)
        buf (g/gtk-text-view-get-buffer view)
        scroll (g/gtk-scrolled-window-new)
        keyctl (g/gtk-event-controller-key-new)
        ed {:view view
            :buf buf
            :scroll scroll
            :tags (make-tags! buf)
            :saved-text (atom "")
            :last-dirty? (atom false)
            :on-dirty-fns (atom [])
            :dirty-at (atom nil)
            :preedit-active? (atom false)}]
    (g/gtk-text-view-set-editable view 1)
    (g/gtk-text-view-set-monospace view 1)
    (g/gtk-text-view-set-left-margin view 8)
    (g/gtk-text-view-set-top-margin view 4)
    (g/gtk-text-buffer-set-enable-undo buf 1)
    (g/gtk-scrolled-window-set-child scroll view)
    (g/g-signal-connect-data buf "changed"
                             (callable (fn [_buf _data]
                                         (reset! (:dirty-at ed) (System/currentTimeMillis))
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; I5: GtkTextView's OWN "preedit-changed" signal (distinct from
    ;; GtkIMContext's identically-named one) -- gtk/gtktextview.c:1529 per
    ;; Gtk-4.0.gir (not declared in the public gtktextview.h header; GTK
    ;; signals are registered at runtime and documented via GIR/gtk-doc,
    ;; not exposed as C symbols to grep for). Callback signature (instance,
    ;; gchar* preedit, user_data): [:pointer :string :pointer] :void.
    (g/g-signal-connect-data view "preedit-changed"
                             (callable (fn [_view preedit _data]
                                         (reset! (:preedit-active? ed) (not (empty? preedit)))
                                         nil)
                                       [:pointer :string :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
    (g/g-signal-connect-data keyctl "key-pressed"
                             (callable (on-key ed) [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller view keyctl)
    (g/g-timeout-add 20 (callable (fn [_data] (tick! ed)) [:pointer] :int) ffi/null)
    ed))
