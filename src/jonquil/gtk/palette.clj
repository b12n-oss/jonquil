(ns jonquil.gtk.palette
  "The command palette's popover (plugins design, Part 1, slice S8): an
  entry over a list of jonquil.palette entries, one per tab, built on the
  tab's editor view like the rename popover. Autohide is on, so it's modal
  for typing and GTK closes it on a click outside; the window's
  capture-phase key handler still sees every key first, so Escape closes it
  through the popover registry (:popover/close-all) and window chords keep
  working. Up, Down, PageUp and PageDown move the selection; Return in the
  entry or a click on a row runs the selected entry. Running hides the
  popover first, then calls the on-run handler set-handlers! installed
  with the entry's command id, so a command that opens a popover of its own
  isn't closed by the palette's close."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
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

(defn- handle-key!
  "The entry's own keys: Up/Down by one, PageUp/PageDown by a page. 1 when
  taken, 0 for every other key."
  [pal keyval]
  (condp = keyval
    KEY-UP (do (move! pal -1) 1)
    KEY-DOWN (do (move! pal 1) 1)
    KEY-PAGE-UP (do (move! pal (- PAGE-STEP)) 1)
    KEY-PAGE-DOWN (do (move! pal PAGE-STEP) 1)
    0))

(defn- refocus!
  "Gives view focus back, unless something other than view's own container
  (where GTK leaves it when a popover parented there unmaps) holds it."
  [view]
  (let [f (g/gtk-root-get-focus (g/gtk-widget-get-root view))]
    (when (or (ffi/null? f) (= f (g/gtk-widget-get-parent view)))
      (g/gtk-widget-grab-focus view))))

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
                   :on-run (atom nil) :on-error (atom nil))]
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
    (g/gtk-widget-add-controller entry keyctl)
    ;; every close path ends in "closed", so focus goes back to the editor
    ;; from here, whichever path closed it. Posted (on-gui!), not done in
    ;; the handler: GTK moves focus to the popover's parent (the editor's
    ;; scrolled window) once the popover unmaps, after "closed" runs, so a
    ;; grab here is undone (measured: the root's focus was the scrolled
    ;; window, not the view). And only while focus is still there or
    ;; nowhere: a command the palette just ran may have focused a popover of
    ;; its own (rename's entry), which this must not take back
    (g/g-signal-connect-data (:popover pp) "closed"
                             (callable (fn [_pop _data] (on-gui! #(refocus! view)) nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
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
  jonquil.gtk.help-popover/show-rename! does)."
  [pal entries]
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
