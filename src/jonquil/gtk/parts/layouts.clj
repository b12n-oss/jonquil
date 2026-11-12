(ns jonquil.gtk.parts.layouts
  "LM8's named layouts as a window part: saves the window's arrangement
  under a name made from what shows, brings one back, forgets them all (the
  commands are jonquil.parts.layouts/spec; using one is a palette entry the
  window dispatches to :layout-use-fn). Moved out of jonquil.gtk.window; the
  one change is that the settle timer's callable is built once at install,
  where each use built its own."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.layouts :as layouts]
            [jonquil.settings :as settings]))

(defn- near? [a b] (<= (abs (- a b)) 2))

(defn- tree-target?
  "True when the target carries a tree width to set."
  [{:keys [tree? tree-width]}]
  (boolean (and tree? tree-width)))

(defn- push-targets!
  "Sets the tree's width and the split to the target's."
  [{:keys [tree-paned paned]} {:keys [tree-width split] :as target}]
  (when (tree-target? target) (g/gtk-paned-set-position tree-paned tree-width))
  (when split (g/gtk-paned-set-position paned split)))

(defn- positions-read-back?
  "True when the tree's width and the split read back as the target's, or
  the target doesn't set them."
  [{:keys [tree-paned paned]} {:keys [tree-width split] :as target}]
  (and (or (not (tree-target? target)) (near? tree-width (g/gtk-paned-get-position tree-paned)))
       (or (not split) (near? split (g/gtk-paned-get-position paned)))))

(defn- settle!
  "One tick of the settle timer: sets the tree's width and the split toward
  pending*'s target and answers 1 to run again, or 0 when they read back
  or the tries run out. Whenever it answers 0 it clears running?, so the next use can arm a timer. A throw clears running? and is
  rethrown, so the callable's :on-throw 0 reports it and answers 0."
  [{:keys [pending* running? right showing-panel] :as ctx}]
  (try
    (let [{:keys [panel] :as target} @pending*
          ready? (or (nil? panel) (= panel (showing-panel right)))]
      (when ready? (push-targets! ctx target))
      (if (or (>= (:tries (swap! pending* update :tries inc)) 20)
              (and ready? (positions-read-back? ctx target)))
        (do (reset! running? false) 0)
        1))
    (catch :default e
      (reset! running? false)
      (throw e))))

(defn- saved
  "The saved layouts in the settings file, {} when there are none."
  [{:keys [settings-path*]}]
  (or (:layouts (settings/load-settings @settings-path*)) {}))

(defn- save-layouts!
  "Writes the layouts map m to the settings file; a failure goes to the
  console."
  [{:keys [settings-path* cv]} m]
  (when-let [err (settings/save-settings! @settings-path* {:layouts m})]
    (cview/append! cv :system (str "; settings: " err "\n"))))

(defn- save-layout!
  "Saves the window's arrangement under the name made from what shows."
  [{:keys [right tree-pane tree-paned paned showing-panel set-status!] :as ctx}]
  (let [panel (showing-panel right)
        l (cond-> {:tree? (pos? (g/gtk-widget-get-visible (:root tree-pane)))
                   :tree-width (g/gtk-paned-get-position tree-paned)
                   :panel panel
                   :split (g/gtk-paned-get-position paned)})]
    (save-layouts! ctx (layouts/save (saved ctx) l))
    (set-status! (str "; Saved the layout \"" (layouts/layout-name l) "\"."))))

(defn- show-layout-panel!
  "The panel showing goes unless it's the layout's own, which is then shown
  or asked for."
  [{:keys [right panel-focus-fn showing-panel show-right-panel! hide-right-panel!]} panel]
  (when-let [k (showing-panel right)]
    (when (not= k panel) (hide-right-panel! right k)))
  (when panel
    (if (= panel (showing-panel right))
      (show-right-panel! right panel)
      (panel-focus-fn panel))))

(defn- arm-settle!
  "Sets the settle target and arms the timer unless it is already running.
  GTK lays the tree and a panel out over its next frames (and
  a panel shown from an evaluator's answer arrives later), so
  the tree's width and the split are set every 100 ms until
  they read back, for at most 2 s. The panel opens at its own
  width: set together with the tree's, GTK kept neither.
  One timer serves every use: a use while it runs takes over
  its target and its tries."
  [{:keys [pending* running? tick]} target]
  (reset! pending* (assoc target :tries 0))
  (when (compare-and-set! running? false true)
    (try (g/g-timeout-add 100 tick ffi/null)
         (catch :default e (reset! running? false) (throw e)))))

(defn- apply-layout!
  "Brings the valid layout l (named n) back."
  [{:keys [status-label files-btn set-status!] :as ctx} n {:keys [tree? tree-width panel split]}]
  (let [before (g/label-text status-label)]
    (g/gtk-toggle-button-set-active files-btn (if tree? 1 0))
    (show-layout-panel! ctx panel)
    (arm-settle! ctx {:panel panel :tree? tree? :tree-width tree-width :split split})
    ;; a panel with nothing to show said so: that line stays
    (when (= before (g/label-text status-label))
      (set-status! (str "; Layout \"" n "\".")))))

(defn- use-layout!
  "Uses the layout saved under n."
  [{:keys [set-status!] :as ctx} n]
  (let [l (get (saved ctx) n)]
    (cond
      (nil? l) (set-status! (str "; No layout named \"" n "\"."))
      ;; checked before anything moves, so a bad one never half-applies
      (not (layouts/valid? l)) (set-status! (str "; The layout \"" n "\" can't be used: its settings entry is malformed."))
      :else (apply-layout! ctx n l))))

(defn- install
  "The layouts part for one window, reading the editor/console split, the
  tree and the right-hand panels from wctx. The settle timer's callable is
  made here, once. pending* holds the target the timer works toward,
  {:panel :tree? :tree-width :split :tries}; running? is whether its
  timeout is armed. A use while running resets the target and the tries and
  adds no second timeout."
  [wctx]
  (let [state {:pending* (atom nil) :running? (atom false)}
        tick (callable
              (fn [_d] (settle! (merge wctx state)))
              [:pointer] :int :on-throw 0)
        ctx (assoc (merge wctx state) :tick tick)]
    {:fns
     {:layout-save-fn (fn [] (save-layout! ctx))
      :layout-use-fn (fn [n] (use-layout! ctx n))
      :layout-forget-fn (fn [] (save-layouts! ctx {}) ((:set-status! ctx) "; Forgot the saved layouts."))}}))

(def part
  "The layouts part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :layouts}))
