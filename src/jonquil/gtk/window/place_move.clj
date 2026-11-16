(ns jonquil.gtk.window.place-move
  "Moving a part to another place while the window is open (issue #33's
  Move to): its pages leave their place for the new one, and settings.edn's
  :layout is rewritten so the next window opens the same way. The tab menu
  (a right-click on a part's tab) and the palette's Move commands both run
  move!. Internal to the window, as jonquil.gtk.window.status is."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.places :as win-places]
            [jonquil.gtk.window.right-slot :as win-right-slot]
            [jonquil.places :as places]
            [jonquil.settings :as settings]))

(def ^:private TREE-WIDTH
  ;; the tree's width when settings.edn has none, as at startup
  240)

(defn- visible? [w] (pos? (g/gtk-widget-get-visible w)))

(defn- tree-paned
  "The split between the left-hand place and the rest of the window."
  [right]
  (g/gtk-widget-get-parent (get-in right [:places :left])))

(defn- save-tree-width!
  "Keeps the left split's position as the tree's :width, before the tree
  leaves the left. Answers the save's error or nil."
  [right path]
  (let [tree (:file-tree (settings/load-settings path))]
    (settings/save-settings! path {:file-tree (assoc tree :width (max 1 (g/gtk-paned-get-position (tree-paned right))))})))

(defn- hide-other-panels!
  "Hides the panels other than id in the place nb: a place shows one
  panel at a time."
  [right id nb]
  (doseq [[k r] (win-right-slot/right-roots right)
          :when (and (not= k id) (= nb (win-right-slot/part-place right k)))]
    (g/gtk-widget-set-visible r 0)))

(defn- move-pages!
  "Moves the part id's pages (and, for the terminal, the + at the end of
  its strip) from the place from to the place to, and records id's new
  place."
  [right id to-name]
  (let [from (win-right-slot/part-place right id)
        to (get-in right [:places to-name])
        panel? (contains? (win-right-slot/right-roots right) id)
        pages (get (some-> (:pages-of* right) deref) id)]
    (when (and panel? (some visible? pages)) (hide-other-panels! right id to))
    (doseq [w pages] (win-places/move-page! from to w))
    (when (= id :terminal) (win-places/move-action! from to))
    (swap! (:place-of* right) assoc id to-name)))

(defn move!
  "Moves the part id to the place to-name (:left, :right or :bottom), then
  rewrites settings.edn's :layout (jonquil.places/moved). The tree's width
  is the left split's position, so it's saved before the tree leaves the
  left and set from the saved one when it arrives. m holds :right and
  :settings-path*. Answers nil when id was already there or it moved and
  saved, else the save's error. GTK-thread only."
  [{:keys [right settings-path*]} id to-name]
  (let [from-name (win-right-slot/place-name right id)
        path @settings-path*]
    (when (not= from-name to-name)
      (let [width-err (when (and (= id :file-tree) (= :left from-name)) (save-tree-width! right path))]
        (move-pages! right id to-name)
        (when (and (= id :file-tree) (= :left to-name))
          (g/gtk-paned-set-position (tree-paned right)
                                    (or (:width (:file-tree (settings/load-settings path))) TREE-WIDTH)))
        (or width-err
            (settings/save-settings! path {:layout (places/moved (:layout (settings/load-settings path)) id to-name)}))))))

(defn panel-or-part
  "The part a palette Move command names: :panel is the panel on screen
  (nil when none is), any other id is itself."
  [right id]
  (if (= id :panel) (win-right-slot/showing-panel right) id))

;; --- the tab menu ---------------------------------------------------------

(defn- rect-alloc [[x y w h]]
  (let [r (ffi/alloc 16)]
    (ffi/write r :int (int x) 0)
    (ffi/write r :int (int y) 4)
    (ffi/write r :int (int w) 8)
    (ffi/write r :int (int h) 12)
    r))

(defn- on-click! [button f]
  (g/g-signal-connect-data button "clicked" (callable (fn [_b _d] (f) nil) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- build-menu
  "The tab menu, built once per window: a popover over the window's column
  (a GtkBox, which unparents it on dispose) with a Move to button per
  place. Each button moves the part in :id* and says a failed save with
  say!."
  [{:keys [right] :as m} say!]
  (let [popover (g/gtk-popover-new)
        box (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        id* (atom nil)
        buttons (into {} (for [place places/places
                               :let [b (g/gtk-button-new-with-label (places/move-label place))]]
                           (do (g/gtk-button-set-has-frame b 0)
                               (g/gtk-box-append box b)
                               (on-click! b (fn []
                                              (g/gtk-popover-popdown popover)
                                              (when-let [id @id*]
                                                (when-let [err (move! m id place)] (say! err)))))
                               [place b])))]
    (g/gtk-popover-set-child popover box)
    (g/gtk-popover-set-autohide popover 1)
    (g/gtk-widget-set-parent popover (g/gtk-window-get-child (:win right)))
    {:popover popover :id* id* :buttons buttons}))

(defn open-menu!
  "Pops the tab menu up under tab for the part id, its own place's item
  greyed. menu* holds the menu once built. Answers the menu,
  {:popover :id* :buttons {place button}}."
  [{:keys [right] :as m} menu* say! id tab]
  (let [{:keys [popover id* buttons]} (or @menu* (reset! menu* (build-menu m say!)))
        here (win-right-slot/place-name right id)
        parent (g/gtk-widget-get-parent popover)
        [x y] (or (g/compute-point tab parent 0 0) [0 0])
        r (rect-alloc [(Math/round (double x)) (Math/round (double y))
                       (max 1 (g/gtk-widget-get-width tab)) (max 1 (g/gtk-widget-get-height tab))])]
    (reset! id* id)
    (doseq [[place b] buttons] (g/gtk-widget-set-sensitive b (if (= place here) 0 1)))
    (try (g/gtk-popover-set-pointing-to popover r)
         (finally (ffi/free r)))
    (g/gtk-popover-popup popover)
    @menu*))

(defn install!
  "Lets the window move its parts: a right-click on a part's tab opens the
  tab menu (right's :tab-menu*), and right's :move!* runs a palette Move
  command, (f id place), id :panel meaning the panel on screen. m holds
  :right and :settings-path*; say! puts a line in the console."
  [{:keys [right] :as m} say!]
  (let [menu* (atom nil)
        said! (fn [err] (say! (str "; settings: " err "\n")))]
    (reset! (:tab-menu* right) (fn [id tab] (open-menu! m menu* said! id tab)))
    (reset! (:move!* right)
            (fn [id place]
              (if-let [part (panel-or-part right id)]
                (some-> (move! m part place) said!)
                (say! "; No panel is showing, so there's none to move.\n"))))))
