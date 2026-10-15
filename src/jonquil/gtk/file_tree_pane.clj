(ns jonquil.gtk.file-tree-pane
  "The file tree's widget (file-tree spec): a header (the root's name, a
  refresh button and Open Folder...), a scrolled GtkListBox with one row per
  entry of jonquil.file-tree/rows, and a body label for a root that's
  missing or can't be read. Every change rebuilds the list from the model
  (plan ruling 4; the spike measured 61 ms at 1,000 rows). A click (single
  click activates) or Return on a folder toggles it; on a file it calls
  :on-open. Left collapses and Right expands the selected folder.

  File operations (file-ops plan T3): a right click (or Shift+F10 / Menu) on a
  row, or on the empty list for the root, opens a popover with New File...,
  New Folder..., Rename... and Move to Trash (the root gets the first two).
  The first three swap the popover to a name entry; Return submits it. F2
  renames the selected row, Delete (or Control/Meta+BackSpace) trashes it.
  The pane never touches the disk: it calls :on-new-file, :on-new-folder,
  :on-rename and :on-trash, and a returned string is shown in the entry's
  problem label. The popover hangs off the pane's :root box, not the list,
  because the list is rebuilt wholesale and a popover parented to it would
  be torn down with its rows. The target (path, listed and dir?) is recorded
  when the menu opens, so a refresh in between can't redirect an action.
  Rename and Move to Trash act on the entry as listed (a symlink itself, not
  its target, like Finder): they pass the row's :listed path, and Rename's
  name and folder come from it too (file-ops fix round 1, Critical 1).

  The list and header carry the jonquil-code CSS class, so the window's
  shared provider gives the tree the theme's colours and the zoomed font."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.file-ops :as ops]
            [jonquil.file-tree :as ft]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.ffi :as g])
  (:import (java.io File)))

(def ^:private KEY-LEFT 0xff51)
(def ^:private KEY-RIGHT 0xff53)
(def ^:private KEY-F2 0xffbf)
(def ^:private KEY-DELETE 0xffff)
(def ^:private KEY-BACKSPACE 0xff08)
(def ^:private KEY-F10 0xffc7)
(def ^:private KEY-MENU 0xff67)

(def ^:private ITEMS ["New File…" "New Folder…" "Rename…" "Move to Trash"])
(def ^:private NAME-WIDTH 260)

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

;; --- the menu and name entry -------------------------------------------------

(defn- row-at
  "The row listed as path, else the first whose canonical path is path's (a
  symlink and its target share one)."
  [pane path]
  (when path
    (let [c (canonical path)
          rs (visible-rows pane)]
      (or (some (fn [r] (when (= path (:listed r)) r)) rs)
          (some (fn [r] (when (= c (:path r)) r)) rs)))))

(defn- rect-alloc [[x y w h]]
  (let [r (ffi/alloc 16)]
    (ffi/write r :int x 0)
    (ffi/write r :int y 4)
    (ffi/write r :int w 8)
    (ffi/write r :int h 12)
    r))

(defn- point-popover-at!
  "Points the popover (parent :root) at a 1px-wide spot: pt, an [x y] in the
  list's coordinates (a click), else the row's top-left corner and height, else
  a 1px-high strip at the top of the visible list area (the scrolled
  window's origin), so a POS-BOTTOM popover never lands below the window."
  [{:keys [popover root list scroll]} row-widget pt]
  (let [[x y w h] (cond
                    pt (let [[x y] (or (g/compute-point list root (first pt) (second pt)) pt)] [x y 1 1])
                    row-widget (let [[x y] (or (g/compute-point row-widget root 0 0) [0 0])]
                                 [x y (g/gtk-widget-get-width row-widget) (g/gtk-widget-get-height row-widget)])
                    :else (let [[x y] (or (g/compute-point scroll root 0 0) [0 0])]
                            [x y (g/gtk-widget-get-width scroll) 1]))
        r (rect-alloc [(Math/round (double x)) (Math/round (double y)) (max 1 (long w)) (max 1 (long h))])]
    (try (g/gtk-popover-set-pointing-to popover r)
         (finally (ffi/free r)))))

(defn menu-state
  "{:open? :mode :menu|:name|nil :heading :problem :target path :listed path}:
  :target is the canonical path, :listed the entry as listed."
  [pane]
  (let [{:keys [mode heading problem target]} @(:menu pane)]
    {:open? (boolean (:open? @(:menu pane))) :mode mode :heading heading :problem problem
     :target (:path target) :listed (:listed target)}))

(defn menu-items
  "The labels the menu currently shows."
  [pane]
  (:items @(:menu pane)))

(defn- close-menu! [{:keys [popover menu]}]
  (g/gtk-popover-popdown popover)
  (swap! menu assoc :open? false :mode nil))

(defn- show-menu-mode! [{:keys [items-box name-box]} menu-mode]
  (g/gtk-widget-set-visible items-box (if (= menu-mode :menu) 1 0))
  (g/gtk-widget-set-visible name-box (if (= menu-mode :name) 1 0)))

(defn- set-problem! [{:keys [problem-label menu]} text]
  (swap! menu assoc :problem text)
  (g/gtk-label-set-text problem-label (or text ""))
  (g/gtk-widget-set-visible problem-label (if (str/blank? text) 0 1)))

(defn open-menu!
  "Opens the menu for the row at path, or for the root when path is nil.
  Records the target now. pt, an optional [x y] in the list's coordinates,
  is where a right click landed; the popover points there. An error row, a
  path that isn't shown, or the root of a pane with no folder open opens
  nothing. Returns true when it popped up, else nil. GTK-thread only."
  ([pane path] (open-menu! pane path nil))
  ([{:keys [popover menu state row-widgets] :as pane} path pt]
  (let [row (when path (row-at pane path))
        root (:root @state)
        target (cond (nil? path) (when root (let [c (canonical root)] {:path c :listed c :dir? true}))
                     (and row (not (:error? row))) (select-keys row [:path :listed :dir?]))]
    (when target
      (swap! menu assoc :target target :items (if (nil? path) (vec (take 2 ITEMS)) ITEMS)
             :mode :menu :open? true :problem nil)
      (doseq [[label btn] (:buttons pane)]
        (g/gtk-widget-set-visible btn (if (some #{label} (:items @menu)) 1 0)))
      (set-problem! pane nil)
      (show-menu-mode! pane :menu)
      (point-popover-at! pane (when row (:row (get @row-widgets (.indexOf ^java.util.List (visible-rows pane) row)))) pt)
      (g/gtk-popover-popup popover)
      true))))

(defn- listed [{:keys [path listed]}] (or listed path))

(defn- target-name [target] (.getName (File. ^String (listed target))))

(defn- target-dir
  "The folder a new entry goes in: the target itself (canonical) for a
  folder, else the folder the file is listed in."
  [{:keys [path dir?] :as target}]
  (if dir? path (.getParent (File. ^String (listed target)))))

(defn- prefill-selection
  "[start end] to select in name: the part before the last dot of a file's
  name (the whole name when there is no dot, or only a leading one)."
  [name dir?]
  (let [i (.lastIndexOf ^String name ".")]
    [0 (if (or dir? (<= i 0)) (count name) i)]))

(defn- show-name-entry! [{:keys [entry heading-label menu] :as pane} action]
  (let [{:keys [target]} @menu
        name (target-name target)
        heading (case action
                  :new-file (str "New file in " (.getName (File. ^String (target-dir target))))
                  :new-folder (str "New folder in " (.getName (File. ^String (target-dir target))))
                  :rename (str "Rename " name))]
    (swap! menu assoc :mode :name :action action :heading heading)
    (g/gtk-label-set-text heading-label heading)
    (set-problem! pane nil)
    (g/gtk-editable-set-text entry (if (= action :rename) name ""))
    (show-menu-mode! pane :name)
    (g/gtk-widget-grab-focus entry)
    (when (= action :rename)
      (let [[a b] (prefill-selection name (:dir? target))]
        (g/gtk-editable-select-region entry a b)))))

(defn press-item!
  "What clicking the menu button labelled label does. Nothing unless the menu
  is open and shows that label."
  [{:keys [menu on-trash on-error] :as pane} label]
  (when (and (:open? @menu) (some #{label} (:items @menu)))
   (case label
    "New File…" (show-name-entry! pane :new-file)
    "New Folder…" (show-name-entry! pane :new-folder)
    "Rename…" (show-name-entry! pane :rename)
    "Move to Trash" (let [path (listed (:target @menu))]
                      (close-menu! pane)
                      (when-let [problem (when on-trash (on-trash path))]
                        (on-error (str "; trash: " problem))))
    nil))
  nil)

(defn submit-name!
  "Return in the name entry. A problem (jonquil.file-ops/name-problem, or a
  string the callback returns) is shown and keeps the entry open; the same
  name on a rename closes without a call; otherwise the callback runs and
  the menu closes. Nothing unless the name entry is showing."
  [{:keys [menu on-new-file on-new-folder on-rename] :as pane} text]
  (when (= :name (:mode @menu))
    (let [{:keys [target action]} @menu
          n (str/trim (str text))
          rename? (= action :rename)
          same? (and rename? (= n (target-name target)))
          dir (if rename? (.getParent (File. ^String (listed target))) (target-dir target))
          problem (when-not same?
                    (ops/name-problem {:dir dir :name text :old-path (when rename? (listed target))}))]
      (cond
        same? (close-menu! pane)
        problem (set-problem! pane problem)
        :else (let [f (case action :new-file on-new-file :new-folder on-new-folder on-rename)
                    err (when f (if rename? (f (listed target) n) (f dir n)))]
                (if err (set-problem! pane err) (close-menu! pane))))))
  nil)

(defn- command-chord? [state]
  (not (zero? (bit-and state (bit-or g/CONTROL-MASK g/META-MASK)))))

(defn- selected-row
  "The selected row itself (by index, so a symlink's row is never confused
  with its target's)."
  [{:keys [list] :as pane}]
  (let [row (g/gtk-list-box-get-selected-row list)]
    (when-not (ffi/null? row)
      (get (visible-rows pane) (g/gtk-list-box-row-get-index row)))))

(defn handle-key!
  "The list's key handler: 1 when keyval (with GDK modifier state) is one the
  pane acts on, else 0. Left/Right toggle a folder; F2, Delete, Control/Meta+
  BackSpace, Shift+F10 and Menu act on the selected row. The action runs on
  the next main-loop turn, since the callbacks can rebuild the rows."
  [{:keys [on-trash on-error] :as pane} keyval state]
  (let [guard (:guard pane)
        r (selected-row pane)
        later (fn [what f] (on-gui! (guard what f)) 1)]
    (cond
      (handles-key? pane keyval) (later "tree" (fn [] (on-key pane keyval)))
      (and r (not (:error? r)) (= keyval KEY-F2))
      (later "rename" (fn [] (when (open-menu! pane (listed r)) (press-item! pane "Rename…"))))
      (and r (not (:error? r))
           (or (= keyval KEY-DELETE) (and (= keyval KEY-BACKSPACE) (command-chord? state))))
      (later "trash" (fn [] (when-let [problem (when on-trash (on-trash (listed r)))]
                              (on-error (str "; trash: " problem)))))
      (and r (or (= keyval KEY-MENU)
                 (and (= keyval KEY-F10) (not (zero? (bit-and state g/SHIFT-MASK))))))
      (later "menu" (fn [] (open-menu! pane (listed r))))
      :else 0)))

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
  [path]) :on-open-folder (fn []) :on-error (fn [msg]) and, optionally,
  :on-new-file (fn [dir name]), :on-new-folder (fn [dir name]), :on-rename
  (fn [path new-name]), :on-trash (fn [path])}. The last four return nil on
  success or an error string (the entry shows it); a throw in any callback
  goes to :on-error. GTK-thread only."
  [{:keys [on-open on-open-folder on-error on-new-file on-new-folder on-rename on-trash]}]
  (let [root (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        header (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        header-label (g/gtk-label-new "No folder")
        refresh-btn (g/gtk-button-new-with-label "⟳")
        open-folder-btn (g/gtk-button-new-with-label "Open Folder…")
        scroll (g/gtk-scrolled-window-new)
        list (g/gtk-list-box-new)
        body-label (g/gtk-label-new "")
        keyctl (g/gtk-event-controller-key-new)
        click (g/gtk-gesture-click-new)
        popover (g/gtk-popover-new)
        items-box (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        name-box (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
        heading-label (g/gtk-label-new "")
        entry (g/gtk-entry-new)
        problem-label (g/gtk-label-new "")
        buttons (mapv (fn [label] [label (g/gtk-button-new-with-label label)]) ITEMS)
        guard (fn [what f] (fn [& args]
                             (try (apply f args)
                                  (catch :default e (on-error (str "; " what ": " (ex-message e)))))))
        pane {:root root :header-label header-label :refresh-btn refresh-btn
              :open-folder-btn open-folder-btn :list list :body-label body-label
              :state (atom (ft/load-model nil)) :rows (atom []) :row-widgets (atom [])
              :on-open on-open :on-error on-error :guard guard
              :on-new-file on-new-file :on-new-folder on-new-folder :on-rename on-rename :on-trash on-trash
              :scroll scroll :popover popover :items-box items-box :name-box name-box :heading-label heading-label
              :entry entry :problem-label problem-label :buttons buttons
              :menu (atom {:open? false :mode nil :items [] :target nil})}]
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
                             (callable (fn [_ctl keyval _kc state _data]
                                         ;; Decided now, run next turn (same reason as
                                         ;; row-activated above).
                                         (try (handle-key! pane keyval state)
                                              (catch :default e (on-error (str "; tree: " (ex-message e))) 1)))
                                       [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller list keyctl)
    ;; The name menu. Parented to root (see the ns docstring), built once.
    (g/gtk-widget-set-parent popover root)
    (g/gtk-widget-add-css-class popover "jonquil-popover")
    (g/gtk-widget-set-halign popover g/ALIGN-START)
    (g/gtk-popover-set-autohide popover 1)
    (g/gtk-popover-set-position popover g/POPOVER-POS-BOTTOM)
    (doseq [[label btn] buttons]
      (g/gtk-button-set-has-frame btn 0)
      (g/gtk-box-append items-box btn)
      (g/g-signal-connect-data btn "clicked"
                               (callable (guard "menu" (fn [_ _] (press-item! pane label) nil))
                                         [:pointer :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT))
    (g/gtk-widget-add-css-class heading-label "jonquil-doc")
    (g/gtk-label-set-xalign heading-label 0.0)
    (g/gtk-widget-add-css-class problem-label "jonquil-doc")
    (g/gtk-label-set-xalign problem-label 0.0)
    (g/gtk-widget-set-visible problem-label 0)
    (g/gtk-widget-set-size-request entry NAME-WIDTH -1)
    (g/gtk-box-append name-box heading-label)
    (g/gtk-box-append name-box entry)
    (g/gtk-box-append name-box problem-label)
    (let [content (g/gtk-box-new g/ORIENTATION-VERTICAL 0)]
      (g/gtk-box-append content items-box)
      (g/gtk-box-append content name-box)
      (g/gtk-popover-set-child popover content))
    (g/gtk-widget-set-visible name-box 0)
    (g/g-signal-connect-data popover "closed"
                             (callable (fn [_pop _data]
                                         (swap! (:menu pane) assoc :open? false :mode nil)
                                         ;; every close path (Escape, click outside,
                                         ;; submit) returns focus to the list
                                         (g/gtk-widget-grab-focus list)
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data entry "activate"
                             (callable (guard "name" (fn [e _] (submit-name! pane (g/editable-text e)) nil))
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-gesture-single-set-button click 3)
    (g/g-signal-connect-data click "pressed"
                             (callable (guard "menu"
                                              (fn [_g _n x y _data]
                                                (let [row (g/gtk-list-box-get-row-at-y list (int y))
                                                      pt [x y]]
                                                  (if (ffi/null? row)
                                                    (open-menu! pane nil pt)
                                                    (let [path (listed (get (visible-rows pane) (g/gtk-list-box-row-get-index row)))]
                                                      (g/gtk-list-box-select-row list row)
                                                      (open-menu! pane path pt)))
                                                  nil)))
                                       [:pointer :int :double :double :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller list click)
    (g/g-signal-connect-data refresh-btn "clicked"
                             (callable (guard "refresh" (fn [_ _] (refresh! pane) nil)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data open-folder-btn "clicked"
                             (callable (guard "open folder" (fn [_ _] (on-open-folder) nil)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    pane))
