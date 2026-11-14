(ns jonquil.gtk.parts.menus
  "The window's menus as a window part (jonquil.parts.menus/spec): a menu
  bar on macOS and a menu button at the end of the toolbar elsewhere, both
  from jonquil/menus.edn, each item running its command as the palette
  would and showing the key the live key map gives it."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.menus :as gm]
            [jonquil.menus :as menus]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.user-keys :as user-keys]))

(defn- mac? [] (= "Mac OS X" (System/getProperty "os.name")))

(defn- live-tree
  "The menus with the keys of the live key map."
  []
  (let [plugins @plugin-registry/live]
    (menus/tree {:keymap {:window (plugin-registry/window-bindings plugins)
                          :editor (plugin-registry/editor-bindings plugins)}
                 :os (System/getProperty "os.name")})))

(defn- run-fn
  "Runs a command the way the palette does, on the active tab."
  [{:keys [active-tab]}]
  (fn [id]
    ((deref (:on-run (:palette (active-tab)))) id)))

(defn- show!
  "Shows model: the system menu bar on macOS, else in button, the menu
  button at the end of the toolbar."
  [{:keys [win]} button model]
  (if (mac?)
    (let [app (g/gtk-window-get-application win)]
      (when-not (ffi/null? app) (g/gtk-application-set-menubar app model)))
    (g/gtk-menu-button-set-menu-model button model)))

(defn- toolbar-button!
  "The menu button in the header bar, which the layout made and placed for
  a system with no menu bar of its own; nil on macOS."
  [{:keys [menu-btn]}]
  menu-btn)

(defn- refresh!
  "Rebuilds the menus when the keys they show have changed: shown is an
  atom holding the tree the menus were last built from."
  [wctx button shown]
  (let [tree (live-tree)]
    (when (not= tree @shown)
      (reset! shown tree)
      (show! wctx button (gm/model! tree))))
  nil)

(def ^:private ticked
  "The commands the menus show with a tick."
  #{:run/reset-on-run})

(defn- reset-note!
  "A dim label at the end of the status bar that says when Reset on Run is
  off, since the header bar no longer shows the check. Answers the label."
  [{:keys [status-label]}]
  (let [note (g/gtk-label-new "Reset on Run: off")
        bar (g/gtk-widget-get-parent status-label)]
    (g/gtk-widget-add-css-class note "dim-label")
    (g/gtk-widget-set-tooltip-text note "A Run keeps what earlier Runs defined. Run > Reset on Run turns it back on.")
    (g/gtk-widget-set-visible note 0)
    (when-not (ffi/null? bar) (g/gtk-box-append bar note))
    note))

(defn- reset-on-run!
  "Ties Run > Reset on Run to the window's Reset on Run check, which holds
  the state and which an attach runtime switches off and greys out: the
  menu item's tick and the status bar's note follow the check, and the
  command toggles it. Answers the command's fn."
  [{:keys [reset-chk set-status!] :as wctx} action]
  (let [note (reset-note! wctx)
        on? #(pos? (g/gtk-check-button-get-active reset-chk))
        can? #(pos? (g/gtk-widget-get-sensitive reset-chk))
        sync! (fn []
                (gm/check! action (on?) (can?))
                (g/gtk-widget-set-visible note (if (on?) 0 1)))
        follow! (fn [signal cb]
                  (g/g-signal-connect-data reset-chk signal cb ffi/null ffi/null g/CONNECT-DEFAULT))]
    ;; toggled is (button, data); notify::sensitive is (object, pspec, data)
    (follow! "toggled" (callable (fn [_w _d] (sync!) nil) [:pointer :pointer] :void))
    (follow! "notify::sensitive" (callable (fn [_w _p _d] (sync!) nil) [:pointer :pointer :pointer] :void))
    (sync!)
    (fn []
      (if (can?)
        (do (g/gtk-check-button-set-active reset-chk (if (on?) 0 1))
            (set-status! (if (on?)
                           "; Reset on Run on: each Run starts clean."
                           "; Reset on Run off: a Run keeps what earlier Runs defined.")))
        (set-status! "; Reset on Run can't change here: this runtime keeps its own state.")))))

(defn- install
  "The menus part for one window: builds the menus and their actions,
  rebuilds the menus whenever your keys.edn or the plugins change the key
  map, and answers :menu-yield-fn, which the window's key handler calls
  before it passes a key on, and :reset-toggle-fn, Reset on Run's command.
  The first build runs before keys.edn is read (the command palette part
  loads it), so the same watch brings your own keys in at startup. ui's
  :menus is {:group :button :yield! :yielded? :shown :refresh! :actions}
  for the self-tests."
  [{:keys [win set-status!] :as wctx}]
  (let [{:keys [group by-id]} (gm/actions! (live-tree) (run-fn wctx) set-status! ticked)
        button (toolbar-button! wctx)
        shown (atom nil)
        refresh-fn (fn [] (refresh! wctx button shown))
        yielded? (atom false)
        yield! (fn [] (gm/yield! win group yielded?))
        reset-toggle-fn (reset-on-run! wctx (get by-id :run/reset-on-run))]
    (gm/attach! win group)
    (refresh-fn)
    (doseq [a [user-keys/live plugin-registry/live]]
      (add-watch a ::menus (fn [_ _ old new] (when (not= old new) (on-gui! refresh-fn)))))
    {:fns {:menu-yield-fn yield! :reset-toggle-fn reset-toggle-fn}
     :ui {:menus {:group group :button button :yield! yield! :yielded? yielded?
                  :shown shown :refresh! refresh-fn :actions by-id}}}))

(def part
  "The menus part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :menus}))
