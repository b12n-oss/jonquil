(ns jonquil.world-ops
  "Save World's and Restore World's steps around the runtime's world op
  (LM7), shared by the window's worlds part and the MCP world tools (UF7):
  a save is written, recorded (which lists it) and the history pruned, or
  dropped when it fails; a restore saves what's there first and never runs
  when that save fails. Callbacks run on the evaluator's thread. No GTK."
  (:require [jonquil.evaluator :as ev]
            [jonquil.world :as world]))

(defn- drop-file! [path] (.delete (java.io.File. ^String path)))

(defn- record-save!
  "Records a finished save r and prunes the history, sparing protect, then
  answers r with :path. A failure deletes the file and answers an :error."
  [r {:keys [path dir at kind protect]}]
  (if (:error r)
    (do (drop-file! path) r)
    (try
      (world/record! path {:at at :kind kind :saved (:saved r)
                           :placeholders (count (:placeholders r))})
      (world/prune! dir world/KEEP protect)
      (assoc r :path path)
      (catch Throwable ex
        (drop-file! path)
        (drop-file! (world/record-path path))
        {:error (str "the world couldn't be recorded: " (or (ex-message ex) (str ex)))}))))

(defn save!
  "One save of ev's data into dir's history as kind (:save, :agent or
  :before-restore), sparing protect when pruning. on-done always runs, with
  the runtime's answer plus :path, or {:error ..} and no file left behind."
  [{:keys [ev dir kind protect]} on-done]
  (let [at (System/currentTimeMillis)
        path (world/free-entry-path dir at)]
    (ev/world-op! ev :save {:path path}
                  (fn [r]
                    (on-done (record-save! r {:path path :dir dir :at at :kind kind
                                              :protect (or protect #{})}))))))

(defn restore!
  "Saves what's there first (kind :before-restore, sparing path), then
  restores path. on-done gets {:first s :restore r}, where s is that first
  save's answer; when it failed there is no :restore and nothing was
  restored, so data that couldn't be kept is never overwritten."
  [{:keys [ev dir path]} on-done]
  (save! {:ev ev :dir dir :kind :before-restore :protect #{path}}
         (fn [s]
           (if (:error s)
             (on-done {:first s})
             (ev/world-op! ev :restore {:path path}
                           (fn [r] (on-done {:first s :restore r})))))))
