(ns jonquil.gtk.tab
  "One tab's widgets: an editor with its find bar above it,
  the four popovers parented on its view (quick doc, full doc, rename,
  completion), and the label box the window's notebook shows for it. The
  window owns the notebook, the wiring and the tab list; this namespace
  never touches a notebook.

  Removing a page while a popover is still parented on
  its view spins GTK in a warning loop, so the window calls
  dispose-popovers! before it removes a tab's page."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.gutter :as gutter]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]))

(defn make-tab!
  "Builds one tab. opts is {:id :path :text :theme :profile
  :completion-opts}: :text (default \"\") is what the editor starts with,
  read by the window; :theme and :profile are the window's current theme
  and the tab's own dialect profile (both are per editor);
  :completion-opts is the map jonquil.gtk.completion/make-completion
  takes, minus :ed. GTK-thread only."
  [{:keys [id path text theme profile completion-opts]}]
  (let [ed0 (editor/make-editor)
        gt (gutter/attach! ed0)
        ;; LM3's changed forms: the keys of the forms as they last ran (nil
        ;; until the tab runs), the gutter that marks the others, and the
        ;; refresh the window installs (wire-tab!); on the editor map, which
        ;; every run path carries
        ed (assoc ed0 :changed {:ran (atom nil) :refresh (atom (fn [])) :gutter gt})
        fb (find-bar/make-find-bar ed)
        view (:view ed)
        root (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        label-box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 4)
        label (g/gtk-label-new "Untitled")
        close-btn (g/gtk-button-new-with-label "×")]
    (g/gtk-box-append root (:box fb))
    (g/gtk-widget-set-vexpand (:scroll ed) 1)
    (g/gtk-box-append root (:scroll ed))
    (g/gtk-widget-set-vexpand root 1)
    (g/gtk-button-set-has-frame close-btn 0)
    (g/gtk-widget-set-focus-on-click close-btn 0)
    (g/gtk-box-append label-box label)
    (g/gtk-box-append label-box close-btn)
    (g/gtk-widget-add-css-class view "jonquil-code")
    (editor/apply-theme! ed theme)
    (find-bar/apply-theme! fb theme)
    (gutter/apply-theme! gt theme)
    (editor/set-profile! ed profile)
    (editor/set-text! ed (or text ""))
    {:id id :ed ed :fb fb
     :help-pop (help-popover/make-help-popover view)
     :full-doc-pop (help-popover/make-full-doc-popover view)
     :rename-pop (help-popover/make-rename-popover view)
     :completion (completion/make-completion view (assoc completion-opts :ed ed))
     :palette (palette/make-palette view)
     :root root :label-box label-box :label label :close-btn close-btn
     :path (atom path) :project (atom nil) :discard-warned? (atom false) :last-shown (atom nil)}))

(defn popovers
  "The tab's five popovers, each a jonquil.gtk.popover map."
  [tab]
  [(:help-pop tab) (:full-doc-pop tab) (:rename-pop tab) (:completion tab) (:palette tab)])

(defn dispose-popovers!
  "Hides each open popover, then unparents every one whose parent isn't
  already null (gtk_widget_unparent asserts a parent): the window's own
  unparent-registered-popovers! steps, for one tab."
  [tab]
  (doseq [p (popovers tab)]
    (when (popover/open? p) (popover/hide! p))
    (let [w (:popover p)]
      (when-not (ffi/null? (g/gtk-widget-get-parent w))
        (g/gtk-widget-unparent w))))
  nil)

(defn set-label!
  "Sets the text the notebook shows for the tab."
  [tab text]
  (g/gtk-label-set-text (:label tab) text)
  nil)
