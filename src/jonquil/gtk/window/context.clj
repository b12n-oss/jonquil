(ns jonquil.gtk.window.context
  "The window's command context: the fns its keys, toolbar and menus call (Open, Save, the inspector's, the focus toggle, the console's clears, Find and Replace). build-key-fns makes activate!'s. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window's activate! unchanged; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.inspector :as win-inspector]
            [jonquil.gtk.window.keys :as win-keys]
            [jonquil.gtk.window.tabs :as win-tabs]))

(defn build-key-fns
  "activate!'s window command fns, in activate!'s order: Open, Save and
  Save As for the active tab, the inspector's context with Inspect Last
  and Inspect Form, the inspector's Close, the focus toggle, the
  console's two clears, and Find and Replace. w holds what they read:
  :cv :win :active-tab :ev* :sel* :status-label, the inspector's :right
  :pane :inspector-state :inspecting? :inspect-queued
  :inspector-last-args :on-view, and :tab-ctx* (activate!'s *tab-ctx,
  read when an Open result opens a file). Returns w with :open-fn
  :save-fn :save-as-fn :inspect-last-ctx :inspect-last-fn
  :inspect-form-fn :close-inspector-fn :toggle-focus-fn
  :clear-console-fn :clear-last-fn :open-find-fn and :open-replace-fn
  added."
  [{:keys [cv win active-tab ev* sel* status-label right pane inspector-state inspecting? inspect-queued
           inspector-last-args on-view] *tab-ctx :tab-ctx* :as w}]
  (let [open-fn (fn [] (win-files/do-open! cv win (fn [p] (win-tabs/open-file! @*tab-ctx p))))
        save-fn (fn [] (let [t (active-tab)] (win-files/do-save! (:ed t) cv win (:path t))))
        save-as-fn (fn [] (let [t (active-tab)] (win-files/do-save-as! (:ed t) cv win (:path t))))
        inspect-last-ctx {:ev* ev* :right right :pane pane :inspector-state inspector-state
                          :inspecting? inspecting? :inspector-last-args inspector-last-args
                          :on-view on-view :status-label status-label :start [:start {}]}
        inspect-last-fn (fn [] (win-inspector/inspect-last! inspect-last-ctx))
        inspect-form-fn (fn [] (win-inspector/inspect-form! {:tab (active-tab) :cv cv :ev* ev* :sel* sel* :status-label status-label
                                                             :inspecting? inspecting? :inspect-queued inspect-queued
                                                             :inspect-last-fn inspect-last-fn}))
        close-inspector-fn (fn [] (win-inspector/close-inspector-panel! right (:ed (active-tab))))
        toggle-focus-fn (fn [] (win-keys/do-toggle-focus! win (:ed (active-tab)) cv))
        clear-console-fn (fn [] (win-keys/do-clear-console! cv))
        clear-last-fn (fn [] (win-keys/do-clear-last-output! cv))
        open-find-fn (fn [] (find-bar/open! (:fb (active-tab)) :find))
        open-replace-fn (fn [] (find-bar/open! (:fb (active-tab)) :replace))]
    (assoc w :open-fn open-fn :save-fn save-fn :save-as-fn save-as-fn :inspect-last-ctx inspect-last-ctx
           :inspect-last-fn inspect-last-fn :inspect-form-fn inspect-form-fn :close-inspector-fn close-inspector-fn
           :toggle-focus-fn toggle-focus-fn :clear-console-fn clear-console-fn :clear-last-fn clear-last-fn
           :open-find-fn open-find-fn :open-replace-fn open-replace-fn)))
