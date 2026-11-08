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

(defn- tab-label-of
  "A tab's name as its strip shows it, without the dirty mark, for
  a message's \"tab N\"; nil once it's closed"
  [{:keys [tabs]} id]
  (let [ts @tabs]
    (some (fn [[t text]] (when (= id (:id t)) text))
          (map vector ts (tab-names/labels (map (fn [t] {:path @(:path t) :dirty? false}) ts))))))

(defn- show-log!
  "Fills the pane from the log, with the tab names as the strip shows them."
  [{:keys [msgp messages*] :as ctx}]
  (messages-pane/show! msgp @messages* (fn [id] (tab-label-of ctx id))))

(defn- refresh-messages!
  "GTK thread: the unread count, and the panel itself while it's
  the one on screen (then everything in it is read)"
  [{:keys [right showing-panel unread-label messages*] :as ctx}]
  (when (= :messages (showing-panel right))
    (swap! messages* messages/mark-read)
    (show-log! ctx))
  (let [n (messages/unread-count @messages*)]
    (g/gtk-label-set-text unread-label (str "● " n " message" (when (not= 1 n) "s")))
    (g/gtk-widget-set-visible unread-label (if (pos? n) 1 0))))

(defn- refresh-reporting!
  "A refresh that throws says so on stderr, not on the GTK loop."
  [ctx]
  (try (refresh-messages! ctx)
       (catch :default e
         (binding [*out* *err*]
           (println "jonquil: messages refresh:" (ex-message e))))))

(defn- log-entry!
  "Logs from any thread. One refresh is queued at a time, so a burst of
  entries costs one GTK turn, not one each."
  [{:keys [messages* refresh-queued] :as ctx} entry]
  (messages/log! messages* entry)
  (when (compare-and-set! refresh-queued false true)
    (on-gui! (fn []
               (reset! refresh-queued false)
               (refresh-reporting! ctx)))))

(defn- unread-click!
  "A click on the status bar's unread count opens the Messages"
  [{:keys [set-status! panel-focus-fn]}]
  (try (panel-focus-fn :messages)
       (catch :default e
         (set-status! (str "; messages: " (ex-message e))))))

(defn- activate-entry!
  "Enter on a row: selects its tab, opens its plugin, or refreshes the rows."
  [{:keys [tabs select-tab! tab-ctx* plugin-step! settings-path* msgp] :as ctx} entry]
  (let [{:keys [tab-id plugin-id]} (:target entry)]
    (cond
      tab-id (if (some #(= tab-id (:id %)) @tabs)
               (select-tab! @tab-ctx* tab-id)
               (messages-pane/report! msgp "That tab is closed."))
      plugin-id (plugin-step! {:settings-path @settings-path* :step (fn [] []) :open? true})
      :else (show-log! ctx))))

(defn- set-handlers!
  "Wires the pane's row, filter and close handlers."
  [{:keys [msgp right hide-right-panel! active-tab] :as ctx}]
  (messages-pane/set-handlers!
   msgp {:on-activate (fn [entry] (activate-entry! ctx entry))
         :on-filter (fn [] (show-log! ctx))
         :on-close (fn [] (hide-right-panel! right :messages) (editor/focus! (:ed (active-tab))))}))

(defn- add-unread-click!
  "Adds the click gesture to the unread count, its callable built once."
  [{:keys [unread-label]} click-fn]
  (let [click (g/gtk-gesture-click-new)]
    (g/g-signal-connect-data click "pressed"
                             (callable (fn [_gesture _n _x _y _data]
                                         (click-fn)
                                         nil)
                                       [:pointer :int :double :double :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller unread-label click)))

(defn- panel-entry
  "The :messages panel's contribution: its root, focus and open fns."
  [{:keys [msgp right show-right-panel!] :as ctx}]
  (let [focus! (fn [] (g/gtk-widget-grab-focus (:list msgp)))]
    {:root (:root msgp)
     :focus! focus!
     :open! (fn []
              ;; never "nothing yet": an empty log says what fills it
              (show-right-panel! right :messages)
              (refresh-messages! ctx)
              (focus!))}))

(defn- install
  "The Messages part for one window: builds the pane and the log, wires the
  handlers, registers the log as the status log and adds the unread count's
  click, reading the tabs, the right-hand panels and the unread label from
  wctx."
  [{:keys [register-status-log!] :as wctx}]
  (let [msgp (messages-pane/make-pane)
        ctx (assoc wctx :msgp msgp :messages* (atom (messages/empty-log))
                   :refresh-queued (atom false))
        log-fn (fn [entry] (log-entry! ctx entry))
        click-fn (fn [] (unread-click! ctx))]
    (register-status-log! log-fn)
    (set-handlers! ctx)
    (add-unread-click! ctx click-fn)
    {:ui {:messages-pane msgp :messages* (:messages* ctx) :log!* log-fn :unread-click!* click-fn}
     :exports {:log-entry! log-fn}
     :panels {:messages (panel-entry ctx)}}))

(def part
  "The Messages part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :messages}))
