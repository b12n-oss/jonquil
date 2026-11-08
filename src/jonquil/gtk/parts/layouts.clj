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

(defn- settle!
  "One tick of the settle timer: sets the tree's width and the split toward
  pending*'s target and answers 1 to run again, or 0 when they read back
  or the tries run out. Whenever it answers 0 it clears running?, so the next use can arm a timer. A throw clears running? and is
  rethrown, so the callable's :on-throw 0 reports it and answers 0."
  [{:keys [pending* running? right tree-paned paned showing-panel]}]
  (try
    (let [{:keys [tree? tree-width panel split]} @pending*
          ready? (or (nil? panel) (= panel (showing-panel right)))]
      (when ready?
        (when (and tree? tree-width) (g/gtk-paned-set-position tree-paned tree-width))
        (when split (g/gtk-paned-set-position paned split)))
      (if (or (>= (:tries (swap! pending* update :tries inc)) 20)
              (and ready?
                   (or (not (and tree? tree-width)) (near? tree-width (g/gtk-paned-get-position tree-paned)))
                   (or (not split) (near? split (g/gtk-paned-get-position paned)))))
        (do (reset! running? false) 0)
        1))
    (catch :default e
      (reset! running? false)
      (throw e))))

(defn- install
  "The layouts part for one window, reading the editor/console split, the
  tree and the right-hand panels from wctx. The settle timer's callable is
  made here, once. pending* holds the target the timer works toward,
  {:panel :tree? :tree-width :split :tries}; running? is whether its
  timeout is armed. A use while running resets the target and the tries and
  adds no second timeout."
  [{:keys [settings-path* status-label cv right tree-pane tree-paned paned files-btn
           panel-focus-fn showing-panel show-right-panel! hide-right-panel! set-status!]}]
  (let [saved (fn [] (or (:layouts (settings/load-settings @settings-path*)) {}))
        said (fn [s] (set-status! s))
        save! (fn [m] (when-let [err (settings/save-settings! @settings-path* {:layouts m})]
                        (cview/append! cv :system (str "; settings: " err "\n"))))
        pending* (atom nil)
        running? (atom false)
        tick (callable
              (fn [_d] (settle! {:pending* pending* :running? running? :right right
                                 :tree-paned tree-paned :paned paned
                                 :showing-panel showing-panel}))
              [:pointer] :int :on-throw 0)]
    {:fns
     {:layout-save-fn
      (fn []
        (let [panel (showing-panel right)
              l (cond-> {:tree? (pos? (g/gtk-widget-get-visible (:root tree-pane)))
                         :tree-width (g/gtk-paned-get-position tree-paned)
                         :panel panel
                         :split (g/gtk-paned-get-position paned)})]
          (save! (layouts/save (saved) l))
          (said (str "; Saved the layout \"" (layouts/layout-name l) "\"."))))
      :layout-use-fn
      (fn [n]
        (let [{:keys [tree? tree-width panel split] :as l} (get (saved) n)]
          (cond
            (nil? l) (said (str "; No layout named \"" n "\"."))
            ;; checked before anything moves, so a bad one never half-applies
            (not (layouts/valid? l)) (said (str "; The layout \"" n "\" can't be used: its settings entry is malformed."))
            :else
            (let [before (g/label-text status-label)]
              (g/gtk-toggle-button-set-active files-btn (if tree? 1 0))
              ;; the panel showing goes unless it's the layout's own
              (when-let [k (showing-panel right)]
                (when (not= k panel) (hide-right-panel! right k)))
              (when panel
                (if (= panel (showing-panel right))
                  (show-right-panel! right panel)
                  (panel-focus-fn panel)))
              ;; GTK lays the tree and a panel out over its next frames (and
              ;; a panel shown from an evaluator's answer arrives later), so
              ;; the tree's width and the split are set every 100 ms until
              ;; they read back, for at most 2 s. The panel opens at its own
              ;; width: set together with the tree's, GTK kept neither.
              ;; One timer serves every use: a use while it runs takes over
              ;; its target and its tries.
              (reset! pending* {:panel panel :tree? tree? :tree-width tree-width :split split :tries 0})
              (when (compare-and-set! running? false true)
                (try (g/g-timeout-add 100 tick ffi/null)
                     (catch :default e (reset! running? false) (throw e))))
              ;; a panel with nothing to show said so: that line stays
              (when (= before (g/label-text status-label))
                (said (str "; Layout \"" n "\".")))))))
      :layout-forget-fn (fn [] (save! {}) (said "; Forgot the saved layouts."))}}))

(def part
  "The layouts part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :layouts}))
