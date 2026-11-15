(ns jonquil.header
  "The header bar's buttons as data: :header {:start [id ...] :end [id ...]}
  in settings.edn names, by command id, the buttons at each end of the
  header bar, left to right. No GTK here; jonquil.gtk.window.layout packs
  what plan answers.")

(def default
  "The header bar Jonquil ships: Files, Open and + at the start, Run, Stop
  and Save at the end."
  {:start [:tree/toggle :file/open :tab/new]
   :end [:run/run :run/stop :file/save]})

(def built-in
  "The commands whose buttons the window already makes, by the window's key
  for each widget. Every other command gets a plain button that runs it."
  {:tree/toggle :files-btn
   :file/open :open-btn
   :tab/new :new-tab-btn
   :run/run :run-btn
   :run/stop :stop-btn
   :file/save :save-btn})

(defn- menu-label
  "The label menus give command id, or nil when no menu has it."
  [menu-items id]
  (some #(when (= id (:command %)) (:label %)) menu-items))

(defn- button
  "One button of the plan: {:command :widget} for a built-in one, else
  {:command :label :tooltip}."
  [commands menu-items id]
  (if-let [k (built-in id)]
    {:command id :widget k}
    {:command id
     :label (or (menu-label menu-items id) (name id))
     :tooltip (:doc (get commands id))}))

(defn plan
  "The header bar to build from setting (settings.edn's :header, or nil):
  {:start [button ...] :end [button ...] :skipped [{:command :reason} ...]}.
  A side the setting leaves out keeps the default's buttons. An id that
  isn't a command in commands, or that an earlier place already shows, is
  skipped with its reason. menu-items are the menus' command items, which
  give a new button its label."
  [setting commands menu-items]
  (let [wanted (merge default (select-keys setting [:start :end]))
        step (fn [{:keys [seen] :as acc} side id]
               (cond
                 (not (contains? commands id))
                 (update acc :skipped conj {:command id :reason :unknown})
                 (contains? seen id)
                 (update acc :skipped conj {:command id :reason :twice})
                 :else
                 (-> acc
                     (update side conj (button commands menu-items id))
                     (update :seen conj id))))
        acc (reduce (fn [acc [side id]] (step acc side id))
                    {:start [] :end [] :skipped [] :seen #{}}
                    (concat (map #(vector :start %) (:start wanted))
                            (map #(vector :end %) (:end wanted))))]
    (dissoc acc :seen)))

(defn skipped-text
  "The console line for a plan's skipped ids, or nil when none were."
  [skipped]
  (when (seq skipped)
    (str "; settings.edn :header: "
         (apply str (interpose ", " (for [{:keys [command reason]} skipped]
                                      (str (pr-str command)
                                           (if (= :twice reason)
                                             " is already in the header"
                                             " isn't a command")))))
         ", left out\n")))
