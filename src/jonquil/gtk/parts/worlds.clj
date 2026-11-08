(ns jonquil.gtk.parts.worlds
  "Save World and Restore World (LM7) as a window part: each project keeps
  its last world/KEEP saves beside the settings file (the commands are
  jonquil.parts.worlds/spec). Moved out of jonquil.gtk.window with no change
  beyond reading the window's bindings from wctx."
  (:require [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.palette :as palette]
            [jonquil.world :as world]))

(defn- world-ev
  "The evaluator when it supports worlds, else nil."
  [{:keys [ev*]}]
  (let [e @ev*] (when (and e (satisfies? ev/World e)) e)))

(defn- world-dir!
  [{:keys [settings-path* tree-pane]}]
  ;; the window's folder, or one no-folder history: never
  ;; the active tab's own folder, which would split the
  ;; history by whichever tab is in front
  (world/world-dir @settings-path* (:root @(:state tree-pane))))

(defn- world-report!
  [{:keys [cv set-status!]} {:keys [status lines]}]
  (doseq [l lines] (cview/append! cv :system (str l "\n")))
  (set-status! status))

(defn- drop-file! [path] (.delete (java.io.File. ^String path)))

(defn- record-save!
  "Records a finished save r (which lists it), then prunes the history,
  sparing protect. A failure deletes the file and answers an :error."
  [r {:keys [path dir at kind protect]}]
  (if (:error r)
    (do (drop-file! path) r)
    (try
      (world/record! path {:at at :kind kind :saved (:saved r)
                           :placeholders (count (:placeholders r))})
      (world/prune! dir world/KEEP protect)
      r
      (catch :default ex
        (drop-file! path)
        (drop-file! (world/record-path path))
        {:error (str "the world couldn't be recorded: " (or (ex-message ex) (str ex)))}))))

(defn- world-save!
  "One save into the history: opts is {:ev :dir :kind :protect :on-done}.
  On success it's recorded,
  which is what lists it, then the history is pruned,
  sparing protect (a save about to be restored). On
  failure the file is deleted, so a half-written or
  timed-out world is never picked. on-done always
  runs, with the answer, on the GTK thread."
  [{:keys [ev dir kind protect on-done]}]
  (let [at (System/currentTimeMillis)
        path (world/free-entry-path dir at)]
    (ev/world-op! ev :save {:path path}
                  (fn [r]
                    (on-gui! (fn []
                               (on-done (record-save! r {:path path :dir dir :at at
                                                         :kind kind :protect protect}))))))))

(defn- world-save-fn
  [{:keys [set-status!] :as ctx}]
  (if-let [e (world-ev ctx)]
    (do (set-status! "; Saving the world...")
        (world-save! {:ev e :dir (world-dir! ctx) :kind :save :protect #{}
                      :on-done (fn [r] (world-report! ctx (world/save-report r)))}))
    (set-status! "; Save World needs Jolt.")))

(defn- name-placeholders!
  "What went into the safety save s as placeholders
  can't come back from it: name it."
  [{:keys [cv]} s]
  (doseq [l (:lines (world/save-report s))]
    (cview/append! cv :system (str l "\n"))))

(defn- restore-after-save!
  "The restore proper, once the safety save s of what was there has landed.
  opts is {:ev :path :picked?}."
  [{:keys [set-status!] :as ctx} {e :ev :keys [path picked?]} s]
  (if (:error s)
    ;; never restore over data that couldn't be kept
    (do (world-report! ctx (world/save-report s))
        (set-status!
         (str "; Restore World didn't run: what you have now couldn't be saved first. " (:error s))))
    (do
      (name-placeholders! ctx s)
      (ev/world-op! e :restore {:path path}
                    (fn [r]
                      (on-gui! (fn []
                                 (world-report!
                                  ctx
                                  (world/restore-report
                                   r {:saved-first? true :picked? picked?
                                      :first-placeholders (count (:placeholders s))})))))))))

(defn- world-restore-from!
  [{:keys [set-status!] :as ctx} path picked?]
  (if-let [e (world-ev ctx)]
    (do (set-status! "; Saving what you have, then restoring...")
        (world-save! {:ev e :dir (world-dir! ctx) :kind :before-restore :protect #{path}
                      :on-done (fn [s] (restore-after-save! ctx {:ev e :path path :picked? picked?} s))}))
    (set-status! "; Restore World needs Jolt.")))

(defn- world-restore-fn
  [{:keys [set-status!] :as ctx}]
  (cond
    (nil? (world-ev ctx)) (set-status! "; Restore World needs Jolt.")
    :else (if-let [newest (world/newest-save (world/entries (world-dir! ctx)))]
            (world-restore-from! ctx (:path newest) false)
            (world-report! ctx (world/restore-report {:missing true})))))

(defn- world-restore-earlier-fn
  [{:keys [set-status! active-tab] :as ctx}]
  (let [es (world/entries (world-dir! ctx))
        now (System/currentTimeMillis)]
    (cond
      (nil? (world-ev ctx)) (set-status! "; Restore World needs Jolt.")
      (empty? es) (world-report! ctx (world/restore-report {:missing true}))
      :else (palette/open! (:palette (active-tab))
                           (mapv (fn [x] {:id [:world/restore-from (:path x)]
                                          :doc (world/entry-label now x)
                                          :chords [] :source "world"})
                                 es)))))

;; Save World and Restore World (LM7, jonquil.world-rt through
;; ev/World): each project keeps its last world/KEEP saves beside
;; the settings file, and every restore saves what's there first,
;; so a restore you didn't mean is one more restore away
(defn- install
  "The worlds part for one window, reading the evaluator, the settings path,
  the console, the tree and the active tab from wctx. Answers the four world
  fns; :world-restore-from-fn is what the palette's earlier-world entries
  dispatch to (wire-tab!)."
  [ctx]
  {:fns
   {:world-save-fn (fn [] (world-save-fn ctx))
    :world-restore-fn (fn [] (world-restore-fn ctx))
    :world-restore-earlier-fn (fn [] (world-restore-earlier-fn ctx))
    :world-restore-from-fn (fn [path] (world-restore-from! ctx path true))}})

(def part
  "The worlds part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :worlds}))
