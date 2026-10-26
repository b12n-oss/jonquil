(ns jonquil.plugin-hooks
  "Plugin hooks (plugins design, Part 2, slice P6): :after-save, :after-run
  and :on-error. A hook is (fn [ctx event] -> event'); the hooks at a point
  run in plugin-id order on one worker, each getting the event the last
  returned (a non-map return leaves it as it was), so none can change the
  save or Run it watches. One chain per point at a time: an event arriving
  while the last chain at its point is still running waits its turn on
  that chain's worker, up to a bound past which it's skipped, with a line
  saying so. :before-run is slice P6b; nothing here calls it. Pure
  apart from run-hooks!, which reads the live registry and its effects."
  (:require [jonquil.console :as console]
            [jonquil.plugin-registry :as plugin-registry]))

(defn hooks
  "[{:plugin id :effects #{...} :f f}] for each installed plugin with a
  hook fn at point, in plugin-id order."
  [reg point]
  (vec (for [[id {:keys [definition]}] (sort-by key (:plugins reg))
             :let [f (get (:hooks definition) point)]
             :when (fn? f)]
         {:plugin id :effects (set (:effects definition)) :f f})))

(defn run-chain
  "Threads event through hooks, each called with (ctx-for its-effects). A
  throw is reported, naming the plugin and point, and the chain goes on
  with the event as it was. The final event."
  [{:keys [point hooks ctx-for event report!]}]
  (reduce (fn [e {:keys [plugin effects f]}]
            (let [e' (try (f (ctx-for effects) e)
                          (catch Throwable ex
                            (report! (str "plugin " (name plugin) ": " point " hook: "
                                          (or (ex-message ex) (str ex))))
                            e))]
              (if (map? e') e' e)))
          event hooks))

(defn error-location
  "{:line n :column m}, {:line n} or {} from text's \"Location: line N,
  col M\" line (jonquil.eval/format-error's)."
  [text]
  (if-let [[_ line col] (and (string? text) (re-find #"Location: line (\d+)(?:, col (\d+))?" text))]
    (cond-> {:line (parse-long line)} col (assoc :column (parse-long col)))
    {}))

(defn run-event
  "The :after-run event for a Run of kind (:run :selection :form) that
  ended with result."
  [kind {:keys [status printed text] :as result}]
  {:kind kind :status status
   :value (when (= :ok status) printed)
   :error (case status
            :error (console/error-text result)
            :notice text
            nil)})

(defn error-event
  "The :on-error event for an eval from source (:run :selection :form
  :repl), or nil unless result is an :error."
  [source result]
  (when (= :error (:status result))
    (let [text (console/error-text result)]
      (merge {:source source :text text} (error-location text)))))

(def ^:private QUEUE-BOUND
  ;; estimate: well past a Save All of an ordinary window's tabs, small
  ;; enough that a hook that never returns holds little
  16)

;; {point [queued events]}: a point is present while its chain runs
(defonce ^:private running (atom {}))

(defn- chain-once!
  "One event through point's hooks as the registry holds them now."
  [point tab event]
  (let [fx @plugin-registry/effects]
    (run-chain {:point point :hooks (hooks @plugin-registry/live point) :event event
                :report! (or (:report! fx) (fn [_]))
                :ctx-for (fn [effects] (plugin-registry/effect-ctx fx effects tab))})))

(defn- drain!
  "Runs [tab event] through point's hooks, then each event queued meanwhile,
  freeing point once its queue is empty."
  [point first-item]
  (loop [[tab event] first-item]
    (chain-once! point tab event)
    (let [[before _] (swap-vals! running (fn [m] (if (seq (get m point))
                                                   (update m point subvec 1)
                                                   (dissoc m point))))]
      (when-let [next-item (first (get before point))]
        (recur next-item)))))

(defn run-hooks!
  "Starts point's hooks on a future with event, each hook's ctx acting on
  tab, and answers the future. While point's chain is still running the
  event is queued for that same future (nil), up to QUEUE-BOUND; past
  that it's skipped and reported (nil), so a hook that never returns
  holds a bounded number. nil when no hook is installed at point."
  [{:keys [point tab event]}]
  (when (seq (hooks @plugin-registry/live point))
    (let [report! (or (:report! @plugin-registry/effects) (fn [_]))
          [before after] (swap-vals! running
                                     (fn [m]
                                       (cond (not (contains? m point)) (assoc m point [])
                                             (< (count (get m point)) QUEUE-BOUND) (update m point conj [tab event])
                                             :else m)))]
      (cond
        (contains? before point)
        (do (when (= before after)
              (report! (str "the " point " hooks are still busy with " QUEUE-BOUND
                            " earlier events, so this one was skipped")))
            nil)
        :else
        (try
          (future
            (try (drain! point [tab event])
                 (catch Throwable _
                   ;; run-chain reports each hook's throw; this only frees
                   ;; the point if the chain itself failed
                   (swap! running dissoc point))))
          (catch Throwable e
            ;; nothing started, so the point is free again
            (swap! running dissoc point)
            (throw e)))))))

(defn after-eval!
  "Fires the hooks an eval from source that ended with result calls for:
  :on-error for an error, and :after-run for a Run (:run :selection
  :form). Never throws: a point whose hooks can't start is reported, and
  the other is still tried. nil."
  [{:keys [source tab result]}]
  (let [try-point (fn [point event-fn]
                    (try (when-let [e (event-fn)]
                           (run-hooks! {:point point :tab tab :event e}))
                         (catch Throwable ex
                           ((or (:report! @plugin-registry/effects) (fn [_]))
                            (str "the " point " hooks didn't start: " (or (ex-message ex) (str ex)))))))]
    (try-point :on-error #(error-event source result))
    (when (#{:run :selection :form} source)
      (try-point :after-run #(run-event source result)))
    nil))
