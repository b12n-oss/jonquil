(ns jonquil.gtk.window.status-bar
  "The window's status bar: the status text, the key hints and their toggle, the agent's pending count, the Messages unread count, the dialect, the Parinfer label and Ln/Col, in one row under the window's panes. build makes activate!'s status bar labels and place! fills the row. build-parinfer makes the window's Parinfer mode, the label's update, the hook's status line and the Parinfer command. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window unchanged; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [jonquil.commands :as commands]
            [jonquil.dialect :as dialect]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.settings :as settings]))

(def ^:private STATUS-MARGIN
  ;; px at each end of the status bar: GNOME's usual 6-12 px gutter, the
  ;; status box's own child spacing (6) plus a little.
  8)

(defn build
  "activate!'s status bar labels, in activate!'s order: the status text,
  Ln/Col, the key hints label with hints-on?, the Parinfer label, the
  pending count, the dialect label, then the Messages unread count, and
  hints-toggle-fn, Key hints' palette command. w holds what they read:
  :sel* (the dialect label's first text). Returns w with :status-label
  :line-col-label :hint-label :hints-on? :parinfer-label :pending-label
  :dialect-label :unread-label and :hints-toggle-fn added."
  [{:keys [sel*] :as w}]
  (let [status-label (g/gtk-label-new "Ready")
        line-col-label (g/gtk-label-new "Ln 1, Col 1")
        ;; LM6: what keys do at the caret, dim, hidden while there's nothing
        hint-label (doto (g/gtk-label-new "")
                     (g/gtk-widget-add-css-class "dim-label")
                     ;; never wider than this, so the status bar doesn't jump
                     (g/gtk-label-set-ellipsize g/PANGO-ELLIPSIZE-END)
                     (g/gtk-label-set-max-width-chars 44))
        hints-on? (atom true)
        parinfer-label (g/gtk-label-new "")
        ;; How many agent proposals are pending, hidden at zero
        pending-label (g/gtk-label-new "")
        ;; The window's dialect (and runtime, when it isn't the
        ;; dialect's default), between the status text and Ln/Col.
        dialect-label (g/gtk-label-new (dialect/label @sel*))
        ;; The Messages panel's unread count (LM8), shown in the status
        ;; bar while it's above zero; the Messages part fills it
        unread-label (g/gtk-label-new "")
        hints-toggle-fn (fn []
                          (let [on? (swap! hints-on? not)]
                            (when-not on? (g/gtk-widget-set-visible hint-label 0))
                            (win-status/set-status! status-label (if on?
                                                                   "; Key hints on: the status bar names the keys for the symbol at the caret."
                                                                   "; Key hints off."))))]
    (assoc w :status-label status-label :line-col-label line-col-label :hint-label hint-label
           :hints-on? hints-on? :parinfer-label parinfer-label :pending-label pending-label
           :dialect-label dialect-label :unread-label unread-label :hints-toggle-fn hints-toggle-fn)))

(defn build-parinfer
  "activate!'s Parinfer bindings, in activate!'s order: the window's one
  mode (from settings.edn, else :off), the label that follows the active
  tab's effective mode, the hook's one status line and the command's fn.
  w holds what they read: :settings-path* :active-tab :parinfer-label
  :evaluating-fn :status-label and :cv. Returns w with :parinfer-mode*
  :update-parinfer-label! :parinfer-status-fn and :parinfer-fn added."
  [{:keys [settings-path* active-tab parinfer-label evaluating-fn status-label cv] :as w}]
  (let [;; Parinfer: the window's one mode, read by every tab's editor
        ;; hook; :off unless settings.edn set one.
        parinfer-mode* (atom (or (:parinfer (settings/load-settings @settings-path*)) :off))
        ;; The active tab's project may override the global either way
        ;; (.jonquil.edn :parinfer), so the label follows the ACTIVE tab's
        ;; effective mode and says (project) when the override decides.
        active-parinfer-override (fn []
                                   (when-let [t (active-tab)]
                                     (:parinfer (some-> (:project t) deref))))
        update-parinfer-label! (fn []
                                 (let [ov (active-parinfer-override)]
                                   (if-let [t (commands/parinfer-status-text
                                               (commands/parinfer-effective ov @parinfer-mode*)
                                               (some? ov)
                                               @parinfer-mode*)]
                                     (do (g/gtk-label-set-text parinfer-label t)
                                         (g/gtk-widget-set-visible parinfer-label 1))
                                     (g/gtk-widget-set-visible parinfer-label 0))))
        ;; The hook's one status line: a skipped long form names itself,
        ;; and the next adjusted edit clears it back to Ready.
        parinfer-skip-shown* (atom false)
        parinfer-status-fn (fn [msg]
                             (if msg
                               (do (reset! parinfer-skip-shown* true)
                                   (when-not (evaluating-fn)
                                     (win-status/set-status! status-label
                                                             (str (or (commands/parinfer-label-text @parinfer-mode*)
                                                                      "Parinfer")
                                                                  " (" msg ")"))))
                               (when (and (compare-and-set! parinfer-skip-shown* true false)
                                          (not (evaluating-fn)))
                                 (win-status/set-status! status-label "Ready"))))
        parinfer-fn (fn [choice]
                      (let [k (commands/parinfer-step @parinfer-mode* choice)]
                        (reset! parinfer-mode* k)
                        ;; A cleared skip note mustn't later stomp a newer
                        ;; status.
                        (reset! parinfer-skip-shown* false)
                        (update-parinfer-label!)
                        (when-let [err (settings/save-settings! @settings-path* {:parinfer k})]
                          (cview/append! cv :system (str "; settings: " err "\n")))
                        ;; same restraint as the theme: a live Running…
                        ;; status isn't overwritten. A pinned active tab
                        ;; names its project's choice, since the command
                        ;; changed only the global.
                        (when-not (evaluating-fn)
                          (win-status/set-status! status-label
                                                  (str "Parinfer: " (commands/parinfer-mode-word k)
                                                       (commands/parinfer-pinned-note
                                                        k (active-parinfer-override)))))))]
    (assoc w :parinfer-mode* parinfer-mode* :update-parinfer-label! update-parinfer-label!
           :parinfer-status-fn parinfer-status-fn :parinfer-fn parinfer-fn)))

(defn place!
  "activate!'s status bar placement, in activate!'s order: the labels in
  one horizontal box, the hint, pending and unread labels hidden until
  they have something to say, the Parinfer label filled, a margin at each
  end, and the box appended to vbox, the window's column. m holds :vbox,
  the labels build made and :update-parinfer-label! from build-parinfer."
  [{:keys [vbox status-label hint-label pending-label unread-label dialect-label parinfer-label line-col-label
           update-parinfer-label!]}]
  (let [;; The status bar is a horizontal box: the status label on the
        ;; left (hexpand, so it claims the box's spare width) and the
        ;; Ln/Col label on the right.
        status-box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)]
    (g/gtk-widget-set-hexpand status-label 1)
    (g/gtk-box-append status-box status-label)
    (g/gtk-box-append status-box hint-label)
    (g/gtk-widget-set-visible hint-label 0)
    (g/gtk-box-append status-box pending-label)
    (g/gtk-widget-set-visible pending-label 0)
    (g/gtk-box-append status-box unread-label)
    (g/gtk-widget-set-visible unread-label 0)
    (g/gtk-box-append status-box dialect-label)
    (g/gtk-box-append status-box parinfer-label)
    (update-parinfer-label!)
    (g/gtk-box-append status-box line-col-label)
    ;; Room at both ends, so "Ln N, Col N" doesn't touch the window's right
    ;; edge and the left matches it.
    (g/gtk-widget-set-margin-start status-box STATUS-MARGIN)
    (g/gtk-widget-set-margin-end status-box STATUS-MARGIN)
    (g/gtk-box-append vbox status-box)))
