(ns jonquil.gtk.parts.plugins
  "The plugin panels' behaviour as a window part (the commands are
  jonquil.parts.plugins/spec): the review, list and history panels' steps and
  handlers, and the window's ui hooks that drive them. Moved out of
  jonquil.gtk.window; the three panes and the pending label stay in the
  window and come in wctx."
  (:require [clojure.string :as str]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.plugin-history-pane :as plugin-history-pane]
            [jonquil.gtk.plugin-list-pane :as plugin-list-pane]
            [jonquil.gtk.review-pane :as review-pane]
            [jonquil.plugin-loader :as plugin-loader]
            [jonquil.plugin-registry :as plugin-registry]))

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
    :keys [plugin-step! settings-path*] :as ctx}]
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
       :on-review (fn [] (plugin-step! {:settings-path @settings-path* :step (fn [] []) :open? true}))
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

(defn- install
  "The plugins part for one window. The panes' handlers are set here, before
  the window is presented."
  [ctx]
  (set-pane-handlers! ctx)
  {:fns {:review-proposals-fn (review-proposals-fn ctx)
         :plugins-list-fn (plugins-list-fn ctx)}
   :ui (ui-keys ctx)})

(def part
  "The plugins part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :plugins}))
