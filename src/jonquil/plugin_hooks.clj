(ns jonquil.plugin-hooks
  "Plugin hooks (plugins design, Part 2, slice P6): :after-save, :after-run
  and :on-error. A hook is (fn [ctx event] -> event'); the hooks at a point
  run in plugin-id order on one worker, each getting the event the last
  returned (a non-map return leaves it as it was), so none can change the
  save or Run it watches. One chain per point at a time: an event arriving
  while the last chain at its point is still running is skipped, with a
  line saying so. :before-run is slice P6b; nothing here calls it. Pure
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

(defonce ^:private running (atom #{}))

(defn run-hooks!
  "Starts point's hooks on a future with event, each hook's ctx acting on
  tab, and answers the future; nil when no hook is installed at point, or
  when its last chain is still running (reported)."
  [{:keys [point tab event]}]
  (let [hs (hooks @plugin-registry/live point)]
    (when (seq hs)
      (let [fx @plugin-registry/effects
            report! (or (:report! fx) (fn [_]))
            [before _] (swap-vals! running conj point)]
        (if (contains? before point)
          (do (report! (str "the " point " hooks are still running from the last time, so they were skipped this time"))
              nil)
          (try
            (future
              (try (run-chain {:point point :hooks hs :event event :report! report!
                               :ctx-for (fn [effects] (plugin-registry/effect-ctx fx effects tab))})
                   (finally (swap! running disj point))))
            (catch Throwable e
              ;; nothing started, so the point is free again
              (swap! running disj point)
              (throw e))))))))

(defn after-eval!
  "Fires the hooks an eval from source that ended with result calls for:
  :on-error for an error, and :after-run for a Run (:run :selection
  :form). nil."
  [{:keys [source tab result]}]
  (when-let [e (error-event source result)]
    (run-hooks! {:point :on-error :tab tab :event e}))
  (when (#{:run :selection :form} source)
    (run-hooks! {:point :after-run :tab tab :event (run-event source result)}))
  nil)
