(ns jonquil.gtk.window.quit
  "Closing the window: close-request-fn, which asks before unsaved text is lost
  and otherwise saves the session, stops the runtime and quits; the close
  alert's choice and Save All; unparenting the popovers before the real quit;
  and connect!, which makes activate!'s close-request connect and its quit
  action. build makes activate!'s close flags. Internal to the window, as
  jonquil.gtk.window.status is. Moved out of jonquil.gtk.window unchanged,
  then close-request-fn's branches split into named steps; connect! holds
  activate!'s own statements in their order."
  (:require [jolt.ffi :as ffi]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.window.alerts :as win-alerts]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.runtime-registry :as registry]
            [jonquil.tabs :as tabs]))

;; --- ask before closing ------------------------------------------------------

(defn- finish-close!
  "Confirms and re-triggers the window's own close-request:
  gtk_window_close synchronously re-enters close-request-fn (below), which
  this time (confirmed-close? true) either quits for real or, when
  suppress-close? is set, only records that it WOULD have -- see
  close-request-fn's docstring for why."
  [win confirmed-close?]
  (reset! confirmed-close? true)
  (g/gtk-window-close win))

(defn- save-all!
  "Save All: saves each tab in dirty-tabs in order -- a
  tab with a path through save-to!, an Untitled one through the Save
  dialog, continuing from its callback. The first tab still dirty after
  its save (the save failed) or whose dialog is dismissed stops the
  sequence and is selected, and the window stays open. All saved closes
  the window. ctx is {:cv :win :confirmed-close? :select!}."
  [{:keys [cv win confirmed-close? select!] :as ctx} dirty-tabs]
  (if-let [[{:keys [ed path] :as t} & more] (seq dirty-tabs)]
    (let [next! (fn [] (if (editor/dirty? ed) (select! t) (save-all! ctx more)))]
      (if-let [p @path]
        (do (win-files/save-to! ed cv path p) (next!))
        (do (select! t)
            (win-files/file-dialog!
             :save win
             (fn [{p :path err :error :as r}]
               (cond
                 p (do (win-files/save-to! ed cv path p) (next!))
                 (win-files/dialog-dismissed? r) nil
                 :else (cview/append! cv :error (str "; save: " err "\n"))))))))
    (finish-close! win confirmed-close?)))

(defn close-choice!
  "The window-close alert's decision:
  index 0 Save (Save All with several dirty tabs), 1 Don't Save, 2 Cancel;
  an :error means the dialog was dismissed. Index 2 and an error both do
  nothing -- the window stays open and the text untouched. Index 0 runs
  save-all! over the tabs that were dirty, which closes the window only
  once every one of them saved. Rows call this directly with a synthetic
  {:index n}, exactly as the real dialog's own completion
  callback does. ctx is save-all!'s plus :dirty-tabs."
  [{:keys [dirty-tabs win confirmed-close?] :as ctx} {:keys [index error]}]
  (cond
    error nil
    (= index 2) nil
    (= index 1) (finish-close! win confirmed-close?)
    (= index 0) (save-all! ctx dirty-tabs)
    :else nil))

;; --- popover teardown --------------------------------------------------

(defn- unparent-registered-popovers!
  "Unparents every popover in popovers (a jonquil.gtk.popover map) whose
  GtkWidget parent isn't already null -- gtk_widget_unparent asserts a
  non-null parent (a critical warning, not a crash, but pointless noise),
  so an already-unparented entry (a self-test row's own throwaway popover
  that unparents itself right after hide!) is skipped rather than warned
  at.

  Hides an OPEN popover FIRST (same idiom close-popovers! uses), before
  unparenting it. Otherwise an open popover's own :open? atom stays true
  after its widget is unparented (and, for a floating-ref GtkWidget with
  no other reference, finalized right there); window teardown then clears
  the editor's focus, GtkEventControllerFocus's \"leave\" fires this
  window's hide-on-focus-lost listener, (popover/open? p) still reads
  true, and hide! calls gtk_popover_popdown on the now-freed widget --
  measured live: \"Gtk-CRITICAL: gtk_popover_popdown: assertion
  'GTK_IS_POPOVER (popover)' failed\". Resetting :open? here means every
  later teardown-triggered listener sees false and skips hide!.

  Called from real-quit! only, right before g_application_quit and the
  return 0 that lets GTK start tearing the window down. A session-lifetime popover (jonquil.gtk.popover's hide!
  never unparents, by design) is still parented to its scrolled-window
  parent at that point; without this, GTK's dispose reports \"Finalizing
  GtkScrolledWindow ..., but it still has children left: GtkPopover ...\"
  (measured live). Never called from the self-test's suppress-close?
  branch: that path returns 1 and closes nothing, so unparenting there
  would break every later row's use of the SAME session-lifetime
  popover."
  [popovers]
  (doseq [p @popovers]
    (let [pop (:popover p)]
      (when (popover/open? p) (popover/hide! p))
      (when-not (ffi/null? (g/gtk-widget-get-parent pop))
        (g/gtk-widget-unparent pop)))))

(defn- ask-before-close!
  "close-request-fn's dirty, unconfirmed branch: shows the close alert
  naming dirty-names, whose choice goes to close-choice! with the dirty
  tabs, unless an alert is already pending (alert-pending?). ctx is
  close-request-fn's."
  [{:keys [cv win confirmed-close? select!]} dirty dirty-names]
  (when-not @win-alerts/alert-pending?
    (reset! win-alerts/alert-pending? true)
    ;; A throw while building the alert would leave alert-pending?
    ;; true for the rest of the session (every later close silently
    ;; doing nothing) and escape into GTK's signal emission. Reset
    ;; the flag and report it instead; the window stays open, since
    ;; the text is still unsaved.
    (try
      (win-alerts/show-alert!
       win (tabs/close-alert dirty-names) win-alerts/CLOSE-ALERT
       (fn [result] (close-choice! {:dirty-tabs dirty :cv cv :win win :confirmed-close? confirmed-close?
                                    :select! select!}
                                   result)))
      (catch :default e
        (reset! win-alerts/alert-pending? false)
        (cview/append! cv :error (str "; close: " (ex-message e) "\n"))))))

(defn- save-session-at-quit!
  "The real quit's session save: calls save-session! (nil, or a fn that
  answers nil or an error string) and says a failure or a throw on stderr."
  [save-session!]
  (when save-session!
    (try (when-let [err (save-session!)]
           (binding [*out* *err*]
             (println "jonquil: saving the session failed:" err)))
         (catch :default e
           (binding [*out* *err*]
             (println "jonquil: saving the session failed:" (ex-message e)))))))

(defn- stop-runtime-at-quit!
  "The real quit's runtime stop: ev/stop! on the window's evaluator, a
  throw said on stderr, and the evaluator leaves the registry either way."
  [ev*]
  (try (ev/stop! @ev*)
       (catch :default e
         ;; The window is going away, so the console can't show
         ;; it; the process's real stderr can.
         (binding [*out* *err*]
           (println "jonquil: stopping the runtime failed:" (ex-message e))))
       ;; Leaves jonquil.runtime-registry: this evaluator is
       ;; stopped (or its stop! at least ran), so
       ;; jonquil.main's stop-all! has nothing left for it.
       (finally (registry/unregister! @ev*))))

(defn- real-quit!
  "close-request-fn's real-quit branch: unparents the popovers, saves the
  session, stops the runtime, quits the application and answers 0, so GTK
  proceeds. ctx is close-request-fn's."
  [{:keys [app popovers ev* save-session!]}]
  ;; Unparents every registered popover BEFORE the real quit
  ;; (see unparent-registered-popovers!), never in the
  ;; suppress-close? branch (proceed-close!, below).
  (unparent-registered-popovers! popovers)
  (save-session-at-quit! save-session!)
  (stop-runtime-at-quit! ev*)
  (g/g-application-quit app)
  0)

(defn- proceed-close!
  "close-request-fn's branch for a clean or confirmed close: sets closing?,
  then either stops there for the self-test (suppress-close?: confirmed-close?
  back to false, answers 1) or quits for real (real-quit!, 0). ctx is
  close-request-fn's."
  [{:keys [confirmed-close? suppress-close? closing?] :as ctx}]
  (reset! closing? true)
  (if @suppress-close?
    (do (reset! confirmed-close? false) 1)
    (real-quit! ctx)))

(defn- close-request-fn
  "The window's close-request handler. Dirty and unconfirmed shows
  the alert and suppresses the close (returns 1, GTK does not proceed);
  otherwise it proceeds to quit -- unless suppress-close? is set, in which
  case it only flips closing? and still returns 1.

  suppress-close?/closing? exist ONLY for the self-test (dev/gtk_selftest_
  window.clj's close-dirty-discard/close-dirty-save/close-clean rows): a
  REAL gtk_window_close/g_application_quit synchronously re-enters this
  same handler and, once it returns 0, GTK proceeds to actually tear the
  window down and the pending quit fires as soon as control next reaches
  the main loop (the very next deferred g_timeout_add wait, which most
  later rows need) -- ending the shared self-test process before the rest
  of row-fns runs, silently dropping them from the summary. Only
  row-command-chord-close (the deliberately-last row) is allowed to
  trigger the real thing; every other close-confirmation row sets
  suppress-close? true first and asserts @closing? instead of an actual
  close. While suppressed, confirmed-close? is also reset to false right
  here, so the attempt leaves no residue for the next row's own
  close-request to see -- rows only need to reset suppress-close?/closing?
  themselves afterward.

  A second close-request while an alert is already pending (alert-pending?)
  doesn't open a second one: it still suppresses the close (returns 1), so
  the user just sees the same already-open alert.

  ev* is stopped (ev/stop!, guarded) right before g_application_quit, in
  the real-quit branch only -- never in the suppress-close? self-test
  branch, which doesn't actually quit anything.

  save-session! (the open tabs, for the next launch) runs in the
  real-quit branch only."
  {:arglists '([{:keys [tabs cv win app confirmed-close? suppress-close? closing? popovers ev* select!
                        save-session!]}])}
  [{:keys [tabs confirmed-close?] :as ctx}]
  (fn [_win _data]
    ;; One alert naming every dirty tab, in tab order.
    (let [ts @tabs
          names (tabs/labels (map (fn [t] {:path @(:path t)}) ts))
          dirty (filterv (fn [t] (editor/dirty? (:ed t))) ts)
          dirty-names (keep-indexed (fn [i t] (when (editor/dirty? (:ed t)) (nth names i))) ts)]
      (if (and (seq dirty) (not @confirmed-close?))
        (do (ask-before-close! ctx dirty dirty-names) 1)
        (proceed-close! ctx)))))

(defn connect!
  "activate!'s close-request connect and its application \"quit\" action, in
  activate!'s order. w is close-request-fn's map (:tabs :select! :cv :win :app
  :confirmed-close? :suppress-close? :closing? :popovers :ev* :save-session!),
  which activate! builds, since its :select! and :save-session! closures read
  activate!'s own forward atoms. Called once per window; activate! reads
  nothing it returns."
  [{:keys [win app] :as w}]
  (g/g-signal-connect-data win "close-request"
                           ;; on-throw 1 refuses the close: a window that
                           ;; may hold unsaved text stays open.
                           (callable (close-request-fn w)
                                     [:pointer :pointer] :int :on-throw 1)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  ;; Without an application "quit" action, GTK's macOS
  ;; delegate lets a Quit AppleEvent (Dock -> Quit, logout, shutdown)
  ;; terminate the process at once -- measured: a dirty ./jonquil sent
  ;; one by pid exited 0 in ~250ms with no prompt. With it, the delegate
  ;; activates this action and cancels the terminate; the action closes
  ;; the window, so a dirty buffer gets the same prompt as the close
  ;; button (close-request-fn) and a clean one quits. It also takes GTK's
  ;; default Cmd+Q accelerator for app.quit on macOS.
  (let [quit-action (g/g-simple-action-new "quit" ffi/null)]
    (g/g-signal-connect-data quit-action "activate"
                             (callable (fn [_action _param _data] (g/gtk-window-close win) nil)
                                       [:pointer :pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-action-map-add-action app quit-action)))

(defn build
  "activate!'s close flags, in activate!'s order: confirmed-close?, and the self-test hooks suppress-close? and closing? (close-request-fn's docstring). Reads nothing from w. Returns w with :confirmed-close? :suppress-close? and :closing? added."
  [w]
  (let [confirmed-close? (atom false)
        ;; Self-test hooks only -- see close-request-fn's docstring.
        suppress-close? (atom false)
        closing? (atom false)]
    (assoc w :confirmed-close? confirmed-close? :suppress-close? suppress-close? :closing? closing?)))
