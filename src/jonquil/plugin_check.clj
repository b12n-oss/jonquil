(ns jonquil.plugin-check
  "The plugin validator (plugins design, Part 2, the protocol's validate
  step): one plugin file's definitions against a description of Jonquil,
  answered as {:ok? :problems :warnings}, each problem or warning one
  sentence naming the item. Pure, and its request is plain data (the
  registered commands as {id {:scope s}}, the condition ids, the shipped
  keymap, the OS name), so the isolated check can send the same request to
  a throwaway Jolt as EDN and run this same code there. Malformed input
  gets a sentence, never a throw."
  (:require [clojure.string :as str]
            [jonquil.keymap :as km]
            [jonquil.palette :as palette]
            [jonquil.plugin :as p]))

(def EFFECTS
  "The effect vocabulary a plugin declares from; #{} means pure."
  #{:buffer :console :eval :fs :proc :net})

(def HOOKS
  "The hook points a plugin may name in :hooks."
  #{:before-run :after-run :after-save :on-error})

(def ^:private top-keys #{:id :doc :effects :commands :bindings :hooks :mcp-tools ::p/line})

(def ^:private id-pattern #"[a-z][a-z0-9-]*")

(defn request-from-registry
  "The data part of a validate request: commands (id -> a registry entry)
  reduced to {id {:scope s}}, with conditions, keymap and os as given."
  [{:keys [commands conditions keymap os]}]
  {:registered (into {} (map (fn [[id c]] [id {:scope (:scope c)}])) commands)
   :conditions (set conditions) :keymap keymap :os os})

(defn- blank-string? [s] (not (and (string? s) (not (str/blank? s)))))

(defn- definition-problems [d file-id]
  (cond-> []
    (not (and (string? file-id) (re-matches id-pattern file-id)))
    (conj (str "the file name " (pr-str file-id) " isn't a plugin id: lower-case letters, digits and -, "
               "starting with a letter"))
    (seq (remove top-keys (keys d)))
    (conj (str "defplugin has unknown keys " (pr-str (vec (remove top-keys (keys d))))))
    (not (and (keyword? (:id d)) (nil? (namespace (:id d)))))
    (conj (str ":id " (pr-str (:id d)) " isn't a plain keyword"))
    (and (keyword? (:id d)) (nil? (namespace (:id d))) (not= (name (:id d)) file-id))
    (conj (str ":id " (pr-str (:id d)) " doesn't match the file name " (pr-str file-id)))
    (blank-string? (:doc d))
    (conj ":doc isn't a sentence; the palette and the plugin list show it")))

(defn- effects-problems [d]
  (let [e (:effects d)]
    (cond
      (not (contains? d :effects)) ["declares no :effects; write #{} for a pure plugin"]
      (not (set? e)) [(str ":effects " (pr-str e) " isn't a set")]
      (seq (remove EFFECTS e))
      [(str ":effects names " (pr-str (vec (remove EFFECTS e))) ", not one of " (pr-str (sort EFFECTS)))]
      :else [])))

(defn- command-problems [i c plugin-name pure? seen]
  (if-not (map? c)
    [(str "command " i " isn't a map")]
    (let [{:keys [id doc scope edit run]} c
          label (str "command " i " (" (pr-str id) ")")]
      (cond-> []
        (and plugin-name (not (and (keyword? id) (= plugin-name (namespace id)))))
        (conj (str label ": its id isn't :" plugin-name "/<name>"))
        (contains? seen id) (conj (str label ": its id is used twice"))
        (blank-string? doc) (conj (str label ": :doc isn't a sentence"))
        (not (contains? #{:window :editor} scope))
        (conj (str label ": :scope " (pr-str scope) " isn't :window or :editor"))
        (and (some? edit) (some? run)) (conj (str label ": has both :edit and :run"))
        (and (nil? edit) (nil? run)) (conj (str label ": has neither :edit nor :run"))
        (and (some? edit) (not (fn? edit))) (conj (str label ": :edit isn't a function"))
        (and (some? run) (not (fn? run))) (conj (str label ": :run isn't a function"))
        (and (some? edit) (nil? run) (not= :editor scope) (contains? #{:window :editor} scope))
        (conj (str label ": an :edit command acts on the editor, so its :scope is :editor"))
        (and (some? run) (nil? edit) pure?)
        (conj (str label ": a pure plugin's commands are :edit commands; declare the :effects its :run uses"))))))

(defn- commands-problems
  "The file name is the plugin's id (a wrong :id is its own problem), so
  command ids are checked against it, and not at all when it's invalid."
  [d file-id]
  (let [cs (:commands d)
        plugin-name (when (and (string? file-id) (re-matches id-pattern file-id)) file-id)
        pure? (and (set? (:effects d)) (empty? (:effects d)))]
    (cond
      (nil? cs) []
      (not (vector? cs)) [":commands isn't a vector"]
      :else (loop [i 0 seen #{} out []]
              (if (< i (count cs))
                (let [c (nth cs i)]
                  (recur (inc i) (if (map? c) (conj seen (:id c)) seen)
                         (into out (command-problems i c plugin-name pure? seen))))
                out)))))

(defn- hooks-problems [d]
  (let [h (:hooks d)]
    (cond
      (nil? h) []
      (not (map? h)) [":hooks isn't a map of hook point to function"]
      :else (vec (concat
                  (for [k (keys h) :when (not (contains? HOOKS k))]
                    (str ":hooks names " (pr-str k) ", not one of " (pr-str (sort HOOKS))))
                  (for [[k f] h :when (and (contains? HOOKS k) (not (fn? f)))]
                    (str "hook " (pr-str k) " isn't a function")))))))

(defn- mcp-tools-problems [d]
  (if (and (contains? d :mcp-tools) (not (vector? (:mcp-tools d))))
    [":mcp-tools isn't a vector"]
    []))

(def ^:private M km/MODIFIER-MASKS)

(def ^:private states
  ;; every subset of the five modifiers, fewest held first, so the first
  ;; match a message names is the plainest chord
  (sort-by (fn [st] [(Long/bitCount st) (if (zero? (bit-and st (:meta M))) 0 1)])
           (let [bits (vals M)]
             (for [n (range 32)]
               (reduce bit-or 0 (keep-indexed (fn [i b] (when (bit-test n i) b)) bits))))))

(defn- command-chord? [st] (not (zero? (bit-and st (bit-or (:ctrl M) (:meta M))))))

(def ^:private typing-keyvals
  ;; the editor's typing stages: smart enter, Tab and Shift+Tab, Backspace
  ;; across a pair (jonquil.gtk.editor/typing-key!); and below 0x100 every
  ;; printable key, which GTK types
  #{0xff0d 0xff8d 0xff09 0xfe20 0xff08})

(defn- typing-key? [kv] (or (< kv 0x100) (contains? typing-keyvals kv)))

(defn- key-names [b] (if (:key b) [(:key b)] (seq (:keys b))))

(defn- chord-label
  "The chord key name k makes under state st, as a person reads it."
  [k st os]
  (let [held (fn [m] (not (zero? (bit-and st (get M m)))))]
    (palette/chord-label {:key k :exact (cond-> #{}
                                          (held :meta) (conj :cmd)
                                          (held :ctrl) (conj :ctrl)
                                          (held :alt) (conj :alt)
                                          (held :shift) (conj :shift))}
                         os)))

(defn- matching-cells
  "[key-name keyval state] for every key b names and every state b matches,
  its conditions all holding (the widest case)."
  [b]
  (for [k (key-names b) :let [kv (km/key-value k)] :when kv
        st states :when (km/matches? b kv st (constantly true))]
    [k kv st]))

(defn- shipped-command [bindings kv st]
  (:command (km/resolve-binding bindings kv st (constantly true))))

(defn- binding-rules
  "Typing keys and unreachable as problems, shadows as warnings, for
  binding i b in scope (its command's)."
  [i b scope {:keys [keymap os]}]
  (let [cells (matching-cells b)
        label (fn [[k _ st]] (chord-label k st os))
        typing (first (filter (fn [[_ kv st]] (and (typing-key? kv) (not (command-chord? st)))) cells))
        ;; typing first: its fix (hold Cmd/Ctrl) changes which chords match
        unreachable (when (and (= :editor scope) (not typing))
                      (some (fn [[_ kv st :as cell]]
                              (when-let [c (shipped-command (:window keymap) kv st)] [cell c]))
                            cells))
        shadow-scopes (if (= :editor scope) [:editor] [:window :editor])
        shadows (distinct (for [[_ kv st :as cell] cells sc shadow-scopes
                                :let [c (shipped-command (get keymap sc) kv st)] :when c]
                            [c cell]))]
    {:problems (cond-> []
                 typing (conj (str "binding " i " (" (label typing) ") would take a key you type; "
                                   "hold Cmd/Ctrl"))
                 unreachable (conj (str "binding " i " (" (label (first unreachable)) ") can never fire: "
                                        "the window's " (pr-str (second unreachable))
                                        " takes it first; bind it in :window scope")))
     ;; one warning per shadowed command, naming the first chord that takes it
     :warnings (vec (for [[c cell] (reduce (fn [acc [c _ :as x]] (if (some #(= c (first %)) acc) acc (conj acc x)))
                                           [] shadows)]
                      (str "binding " i " (" (label cell) ") takes that chord from the built-in "
                           (pr-str c))))}))

(defn- bindings-check
  "km/problems over the plugin's bindings, then the scope, typing,
  unreachable and shadow rules for each binding km/problems passes."
  [d {:keys [registered conditions] :as request}]
  (let [bs (:bindings d)
        own (into {} (for [c (:commands d) :when (and (map? c) (keyword? (:id c)))] [(:id c) {:scope (:scope c)}]))
        known (merge registered own)
        ids (set (keys known))]
    (cond
      (nil? bs) {:problems [] :warnings []}
      (not (vector? bs)) {:problems [":bindings isn't a vector"] :warnings []}
      :else
      (reduce (fn [acc [i b]]
                (let [ps (when (map? b) (km/problems [b] ids conditions))]
                  (cond
                    (not (map? b)) (update acc :problems conj (str "binding " i " isn't a map"))
                    (seq ps) (update acc :problems into
                                     (map #(str "bindings: " (str/replace-first % "binding 0" (str "binding " i))) ps))
                    (:outcome b) (update acc :problems conj (str "binding " i " has an :outcome; a plugin binding runs a command"))
                    :else
                    (let [scope (:scope (get known (:command b)))]
                      (if-not (contains? #{:window :editor} scope)
                        (update acc :problems conj
                                (str "binding " i " runs " (pr-str (:command b)) ", a " (pr-str scope)
                                     " command, and the " (if (= :console scope) "console" (pr-str scope))
                                     " has no keymap"))
                        (let [r (binding-rules i b scope request)]
                          (-> acc
                              (update :problems into (:problems r))
                              (update :warnings into (:warnings r)))))))))
              {:problems [] :warnings []}
              (map-indexed vector bs)))))

(defn validate
  "One plugin file's {:ok? :problems :warnings}. definitions is what the
  file's defplugin calls recorded; file-id its name without .clj;
  registered {command-id {:scope s}}; conditions the condition ids; keymap
  the shipped {:window [...] :editor [...]}; os the OS name, for chords in
  messages."
  [{:keys [definitions file-id] :as request}]
  (let [n (count definitions)
        d (first definitions)
        checked (when (and (= 1 n) (map? d)) (bindings-check d request))
        problems (cond
                   (zero? n) ["defplugin wasn't called"]
                   (> n 1) [(str "defplugin was called " n " times; a plugin file calls it once")]
                   (not (map? d)) ["defplugin's argument isn't a map"]
                   :else (vec (concat (definition-problems d file-id)
                                      (effects-problems d)
                                      (commands-problems d file-id)
                                      (hooks-problems d)
                                      (mcp-tools-problems d)
                                      (:problems checked))))]
    {:ok? (empty? problems) :problems problems :warnings (vec (:warnings checked))}))
