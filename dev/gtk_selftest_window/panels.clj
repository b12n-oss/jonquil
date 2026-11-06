(ns gtk-selftest-window.panels
  "Switching between the right-hand panels: the palette's Switch to panel
  entries and the Cmd/Ctrl+Alt+1..6 keys that focus a panel or open it."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]))

(def ^:private fixture "(defn area [s] s)\n(area 2)")

(defn- run-palette!
  "Opens the palette, filters it to query and runs the first entry, then
  closes it. GTK-thread only."
  [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn- palette-docs!
  "The docs the palette shows for query, then closes it. GTK-thread only."
  [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (let [docs (palette/shown-docs p)]
      (when (popover/open? p) (popover/hide! p))
      docs)))

(defn- panel-key!
  "Cmd/Ctrl+Alt+digit through the window's key handler."
  [ui digit]
  ((:handle-window-key! ui) ffi/null (int digit) 0 (bit-or g/META-MASK g/ALT-MASK) ffi/null))

(defn- status [ui] (g/label-text (:status-label ui)))

(defn- shown? [pane] (pos? (g/gtk-widget-get-visible (:root pane))))

(defn- list-focused?
  "True when keyboard focus is the pane's list or one of its rows."
  [pane]
  (let [f (g/gtk-root-get-focus (g/gtk-widget-get-root (:root pane)))
        l (:list pane)]
    (and (not (ffi/null? f))
         (or (= f l) (not (zero? (g/gtk-widget-is-ancestor f l)))))))

(defn- close! [pane]
  (when-let [f (:on-close @(:handlers pane))] (f)))

(defn row-panel-switch-and-keys
  "With Locations shown and then Trace over it, the palette offers
  Switch to panel: Locations and not Trace; running it brings Locations
  back with focus in its list. Cmd/Ctrl+Alt+5 opens Trace again through
  its own command, and pressed again from the editor it focuses the
  Trace panel showing."
  [ui]
  (go-row :panel-switch-and-keys support/check
    (let [locp (:locations-pane ui)
          tracep (:trace-pane ui)]
      (try
        (on-gtk (let [ed (:editor ui)]
                  (editor/set-text! ed fixture)
                  (support/place-cursor! (:buf ed) (inc (str/last-index-of fixture "(area")))
                  (run-palette! ui "list the uses")))
        (let [locations? (on-gtk (shown? locp))
              _ (on-gtk (run-palette! ui "show the calls of the traced"))
              trace? (await-until 3000 20 #(shown? tracep))
              docs (on-gtk (palette-docs! ui "switch to panel"))
              _ (on-gtk (run-palette! ui "switch to panel: locations"))
              back (on-gtk {:shown (shown? locp) :focused (list-focused? locp) :trace (shown? tracep)})
              _ (on-gtk (panel-key! ui \5))
              _ (await-until 3000 20 #(shown? tracep))
              key5 (on-gtk {:shown (shown? tracep) :locations (shown? locp)})
              _ (on-gtk (editor/focus! (:editor ui)) (panel-key! ui \5))
              again (on-gtk {:shown (shown? tracep) :focused (list-focused? tracep)})]
          (on-gtk (support/check :panel-switch-and-keys
                                 (and locations? trace?
                                      (= "Switch to panel: Locations" (first docs))
                                      (not-any? #(= "Switch to panel: Trace" %) docs)
                                      (:shown back) (:focused back) (not (:trace back))
                                      (:shown key5) (not (:locations key5))
                                      (:shown again) (:focused again))
                                 {:locations locations? :trace trace? :docs docs
                                  :back back :key5 key5 :again again})))
        (finally (on-gtk (close! locp) (close! tracep)))))))

(defn row-panel-key-says-what-fills
  "A panel key for a panel that never showed anything opens nothing and
  says on the status bar what fills it: the inspector here."
  [ui]
  (go-row :panel-key-says-what-fills support/check
    (let [recent ((:recent-panels* ui)) saved @recent]
      (try
        (on-gtk (reset! recent [])
                (g/gtk-label-set-text (:status-label ui) "")
                (panel-key! ui \1))
        (let [want "; Nothing inspected yet: inspect a form or a result first."
              said? (await-until 2000 20 #(= want (g/label-text (:status-label ui))))
              opened? (on-gtk (shown? (:inspector ui)))]
          (on-gtk (support/check :panel-key-says-what-fills
                                 (and said? (not opened?))
                                 {:said said? :opened opened? :status (g/label-text (:status-label ui))})))
        (finally (reset! recent saved))))))

(defn row-panel-stale-panels-leave
  "A panel whose state went away is no longer switched back to: closing
  the tab a locations list came from clears it, and Cmd/Ctrl+Alt+3 then
  says what fills the panel instead of showing an empty one."
  [ui]
  (go-row :panel-stale-panels-leave support/check
    (on-gtk (let [_ ((:new-tab!* ui))
                  t ((:active-tab* ui))
                  ed (:ed t)]
              (editor/set-text! ed fixture)
              (support/place-cursor! (:buf ed) (inc (str/last-index-of fixture "(area")))
              (run-palette! ui "list the uses")
              (editor/mark-saved! ed)
              ((:close-tab! ui) (:id t))
              (g/gtk-label-set-text (:status-label ui) "")
              (panel-key! ui \3)))
    (let [want "; No locations yet: list a symbol's uses or definitions, or the find matches."
          said? (await-until 2000 20 #(= want (g/label-text (:status-label ui))))
          state (on-gtk {:shown (shown? (:locations-pane ui))
                         :recent @((:recent-panels* ui))
                         :docs (palette-docs! ui "switch to panel: locations")})]
      (on-gtk (support/check :panel-stale-panels-leave
                             (and said? (not (:shown state)) (not-any? #{:locations} (:recent state))
                                  (empty? (:docs state)))
                             (assoc state :said said? :status (g/label-text (:status-label ui))))))))

;; --- named layouts (LM8) -----------------------------------------------------

(defn row-layout-save-and-use
  "Save the layout with the file tree and the Trace panel showing; hide
  both; Use layout brings both back; Forget every saved layout empties the
  list. \"save the layout\" in the palette picks Save, not Forget. The
  widths aren't asserted: in this window GTK holds the re-shown tree to a
  width of its own (an open question, recorded in the plan)."
  [ui]
  (go-row :layout-save-and-use support/check
    (let [tree-root (:root (:file-tree ui))
          trace-root (:root (:trace-pane ui))
          vis? (fn [w] (pos? (g/gtk-widget-get-visible w)))
          _ (on-gtk (g/gtk-toggle-button-set-active (:files-btn ui) 1)
                    (run-palette! ui "show the calls of the traced functions"))
          both? (await-until 3000 20 #(and (vis? tree-root) (vis? trace-root)))
          _ (on-gtk (run-palette! ui "save the layout"))
          saved? (await-until 3000 20 #(= "; Saved the layout \"File tree and Trace panel\"." (status ui)))
          _ (on-gtk (g/gtk-toggle-button-set-active (:files-btn ui) 0)
                    ((:on-close @(:handlers (:trace-pane ui)))))
          hidden? (await-until 3000 20 #(and (not (vis? tree-root)) (not (vis? trace-root))))
          _ (on-gtk (run-palette! ui "use layout: file tree and trace panel"))
          back? (await-until 3000 20 #(and (vis? tree-root) (vis? trace-root)))
          said? (await-until 3000 20 #(= "; Layout \"File tree and Trace panel\"." (status ui)))
          _ (on-gtk (run-palette! ui "forget every saved layout"))
          forgot? (await-until 3000 20 #(= "; Forgot the saved layouts." (status ui)))
          _ (on-gtk (g/gtk-toggle-button-set-active (:files-btn ui) 0) ((:on-close @(:handlers (:trace-pane ui)))))]
      (on-gtk (support/check :layout-save-and-use (and both? saved? hidden? back? said? forgot?)
                             {:both both? :saved saved? :hidden hidden? :back back? :said said? :forgot forgot?
                              :status (status ui)})))))
