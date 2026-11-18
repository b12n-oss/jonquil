(ns jonquil.gtk.parts.worlds
  "Save World and Restore World (LM7) as a window part: each project keeps
  its last world/KEEP saves beside the settings file (the commands are
  jonquil.parts.worlds/spec). Moved out of jonquil.gtk.window with no change
  beyond reading the window's bindings from wctx."
  (:require [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.palette :as palette]
            [jonquil.mcp.world :as mcp-world]
            [jonquil.world :as world]
            [jonquil.world-ops :as world-ops]))

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

(defn- world-save!
  "One save into the history through world-ops/save!: opts is {:ev :dir
  :kind :protect :on-done}; on-done runs with the answer on the GTK thread."
  [{:keys [on-done] :as opts}]
  (world-ops/save! opts (fn [r] (on-gui! (fn [] (on-done r))))))

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

(defn- restore-reported!
  "Reports a world-ops/restore! answer {:first s :restore r}: when the
  safety save s failed, that nothing was restored; else what s could only
  keep as placeholders, then the restore. GTK thread."
  [{:keys [set-status!] :as ctx} picked? {s :first r :restore}]
  (if (:error s)
    ;; never restore over data that couldn't be kept
    (do (world-report! ctx (world/save-report s))
        (set-status!
         (str "; Restore World didn't run: what you have now couldn't be saved first. " (:error s))))
    (do (name-placeholders! ctx s)
        (world-report! ctx (world/restore-report
                            r {:saved-first? true :picked? picked?
                               :first-placeholders (count (:placeholders s))})))))

(defn- world-restore-from!
  [{:keys [set-status!] :as ctx} path picked?]
  (if-let [e (world-ev ctx)]
    (do (set-status! "; Saving what you have, then restoring...")
        (world-ops/restore! {:ev e :dir (world-dir! ctx) :path path}
                            (fn [a] (on-gui! (fn [] (restore-reported! ctx picked? a))))))
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

(defn- mcp-world!
  "The MCP world tools' hook (UF7), GTK thread. op :save saves into the
  agent's own list; op :restore brings back args' :id, or the agent's
  newest, saving what's there first (args' :on-first-path hears where, at
  once; that save is kind :agent-before-restore, kept apart from the
  person's own before-restore saves). The person sees what Save World and
  Restore World would say. on-done (any thread) gets world-ops' answer,
  {:answer a} when there's nothing to restore, or {:unsupported true}."
  [{:keys [set-status!] :as ctx} op {:keys [id on-first-path]} on-done]
  (if-let [e (world-ev ctx)]
    (let [dir (world-dir! ctx)]
      (case op
        :save (do (set-status! "; An agent is saving the world...")
                  (world-save! {:ev e :dir dir :kind :agent :protect #{}
                                :on-done (fn [r] (world-report! ctx (world/save-report r)) (on-done r))}))
        :restore (let [{:keys [path answer]} (mcp-world/restore-target (world/entries dir) id)]
                   (if answer
                     (on-done {:answer answer})
                     (do (set-status! "; An agent is restoring a world: saving what you have first...")
                         (world-ops/restore! {:ev e :dir dir :path path :first-kind :agent-before-restore
                                              :on-first-path on-first-path}
                                             (fn [a]
                                               (on-gui! (fn [] (restore-reported! ctx true a)))
                                               (on-done a))))))))
    (on-done {:unsupported true})))

;; Save World and Restore World (LM7, jonquil.world-rt through
;; ev/World): each project keeps its last world/KEEP saves beside
;; the settings file, and every restore saves what's there first,
;; so a restore you didn't mean is one more restore away
(defn- install
  "The worlds part for one window, reading the evaluator, the settings path,
  the console, the tree and the active tab from wctx. Answers the four world
  fns and the MCP tools' :mcp-world!* ui key; :world-restore-from-fn is what the palette's earlier-world entries
  dispatch to (wire-tab!)."
  [ctx]
  {:fns
   {:world-save-fn (fn [] (world-save-fn ctx))
    :world-restore-fn (fn [] (world-restore-fn ctx))
    :world-restore-earlier-fn (fn [] (world-restore-earlier-fn ctx))
    :world-restore-from-fn (fn [path] (world-restore-from! ctx path true))}
   ;; for jonquil_world_save and jonquil_world_restore (UF7)
   :ui {:mcp-world!* (fn [op args on-done] (mcp-world! ctx op args on-done))}})

(def part
  "The worlds part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :worlds}))
