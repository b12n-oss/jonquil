(ns jonquil.plugin-review
  "What the review panel shows for an agent's pending proposal (plugins
  P9b): summary lines (where it stands against live, the effects with new
  ones marked, the check's warnings and soak timings, all as the check
  reported them) and a line diff against live. Pure except
  pending-reviews, which reads the history; no GTK."
  (:require [clojure.string :as str]
            [jonquil.plugin-check :as plugin-check]
            [jonquil.plugin-history :as history]))

(def DIFF-MAX-CELLS
  ;; MEASURED (jolt v0.8.23-47): the LCS table takes 39 ms at 300 x 300
  ;; lines and 462 ms at 1000 x 1000. Past this many cells the diff is the
  ;; whole old file removed and the whole new one added.
  1000000)

(def CONTEXT
  ;; unchanged lines kept on each side of a change
  3)

(defn- text-lines [s]
  (if (empty? s) [] (str/split-lines s)))

(defn line-diff
  "The line diff from old-text to new-text (nil is no text): a vector of
  [:same|:del|:add line], a change's removed lines before its added ones."
  [old-text new-text]
  (let [a (vec (text-lines old-text))
        b (vec (text-lines new-text))
        n (count a)
        m (count b)]
    (if (> (* n m) DIFF-MAX-CELLS)
      (into (mapv #(vector :del %) a) (map #(vector :add %) b))
      (let [t (make-array Long/TYPE (inc n) (inc m))]
        (doseq [i (range (dec n) -1 -1) j (range (dec m) -1 -1)]
          (aset t i j (if (= (nth a i) (nth b j))
                        (inc (aget t (inc i) (inc j)))
                        (max (aget t (inc i) j) (aget t i (inc j))))))
        (loop [i 0 j 0 out []]
          (cond
            (and (< i n) (< j m) (= (nth a i) (nth b j))) (recur (inc i) (inc j) (conj out [:same (nth a i)]))
            (and (< i n) (or (= j m) (>= (aget t (inc i) j) (aget t i (inc j)))))
            (recur (inc i) j (conj out [:del (nth a i)]))
            (< j m) (recur i (inc j) (conj out [:add (nth b j)]))
            :else out))))))

(defn diff-text
  "line-diff as text, lines prefixed \"+ \", \"- \" or two spaces. An
  unchanged line more than CONTEXT lines from a change is dropped, and each
  run of dropped lines is one \"  … <k> unchanged lines\" (a run of one is
  kept). \"No change from live.\" when nothing changed."
  [old-text new-text]
  (let [ops (line-diff old-text new-text)
        changed (keep-indexed (fn [i [k]] (when (not= :same k) i)) ops)]
    (if (empty? changed)
      "No change from live."
      (let [near? (fn [i] (some #(<= (Math/abs (long (- i %))) CONTEXT) changed))
            render (fn [[k l]] (str (case k :same "  " :del "- " :add "+ ") l))]
        (->> (map-indexed vector ops)
             (partition-by (fn [[i _]] (boolean (near? i))))
             (mapcat (fn [run]
                       (if (or (near? (ffirst run)) (= 1 (count run)))
                         (map (comp render second) run)
                         [(str "  … " (count run) " unchanged lines")])))
             (str/join "\n"))))))

(defn- standing
  "Where the proposal stands against live."
  [based-on live-n]
  (cond
    (nil? live-n) "A new plugin, so the whole file is shown."
    (= based-on live-n) (str "Based on v" live-n ", the live version.")
    :else (str "Based on " (if based-on (str "v" based-on) "no version") ", live is v" live-n
               ": the diff is against live.")))

(defn review
  "The review of a pending proposal entry for plugin id (a string), given
  its source (nil when missing from the history), the live version's
  number, source and declared effects, and the live definition's MCP tool
  names (each nil when there's none): {:id :n :lines [...] :diff text}."
  [{:keys [id proposal source live-n live-source live-effects live-tools]}]
  (let [{:keys [n reason based-on check]} proposal
        {:keys [unchecked? plugin warnings timings]} check
        live-effects (set live-effects)]
    {:id id :n n
     :diff (if source (diff-text live-source source) "")
     :lines (-> (cond-> []
                  unchecked? (conj "Unchecked: there was no jolt to check it in, so its checks run when you press Accept."))
                (conj (str id " v" n ", proposed by an agent: " reason))
                (cond-> (nil? source) (conj "Its source is missing from its history, so it can't be accepted."))
                (conj (standing based-on live-n))
                (conj (if unchecked?
                        "Effects: not known until its checks run at Accept."
                        (str "Effects, as its check reported: "
                             (if (empty? (:effects plugin))
                               "none"
                               (str/join ", " (for [e (sort-by str (:effects plugin))]
                                                (str e (when-not (contains? live-effects e) " (new)"))))))))
                ;; plugins P10b: the tools an agent could call once it's live
                (cond-> (and (not unchecked?) (seq (:mcp-tools plugin)))
                  (conj (str "MCP tools, as its check reported: "
                             (str/join ", " (for [{:keys [name]} (:mcp-tools plugin)]
                                              (str (plugin-check/served-tool-name id name)
                                                   (when-not (contains? (set live-tools) name) " (new)")))))))
                (cond-> (and (not unchecked?) (seq warnings))
                  (conj (str "Warnings, as its check reported: " (str/join "; " warnings))))
                (cond-> (and (not unchecked?) (seq timings))
                  (conj (str "Soak timings, as its check reported: "
                             (str/join ", " (for [[k ms] (sort-by (comp str key) timings)] (str k " " ms " ms"))))))
                (conj "Pressing Accept runs this code in Jonquil."))}))

(defn summary-text
  "review's lines as one text, ending \"Proposal <i> of <count>.\" when
  more than one is pending (index is 0-based)."
  [review index count]
  (str/join "\n" (cond-> (:lines review)
                   (> count 1) (conj (str "Proposal " (inc index) " of " count ".")))))

(defn pending-reviews
  "The review of every pending proposal under plugins-dir, sorted by id.
  Live is registry's source and declared effects when the plugin is
  loaded, else the latest accepted version's source (and no effects, so
  every one is marked new). A proposal whose review can't be built (a
  forged check result, say) is a short review saying why, so it's still
  counted and can be rejected. Never throws."
  [{:keys [plugins-dir registry]}]
  (try
    (vec (keep (fn [{:keys [id] :as proposal}]
                 (try
                   (let [hdir (history/history-dir plugins-dir id)
                         {:keys [entries error]} (history/read-entries hdir)
                         live-n (:n (history/latest-accepted entries))
                         loaded (get-in registry [:plugins (keyword id)])]
                     (when-not error
                       (review {:id id :proposal (dissoc proposal :id)
                                :source (history/version-source hdir (:n proposal))
                                :live-n live-n
                                :live-source (or (:source loaded) (when live-n (history/version-source hdir live-n)))
                                :live-effects (get-in loaded [:definition :effects])
                                :live-tools (map :name (get-in loaded [:definition :mcp-tools]))})))
                   (catch Throwable e
                     {:id id :n (:n proposal) :diff ""
                      :lines [(str id " v" (:n proposal) ": its review couldn't be built: " (or (ex-message e) (str e)))
                              "Pressing Accept runs this code in Jonquil."]})))
               (history/all-pending plugins-dir)))
    (catch Throwable _ [])))
