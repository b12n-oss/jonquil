(ns jonquil.gtk.parts.replay
  "The last session's replay (LM7) as a window part: records what the
  window evaluates in its project's record (jonquil.replay), offers the
  previous session back at open and on a re-root, and opens or forgets it
  from the palette (the commands are jonquil.parts.replay/spec). Moved out
  of jonquil.gtk.window unchanged; the tab operations it needs (tab-by-id,
  select-tab!, new-tab!) come from wctx, since a part never requires the
  window namespace."
  (:require [jolt.ffi :as ffi]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.dialect :as dialect]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.replay :as replay]
            [jonquil.settings :as settings]))

;; --- LM7: the last session's record ---------------------------------------------
;; Each project has a record (jonquil.replay): what its windows evaluated, in
;; :current, and what earlier windows did, in :previous. ctx is the window's
;; tab map (tab-ctx) with this part's state and wctx's tab fns added (see
;; part-ctx): :replay*, an atom holding
;; {:root :path :record :dirty?} for the root the window records under (nil
;; until a root opens with :remember-session? on for it).

;; The roots a window of this process has opened: only the first window to
;; open one moves its :current to :previous.
(defonce ^:private opened-roots (atom #{}))

(defn- remember-session?
  "Whether root's evaluations are recorded: its project's .jonquil.edn
  :remember-session? when it names one, else the settings file's (on
  unless it says false)."
  [settings-path root]
  (replay/remember-session? (dialect/project-remember-session root)
                            (:remember-session? (settings/load-settings settings-path))))

(defn- replayable?
  "Whether the window's dialect gets a replay: Jolt and JVM Clojure."
  [sel]
  (contains? #{:jolt :clj} (:id (:profile sel))))

(defn- count-text [n one many]
  (str n " " (if (= 1 n) one many)))

(defn- offer-line
  "The console line that offers back the previous slot's entries es at open,
  or nil when es is empty. now is epoch ms, sel the window's selection. When
  the newest entry's dialect isn't the window's, the line says so."
  [es now sel]
  (when (seq es)
    (let [s (replay/summary es now)
          dialect (:name (:profile sel))]
      (if (replayable? sel)
        (str "; Last session (" (:dialect s) ", " (:when s) "): "
             (count-text (:files s) "file" "files") ", "
             (count-text (:defs s) "definition" "definitions") ", "
             (count-text (:others s) "other form" "other forms")
             ". Open last session's replay from the palette to review it."
             (when (not= (:dialect s) dialect)
               (str " Recorded in " (:dialect s) "; this window runs " dialect ".")))
        (str "; Last session (" dialect ", " (:when s) "): replay isn't available for "
             dialect " yet. Your prompt history is back.")))))

;; A record change is written about this long after it, once, however many
;; changes came meanwhile (estimate: the spec lets a crash lose up to a second).
(def ^:private REPLAY-SAVE-MS 300)

(defn- note-replay-result!
  "Says err once on the console, as \"; last session: <error>\", until a
  write works again (err nil)."
  [{:keys [cv replay-error]} err]
  (if err
    (when (not= err @replay-error)
      (reset! replay-error err)
      (cview/append! cv :system (str "; last session: " err "\n")))
    (reset! replay-error nil)))

(defn- save-replay!
  "Writes {:path :record} now."
  [ctx {:keys [path record]}]
  (note-replay-result! ctx (replay/save-record! path record)))

(defn- flush-replay!
  "Writes the window's record when it has changes not yet on disk. GTK thread."
  [{:keys [replay*] :as ctx}]
  (let [st @replay*]
    (when (:dirty? st)
      (swap! replay* assoc :dirty? false)
      (save-replay! ctx st))))

(defn- schedule-replay-save!
  "Arranges one flush-replay! in REPLAY-SAVE-MS unless one is already due.
  The timer's callable is the part's own :replay-save-cb, made once at
  install, since every callable lives for the process."
  [{:keys [replay-save-pending? replay-save-cb]}]
  (when (compare-and-set! replay-save-pending? false true)
    (g/g-timeout-add REPLAY-SAVE-MS replay-save-cb ffi/null)))

(defn- open-root!
  "The tree's root has opened (at startup, or an Open Folder re-root):
  writes the old root's pending changes, loads the new root's record, and, for
  the first window of the process on this root, moves its :current to
  :previous (saved at once). Then the root's prompt history goes into the
  evaluator and the console offers :previous when it isn't empty. Does
  nothing with :remember-session? off for root, or when the record is
  already this root's. GTK thread. Never throws; a failure is said on the console."
  [{:keys [cv ev* sel* settings-path* tree-pane replay*] :as ctx}]
  (let [root (:root @(:state tree-pane))]
    (when (and (remember-session? @settings-path* root)
               (not (and @replay* (= root (:root @replay*)))))
      (try
        (flush-replay! ctx)
        (let [path (replay/record-path @settings-path* root)
              {:keys [record warning]} (replay/load-record path)
              first? (not (contains? @opened-roots root))
              moved (if first? (replay/open-slot record) record)
              state {:root root :path path :record moved :dirty? false}]
          (swap! opened-roots conj root)
          (when warning (cview/append! cv :system (str "; " warning "\n")))
          (reset! replay* state)
          (when (not= moved record) (save-replay! ctx state))
          (let [v (:history moved)]
            (reset! (ev/history @ev*) {:items v :index (count v) :draft ""}))
          (when-let [line (offer-line (:previous moved) (System/currentTimeMillis) @sel*)]
            (cview/append! cv :system (str line "\n"))))
        (catch :default e
          (cview/append! cv :system (str "; last session: " (ex-message e) "\n")))))))

(defn- append-to-root!
  "Files es (and forgets :previous when asked) in root's own record file and
  saves it at once: the window has moved to another root since the evaluation
  started, so its replay state stays as it is. opts: :forget? forgets
  :previous, after carrying the other forms of that file's own :previous that
  are still skipped in :run-text (replay/carry-skipped, ahead of es). A file
  that can't be read is left alone and the warning said (once), since saving
  would replace it."
  [{:keys [settings-path*] :as ctx} root es {:keys [forget? run-text]}]
  (when (or (seq es) forget?)
    (let [path (replay/record-path @settings-path* root)
          {:keys [record warning]} (replay/load-record path)]
      (if warning
        (note-replay-result! ctx warning)
        (save-replay! ctx {:path path
                           :record (cond-> record
                                     forget? (replay/carry-skipped run-text)
                                     (seq es) (replay/add-entries es)
                                     forget? replay/forget)})))))

(defn- file-in-window!
  "Files an evaluation's entries in the window's own record: the evaluator's
  history, the Run's skipped forms and forgetting when forget-here?, then the
  entries when any. Marks the record dirty and schedules the save."
  [{:keys [ev* replay*] :as ctx} {:keys [es forget-here? run-text]}]
  (let [items (:items @(ev/history @ev*))]
    (swap! replay* (fn [s]
                     (-> s
                         (update :record (fn [r] (cond-> (replay/with-history r items)
                                                   forget-here? (replay/carry-skipped run-text)
                                                   (seq es) (replay/add-entries es)
                                                   forget-here? replay/forget)))
                         (assoc :dirty? true))))
    (schedule-replay-save! ctx)))

(defn- forget-tab-root!
  "Forgets the replay tab's root when it isn't the root the entries went to:
  in the window's record when st holds that root, else through append-to-root!."
  [{:keys [replay*] :as ctx} {:keys [rt st run-text]}]
  (if (and st (= (:root rt) (:root st)))
    (do (swap! replay* (fn [s] (-> s
                                   (update :record #(-> % (replay/carry-skipped run-text) replay/forget))
                                   (assoc :dirty? true))))
        (schedule-replay-save! ctx))
    (append-to-root! ctx (:root rt) nil {:forget? true :run-text run-text})))

(defn- record-eval!
  "The recorder's work for one finished evaluation, on the GTK thread. An :ok
  Run of the replay tab forgets the :previous of the root the tab was opened
  for, but first carries into :current the other forms of that :previous that
  are still behind #_ in the Run's text (the record's :code), ahead of what
  the Run recorded."
  [{:keys [cv sel* settings-path* replay*] :as ctx} {:keys [source tab-id root] :as record} result]
  (when (remember-session? @settings-path* root)
    (let [ok? (= :ok (:status result))
          es (when ok?
               (replay/entries (assoc (select-keys record [:source :code :path :ns])
                                      :at (System/currentTimeMillis)
                                      :dialect (:name (:profile @sel*)))))
          rt @(:replay-tab* cv)
          ;; an :ok Run of the replay tab forgets its own root's :previous,
          ;; which may not be the root the entries are filed under
          forget? (and ok? (= :run source) (some? tab-id) (= tab-id (:id rt)))
          forget-here? (and forget? (= root (:root rt)))
          run-text (:code record)
          st @replay*]
      (if (and st (= root (:root st)))
        (file-in-window! ctx {:es es :forget-here? forget-here? :run-text run-text})
        (append-to-root! ctx root es {:forget? forget-here? :run-text run-text}))
      (when (and forget? (not forget-here?))
        (forget-tab-root! ctx {:rt rt :st st :run-text run-text})))))

(defn- make-recorder
  "The fn (record result) hooked-opts calls when an evaluation that carries a
  :record (with the :root it started under) finishes. It runs on the eval's
  worker thread, so it only hands the work to the GTK thread, where every
  read and write of the replay state happens. The work files the entries of
  an :ok evaluation under the record's root, stores the evaluator's history
  and schedules a save; an :ok Run of the replay tab also forgets the
  :previous of the root that tab was opened for, after carrying forward the
  other forms still skipped (#_) in the Run's text.
  A throw is said once, like a failed save, and never reaches the evaluation."
  [ctx]
  (fn [record result]
    (on-gui! (fn []
               (try (record-eval! ctx record result)
                    (catch :default e
                      (try (note-replay-result! ctx (or (ex-message e) (str e)))
                           (catch :default _ nil))))))))

(defn- replay-open!
  "Open last session's replay: an Untitled tab holding the script of :previous,
  marked saved, and now the replay tab, {:id :root} in :replay-tab*. A
  second Open selects that tab while it exists and was opened for this
  root; one opened for another root stops being the replay tab when the
  new one opens. With nothing to replay, or a dialect that doesn't replay,
  the console says so instead. GTK thread."
  [{:keys [cv sel* replay* tabs tab-by-id select-tab! new-tab!] :as ctx}]
  (let [st @replay*
        es (:previous (:record st))
        sel @sel*
        {open-id :id open-root :root} @(:replay-tab* cv)]
    (cond
      ;; this root's replay tab is still there: show it, change nothing (an
      ;; older one left behind would become a plain Untitled tab whose Run resets)
      (and open-id (= open-root (:root st)) (tab-by-id tabs open-id)) (select-tab! ctx open-id)
      (empty? es) (cview/append! cv :system "; There's no last session to replay for this folder.\n")
      (not (replayable? sel)) (cview/append! cv :system (str (offer-line es (System/currentTimeMillis) sel) "\n"))
      :else (when-let [t (new-tab! ctx {:path nil
                                        :text (replay/script es {:root (:root st)
                                                                 :now (System/currentTimeMillis)
                                                                 :dialect (:name (:profile sel))})})]
              (editor/mark-saved! (:ed t))
              (reset! (:replay-tab* cv) {:id (:id t) :root (:root st)})))))

(defn- replay-forget!
  "Forget last session: empties :previous (the prompt history stays), saves
  at once and says so. GTK thread."
  [{:keys [cv replay* settings-path*] :as ctx}]
  (if (and @replay* (remember-session? @settings-path* (:root @replay*)))
    (do (swap! replay* (fn [s] (-> s (update :record replay/forget) (assoc :dirty? true))))
        (flush-replay! ctx)
        (cview/append! cv :system "; Forgot the last session. Your prompt history stays.\n"))
    (cview/append! cv :system "; There's no last session to forget for this folder.\n")))

;; --- the install fn ------------------------------------------------------------

(defn- part-ctx
  "What the helpers above take as ctx: the window's tab map, read at call
  time, plus this part's state and wctx's tab fns. nil until activate! has
  set the tab map."
  [wctx state]
  (when-let [tc @(:tab-ctx* wctx)]
    (merge tc state (select-keys wctx [:tab-by-id :select-tab! :new-tab!]))))

(defn- new-state
  "This window's replay state: the record, the last error, the pending-save
  flag and the one callable that timer fires. The callable reads the tab map
  when it runs, so it is made before the map exists."
  [wctx]
  (let [state* (atom nil)
        pending? (atom false)
        cb (callable (fn [_data]
                       (reset! pending? false)
                       (try (some-> (part-ctx wctx @state*) flush-replay!)
                            (catch :default _ nil))
                       0)
                     [:pointer] :int :on-throw 0)]
    (reset! state* {:replay* (atom nil) :replay-error (atom nil)
                    :replay-save-pending? pending? :replay-save-cb cb})))

(defn- install
  "The replay part for one window. Its state (see new-state) is this
  window's own; opened-roots stays process-wide.
  The recorder and the root fn go into cv's hook slots now, and read the
  tab map when an evaluation calls them."
  [{:keys [cv] :as wctx}]
  (let [state (new-state wctx)
        ctx (fn [] (part-ctx wctx state))
        open-root (fn [] (when-let [c (ctx)] (open-root! c)))
        open-fn (fn [] (replay-open! (ctx)))
        forget-fn (fn [] (replay-forget! (ctx)))]
    (reset! (:recorder* cv) (fn [record result]
                              (when-let [c (ctx)] ((make-recorder c) record result))))
    (reset! (:replay-root* cv) (fn [] (some-> (ctx) :tree-pane :state deref :root)))
    {:fns {:replay-open-fn open-fn :replay-forget-fn forget-fn}
     :ui {;; LM7: the window's record, {:root :path :record}, and the
          ;; replay tab's id (nil until one is open)
          :replay-record* (fn [] (select-keys @(:replay* state) [:root :path :record]))
          :replay-tab-id* (fn [] (:id @(:replay-tab* cv)))
          :replay-open!* open-fn
          :replay-forget!* forget-fn
          ;; forgets that this process opened root, so the next
          ;; open-root! on it moves :current to :previous again
          :replay-reopen-root!* (fn [root] (swap! opened-roots disj root))}
     ;; LM7: the root's record, its offer and history, at startup and on a
     ;; re-root (open-root! flushes the old root first); at quit a pending
     ;; record write is not left to the timer
     :hooks {:on-start open-root
             :on-reroot (fn [_] (open-root))
             :on-quit (fn [] (when-let [c (ctx)] (flush-replay! c)))}}))

(def part
  "The replay part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :replay}))
