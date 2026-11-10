(ns jonquil.gtk.window.inspector
  "The window's inspector panel: showing and closing it, the
  one-request-at-a-time guard (try-inspect!), folding each reply into the
  pane's state, and Inspect Last Result and Inspect Form, which open it on
  the last value or on the form at the caret. build makes activate!'s
  inspector pane and its closures. Internal to the window, as
  jonquil.gtk.window.status is. Moved out of jonquil.gtk.window unchanged,
  then inspect-last! took one map and inspect-form!'s evaluation split into
  named steps; the lock caller rule in jonquil.gtk.window's docstring
  applies here."
  (:require [jonquil.commands :as commands]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.window.right-slot :as win-right-slot]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.inspect :as inspect]))

;; --- inspector panel -----------------------------------------------------

(defn close-inspector-panel!
  "Hides pane's root and focuses the editor (the pane keeps its state, so
  the next open replaces it). Used both as the pane's own on-close (its
  Close button, or Escape while the rows view itself has focus) and the
  window keymap's :inspector/close Escape binding."
  [right ed]
  (win-right-slot/hide-right-panel! right :inspector)
  (editor/focus! ed))

(defn- show-inspector-panel!
  "Makes the inspector's root visible. The FIRST time (the slot was
  hidden), also sizes h-paned so the panel claims about PANEL-WIDTH of the
  window (the position is the window width minus 420, an estimate) -- a
  later open, while it's already showing, leaves the split exactly where
  the person put it. See show-right-panel!. GTK-thread only."
  [right]
  (win-right-slot/show-right-panel! right :inspector))

(defn- try-inspect!
  "The window sends at most one inspect! at a time. Runs f
  (which must send exactly one ev/inspect! request, through an on-view
  that eventually flips inspecting? back to false) only when no request
  is already pending, atomically flipping inspecting? true first -- a key
  or button press while one is pending is dropped here, with nothing
  queued for after it (only inspect-form!'s own open, not a press, waits
  in inspect-queued). A throw from f before its request goes out (the
  panel's show or measure, say) resets the flag before rethrowing, so one
  failure can't leave the inspector dead for the rest of the session."
  [inspecting? f]
  (when (compare-and-set! inspecting? false true)
    (try (f)
         (catch :default e
           (reset! inspecting? false)
           (throw e)))))

(defn- on-inspect-view!
  "on-view's own body, already handed to on-gui! by its caller: swap! inspector-state with jonquil.inspect/on-reply -- or, the first
  time (inspector-state nil, since inspect-last!/inspect-form! reset it
  right before sending :start), jonquil.inspect/open, which a :start
  reply's own on-reply application would reduce to anyway. A reply with
  :defd also prints \"; Defined <defd>\" on the console as a system line.
  inspecting? is cleared FIRST, so the pane's own next key/click (or
  another inspect-last!/inspect-form!) is never dropped on account of a
  request that has, in fact, already finished. Then an open inspect-form!
  queued behind this request (inspect-queued's thunk) runs, after this
  reply is shown, so the panel ends on the form's value."
  [pane inspector-state inspecting? inspect-queued cv status-label reply]
  (try
    (reset! inspecting? false)
    (let [state (swap! inspector-state
                       (fn [s] (if s (inspect/on-reply s reply) (inspect/open reply))))]
      (inspector-pane/show-state! pane state)
      (when-let [defd (:defd reply)]
        (cview/append! cv :system (str "; Defined " defd "\n"))))
    (catch :default e
      (win-status/set-status! status-label (str "; inspect: " (ex-message e)))))
  (when-let [queued (first (reset-vals! inspect-queued nil))]
    (try (queued)
         (catch :default e
           (win-status/set-status! status-label (str "; inspect: " (ex-message e)))))))

(defn inspect-last!
  "Cmd/Ctrl+I and the menu's \"Inspect Last Result\": an availability check
  puts the unavailable sentence in the status bar and stops; otherwise
  shows/sizes the panel, moves focus to its rows view (so Return/Escape/
  arrows go to the panel, not the editor, without an extra click), drops
  any earlier session's own state (so on-view opens fresh rather than
  continuing an old browse), and sends :start with the pane's own
  measured size once its rows view is actually allocated (the first open
  in a window would otherwise measure before GTK's layout pass ever ran)
  -- guarded by try-inspect!, so a second press while the first :start is
  still in flight does nothing. Takes one map: the window's values under
  their activate! names, and :start, [op extra-args]: [:start {}] for the
  key and the menu, or another start, as the Frames panel opens a local
  with [:start-local {:frame i :local name}]."
  [{:keys [ev* right pane inspector-state inspecting? inspector-last-args on-view status-label]
    [op extra] :start}]
  (if-not (and (satisfies? ev/Inspector @ev*) (ev/inspectable? @ev*))
    (win-status/set-status! status-label inspect/unavailable-message)
    (try-inspect!
     inspecting?
     (fn []
       (show-inspector-panel! right)
       (inspector-pane/focus! pane)
       (reset! inspector-state nil)
       ;; The evaluator current at the press, not whatever @ev* holds once
       ;; the rows view is allocated: a file switch in between would hand
       ;; :start to an evaluator without Inspector. A throw on this deferred
       ;; path releases the in-flight flag, or the inspector would stay dead.
       (let [e @ev*]
         (inspector-pane/measure-when-ready!
          pane
          (fn [measured]
            (try
              (reset! inspector-last-args measured)
              (ev/inspect! e op (merge measured extra) on-view)
              (catch :default ex
                (reset! inspecting? false)
                (throw ex))))))))))

(defn- open-or-queue!
  "Inspect Form's open once its eval came back :ok, on the GTK thread:
  inspect-last-fn now, or, while another inspect! request is in flight,
  queued in inspect-queued for that reply."
  [{:keys [inspecting? inspect-queued inspect-last-fn]}]
  (if @inspecting?
    (reset! inspect-queued inspect-last-fn)
    (inspect-last-fn)))

(defn- inspect-form-opts
  "make-eval-opts' map for Inspect Form's eval, with an :on-complete that
  runs make-eval-opts' own and then, for an :ok result, hands open-or-queue!
  to on-gui! (the completion runs on the eval worker thread)."
  [{:keys [tab cv ev* status-label] :as ctx}]
  (let [base-opts (win-run/make-eval-opts (:ed tab) cv ev* status-label false)]
    (assoc base-opts
           :on-complete
           (fn [result]
             ((:on-complete base-opts) result)
             (when (= :ok (:status result))
               (on-gui! (fn [] (open-or-queue! ctx))))))))

(defn- eval-for-inspect!
  "Inspect Form's eval of form (commands/run-form's map) from snap: the opts
  first, then the flash, the echo and the eval, as do-run-form! does; a busy
  engine's warning goes on the console."
  [{:keys [tab cv ev*] :as ctx} snap {:keys [text start end]}]
  (let [opts (inspect-form-opts ctx)]
    ((:flash! (:flash-fns tab)) start end (:caret snap))
    (cview/echo-entry! cv text (not (ev/evaluating? @ev*)))
    (let [_ (when-not (ev/evaluating? @ev*) (cview/note-run-start! cv))
          warning (ev/eval! @ev* text (assoc opts :run? false :ns-source (:text snap) :owner (:id tab)))]
      (when warning (cview/append! cv :system warning)))))

(defn inspect-form!
  "Cmd/Ctrl+Shift+I and the menu's \"Inspect Form\": the same
  availability check as inspect-last!, then commands/run-form on the
  editor's snapshot -- \"No form at the caret.\" for no form, the exact
  wording do-run-form! uses. Otherwise flashes, echoes and evaluates the
  form exactly as do-run-form! does, but builds its OWN opts map (never
  passing a second :on-complete through do-repl-eval!'s extra-opts, which
  would just replace the window's own): after make-eval-opts' own
  completion handling runs, an :ok result calls inspect-last! through
  on-gui!, or queues it in inspect-queued while another request is in
  flight -- :on-complete runs on the eval worker thread (the lock-caller
  rule: must only enqueue), and inspect-last! reaches GTK.

  A press while an inspect! request is already pending does
  nothing at all, not even the eval -- the same drop inspect-last!/
  on-request/on-def give a key or button in that window. But a request
  sent while the form evaluates (the person browsing the panel) doesn't
  drop the form's open: it waits in inspect-queued for that reply."
  {:arglists '([{:keys [tab cv ev* status-label inspecting? inspect-queued inspect-last-fn] :as ctx}])}
  [{:keys [tab ev* status-label inspecting?] :as ctx}]
  (let [{:keys [ed]} tab]
    (cond
    ;; A foreign tab can't evaluate this way either.
      (win-run/refuse-foreign! ctx) nil

      (not (and (satisfies? ev/Inspector @ev*) (ev/inspectable? @ev*)))
      (win-status/set-status! status-label inspect/unavailable-message)

      @inspecting? nil

      :else
      (let [snap (editor/snapshot ed)]
        (if-let [form (commands/run-form snap)]
          (eval-for-inspect! ctx snap form)
          (win-status/set-status! status-label "No form at the caret."))))))

(defn build
  "activate!'s inspector bindings, in activate!'s order: h-paned, the inspector's state atoms, on-view, the pane's request, def and close fns, and the pane, with *pane (the forward reference those closures read) reset to it. w holds what they read: :active-tab :cv :ev* :status-label, and :right* (activate!'s *right, read when the pane closes). Returns w with :h-paned :inspector-state :inspecting? :inspect-queued :inspector-last-args :on-view :on-inspector-close and :pane added."
  [{:keys [active-tab cv ev* status-label] *right :right* :as w}]
  (let [;; The inspector panel. h-paned is the horizontal split (editor+
        ;; console keep their vertical paned as its start child; the
        ;; pane's own :root becomes its end child, wired in activate! once
        ;; both exist). *pane is a forward reference the on-view/on-request/
        ;; on-def/on-close closures below close over -- pane itself doesn't
        ;; exist until make-pane returns, the same atom-indirection
        ;; the console's *cv (made in jonquil.gtk.window.console's build)
        ;; gives make-on-submit.
        h-paned (g/gtk-paned-new g/ORIENTATION-HORIZONTAL)
        inspector-state (atom nil)
        inspecting? (atom false)
        ;; An Inspect Form open that arrived while another request was in
        ;; flight: the thunk on-inspect-view! runs once that reply lands.
        inspect-queued (atom nil)
        ;; Self-test hook only (:inspector-last-args*): the args of the
        ;; last inspect! request this window actually sent, so a row can
        ;; compare a :start's own :cols against the pane's own measure.
        inspector-last-args (atom nil)
        *pane (atom nil)
        on-view (fn [reply]
                  (on-gui! (fn [] (on-inspect-view! @*pane inspector-state inspecting? inspect-queued cv status-label reply))))
        on-inspector-request
        (fn [[op args]]
          ;; Returns true only when the request was actually sent, so the
          ;; pane commits a key step's :pending/:selected only then.
          (boolean
           (try-inspect! inspecting?
                         (fn [] (reset! inspector-last-args args) (ev/inspect! @ev* op args on-view) true))))
        on-inspector-def
        (fn [name]
          (try-inspect! inspecting?
                        (fn []
                          (let [args {:name name}]
                            (reset! inspector-last-args args)
                            ;; :pending :keep, so on-reply's default (row 0)
                            ;; doesn't reset the selection: the panel stays
                            ;; on the same view. The pane shares
                            ;; this atom, so :selected is the row on screen.
                            (swap! inspector-state assoc :pending :keep)
                            (ev/inspect! @ev* :def args on-view)))))
        on-inspector-close (fn [] (close-inspector-panel! @*right (:ed (active-tab))))
        pane (inspector-pane/make-pane {:on-request on-inspector-request
                                        :on-close on-inspector-close
                                        :on-def on-inspector-def
                                        :state inspector-state})
        _ (reset! *pane pane)]
    (assoc w :h-paned h-paned :inspector-state inspector-state :inspecting? inspecting?
           :inspect-queued inspect-queued :inspector-last-args inspector-last-args
           :on-view on-view :on-inspector-close on-inspector-close :pane pane)))
