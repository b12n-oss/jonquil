(ns jonquil.gtk.source-pane
  "The source panel: a definition outside the buffer, shown read-only on
  the right where the inspector sits. A header names the file (and its
  jar), a read-only jonquil.gtk.editor shows the text with the caret on
  the definition's line, buttons offer Open in window and Close, and a
  footer holds key hints, notes and errors. The panel keeps its own
  history, so F12 inside it can follow definitions and Cmd/Ctrl+[ can come
  back. jonquil.gtk.window decides where it shows and owns its keys; this
  namespace owns what it shows."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.source :as source]))

(def WIDTH-REQUEST
  ;; jonquil.gtk.inspector-pane's own WIDTH-REQUEST, so the right-hand slot
  ;; keeps its width when one panel replaces the other.
  360)

(def hint-text "F12 follow · Cmd/Ctrl+[ back · Esc close")

(def ^:private SCROLL-SETTLE-POLL-MS
  ;; estimate: frequent enough to catch the view's own line-height
  ;; validation promptly, far enough apart not to spin the main loop
  ;; (jonquil.gtk.inspector-pane/NOTE-POLL-MS's own class of estimate).
  30)

(def ^:private SCROLL-SETTLE-DEADLINE-MS
  ;; MEASURED (dev/demo_shots.clj's clj-source scene):
  ;; place-caret!'s first gtk_text_view_scroll_to_mark, right after a
  ;; fresh set-text!, can settle on a stale vadjustment well short of its
  ;; target and never correct itself -- jumping to clojure.string/join in
  ;; a 377-line jar file landed at vadjustment 1005 instead of ~2521
  ;; (caret 1684px below a 575px-tall view) and stayed there for 2+
  ;; continuous seconds. Re-issuing the exact same call a little later,
  ;; once the text view has had a chance to validate real line heights,
  ;; converges within a few hundred ms. This caps the retries generously
  ;; past that.
  2000)

(defn read-only-reason
  "Why loc can't be opened for editing: Open in window's tooltip, and the
  footer's answer to Cmd/Ctrl+Shift+O."
  [loc]
  (if (:jar loc)
    "A definition inside a jar opens read-only."
    "This file is outside the project, so it opens read-only."))

(defn report!
  "Puts msg on the panel's footer."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) msg)
  nil)

(defn current
  "{:loc :caret} for what the panel shows now, or nil before anything loaded."
  [pane]
  (when-let [loc (:loc @(:state pane))]
    {:loc loc :caret (g/cursor-offset (:buf (:editor pane)))}))

(defn- rect-visible?
  "True when rect (g/caret-window-rect's own [x y w h], TEXT-window
  coordinates) falls entirely inside a view of view-height pixels. The
  one shared check behind caret-visible?, below, and the pre-pane
  scroll-settle callback built in make-pane (the body is
  scroll-settle-callback, which closes over the editor directly, not
  pane -- see its own comment)."
  [[_ y _ h] view-height]
  (and (>= y 0) (<= (+ y h) view-height)))

(defn caret-visible?
  "True once pane's own editor caret actually lands inside its view --
  not just that scroll_to_mark was called, but that it actually landed.
  Shared by place-caret!'s own re-scroll settling (arm-scroll-settle!,
  below) and the window-group and demo-shots harnesses that verify it."
  [pane]
  (let [view (:view (:editor pane))]
    (rect-visible? (g/caret-window-rect view) (g/gtk-widget-get-height view))))

(defn- rescroll!
  "The scroll_to_mark call place-caret! issues, factored out so
  arm-scroll-settle!'s shared timeout can re-issue the identical call."
  [ed]
  (g/gtk-text-view-scroll-to-mark (:view ed) (g/gtk-text-buffer-get-insert (:buf ed)) 0.0 1 0.0 0.3))

(defn cancel-scroll-settle!
  "Stops any re-scroll settling in flight from touching the view again:
  the shared scroll-settle-cb (make-pane) checks cancelled?-atom before
  every re-scroll and, once true, stops at once (G_SOURCE_REMOVE) without
  re-scrolling again. Public: jonquil.gtk.
  window's hide-right-panel! calls this too, when it hides the :source
  root, so a hidden panel's settle timer stops touching the view behind
  it -- close-source-panel! reaches the same cancellation by calling
  through hide-right-panel!, not by calling this a second time. Takes the
  pane's own scroll-cancelled? atom directly, not pane itself, since two
  of its callers -- the scroll controller's handler and the cursor-moved
  hook, both wired in connect-scroll-cancels! -- are built before pane
  exists (same reasoning as scroll-settle-cb closing over ed instead of
  pane); the panel editor view's own \"destroy\" handler (also
  connect-scroll-cancels!) and jonquil.gtk.window both reach it through
  the pane's public :scroll-cancelled? key instead. arm-scroll-settle!
  resets it back to false on every fresh jump, so a later place-caret!
  isn't short-circuited by an earlier cancel."
  [cancelled?-atom]
  (reset! cancelled?-atom true)
  nil)

(defn- arm-scroll-settle!
  "Clears pane's own scroll-cancelled? flag and pushes its scroll-settle
  deadline SCROLL-SETTLE-DEADLINE-MS out from now, then arms its single
  repeating scroll-settle-cb (built once in make-pane, per
  jonquil.gtk.inspector-pane/arm-note-clear!'s own pattern -- a fresh
  callable per call would leak, since jonquil.gtk.callbacks retains every
  one for the process's life) if it isn't already running. A later
  place-caret! (another jump before this one settles) simply moves the
  shared deadline forward and clears any pending cancellation; the
  already-scheduled callback goes on re-scrolling toward wherever the
  editor's caret currently is, which is always the latest target since
  place-cursor! already moved it there before this is called."
  [pane]
  (reset! (:scroll-cancelled? pane) false)
  (reset! (:scroll-deadline pane) (+ (System/nanoTime) (* SCROLL-SETTLE-DEADLINE-MS 1000000)))
  (when (compare-and-set! (:scroll-armed? pane) false true)
    (g/g-timeout-add SCROLL-SETTLE-POLL-MS (:scroll-settle-cb pane) ffi/null))
  nil)

(defn- place-caret!
  "Puts the panel editor's caret at offset and scrolls it about a third of
  the way down, then keeps re-scrolling on a shared timeout (arm-scroll-
  settle!) until the caret is actually visible or SCROLL-SETTLE-DEADLINE-MS
  passes. scroll_to_mark, not scroll_to_iter: the text was usually just
  loaded, and GTK computes line heights later -- MEASURED: the very first call, right after a fresh set-text!, can settle on a
  stale vadjustment well short of its target and never self-correct, so a
  single call isn't enough; see SCROLL-SETTLE-DEADLINE-MS's own comment."
  [pane offset]
  (let [ed (:editor pane)]
    (g/with-iters* (:buf ed) offset offset (fn [a _] (g/gtk-text-buffer-place-cursor (:buf ed) a)))
    (rescroll! ed)
    (arm-scroll-settle! pane)))

(defn- show-header! [pane loc]
  (g/gtk-label-set-text (:header pane) (str (:label loc) (when-not (:editable? loc) "  · read-only")))
  (g/gtk-widget-set-sensitive (:open-btn pane) (if (:editable? loc) 1 0))
  (g/gtk-widget-set-tooltip-text (:open-btn pane)
                                 (if (:editable? loc)
                                   "Open this file in the window for editing (Cmd/Ctrl+Shift+O)"
                                   (read-only-reason loc))))

(defn- load!
  "Shows loc with the caret on :line (clamped) or at :caret. Re-reads only
  when loc isn't the file already showing. Returns nil, or {:error msg}
  with the footer saying so and the panel keeping what it had."
  [pane loc {:keys [line caret]}]
  (let [ed (:editor pane)
        same? (source/same-file? loc (:loc @(:state pane)))
        read (when-not same? (source/read-text loc))]
    (if-let [err (:error read)]
      (do (report! pane err) {:error err})
      (let [text (if same? (editor/editor-text ed) (:text read))
            {cl :line moved? :moved?} (when line (source/clamp-line text line))
            offset (if line (source/line-offset text cl) (min (or caret 0) (count text)))]
        (when-not same? (editor/set-text! ed text))
        (swap! (:state pane) assoc :loc loc)
        (show-header! pane loc)
        (place-caret! pane offset)
        (when-not same? (editor/paint-async! ed))
        (report! pane (if moved? source/moved-note hint-text))
        nil))))

(defn open-location!
  "Opens loc on its :line, pushing what showed before onto the panel's
  history once the load succeeded. Returns nil, or {:error msg} with the
  history unchanged."
  [pane loc]
  (let [before (current pane)
        r (load! pane loc {:line (:line loc)})]
    (when (and (nil? r) before)
      (swap! (:state pane) update :history source/push before))
    r))

(defn move-to-offset!
  "A definition inside the panel's own file: pushes where the caret was,
  then moves it to offset."
  [pane offset]
  (when-let [before (current pane)]
    (swap! (:state pane) update :history source/push before)
    (place-caret! pane offset)
    (report! pane hint-text))
  nil)

(defn back!
  "Steps back through the history: shows the previous entry's file with
  its caret. true when it moved; false when the history was empty or the
  entry couldn't be read (that entry is dropped, the footer says why, and
  the panel keeps showing what it had)."
  [pane]
  (if-let [[entry rest-history] (source/back (:history @(:state pane)))]
    (do (swap! (:state pane) assoc :history rest-history)
        (nil? (load! pane (:loc entry) {:caret (:caret entry)})))
    false))

(defn focused?
  "True when keyboard focus is anywhere inside the panel (the root's focus
  widget is a descendant of :root, the find bar's own test)."
  [pane win]
  (let [f (g/gtk-root-get-focus win)]
    (and (not (ffi/null? f)) (not (zero? (g/gtk-widget-is-ancestor f (:root pane)))))))

(defn focus! [pane] (editor/focus! (:editor pane)))

(defn apply-theme! [pane t] (editor/apply-theme! (:editor pane) t))

(defn set-profile!
  "Paints the panel with a jonquil.dialect profile's sets (the window's
  current one), from the next load on."
  [pane profile]
  (reset! (:profile (:editor pane)) profile)
  nil)

(defn set-handlers!
  "Registers the window's {:on-open f :on-close g}, called by the Open in
  window and Close buttons. A single slot, set once the window has built
  what they need."
  [pane handlers]
  (reset! (:handlers pane) handlers)
  nil)

(defn- handler-click
  "A clicked handler running the registered handler k, reporting a throw
  on the footer."
  [pane k]
  (fn [_button _data]
    (try (when-let [f (get @(:handlers pane) k)] (f))
         (catch :default e (report! pane (str "; " (ex-message e)))))
    nil))

(defn- scroll-settle-callback
  "The scroll-settle timer's callable, built once per pane over its editor,
  footer and the shared atoms."
  [{:keys [ed footer scroll-deadline scroll-armed? scroll-cancelled?]}]
  (callable (fn [_data]
              (try
                (if (or @scroll-cancelled?
                        (rect-visible? (g/caret-window-rect (:view ed)) (g/gtk-widget-get-height (:view ed)))
                        (>= (System/nanoTime) @scroll-deadline))
                  (do (reset! scroll-armed? false) 0) ;; G_SOURCE_REMOVE
                  (do (rescroll! ed) 1)) ;; G_SOURCE_CONTINUE
                (catch :default e
                  (reset! scroll-armed? false)
                  (g/gtk-label-set-text footer (str "; " (ex-message e)))
                  0)))
            [:pointer] :int :on-throw 0))

(defn- user-scroll-fn
  "The plain fn behind the panel's \"scroll\" signal: a person's own scroll
  cancels the settling, and a throw lands on the footer."
  [footer scroll-cancelled?]
  (fn [_ctl _dx _dy _data]
    (try (cancel-scroll-settle! scroll-cancelled?)
         (catch :default e (g/gtk-label-set-text footer (str "; " (ex-message e)))))
    0)) ;; FALSE: GTK still performs the real scroll

(defn- lay-out!
  "Makes the editor read-only, styles the labels and packs the widgets into
  the root."
  [{:keys [root header footer open-btn close-btn] ed :editor} bar]
  (editor/set-read-only! ed true)
  (g/gtk-widget-add-css-class (:view ed) "jonquil-code")
  (g/gtk-label-set-xalign header 0.0)
  (g/gtk-label-set-ellipsize header g/PANGO-ELLIPSIZE-START)
  (g/gtk-label-set-xalign footer 0.0)
  (g/gtk-widget-set-vexpand (:scroll ed) 1)
  (g/gtk-widget-set-sensitive open-btn 0)
  (g/gtk-box-append bar open-btn)
  (g/gtk-box-append bar close-btn)
  (g/gtk-box-append root header)
  (g/gtk-box-append root (:scroll ed))
  (g/gtk-box-append root bar)
  (g/gtk-box-append root footer)
  (g/gtk-widget-set-size-request root WIDTH-REQUEST -1))

(defn- connect-scroll-cancels!
  "Wires the ways a person's own scroll, caret move or the view's
  destruction cancel the re-scroll settling."
  [{ed :editor :keys [scroll-cancelled? on-user-scroll!]} scroll-ctl]
  ;; A person's own scroll or caret move over the panel cancels any
  ;; re-scroll settling in flight (cancel-scroll-settle!), so arm-
  ;; scroll-settle! never fights them (a single call never did, so the
  ;; re-scroll timeout must not break that). on-cursor-moved! is
  ;; editor/make-editor's own existing hook
  ;; (fires on the buffer's notify::cursor-position, which a key press
  ;; or a click in a read-only view both still trigger): the smallest
  ;; way to cover "the person moves the caret themselves" without a
  ;; second key controller alongside the scroll one below.
  (g/g-signal-connect-data scroll-ctl "scroll"
                           (callable on-user-scroll! [:pointer :double :double :pointer] :int :on-throw 0)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-widget-add-controller (:view ed) scroll-ctl)
  (editor/on-cursor-moved! ed (fn [_line-col] (cancel-scroll-settle! scroll-cancelled?)))
  ;; A backstop for when the view is destroyed
  ;; without the panel having been hidden first (hide-right-panel!/
  ;; close-source-panel! already cancel on every ordinary hide, well
  ;; before the view ever goes away) -- a transient window closing out
  ;; from under the panel, as dev/gtk_selftest_window/source_pane.clj's
  ;; with-source-pane does between rows. Connected ONCE, here.
  (g/g-signal-connect-data (:view ed) "destroy"
                           (callable (fn [_view _data] (cancel-scroll-settle! scroll-cancelled?) nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- connect-buttons!
  "Open in window and Close run the handlers registered on the pane."
  [{:keys [open-btn close-btn] :as pane}]
  (g/g-signal-connect-data open-btn "clicked" (callable (handler-click pane :on-open) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/g-signal-connect-data close-btn "clicked" (callable (handler-click pane :on-close) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- new-scroll-state
  "The scroll-settle bookkeeping for a pane over ed and footer:
  {:scroll-deadline :scroll-armed? :scroll-cancelled? :scroll-settle-cb
  :on-user-scroll!}."
  [ed footer]
  ;; scroll-deadline/armed?/cancelled? are made before the pane map so
  ;; scroll-settle-cb and on-user-scroll! can close over the SAME atoms
  ;; and ed before pane itself exists -- one callable for the pane's
  ;; whole lifetime (jonquil.gtk.inspector-pane/note-clear-cb's own
  ;; pattern), never one per place-caret! call.
  (let [scroll-deadline (atom 0)
        scroll-armed? (atom false)
        scroll-cancelled? (atom false)]
    {:scroll-deadline scroll-deadline
     :scroll-armed? scroll-armed?
     :scroll-cancelled? scroll-cancelled?
     :scroll-settle-cb (scroll-settle-callback {:ed ed :footer footer :scroll-deadline scroll-deadline
                                                :scroll-armed? scroll-armed? :scroll-cancelled? scroll-cancelled?})
     ;; The real "scroll" signal's own 4-arg shape (controller, dx, dy,
     ;; data); dx/dy are never read, since ANY scroll at all means the
     ;; person is driving the view themselves. Plain fn, not yet
     ;; wrapped in callable, so it can also be called directly (no FFI
     ;; marshaling) by a self-test row, and wrapped once, in
     ;; connect-scroll-cancels!, for the real GTK connection.
     :on-user-scroll! (user-scroll-fn footer scroll-cancelled?)}))

(defn make-pane
  "Builds the panel. Returns {:root :header :editor :footer :open-btn
  :close-btn :state (atom {:loc nil :history []}) :handlers (atom {})}
  plus the scroll-settle bookkeeping place-caret! shares across calls
  (:scroll-deadline :scroll-armed? :scroll-cancelled? :scroll-settle-cb --
  see arm-scroll-settle!/cancel-scroll-settle!) and :on-user-scroll!, the
  panel's own GtkEventControllerScroll handler (test-facing: a self-test
  row calls it directly to simulate a person's own scroll without driving
  a real GdkScroll event). :root starts visible; the window hides it. The
  editor is read-only and carries the jonquil-code CSS class, so the
  window's zoom reaches it.

  The panel editor view's own \"destroy\" signal is connected ONCE, in
  connect-scroll-cancels!, to a callable that cancels scroll-settle (sets
  scroll-cancelled? true) so scroll-settle-cb's next tick sees it and
  removes itself (G_SOURCE_REMOVE) before touching the now-gone view --
  rather than finding out the hard way, via a GTK_IS_TEXT_VIEW/
  GTK_IS_WIDGET assertion failure, that the view it was about to re-scroll
  no longer exists. Hiding the panel (hide-right-panel!, close-source-
  panel!) cancels the same way, through the pane's public
  :scroll-cancelled? key, well before the view is ever destroyed; this
  handler is the backstop for when the view goes away without the panel
  having been hidden first (a transient window closing out from under it,
  as dev/gtk_selftest_window/source_pane.clj's with-source-pane does
  between rows)."
  []
  (let [ed (editor/make-editor)
        footer (g/gtk-label-new hint-text)
        bar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        pane (merge {:root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
                     :header (g/gtk-label-new "")
                     :editor ed :footer footer
                     :open-btn (g/gtk-button-new-with-label "Open in window")
                     :close-btn (g/gtk-button-new-with-label "Close")
                     :state (atom {:loc nil :history []})
                     :handlers (atom {})}
                    (new-scroll-state ed footer))
        scroll-ctl (g/gtk-event-controller-scroll-new g/EVENT-CONTROLLER-SCROLL-BOTH-AXES)]
    (lay-out! pane bar)
    (connect-scroll-cancels! pane scroll-ctl)
    (connect-buttons! pane)
    pane))
