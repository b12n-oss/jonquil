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

(defn- install
  "The worlds part for one window, reading the evaluator, the settings path,
  the console, the tree and the active tab from wctx. Answers the four world
  fns; :world-restore-from-fn is what the palette's earlier-world entries
  dispatch to (wire-tab!)."
  [{:keys [ev* settings-path* cv tree-pane active-tab set-status!]}]
  (let [;; Save World and Restore World (LM7, jonquil.world-rt through
        ;; ev/World): each project keeps its last world/KEEP saves beside
        ;; the settings file, and every restore saves what's there first,
        ;; so a restore you didn't mean is one more restore away
        world-ev (fn [] (let [e @ev*] (when (and e (satisfies? ev/World e)) e)))
        world-dir! (fn []
                     ;; the window's folder, or one no-folder history: never
                     ;; the active tab's own folder, which would split the
                     ;; history by whichever tab is in front
                     (world/world-dir @settings-path* (:root @(:state tree-pane))))
        world-report! (fn [{:keys [status lines]}]
                        (doseq [l lines] (cview/append! cv :system (str l "\n")))
                        (set-status! status))
        world-save! (fn [e dir kind protect on-done]
                      ;; one save into the history. On success it's recorded,
                      ;; which is what lists it, then the history is pruned,
                      ;; sparing protect (a save about to be restored). On
                      ;; failure the file is deleted, so a half-written or
                      ;; timed-out world is never picked. on-done always
                      ;; runs, with the answer, on the GTK thread.
                      (let [at (System/currentTimeMillis)
                            path (world/free-entry-path dir at)]
                        (ev/world-op! e :save {:path path}
                                      (fn [r]
                                        (on-gui! (fn []
                                                   (let [r (if (:error r)
                                                             (do (.delete (java.io.File. ^String path)) r)
                                                             (try
                                                               (world/record! path {:at at :kind kind :saved (:saved r)
                                                                                    :placeholders (count (:placeholders r))})
                                                               (world/prune! dir world/KEEP protect)
                                                               r
                                                               (catch :default ex
                                                                 (.delete (java.io.File. ^String path))
                                                                 (.delete (java.io.File. ^String (world/record-path path)))
                                                                 {:error (str "the world couldn't be recorded: " (or (ex-message ex) (str ex)))})))]
                                                     (on-done r))))))))
        world-save-fn (fn []
                        (if-let [e (world-ev)]
                          (do (set-status! "; Saving the world...")
                              (world-save! e (world-dir!) :save #{} (fn [r] (world-report! (world/save-report r)))))
                          (set-status! "; Save World needs Jolt.")))
        world-restore-from! (fn [path picked?]
                              (if-let [e (world-ev)]
                                (do (set-status! "; Saving what you have, then restoring...")
                                    (world-save! e (world-dir!) :before-restore #{path}
                                                 (fn [s]
                                                   (if (:error s)
                                                     ;; never restore over data that couldn't be kept
                                                     (do (world-report! (world/save-report s))
                                                         (set-status!
                                                                      (str "; Restore World didn't run: what you have now couldn't be saved first. " (:error s))))
                                                     (do
                                                       ;; what went into the safety save as placeholders
                                                       ;; can't come back from it: name it
                                                       (doseq [l (:lines (world/save-report s))]
                                                         (cview/append! cv :system (str l "\n")))
                                                       (ev/world-op! e :restore {:path path}
                                                                     (fn [r]
                                                                       (on-gui! (fn []
                                                                                  (world-report!
                                                                                   (world/restore-report
                                                                                    r {:saved-first? true :picked? picked?
                                                                                       :first-placeholders (count (:placeholders s))})))))))))))
                                (set-status! "; Restore World needs Jolt.")))
        world-restore-fn (fn []
                           (cond
                             (nil? (world-ev)) (set-status! "; Restore World needs Jolt.")
                             :else (if-let [newest (world/newest-save (world/entries (world-dir!)))]
                                     (world-restore-from! (:path newest) false)
                                     (world-report! (world/restore-report {:missing true})))))
        world-restore-earlier-fn (fn []
                                   (let [es (world/entries (world-dir!))
                                         now (System/currentTimeMillis)]
                                     (cond
                                       (nil? (world-ev)) (set-status! "; Restore World needs Jolt.")
                                       (empty? es) (world-report! (world/restore-report {:missing true}))
                                       :else (palette/open! (:palette (active-tab))
                                                            (mapv (fn [x] {:id [:world/restore-from (:path x)]
                                                                           :doc (world/entry-label now x)
                                                                           :chords [] :source "world"})
                                                                  es)))))]
    {:fns
     {:world-save-fn world-save-fn
      :world-restore-fn world-restore-fn
      :world-restore-earlier-fn world-restore-earlier-fn
      :world-restore-from-fn (fn [path] (world-restore-from! path true))}}))

(def part
  "The worlds part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :worlds}))
