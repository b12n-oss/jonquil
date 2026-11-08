(ns gtk-selftest-window.probe
  "Goal 1 of the window parts: a part adds a command, a right-hand panel with
  its own key, a ui key and a quit hook with no change to jonquil.gtk.window or
  jonquil.command-registry. The part is gtk-selftest-window.probe-part, loaded
  first by the self-test so its spec is in the tables before they are built."
  (:require [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.command-registry :as registry]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]
            [jonquil.keymap :as km]
            [jonquil.parts :as parts]))

;; F2 with Cmd/Ctrl+Shift: keymap.edn binds F2 only with :exact #{}, so no
;; :window or :editor binding matches this chord.
(def ^:private KEY-F2 0xffbf)

(defn- probe-key!
  "The probe's chord through the window's key handler."
  [ui]
  ((:handle-window-key! ui) ffi/null (int KEY-F2) 0 (bit-or g/META-MASK g/SHIFT-MASK) ffi/null))

(defn- open-query!
  "Opens the palette filtered to query. GTK-thread only."
  [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    p))

(defn- run-palette!
  [ui query]
  (let [p (open-query! ui query)]
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn- palette-docs!
  [ui query]
  (let [p (open-query! ui query)
        docs (palette/shown-docs p)]
    (when (popover/open? p) (popover/hide! p))
    docs))

(defn- shown? [ui] (pos? (g/gtk-widget-get-visible (:probe-root ui))))

(defn- close-messages! [ui]
  (when-let [f (:on-close @(:handlers (:messages-pane ui)))] (f)))

(defn row-probe-part
  "The probe part's command is in the window table and runs from the palette,
  its chord shows its panel, Switch to panel offers it once another panel
  shows, its quit hook is registered under :probe, and its binding is reachable."
  [ui]
  (go-row :probe-part support/check
    (try
      (let [registered? (contains? registry/window-commands :probe/ping)
            pings (:probe-pings* ui)
            before (when pings @pings)
            _ (on-gtk (run-palette! ui "self-test probe"))
            pinged? (and pings (= (inc before) @pings))
            _ (on-gtk (probe-key! ui))
            shown (await-until 3000 20 #(shown? ui))
            recent (first @((:recent-panels* ui)))
            _ (on-gtk ((:panel-focus!* ui) :messages))
            hidden-by-switch? (await-until 3000 20 #(not (shown? ui)))
            docs (on-gtk (palette-docs! ui "switch to panel"))
            quit-owners (get-in ((:part-owners* ui)) [:hooks :on-quit])
            unreachable (km/unreachable-bindings (parts/bindings parts/specs :window) (:window km/shipped))]
        (on-gtk (support/check :probe-part
                               (and registered? pinged? shown (= :probe recent) hidden-by-switch?
                                    (some #{"Switch to panel: Probe"} docs)
                                    (some #{:probe} quit-owners)
                                    (empty? unreachable))
                               {:registered registered? :pinged pinged? :shown shown :recent recent
                                :switched-away hidden-by-switch? :docs docs :quit-owners quit-owners
                                :unreachable unreachable})))
      (finally
        (on-gtk (when (:probe-close! ui) ((:probe-close! ui)))
                (close-messages! ui))))))
