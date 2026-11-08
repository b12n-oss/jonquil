(ns jonquil.gtk.parts.messages
  "The Messages panel as a window part (the command and the label are
  jonquil.parts.messages/spec). Moved out of jonquil.gtk.window: the pane,
  the log, the unread count's text and click, and the status log's
  registration. It answers its log fn as an export, which parts installed
  after it read."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.messages-pane :as messages-pane]
            [jonquil.messages :as messages]
            [jonquil.tabs :as tab-names]))

(defn- install
  "The Messages part for one window: builds the pane and the log, wires the
  handlers, registers the log as the status log and adds the unread count's
  click, reading the tabs, the right-hand panels and the unread label from
  wctx."
  [{:keys [right tabs active-tab showing-panel show-right-panel! hide-right-panel!
           set-status! unread-label register-status-log! plugin-step! settings-path*
           select-tab! tab-ctx* panel-focus-fn]}]
  (let [msgp (messages-pane/make-pane)
        messages* (atom (messages/empty-log))
        ;; A tab's name as its strip shows it, without the dirty mark, for
        ;; a message's "tab N"; nil once it's closed
        tab-label-of (fn [id]
                       (let [ts @tabs]
                         (some (fn [[t text]] (when (= id (:id t)) text))
                               (map vector ts (tab-names/labels (map (fn [t] {:path @(:path t) :dirty? false}) ts))))))
        ;; GTK thread: the unread count, and the panel itself while it's
        ;; the one on screen (then everything in it is read)
        refresh-messages! (fn []
                            (when (= :messages (showing-panel right))
                              (swap! messages* messages/mark-read)
                              (messages-pane/show! msgp @messages* tab-label-of))
                            (let [n (messages/unread-count @messages*)]
                              (g/gtk-label-set-text unread-label (str "● " n " message" (when (not= 1 n) "s")))
                              (g/gtk-widget-set-visible unread-label (if (pos? n) 1 0))))
        ;; one refresh queued at a time, so a burst of entries costs one
        ;; GTK turn, not one each
        messages-refresh-queued (atom false)
        log-entry! (fn [entry]
                     (messages/log! messages* entry)
                     (when (compare-and-set! messages-refresh-queued false true)
                       (on-gui! (fn []
                                  (reset! messages-refresh-queued false)
                                  (try (refresh-messages!)
                                       (catch :default e
                                         (binding [*out* *err*]
                                           (println "jonquil: messages refresh:" (ex-message e)))))))))
        focus! (fn [] (g/gtk-widget-grab-focus (:list msgp)))
        ;; a click on the status bar's unread count opens the Messages
        unread-click! (fn []
                        (try (panel-focus-fn :messages)
                             (catch :default e
                               (set-status! (str "; messages: " (ex-message e))))))]
    (register-status-log! log-entry!)
    (messages-pane/set-handlers!
     msgp {:on-activate (fn [entry]
                          (let [{:keys [tab-id plugin-id]} (:target entry)]
                            (cond
                              tab-id (if (some #(= tab-id (:id %)) @tabs)
                                       (select-tab! @tab-ctx* tab-id)
                                       (messages-pane/report! msgp "That tab is closed."))
                              plugin-id (plugin-step! {:settings-path @settings-path* :step (fn [] []) :open? true})
                              :else (messages-pane/show! msgp @messages* tab-label-of))))
           :on-filter (fn [] (messages-pane/show! msgp @messages* tab-label-of))
           :on-close (fn [] (hide-right-panel! right :messages) (editor/focus! (:ed (active-tab))))})
    (let [click (g/gtk-gesture-click-new)]
      (g/g-signal-connect-data click "pressed"
                               (callable (fn [_gesture _n _x _y _data]
                                           (unread-click!)
                                           nil)
                                         [:pointer :int :double :double :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT)
      (g/gtk-widget-add-controller unread-label click))
    {:ui {:messages-pane msgp :messages* messages* :log!* log-entry! :unread-click!* unread-click!}
     :exports {:log-entry! log-entry!}
     :panels {:messages {:root (:root msgp)
                         :focus! focus!
                         :open! (fn []
                                  (show-right-panel! right :messages)
                                  (refresh-messages!)
                                  (focus!))}}}))

(def part
  "The Messages part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :messages}))
