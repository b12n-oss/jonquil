(ns jonquil.gtk.window.startup
  "The window's start. connect-quit! makes activate!'s close-request connect and quit action with the closures that read activate!'s forward atoms. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window's activate! unchanged; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [jonquil.gtk.window.quit :as win-quit]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.parts :as parts]))

(defn connect-quit!
  "activate!'s close-request connect and quit action, through jonquil.gtk.window.quit's connect!, with close-request-fn's map built as activate! built it: :select! and :save-session! read *tab-ctx when they run, and :save-session! runs the parts' :on-quit hooks first. Called once per window; activate! reads nothing it returns. m holds :tabs :cv :win :app :confirmed-close? :suppress-close? :closing? :popovers :ev* :part-contribution :part-error! and :tab-ctx* (the *tab-ctx atom)."
  [{:keys [tabs cv win app confirmed-close? suppress-close? closing? popovers ev* part-contribution part-error!]
    *tab-ctx :tab-ctx*}]
  (win-quit/connect! {:tabs tabs :select! (fn [t] (win-tabs/select-tab! @*tab-ctx (:id t)))
                      :cv cv :win win :app app
                      :confirmed-close? confirmed-close?
                      :suppress-close? suppress-close? :closing? closing?
                      :popovers popovers :ev* ev*
                      ;; Live saving stops first: nothing after the
                      ;; quit's own save may write a shorter list.
                      ;; A quit before startup's reopen is done
                      ;; stops it and saves nothing, since the
                      ;; tabs open are only part of the list.
                      :save-session! (fn [] (let [c @*tab-ctx
                                                  restoring? (some-> c :session-restoring? deref)]
                                              (parts/run-hooks! part-contribution :on-quit [] part-error!)
                                              (some-> c :session-live? (reset! false))
                                              (some-> c :session-restoring? (reset! false))
                                              (when-not restoring? (win-session/save-session! c))))}))
