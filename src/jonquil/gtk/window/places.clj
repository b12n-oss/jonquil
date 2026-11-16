(ns jonquil.gtk.window.places
  "The three places around the editor (jonquil.places): each a notebook
  with no border, whose tabs show only when more than one of its pages
  does, and which hides itself when none does. A page's own visible flag
  says whether it shows, so a part keeps showing and hiding its root as it
  always has: the page's notify::visible refreshes its place, and a page
  that becomes visible becomes the current one. Internal to the window, as
  jonquil.gtk.window.status is."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]))

(defn- visible? [w] (pos? (g/gtk-widget-get-visible w)))

(defn- pages
  "The notebook nb's page widgets, in order."
  [nb]
  (mapv #(g/gtk-notebook-get-nth-page nb %) (range (g/gtk-notebook-get-n-pages nb))))

(def ^:private PACK-END
  ;; GtkPackType's GTK_PACK_END, where the terminals' + sits
  1)

(defn refresh!
  "Shows nb's tabs when more than one page shows, or when one does and the
  strip has a button at its end (the terminals' +, whose tabs also carry
  their close buttons), and nb itself when any page shows."
  [nb]
  (let [n (count (filter visible? (pages nb)))
        action? (not (ffi/null? (g/gtk-notebook-get-action-widget nb PACK-END)))]
    (g/gtk-notebook-set-show-tabs nb (if (or (> n 1) (and (pos? n) action?)) 1 0))
    (g/gtk-widget-set-visible nb (if (pos? n) 1 0))))

(defn on-shown!
  "Runs f whenever the place nb goes from hidden to shown."
  [nb f]
  (g/g-signal-connect-data nb "notify::visible"
                           (callable (fn [_w _pspec _d] (when (visible? nb) (f)) nil)
                                     [:pointer :pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- holds-focus?
  "True when the keyboard focus is w or inside it."
  [w]
  (let [root (g/gtk-widget-get-root w)]
    (and (not (ffi/null? root))
         (let [f (g/gtk-root-get-focus root)]
           (and (not (ffi/null? f))
                (or (= f w) (pos? (g/gtk-widget-is-ancestor f w))))))))

(defn show-page!
  "Makes the page widget w the current page of nb, when it is one. When
  the page it covers holds the focus, the focus moves into w, so it never
  lands on the tab strip (a terminal sharing a place with the tree, say)."
  [nb w]
  (let [i (g/gtk-notebook-page-num nb w)
        cur (g/gtk-notebook-get-current-page nb)]
    (when (and (<= 0 i) (not= i cur))
      (let [covered (when (<= 0 cur) (g/gtk-notebook-get-nth-page nb cur))
            had-focus? (and covered (holds-focus? covered))]
        (g/gtk-notebook-set-current-page nb i)
        (when had-focus? (g/gtk-widget-child-focus w g/DIR-TAB-FORWARD))))))

(defn place-of
  "The place (a notebook) the page widget w is in, or nil."
  [w]
  (let [nb (g/gtk-widget-get-ancestor w (g/gtk-notebook-get-type))]
    (when-not (ffi/null? nb) nb)))

(defn reveal!
  "Makes the page widget w the current page of the place it is in, so a
  part about to take the focus is in front of the others there."
  [w]
  (when-let [nb (place-of w)] (show-page! nb w)))

(defn current?
  "True when w is nb's current page and both show."
  [nb w]
  (and (visible? nb) (visible? w)
       (= (g/gtk-notebook-get-current-page nb) (g/gtk-notebook-page-num nb w))))

(defn make
  "A place: a notebook with no border, its strip scrollable, hidden until
  a page shows."
  []
  (let [nb (g/gtk-notebook-new)]
    (g/gtk-notebook-set-show-border nb 0)
    (g/gtk-notebook-set-scrollable nb 1)
    (g/gtk-notebook-set-show-tabs nb 0)
    (g/gtk-widget-set-visible nb 0)
    nb))

(defn add!
  "Adds w to the place nb as a page whose tab is the widget label, and
  refreshes w's place whenever w shows or hides (a w that shows becomes
  the current page). The place is looked up each time, so a page that
  moves (move-page!) refreshes the place it is in now. Answers w's page
  number."
  [nb w label]
  (let [i (g/gtk-notebook-append-page nb w label)]
    (g/g-signal-connect-data w "notify::visible"
                             (callable (fn [_w _pspec _d]
                                         (when-let [now (place-of w)]
                                           (when (visible? w) (show-page! now w))
                                           (refresh! now))
                                         nil)
                                       [:pointer :pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (refresh! nb)
    i))

(defn remove!
  "Takes the page w out of the place nb, which hides itself when nothing
  in it shows any more."
  [nb w]
  (let [i (g/gtk-notebook-page-num nb w)]
    (when (<= 0 i)
      (g/gtk-notebook-remove-page nb i)
      (refresh! nb))))

(defn move-page!
  "Moves the page w, with its tab, from the place from to the end of the
  place to. A w that shows comes to the front there, and keeps the focus
  when it had it. Both places refresh."
  [from to w]
  (let [i (g/gtk-notebook-page-num from w)]
    (when (and (<= 0 i) (not= from to))
      (let [label (g/gtk-notebook-get-tab-label from w)
            had-focus? (holds-focus? w)]
        ;; the notebook drops its refs on remove; these keep both alive
        (g/g-object-ref w)
        (g/g-object-ref label)
        (try
          (g/gtk-notebook-remove-page from i)
          (g/gtk-notebook-append-page to w label)
          (finally
            (g/g-object-unref label)
            (g/g-object-unref w)))
        (refresh! from)
        (when (visible? w) (show-page! to w))
        (refresh! to)
        (when had-focus? (g/gtk-widget-child-focus w g/DIR-TAB-FORWARD))))))

(defn move-action!
  "Moves the button at the end of from's tab strip (the terminals' +) to
  the end of to's."
  [from to]
  (let [b (g/gtk-notebook-get-action-widget from PACK-END)]
    (when (and (not (ffi/null? b)) (not= from to))
      (g/g-object-ref b)
      (try
        (g/gtk-notebook-set-action-widget from ffi/null PACK-END)
        (g/gtk-notebook-set-action-widget to b PACK-END)
        (finally (g/g-object-unref b)))
      (refresh! from)
      (refresh! to))))
