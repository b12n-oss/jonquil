(ns jonquil.plugin-hooks
  "Plugin hooks (plugins design, Part 2, slice P6): :after-save, :after-run
  and :on-error. A hook is (fn [ctx event] -> event'); the hooks at a point
  run in plugin-id order on one worker, each getting the event the last
  returned (a non-map return leaves it as it was), so none can change the
  save or Run it watches. One chain per point at a time: an event arriving
  while the last chain at its point is still running waits its turn on
  that chain's worker, up to a bound past which it's skipped, with a line
  saying so. :before-run (slice P6b) is different: the window waits for
  it before a Run, up to a budget, and it may stop the Run, so its ctx has
  no effects. Pure apart from run-hooks! and before-run!, which read the
  live registry and its effects."
  (:require [clojure.string :as str]
            [jonquil.console :as console]
            [jonquil.plugin :as p]
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

;; --- :before-run (P6b) -------------------------------------------------------

(def ^:private BEFORE-RUN-MS
  ;; estimate: about where a delay after a key press starts to show;
  ;; ROADMAP D2 measured a REPL line at 8 ms out of process, 0.3 ms in
  100)

(def ^:private NO-EFFECTS
  ":before-run hooks can't use effects, since the Run waits for them")

(defn run-before-chain
  "Threads event through hooks, each called with ctx, until one answers
  {:abort sentence}: {:plugin id :abort sentence}, or nil when none did.
  Each hook's plugin id goes into the current atom before it's called. A
  throw, or an :abort that isn't a non-blank string, is reported and the
  chain goes on with the event as it was."
  [{:keys [hooks ctx event report! current]}]
  (reduce (fn [e {:keys [plugin f]}]
            (reset! current plugin)
            ;; the answer's shape is checked inside the try too: an odd map
            ;; (a sorted one with string keys) throws on contains? (review I1)
            (let [answer (try (let [e' (f ctx e)]
                                (cond
                                  (not (and (map? e') (contains? e' :abort))) [:next (if (map? e') e' e)]
                                  (and (string? (:abort e')) (not (str/blank? (:abort e')))) [:abort (:abort e')]
                                  :else [:bad-abort]))
                              (catch Throwable ex
                                (report! (str "plugin " (name plugin) ": :before-run hook: "
                                              (or (ex-message ex) (str ex))))
                                [:next e]))]
              (case (first answer)
                :next (second answer)
                :abort (reduced {:plugin plugin :abort (second answer)})
                :bad-abort (do (report! (str "plugin " (name plugin)
                                             ": :before-run's :abort must be a sentence, so the Run went ahead"))
                               e))))
          event hooks))

;; true while a :before-run chain is running, overrun or not
(defonce ^:private before-busy (atom false))

(defn before-run!
  "Asks the :before-run hooks about a Run of kind about to send text from
  path, on tab. nil to let it go ahead, or the line saying which plugin
  stopped it and why. The chain runs on a future this waits for, up to
  BEFORE-RUN-MS; past that, or while an earlier chain that overran is
  still running, the Run goes ahead with a line saying so. GTK thread:
  the ctx has no effects, so nothing in the chain needs a GTK turn."
  [{:keys [kind text path tab]}]
  (let [hs (hooks @plugin-registry/live :before-run)]
    (when (seq hs)
      (let [report! (or (:report! @plugin-registry/effects) (fn [_]))
            [was _] (swap-vals! before-busy (constantly true))]
        (if was
          (do (report! "the :before-run hooks are still running from an earlier Run, so this Run went ahead without them")
              nil)
          (let [current (atom nil)
                ctx {::p/effects #{} ::p/impl {} ::p/tab tab ::p/refused NO-EFFECTS}
                f (try
                    (future
                      (try (run-before-chain {:hooks hs :ctx ctx :report! report! :current current
                                              :event {:kind kind :text text :path path}})
                           (finally (reset! before-busy false))))
                    (catch Throwable e
                      ;; nothing started, so the hooks are free again
                      (reset! before-busy false)
                      (throw e)))
                ;; a chain that failed outright is reported, never thrown
                ;; onto the GTK thread (review I1)
                r (try (deref f BEFORE-RUN-MS ::timeout)
                       (catch Throwable e
                         (let [c (or (ex-cause e) e)]
                           (report! (str "the :before-run hooks failed: " (or (ex-message c) (str c))
                                         ", so the Run went ahead")))
                         nil))]
            (cond
              (= ::timeout r)
              (do (report! (str "the :before-run hooks took longer than " BEFORE-RUN-MS " ms"
                                ;; under load the worker may not have reached a hook yet
                                (when-let [id @current] (str " (plugin " (name id) " was running)"))
                                ", so the Run went ahead"))
                  nil)
              (:abort r) (str "plugin " (name (:plugin r)) " stopped this Run: " (:abort r))
              :else nil)))))))
