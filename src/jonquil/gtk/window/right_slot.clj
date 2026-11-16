(ns jonquil.gtk.window.right-slot
  "The window's right-hand panels: the inspector, the source panel, the plugin panels and the parts' panels, each in one of the window's places (jonquil.gtk.window.places, the right-hand one unless settings.edn's :layout says otherwise), one showing per place at a time, and the fns that show, hide and name the one on screen. build makes activate!'s place and panel bindings, build-panels makes the four panels and publish resets *right. Internal to the window, as jonquil.gtk.window.status is."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.plugin-history-pane :as plugin-history-pane]
            [jonquil.gtk.plugin-list-pane :as plugin-list-pane]
            [jonquil.gtk.review-pane :as review-pane]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.gtk.window.places :as win-places]
            [jonquil.places :as places]
            [jonquil.gtk.window.plugin-agent :as win-plugin-agent]
            [jonquil.gtk.window.status :as win-status]))

(def ^:private PANEL-WIDTH
  ;; estimate: matched against jonquil.gtk.inspector-pane/WIDTH-REQUEST
  ;; (360) plus room for the paned's handle.
  420)

;; --- the right-hand panels: the inspector and the source panel -----------
;; One panel per place, last wins: formerly a vertical GtkBox
;; holds both panels' roots, only one visible at a time, and the box itself
;; is hidden when neither is. Each root keeps its own visibility, so "is the
;; inspector showing?" is still (:root pane)'s own visible flag.
;; jonquil.gtk.window.runtime's apply-selection! hides the slot on a
;; runtime switch.

(defn right-roots
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

(defn place-name
  "The place (:left, :right or :bottom) the part id is in, by right's
  :place-of*: the usual one (jonquil.places/usual, else :right) before the
  parts are placed, or for a right without :place-of* (a test's)."
  [right id]
  (get (some-> (:place-of* right) deref) id (get places/usual id :right)))

(defn part-place
  "The place (a notebook) the part id is in (place-name)."
  [right id]
  (get (:places right) (place-name right id)))

(defn- on-right-press!
  "Runs f on a right-button press on widget."
  [widget f]
  (let [click (g/gtk-gesture-click-new)]
    (g/gtk-gesture-single-set-button click 3)
    (g/g-signal-connect-data click "pressed"
                             (callable (fn [_gesture _n _x _y _data] (f) nil)
                                       [:pointer :int :double :double :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller widget click)))

(defn add-to-place!
  "Adds w, with the tab label (a string or a widget), to the place the
  part id is in, and records it as one of id's pages (:pages-of*). A
  right-click on the tab runs right's tab menu (:tab-menu*, once set) with
  id and the tab."
  [right id w label]
  (let [nb (part-place right id)
        tab (if (string? label) (g/gtk-label-new label) label)]
    (on-right-press! tab (fn [] (when-let [f (some-> (:tab-menu* right) deref)] (f id tab))))
    (when-let [p (:pages-of* right)] (swap! p update id (fnil conj []) w))
    (win-places/add! nb w tab)))

(defn remove-from-place!
  "Takes w, one of the part id's pages, out of its place."
  [right id w]
  (when-let [p (:pages-of* right)] (swap! p update id #(filterv (fn [x] (not= x w)) %)))
  (win-places/remove! (part-place right id) w))

(defn size-right-place!
  "The right-hand place's on-shown: sizes h-paned so the place claims about
  PANEL-WIDTH of the window (900 wide before it has a width); while it
  shows, the split stays where the person put it."
  [{:keys [win h-paned]}]
  (let [w (g/gtk-widget-get-width win)]
    (g/gtk-paned-set-position h-paned (max 0 (- (if (pos? w) w 900) PANEL-WIDTH)))))

(defn show-right-panel!
  "Shows which (:inspector, :source, :review, :plugins, :history,
  :locations, :debugger, :trace or :messages) in its place, as that
  place's current page, and hides the other panels there: a place shows
  one panel at a time, beside whatever else it holds (the tree, the REPL,
  the terminals). Any but :history clears plugin-history-shown, and which
  moves to the front of right's :recent-panels when it has one. which is
  shown before the others hide, so its place never hides on the way and
  keeps its size (the place sizes itself when it goes from hidden to
  shown, size-right-place!). right is {:win :h-paned :places :place-of*
  :inspector-root :source-root ...}. GTK-thread only."
  [right which]
  (let [roots (right-roots right)
        place (part-place right which)
        root (get roots which)]
    (when (not= which :history) (reset! win-plugin-agent/plugin-history-shown nil))
    (g/gtk-widget-set-visible root 1)
    (doseq [[k r] roots :when (and (not= k which) (= place (part-place right k)))]
      (g/gtk-widget-set-visible r 0))
    (win-places/show-page! place root)
    ;; the recent panels, most recently shown first (a test's right has none)
    (when-let [r (:recent-panels right)]
      (swap! r (fn [v] (into [which] (remove #{which}) v))))))

(defn showing-panel
  "The key of a right-hand panel on screen, or nil when none is: one that
  shows as its place's current page, the most recently shown first."
  [right]
  (let [roots (right-roots right)]
    (some #(when (win-places/current? (part-place right %) (get roots %)) %)
          (distinct (concat (some-> (:recent-panels right) deref) (keys roots))))))

(defn focus-within?
  "True when keyboard focus is widget or inside it."
  [widget]
  (let [root (g/gtk-widget-get-root widget)]
    (when-not (ffi/null? root)
      (let [f (g/gtk-root-get-focus root)]
        (and (not (ffi/null? f))
             (or (= f widget) (pos? (g/gtk-widget-is-ancestor f widget))))))))

(defn hide-right-panel!
  "Hides which's root; its place hides itself once nothing in it shows
  (jonquil.gtk.window.places). Hiding
  :source also cancels any re-scroll settling still in flight for it
  (source-pane/cancel-scroll-settle!), so the
  shared settle timeout stops touching the view behind a hidden panel
  instead of only finding out once the view is actually destroyed.
  close-source-panel! reaches this same cancellation by calling through
  here, not by calling cancel-scroll-settle! a second time."
  [{:keys [source-pane] :as right} which]
  (let [roots (right-roots right)]
    (g/gtk-widget-set-visible (get roots which) 0)
    (when (= which :history) (reset! win-plugin-agent/plugin-history-shown nil))
    (when (= which :source)
      (source-pane/cancel-scroll-settle! (:scroll-cancelled? source-pane)))))

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
  "activate!'s place and panel bindings, in activate!'s order: the three
  places (jonquil.gtk.window.places), part-panels* (reset once the parts
  install), the right map every panel fn takes, and panel-focus-fn. w
  holds what they read: :win :h-paned :pane :sp :rp :lp :hp
  :settings-path* :status-label. Returns w with :places :slot (the
  right-hand place) :part-panels* :right and :panel-focus-fn added;
  activate! resets *right to the right map next. The right map's
  :place-of* is reset when jonquil.gtk.window.layout places the parts."
  [{:keys [win h-paned pane sp rp lp hp settings-path* status-label] :as w}]
  (let [places {:left (win-places/make) :right (win-places/make) :bottom (win-places/make)}
        slot (:right places)
        ;; {id {:root :focus! :open!}} of the parts' panels, reset once the
        ;; parts install (after part-contribution): panel-focus-fn and
        ;; right-roots are built first, like *tab-ctx.
        part-panels* (atom {})
        right {:win win :h-paned h-paned :slot slot :places places :place-of* (atom {})
               ;; {id [page ...]}: what a move takes along; and the tab
               ;; menu's fn, set once the window can move parts
               :pages-of* (atom {}) :tab-menu* (atom nil) :move!* (atom nil)
               :inspector-root (:root pane) :source-root (:root sp)
               :review-root (:root rp) :plugins-root (:root lp)
               :history-root (:root hp) :source-pane sp
               ;; the panels shown, most recent first: the palette's
               ;; Switch to panel entries, and the panel keys' "open it"
               :recent-panels (atom [])
               :part-panels* part-panels*}
        panel-focus-fn (make-panel-focus-fn {:right right :pane pane :sp sp :lp lp :part-panels* part-panels*
                                             :settings-path* settings-path* :status-label status-label})]
    (win-places/on-shown! slot (fn [] (size-right-place! right)))
    (assoc w :places places :slot slot :part-panels* part-panels* :right right :panel-focus-fn panel-focus-fn)))

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
