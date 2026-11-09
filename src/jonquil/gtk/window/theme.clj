(ns jonquil.gtk.window.theme
  "Theme and zoom for the window: the one shared GtkCssProvider's code CSS, applying a theme to every tab, the console and GTK's colour scheme, the auto appearance poll, and cycling and saving the choice. build makes activate!'s theme and zoom bindings. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window unchanged; build holds activate!'s own bindings in their order."
  (:require [jolt.ffi :as ffi]
            [jonquil.commands :as commands]
            [jonquil.gtk.appearance :as appearance]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.gutter :as gutter]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.settings :as settings]
            [jonquil.theme :as theme]))

(defn- reload-code-css!
  "Loads theme t's CSS (jonquil.theme/code-css) at font size px into the
  one shared GtkCssProvider. The self-test's :code-css* reads the CSS back
  from the provider itself (g/css-provider-string), so nothing keeps a copy."
  [provider t px]
  (g/gtk-css-provider-load-from-string provider (theme/code-css t px)))

(defn- do-zoom!
  "Cmd/Ctrl+=/-/0: the next font size from commands/zoom, reloaded
  into the one shared GtkCssProvider (added to the display once, in
  activate!) so both views resize live. Reloads under the CURRENT theme
  (theme-name), not a fixed one, so a zoom never drops back to the
  default theme's colours."
  [provider zoom-px theme-name direction]
  (let [px (commands/zoom @zoom-px direction)]
    (reset! zoom-px px)
    (reload-code-css! provider (theme/theme @theme-name) px)))

(defn- apply-window-theme!
  "Applies theme k to the editor, console, find bar, the shared CSS
  provider (at the current @zoom-px) and GtkSettings' colour-scheme, and
  resets theme-name to k. Only ever writes tag properties, the CSS
  provider and the GtkSettings property -- never buffer text, marks, the
  selection or undo history.

  gtk-application-prefer-dark-theme is deprecated (GTK 4.20+, a
  g_warning from 4.23.1); this sets GtkSettings' replacement,
  gtk-interface-color-scheme, instead (g/set-color-scheme!, measured
  live to darken the chrome exactly as the old boolean did; see ffi.clj)."
  [{:keys [tabs cv provider zoom-px theme-name]} k]
  (let [t (theme/theme k)]
    ;; Every tab's editor and find bar, not just one.
    (doseq [{:keys [ed fb]} @tabs]
      (editor/apply-theme! ed t)
      (find-bar/apply-theme! fb t)
      (some-> (get-in ed [:changed :gutter]) (gutter/apply-theme! t)))
    (cview/apply-theme! cv t)
    (reload-code-css! provider t @zoom-px)
    (g/set-color-scheme! (g/gtk-settings-get-default) (:dark? t))
    (reset! theme-name k))
  nil)

(defn poll-tick!
  "One tick of the `auto` appearance poll, returned as the GLib source
  result: 0 drops the timer (the choice is no longer :auto), 1 keeps it.
  It never touches poll-armed?: only the real GLib callback may clear that
  (see poll-cb in build), because only its return of 0 drops the timer.
  A closing window does nothing, since the app is about to quit and the
  self-test's suppressed close mustn't stop the poll. Otherwise resolves
  the choice against the system and applies it only when it differs from
  the theme being shown. A nil from the system fn means the read failed
  (inside the poll on macOS it can mean nothing else), so the palette is
  left alone and the tick returns 1; startup and apply-choice! still count
  nil as light. Never saves, because the saved value is :auto."
  [{:keys [theme-name theme-choice system-dark-fn* closing?] :as theme-ctx}]
  (cond
    (not= :auto @theme-choice) 0
    @closing? 1
    :else (let [dark? ((deref system-dark-fn*))]
            (when-not (nil? dark?)
              (let [r (theme/resolve-choice :auto dark?)]
                (when (not= r @theme-name)
                  (apply-window-theme! theme-ctx r))))
            1)))

(defn- apply-choice!
  "Sets the theme choice to k (a jonquil.theme choice key, possibly :auto),
  applies the theme it resolves to (:auto asks the system through
  system-dark-fn*, nil counting as light) and returns that resolved key.
  Arms the 1 second appearance poll when k is :auto on macOS; poll-armed?
  keeps it to one timer however often :auto is chosen."
  [{:keys [theme-choice system-dark-fn* poll-armed? poll-cb arm-count] :as theme-ctx} k]
  (reset! theme-choice k)
  (let [r (theme/resolve-choice k (when (= :auto k) ((deref system-dark-fn*))))]
    (apply-window-theme! theme-ctx r)
    (when (and (= :auto k)
               (= "Mac OS X" (System/getProperty "os.name"))
               (compare-and-set! poll-armed? false true))
      ;; If arming throws there is no timer, so the flag must not stay
      ;; true. The throw continues to the caller.
      (try
        (g/g-timeout-add 1000 poll-cb ffi/null)
        (catch Throwable e
          (reset! poll-armed? false)
          (throw e)))
      (swap! arm-count inc))
    r))

(defn load-and-apply-theme!
  "jonquil.settings/load-settings against path, applied with
  apply-window-theme! -- a missing file is silently :light (load-settings'
  own contract), a present-but-broken one still resolves to a real theme,
  with a :warning appended to the console as :system once. Used both by
  activate! at startup and as the :load-settings!* self-test hook, so a row
  can drive the exact same startup path against a synthetic path."
  [{:keys [cv] :as theme-ctx} path]
  (let [{:keys [theme warning]} (settings/load-settings path)]
    (apply-choice! theme-ctx theme)
    (when warning
      (cview/append! cv :system (str "; settings: " warning "\n")))))

(defn- do-cycle-theme!
  "The chord/:cycle-theme!* action. Advances to the next theme
  (theme/next-theme over the choice, wrapping) and applies it, then saves {:theme k} to
  settings-path -- read at CALL time (not captured once), so a self-test
  row can repoint it via :settings-path!* right before calling this. A
  save error is reported on the console as :system, but the theme switch
  itself already happened and is kept regardless. While a program is
  running, the status label is left alone --
  overwriting a live Running…/Waiting for input would make the status
  lie, so only the idle case gets 'Theme: <name>'."
  [{:keys [cv theme-choice settings-path status-label evaluating?] :as theme-ctx}]
  (let [k (theme/next-theme @theme-choice)
        r (apply-choice! theme-ctx k)]
    (when-let [err (settings/save-settings! @settings-path {:theme k})]
      (cview/append! cv :system (str "; settings: " err "\n")))
    (when-not (evaluating?)
      (win-status/set-status! status-label (if (= :auto k)
                                             (str "Theme: auto (" (name r) ")")
                                             (str "Theme: " (name k)))))))

(defn build
  "activate!'s theme and zoom bindings, in activate!'s order: the one shared GtkCssProvider, the zoom and theme atoms, zoom-fn, theme-ctx with its appearance poll callable, and the set and cycle fns. w holds what they read: :tabs :cv :settings-path* :status-label :evaluating-fn :closing?. Returns w with :provider :theme-name :theme-choice :system-dark-fn* :poll-armed? :arm-count :zoom-fn :theme-ctx :set-theme-fn and :cycle-theme-fn added; zoom-px and poll-cb are reached through theme-ctx."
  [{:keys [tabs cv settings-path* status-label evaluating-fn closing?] :as w}]
  (let [provider (g/gtk-css-provider-new)
        zoom-px (atom commands/DEFAULT-FONT-PX)
        ;; The active theme's name, kept next to zoom-px; its initial
        ;; value is loaded from settings.
        theme-name (atom theme/default-theme)
        ;; Auto theme: what the user picked (:auto or a theme key), the
        ;; function that asks the system (repointed by a self-test hook),
        ;; whether the appearance poll timer is armed, and how many times
        ;; one was (self-test only).
        theme-choice (atom theme/default-theme)
        system-dark-fn* (atom appearance/system-dark?)
        poll-armed? (atom false)
        arm-count (atom 0)
        zoom-fn (fn [direction] (do-zoom! provider zoom-px theme-name direction))
        theme-ctx {:tabs tabs :cv cv :provider provider :zoom-px zoom-px :theme-name theme-name
                   :settings-path settings-path* :status-label status-label
                   :evaluating? evaluating-fn :theme-choice theme-choice
                   :system-dark-fn* system-dark-fn* :poll-armed? poll-armed?
                   :arm-count arm-count :closing? closing?}
        ;; Built once, as the inspector's note-clear timer is, because
        ;; callbacks keeps every callable for the life of the process.
        ;; Only this callback clears poll-armed?, when its tick returns 0
        ;; and GLib drops the timer.
        poll-cb (callable (fn [_data]
                            (let [r (poll-tick! theme-ctx)]
                              (when (zero? r) (reset! poll-armed? false))
                              r))
                          [:pointer] :int :on-throw 1)
        theme-ctx (assoc theme-ctx :poll-cb poll-cb)
        set-theme-fn (fn [k] (apply-choice! theme-ctx k))
        ;; The chord's/:cycle-theme!*'s action.
        cycle-theme-fn (fn [] (do-cycle-theme! theme-ctx))]
    (assoc w :provider provider :theme-name theme-name :theme-choice theme-choice
           :system-dark-fn* system-dark-fn* :poll-armed? poll-armed? :arm-count arm-count
           :zoom-fn zoom-fn :theme-ctx theme-ctx :set-theme-fn set-theme-fn
           :cycle-theme-fn cycle-theme-fn)))
