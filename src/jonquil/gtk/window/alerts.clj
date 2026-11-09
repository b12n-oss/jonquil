(ns jonquil.gtk.window.alerts
  "The window's alert dialogs: show-alert!, complete-alert!'s routing by each
  dialog's own identity, and the one alert-pending? guard that the close, tab
  close, trash and Time Form alerts share. Internal to the window, as
  jonquil.gtk.window.status is. Moved out of jonquil.gtk.window unchanged."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]))

;; Each pending alert dialog tracks its OWN on-choice fn, keyed by the
;; completion callback's own source_object (GAsyncReadyCallback's first
;; argument -- for gtk_alert_dialog_choose this is the GtkAlertDialog
;; itself), never a single "current dialog" atom. With a shared atom, a
;; second alert would overwrite which dialog the FIRST's real completion
;; uses: gtk_alert_dialog_choose_finish(wrong-dialog, first-result) fails
;; GTK's source-object check, returns -1 with NO GError, decodes as {:error
;; "no index and no GError"}, and close-choice!'s error branch treats that
;; as a dismissal, silently dropping the user's real Save/Don't Save
;; choice, while the WRONG dialog is released and the RIGHT one leaks (then
;; double-releases when it later completes).
(defonce ^:private alert-on-choice-by-dialog (atom {}))

;; Swappable only for the self-test (row-close-alert-routing): lets
;; complete-alert! be driven with synthetic, non-GTK "dialog" identities
;; without ever calling the real g_object_unref on something that isn't a
;; live GObject. Defaults to the real release.
(defonce alert-release! (atom g/g-object-unref))

;; Self-test observability only: counts real GtkAlertDialogs actually
;; built, so row-close-alert-pending-guard can assert the guard below
;; really did prevent a second one.
(defonce alert-dialog-count (atom 0))

;; Guards jonquil.gtk.window.quit's close-request-fn against opening a
;; SECOND alert while one is already pending: set true right before
;; gtk_alert_dialog_choose starts, cleared once THIS dialog's own on-choice
;; actually runs (success, Cancel, or dismissal all clear it, via
;; complete-alert!).
(defonce alert-pending? (atom false))

;; Each real alert's GCancellable, by its dialog: cancelling one closes the
;; dialog, and GTK completes it as a dismissal (complete-alert! below, with
;; {:error ...}, which every alert's choice handler treats as Cancel). The
;; self-test answers alerts in code, so it dismisses what's left after each
;; row (dismiss-pending-alerts!); nothing else cancels them.
(defonce alert-cancellables (atom {}))

(defn dismiss-pending-alerts!
  "Cancels every alert dialog still open, so GTK closes them."
  []
  (doseq [c (vals @alert-cancellables)] (g/g-cancellable-cancel c))
  nil)

(defn register-alert-choice!
  "Tracks on-choice under dialog's own identity: a raw pointer for a real
  GtkAlertDialog (show-alert!, below), or any comparable value in
  a self-test row driving complete-alert! directly."
  [dialog on-choice]
  (swap! alert-on-choice-by-dialog assoc dialog on-choice))

(defn complete-alert!
  "Routes ONE alert's async completion by its own identity (src). Looks
  up and removes src's own on-choice, calls it with the already-decoded
  result, and releases src exactly once, then clears alert-pending? so a
  later close-request can open a fresh alert. A src with no registration
  (already completed, or never registered) is a no-op -- idempotent
  against a stray re-invocation, so calling this twice with the SAME src
  never double-releases."
  [src result]
  (when-let [c (get @alert-cancellables src)]
    (swap! alert-cancellables dissoc src)
    (g/g-object-unref c))
  (when-let [on-choice (get @alert-on-choice-by-dialog src)]
    (swap! alert-on-choice-by-dialog dissoc src)
    (try (on-choice result)
         (finally (@alert-release! src) (reset! alert-pending? false)))))

(defonce ^:private alert-choose-cb
  (callable (fn [src res _data] (complete-alert! src (g/finish-choice src res)) nil)
            [:pointer :pointer :pointer] :void))

(defn show-alert!
  "Builds a fresh GtkAlertDialog (g_object_new_with_properties,
  0 properties -- gtk_alert_dialog_new is variadic and is never bound) for
  THIS attempt (the message needs the current file name, which can differ
  between attempts after a Save As), but reuses the ONE retained
  alert-choose-cb across every attempt. cancel and default are button
  indexes: the close alerts pass 2 and 0, the tree's trash alert 1 and 1."
  [win {:keys [message detail buttons]} {:keys [cancel default]} on-choice]
  (let [d (g/g-object-new-with-properties (g/gtk-alert-dialog-get-type) 0 ffi/null ffi/null)
        cancellable (g/g-cancellable-new)]
    (swap! alert-dialog-count inc)
    (register-alert-choice! d on-choice)
    (swap! alert-cancellables assoc d cancellable)
    (g/gtk-alert-dialog-set-message d message)
    (when detail (g/gtk-alert-dialog-set-detail d detail))
    (ffi/with-c-string-array [labels 4] (conj (vec buttons) nil)
      (g/gtk-alert-dialog-set-buttons d labels))
    (g/gtk-alert-dialog-set-cancel-button d cancel)
    (g/gtk-alert-dialog-set-default-button d default)
    (g/gtk-alert-dialog-choose d win cancellable alert-choose-cb ffi/null)))

(def CLOSE-ALERT {:cancel 2 :default 0})
(def TRASH-ALERT {:cancel 1 :default 1})
