(ns jonquil.gtk.window.session
  "The window's tab list as the next launch reopens it: whether reopening is
  on, the snapshot of the open tabs, saving it to sessions.edn, and saving it
  as it changes (session-changed!). ctx is activate!'s tab-ctx. Internal to
  the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window
  unchanged."
  (:require [jonquil.gtk.console-view :as cview]
            [jonquil.last-session :as last-session]
            [jonquil.settings :as settings]))

(defn reopen-tabs?
  "False only when the settings file holds :reopen-tabs? false."
  [settings-path]
  (not (false? (:reopen-tabs? (settings/load-settings settings-path)))))

(defn session-snapshot
  "{:root :tabs :active} for sessions.edn: the tree's root, every tab's
  path in tab order (Untitled ones left out), and the active tab's path."
  [{:keys [tabs tree-pane active-tab]}]
  {:root (:root @(:state tree-pane))
   :tabs (vec (keep (fn [t] @(:path t)) @tabs))
   :active (some-> (active-tab) :path deref)})

(defn save-session!
  "Remembers the open tabs for the tree's root in sessions.edn. nil, or an
  error string when saving failed; does nothing when :reopen-tabs? is off."
  [{:keys [settings-path*] :as ctx}]
  (when (reopen-tabs? @settings-path*)
    (let [path (last-session/sessions-path @settings-path*)]
      (last-session/save-sessions!
       path (last-session/remember (:sessions (last-session/load-sessions path)) (session-snapshot ctx))))))

(defn session-changed!
  "Saves the tab list as it changes, not only at quit, so a crash or a
  force quit keeps it. Does nothing until :session-live? is set, which
  startup! does once the launch's own tabs are open: before that the
  first Untitled tab would overwrite the list it's about to reopen. Writes
  only when the snapshot differs from the last one written (or the state
  startup ended in), so a label refresh for a dirty mark costs no I/O. With
  :reopen-tabs? off it does nothing and records nothing, so turning it back
  on saves at the next change. A failure is said on the console
  once, until a save works again. ctx without :session-live? (a partial
  map) does nothing."
  [{:keys [session-live? session-last session-error cv] :as ctx}]
  (when (and session-live? @session-live? (reopen-tabs? @(:settings-path* ctx)))
    (let [snap (session-snapshot ctx)]
      (when (not= snap @session-last)
        (let [err (try (save-session! ctx) (catch :default e (ex-message e)))]
          (if err
            (when (not= err @session-error)
              (reset! session-error err)
              (cview/append! cv :system (str "; sessions: " err "\n")))
            (do (reset! session-error nil)
                (reset! session-last snap))))))))
