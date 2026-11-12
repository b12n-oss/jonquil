(ns jonquil.gtk.window.tabs
  "The window's tabs: finding, selecting and switching them, their labels, closing one (asking first when it is dirty), a new tab, opening a file into the right tab (open-file!, read-for-tab), and wire-tab!, which connects one tab's editor, find bar, popovers, menu and palette to the window. build makes activate!'s close, new, step and digit tab fns. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window unchanged, then wire-tab!'s statements split into named steps; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.changed :as changed]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.commands :as commands]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.gutter :as gutter]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.tab :as tab]
            [jonquil.gtk.window.alerts :as win-alerts]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.help :as win-help]
            [jonquil.gtk.window.keys :as win-keys]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.runtime :as win-runtime]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.hints :as hints]
            [jonquil.parts :as parts]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.tabs :as tabs]
            [jonquil.theme :as theme]))

;; --- tab operations ----------------------------------------------------------
;; ctx below is the window's tab map (activate!'s tab-ctx): {:tabs :active-id
;; :notebook :win :cv :line-col-label :close-popovers-fn :popovers ...}.

(defn tab-by-id [tabs id] (some (fn [t] (when (= id (:id t)) t)) @tabs))

(defn refresh-labels!
  "Sets every tab's label (jonquil.tabs/labels) and shows the strip only
  while there are two or more tabs. Every open, close and path change comes
  through here, so it also saves the tab list (session-changed!)."
  [{:keys [tabs notebook] :as ctx}]
  (let [ts @tabs]
    (doseq [[t text] (map vector ts (tabs/labels (map (fn [t] {:path @(:path t) :dirty? (editor/dirty? (:ed t))}) ts)))]
      (tab/set-label! t text))
    (g/gtk-notebook-set-show-tabs notebook (if (> (count ts) 1) 1 0)))
  (win-session/session-changed! ctx))

(defn- on-switch!
  "Makes t the active tab: the window's title, Ln/Col and focus follow it,
  and every open popover closes (one on a hidden page would be lost). The
  saved tab list records the new active tab (session-changed!)."
  [{:keys [active-id win line-col-label close-popovers-fn hint-label] :as ctx} t]
  (reset! active-id (:id t))
  ;; LM6's key hint was for the tab left behind
  (when hint-label (g/gtk-widget-set-visible hint-label 0))
  (when-let [r (:recent-tabs ctx)]
    (swap! r (fn [v] (into [(:id t)] (remove #{(:id t)}) v))))
  (close-popovers-fn)
  (parts/run-tab-hooks! ctx :on-switch t)
  (win-files/update-title! (:ed t) win (:path t))
  (let [[line col] (g/cursor-line-col (:buf (:ed t)))]
    (g/gtk-label-set-text line-col-label (str "Ln " (inc line) ", Col " (inc col))))
  (editor/focus! (:ed t))
  ;; The Parinfer label follows the active tab, whose project may override
  ;; the global mode.
  (when-let [f (:parinfer-label-update! ctx)] (f))
  (win-session/session-changed! ctx))

(defn- tab-by-root
  "The tab in tabs whose page root is root, or nil."
  [tabs root]
  (some (fn [t] (when (= root (:root t)) t)) @tabs))

(defn switch-page-fn
  "The notebook's switch-page handler. The tab is found from the
  page argument, never get_current_page (which reads -1 while a current
  page is being removed), and a page that isn't in tabs (the first
  append, or a tab already taken out) is ignored."
  [{:keys [tabs] :as ctx}]
  (fn [_nb page _n _data]
    (when-let [t (tab-by-root tabs page)]
      (on-switch! ctx t))
    nil))

(defn select-tab!
  "Shows tab id's page. set_current_page fires switch-page, except when the
  page is already current, so on-switch! runs here in that case."
  [{:keys [tabs notebook active-id] :as ctx} id]
  (when-let [t (tab-by-id tabs id)]
    (g/gtk-notebook-set-current-page notebook (g/gtk-notebook-page-num notebook (:root t)))
    (when-not (= id @active-id) (on-switch! ctx t))))

(defn- release-tab!
  "Lets go of what tab t holds outside its widgets: a pending Run Form
  flash, its namespaces in the REPL and its place as the replay tab."
  [{:keys [cv ev*]} t]
  (let [id (:id t)]
    ;; A Run Form flash still pending would restore the selection on
    ;; this tab's buffer after it's destroyed.
    (when-let [cancel! (:cancel! (:flash-fns t))] (cancel!))
    ;; Nothing will Run as this tab again, so
    ;; its namespaces go to the REPL's set for the next Run to clear.
    (let [e @ev*] (when (satisfies? ev/Owners e) (ev/release-owner! e id)))
    ;; LM7: a closed replay tab is no longer the one whose Run forgets
    (when (= id (:id @(:replay-tab* cv))) (reset! (:replay-tab* cv) nil))))

(defn- adopt-last-tab!
  "One tab left, from another project: the window adopts it (a runtime
  switch, with its \"Switched to\" line)."
  [{:keys [tabs sel* sel-ctx cv]}]
  (let [[only & more] @tabs
        osel (when only @(:project only))]
    (when (and only (not more) osel
               (not (tabs/runnable-here? @sel* osel)))
      (try (win-runtime/apply-selection! sel-ctx osel)
           (catch :default e
             (cview/append! cv :error (str "; project: " (ex-message e) "\n")))))))

(defn- forget-tab!
  "remove-tab!'s bookkeeping for tab id: out of the recent tab ids, tabs
  and the popover registry (gone is the set of its popovers), and, when
  it was the active one (a :neighbour is given), the neighbour becomes
  the active id. ctx is remove-tab!'s."
  [{:keys [tabs active-id popovers] :as ctx} id {:keys [gone neighbour]}]
  (when-let [r (:recent-tabs ctx)] (swap! r (fn [v] (vec (remove #{id} v)))))
  (swap! tabs (fn [v] (vec (remove (fn [x] (= id (:id x))) v))))
  (when neighbour (reset! active-id (:id neighbour)))
  (swap! popovers (fn [ps] (vec (remove gone ps)))))

(defn- remove-tab!
  "Takes tab id out of the window, in this order: out of tabs first
  (so the switch-page remove_page fires never resolves to it), its five
  popovers out of the registry and unparented (unparenting drops their
  last reference, and a page removed with a popover still parented spins
  GTK in a warning loop), then the page, then the neighbour selected."
  [{:keys [tabs active-id notebook] :as ctx} id]
  (when-let [t (tab-by-id tabs id)]
    (let [ts @tabs
          i (.indexOf ^java.util.List (mapv :id ts) id)
          neighbour (or (get ts (inc i)) (get ts (dec i)))
          was-active? (= id @active-id)
          gone (set (tab/popovers t))]
      ;; while the buffer is alive, so the list's marks can be deleted
      (parts/run-tab-hooks! ctx :on-tab-removed t)
      (forget-tab! ctx id {:gone gone :neighbour (when was-active? neighbour)})
      (release-tab! ctx t)
      (tab/dispose-popovers! t)
      (g/gtk-notebook-remove-page notebook (g/gtk-notebook-page-num notebook (:root t)))
      (refresh-labels! ctx)
      (when was-active?
        (g/gtk-notebook-set-current-page notebook (g/gtk-notebook-page-num notebook (:root neighbour)))
        (on-switch! ctx neighbour))
      (adopt-last-tab! ctx))))

(defn- save-then-close!
  "The close alert's Save: saves to the tab's path, or asks for one
  first, then close-if-saved!. A dismissed dialog does nothing."
  [{:keys [cv win]} {:keys [ed path close-if-saved!]}]
  (if-let [p @path]
    (do (win-files/save-to! ed cv path p) (close-if-saved!))
    (win-files/file-dialog! :save win
                            (fn [{p :path err :error :as r}]
                              (cond
                                p (do (win-files/save-to! ed cv path p) (close-if-saved!))
                                (win-files/dialog-dismissed? r) nil
                                :else (cview/append! cv :error (str "; save: " err "\n")))))))

(defn close-tab-choice!
  "The close-tab alert's decision: 0 Save (then close once saved), 1 Don't
  Save (close), 2 Cancel or a dismissal (nothing)."
  [{:keys [tabs] :as ctx} id {:keys [index error]}]
  (when-let [{:keys [ed path]} (tab-by-id tabs id)]
    (let [close-if-saved! (fn [] (refresh-labels! ctx) (when-not (editor/dirty? ed) (remove-tab! ctx id)))]
      (cond
        error nil
        (= index 1) (remove-tab! ctx id)
        (= index 0) (save-then-close! ctx {:ed ed :path path :close-if-saved! close-if-saved!})
        :else nil))))

(defn- ask-close!
  "Shows the close alert for dirty tab id, saved at p (nil: Untitled);
  close-tab-choice! acts on the answer."
  [{:keys [win cv] :as ctx} id p]
  (reset! win-alerts/alert-pending? true)
  (try
    (win-alerts/show-alert! win (tabs/close-alert [(if p (win-files/file-name p) "Untitled")]) win-alerts/CLOSE-ALERT
                            (fn [result] (close-tab-choice! ctx id result)))
    (catch :default e
      (reset! win-alerts/alert-pending? false)
      (cview/append! cv :error (str "; close: " (ex-message e) "\n")))))

(defn close-tab!
  "Cmd/Ctrl+W and a tab's ×: on the last tab, closes the window (with its
  own alert). Otherwise a dirty tab asks first, with the
  same alert the window uses for one file; a clean one closes."
  [{:keys [tabs win] :as ctx} id]
  (when-let [{:keys [ed path]} (tab-by-id tabs id)]
    (cond
      (= 1 (count @tabs)) (g/gtk-window-close win)
      (editor/dirty? ed) (when-not @win-alerts/alert-pending? (ask-close! ctx id @path))
      :else (remove-tab! ctx id))))

;; --- tabs ---------------------------------------------------------------------
;; The window keeps its tabs in a vector (tabs) and the active one's id
;; (active-id); everything per buffer lives in a tab map built by
;; jonquil.gtk.tab/make-tab! and wired here. Closures built in wire-tab!
;; capture their own tab, never the active one: a tab's editor keys, menu
;; and popovers act on that tab even while another is active.

(defn- draw-changed!
  "LM3's marks for text against r (the ran set read with it), computed off the GTK thread; drawn on the GTK thread only while the editor is alive and r and text are still current. A throw while computing draws nothing."
  [{:keys [ed ran gutter r text]}]
  (when-let [rs (try (mapv (juxt :start :end) (changed/changed text r))
                     (catch Throwable _ nil))]
    (on-gui! (fn [] (when (and (not @(:destroyed? ed))
                               (identical? r @ran)
                               (= text (editor/editor-text ed)))
                      (gutter/set-ranges! gutter rs))))))

(defn- refresh-changed!
  "LM3's redraw, on the GTK thread: for a live editor, the marks for its text once something has run (draw-changed!, in a future), else no marks."
  [{:keys [ed ran gutter]}]
  (when-not @(:destroyed? ed)
    (if-let [r @ran]
      (let [text (editor/editor-text ed)]
        (future (draw-changed! {:ed ed :ran ran :gutter gutter :r r :text text})))
      (gutter/set-ranges! gutter []))))

(defn- debounce-refresh!
  "Counts an edit in pending and, on a future, calls refresh! once 150 ms
  pass with no later edit. Returns the future."
  [pending refresh!]
  (let [n (swap! pending inc)]
    (future (Thread/sleep 150)
            (when (= n @pending) (refresh!)))))

(defn- wire-changed-marks!
  "LM3 for t's editor: its :refresh atom gets refresh!, which redraws the changed-form marks (refresh-changed!), and every edit after a first run calls it 150 ms after the last change. Does nothing for an editor without :changed."
  [{:keys [ed]}]
  ;; LM3: the gutter's marks follow edits, 150 ms after the last change,
  ;; computed off the GTK thread and drawn only if the text is still the same
  (when-let [{:keys [ran refresh gutter]} (:changed ed)]
    (let [pending (atom 0)
          ;; a closed tab's buffer and gutter are gone (:destroyed?), and a
          ;; result is drawn only for the text and ran set it was computed for
          refresh! (fn []
                     (on-gui! (fn [] (refresh-changed! {:ed ed :ran ran :gutter gutter}))))]
      (reset! refresh refresh!)
      (g/g-signal-connect-data (:buf ed) "changed"
                               (callable (fn [_buf _data]
                                           (when @ran (debounce-refresh! pending refresh!))
                                           nil)
                                         [:pointer :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT))))

(defn- make-quick-doc-fn
  "t's quick doc action: a no-arg fn calling win-help/do-quick-doc! with
  t's popovers and a ctx for its example buttons. ctx is tab-actions's."
  [{:keys [win cv ev* status-label close-popovers-fn active? sel*]}
   {:keys [ed help-pop full-doc-pop last-shown] :as t}]
  (fn []
    (win-help/do-quick-doc! {:ed ed :ev* ev* :status-label status-label :help-pop help-pop
                             :full-doc-pop full-doc-pop :last-shown last-shown
                             :example-ctx {:tab t :cv cv :ev* ev* :sel* sel* :status-label status-label
                                           :full-doc-pop full-doc-pop}
                             :close-popovers-fn close-popovers-fn :win win
                             :active? (fn [] (active? t))})))

(defn- install-editor-menu!
  "Builds ed's context menu. Its ctx (jonquil.command-registry/editor-menu)
  is the tab's own fns, each the one its key calls: acts is {:run-form!
  :jump! :rename! :quick-doc! :complete!}, joined by the window's inspector
  and find fns from ctx (tab-actions's). Returns the menu's action group."
  [{:keys [status-label inspect-last-fn inspect-form-fn open-find-fn open-replace-fn]} ed acts]
  (let [menu-ctx (merge acts
                        {:inspect-last-fn inspect-last-fn :inspect-form-fn inspect-form-fn
                         :toggle-comment! (fn [] (editor/do-toggle-comment! ed))
                         :format-all! (fn [] (editor/do-format-all! ed))
                         :format-selection! (fn [] (editor/do-format-selection! ed))
                         :indent! (fn [] (editor/do-indent! ed))
                         :unindent! (fn [] (editor/do-unindent! ed))
                         :open-find-fn open-find-fn :open-replace-fn open-replace-fn})]
    (win-keys/install-context-menu! (:view ed) (cmd-registry/menu-items cmd-registry/editor-menu menu-ctx)
                                    (fn [msg] (win-status/set-status! status-label msg)))))

(defn- tab-actions
  "t's own actions, each a fn bound to this tab: the Run Form flash fns, quick doc, jump, rename, rename-to, complete and Run Form, and the editor's context menu built from them (its action group is :menu-group). Returns them under wire-tab!'s names for them."
  [{:keys [cv ev* status-label close-popovers-fn source-ctx sel*] :as ctx}
   {:keys [ed rename-pop completion] :as t}]
  (let [flash-fns (win-run/make-flash-fn ed)
        quick-doc-fn (make-quick-doc-fn ctx t)
        jump-fn (fn [] (win-help/do-jump! (source-ctx t)))
        rename-fn (fn [] (win-help/do-rename! ed rename-pop status-label close-popovers-fn))
        rename-to-fn (fn [name] (win-help/do-rename-to! ed rename-pop name status-label))
        complete-fn (fn [] (win-help/do-complete! ed completion status-label))
        run-form-fn (fn [] (win-run/do-run-form! {:tab t :cv cv :ev* ev* :sel* sel* :status-label status-label
                                                  :flash-fns flash-fns}))
        menu-group (install-editor-menu! ctx ed {:run-form! run-form-fn :jump! jump-fn :rename! rename-fn
                                                 :quick-doc! quick-doc-fn :complete! complete-fn})]
    {:flash-fns flash-fns :quick-doc-fn quick-doc-fn :jump-fn jump-fn :rename-fn rename-fn
     :rename-to-fn rename-to-fn :complete-fn complete-fn :run-form-fn run-form-fn
     :menu-group menu-group}))

(defn- wire-editor-fns!
  "The editor's and the find bar's error fns (both say on the status line), and the editor's Parinfer and format-style fns, which read t's project when they run."
  [{:keys [status-label] :as ctx} {:keys [ed fb] :as t}]
  (editor/set-error-fn! ed (fn [msg] (win-status/set-status! status-label msg)))
    ;; Parinfer: the tab's editor reads its effective mode on every user
    ;; action: the project's .jonquil.edn :parinfer when it names one, else
    ;; the window's global.
  (when-let [mf (:parinfer-mode-read ctx)]
    (editor/set-parinfer-fns! ed
                              (fn [] (commands/parinfer-effective
                                      (:parinfer (some-> (:project t) deref))
                                      (mf)))
                              (:parinfer-status-fn ctx)))
    ;; The format-style pin: the project's .jonquil.edn :format-style, when
    ;; it names one, decides what this tab's Format All and Format
    ;; Selection produce; nil follows the Parinfer mode.
  (editor/set-format-style-fn! ed
                               (fn [] (:format-style (some-> (:project t) deref))))
  (find-bar/set-error-fn! fb (fn [msg] (win-status/set-status! status-label msg))))

(defn- wire-tab-status!
  "What follows t's edits and caret: the labels, title and tree on a dirty change, the tab's close button, the discard warning re-armed by a save, and the Ln/Col label while t is active."
  [{:keys [win status-label line-col-label active? tree-saved!] :as ctx}
   {:keys [ed path discard-warned?] :as t}]
  (editor/on-dirty-changed! ed (fn [dirty]
                                 (refresh-labels! ctx)
                                 (when (active? t) (win-files/update-title! ed win path))
                                   ;; A save shows its file in the tree.
                                 (when (and (not dirty) tree-saved!) (tree-saved! t))))
  (g/g-signal-connect-data (:close-btn t) "clicked"
                           (callable (win-status/contain-click status-label "close tab" (fn [_] (close-tab! ctx (:id t))))
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; A dirty->clean transition (a successful Save) re-arms the
    ;; discard warning, so an unrelated LATER dirty streak earns its own.
  (editor/on-dirty-changed! ed (fn [dirty] (when-not dirty (reset! discard-warned? false))))
    ;; The status bar's Ln/Col label, the active tab's position only.
  (editor/on-cursor-moved!
   ed (fn [[line col]]
        (when (active? t)
          (g/gtk-label-set-text line-col-label (str "Ln " (inc line) ", Col " (inc col)))))))

(defn- wire-doc-popovers!
  "Quick doc and full doc join the window's popover registry, every open popover of t follows a scroll, and the editor gets its quick doc and jump fns; a caret move hides quick doc."
  [{:keys [register-popover-fn]} {:keys [ed help-pop full-doc-pop] :as t} {:keys [quick-doc-fn jump-fn]}]
    ;; The quick-doc and full-doc popovers join the window's registry
    ;; (Escape and teardown close them) and close on any caret move or
    ;; focus leaving the editor.
  (register-popover-fn help-pop)
  (register-popover-fn full-doc-pop)
    ;; A scroll moves the text, so every open caret-anchored popover of
    ;; this tab follows the caret, or hides once it leaves the visible text
    ;; (jonquil.gtk.popover/follow-caret!). value-changed fires on the GTK
    ;; thread; both axes, one handler.
  (let [follow (callable (fn [_adj _data]
                           (doseq [p (tab/popovers t)]
                             (popover/follow-caret! p))
                           nil)
                         [:pointer :pointer] :void)]
    (doseq [adj [(g/gtk-scrollable-get-vadjustment (:view ed))
                 (g/gtk-scrollable-get-hadjustment (:view ed))]]
      (g/g-signal-connect-data adj "value-changed" follow ffi/null ffi/null g/CONNECT-DEFAULT)))
  (editor/set-quick-doc-fn! ed quick-doc-fn)
  (editor/set-jump-fn! ed jump-fn)
  (editor/on-cursor-moved! ed (fn [_lc] (when (popover/open? help-pop) (popover/hide! help-pop)))))

(defn- caret-hint
  "What keys do at ed's caret, as the key hints label's text, or nil when
  there is nothing to say. Reads only the caret's line, a window of the
  buffer around it."
  [ed]
  (let [buf (:buf ed)
        pos (g/cursor-offset buf)
        a (max 0 (- pos 1000))
        text (g/buffer-text-range buf a (min (g/gtk-text-buffer-get-char-count buf) (+ pos 1000)))
        [line at] (hints/line-at text (- pos a))
        plugins @plugin-registry/live]
    (hints/hint {:at (hints/at-caret line at)
                 :keymap {:editor (plugin-registry/editor-bindings plugins)
                          :window (plugin-registry/window-bindings plugins)}
                 :os (System/getProperty "os.name")})))

(defn- hint-tick!
  "One LM6 key hint timeout: while it is still the newest move, hints are on, t is active and its editor alive, shows what keys do at the caret in hint-label, or hides it; a throw hides it and is said on the console once. Answers 0, so the timeout runs once."
  [{:keys [n pending failed? hints-on? active? t ed hint-label cv]}]
  (when (and (= n @pending) @hints-on? (active? t) (not @(:destroyed? ed)))
    (try
      (let [h (caret-hint ed)]
        (g/gtk-label-set-text hint-label (or h ""))
        (g/gtk-widget-set-visible hint-label (if h 1 0)))
                        ;; said once on the console, then quiet
      (catch :default e
        (g/gtk-widget-set-visible hint-label 0)
        (when (compare-and-set! failed? false true)
          (cview/append! cv :error (str "; key hints: " (or (ex-message e) (str e)) "\n"))))))
  0)

(defn- wire-key-hints!
  "LM6's key hints for t, when the window has a hint label: each caret move arms a 200 ms timeout that runs hint-tick!."
  [{:keys [cv active? hint-label hints-on?]} {:keys [ed] :as t}]
    ;; LM6: what keys do at the caret, 200 ms after it stops: a one-shot
    ;; GLib timeout per move, the newest one alone acting. It reads only the
    ;; caret's line (a window of the buffer around it), so the work is the
    ;; same on any size of file and stays on the GTK thread.
  (when hint-label
    (let [pending (atom 0)
          failed? (atom false)]
      (editor/on-cursor-moved!
       ed (fn [_lc]
            (let [n (swap! pending inc)]
              (g/g-timeout-add
               200
               (callable
                (fn [_data]
                  (hint-tick! {:n n :pending pending :failed? failed? :hints-on? hints-on? :active? active?
                               :t t :ed ed :hint-label hint-label :cv cv}))
                [:pointer] :int :on-throw 0)
               ffi/null)))))))

(defn- wire-popover-hides!
  "Quick doc hides when the editor loses focus, full doc on a caret move or on a focus loss it doesn't take itself, and rename, which hides itself (autohide), only joins the registry."
  [{:keys [register-popover-fn]} {:keys [ed help-pop full-doc-pop rename-pop]}]
  (editor/on-focus-lost! ed (fn [] (when (popover/open? help-pop) (popover/hide! help-pop))))
  (editor/on-cursor-moved! ed (fn [_lc] (when (popover/open? full-doc-pop) (popover/hide! full-doc-pop))))
  (editor/on-focus-lost! ed (fn [] (help-popover/hide-unless-focused! full-doc-pop)))
    ;; Rename is autohide ON, so GTK's own grab closes it.
  (register-popover-fn rename-pop))

(def ^:private palette-entry-fns
  ;; A palette entry that is a vector ([:tab/switch id]) is the window's own,
  ;; not a registry command: the window-key-args fn that runs it, by the
  ;; entry's first element. The tab, panel (as its key does), Compare with
  ;; tab (LM3), named layout (LM8) and Restore an Earlier World entries.
  {:tab/switch :switch-tab-fn
   :panel/switch :panel-focus-fn
   :compare/tab :compare-tab-fn
   :layout/use :layout-use-fn
   :docs/page :docs-page-fn
   :world/restore-from :world-restore-from-fn})

(defn- palette-run!
  "The command palette's run of id, as its key would: a tab, panel, layout or earlier-world entry through window-key-args' fns, anything else as a registry command with the window's, this editor's and the console's ctx. A throw is said on the status line."
  [{:keys [id window-key-args ed repl-complete-fn status-label]}]
  (try
                  ;; a tab entry ([:tab/switch id]) is the window's own,
                  ;; not a registry command
    (if-let [k (and (vector? id) (get palette-entry-fns (first id)))]
      ((k window-key-args) (second id))
      (cmd-registry/run-command-in (plugin-registry/all-commands @plugin-registry/live)
                                   id {:window (win-keys/window-key-ctx window-key-args 0 0)
                                       :editor (editor/key-ctx ed)
                                       :console {:complete! repl-complete-fn}}))
    (catch :default e
      (win-status/set-status! status-label (str "; palette: " (ex-message e))))))

(defn- wire-palette!
  "t's command palette joins the registry and runs its entries with palette-run!."
  [{:keys [status-label register-popover-fn window-key-args repl-complete-fn]} {:keys [ed palette]}]
    ;; The command palette runs a command as its key would, with its
    ;; scope's ctx: the window's, this tab's editor's, the console's
  (register-popover-fn palette)
  (palette/set-handlers!
   palette
   {:on-run (fn [id]
              (palette-run! {:id id :window-key-args window-key-args :ed ed
                             :repl-complete-fn repl-complete-fn :status-label status-label}))
    :on-error (fn [msg] (win-status/set-status! status-label msg))}))

(defn- connect-rename-entry!
  "Connects the rename popover's entry Enter (activate) to rename-to-fn
  with the entry's text. A throw is said on the status line, never raised
  into GTK."
  [status-label rename-pop rename-to-fn]
  (g/g-signal-connect-data (:entry rename-pop) "activate"
                           (callable (fn [_entry _data]
                                       (try
                                         (rename-to-fn (help-popover/rename-entry-text rename-pop))
                                         (catch :default e
                                           (win-status/set-status! status-label (str "; rename: " (ex-message e)))))
                                       nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- wire-editor-actions!
  "The editor's rename, Run Form and flash cancel fns, the rename entry's Enter, and t's completion."
  [{:keys [status-label register-popover-fn]}
   {:keys [ed rename-pop completion]}
   {:keys [flash-fns rename-fn rename-to-fn complete-fn run-form-fn]}]
  (editor/set-rename-fn! ed rename-fn)
  (editor/set-run-form-fn! ed run-form-fn)
  (editor/set-flash-cancel-fn! ed (:cancel! flash-fns))
  (connect-rename-entry! status-label rename-pop rename-to-fn)
  (win-help/wire-completion! completion
                             {:complete-fn complete-fn :status-label status-label
                              :register-popover-fn register-popover-fn
                              :set-fns! (fn [fns] (editor/set-completion-fns! ed fns))
                              :on-moved! (fn [f] (editor/on-cursor-moved! ed f))
                              :on-focus-lost! (fn [f] (editor/on-focus-lost! ed f))}))

(defn- watch-tab-path!
  "Re-selection whenever t's path changes (Open, Save As): t's project, the window's when t is its only tab, the editor's profile, the labels and the tree."
  [{:keys [cv sel-ctx sel* tabs settings-path* tree-saved!] :as ctx} {:keys [ed path] :as t}]
    ;; Re-selection whenever this tab's path changes (Open, Save As). The
    ;; reset! runs on the GTK thread, so the watch does too; it never
    ;; throws into GTK. set-profile! sits in a finally so the editor
    ;; follows @sel* even when apply-selection! throws after its own reset!.
    ;; The tab keeps its own selection in :project, and paints with that
    ;; selection's profile.
  (add-watch path ::dialect
             (fn [_ _ old new]
               (when (not= old new)
                 (let [tsel (win-runtime/select-for new @settings-path*)]
                   (reset! (:project t) tsel)
                     ;; With one tab the window follows its project; with
                     ;; two or more the window's project is fixed.
                     ;; A loose Jolt file keeps the window's own Jolt, as
                     ;; it does when tabs close down to it (keeps-window-jolt?).
                   (try (when (and (= 1 (count @tabs)) (not (tabs/keeps-window-jolt? @sel* tsel)))
                          (win-runtime/apply-selection! sel-ctx tsel))
                        (catch :default e
                          (cview/append! cv :error (str "; project: " (ex-message e) "\n")))
                        (finally (editor/set-profile! ed (:profile tsel))
                                 (refresh-labels! ctx)
                                   ;; The tab's project just changed, and
                                   ;; its :parinfer with it.
                                 (when-let [f (:parinfer-label-update! ctx)] (f))
                                 (when tree-saved! (tree-saved! t)))))))))

(defn- with-actions
  "t with tab-actions's acts on it: :flash-fns, :menu-group and :actions
  {:quick-doc! :jump! :rename! :rename-to! :complete! :run-form!}."
  [t {:keys [flash-fns quick-doc-fn jump-fn rename-fn rename-to-fn complete-fn run-form-fn menu-group]}]
  (assoc t
         :flash-fns flash-fns
         :menu-group menu-group
         :actions {:quick-doc! quick-doc-fn :jump! jump-fn :rename! rename-fn
                   :rename-to! rename-to-fn :complete! complete-fn :run-form! run-form-fn}))

(defn- wire-tab!
  "Connects one tab to the window, doing for its editor everything the
  window needs of an editor, and returns the tab with
  :flash-fns, :menu-group and :actions {:quick-doc! :jump! :rename!
  :rename-to! :complete! :run-form!} (each a fn bound to this tab) added.
  ctx is the window's map; see add-tab!."
  {:arglists '([{:keys [win cv ev* status-label line-col-label close-popovers-fn register-popover-fn
                        active? source-ctx sel-ctx sel* tabs settings-path* inspect-last-fn inspect-form-fn tree-saved!
                        open-find-fn open-replace-fn window-key-args repl-complete-fn hint-label hints-on?] :as ctx}
                {:keys [ed fb help-pop full-doc-pop rename-pop completion palette last-shown path discard-warned?] :as t}])}
  [ctx t]
  ;; LM4 (the debugger part's :on-tab-wired hook): the gutter's line counts,
  ;; first, so its "changed" handler connects before LM3's
  (parts/run-tab-hooks! ctx :on-tab-wired t)
  (wire-changed-marks! t)
  (let [acts (tab-actions ctx t)]
    (wire-editor-fns! ctx t)
    (wire-tab-status! ctx t)
    (wire-doc-popovers! ctx t acts)
    (wire-key-hints! ctx t)
    (wire-popover-hides! ctx t)
    (wire-palette! ctx t)
    (wire-editor-actions! ctx t acts)
    (watch-tab-path! ctx t)
    (with-actions t acts)))

(defn add-tab!
  "Builds a tab for path (nil for Untitled) holding text, with the
  window's current theme and the profile path's own selection gives it,
  appends its page to the notebook, wires it, and only then adds it to
  tabs. Returns the wired tab. The notebook fires switch-page
  inside the first append, before the tab is in tabs, so the caller makes
  the tab active itself. ctx is wire-tab!'s map plus {:tabs :next-id
  :notebook :theme-name :completion-opts}."
  [{:keys [tabs next-id notebook theme-name completion-opts settings-path* sel*] :as ctx} {:keys [path text]}]
  (let [tsel (win-runtime/select-for path @settings-path*)
        t (tab/make-tab! {:id (swap! next-id inc) :path path :text text
                          :theme (theme/theme @theme-name)
                          :profile (:profile (if path tsel @sel*))
                          :completion-opts completion-opts})]
    (when path (reset! (:project t) tsel))
    (g/gtk-notebook-append-page notebook (:root t) (:label-box t))
    (let [wired (wire-tab! ctx t)]
      (swap! tabs conj wired)
      wired)))

(defn new-tab!
  "Cmd/Ctrl+T and Open: a tab for path (nil for Untitled) holding text,
  placed right after the active tab and selected. A failure
  while building it goes on the console and leaves the tabs as they were."
  [{:keys [tabs notebook active-id cv] :as ctx} {:keys [path text]}]
  (try
    (let [i (.indexOf ^java.util.List (mapv :id @tabs) @active-id)
          t (add-tab! ctx {:path path :text (or text "")})]
      (swap! tabs (fn [v] (tabs/insert-after (vec (remove (fn [x] (= (:id t) (:id x))) v)) i t)))
      (g/gtk-notebook-reorder-child notebook (:root t) (inc i))
      (refresh-labels! ctx)
      (select-tab! ctx (:id t))
      t)
    (catch :default e
      (cview/append! cv :error (str "; new tab: " (ex-message e) "\n"))
      nil)))

(defn- read-for-tab
  "{:text t} for path, warning on the console when it isn't UTF-8 (as
  open-path! does), or {:error msg} after appending it. A file holding a
  NUL byte is refused, as open-path! refuses it, before the U+FFFD
  warning can print."
  [cv path]
  (try
    (let [text (slurp path)]
      (if-let [n (tabs/nul-line text)]
        (let [msg (str path " holds a NUL byte on line " n
                       ", which Jonquil can't edit, so it wasn't opened.")]
          (cview/append! cv :error (str "; open: " msg "\n"))
          {:error msg})
        (do
          (when (str/includes? text "�")
            (cview/append! cv :system
                           "This file isn't valid UTF-8; unreadable bytes show as � and saving will replace them.\n"))
          {:text text})))
    (catch :default e
      (cview/append! cv :error (str "; open: " (ex-message e) "\n"))
      {:error (ex-message e)})))

(defn open-file!
  "Open: the tab already showing path is selected;
  otherwise an untouched Untitled active tab is filled (open-path!);
  otherwise path opens in a new tab. Returns {:error msg} when the file
  couldn't be read, else nil."
  [{:keys [tabs cv win active-tab] :as ctx} path]
  (let [ts @tabs
        t (active-tab)]
    (if-let [i (tabs/find-open (map (fn [x] {:path @(:path x)}) ts) path)]
      (do (select-tab! ctx (:id (nth ts i))) nil)
      (if (tabs/untouched-untitled? {:path @(:path t) :text (editor/editor-text (:ed t))})
        (let [r (win-files/open-path! (:ed t) cv (:path t) path)]
          (win-files/update-title! (:ed t) win (:path t))
          (refresh-labels! ctx)
          r)
        (let [{:keys [text error]} (read-for-tab cv path)]
          (if error
            {:error error}
            (do (new-tab! ctx {:path path :text text}) nil)))))))

(defn build
  "activate!'s tab fns, in activate!'s order: Cmd/Ctrl+W's close, Cmd/Ctrl+T's new tab, and the step and digit fns that select a tab by its place. w holds what they read: :tabs :active-id :active-tab, and :tab-ctx* (activate!'s *tab-ctx, read when one of them runs). Returns w with :close-fn :new-tab-fn :step-tab-fn and :digit-tab-fn added."
  [{:keys [tabs active-id active-tab] *tab-ctx :tab-ctx* :as w}]
  (let [;; Cmd/Ctrl+W closes the active tab; on the last one close-tab!
        ;; closes the window.
        close-fn (fn [] (close-tab! @*tab-ctx (:id (active-tab))))
        new-tab-fn (fn [] (new-tab! @*tab-ctx {}))
        step-tab-fn (fn [delta]
                      (let [ts @tabs
                            i (.indexOf ^java.util.List (mapv :id ts) @active-id)]
                        (select-tab! @*tab-ctx (:id (nth ts (tabs/cycle-index (count ts) i delta))))))
        digit-tab-fn (fn [digit]
                       (let [ts @tabs]
                         (when-let [i (tabs/index-for-digit (count ts) digit)]
                           (select-tab! @*tab-ctx (:id (nth ts i))))))]
    (assoc w :close-fn close-fn :new-tab-fn new-tab-fn :step-tab-fn step-tab-fn :digit-tab-fn digit-tab-fn)))
