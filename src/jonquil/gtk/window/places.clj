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
  their close buttons), and nb itself when any page shows. on-shown (or
  nil) runs when nb goes from hidden to shown."
  [nb on-shown]
  (let [n (count (filter visible? (pages nb)))
        action? (not (ffi/null? (g/gtk-notebook-get-action-widget nb PACK-END)))
        was? (visible? nb)]
    (g/gtk-notebook-set-show-tabs nb (if (or (> n 1) (and (pos? n) action?)) 1 0))
    (g/gtk-widget-set-visible nb (if (pos? n) 1 0))
    (when (and on-shown (not was?) (pos? n)) (on-shown))))

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

(defn reveal!
  "Makes the page widget w the current page of the place it is in, so a
  part about to take the focus is in front of the others there."
  [w]
  (let [nb (g/gtk-widget-get-ancestor w (g/gtk-notebook-get-type))]
    (when-not (ffi/null? nb) (show-page! nb w))))

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
  "Adds w to the place nb as a page whose tab is label (a string, or a
  widget), and refreshes nb whenever w shows or hides (a w that shows
  becomes the current page). on-shown is refresh!'s. Answers w's page
  number."
  [nb w label on-shown]
  (let [i (g/gtk-notebook-append-page nb w (if (string? label) (g/gtk-label-new label) label))]
    (g/g-signal-connect-data w "notify::visible"
                             (callable (fn [_w _pspec _d]
                                         (when (visible? w) (show-page! nb w))
                                         (refresh! nb on-shown)
                                         nil)
                                       [:pointer :pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (refresh! nb on-shown)
    i))

(defn remove!
  "Takes the page w out of the place nb, which hides itself when nothing
  in it shows any more."
  [nb w]
  (let [i (g/gtk-notebook-page-num nb w)]
    (when (<= 0 i)
      (g/gtk-notebook-remove-page nb i)
      (refresh! nb nil))))
