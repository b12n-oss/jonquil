(ns jonquil.gtk.prefs-window
  "The Preferences window's widgets: a notebook whose Keys tab holds a
  key set chooser, a search box, a list with one row per binding (the command's description,
  a button showing its key, where the key comes from), a message line with
  two answer buttons, and Open keys.edn and Reset this key. It shows what
  it is given and calls its handlers; jonquil.gtk.parts.prefs decides what
  a click or a key press means."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(def ^:private OS-NAME (System/getProperty "os.name"))

(def WIDTH 720)
(def HEIGHT 520)

(defn- call!
  "Runs the handler k with args, when there is one."
  [pw k & args]
  (when-let [f (get @(:handlers pw) k)]
    (apply f args)))

(defn- button!
  "A button labelled label that calls f."
  [label f]
  (let [b (g/gtk-button-new-with-label label)]
    (g/g-signal-connect-data b "clicked" (callable (fn [_b _d] (f) nil) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    b))

(defn set-handlers!
  "Registers {:on-search (fn [text]) :on-record (fn [index]) :on-answer (fn
  [yes?]) :on-open-file (fn []) :on-reset (fn [index-or-nil]) :on-key-set
  (fn [index]), the chooser's new choice, :on-key (fn
  [keyval state physical]) -> 1 when the key is used up, else 0; physical
  is jonquil.gtk.editor/physical-key's answer, the key's name or nil}."
  [pw m]
  (reset! (:handlers pw) m)
  nil)

(defn search-text
  "What the search box holds."
  [pw]
  (or (ffi/ptr->string (g/gtk-editable-get-text (:search pw))) ""))

(defn key-set-index
  "The place of the key set the chooser shows."
  [pw]
  (g/gtk-drop-down-get-selected (:key-set pw)))

(defn set-key-set-index!
  "Makes the chooser show the key set at index. The chooser then calls
  :on-key-set, as it does for a click."
  [pw index]
  (g/gtk-drop-down-set-selected (:key-set pw) index)
  nil)

(defn selected-index
  "The selected row's index, or nil."
  [pw]
  (let [row (g/gtk-list-box-get-selected-row (:list pw))]
    (when-not (ffi/null? row) (g/gtk-list-box-row-get-index row))))

(defn key-label
  "What a row's key button says: its chord, or an invitation when it has
  none."
  [{:keys [chord]}]
  (if (= "" chord) "Add a key" chord))

(defn- row-widget
  "One row: the description, the key button and the source. A fixed row's
  button is greyed out. Answers [widget button]."
  [pw i {:keys [doc source fixed?] :as row}]
  (let [box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 8)
        what (g/gtk-label-new (or doc ""))
        btn (button! (key-label row) #(call! pw :on-record i))
        from (g/gtk-label-new (or source ""))]
    (g/gtk-label-set-xalign what 0.0)
    (g/gtk-widget-set-hexpand what 1)
    (g/gtk-label-set-ellipsize what g/PANGO-ELLIPSIZE-END)
    (g/gtk-widget-add-css-class from "dim-label")
    (g/gtk-widget-set-size-request from 90 -1)
    (g/gtk-label-set-xalign from 0.0)
    (when fixed?
      (g/gtk-widget-set-sensitive btn 0)
      (g/gtk-widget-set-tooltip-text btn "This key is part of a chain of keys and can't be changed here."))
    (doseq [w [what btn from]] (g/gtk-box-append box w))
    (g/gtk-widget-set-margin-start box 6)
    (g/gtk-widget-set-margin-end box 6)
    [box btn]))

(defn show-rows!
  "Replaces the list with rows (jonquil.key-prefs/rows' shape), keeping the
  selection on the same index when it still exists."
  [pw rows]
  (let [lb (:list pw)
        keep-i (selected-index pw)
        built (vec (map-indexed (fn [i r] (row-widget pw i r)) rows))]
    (g/gtk-list-box-remove-all lb)
    (doseq [[w _] built] (g/gtk-list-box-append lb w))
    (reset! (:rows pw) (vec rows))
    (reset! (:buttons pw) (mapv second built))
    (when (and keep-i (< keep-i (count rows)))
      (g/gtk-list-box-select-row lb (g/gtk-list-box-get-row-at-index lb keep-i)))
    nil))

(defn rows
  "The rows the list shows."
  [pw]
  @(:rows pw))

(defn set-key-label!
  "Sets what row index's key button says."
  [pw index text]
  (when-let [b (get @(:buttons pw) index)]
    (g/gtk-button-set-label b text))
  nil)

(defn say!
  "Puts text on the message line. answers is nil, or [yes no], the labels
  of the two answer buttons, which show only then."
  [pw text answers]
  (g/gtk-label-set-text (:message pw) text)
  (reset! (:said pw) {:text text :answers answers})
  (let [[yes no] answers]
    (when yes (g/gtk-button-set-label (:yes-btn pw) yes))
    (when no (g/gtk-button-set-label (:no-btn pw) no))
    (g/gtk-widget-set-visible (:yes-btn pw) (if answers 1 0))
    (g/gtk-widget-set-visible (:no-btn pw) (if answers 1 0)))
  nil)

(defn said
  "{:text :answers}, what the message line shows."
  [pw]
  @(:said pw))

(defn- connect-input!
  "The search box's changes and the window's keys, taken in the capture
  phase so a recording sees a key before the search box does."
  [{:keys [win search] :as pw}]
  (let [keyctl (g/gtk-event-controller-key-new)]
    (g/g-signal-connect-data search "search-changed"
                             (callable (fn [_e _d] (call! pw :on-search (search-text pw)) nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
    (g/g-signal-connect-data keyctl "key-pressed"
                             (callable (fn [_ctl keyval keycode state _data]
                                         (or (call! pw :on-key (g/gdk-keyval-to-lower keyval) state
                                                    (editor/physical-key OS-NAME keyval keycode state))
                                             0))
                                       [:pointer :uint :uint :uint :pointer] :int :on-throw 0)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data (:key-set pw) "notify::selected"
                             (callable (fn [_d _pspec _data] (call! pw :on-key-set (key-set-index pw)) nil)
                                       [:pointer :pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller win keyctl)))

(defn- keys-page
  "Packs the Keys tab: the key set chooser, search, list, message row,
  bottom buttons."
  [{:keys [search scroll message yes-btn no-btn key-set] lb :list :as pw}]
  (let [page (g/gtk-box-new g/ORIENTATION-VERTICAL 6)
        set-row (g/gtk-box-new g/ORIENTATION-HORIZONTAL 8)
        set-label (g/gtk-label-new "Key set")
        answer-row (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        bottom (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)]
    (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
    (g/gtk-scrolled-window-set-child scroll lb)
    (g/gtk-widget-set-vexpand scroll 1)
    ;; GTK 4.10 added the placeholder; an older GTK goes without it
    (try (g/gtk-search-entry-set-placeholder-text search "Search commands and keys")
         (catch Throwable _ nil))
    (g/gtk-label-set-xalign message 0.0)
    (g/gtk-label-set-wrap message 1)
    (g/gtk-widget-set-hexpand message 1)
    (doseq [w [message yes-btn no-btn]] (g/gtk-box-append answer-row w))
    (doseq [w [(button! "Open keys.edn" #(call! pw :on-open-file))
               (button! "Reset this key" #(call! pw :on-reset (selected-index pw)))]]
      (g/gtk-box-append bottom w))
    (g/gtk-widget-set-tooltip-text key-set "A key set adds a group of keys. Your own keys still win over it.")
    (doseq [w [set-label key-set]] (g/gtk-box-append set-row w))
    (g/gtk-widget-set-margin-top set-row 8)
    (doseq [w [set-row search scroll answer-row bottom]] (g/gtk-box-append page w))
    (doseq [f [g/gtk-widget-set-margin-start g/gtk-widget-set-margin-end]] (f page 8))
    page))

(defn make-window
  "Builds the Preferences window over parent, hidden, its chooser listing
  key-set-labels: {:win :notebook :key-set
  :search :list :scroll :message :yes-btn :no-btn :rows :buttons :said
  :handlers}. Closing it hides it, so it opens again as it was left."
  [parent key-set-labels]
  (let [names (g/gtk-string-list-new ffi/null)
        _ (doseq [l key-set-labels] (g/gtk-string-list-append names l))
        pw {:win (g/gtk-window-new) :notebook (g/gtk-notebook-new)
            :key-set (g/gtk-drop-down-new names ffi/null)
            :search (g/gtk-search-entry-new)
            :list (g/gtk-list-box-new) :scroll (g/gtk-scrolled-window-new)
            :message (g/gtk-label-new "")
            :rows (atom []) :buttons (atom []) :said (atom {:text "" :answers nil})
            :handlers (atom {})}
        pw (assoc pw
                  :yes-btn (button! "Replace" #(call! pw :on-answer true))
                  :no-btn (button! "Cancel" #(call! pw :on-answer false)))]
    (g/gtk-window-set-title (:win pw) "Preferences")
    (g/gtk-window-set-default-size (:win pw) WIDTH HEIGHT)
    (when (and parent (not (ffi/null? parent)))
      (g/gtk-window-set-transient-for (:win pw) parent))
    (g/gtk-window-set-hide-on-close (:win pw) 1)
    (g/gtk-notebook-append-page (:notebook pw) (keys-page pw) (g/gtk-label-new "Keys"))
    (g/gtk-window-set-child (:win pw) (:notebook pw))
    (say! pw "" nil)
    (connect-input! pw)
    pw))

(defn present!
  "Shows the window and puts the caret in the search box."
  [pw]
  (g/gtk-window-present (:win pw))
  (g/gtk-widget-grab-focus (:search pw))
  nil)
