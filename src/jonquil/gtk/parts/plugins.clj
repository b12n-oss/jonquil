(ns jonquil.gtk.parts.plugins
  "The plugin panels' behaviour as a window part (the commands are
  jonquil.parts.plugins/spec): the review, list and history panels' steps and
  handlers, the plugin startup, loading and Reload Plugins, and the window's
  ui hooks that drive them. Moved out of jonquil.gtk.window; the three panes
  and the pending label stay in the window and come in wctx."
  (:require [clojure.string :as str]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.plugin-history-pane :as plugin-history-pane]
            [jonquil.gtk.plugin-list-pane :as plugin-list-pane]
            [jonquil.gtk.review-pane :as review-pane]
            [jonquil.mcp.window :as mcp-window]
            [jonquil.plugin-hooks :as plugin-hooks]
            [jonquil.plugin-loader :as plugin-loader]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.settings :as settings]
            [jonquil.tabs :as tabs]))

(defn- load-step
  "The step of a plugin load: jonquil.plugin-loader/load-step! over the
  live registry, with the plugins folder and disabled ids from the settings
  file, all files or just only. plugins? false (--no-plugins) makes it
  refuse; nil runs it."
  ([settings-path only] (load-step settings-path only nil))
  ([settings-path only plugins?]
   (fn [] (plugin-loader/load-step! plugin-registry/live
                                    {:settings-path settings-path :os (System/getProperty "os.name") :only only
                                     :plugins? plugins?}))))

(defn- plugin-load!
  "Queues a plugin load (load-step) through plugin-step!. Returns the
  agent, for a caller that wants to wait."
  [{:keys [plugin-step!]} {:keys [settings-path only plugins?]}]
  (plugin-step! {:settings-path settings-path :step (load-step settings-path only plugins?)}))

(defn- plugin-step-now-fn
  "plugin-step!, waited for up to 10 s, and for the window to change after
  it: the step's lines, or a line saying it didn't finish. For the
  self-test hooks and an MCP propose, called off the GTK thread."
  [{:keys [plugin-step!]}]
  (fn [opts]
    (let [done (promise)
          a (plugin-step! (assoc opts :done done))]
      (if (and (await-for 10000 a) (deref done 10000 false))
        @a
        ["the plugin step didn't finish in 10 s"]))))

(defn- sync-tab!
  "Brings one tab on a plugin file in step with the file on disk."
  [{:keys [cv refresh-labels!]} t]
  (let [path @(:path t)
        ed (:ed t)
        disk (try (slurp path) (catch :default _ nil))
        ;; NUL-bearing disk text must never reach
        ;; set-text! or rebase-saved! (either would
        ;; truncate or leave the tab permanently dirty), so
        ;; it gets disk-sync's "missing" case, with one line.
        nul (when disk (tabs/nul-line disk))
        disk (when-not nul disk)]
    (when nul
      (cview/append! cv :system (str "; " path " changed on disk but now holds a NUL byte, so this tab keeps its text.\n")))
    (case (tabs/disk-sync {:saved @(:saved-text ed) :disk disk :dirty? (editor/dirty? ed)
                           :buffer (editor/editor-text ed)})
      :reload (do (editor/reload-text! ed disk)
                  (cview/append! cv :system (str "; " path " changed on disk, so this tab was reloaded.\n"))
                  (refresh-labels!))
      :warn (do (editor/rebase-saved! ed disk)
                (cview/append! cv :system (str "; " path " changed on disk. This tab keeps your unsaved edits, and saving it will write over the version on disk.\n"))
                (refresh-labels!))
      :rebase (do (editor/rebase-saved! ed disk) (refresh-labels!))
      nil)))

(defn- sync-plugin-tabs!
  "After a plugin step, each tab on a plugin file whose
  file now holds other text than the tab last saved follows it: a
  clean tab reloads as one undo step, keeping its caret's offset, and a dirty one
  keeps its edits, is warned once on the console and takes the disk
  text as its saved baseline. GTK-thread only."
  [{:keys [settings-path* tabs cv] :as ctx}]
  (let [dir (plugin-loader/plugins-dir @settings-path*)]
    ;; a throw here is one console line and never
    ;; skips the rest of the step's window update
    (try
      (doseq [t @tabs
              :let [path @(:path t)]
              :when (and path (plugin-loader/plugin-file-or-target? dir path))]
        (sync-tab! ctx t))
      (catch :default e
        (cview/append! cv :error (str "; keeping plugin tabs in step failed: " (or (ex-message e) (str e)) "\n"))))))

(defn- refuse-superseded-accept!
  "The shown proposal was replaced while shown, so this click was aimed at the
  old one; refuse it once and say so."
  [{rp :review-pane :keys [review-state]} [id from to]]
  (swap! review-state dissoc :swapped)
  (review-pane/report! rp (str "plugin " id ": v" to " replaced v" from
                               " while it was shown, so it wasn't accepted. Read v" to
                               ", then press Accept again."))
  nil)

(defn- review-after
  "The step's :after: every line on the panel's footer, since a refused
  Accept's reasons come before its last, and the last on the status bar."
  [{rp :review-pane :keys [status-label]}]
  (fn [lines]
    (when-let [l (last lines)]
      (when (pos? (g/gtk-widget-get-visible (:root rp)))
        (review-pane/report! rp (str/join "\n" lines)))
      (g/gtk-label-set-text status-label (str "; " l)))))

(defn- review-step
  "The plugin-step! opts for Accept or Reject of the proposal the panel shows
  (nil when none is), its lines put on the panel's footer and its last on the
  status bar once the window has changed. GTK-thread only: it reads the note."
  [{rp :review-pane :keys [review-state settings-path* plugins-on*] :as ctx} kind]
  (let [{:keys [reviews index swapped]} @review-state]
    (if (and (= :accept kind) swapped)
      (refuse-superseded-accept! ctx swapped)
      (when-let [{:keys [id n]} (get reviews index)]
        (let [path @settings-path*
              note (review-pane/note-text rp)]
          {:settings-path path
           :step (case kind
                   :accept #(plugin-loader/accept-step!
                             plugin-registry/live
                             {:settings-path path :os (System/getProperty "os.name") :id id :n n
                              :plugins? @plugins-on*})
                   :reject #(plugin-loader/reject-step! {:settings-path path :id id :n n :note note}))
           :after (review-after ctx)})))))

(defn- review-step-now
  "The same, waited for, for the self-test hooks (off the GTK thread)."
  [{:keys [plugin-step-now!] :as ctx} kind]
  (let [p (promise)]
    (on-gui! #(deliver p (review-step ctx kind)))
    (if-let [o (deref p 10000 nil)] (plugin-step-now! o) [])))

(defn- toggle-step
  "The plugin-step! opts for Enable (enabled? true) or
  Disable of the list's selected plugin, its first line put on the
  list's footer and the status bar once the window has changed;
  nil, with the footer saying so, when no row is selected.
  GTK-thread only."
  [{lp :plugin-list-pane :keys [settings-path* plugins-on* status-label]} enabled?]
  (let [row (plugin-list-pane/selected-row lp)]
    (cond
      (nil? row) (do (plugin-list-pane/report! lp "No plugin is selected.") nil)
      ;; whether it's already that way is the step's to
      ;; say, since the row shown may be a step behind
      :else
      (let [path @settings-path*]
        {:settings-path path
         :step #(plugin-loader/set-enabled-step!
                 plugin-registry/live
                 {:settings-path path :os (System/getProperty "os.name")
                  :id (:id row) :enabled? enabled? :plugins? @plugins-on*})
         :after (fn [lines]
                  (when-let [l (first lines)]
                    (plugin-list-pane/report! lp l)
                    (g/gtk-label-set-text status-label (str "; " l))))}))))

(defn- toggle-step-now
  [{lp :plugin-list-pane :keys [plugin-step-now!] :as ctx} id enabled?]
  (let [p (promise)]
    (on-gui! #(deliver p (when (plugin-list-pane/select-id! lp id) (toggle-step ctx enabled?))))
    (if-let [o (deref p 10000 nil)] (plugin-step-now! o) [])))

(defn- history-step
  "History on the list's selected plugin: a plugin-step! opts, or nil with
  the footer saying why. GTK-thread only: it reads the selection."
  [{lp :plugin-list-pane hp :plugin-history-pane :keys [settings-path* plugin-history-shown]}]
  (if-let [{:keys [id]} (plugin-list-pane/selected-row lp)]
    (do (reset! plugin-history-shown id)
        (plugin-history-pane/set-reason! hp "")
        {:settings-path @settings-path* :step (fn [] []) :open-history? true})
    (do (plugin-list-pane/report! lp "No plugin is selected.") nil)))

(defn- history-back-step
  "Back on the history: plugin-step! opts that open the list."
  [{:keys [settings-path* plugin-history-shown]}]
  (reset! plugin-history-shown nil)
  {:settings-path @settings-path* :step (fn [] []) :open-list? true})

(defn- revert-step
  "Revert on the history's selected version: plugin-step! opts, or nil with
  the footer saying why. Its lines go to the history's footer and its last to
  the status bar once the window has changed. GTK-thread only: it reads the
  selection and reason."
  [{hp :plugin-history-pane :keys [settings-path* plugins-on* plugin-history-shown status-label]}]
  (let [id @plugin-history-shown
        row (plugin-history-pane/selected-row hp)]
    (if (or (nil? id) (nil? row))
      (do (plugin-history-pane/report! hp "No version is selected.") nil)
      ;; the reason is read here, at the click, never on
      ;; the agent
      (let [path @settings-path*
            reason (plugin-history-pane/reason-text hp)]
        {:settings-path path
         :step #(plugin-loader/revert-step!
                 plugin-registry/live
                 {:settings-path path :os (System/getProperty "os.name")
                  :id id :n (:n row) :reason reason :plugins? @plugins-on*})
         :after (fn [lines]
                  (when-let [l (last lines)]
                    (plugin-history-pane/report! hp (str/join "\n" lines))
                    (g/gtk-label-set-text status-label (str "; " l))))}))))

(defn- open-step
  "Open shows the selected plugin's file in a tab, or
  says why it can't (no row, or no file now). GTK-thread only."
  [{lp :plugin-list-pane :keys [settings-path* open-file!]}]
  (if-let [{:keys [id]} (plugin-list-pane/selected-row lp)]
    (let [dir (plugin-loader/plugins-dir @settings-path*)
          f (java.io.File. ^String dir (str id ".clj"))]
      (if (.isFile f)
        (when-let [err (:error (open-file! (str f)))]
          (plugin-list-pane/report! lp (str "plugin " id ": " err)))
        (plugin-list-pane/report! lp (str "plugin " id " has no file in " dir "."))))
    (plugin-list-pane/report! lp "No plugin is selected.")))

(defn- review-proposals-fn
  [{:keys [plugin-step! settings-path*]}]
  (fn [] (plugin-step! {:settings-path @settings-path*
                        :step (fn [] []) :open? true})))

(defn- plugins-list-fn
  [{:keys [plugin-step! settings-path*]}]
  (fn [] (plugin-step! {:settings-path @settings-path*
                        :step (fn [] []) :open-list? true})))

(defn- review-next!
  "The review's Next: the next proposal, wrapping, with the refusal note
  dropped."
  [{rp :review-pane :keys [review-state]}]
  (let [{:keys [reviews index]} @review-state
        n (count reviews)]
    (when (pos? n)
      (let [i (mod (inc index) n)]
        (swap! review-state #(-> % (assoc :index i) (dissoc :swapped)))
        (review-pane/show! rp (nth reviews i) i n)))))

(defn- close-handler
  "A pane's Close: hides its panel and gives the editor focus."
  [{:keys [right hide-right-panel! active-tab]} panel]
  (fn [] (hide-right-panel! right panel) (editor/focus! (:ed (active-tab)))))

(defn- set-pane-handlers!
  "The three panes' buttons' handlers."
  [{rp :review-pane lp :plugin-list-pane hp :plugin-history-pane
    :keys [plugin-step!] :as ctx}]
  (review-pane/set-handlers!
   rp {:on-accept (fn [] (some-> (review-step ctx :accept) plugin-step!))
       :on-reject (fn [] (some-> (review-step ctx :reject) plugin-step!))
       :on-next (fn [] (review-next! ctx))
       :on-close (close-handler ctx :review)})
  (plugin-list-pane/set-handlers!
   lp {:on-enable (fn [] (some-> (toggle-step ctx true) plugin-step!))
       :on-disable (fn [] (some-> (toggle-step ctx false) plugin-step!))
       :on-open (fn [] (open-step ctx))
       :on-history (fn [] (some-> (history-step ctx) plugin-step!))
       :on-review (fn [] ((review-proposals-fn ctx)))
       :on-close (close-handler ctx :plugins)})
  (plugin-history-pane/set-handlers!
   hp {:on-revert (fn [] (some-> (revert-step ctx) plugin-step!))
       :on-back (fn [] (plugin-step! (history-back-step ctx)))
       :on-close (close-handler ctx :history)}))

(defn- review-proposals!*
  [{:keys [plugin-step-now! settings-path*]}]
  (fn [] (plugin-step-now! {:settings-path @settings-path*
                            :step (fn [] []) :open? true})
    nil))

(defn- review-next!*
  [{rp :review-pane}]
  (fn [] (let [p (promise)]
           (on-gui! #(do ((:on-next @(:handlers rp))) (deliver p true)))
           (deref p 10000 nil)
           [])))

(defn- plugins-list!*
  [{:keys [plugin-step-now! settings-path*]}]
  (fn [] (plugin-step-now! {:settings-path @settings-path*
                            :step (fn [] []) :open-list? true})
    nil))

(defn- plugin-open!*
  [{lp :plugin-list-pane :as ctx}]
  (fn [id] (let [p (promise)]
             (on-gui! #(deliver p (do (when (plugin-list-pane/select-id! lp id) (open-step ctx))
                                      (plugin-list-pane/footer-text lp))))
             (deref p 10000 nil))))

(defn- plugin-history!*
  [{lp :plugin-list-pane :keys [plugin-step-now!] :as ctx}]
  (fn [id] (let [p (promise)]
             (on-gui! #(deliver p (when (plugin-list-pane/select-id! lp id) (history-step ctx))))
             (if-let [o (deref p 10000 nil)] (plugin-step-now! o) []))))

(defn- plugin-revert!*
  [{hp :plugin-history-pane :keys [plugin-step-now!] :as ctx}]
  (fn [n reason & [then]]
    ;; then: run on the GTK thread after the
    ;; click, before the step runs (a row
    ;; edits the reason there)
    (let [p (promise)]
      (on-gui! #(deliver p (do (plugin-history-pane/set-reason! hp reason)
                               (let [o (if (or (nil? n) (plugin-history-pane/select-n! hp n))
                                         (revert-step ctx)
                                         (do (plugin-history-pane/report! hp (str "no v" n " row")) nil))]
                                 (when then (then))
                                 o))))
      (if-let [o (deref p 10000 nil)] (plugin-step-now! o) []))))

(defn- plugin-history-close!*
  [{:keys [right hide-right-panel!]}]
  (fn [] (let [p (promise)]
           (on-gui! #(deliver p (hide-right-panel! right :history)))
           (deref p 10000 nil))))

(defn- plugin-history-shown*
  [{hp :plugin-history-pane :keys [plugin-history-shown]}]
  (fn [] (let [p (promise)]
           (on-gui! #(deliver p {:visible (pos? (g/gtk-widget-get-visible (:root hp)))
                                 :id @plugin-history-shown
                                 :title (plugin-history-pane/title-text hp)
                                 :rows (plugin-history-pane/row-texts hp)
                                 :selected (:n (plugin-history-pane/selected-row hp))
                                 :empty (plugin-history-pane/empty-text hp)
                                 :reason (plugin-history-pane/reason-text hp)
                                 :footer (plugin-history-pane/footer-text hp)}))
           (deref p 10000 nil))))

(defn- plugin-list-shown*
  [{lp :plugin-list-pane}]
  (fn [] (let [p (promise)]
           (on-gui! #(deliver p {:visible (pos? (g/gtk-widget-get-visible (:root lp)))
                                 :rows (plugin-list-pane/row-texts lp)
                                 :selected (:id (plugin-list-pane/selected-row lp))
                                 :empty (plugin-list-pane/empty-text lp)
                                 :footer (plugin-list-pane/footer-text lp)}))
           (deref p 10000 nil))))

(defn- review-shown*
  [{:keys [review-state]}]
  (fn [] (let [{:keys [reviews index]} @review-state]
           (when-let [r (get reviews index)]
             {:id (:id r) :n (:n r) :index index :count (count reviews)}))))

(defn- ui-keys
  "The window's ui hooks that drive the three panels (self-test and MCP)."
  [ctx]
  {:review-proposals!* (review-proposals!* ctx)
   :review-accept!* (fn [] (review-step-now ctx :accept))
   :review-reject!* (fn [] (review-step-now ctx :reject))
   :review-next!* (review-next!* ctx)
   :plugins-list!* (plugins-list!* ctx)
   :plugin-enable!* (fn [id] (toggle-step-now ctx id true))
   :plugin-disable!* (fn [id] (toggle-step-now ctx id false))
   :plugin-open!* (plugin-open!* ctx)
   :plugin-history!* (plugin-history!* ctx)
   :plugin-revert!* (plugin-revert!* ctx)
   :plugin-history-back!* (fn [] ((:plugin-step-now! ctx) (history-back-step ctx)))
   :plugin-history-close!* (plugin-history-close!* ctx)
   :plugin-history-shown* (plugin-history-shown* ctx)
   :plugin-list-shown* (plugin-list-shown* ctx)
   :review-shown* (review-shown* ctx)})

(defn- plugin-state*
  "What the plugin MCP tools read."
  [{:keys [settings-path* plugins-on*]}]
  (fn [] (let [path @settings-path*]
           {:plugins? @plugins-on*
            :plugins-dir (plugin-loader/plugins-dir path)
            :registry @plugin-registry/live
            :disabled (:disabled (:plugins (settings/load-settings path)))})))

(defn- plugin-propose!*
  "An agent's propose: the isolated check on the caller's
  thread (never the GTK one), then propose-step! on the
  plugin agent, which refreshes the count and the panel."
  [{:keys [plugin-step-now! settings-path*]}]
  (fn [{:keys [id source reason]}]
    (let [path @settings-path*
          result (plugin-loader/propose-check
                  {:registry @plugin-registry/live :id id :source source
                   :os (System/getProperty "os.name")})
          out (promise)
          lines (plugin-step-now!
                 {:settings-path path
                  :step (fn []
                          (let [r (plugin-loader/propose-step!
                                   {:settings-path path :id id :source source
                                    :reason reason :result result})]
                            (deliver out r)
                            (:lines r)))})]
      (deref out 0 {:status :unknown :n nil :problems [] :lines lines}))))

(defn- load-ui-keys
  "The window's ui hooks for loading plugins and for the plugin MCP tools."
  [{:keys [settings-path* plugins-on* plugin-step-now!] :as ctx}]
  {:load-plugins!* (fn [] (plugin-step-now! {:settings-path @settings-path* :step (load-step @settings-path* nil)}))
   :reload-plugins!* (fn [] (plugin-step-now! {:settings-path @settings-path* :step (load-step @settings-path* nil @plugins-on*)}))
   :plugins-on!* (fn [b] (reset! plugins-on* (boolean b)) nil)
   :plugins-dir* (fn [] (plugin-loader/plugins-dir @settings-path*))
   :plugin-state* (plugin-state* ctx)
   ;; The live plugins' MCP tools, read on
   ;; every message the MCP server handles
   :plugin-tools* (fn [] (plugin-registry/mcp-tools @plugin-registry/live))
   :watch-plugin-tools* (fn [notify!] (plugin-registry/watch-tools! plugin-registry/live notify!))
   :plugin-propose!* (plugin-propose!* ctx)})

(defn- reload-plugins-fn
  [{:keys [settings-path* cv plugins-on*] :as ctx}]
  (fn [] (plugin-load! ctx {:settings-path @settings-path* :cv cv :plugins? @plugins-on*})))

;; The startup block: the globals the core reads (the console, the reviews
;; callback, the effects, the save hook), set once the window is up.

(defn- plugin-console-fn
  "The console line printer: one line on the window's console."
  [{:keys [cv]}]
  (fn [line] (on-gui! #(cview/append! cv :system (str "; " line "\n")))))

(defn- show-list-step!
  "The list, before the review, which hides it when it opens."
  [{lp :plugin-list-pane :keys [settings-path* right show-right-panel!]} {:keys [rows open-list?]}]
  (when (or open-list? (pos? (g/gtk-widget-get-visible (:root lp))))
    (plugin-list-pane/show! lp rows (:id (plugin-list-pane/selected-row lp))
                            (str "No plugins yet. A plugin is a file in "
                                 (plugin-loader/plugins-dir @settings-path*) "."))
    (when open-list?
      (plugin-list-pane/report! lp "")
      (show-right-panel! right :plugins))))

(defn- show-history-step!
  "The history, kept on the same version while it stays open."
  [{hp :plugin-history-pane :keys [right show-right-panel!]} {:keys [history open-history?]}]
  (when (and history (or open-history? (pos? (g/gtk-widget-get-visible (:root hp)))))
    (plugin-history-pane/show! hp history
                               (when-not open-history? (:n (plugin-history-pane/selected-row hp))))
    (when open-history?
      (plugin-history-pane/report! hp "")
      (show-right-panel! right :history))))

(defn- next-review-state
  "The review state after the proposals changed: the same plugin's proposal
  again if it's still pending, else the first. A newer version of the shown
  plugin replacing it arms a one-time refusal of Accept, kept while that
  version stays shown."
  [old-state reviews showing?]
  (let [{old :reviews index :index} old-state
        shown (get old index)
        i (or (first (keep-indexed (fn [i r] (when (= (:id shown) (:id r)) i)) reviews)) 0)
        r (nth reviews i)]
    (cond-> {:reviews reviews :index i}
      (and showing? (= (:id shown) (:id r)) (not= (:n shown) (:n r)))
      (assoc :swapped [(:id r) (:n shown) (:n r)])
      (and showing? (= [(:id shown) (:n shown)] [(:id r) (:n r)])
           (= (:n r) (get (:swapped old-state) 2)))
      (assoc :swapped (:swapped old-state)))))

(defn- show-review!
  "Shows the review panel on the proposals: the shown plugin's again if it's
  still pending, else the first."
  [{rp :review-pane :keys [review-state right show-right-panel!]} reviews]
  (let [{old :reviews index :index} @review-state
        shown (get old index)
        showing? (pos? (g/gtk-widget-get-visible (:root rp)))
        st (next-review-state @review-state reviews showing?)
        i (:index st)
        r (nth reviews i)
        n (count reviews)]
    (reset! review-state st)
    ;; the same proposal still shown keeps
    ;; the note being typed, the diff's place and the
    ;; footer
    (if (and showing? (= [(:id shown) (:n shown)] [(:id r) (:n r)]))
      (review-pane/update-summary! rp r i n)
      (review-pane/show! rp r i n))
    (show-right-panel! right :review)))

(defn- show-review-step!
  "The pending count and, when the review is showing or being opened, the
  review panel."
  [{rp :review-pane :keys [review-state pending-label status-label right hide-right-panel!] :as ctx}
   {:keys [reviews open?]}]
  (let [n (count reviews)]
    (g/gtk-label-set-text pending-label (str n " plugin proposal" (when (not= 1 n) "s")))
    (g/gtk-widget-set-visible pending-label (if (pos? n) 1 0))
    (when (or open? (pos? (g/gtk-widget-get-visible (:root rp))))
      (if (zero? n)
        (do (reset! review-state {:reviews [] :index 0})
            (hide-right-panel! right :review)
            (when open? (g/gtk-label-set-text status-label "No plugin proposals to review.")))
        (show-review! ctx reviews)))))

(defn- reviews-changed-fn
  "After every plugin step, the pending count and, when
  it's showing or being opened, the review panel: the same plugin's
  proposal again if it's still pending, else the first."
  [ctx]
  (fn [{:keys [after lines done] :as m}]
    (on-gui!
     (fn []
       (try
         ;; tabs on a file the step rewrote follow it
         (sync-plugin-tabs! ctx)
         (show-list-step! ctx m)
         (show-history-step! ctx m)
         (show-review-step! ctx m)
         (when after (after lines))
         (finally (when done (deliver done true))))))))

(defn- effect-turn [f] (plugin-registry/gui-turn on-gui! f 5000))

(defn- eval-fire
  "The :eval! effect's :fire: the dialect is checked on the evaluator the
  eval goes to, in the same turn."
  [{:keys [ev*]} {:keys [tab-of]} id code]
  (fn [cbs]
    (effect-turn #(let [t (tab-of id)
                        e @ev*]
                    (when-not (plugin-registry/quiet-capable? e)
                      (throw (ex-info "p/eval! works with Jolt for now; p/repl! works with every dialect" {})))
                    (ev/eval! e code (assoc cbs :quiet (str "jonquil-quiet-" (random-uuid) ":")
                                            :ns-source (editor/editor-text (:ed t))
                                            :owner id))))))

(defn- eval-effect
  "The effects' :eval!, run through the tab's id on the plugin's worker;
  await-eval! stops one that times out."
  [ctx {:keys [stop-eval! notice!] :as fx}]
  (fn [id code opts]
    (plugin-registry/await-eval!
     {:timeout-ms (:timeout-ms opts)
      :interrupt! stop-eval!
      :on-notice notice!
      :fire (eval-fire ctx fx id code)})))

(defn- repl-notify
  "mcp-eval!'s notify for :repl!: a busy warning is kept for the turn's
  result, the done map's output and result go to the effect's callbacks."
  [busy {:keys [on-output on-complete]}]
  (fn [m]
    (if-let [w (:busy m)]
      (reset! busy w)
      (let [{:keys [result output]} (:done m)]
        (on-output :out output)
        (on-complete result)))))

(defn- repl-fire
  "The :repl! effect's :fire: the code goes through mcp-eval! on the tab."
  [{:keys [cv ev* status-label mcp-eval!]} {:keys [tab-of]} id code]
  (fn [cbs]
    (effect-turn #(let [t (tab-of id)
                        busy (atom nil)]
                    (mcp-eval! {:tab t :cv cv :ev* ev* :status-label status-label}
                               code {:ns-source (editor/editor-text (:ed t)) :owner id}
                               (repl-notify busy cbs))
                    @busy))))

(defn- repl-effect
  "The effects' :repl!, run through the tab's id on the plugin's worker."
  [ctx {:keys [stop-eval! notice!] :as fx}]
  (fn [id code opts]
    (dissoc (plugin-registry/await-eval!
             {:timeout-ms (:timeout-ms opts)
              :interrupt! stop-eval!
              ;; the person can answer a read at the console
              :input-stops? false
              :on-notice notice!
              :fire (repl-fire ctx fx id code)})
            :err)))

(defn- replace-text-effect
  "A plugin's text lands exactly as sent, like an MCP edit. A NUL in it
  would truncate at the insert (apply-op! refuses the op), so the throw names
  it rather than claiming read-only."
  [{:keys [tab-of]}]
  (fn [id text]
    (when-let [n (when (string? text) (tabs/nul-line text))]
      (throw (ex-info (str "the new text holds a NUL byte on line " n
                           ", which Jonquil's editor can't keep, so nothing was changed") {})))
    (effect-turn #(let [ed (:ed (tab-of id))]
                    (when-not (editor/apply-op-plain! ed (mcp-window/replace-op (editor/editor-text ed) text))
                      (throw (ex-info "that tab is read-only" {})))))))

(defn- effect-helpers
  "The small fns the effects share: stop-eval! (as the MCP's stop: never
  interrupt an idle evaluator, since an attach evaluator would detach),
  notice! (Jonquil's own lines during a plugin's eval reach the console) and
  tab-of (the tab with that id, which may have closed since the command
  started)."
  [{:keys [cv ev* tabs]}]
  {:stop-eval! (fn [] (effect-turn #(when (ev/evaluating? @ev*) (ev/interrupt! @ev*))))
   :notice! (fn [kind text] (on-gui! #(cview/append! cv kind text)))
   :tab-of (fn [id] (or (some #(when (= id (:id %)) %) @tabs)
                        (throw (ex-info "that tab was closed" {}))))})

(defn- effects-map
  "The effect functions a plugin's :run command reaches,
  each a turn on the GTK thread (it runs on a worker) acting on the
  tab with that id, which may have closed since the command started."
  [{:keys [cv status-label active-tab open-file!] :as ctx}]
  (let [{:keys [tab-of] :as fx} (effect-helpers ctx)]
    {:active-tab (fn [] (:id (active-tab)))
     ;; the same, for a plugin MCP tool's ctx,
     ;; whose call arrives on the MCP serve thread
     :active-tab-turn (fn [] (effect-turn #(:id (active-tab))))
     :print! (fn [s] (effect-turn #(cview/append! cv :system (str s "\n"))))
     :status! (fn [s] (effect-turn #(g/gtk-label-set-text status-label (str s))))
     :buffer-text (fn [id] (effect-turn #(editor/editor-text (:ed (tab-of id)))))
     :replace-text! (replace-text-effect fx)
     :open! (fn [path]
              (effect-turn #(let [r (open-file! (str path))]
                              (when (and (map? r) (:error r)) (throw (ex-info (str (:error r)) {})))
                              (:id (active-tab)))))
     ;; both run through the tab's id, on the
     ;; plugin's worker; await-eval! stops one that times out
     :eval! (eval-effect ctx fx)
     :repl! (repl-effect ctx fx)
     :report! (fn [line]
                (on-gui! #(do (cview/append! cv :system (str "; " line "\n"))
                              (g/gtk-label-set-text status-label (str "; " line)))))}))

(defn- on-saved-fn
  "Saving a plugin file reloads that plugin, and any save fires the
  :after-save hooks on the saved tab; save-to! runs on the GTK
  thread, so the tab lookup is safe here."
  [{:keys [settings-path* cv plugins-on* tabs] :as ctx}]
  (fn [path ed]
    (when (and @plugins-on*
               (plugin-loader/plugin-file? (plugin-loader/plugins-dir @settings-path*) path))
      (plugin-load! ctx {:settings-path @settings-path* :cv cv :only path}))
    (plugin-hooks/run-hooks! {:point :after-save
                              :tab (some #(when (identical? ed (:ed %)) (:id %)) @tabs)
                              :event {:path path}})))

(defn- plugins-start!
  "The part's :plugins-start!: the five statements that repoint the
  process-global plugin atoms at this window, in order. The user's plugins
  load off the GTK thread once the window is up; keys pressed before the
  swap see none yet. The window that started last owns them."
  [{:keys [plugin-console plugin-reviews-changed on-saved plugins-on* settings-path* cv] :as ctx}]
  (fn []
    (reset! plugin-console (plugin-console-fn ctx))
    (reset! plugin-reviews-changed (reviews-changed-fn ctx))
    (reset! plugin-registry/effects (effects-map ctx))
    (when @plugins-on*
      (plugin-load! ctx {:settings-path @settings-path* :cv cv}))
    (reset! on-saved (on-saved-fn ctx))))

(defn- install
  "The plugins part for one window. The panes' handlers are set here, before
  the window is presented; the process-global atoms wait for :plugins-start!."
  [ctx0]
  (let [ctx (assoc ctx0 :review-state (atom {:reviews [] :index 0}))
        ctx (assoc ctx :plugin-step-now! (plugin-step-now-fn ctx))]
    (set-pane-handlers! ctx)
    {:fns {:review-proposals-fn (review-proposals-fn ctx)
           :plugins-list-fn (plugins-list-fn ctx)
           :reload-plugins-fn (reload-plugins-fn ctx)
           :plugins-start! (plugins-start! ctx)}
     :ui (merge (ui-keys ctx) (load-ui-keys ctx))}))

(def part
  "The plugins part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :plugins}))
