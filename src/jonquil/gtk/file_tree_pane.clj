(ns jonquil.gtk.file-tree-pane
  "The file tree's widget (file-tree spec): a header (the root's name, a
  refresh button and Open Folder...), a scrolled GtkListBox with one row per
  entry of jonquil.file-tree/rows, and a body label for a root that's
  missing or can't be read. Every change rebuilds the list from the model
  (plan ruling 4; the spike measured 61 ms at 1,000 rows). A click (single
  click activates) or Return on a folder toggles it; on a file it calls
  :on-open. Left collapses and Right expands the selected folder.

  The list and header carry the jonquil-code CSS class, so the window's
  shared provider gives the tree the theme's colours and the zoomed font."
  (:require [jolt.ffi :as ffi]
            [jonquil.file-tree :as ft]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.ffi :as g])
  (:import (java.io File)))

(def ^:private KEY-LEFT 0xff51)
(def ^:private KEY-RIGHT 0xff53)

(defn visible-rows
  "The rows (jonquil.file-tree/rows) the list shows now, in order."
  [pane]
  @(:rows pane))

(defn row-widgets
  "[{:row :toggle-label :name-label}] for the list's rows, in order."
  [pane]
  @(:row-widgets pane))

(defn- row-box
  "One row's child: an indent, the ▸/▾ toggle (blank for a file) and the name."
  [{:keys [name depth dir? expanded? dim?]}]
  (let [box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 4)
        indent (g/gtk-label-new (apply str (repeat (* 2 depth) " ")))
        toggle (g/gtk-label-new (cond (not dir?) " " expanded? "▾" :else "▸"))
        label (g/gtk-label-new name)]
    (g/gtk-label-set-xalign label 0.0)
    (when dim? (g/gtk-widget-add-css-class label "dim-label"))
    (g/gtk-box-append box indent)
    (g/gtk-box-append box toggle)
    (g/gtk-box-append box label)
    {:box box :toggle-label toggle :name-label label}))

(defn- root-problem
  "The body text for a model whose root can't be shown, or nil."
  [{:keys [root]}]
  (cond
    (nil? root) "No folder open"
    (not (and (.isDirectory (File. ^String root)) (.canRead (File. ^String root)))) (str "Can't read " root)
    :else nil))

(defn show-model!
  "Shows model: rebuilds every row from (jonquil.file-tree/rows model), sets
  the header to the root's folder name, and shows the body label when the
  root can't be read or there is none. GTK-thread only."
  [{:keys [list header-label body-label state rows row-widgets]} model]
  (reset! state model)
  (let [problem (root-problem model)
        rs (if problem [] (ft/rows model))]
    (g/gtk-list-box-remove-all list)
    (reset! rows rs)
    (reset! row-widgets
            (vec (map-indexed (fn [i r]
                                (let [{:keys [box] :as w} (row-box r)]
                                  (g/gtk-list-box-append list box)
                                  (assoc (dissoc w :box) :row (g/gtk-list-box-get-row-at-index list i))))
                              rs)))
    (g/gtk-label-set-text header-label (if-let [r (:root model)] (.getName (File. ^String r)) "No folder"))
    (g/gtk-label-set-text body-label (or problem ""))
    (g/gtk-widget-set-visible body-label (if problem 1 0))
    nil))

(defn refresh!
  "Re-reads every expanded folder (dropping ones that are gone) and rebuilds."
  [pane]
  (show-model! pane (ft/prune @(:state pane))))

(defn- canonical [p]
  (try (.getCanonicalPath (File. ^String p)) (catch Exception _ p)))

(defn- index-of
  "path's row index. Rows hold canonical paths and tabs keep what they were
  given (a relative argument, a /var path on macOS), so path is
  canonicalised first (final review finding 3)."
  [pane path]
  (let [c (canonical path)]
    (first (keep-indexed (fn [i r] (when (= c (:path r)) i)) (visible-rows pane)))))

(defn select-path!
  "Selects path's row when it's visible, else selects nothing. Never opens
  anything."
  [{:keys [list] :as pane} path]
  (if-let [i (and path (index-of pane path))]
    (g/gtk-list-box-select-row list (g/gtk-list-box-get-row-at-index list i))
    (g/gtk-list-box-unselect-all list))
  nil)

(defn selected-path
  "The selected row's path, or nil."
  [{:keys [list] :as pane}]
  (let [row (g/gtk-list-box-get-selected-row list)]
    (when-not (ffi/null? row)
      (:path (get (visible-rows pane) (g/gtk-list-box-row-get-index row))))))

(defn- rebuild-keeping-selection! [pane model path]
  (show-model! pane model)
  (select-path! pane path))

(defn activate-path!
  "What a click or Return on path's row does: a folder toggles, a file
  calls :on-open with its path, an error row (or a row no longer shown)
  does nothing."
  [{:keys [state on-open] :as pane} path]
  (when-let [{:keys [dir? error?]} (some (fn [r] (when (= path (:path r)) r)) (visible-rows pane))]
    (cond
      error? nil
      dir? (rebuild-keeping-selection! pane (ft/toggle @state path) path)
      :else (on-open path)))
  nil)

(defn activate-index!
  "activate-path! for row i. Also the self-test's way in."
  [pane i]
  (when-let [{:keys [path]} (get (visible-rows pane) i)]
    (activate-path! pane path)))

(defn- key-target
  "The selected folder row Left would collapse or Right would expand, or nil."
  [{:keys [list] :as pane} keyval]
  (let [row (g/gtk-list-box-get-selected-row list)
        r (when-not (ffi/null? row) (get (visible-rows pane) (g/gtk-list-box-row-get-index row)))]
    (when (and r (:dir? r)
               (or (and (= keyval KEY-LEFT) (:expanded? r))
                   (and (= keyval KEY-RIGHT) (not (:expanded? r)))))
      r)))

(defn- handles-key? [pane keyval] (some? (key-target pane keyval)))

(defn- on-key
  "Left collapses an expanded folder, Right expands a collapsed one; both
  keep the folder selected."
  [{:keys [state] :as pane} keyval]
  (when-let [r (key-target pane keyval)]
    (rebuild-keeping-selection! pane (ft/toggle @state (:path r)) (:path r))))

(defn focus!
  "Gives the list keyboard focus."
  [pane]
  (g/gtk-widget-grab-focus (:list pane)))

(defn focused?
  "True when keyboard focus is anywhere inside the pane."
  [pane win]
  (let [f (g/gtk-root-get-focus win)]
    (and (not (ffi/null? f)) (not (zero? (g/gtk-widget-is-ancestor f (:root pane)))))))

(defn make-pane
  "Builds the pane, empty (show-model! fills it). opts is {:on-open (fn
  [path]) :on-open-folder (fn []) :on-error (fn [msg])}; a throw in any of
  its callbacks goes to :on-error. GTK-thread only."
  [{:keys [on-open on-open-folder on-error]}]
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        header (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        header-label (g/gtk-label-new "No folder")
        refresh-btn (g/gtk-button-new-with-label "⟳")
        open-folder-btn (g/gtk-button-new-with-label "Open Folder…")
        scroll (g/gtk-scrolled-window-new)
        list (g/gtk-list-box-new)
        body-label (g/gtk-label-new "")
        keyctl (g/gtk-event-controller-key-new)
        pane {:root root :header-label header-label :refresh-btn refresh-btn
              :open-folder-btn open-folder-btn :list list :body-label body-label
              :state (atom (ft/load-model nil)) :rows (atom []) :row-widgets (atom [])
              :on-open on-open}
        guard (fn [what f] (fn [& args]
                             (try (apply f args)
                                  (catch :default e (on-error (str "; " what ": " (ex-message e)))))))]
    (g/gtk-label-set-xalign header-label 0.0)
    (g/gtk-widget-set-hexpand header-label 1)
    (g/gtk-box-append header header-label)
    (g/gtk-box-append header refresh-btn)
    (g/gtk-box-append header open-folder-btn)
    (g/gtk-widget-add-css-class header "jonquil-code")
    (g/gtk-widget-add-css-class list "jonquil-code")
    (g/gtk-scrolled-window-set-child scroll list)
    (g/gtk-widget-set-vexpand scroll 1)
    (g/gtk-widget-set-visible body-label 0)
    (g/gtk-box-append root header)
    (g/gtk-box-append root body-label)
    (g/gtk-box-append root scroll)
    (g/g-signal-connect-data list "row-activated"
                             ;; The rebuild removes every row, the activated one
                             ;; included, so it runs on the next main-loop turn,
                             ;; never inside the row's own signal emission.
                             (callable (guard "tree" (fn [_box row _data]
                                                                                                              ;; The row's path is read now, so a rebuild landing
                                                       ;; before the deferred run can't redirect it to
                                                       ;; another row (final review finding 7).
                                                       (when-let [{:keys [path]} (get (visible-rows pane) (g/gtk-list-box-row-get-index row))]
                                                         (on-gui! (guard "tree" (fn [] (activate-path! pane path)))))
                                                       nil))
                                       [:pointer :pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data keyctl "key-pressed"
                             (callable (fn [_ctl keyval _kc _state _data]
                                         ;; Decided now, rebuilt next turn (same reason as
                                         ;; row-activated above).
                                         (try (if (handles-key? pane keyval)
                                                (do (on-gui! (guard "tree" (fn [] (on-key pane keyval)))) 1)
                                                0)
                                              (catch :default e (on-error (str "; tree: " (ex-message e))) 1)))
                                       [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller list keyctl)
    (g/g-signal-connect-data refresh-btn "clicked"
                             (callable (guard "refresh" (fn [_ _] (refresh! pane) nil)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data open-folder-btn "clicked"
                             (callable (guard "open folder" (fn [_ _] (on-open-folder) nil)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    pane))
