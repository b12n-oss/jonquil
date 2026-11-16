(ns jonquil.places
  "Where the window's parts go, as data: :layout {:left [id ...] :right
  [id ...] :bottom [id ...]} in settings.edn names, by part id, what each
  place around the editor holds, in order. The parts are :file-tree, :repl,
  :terminal and the right-hand panels (:inspector, :debugger, :trace and
  the rest). No GTK here; jonquil.gtk.window.layout puts the parts where
  plan says.")

(def places
  "The places around the editor, in the order a part is looked for."
  [:left :right :bottom])

(def usual
  "Where a part goes when :layout doesn't say: the tree on the left, the
  REPL and the terminal at the bottom. Every panel goes on the right."
  {:file-tree :left :repl :bottom :terminal :bottom})

(def fixed-parts
  "The parts that aren't right-hand panels, in their usual order."
  [:file-tree :repl :terminal])

(defn plan
  "Where every part goes for setting (settings.edn's :layout, or nil) when
  panel-ids are the window's right-hand panels, in their order:
  {:left [id ...] :right [id ...] :bottom [id ...] :place-of {id place}
  :skipped [{:id :reason} ...]}. The ids a place names come first, in
  order; every part it leaves out then goes to its usual place. An id that
  isn't a part (:unknown) or that an earlier place already holds (:twice)
  is skipped."
  [setting panel-ids]
  (let [known (set (concat fixed-parts panel-ids))
        step (fn [acc place id]
               (cond
                 (not (known id)) (update acc :skipped conj {:id id :reason :unknown})
                 (contains? (:place-of acc) id) (update acc :skipped conj {:id id :reason :twice})
                 :else (-> acc (update place conj id) (assoc-in [:place-of id] place))))
        named (reduce (fn [acc place] (reduce #(step %1 place %2) acc (get setting place)))
                      {:left [] :right [] :bottom [] :place-of {} :skipped []}
                      places)]
    (reduce (fn [acc id]
              (if (contains? (:place-of acc) id)
                acc
                (let [place (get usual id :right)]
                  (-> acc (update place conj id) (assoc-in [:place-of id] place)))))
            named
            (concat fixed-parts panel-ids))))

(defn skipped-text
  "The console line for a plan's skipped ids, or nil when none were."
  [skipped]
  (when (seq skipped)
    (str "; settings.edn :layout: "
         (apply str (interpose ", " (for [{:keys [id reason]} skipped]
                                      (str (pr-str id)
                                           (if (= :twice reason)
                                             " already has a place"
                                             " isn't a part")))))
         ", left out\n")))

(defn moved
  "settings.edn's :layout setting (or nil) after the part id moves to
  place: id leaves every place's list and goes at the end of place's. A
  list that isn't a vector, and a setting that isn't a map, start again
  empty; every other id and key the setting holds stays."
  [setting id place]
  (let [m (if (map? setting) setting {})]
    (reduce (fn [acc p]
              (let [v (get m p)
                    kept (if (vector? v) (filterv #(not= id %) v) [])]
                (assoc acc p (if (= p place) (conj kept id) kept))))
            m
            places)))

(def ^:private place-names {:left "Left" :right "Right" :bottom "Bottom"})

(defn move-label
  "The tab menu's item for moving a part to place."
  [place]
  (str "Move to " (place-names place)))
