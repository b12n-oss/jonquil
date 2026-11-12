(ns jonquil.gtk.window.plugin-agent
  "The plugin agent the whole process shares: plugin-step! runs every plugin load and proposal step on one agent, prints its lines through plugin-console and hands the window the pending reviews, the plugin list's rows and the open history through plugin-reviews-changed, and plugin-history-shown names the plugin whose history panel is open. The plugins part sets the two hooks at start. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window unchanged."
  (:require [jonquil.plugin-list :as plugin-list]
            [jonquil.plugin-loader :as plugin-loader]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.plugin-review :as plugin-review]
            [jonquil.settings :as settings]))

(defonce plugin-history-shown
  ;; The id whose history panel is open, or nil. Set when
  ;; History is pressed, cleared by show-right-panel! for any other panel
  ;; and by hide-right-panel! :history, so it follows the panel; plugin-step!
  ;; reads it to rebuild that history off the GTK thread.
  (atom nil))

(defonce plugin-console
  ;; (fn [line]) printing one line on the window's console; the plugins
  ;; part's :plugins-start! sets it
  (atom (fn [line] (binding [*out* *err*] (println "jonquil:" line)))))

(defonce ^:private plugin-loads
  ;; Every plugin load (startup, Reload Plugins, a saved plugin
  ;; file) runs here, one at a time, so a load starts from what the last
  ;; one left; the state is the last step's console lines. It keeps going
  ;; after a throw, which is reported, so no later load is lost.
  (plugin-loader/load-agent
   (fn [e] (@plugin-console (str "loading plugins failed: " (or (ex-message e) (str e)))))))

(defonce plugin-reviews-changed
  ;; (fn [{:keys [reviews open? lines after done]}]) taking the
  ;; pending proposals' reviews after every plugin step; the plugins
  ;; part's :plugins-start! sets it to update the pending count and the
  ;; review panel. done, when given, is a promise it delivers once the
  ;; window has changed.
  (atom (fn [{:keys [done]}] (when done (deliver done true)))))

(defn- publish-reviews!
  "Builds the pending proposals' reviews, the plugin list's rows and the
  open history's version rows, off the GTK thread, and hands them with
  lines to plugin-reviews-changed. A throw is said on the plugin console
  and still delivers done. m is plugin-step!'s map. Returns nil."
  [{:keys [settings-path open? open-list? open-history? after done]} lines]
  (try
    (let [dir (plugin-loader/plugins-dir settings-path)
          reg @plugin-registry/live]
      (@plugin-reviews-changed
       {:reviews (plugin-review/pending-reviews {:plugins-dir dir :registry reg})
        :rows (plugin-list/rows {:plugins-dir dir :registry reg
                                 :disabled (:disabled (:plugins (settings/load-settings settings-path)))})
        :history (when-let [id @plugin-history-shown]
                   (assoc (plugin-list/version-rows dir id) :id id))
        :open? open? :open-list? open-list? :open-history? open-history?
        :lines lines :after after :done done}))
    (catch :default e
      (when done (deliver done true))
      (@plugin-console (str "reading the plugin proposals failed: " (or (ex-message e) (str e)))))))

(defn plugin-step!
  "Queues step (a no-arg fn answering console lines) on plugin-loads. Its
  lines go to the console; then, still off the GTK thread, the pending
  proposals' reviews are built (jonquil.plugin-review/pending-reviews over
  the settings file's plugins folder and the live registry) and handed to
  plugin-reviews-changed with open? (open the review panel), after (fn
  [lines] run once the window has changed) and done. So are
  the plugin list's rows (jonquil.plugin-list/rows, with the disabled ids
  from the settings file) and open-list? (open the list), and the
  open history's version rows (jonquil.plugin-list/version-rows for
  plugin-history-shown, with its :id) and open-history?. Returns the
  agent, whose state is the step's lines."
  [{:keys [step] :as m}]
  (send-off plugin-loads
            (fn [_]
              (let [lines (step)]
                (try (doseq [l lines] (@plugin-console l))
                     (catch :default e
                       (binding [*out* *err*] (println "jonquil: plugin console:" (ex-message e)))))
                (publish-reviews! m lines)
                lines))))
