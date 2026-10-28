(ns gtk-selftest-window.tab-widget
  "jonquil.gtk.tab in isolation (tabs plan P2.T1): a tab's widgets build
  with the window's theme and profile, and dispose-popovers! leaves none of
  its four popovers parented (spike: removing a page with a popover still
  parented on its view spun GTK in a warning loop).

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each."
  (:require [gtk-async :refer [go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.dialect :as dialect]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.tab :as tab]
            [jonquil.theme :as theme]))

(def ^:private completion-opts
  {:ns-fn (fn [] 'user)
   :candidates-fn (fn [& _] [])
   :doc-fn (fn [& _] nil)
   :close-others! (fn [& _] false)
   :on-error (fn [_] nil)})

(defn- make []
  (tab/make-tab! {:id 99 :path nil :text "(ns x.y)\n(+ 1 2)\n"
                  :theme (theme/theme theme/default-theme) :profile dialect/jolt
                  :completion-opts completion-opts}))

(defn row-tab-widget-builds
  "A tab's editor holds its text, carries the jonquil-code class, takes a
  label, and owns exactly five popovers (the palette is the fifth, step S8)."
  [_ui]
  (go-row :tab-widget-builds support/check
    (on-gtk (let [t (make)
                  _ (tab/set-label! t "x.clj")
                  v {:text (editor/editor-text (:ed t))
                     :label (g/label-text (:label t))
                     :class (g/gtk-widget-has-css-class (:view (:ed t)) "jonquil-code")
                     :popovers (count (tab/popovers t))
                     :id (:id t)}]
              (support/check :tab-widget-builds
                             (= {:text "(ns x.y)\n(+ 1 2)\n" :label "x.clj" :class 1 :popovers 5 :id 99} v)
                             v)))))

(defn row-tab-widget-dispose-unparents
  "In a throwaway window, with the help popover open, dispose-popovers!
  closes it and leaves all four popovers without a parent."
  [_ui]
  (go-row :tab-widget-dispose-unparents support/check
    (let [{:keys [win t]} (on-gtk (let [win (g/gtk-window-new)
                                        t (make)]
                                    (g/gtk-window-set-default-size win 400 300)
                                    (g/gtk-window-set-child win (:root t))
                                    (g/gtk-window-present win)
                                    {:win win :t t}))]
      (try
        (on-gtk (help-popover/show-doc! (:help-pop t) "doc text")
                ;; Unparenting drops a popover's last reference, so the row
                ;; holds one of its own while it reads the parent back.
                (let [open-before (popover/open? (:help-pop t))
                      held (mapv (fn [p] (g/g-object-ref (:popover p))) (tab/popovers t))
                      _ (tab/dispose-popovers! t)
                      parents (mapv (fn [w] (ffi/null? (g/gtk-widget-get-parent w))) held)
                      open-after (mapv popover/open? (tab/popovers t))
                      _ (run! g/g-object-unref held)]
                  (support/check :tab-widget-dispose-unparents
                                 (and open-before (every? true? parents) (every? false? open-after))
                                 {:open-before open-before :parents-null parents :open-after open-after})))
        (finally (on-gtk (g/gtk-window-close win)))))))
