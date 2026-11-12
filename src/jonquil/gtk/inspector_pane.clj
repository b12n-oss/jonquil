(ns jonquil.gtk.inspector-pane
  "The inspector panel widget: a header, an info line, a toolbar
  (Back/Meta/Table/Def/Close), a monospace table-header line, a read-only
  monospace rows view in a scrolled window, and a footer -- the console's
  own widget kind, not a GtkListBox.

  make-pane never calls jonquil.inspect or talks to any runtime itself: it
  only renders whatever state show-state! is given and turns a key or
  button press into either a local repaint (jonquil.inspect/key-step's
  :state, shown at once) or a request handed to on-request. The window
  owns the actual inspect! round trip and jonquil.inspect/on-reply.

  Every signal handler body below (key, click, timeout) is wrapped in
  try/catch that reports the message into the pane's own footer -- the
  FFI-boundary rule: an uncaught throw from a foreign-callable kills the
  process."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.inspect :as inspect]
            [jonquil.theme :as theme]))

;; --- keys (gdkkeysyms.h, GTK 4.22.4 installed headers; re-verified
;; directly against the header, same citation style as jonquil.gtk.window
;; and jonquil.gtk.editor's own key tables) -----------------------------
(def ^:private KEY-BACKSPACE 0xff08)
(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-ESCAPE 0xff1b)
(def ^:private KEY-LEFT 0xff51)
(def ^:private KEY-UP 0xff52)
(def ^:private KEY-RIGHT 0xff53)
(def ^:private KEY-DOWN 0xff54)
(def ^:private KEY-PAGE-UP 0xff55)
(def ^:private KEY-PAGE-DOWN 0xff56)
(def ^:private KEY-D 0x064)
(def ^:private KEY-M 0x06d)
;; [ and ]: the history of inspected objects (window chords on them all
;; need Cmd/Ctrl, so the plain keys are the rows view's)
(def ^:private KEY-BRACKETLEFT 0x05b)
(def ^:private KEY-BRACKETRIGHT 0x05d)
(def ^:private KEY-V 0x076)

(def ^:private WIDTH-REQUEST
  ;; estimate: about 40 monospace columns at the default zoom.
  360)

(def ^:private NOTE-CLEAR-MS
  "How long a reply's :note/:defd stays in the footer before the display
  drops it again (row-pane-note-clears)."
  3000)

(def ^:private DEF-ENTRY-WIDTH
  ;; Same width class as help-popover's own RENAME-WIDTH.
  240)

;; --- rendering -----------------------------------------------------------

(defn- footer-text
  "state's footer position text plus a trailing :note or :defd, joined with
  a wide gap so the two reasons for a message never run together."
  [state]
  (let [base (inspect/footer state)
        extra (or (:note state) (:defd state))]
    (cond
      (and extra (seq base)) (str base "  " extra)
      extra extra
      :else base)))

(defn- set-footer-text!
  [pane state]
  (g/gtk-label-set-text (:footer pane) (footer-text state)))

(defn- report-error!
  "Puts msg in pane's own footer -- every signal handler here reports a
  throw this way instead of letting it escape into GTK's own signal
  emission (which kills the process)."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) (str "; " msg))
  nil)

(defn apply-theme!
  "Sets the selected row's tag background to theme t's :selection. Only
  ever touches the tag's colour, never the rows or the selection."
  [pane t]
  (g/set-string-property! (:selected-tag pane) "background" (:selection t))
  nil)

(def ^:private NOTE-POLL-MS
  ;; estimate: frequent enough that a note's clear lands close to its own
  ;; NOTE-CLEAR-MS deadline, far enough apart not to spin the main loop.
  100)

(defn- arm-note-clear!
  "Pushes pane's own note-clear deadline NOTE-CLEAR-MS out from now, and
  arms its single repeating note-clear-cb (built once in make-pane, per
  jonquil.gtk.window.run/make-flash-fn's own pattern -- jonquil.gtk.callbacks
  retains every callable for the process's life, so a fresh one per note
  would leak) if it isn't already running. The callback itself (below)
  polls the shared deadline, so a LATER note/def pushing it out can't
  have an EARLIER one's own already-armed timer clear it early -- the
  bug a fresh callable per note, each checking identical? against a
  single shared target, would have had."
  [pane]
  (reset! (:note-clear-deadline pane) (+ (System/nanoTime) (* NOTE-CLEAR-MS 1000000)))
  (when (compare-and-set! (:note-clear-armed? pane) false true)
    (g/g-timeout-add NOTE-POLL-MS (:note-clear-cb pane) ffi/null))
  nil)

(defn- mark-selected!
  "Clears any previous `selected` tagging over buf's whole text, then
  re-applies it to line selected's own [start end) span -- never on a
  leaf, never past the last line."
  [pane lines selected leaf?]
  (let [buf (:buf pane) tag (:selected-tag pane)
        n (g/gtk-text-buffer-get-char-count buf)]
    (g/with-iters* buf 0 n (fn [a b] (g/gtk-text-buffer-remove-tag buf tag a b)))
    (when (and (not leaf?) (seq lines) selected (<= 0 selected) (< selected (count lines)))
      (let [start (reduce + 0 (map #(inc (count %)) (take selected lines)))
            end (+ start (count (nth lines selected)))]
        (g/with-iters* buf start end (fn [a b] (g/gtk-text-buffer-apply-tag buf tag a b)))))))

(defn- render!
  "The full repaint: header/info/table-header, the rows-or-leaf buffer
  text, the selected-row tag, the footer, and the toolbar's sensitivity."
  [pane state]
  (let [v (:view state)
        leaf? (contains? v :leaf)
        lines (:entries v)
        header-text (:header v)]
    (g/gtk-label-set-text (:header pane) (or (:path v) ""))
    (g/gtk-label-set-text (:info pane) (or (:info v) ""))
    (g/gtk-label-set-text (:table-header pane) (or header-text ""))
    (g/gtk-widget-set-visible (:table-header pane) (if (seq header-text) 1 0))
    (g/gtk-text-buffer-set-text (:buf pane) (if leaf? (or (:leaf v) "") (str/join "\n" lines)) -1)
    (mark-selected! pane lines (:selected state) leaf?)
    (set-footer-text! pane state)
    (doseq [button [(:back-btn pane) (:earlier-btn pane) (:later-btn pane) (:meta-btn pane) (:table-btn pane) (:def-btn pane)]]
      (g/gtk-widget-set-sensitive button (if (:disabled? state) 0 1)))))

(defn show-state!
  "Renders a jonquil.inspect pane state into pane and remembers it as
  pane's own current state, so the next local key/click steps from
  exactly what's on screen. GTK-thread only."
  [pane state]
  (reset! (:state pane) state)
  (render! pane state)
  (when (or (:note state) (:defd state))
    (arm-note-clear! pane))
  nil)

;; --- stepping: key-step/click-step, always shown at once ------------------

(defn- apply-step!
  "Runs one jonquil.inspect/key-step from state. A local move is shown at
  once. A step with a request is forwarded to pane's on-request, and its
  :state (the :pending rule the reply will follow) is committed only when
  on-request reports the request was sent: a step dropped because another
  request is in flight must not rewrite that request's :pending."
  [pane state key]
  (let [{step-state :state request :request} (inspect/key-step state key)]
    (if request
      (when ((:on-request pane) request)
        (show-state! pane step-state))
      (show-state! pane step-state))))

(defn- step!
  [pane key]
  (apply-step! pane @(:state pane) key))

;; --- the def prompt ---------------------------------------------------

(defn- open-def-prompt!
  "Opens the def prompt, unless the panel's actions are disabled (a lost
  connection): the def button is insensitive then, and d did the same
  nothing."
  [pane]
  (when-not (:disabled? @(:state pane))
    (let [dp (:def-popover pane)]
      (g/gtk-editable-set-text (:entry dp) "")
      (popover/show-at-caret! dp)
      (g/gtk-widget-grab-focus (:entry dp))))
  nil)

;; --- keys ---------------------------------------------------------------

(def ^:private key-steps
  "The keys that go through step!, each to its jonquil.inspect/key-step
  step."
  {KEY-UP :up KEY-DOWN :down
   KEY-RETURN :in KEY-RIGHT :in
   KEY-LEFT :out KEY-BACKSPACE :out
   KEY-PAGE-UP :page-up KEY-PAGE-DOWN :page-down
   KEY-M :meta KEY-V :table
   KEY-BRACKETLEFT :earlier KEY-BRACKETRIGHT :later})

(defn handle-key!
  "The rows view's own key dispatch (bubble phase, attached to the
  rows view itself, not the window). Up/Down/Return/Right/Left/BackSpace/
  Page_Up/Page_Down/m/v/[/] all go through jonquil.inspect/key-step via step!;
  d opens the def prompt; Escape calls on-close. Returns 1 when handled, 0
  otherwise (a self-test row can call this directly with a synthetic
  keyval, exactly as jonquil.gtk.editor/handle-key! is called elsewhere)."
  [pane keyval]
  (if-let [k (key-steps keyval)]
    (do (step! pane k) 1)
    (cond
      (= keyval KEY-ESCAPE) (do ((:on-close pane)) 1)
      (= keyval KEY-D) (do (open-def-prompt! pane) 1)
      :else 0)))

(defn dispatch-key!
  "Wraps handle-key! so a throw is reported into pane's own footer and the
  key still counts as consumed (1), instead of escaping into GTK's own
  signal emission. on-key (below) is the real \"key-pressed\" callback and
  always calls this."
  [pane keyval]
  (try
    (handle-key! pane keyval)
    (catch :default e
      (report-error! pane (ex-message e))
      1)))

(defn- on-key [pane]
  (fn [_ctl keyval _keycode _state _data]
    (dispatch-key! pane keyval)))

;; --- double click ---------------------------------------------------------

(defn- click-row!
  "n_press 2 on the rows view: translates the gesture's own widget-relative
  x/y into buffer coordinates and an iter, takes its line as the clicked
  row (clamped into range), and steps :in from there."
  [pane wx wy]
  (let [view (:view pane)
        coords (ffi/alloc 8)]
    (try
      (g/gtk-text-view-window-to-buffer-coords view g/TEXT-WINDOW-WIDGET
                                               (int wx) (int wy) coords (+ coords 4))
      (let [bx (ffi/read coords :int 0) by (ffi/read coords :int 4)
            it (ffi/alloc g/ITER-BYTES)]
        (try
          (g/gtk-text-view-get-iter-at-location view it bx by)
          (let [line (g/gtk-text-iter-get-line it)
                entries (:entries (:view @(:state pane)))
                n (count entries)
                row (if (pos? n) (min (max line 0) (dec n)) 0)]
            (apply-step! pane (assoc @(:state pane) :selected row) :in))
          (finally (ffi/free it))))
      (finally (ffi/free coords)))))

;; --- measure / focus -----------------------------------------------------

(defn measure
  "The rows view's own size in character cells: one \"0000000000\" pango
  layout's pixel size gives a single character's width and a line's
  height (gtk-widget-create-pango-layout + pango-layout-get-pixel-size,
  g_object_unref'd after); the view's own allocated pixel size divided by
  those gives :cols/:rows. GTK-thread only."
  [pane]
  (let [view (:view pane)
        layout (g/gtk-widget-create-pango-layout view "0000000000")
        out (ffi/alloc 8)]
    (try
      (g/pango-layout-get-pixel-size layout out (+ out 4))
      (let [char-width (max 1 (quot (ffi/read out :int 0) 10))
            line-height (max 1 (ffi/read out :int 4))
            view-width (g/gtk-widget-get-width view)
            view-height (g/gtk-widget-get-height view)]
        {:cols (max 20 (dec (quot view-width char-width)))
         :rows (max 5 (quot view-height line-height))})
      (finally (ffi/free out) (g/g-object-unref layout)))))

(def ^:private ALLOCATE-WAIT-MS
  ;; The cap: long enough for GTK's own layout pass to run, short
  ;; enough that a stuck layout doesn't leave the first open waiting
  ;; forever.
  1000)

(def ^:private ALLOCATE-POLL-MS
  ;; estimate: frequent enough to catch the layout pass promptly.
  16)

(defn allocated?
  "True once a layout pass has run over the pane since it was last shown:
  the pane's root has a positive width and the rows view a positive width
  and height. The root is checked because hiding a widget zeroes only its
  OWN allocation, not its children's, so after a close the rows view
  still reports its old size; on a reopen that stale size would be
  measured before GTK lays the panel out at its new width. Callers hide
  and show the pane through its root. GTK-thread only."
  [pane]
  (let [view (:view pane)]
    (and (pos? (g/gtk-widget-get-width (:root pane)))
         (pos? (g/gtk-widget-get-width view))
         (pos? (g/gtk-widget-get-height view)))))

(defn measure-when-ready!
  "Calls (f (measure pane)) once the rows view is allocated, or after
  ALLOCATE-WAIT-MS, whichever comes first: the first open in a
  window makes the root visible and would otherwise measure in that
  SAME callback, before GTK's own layout pass has ever run over it, so
  gtk-widget-get-width/height read 0 and measure's own floors
  (:cols 20 :rows 5) apply. A reopen after a close waits the same way:
  the rows view keeps its pre-close size until that pass runs (see
  allocated?). Already allocated (an open while the panel is already
  showing): calls f at once, synchronously. Otherwise polls with a
  repeating g_timeout_add -- GTK-thread only, never parks -- and falls
  back to measure's own floors once the cap is reached, so f is always
  called exactly once either way (the caller's in-flight flag
  is released through f's own request, on every path, including this
  fallback). The poll is the pane's one ready-cb (built in make-pane):
  a wait queues f, and only the first queued wait arms the timer, whose
  cap counts from then."
  [pane f]
  (if (allocated? pane)
    (f (measure pane))
    (do (swap! (:ready-waiters pane) conj f)
        (when (compare-and-set! (:ready-armed? pane) false true)
          (reset! (:ready-start pane) (System/nanoTime))
          (g/g-timeout-add ALLOCATE-POLL-MS (:ready-cb pane) ffi/null))))
  nil)

(defn- ready-tick
  "One poll of measure-when-ready!'s wait: once the pane is allocated or
  the cap has passed, measures once and calls every queued f with it,
  then answers 0 (G_SOURCE_REMOVE); else 1 (G_SOURCE_CONTINUE). A throw
  from one f is reported and doesn't skip the rest; a throw from measure
  is reported and every f gets measure's own floors, so none is dropped."
  [pane]
  (if (or (allocated? pane)
          (>= (/ (- (System/nanoTime) @(:ready-start pane)) 1e6) ALLOCATE-WAIT-MS))
    (let [m (try (measure pane)
                 (catch :default e
                   (report-error! pane (ex-message e))
                   {:cols 20 :rows 5}))
          fs (first (reset-vals! (:ready-waiters pane) []))]
      (reset! (:ready-armed? pane) false)
      (doseq [f fs]
        (try (f m) (catch :default e (report-error! pane (ex-message e)))))
      0)
    1))

(defn focus!
  [pane]
  (g/gtk-widget-grab-focus (:view pane))
  nil)

(defn focused?
  [pane win]
  (= (:view pane) (g/gtk-root-get-focus win)))

;; --- construction ----------------------------------------------------------

(defn- guarded-click
  "Wraps a no-arg thunk as a \"clicked\" callback (2-pointer signature)
  whose body is reported into pane's own footer instead of escaping."
  [pane f]
  (fn [_button _data]
    (try (f) (catch :default e (report-error! pane (ex-message e))))
    nil))

(defn- toolbar-buttons
  "The seven toolbar buttons, returned by name."
  []
  {:back-btn (g/gtk-button-new-with-label "Back")
   :earlier-btn (g/gtk-button-new-with-label "Earlier")
   :later-btn (g/gtk-button-new-with-label "Later")
   :meta-btn (g/gtk-button-new-with-label "Meta")
   :table-btn (g/gtk-button-new-with-label "Table")
   :def-btn (g/gtk-button-new-with-label "Def")
   :close-btn (g/gtk-button-new-with-label "Close")})

(defn- inspector-widgets
  "Creates the panel's GTK widgets, returned by name."
  []
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        header (g/gtk-label-new "")
        info (g/gtk-label-new "")
        toolbar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        table-header (g/gtk-label-new "")
        view (g/gtk-text-view-new)
        buf (g/gtk-text-view-get-buffer view)
        scroll (g/gtk-scrolled-window-new)
        footer (g/gtk-label-new "")
        selected-tag (g/gtk-text-tag-new "jonquil-inspector-selected")
        ;; jonquil.gtk.popover/make-popover parents its own popover to
        ;; (gtk_widget_get_parent view), so view must already be inside
        ;; scroll (its own immediate container) BEFORE def-popover is
        ;; built below -- otherwise gtk_widget_get_parent answers NULL and
        ;; gtk_widget_set_parent fails its own GTK_IS_WIDGET assertion.
        _ (g/gtk-scrolled-window-set-child scroll view)
        def-entry (g/gtk-entry-new)
        def-popover (popover/make-popover view {:autohide? true :position :bottom})
        keyctl (g/gtk-event-controller-key-new)
        click (g/gtk-gesture-click-new)]
    (merge {:root root :header header :info info :toolbar toolbar
            :table-header table-header :view view :buf buf
            :scroll scroll :footer footer :selected-tag selected-tag
            :def-entry def-entry :def-popover def-popover :keyctl keyctl
            :click click}
           (toolbar-buttons))))

(defn- note-clear-callback
  "The pane's one note-clear timer callable."
  [{:keys [state footer note-clear-deadline note-clear-armed?]}]
  (callable (fn [_data]
              (try
                (if (>= (System/nanoTime) @note-clear-deadline)
                  (do (reset! note-clear-armed? false)
                      (swap! state dissoc :note :defd)
                      (g/gtk-label-set-text footer (footer-text @state))
                      0) ;; G_SOURCE_REMOVE
                  1) ;; G_SOURCE_CONTINUE: the deadline moved out
                (catch :default e
                  (reset! note-clear-armed? false)
                  (g/gtk-label-set-text footer (str "; " (ex-message e)))
                  0)))
            [:pointer] :int))

(defn- ready-callback
  "The pane's one measure-when-ready! wait callable."
  [{:keys [pane* ready-waiters ready-armed?]}]
  (callable (fn [_data]
              (try
                (ready-tick @pane*)
                (catch :default e
                  (reset! ready-waiters [])
                  (reset! ready-armed? false)
                  (report-error! @pane* (ex-message e))
                  0)))
            [:pointer] :int))

(defn- style-widgets!
  "Sets up the labels, rows view, scroll and def prompt entry."
  [{:keys [header info table-header footer view buf selected-tag]}
   {:keys [scroll def-entry def-popover]}]
  ;; labels
  (g/gtk-label-set-xalign header 0.0)
  (g/gtk-label-set-ellipsize header g/PANGO-ELLIPSIZE-START)
  (g/gtk-label-set-xalign info 0.0)
  (g/gtk-label-set-xalign table-header 0.0)
  (g/gtk-widget-add-css-class table-header "monospace")
  (g/gtk-widget-set-visible table-header 0)
  (g/gtk-label-set-xalign footer 0.0)
  ;; rows view: read-only monospace, no visible cursor
  (g/gtk-text-view-set-editable view 0)
  (g/gtk-text-view-set-monospace view 1)
  (g/gtk-text-view-set-cursor-visible view 0)
  (g/gtk-widget-add-css-class view "jonquil-code")
  (g/set-string-property! selected-tag "background" (:selection (theme/theme theme/default-theme)))
  (g/gtk-text-tag-table-add (g/gtk-text-buffer-get-tag-table buf) selected-tag)
  (g/gtk-widget-set-vexpand scroll 1)
  ;; the def prompt entry
  (g/gtk-widget-set-size-request def-entry DEF-ENTRY-WIDTH -1)
  (popover/set-child! def-popover def-entry))

(defn- lay-out!
  "Appends the toolbar's buttons and the panel's rows to their boxes."
  [{:keys [root header info table-header footer
           back-btn earlier-btn later-btn meta-btn table-btn def-btn close-btn]}
   toolbar scroll]
  ;; layout
  (g/gtk-box-append toolbar back-btn)
  (g/gtk-box-append toolbar earlier-btn)
  (g/gtk-box-append toolbar later-btn)
  (g/gtk-box-append toolbar meta-btn)
  (g/gtk-box-append toolbar table-btn)
  (g/gtk-box-append toolbar def-btn)
  (g/gtk-box-append toolbar close-btn)
  (g/gtk-box-append root header)
  (g/gtk-box-append root info)
  (g/gtk-box-append root toolbar)
  (g/gtk-box-append root table-header)
  (g/gtk-box-append root scroll)
  (g/gtk-box-append root footer)
  (g/gtk-widget-set-size-request root WIDTH-REQUEST -1))

(defn- connect-click!
  "Connects btn's \"clicked\" signal to the thunk f, errors reported into
  pane's footer."
  [btn pane f]
  (g/g-signal-connect-data btn "clicked"
                           (callable (guarded-click pane f) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-toolbar!
  "Connects the seven toolbar buttons' \"clicked\" signals."
  [{:keys [back-btn earlier-btn later-btn meta-btn table-btn def-btn close-btn on-close] :as pane}]
  (connect-click! back-btn pane #(step! pane :out))
  (connect-click! earlier-btn pane #(step! pane :earlier))
  (connect-click! later-btn pane #(step! pane :later))
  (connect-click! meta-btn pane #(step! pane :meta))
  (connect-click! table-btn pane #(step! pane :table))
  (connect-click! def-btn pane #(open-def-prompt! pane))
  (connect-click! close-btn pane on-close))

(defn- connect-def-prompt!
  "Wires the def prompt's close refocus and its entry's \"activate\"."
  [{:keys [on-def] :as pane} def-popover def-entry]
  ;; def prompt: Return with a non-blank name calls on-def; blank hides it.
  ;; Escape/click-outside close it themselves (autohide true), and the
  ;; popover's own "closed" signal (jonquil.gtk.popover/make-popover)
  ;; already keeps its :open? atom in sync -- this connects a SECOND
  ;; "closed" handler, as the rename popover does, to give keyboard focus
  ;; back to the rows view whichever way it closed, once GTK has finished
  ;; closing (jonquil.gtk.popover/refocus-view-on-close!: a grab in the
  ;; handler itself was undone, leaving focus on the scrolled window)
  (popover/refocus-view-on-close! def-popover)
  (g/g-signal-connect-data def-entry "activate"
                           (callable (fn [_entry _data]
                                       (try
                                         (let [dp (:def-popover pane)
                                               name (str/trim (or (g/editable-text (:entry dp)) ""))]
                                           (popover/hide! dp)
                                           (when (seq name) (on-def name)))
                                         (catch :default e (report-error! pane (ex-message e))))
                                       nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-view-input!
  "Attaches the key controller and the double-click gesture to the rows view."
  [{:keys [view] :as pane} keyctl click]
  ;; keys: bubble phase, attached to the rows view itself.
  (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-BUBBLE)
  (g/g-signal-connect-data keyctl "key-pressed"
                           (callable (on-key pane) [:pointer :uint :uint :uint :pointer] :int)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-widget-add-controller view keyctl)
  ;; double click
  (g/g-signal-connect-data click "pressed"
                           (callable (fn [_gesture n-press x y _data]
                                       (try
                                         (when (= 2 n-press) (click-row! pane x y))
                                         (catch :default e (report-error! pane (ex-message e))))
                                       nil)
                                     [:pointer :int :double :double :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-widget-add-controller view click))

(def ^:private pane-widget-keys
  [:root :view :buf :header :info :table-header :footer :selected-tag
   :back-btn :earlier-btn :later-btn :meta-btn :table-btn :def-btn :close-btn])

(defn- pane-timers
  "The pane's state atom, its note-clear and measure-wait timer callables
  and their atoms, built before the pane map exists. Returns the pane's
  own keys plus :pane*, the atom the pane map is put into once built."
  [opts footer]
  (let [;; state/note-clear-deadline/armed? are hoisted out of the pane
        ;; map literal (rather than built as `(atom nil)` inline there)
        ;; so note-clear-cb, below, can close over the SAME atoms before
        ;; pane itself exists -- one callable for the pane's whole
        ;; lifetime (make-flash-fn's own pattern), never one per note.
        ;; The caller may pass its own atom (:state in opts), so the window
        ;; and the pane hold ONE pane state: a local move and a reply's
        ;; :pending then always act on the same map.
        state (or (:state opts) (atom nil))
        note-clear-deadline (atom 0)
        note-clear-armed? (atom false)
        note-clear-cb (note-clear-callback {:state state :footer footer
                                            :note-clear-deadline note-clear-deadline
                                            :note-clear-armed? note-clear-armed?})
        ;; measure-when-ready!'s wait, one callable for the pane's
        ;; lifetime as note-clear-cb is; it reads the pane through pane*,
        ;; set once the map exists
        pane* (atom nil)
        ready-waiters (atom [])
        ready-armed? (atom false)
        ready-cb (ready-callback {:pane* pane* :ready-waiters ready-waiters
                                  :ready-armed? ready-armed?})]
    {:state state
     :note-clear-cb note-clear-cb
     :note-clear-deadline note-clear-deadline
     :note-clear-armed? note-clear-armed?
     :ready-cb ready-cb :ready-waiters ready-waiters
     :ready-armed? ready-armed?
     :pane* pane*}))

(defn make-pane
  "Builds the inspector panel widget tree. f/g/h below are on-request/
  on-close/on-def -- f is called on the GTK thread with [op args] whenever
  a key or button asks for a request key-step didn't resolve locally; g
  and h take no state, the pane's own current state living entirely in
  the returned map's :state atom.

  Returns {:root :view :buf :header :info :table-header :footer
  :def-popover :state (atom nil)} plus internal bookkeeping (:back-btn
  etc, :selected-tag, :note-clear-cb/:note-clear-deadline/:note-clear-
  armed?, :on-request/:on-close/:on-def) show-state!/measure/focus!/
  focused? and this namespace's own button/key/click handlers read
  back."
  [{:keys [on-request on-close on-def] :as opts}]
  (let [{:keys [toolbar scroll def-entry def-popover keyctl click] :as widgets} (inspector-widgets)
        {:keys [pane*] :as timers} (pane-timers opts (:footer widgets))
        pane (merge (select-keys widgets pane-widget-keys)
                    (dissoc timers :pane*)
                    {:def-popover (assoc def-popover :entry def-entry)
                     :ready-start (atom 0)
                     :on-request on-request :on-close on-close :on-def on-def})]
    (reset! pane* pane)
    (style-widgets! pane {:scroll scroll :def-entry def-entry :def-popover def-popover})
    (lay-out! pane toolbar scroll)
    (connect-toolbar! pane)
    (connect-def-prompt! pane def-popover def-entry)
    (connect-view-input! pane keyctl click)
    pane))
