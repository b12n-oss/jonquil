(ns jonquil.gtk.window.right-slot
  "The window's right-hand slot: one box holding the inspector, the source panel, the plugin panels and the parts' panels, one showing at a time, and the fns that show, hide and name the one on screen. build makes activate!'s slot bindings, build-panels makes the four panels and publish resets *right. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window unchanged; build holds activate!'s own bindings in their order."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.plugin-history-pane :as plugin-history-pane]
            [jonquil.gtk.plugin-list-pane :as plugin-list-pane]
            [jonquil.gtk.review-pane :as review-pane]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.gtk.window.plugin-agent :as win-plugin-agent]
            [jonquil.gtk.window.status :as win-status]))

(def ^:private PANEL-WIDTH
  ;; estimate: matched against jonquil.gtk.inspector-pane/WIDTH-REQUEST
  ;; (360) plus room for the paned's handle.
  420)

;; --- the right-hand slot: the inspector and the source panel -------------
;; One slot, last wins: a vertical GtkBox
;; holds both panels' roots, only one visible at a time, and the box itself
;; is hidden when neither is. Each root keeps its own visibility, so "is the
;; inspector showing?" is still (:root pane)'s own visible flag.
;; jonquil.gtk.window.runtime's apply-selection! hides the slot on a
;; runtime switch.

(defn- right-roots
  "{which root} for the panels right's slot holds, a part's among them; a
  right without :review-root, :plugins-root or :history-root (a test's) has
  fewer, and one without :part-panels* has no part panels."
  [{:keys [inspector-root source-root review-root plugins-root history-root
           part-panels*]}]
  (cond-> (into {:inspector inspector-root :source source-root}
                (map (fn [[id p]] [id (:root p)]))
                (some-> part-panels* deref))
    review-root (assoc :review review-root)
    plugins-root (assoc :plugins plugins-root)
    history-root (assoc :history history-root)))

(defn show-right-panel!
  "Shows which (:inspector, :source, :review, :plugins, :history,
  :locations, :debugger, :trace or :messages) in
  the slot and hides the others; any but :history clears
  plugin-history-shown, and which moves to the front of right's
  :recent-panels when it has one. When the slot was hidden, also sizes h-paned so
  the panel claims about PANEL-WIDTH; while
  it's showing, the split stays where the person put it. right is {:win
  :h-paned :slot :inspector-root :source-root :review-root}. GTK-thread
  only."
  [{:keys [win h-paned slot] :as right} which]
  (let [was-hidden? (zero? (g/gtk-widget-get-visible slot))
        roots (right-roots right)]
    (when (not= which :history) (reset! win-plugin-agent/plugin-history-shown nil))
    (doseq [[k root] roots :when (not= k which)] (g/gtk-widget-set-visible root 0))
    (g/gtk-widget-set-visible (get roots which) 1)
    (g/gtk-widget-set-visible slot 1)
    ;; the recent panels, most recently shown first (a test's right has none)
    (when-let [r (:recent-panels right)]
      (swap! r (fn [v] (into [which] (remove #{which}) v))))
    (when was-hidden?
      (g/gtk-paned-set-position h-paned (max 0 (- (g/gtk-widget-get-width win) PANEL-WIDTH))))))

(defn showing-panel
  "The key of the right-hand panel on screen, or nil when the slot is
  hidden."
  [{:keys [slot] :as right}]
  (when (pos? (g/gtk-widget-get-visible slot))
    (some (fn [[k root]] (when (pos? (g/gtk-widget-get-visible root)) k)) (right-roots right))))

(defn focus-within?
  "True when keyboard focus is widget or inside it."
  [widget]
  (let [root (g/gtk-widget-get-root widget)]
    (when-not (ffi/null? root)
      (let [f (g/gtk-root-get-focus root)]
        (and (not (ffi/null? f))
             (or (= f widget) (pos? (g/gtk-widget-is-ancestor f widget))))))))

(defn hide-right-panel!
  "Hides which's root, and the slot once no root is showing. Hiding
  :source also cancels any re-scroll settling still in flight for it
  (source-pane/cancel-scroll-settle!), so the
  shared settle timeout stops touching the view behind a hidden panel
  instead of only finding out once the view is actually destroyed.
  close-source-panel! reaches this same cancellation by calling through
  here, not by calling cancel-scroll-settle! a second time."
  [{:keys [slot source-pane] :as right} which]
  (let [roots (right-roots right)]
    (g/gtk-widget-set-visible (get roots which) 0)
    (when (= which :history) (reset! win-plugin-agent/plugin-history-shown nil))
    (when (= which :source)
      (source-pane/cancel-scroll-settle! (:scroll-cancelled? source-pane)))
    (when (every? #(zero? (g/gtk-widget-get-visible %)) (vals roots))
      (g/gtk-widget-set-visible slot 0))))

(defn- focus-panel!
  "Moves focus into the panel k: the inspector, the source panel, the
  plugin list or one of the parts' panels. m is make-panel-focus-fn's.
  Throws for an id it doesn't know."
  [{:keys [pane sp lp part-panels*]} k]
  (case k
    :inspector (inspector-pane/focus! pane)
    :source (source-pane/focus! sp)
    :plugins (g/gtk-widget-grab-focus (:list lp))
    (if-let [p (get @part-panels* k)]
      ((:focus! p))
      (throw (IllegalArgumentException. (str "No matching clause: " k))))))

(defn- empty-panel-message
  "The status line for a panel k with nothing to show yet."
  [k]
  (case k
    :inspector "; Nothing inspected yet: inspect a form or a result first."
    :source "; The Source panel is empty: jump to a definition first."))

(defn- make-panel-focus-fn
  "The panel keys and the palette's Switch to panel: a fn of a panel id
  that focuses the panel when it shows. Frames, Trace and Plugins
  otherwise run their own command, which asks for what they show now (a
  hidden one would show the calls or the error from before). The
  inspector, source panel and locations list come back with their state
  when they showed before and it still holds (a dialect switch and a
  cleared list take them off the recent list); with nothing to show they
  say what fills them. m holds :right :pane :sp :lp :part-panels*
  :settings-path* and :status-label."
  [{:keys [right part-panels* settings-path* status-label] :as m}]
  (fn [k]
    (cond
      (= k (showing-panel right)) (focus-panel! m k)
      (= k :plugins) (win-plugin-agent/plugin-step! {:settings-path @settings-path*
                                                     :step (fn [] []) :open-list? true})
      (contains? @part-panels* k) ((:open! (get @part-panels* k)))
      (some #{k} @(:recent-panels right)) (do (show-right-panel! right k) (focus-panel! m k))
      :else (win-status/set-status! status-label (empty-panel-message k)))))

(defn build
  "activate!'s right-hand slot bindings, in activate!'s order: the slot box, part-panels* (reset once the parts install), the right map every slot fn takes, and panel-focus-fn. w holds what they read: :win :h-paned :pane :sp :rp :lp :hp :settings-path* :status-label. Returns w with :slot :part-panels* :right and :panel-focus-fn added; activate! resets *right to the right map next."
  [{:keys [win h-paned pane sp rp lp hp settings-path* status-label] :as w}]
  (let [slot (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        ;; {id {:root :focus! :open!}} of the parts' panels, reset once the
        ;; parts install (after part-contribution): panel-focus-fn and
        ;; right-roots are built first, like *tab-ctx.
        part-panels* (atom {})
        right {:win win :h-paned h-paned :slot slot :inspector-root (:root pane) :source-root (:root sp)
               :review-root (:root rp) :plugins-root (:root lp)
               :history-root (:root hp) :source-pane sp
               ;; the panels shown, most recent first: the palette's
               ;; Switch to panel entries, and the panel keys' "open it"
               :recent-panels (atom [])
               :part-panels* part-panels*}
        panel-focus-fn (make-panel-focus-fn {:right right :pane pane :sp sp :lp lp :part-panels* part-panels*
                                             :settings-path* settings-path* :status-label status-label})]
    (assoc w :slot slot :part-panels* part-panels* :right right :panel-focus-fn panel-focus-fn)))

(defn build-panels
  "activate!'s right-hand slot panels, in activate!'s order: the source panel, the review panel, the plugin list and the plugin history, then *right, the right map's forward atom. Reads nothing from w. Returns w with :sp :rp :lp :hp and :right* (the *right atom) added."
  [w]
  (let [;; The source panel is built before the tabs, whose
        ;; jump opens it; its root joins the right-hand slot once the
        ;; inspector exists (*right, reset below).
        sp (source-pane/make-pane)
        ;; The review panel shares the slot
        rp (review-pane/make-pane)
        ;; The plugin list shares the slot too; its rows are rebuilt after
        ;; every plugin step, like the reviews
        lp (plugin-list-pane/make-pane)
        ;; And the history of the plugin selected in it
        hp (plugin-history-pane/make-pane)
        *right (atom nil)]
    (assoc w :sp sp :rp rp :lp lp :hp hp :right* *right)))

(defn publish
  "activate!'s *right reset, in its place right after build: from here on every fn that reads *right finds the right map. w holds :right and :right* (the *right atom). Returns w unchanged."
  [{:keys [right] *right :right* :as w}]
  (let [_ (reset! *right right)]
    w))
