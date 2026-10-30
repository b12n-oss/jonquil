(ns gtk-selftest-window.locations
  "The locations list: the panel on its own in a transient window, then
  the real window's commands that fill it and F8 / Shift+F8 stepping
  through it."
  (:require [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.locations-pane :as lpane]
            [jonquil.locations :as loc]))

(def ^:private fixture
  (str "(defmulti area :shape)\n"
       "(defmethod area :sq [s] (* (:w s) (:w s)))\n"
       "(defmethod area :ci [c] (* 3 (:r c)))\n"
       "(area {:shape :sq :w 2})"))

(defn row-locations-pane-shows
  "show! lists one row per entry as line: text, selects the current one and
  puts its position on the footer."
  [_ui]
  (go-row :locations-pane-shows support/check
    (let [{:keys [win pane]} (on-gtk (let [win (g/gtk-window-new)
                                           pane (lpane/make-pane)]
                                       (g/gtk-window-set-child win (:root pane))
                                       (g/gtk-window-present win)
                                       {:win win :pane pane}))
          lst (assoc (loc/definitions fixture "area") :index 1)]
      (try
        (await-until 2000 20 #(pos? (g/gtk-widget-get-width (:root pane))))
        (on-gtk
         (lpane/show! pane lst)
         (let [rows (lpane/row-texts pane)
               selected (lpane/selected-index pane)
               footer (lpane/footer-text pane)
               title (lpane/title-text pane)]
           (support/check :locations-pane-shows
                          (and (= ["1: (defmulti area :shape)"
                                   "2: (defmethod area :sq [s] (* (:w s) (:w s)))"
                                   "3: (defmethod area :ci [c] (* 3 (:r c)))"]
                                  rows)
                               (= 1 selected) (= "2 of 3" footer) (= "Definitions of area" title))
                          {:rows rows :selected selected :footer footer :title title})))
        (finally (on-gtk (g/gtk-window-close win)))))))
