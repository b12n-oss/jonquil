(ns jonquil.gtk.window.keys
  "The window's keys: on-window-key, the capture-phase handler that runs
  jonquil/keymap.edn's :window bindings through jonquil.command-registry;
  window-key-ctx, the per-key ctx it and the command palette build, with its
  focus checks; the editor's and the console's context menus; and the F6 focus
  toggle and the two console clears. Internal to the window, as
  jonquil.gtk.window.status is. Moved out of jonquil.gtk.window unchanged."
  (:require [jolt.ffi :as ffi]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.describe :as describe]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.plugin-registry :as plugin-registry]))

;; --- window commands: F6 focus toggle, clear console, zoom -----------------

(defn do-toggle-focus!
  "F6 (Jonquil's own key): moves focus between the editor and
  console panes. Compares gtk_root_get_focus's FOCUS WIDGET pointer, not
  gtk_widget_has_focus -- measured: has_focus reads false on a window that isn't the OS key window, which a
  self-test window (and jolt gtk:selftest's own window) usually isn't. When
  the console gets focus, cview/focus! also places its cursor at the end
  of the input."
  [win ed cv]
  (if (= (g/gtk-root-get-focus win) (:view ed))
    (cview/focus! cv)
    (editor/focus! ed)))

(defn do-clear-console! [cv]
  (cview/clear-output! cv))

(defn do-clear-last-output!
  "Cmd/Ctrl+Shift+L (CIDER's C-c C-o): clears only the most recent
  entry's own block. cview/clear-last-output! does the actual work; this
  is only the window's own dispatch fn, same shape as do-clear-console!
  just above."
  [cv]
  (cview/clear-last-output! cv))

;; --- find/replace bar -------------------------------------------------

(defn- find-bar-focused?
  "True when keyboard focus is anywhere inside the find bar (the
  has_focus caveat of do-toggle-focus! applies here too, so this reads the ROOT's focus-widget
  pointer via gtk_root_get_focus, not gtk_widget_has_focus). Used only to
  route Escape: the window's own capture-phase key controller runs BEFORE
  the bar's own (win is a more outer ancestor than the bar's box), so
  without this check Escape would always reach the window's
  evaluating?-gated Stop handling first, regardless of where focus
  actually is.

  gtk_root_get_focus returns an entry's internal GtkText DELEGATE, never
  the outer GtkSearchEntry/GtkEntry itself (measured), so comparing the
  focus widget against (:query fb)/(:replacement fb) is always false.
  gtk_widget_is_ancestor(focus, box) sidesteps the delegate: true for
  anything inside the bar's box, measured true with focus in the query
  and the replacement entry, false with focus in the editor and the
  console."
  [win fb]
  (let [f (g/gtk-root-get-focus win)]
    (and (not (ffi/null? f)) (not (zero? (g/gtk-widget-is-ancestor f (:box fb)))))))

(defn- focus-in-popover?
  "True when win's focus is inside pp's popover (its entry's GtkText
  delegate, as find-bar-focused? explains). pp may be nil. Asked of the
  rename popover and of the command palette, both popovers with an entry
  (window-key-ctx's :entry-focused?)."
  [win pp]
  (let [f (g/gtk-root-get-focus win)]
    (boolean (and pp (not (ffi/null? f))
                  (not (zero? (g/gtk-widget-is-ancestor f (:popover pp))))))))

;; --- window-level keys: jonquil/keymap.edn's :window, all of them --------

(defn window-key-ctx
  "The window keymap's ctx for one key: on-window-key's own argument map ctx
  plus the active tab's find-bar and focus reads and actions as fns, so
  jonquil.command-registry never touches GTK itself. keyval0 and state are
  the key's, for the find bar's Escape (:find-bar-key!). The command
  palette builds the same ctx to run a window command as its key would."
  [{:keys [win active-tab pane] :as ctx} keyval0 state]
  ;; The find bar is the active tab's.
  (let [fb (:fb (active-tab))]
    (assoc ctx
           :find-bar-navigable? #(find-bar/nav-ready? fb)
           :find-next! #(find-bar/find-next! fb)
           :find-prev! #(find-bar/find-prev! fb)
           :find-bar-focused? #(find-bar-focused? win fb)
           :find-bar-key! #(find-bar/handle-key! fb keyval0 state)
           :inspector-focused? #(inspector-pane/focused? pane win)
           ;; the rename popover's entry and the command palette's: either
           ;; one keeps every key but Escape
           :entry-focused? #(let [t (active-tab)]
                              (or (focus-in-popover? win (:rename-pop t))
                                  (focus-in-popover? win (:palette t)))))))

(defn- describe-key!
  "Answers a pending Describe Key: prints what the key chord is and runs
  nothing. k is on-window-key's key map ({:keyval :state :os :kname
  :physical :key?}). Returns 1, the key used."
  [ctx {:keys [keyval state os kname physical]}]
  (let [matches (describe/key-matches (assoc ((:live-keys ctx)) :keyval keyval
                                             :state state :physical physical))]
    ((:print-lines! ctx) (describe/describe-lines (describe/chord-text kname state os) matches))
    1))

(defn- remember-key!
  "Adds the key k (on-window-key's key map) to the recent keys, with ran,
  the command that took it (or nil). Recording never throws."
  [ctx {:keys [keyval state os kname]} ran]
  (try
    (swap! (:recent-keys ctx) describe/remember
           (describe/recent-entry kname state os
                                  (describe/typing? kname state (g/printable-key? keyval))
                                  ran))
    (catch :default _ nil)))

(defn- answer-window-key!
  "Runs the window keymap for the key k (on-window-key's key map) through
  jonquil.command-registry/window-answer, the live plugins' :window
  bindings ahead of the shipped ones (a plugin may bind a built-in window
  command), then records the key. kctx is window-key-ctx's. Returns the
  registry's answer."
  [ctx kctx {:keys [keyval state physical key?] :as k}]
  (let [;; one read per key: the bindings and commands agree
        plugins @plugin-registry/live
        ;; the command that took the key, for recent keys
        ran (volatile! nil)
        answer (cmd-registry/window-answer
                (plugin-registry/window-bindings plugins) keyval state
                {:holds? (cmd-registry/holds-fn cmd-registry/window-conditions kctx)
                 ;; Parinfer's Cmd/Ctrl+Alt+P: on macOS Option remaps P's
                 ;; keyval, and the keycode names the key (editor/physical-key)
                 :physical physical
                 ;; plugins' window-scope commands run too, so a :run one bound
                 ;; to a key runs
                 :run! (fn [b]
                         (let [o (cmd-registry/run-command! (merge cmd-registry/window-commands
                                                                   (plugin-registry/commands plugins))
                                                            b kctx)]
                           (when (= :handled o) (vreset! ran (:command b)))
                           o))})]
    ;; Every key but a lone modifier goes into recent keys, after the fact
    ;; and on its own, so recording never blocks a key
    (when key? (remember-key! ctx k @ran))
    answer))

(defn on-window-key
  "The window's capture-phase key handler. Every key goes through
  jonquil.command-registry/window-answer: one fixed stage (while the
  rename popover's or the command palette's entry has focus, every key but
  Escape is the entry's, so Run, close and tab switches can't fire
  mid-typing), then jonquil/keymap.edn's
  :window bindings, first match wins, run through the command registry.
  A command answers :handled (1), :to-gtk (0, the key is GTK's: stop) or
  :next (try the next matching binding). keymap_shipped_test pins what
  each window chord runs and the Escape chain's order and conditions.

  keyval is lowered with gdk_keyval_to_lower first. Caps Lock reports
  the UPPERCASE keyval with no Shift bit, so the chords match whatever Caps
  Lock says, while a binding's own :shift still reads the modifier mask.

  Escape Stops a running program only while one is evaluating;
  otherwise it answers 0, so an IME composition or GTK's own handling gets
  it. Before Stop, Escape goes to the focused find bar (whose own 0, an IME
  preedit composing, is final and goes to GTK, never to Stop), then closes
  any open popover, the focused inspector panel, the focused source panel,
  the file tree's open menu, or leaves the focused tree, in that order,
  without Stopping.

  ctx is the window's map of fns, which the commands and conditions read;
  kctx adds the active tab's find-bar reads and actions and the focus
  checks, so the registry never touches GTK. source-keys ({:focused?
  :close! :back! :open!}, plus an optional :jump-back!, each a no-arg fn)
  is the source panel's: Escape closes it when focused, Cmd/Ctrl+Shift+O
  opens its location in the window, Cmd/Ctrl+[ steps its history back, and
  anywhere else Cmd/Ctrl+[ goes back from a jump that opened a tab
  (:jump-back! true when it went back, else the key passes on). tree-keys
  ({:focused? :leave! :menu-open? :close-menu!}) is the file tree's, and a
  window without a tree passes nil; F2, Delete, Cmd/Ctrl+BackSpace,
  Shift+F10 and Menu match no binding, so they reach the tree's own
  handler.

  The whole answer runs inside try/catch, the same containment
  editor/dispatch-key! gives the editor: a throw from any condition or
  command is reported through set-status! and the key counts as used (1),
  instead of escaping into GTK's signal emission and killing the process."
  [{:keys [status-label] :as ctx}]
  (let [os (System/getProperty "os.name")]
    (fn [_ctl keyval0 keycode state _data]
      (try
        (let [keyval (g/gdk-keyval-to-lower keyval0)
              kctx (window-key-ctx ctx keyval0 state)
              kname (g/keyval-name keyval)
              k {:keyval keyval :state state :os os :kname kname
                 :physical (editor/physical-key os keyval0 keycode state)
                 :key? (and kname (not (describe/modifier-key? kname)))}]
          (if (and (:key? k) (some-> (:describe-pending ctx) (compare-and-set! true false)))
            ;; it answers a pending Describe Key: described, never run
            (describe-key! ctx k)
            (answer-window-key! ctx kctx k)))
        (catch :default e
          (win-status/set-status! status-label (str "; key: " (ex-message e)))
          1)))))

;; --- context menus -------------------------------------------------------

(defn install-context-menu!
  "Builds view's context menu: a GMenu with one
  entry per item, each a detailed action name \"p4.<action-name>\"
  (g_menu_append), set as view's extra-menu (gtk_text_view_set_extra_menu
  -- GTK's own Cut/Copy/Paste/... stay, this only APPENDS); a matching
  GSimpleActionGroup holding one no-parameter GSimpleAction per item,
  each wired to call f (its \"activate\" signal), inserted on view under
  prefix \"p4\" (gtk_widget_insert_action_group). items is [[label
  action-name f] ...], from jonquil.command-registry/menu-items
  -- f runs the item's command, the SAME one its key chord runs.

  Returns the GSimpleActionGroup pointer so a caller can fire an item by
  its own unprefixed action-name through g_action_group_activate_action
  directly on the group -- measured equivalent to
  gtk_widget_activate_action_variant(view, \"p4.<name>\", NULL) on the
  prefixed name, since \"p4\" here is only this group's own insertion
  prefix, not a second name the action itself carries.

  Every item's own \"activate\" callback runs inside try/catch, reporting
  a throw through on-error (fn [msg] -> nil, set-status! as
  jonquil.gtk.window.tabs wires the editor's menu and
  jonquil.gtk.window.context the console's) rather than letting it escape:
  an uncaught throw from a
  GSimpleAction \"activate\" callback goes through Jolt's foreign-callable
  and kills the process (exit 1), measured live -- the same failure mode
  do-complete!/handle-key! guard against, here at the one call site every
  menu item (current and future) shares, rather than in each item's own
  fn."
  [view items on-error]
  (let [menu (g/g-menu-new)
        group (g/g-simple-action-group-new)]
    (doseq [[label action-name f] items]
      (g/g-menu-append menu label (str "p4." action-name))
      (let [action (g/g-simple-action-new action-name ffi/null)]
        (g/g-signal-connect-data action "activate"
                                 (callable (fn [_action _param _data]
                                             (try
                                               (f)
                                               (catch :default e
                                                 (on-error (str "; " action-name ": " (ex-message e)))))
                                             nil)
                                           [:pointer :pointer :pointer] :void)
                                 ffi/null ffi/null g/CONNECT-DEFAULT)
        (g/g-action-map-add-action group action)))
    (g/gtk-widget-insert-action-group view "p4" group)
    (g/gtk-text-view-set-extra-menu view menu)
    group))
