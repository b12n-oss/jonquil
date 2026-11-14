(ns jonquil.gtk.window.layout
  "The window's frame: the toolbar and its buttons, the panes' outer widgets, the window's column and its key controller. build makes them, place! puts every widget in its place and connect! wires the toolbar and the key controller. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window's activate! unchanged; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [jolt.ffi :as ffi]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.gtk.window.status-bar :as win-status-bar]))

(defn- mac? [] (= "Mac OS X" (System/getProperty "os.name")))

(defn build
  "activate!'s frame widgets, in activate!'s order: the Files toggle, the
  tree's outer paned and the editor and console paned, then the header
  bar, its buttons and its title, the Reset on Run check with reset-state,
  the window's column and its key controller. Reads nothing from w.
  Returns w with :files-btn :tree-paned :paned :toolbar (the header bar)
  :run-btn :stop-btn :open-btn :save-btn :new-tab-btn :title-label
  :subtitle-label :menu-btn :reset-chk :reset-state :vbox and :keyctl
  added. :menu-btn is the button the menus open from, nil on macOS, where
  they are in the system menu bar."
  [w]
  (let [files-btn (g/gtk-toggle-button-new-with-label "☰ Files")
        tree-paned (g/gtk-paned-new g/ORIENTATION-HORIZONTAL)
        paned (g/gtk-paned-new g/ORIENTATION-VERTICAL)
        toolbar (g/gtk-header-bar-new)
        run-btn (g/gtk-button-new-with-label "▶ Run")
        stop-btn (g/gtk-button-new-with-label "Stop")
        open-btn (g/gtk-button-new-with-label "Open…")
        save-btn (g/gtk-button-new-with-label "Save…")
        new-tab-btn (g/gtk-button-new-with-label "+")
        title-label (g/gtk-label-new "Untitled")
        subtitle-label (g/gtk-label-new "")
        menu-btn (when-not (mac?) (g/gtk-menu-button-new))
        ;; Run > Reset on Run shows this check's tick. The check itself has
        ;; no place in the window: it holds the state the runtimes switch.
        reset-chk (g/gtk-check-button-new-with-label "Reset on Run")
        ;; The tick an attach runtime took down (dialect/reset-toggle).
        reset-state (atom {})
        vbox (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        keyctl (g/gtk-event-controller-key-new)]
    (assoc w :files-btn files-btn :tree-paned tree-paned :paned paned :toolbar toolbar :run-btn run-btn
           :stop-btn stop-btn :open-btn open-btn :save-btn save-btn :new-tab-btn new-tab-btn
           :title-label title-label :subtitle-label subtitle-label :menu-btn menu-btn
           :reset-chk reset-chk :reset-state reset-state :vbox vbox :keyctl keyctl)))

(def ^:private tooltips
  ;; a short sentence saying what each header button does, with its key
  {:files-btn "Show or hide the file tree"
   :open-btn "Open a file"
   :new-tab-btn "Open a new tab"
   :run-btn "Run the buffer (F5)"
   :stop-btn "Stop the running evaluation (Esc)"
   :save-btn "Save the buffer"})

(defn- pack-title!
  "The header bar's middle: the file's name over its folder."
  [{:keys [toolbar title-label subtitle-label]}]
  (let [titles (g/gtk-box-new g/ORIENTATION-VERTICAL 0)]
    (g/gtk-widget-add-css-class title-label "title")
    (g/gtk-widget-add-css-class subtitle-label "subtitle")
    (g/gtk-label-set-ellipsize subtitle-label g/PANGO-ELLIPSIZE-START)
    (g/gtk-widget-set-visible subtitle-label 0)
    (g/gtk-box-append titles title-label)
    (g/gtk-box-append titles subtitle-label)
    (g/gtk-widget-set-valign titles g/ALIGN-CENTER)
    (g/gtk-header-bar-set-title-widget toolbar titles)))

(defn- pack-toolbar!
  "place!'s first step, the header bar: Files, Open and the new-tab button
  at the start, the title in the middle, and Run, Stop and Save at the end,
  with the menu button last where there is one (pack_end fills from the
  edge inwards, so the last one packed sits nearest the title). Reset on
  Run starts ticked and is kept alive without a parent. On macOS the
  window buttons are the system's own."
  [{:keys [toolbar files-btn run-btn stop-btn open-btn save-btn new-tab-btn menu-btn reset-chk] :as m}]
  (doseq [b [files-btn open-btn new-tab-btn]] (g/gtk-header-bar-pack-start toolbar b))
  (pack-title! m)
  (when menu-btn
    (g/gtk-menu-button-set-label menu-btn "☰")
    (g/gtk-widget-set-tooltip-text menu-btn "Menu")
    (g/gtk-header-bar-pack-end toolbar menu-btn))
  (doseq [b [save-btn stop-btn run-btn]] (g/gtk-header-bar-pack-end toolbar b))
  (doseq [[k text] tooltips] (g/gtk-widget-set-tooltip-text (get m k) text))
  ;; GTK 4.18 added this, and only macOS calls it. Declaring it costs nothing
  ;; on an older GTK: Jolt looks a C function up when it is first called
  ;; (measured: a binding to a name the library lacks loads, and throws only
  ;; on the call).
  (when (mac?) (g/gtk-header-bar-set-use-native-controls toolbar 1))
  (g/g-object-ref-sink reset-chk)
  (g/gtk-check-button-set-active reset-chk 1))

(defn- pack-editor!
  "place!'s second step: the tabs' notebook over the console in the vertical paned."
  [{:keys [paned notebook cv]}]
  (g/gtk-widget-set-vexpand paned 1)
  ;; A notebook of tabs is the paned's start child; each page is a tab's
  ;; own find bar over its editor (jonquil.gtk.tab). The strip stays
  ;; hidden while there's one tab.
  (g/gtk-notebook-set-show-tabs notebook 0)
  (g/gtk-notebook-set-show-border notebook 0)
  (g/gtk-notebook-set-scrollable notebook 1)
  (g/gtk-widget-set-vexpand notebook 1)
  (g/gtk-paned-set-start-child paned notebook)
  (g/gtk-paned-set-end-child paned (:scroll cv))
  (g/gtk-paned-set-position paned 430))

(defn- part-roots
  "The root widgets of the parts' panels in part-panels*, in id order."
  [part-panels*]
  (map (fn [[_ p]] (:root p)) @part-panels*))

(defn- append-expanding!
  "Appends root to slot, expanding vertically."
  [slot root]
  (g/gtk-widget-set-vexpand root 1)
  (g/gtk-box-append slot root))

(defn- pack-right-slot!
  "place!'s third step: the vertical paned beside the right-hand slot in h-paned, each panel in the slot, and the panels and the slot hidden until one opens."
  [{:keys [h-paned paned pane sp slot rp lp hp part-panels*]}]
  ;; The editor/console paned is h-paned's start child; the
  ;; inspector panel is its end child, hidden until the first open
  ;; (show-inspector-panel!), and kept at a fixed width when the window
  ;; resizes (never grows/shrinks with it, never squeezed below its own
  ;; WIDTH-REQUEST).
  (g/gtk-widget-set-vexpand h-paned 1)
  (g/gtk-paned-set-start-child h-paned paned)
  (g/gtk-widget-set-vexpand (:root pane) 1)
  (g/gtk-widget-set-vexpand (:root sp) 1)
  (g/gtk-box-append slot (:root pane))
  (g/gtk-box-append slot (:root sp))
  (doseq [root (concat (map :root [rp lp hp]) (part-roots part-panels*))]
    (append-expanding! slot root))
  (g/gtk-paned-set-end-child h-paned slot)
  (g/gtk-paned-set-resize-end-child h-paned 0)
  (g/gtk-paned-set-shrink-end-child h-paned 0)
  (doseq [w (concat (map :root [pane sp rp lp]) (part-roots part-panels*) [(:root hp) slot])]
    (g/gtk-widget-set-visible w 0)))

(defn- pack-window!
  "place!'s last step: the toolbar, the tree's paned and the status bar in the window's column, which becomes the window's child at its default size."
  [{:keys [vbox toolbar title-label subtitle-label tree-paned tree-pane h-paned win status-label hint-label pending-label unread-label dialect-label parinfer-label line-col-label update-parinfer-label!]}]
  (g/gtk-window-set-titlebar win toolbar)
  (win-files/register-title-labels! win title-label subtitle-label)
  (g/gtk-paned-set-start-child tree-paned (:root tree-pane))
  (g/gtk-paned-set-end-child tree-paned h-paned)
  (g/gtk-paned-set-resize-start-child tree-paned 0)
  (g/gtk-paned-set-shrink-start-child tree-paned 0)
  (g/gtk-widget-set-vexpand tree-paned 1)
  (g/gtk-box-append vbox tree-paned)
  (win-status-bar/place! {:vbox vbox :status-label status-label :hint-label hint-label :pending-label pending-label
                          :unread-label unread-label :dialect-label dialect-label :parinfer-label parinfer-label
                          :line-col-label line-col-label :update-parinfer-label! update-parinfer-label!})
  (g/gtk-window-set-child win vbox)
  (g/gtk-window-set-default-size win 900 700))

(defn place!
  "activate!'s widget placement, in activate!'s order: the toolbar, the
  editor and console paned, the right-hand slot, then the window's
  column with the status bar. m holds the widgets they place, under
  activate!'s local names; each step reads its own keys. Called once per
  window; activate! reads nothing it returns."
  [m]
  (pack-toolbar! m)
  (pack-editor! m)
  (pack-right-slot! m)
  (pack-window! m))

(defn- connect-clicked!
  "Connects btn's clicked signal to f, contained so a throw is said on the
  status line as label's. Returns the handler id."
  [status-label btn label f]
  (g/g-signal-connect-data btn "clicked"
                           (callable (win-status/contain-click status-label label f) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn connect!
  "activate!'s toolbar and key controller connects, in activate!'s order:
  Run, Stop, Open and Save's clicked handlers and Reset on Run's toggled
  one, each contained so a throw is said on the status line, then the
  key controller, in the capture phase with window-key-fn, added to the
  window. Called once per window; activate! reads nothing it returns. m
  holds the widgets build made, :win :status-label :ev*, the four
  toolbar fns and :window-key-fn."
  [{:keys [run-btn stop-btn open-btn save-btn new-tab-btn reset-chk keyctl win status-label run-fn stop-fn open-fn
           save-fn new-tab-fn ev* window-key-fn]}]
  (connect-clicked! status-label run-btn "run" (fn [_] (run-fn)))
  (connect-clicked! status-label stop-btn "stop" (fn [_] (stop-fn)))
  (connect-clicked! status-label open-btn "open" (fn [_] (open-fn)))
  (connect-clicked! status-label save-btn "save" (fn [_] (save-fn)))
  (when new-tab-btn
    (connect-clicked! status-label new-tab-btn "new tab" (fn [_] (new-tab-fn))))
  (g/g-signal-connect-data reset-chk "toggled"
                           (callable (win-status/contain-click status-label "reset on run"
                                                               (fn [btn]
                                                                 (ev/set-mode! @ev*
                                                                               (if (zero? (g/gtk-check-button-get-active btn))
                                                                                 :accumulate :reset))))
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
  (g/g-signal-connect-data keyctl "key-pressed"
                           (callable window-key-fn [:pointer :uint :uint :uint :pointer] :int)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/gtk-widget-add-controller win keyctl))
