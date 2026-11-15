(ns jonquil.key-seq
  "Key sequences: a binding whose :then holds more chords runs its command
  only after each of them is pressed in turn. The first chord matches like
  any binding's; it then starts a sequence, kept in live until the last
  chord arrives, Escape cancels it, or a key that doesn't continue it ends
  it. No GTK here.

  live holds {:pending p :note text}. p is nil, or {:prefix [chord ...]
  :candidates [{:rest [chord ...] :binding b}] :run! f}: the chords pressed
  so far, the bindings they could still be, and the fn that runs the one
  that completes, taken from the handler that saw the first chord. :note is
  what the last step has to say (a cancel, a key that led nowhere), or nil.
  The window shows :pending in the status bar and, after a pause, in a list
  of what can follow."
  (:require [jonquil.keymap :as km]
            [jonquil.palette :as palette]))

(defonce ^{:doc "The live sequence state, {:pending p :note text}."}
  live
  (atom {:pending nil :note nil}))

(def ^:private KEY-ESCAPE (km/key-value "Escape"))

(def ^:private command-modifiers
  (reduce bit-or 0 (map km/MODIFIER-MASKS [:shift :ctrl :alt :meta])))

(defn pending
  "The sequence under way, or nil."
  []
  (:pending @live))

(defn cancel!
  "Ends any sequence under way, saying note (or nothing)."
  ([] (cancel! nil))
  ([note] (reset! live {:pending nil :note note}) nil))

(defn- first-chord
  "binding's first chord, as a binding with no command, condition or :then."
  [b]
  (select-keys b [:key :keys :mods :not :exact :physical?]))

(defn start!
  "A key matched binding b, which has a :then, among bindings (its scope's
  live list): starts a sequence over every binding there with a :then that
  the same key matches. run! (fn [binding]) runs the one that completes.
  key is {:keyval :state :physical}, holds? the scope's condition fn.
  Answers :handled."
  [bindings b {:keys [keyval state physical]} holds? run!]
  (let [same-key (filter #(and (:then %) (km/matches? % keyval state holds? physical)) bindings)]
    (reset! live {:pending {:prefix [(first-chord b)]
                            :candidates (mapv (fn [c] {:rest (vec (:then c)) :binding c}) same-key)
                            :run! run!}
                  :note nil})
    :handled))

(defn- typed-chord
  "The key just pressed as a chord, for naming it: nil for a key Jonquil
  has no name for."
  [keyval state]
  (when-let [n (km/key-name keyval)]
    {:key n :exact (set (for [m [:shift :ctrl :alt :meta]
                              :when (pos? (bit-and state (get km/MODIFIER-MASKS m)))]
                          m))}))

(defn chords-label
  "chords as a person reads them, one after the other."
  [chords os]
  (apply str (interpose " " (map #(palette/chord-label % os) chords))))

(defn step!
  "The next key of the sequence under way: nil when there is none, so the
  caller goes on as usual, else 1 (the key is used up). A lone modifier
  waits. Escape with no modifier cancels. A key that is some candidate's
  next chord goes on, and runs that candidate's command once it was its
  last. Any other key ends the sequence and says it isn't one.
  key is {:keyval :state :physical}; os names the system for the note."
  [{:keys [keyval state physical]} os]
  (when-let [{:keys [prefix candidates run!]} (pending)]
    (cond
      ;; a modifier's own keyval, gdkkeysyms.h Shift_L 0xffe1 to Hyper_R 0xffee
      (<= 0xffe1 keyval 0xffee) 1

      (and (= KEY-ESCAPE keyval) (zero? (bit-and state command-modifiers)))
      (do (cancel! (str (chords-label prefix os) " cancelled.")) 1)

      :else
      (let [hits (filter #(km/matches? (first (:rest %)) keyval state (constantly true) physical) candidates)
            chord (first (:rest (first hits)))
            done (first (filter #(= 1 (count (:rest %))) hits))]
        (cond
          done (do (cancel!) (run! (:binding done)) 1)
          (seq hits) (do (reset! live {:pending {:prefix (conj prefix chord)
                                                 :candidates (mapv #(update % :rest subvec 1) hits)
                                                 :run! run!}
                                       :note nil})
                         1)
          :else (do (cancel! (str (chords-label prefix os) " "
                                  (if-let [c (typed-chord keyval state)] (palette/chord-label c os) "and that key")
                                  " isn't a key sequence."))
                    1))))))

(defn follow-rows
  "What can follow the pending sequence p, for the list: [{:keys label
  :doc text}] in candidate order, a row per next chord. A chord that ends a
  sequence shows its command's description, and one that leads to more
  shows how many, with a mark that it goes on. commands is the registry's
  table, id to {:doc}."
  [{:keys [candidates]} commands os]
  (vec
   (for [[chord cs] (map (juxt key val)
                         (reduce (fn [m c] (update m (first (:rest c)) (fnil conj []) c))
                                 (array-map) candidates))]
     {:keys (palette/chord-label chord os)
      :doc (if (and (= 1 (count cs)) (= 1 (count (:rest (first cs)))))
             (or (:doc (get commands (:command (:binding (first cs))))) "")
             (str (count cs) " more keys…"))})))

(defn status-text
  "What the status bar says while p is pending: the chords so far and a
  mark that more is expected."
  [{:keys [prefix]} os]
  (str (chords-label prefix os) " …"))

(defn list-lines
  "The list of what can follow p, as lines of text for a fixed-width font:
  a heading, a row per next chord with the keys padded to one width, and
  Esc last."
  [{:keys [prefix] :as p} commands os]
  (let [rows (conj (follow-rows p commands os) {:keys "Esc" :doc "Cancel"})
        width (reduce max (map (comp count :keys) rows))]
    (into [(str (chords-label prefix os) ", then:")]
          (for [{:keys [keys doc]} rows]
            (str "  " keys (apply str (repeat (- width (count keys)) " ")) "  " doc)))))
