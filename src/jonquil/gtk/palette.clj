(ns jonquil.gtk.palette
  "The command palette's popover (plugins design, Part 1, slice S8): an
  entry over a list of jonquil.palette entries, one per tab, built on the
  tab's editor view like the rename popover. Autohide is on, so it's modal
  for typing and GTK closes it on a click outside; the window's
  capture-phase key handler still sees every key first, so Escape closes it
  through the popover registry (:popover/close-all); every other window
  shortcut waits while the entry has focus, as in the rename popover
  (jonquil.command-registry/window-answer's fixed stage). Up, Down, PageUp and PageDown move the selection; Return in the
  entry or a click on a row runs the selected entry. Running hides the
  popover first, then calls the on-run handler set-handlers! installed
  with the entry's command id, so a command that opens a popover of its own
  isn't closed by the palette's close."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.palette :as data]))

(def ^:private WIDTH 560)
(def ^:private MAX-HEIGHT 320)
(def ^:private PAGE-STEP 10)

(def ^:private KEY-UP 0xff52)
(def ^:private KEY-DOWN 0xff54)
(def ^:private KEY-PAGE-UP 0xff55)
(def ^:private KEY-PAGE-DOWN 0xff56)

(defn- report! [pal where e]
  (when-let [f @(:on-error pal)] (f (str "; " where ": " (ex-message e)))))

(defn- select! [pal]
  (let [{:keys [shown index]} @(:state pal)
        lb (:list-box pal)]
    (if (seq shown)
      (g/gtk-list-box-select-row lb (g/gtk-list-box-get-row-at-index lb index))
      (g/gtk-list-box-unselect-all lb))))

(defn- render!
  "Rebuilds the list's rows from the shown entries, one left-aligned label
  each (as jonquil.gtk.completion's render! builds its rows), then selects
  the current index."
  [pal]
  (let [lb (:list-box pal)]
    (g/gtk-list-box-remove-all lb)
    (doseq [e (:shown @(:state pal))]
      (let [label (g/gtk-label-new (data/row-text e))]
        (g/gtk-label-set-xalign label 0.0)
        (g/gtk-widget-set-halign label g/ALIGN-START)
        (g/gtk-list-box-append lb label)))
    (select! pal)))

(defn- refilter! [pal]
  (let [query (or (g/editable-text (:entry pal)) "")]
    (swap! (:state pal) (fn [st] (assoc st :shown (data/filter-entries (:all st) query) :index 0)))
    (render! pal)))

(defn- move! [pal delta]
  (swap! (:state pal) data/move delta)
  (select! pal))

(defn run-selected!
  "Runs the selected entry: hides the popover, then calls on-run with its
  command id. Does nothing when no entry is shown."
  [pal]
  (let [{:keys [shown index]} @(:state pal)]
    (when-let [{:keys [id]} (get shown index)]
      (popover/hide! pal)
      (when-let [f @(:on-run pal)] (f id)))))

(defn handle-key!
  "The entry's own keys, on its capture-phase controller (public for
  rows): Up/Down by one, PageUp/PageDown by a page. 1 when taken, 0 for
  every other key, and 0 for every key while an IME preedit is active, so
  a composition's own arrows (its candidate list) reach the input method,
  as jonquil.gtk.find-bar/handle-key! does."
  [pal keyval]
  (if @(:preedit-active? pal)
    0
    (condp = keyval
      KEY-UP (do (move! pal -1) 1)
      KEY-DOWN (do (move! pal 1) 1)
      KEY-PAGE-UP (do (move! pal (- PAGE-STEP)) 1)
      KEY-PAGE-DOWN (do (move! pal PAGE-STEP) 1)
      0)))

(defn make-palette
  "Builds the palette on view (an editor's GtkTextView): the
  jonquil.gtk.popover map (autohide, below the caret) plus :entry,
  :list-box, :state (an atom of {:all :shown :index}) and the :on-run and
  :on-error handler atoms set-handlers! fills. Every signal handler runs
  inside try/catch, since a throw out of a GTK callback kills the process;
  a throw goes to :on-error."
  [view]
  (let [entry (g/gtk-entry-new)
        list-box (g/gtk-list-box-new)
        scroll (g/gtk-scrolled-window-new)
        box (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        keyctl (g/gtk-event-controller-key-new)
        pp (popover/make-popover view {:autohide? true :position :bottom})
        pal (assoc pp :entry entry :list-box list-box
                   :state (atom {:all [] :shown [] :index 0})
                   :on-run (atom nil) :on-error (atom nil) :preedit-active? (atom false))]
    (g/gtk-widget-set-size-request entry WIDTH -1)
    (g/gtk-list-box-set-selection-mode list-box g/SELECTION-MODE-BROWSE)
    (g/gtk-scrolled-window-set-policy scroll g/POLICY-NEVER g/POLICY-AUTOMATIC)
    (g/gtk-scrolled-window-set-propagate-natural-height scroll 1)
    (g/gtk-scrolled-window-set-max-content-height scroll MAX-HEIGHT)
    (g/gtk-scrolled-window-set-child scroll list-box)
    (g/gtk-box-append box entry)
    (g/gtk-box-append box scroll)
    (popover/set-child! pp box)
    (g/g-signal-connect-data entry "changed"
                             (callable (fn [_ _] (try (refilter! pal) (catch :default e (report! pal "palette" e))) nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data entry "activate"
                             (callable (fn [_ _] (try (run-selected! pal) (catch :default e (report! pal "palette" e))) nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data list-box "row-activated"
                             (callable (fn [_ row _]
                                         (try
                                           (swap! (:state pal) assoc :index (g/gtk-list-box-row-get-index row))
                                           (run-selected! pal)
                                           (catch :default e (report! pal "palette" e)))
                                         nil)
                                       [:pointer :pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data keyctl "key-pressed"
                             (callable (fn [_ctl keyval _keycode _state _data]
                                         (try (handle-key! pal keyval)
                                              (catch :default e (report! pal "palette" e) 1)))
                                       [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; the entry delegates its GtkEditable to an inner GtkText, and that
    ;; delegate carries "preedit-changed" (as find_bar.clj's entries)
    (g/g-signal-connect-data (g/gtk-editable-get-delegate entry) "preedit-changed"
                             (callable (fn [_ preedit _]
                                         (try (reset! (:preedit-active? pal) (not (empty? preedit)))
                                              (catch :default e (report! pal "palette" e)))
                                         nil)
                                       [:pointer :string :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; capture, as find_bar's own controller: the entry's inner GtkText (its
    ;; input method, its own bindings) sees keys first in the bubble phase
    (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
    (g/gtk-widget-add-controller entry keyctl)
    ;; every close path gives the editor focus back, once GTK has finished
    ;; closing (see jonquil.gtk.popover/refocus-view-on-close!)
    (popover/refocus-view-on-close! pp)
    pal))

(defn set-handlers!
  "Installs pal's on-run (fn [command-id]) and on-error (fn [message])."
  [pal {:keys [on-run on-error]}]
  (reset! (:on-run pal) on-run)
  (reset! (:on-error pal) on-error)
  nil)

(defn open!
  "Shows entries in pal, all of them, the first selected, with the entry
  cleared; pops up at the caret and only then focuses the entry, since a
  later call touching selection can revoke an earlier one's (as
  jonquil.gtk.help-popover/show-rename! does). No composition can be
  under way in an entry that's about to take focus, so it starts with none:
  one the palette was closed over (Escape, a click outside) may never have
  reported its end."
  [pal entries]
  (reset! (:preedit-active? pal) false)
  (reset! (:state pal) {:all entries :shown entries :index 0})
  (g/gtk-editable-set-text (:entry pal) "")
  (render! pal)
  (popover/show-at-caret! pal)
  (g/gtk-widget-grab-focus (:entry pal))
  nil)

(defn set-query!
  "Sets the entry's text, which refilters the list as typing does."
  [pal text]
  (g/gtk-editable-set-text (:entry pal) text)
  nil)

(defn shown-docs
  "The shown entries' docs, in order, for rows."
  [pal]
  (mapv :doc (:shown @(:state pal))))
