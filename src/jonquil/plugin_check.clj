(ns jonquil.plugin-check
  "The plugin validator (the protocol's validate
  step): one plugin file's definitions against a description of Jonquil,
  answered as {:ok? :problems :warnings}, each problem or warning one
  sentence naming the item. Pure, and its request is plain data (the
  registered commands as {id {:scope s :passes? b}}, the condition ids, the shipped
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
  reduced to {id {:scope s :passes? b}} (:passes? marks a command that can
  hand the key on, answering :next or :to-gtk), with conditions, keymap and
  os as given."
  [{:keys [commands conditions keymap os]}]
  {:registered (into {} (map (fn [[id c]] [id (cond-> {:scope (:scope c) :passes? (true? (:passes? c))}
                                                (:plugin c) (assoc :plugin (:plugin c)))]))
                     commands)
   ;; a set (any scope), or {:window #{...} :editor #{...}}, so a binding's
   ;; :when is checked against its own scope's conditions
   :conditions (if (map? conditions) conditions (set conditions)) :keymap keymap :os os})

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

(defn- command-identity-problems
  "The id, :doc and :scope findings for command c, labelled."
  [label {:keys [id doc scope]} {:keys [plugin-name seen]}]
  (cond-> []
    (and plugin-name (not (and (keyword? id) (= plugin-name (namespace id)))))
    (conj (str label ": its id isn't :" plugin-name "/<name>"))
    (contains? seen id) (conj (str label ": its id is used twice"))
    (blank-string? doc) (conj (str label ": :doc isn't a sentence"))
    (not (contains? #{:window :editor} scope))
    (conj (str label ": :scope " (pr-str scope) " isn't :window or :editor"))))

(defn- command-body-problems
  "The :edit and :run presence and type findings for command c, labelled."
  [label {:keys [edit run]}]
  (cond-> []
    (and (some? edit) (some? run)) (conj (str label ": has both :edit and :run"))
    (and (nil? edit) (nil? run)) (conj (str label ": has neither :edit nor :run"))
    (and (some? edit) (not (fn? edit))) (conj (str label ": :edit isn't a function"))
    (and (some? run) (not (fn? run))) (conj (str label ": :run isn't a function"))))

(defn- command-kind-problems
  "The findings where command c's :edit or :run disagrees with its :scope
  or with a pure plugin."
  [label {:keys [scope edit run]} pure?]
  (cond-> []
    (and (some? edit) (nil? run) (not= :editor scope) (contains? #{:window :editor} scope))
    (conj (str label ": an :edit command acts on the editor, so its :scope is :editor"))
    (and (some? run) (nil? edit) pure?)
    (conj (str label ": a pure plugin's commands are :edit commands; declare the :effects its :run uses"))))

(defn- command-problems [i c plugin-name pure? seen]
  (if-not (map? c)
    [(str "command " i " isn't a map")]
    (let [label (str "command " i " (" (pr-str (:id c)) ")")]
      (-> []
          (into (command-identity-problems label c {:plugin-name plugin-name :seen seen}))
          (into (command-body-problems label c))
          (into (command-kind-problems label c pure?))))))

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

(def MCP-ARG-TYPES
  "The JSON types a plugin tool's argument may name in :type."
  #{"string" "integer" "number" "boolean"})

(def MCP-NAME-MAX
  ;; Measured 2026-10-28 on Claude Code
  ;; 2.1.311: Claude Code called tools whose full mcp__jonquil__ names were
  ;; 64, 65 and 70 characters, so 50 isn't its limit. It stays as headroom
  ;; for a client that hands names straight to an API with a 64-character
  ;; limit (^[a-zA-Z0-9_-]{1,64}$, as remembered), less that 14-character
  ;; prefix
  50)

(def ^:private arg-name-pattern
  ;; What the API accepts as a schema property key, as
  ;; remembered (not measured), so one plugin row can't break a client's
  ;; whole tool list
  #"[a-zA-Z0-9_.-]{1,64}")

(def ^:private tool-name-pattern #"[a-z][a-z0-9_]*")
(def ^:private tool-keys #{:name :doc :args :run})
(def ^:private arg-keys #{:type :doc :required?})

(defn served-tool-name
  "The name plugin file-id's tool row name is served under over MCP."
  [file-id name]
  (str "plugin_" file-id "_" name))

(defn- arg-problems [label k a]
  (if-not (and (string? k) (re-matches arg-name-pattern k))
    [(str label ": argument " (pr-str k) " isn't a string of letters, digits, _, . or -, 1 to 64 long")]
    (let [al (str label ": argument " k)]
      (if-not (map? a)
        [(str al " isn't a map of :type :doc :required?")]
        (cond-> []
          (not (contains? MCP-ARG-TYPES (:type a)))
          (conj (str al "'s :type " (pr-str (:type a)) " isn't one of " (pr-str (vec (sort MCP-ARG-TYPES)))))
          (seq (remove arg-keys (keys a))) (conj (str al " has unknown keys " (pr-str (vec (remove arg-keys (keys a))))))
          (blank-string? (:doc a)) (conj (str al "'s :doc isn't a sentence"))
          (and (contains? a :required?) (not (boolean? (:required? a))))
          (conj (str al "'s :required? isn't true or false")))))))

(defn- tool-problems [i t file-id seen]
  (if-not (map? t)
    [(str "mcp tool " i " isn't a map")]
    (let [{:keys [name doc args run]} t
          label (str "mcp tool " i " (" (pr-str name) ")")
          good-name? (and (string? name) (re-matches tool-name-pattern name))
          served (when (and good-name? (string? file-id)) (served-tool-name file-id name))]
      (cond-> []
        (not good-name?) (conj (str label ": :name isn't lower-case letters, digits and _, starting with a letter"))
        (contains? seen name) (conj (str label ": its name is used twice"))
        (and served (> (count served) MCP-NAME-MAX))
        (conj (str label ": its served name " served " is longer than " MCP-NAME-MAX " characters"))
        (seq (remove tool-keys (keys t))) (conj (str label ": has unknown keys " (pr-str (vec (remove tool-keys (keys t))))))
        (blank-string? doc) (conj (str label ": :doc isn't a sentence"))
        (and (some? args) (not (map? args)))
        (conj (str label ": :args isn't a map of argument name to {:type :doc :required?}"))
        (map? args) (into (mapcat (fn [[k a]] (arg-problems label k a)) args))
        (not (fn? run)) (conj (str label ": :run isn't a function"))))))

(defn- mcp-tools-problems
  "Each :mcp-tools row's problems. file-id names the served
  tools, so their length is checked against it."
  [d file-id]
  (let [ts (:mcp-tools d)]
    (cond
      (not (contains? d :mcp-tools)) []
      (not (vector? ts)) [":mcp-tools isn't a vector"]
      :else (loop [i 0 seen #{} out []]
              (if (< i (count ts))
                (let [t (nth ts i)]
                  (recur (inc i) (if (map? t) (conj seen (:name t)) seen)
                         (into out (tool-problems i t file-id seen))))
                out)))))

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
  ;; printable key, which GTK types. Tab, ISO_Left_Tab and BackSpace have no
  ;; key name a binding can use today (jonquil.keymap/key-value); they're
  ;; here so naming them later can't open a hole
  #{0xff0d 0xff8d 0xff09 0xfe20 0xff08})

(defn- typing-key? [kv] (or (< kv 0x100) (contains? typing-keyvals kv)))

(defn- key-names [b] (if (:key b) [(:key b)] (seq (:keys b))))

(defn- chord-label
  "The chord key name k makes under state st, as a person reads it."
  [k st os]
  (let [held (fn [m] (not (zero? (bit-and st (get M m)))))]
    ;; Meta is Cmd on macOS; elsewhere it's its own key, as is Super
    (palette/chord-label {:key k :exact (cond-> #{}
                                          (held :meta) (conj (if (= "Mac OS X" os) :cmd :meta))
                                          (held :super) (conj :super)
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

(defn- walk
  "Who takes key kv under state st among bindings, walking the matching
  ones in order as jonquil.keymap/dispatch does: [[how b] ...], how
  :sometimes for a binding that takes it only in some states (a :when
  guard, or a command registered :passes?, which can hand it on) and
  :always for the first that takes it whenever it's reached, after which
  nothing later is reached. A fixed :to-gtk ends the walk (the key goes on
  to GTK); a guarded one, or a passing command before the :always one,
  means the key can get past, so that one is only :sometimes."
  [bindings kv st registered]
  (loop [[b & more] (filter #(km/matches? % kv st (constantly true)) bindings)
         out [] escape? false]
    (let [guarded? (seq (:when b))
          passes? (:passes? (get registered (:command b)))]
      (cond
        (nil? b) out
        (= :to-gtk (:outcome b)) (if guarded? (recur more out true) out)
        (or guarded? passes?) (recur more (conj out [:sometimes b]) (or escape? (boolean passes?)))
        :else (conj out [(if escape? :sometimes :always) b])))))

(defn- earlier? [b] (some? (:plugin/source b)))

(defn- who [b]
  (if (earlier? b)
    (str "plugin " (name (:plugin/source b)) "'s binding of " (pr-str (or (:command b) :outcome/handled)))
    (str "the built-in " (pr-str (or (:command b) :outcome/handled)))))

(defn- before-findings
  "Findings [kind binding] for the bindings a walk found ahead of b."
  [taken]
  (mapv (fn [[how tb]]
          [(if (= :always how) (if (earlier? tb) :dead :unreachable) :sometimes) tb])
        taken))

(defn- always-taken? [taken] (some #(= :always (first %)) taken))

(defn- editor-cell-findings
  "cell-findings for an :editor binding: the window first, all of it; then
  the earlier plugins' editor bindings; then the built-in editor binding b
  takes over."
  [{:keys [win ed walk-of first-match]}]
  ;; the window first, all of it; then the earlier plugins' editor
  ;; bindings; then the built-in editor binding b takes over
  (let [w (walk-of win)
        ;; an earlier plugin's always-take can hide a built-in that would
        ;; always take it too; that's still this binding's problem, or it
        ;; would load or not depending on an unrelated plugin
        hidden (when (some (fn [[how tb]] (and (= :always how) (earlier? tb))) w)
                 (some (fn [[how tb]] (when (= :always how) [:unreachable tb]))
                       (walk-of (remove earlier? win))))]
    (if (always-taken? w)
      (cond-> (before-findings w) hidden (conj hidden))
      (let [e (walk-of (filter earlier? ed))]
        (into (into (before-findings w) (before-findings e))
              (when-not (always-taken? e)
                (when-let [tb (first-match (remove earlier? ed))] [[:takes tb]])))))))

(defn- window-cell-findings
  "cell-findings for a :window binding: the earlier plugins' window bindings
  first; then b takes the key from the built-in window binding and from any
  editor one."
  [{:keys [win ed walk-of first-match]}]
  ;; :window: the earlier plugins' window bindings first; then b takes
  ;; the key from the built-in window binding and from any editor one
  (let [w (walk-of (filter earlier? win))]
    (into (before-findings w)
          (when-not (always-taken? w)
            (keep (fn [tb] (when tb [:takes tb]))
                  [(first-match (remove earlier? win)) (first-match ed)])))))

(defn- cell-findings
  "What happens to binding b (in scope) on one key cell, as findings
  [kind binding]: :unreachable (a built-in in the window always takes it
  first: a problem), :dead (an earlier plugin's binding always takes it
  first: never fires), :sometimes (another binding takes it in some
  states), :takes (b takes it from another binding). keymap's scopes hold
  the earlier plugins' bindings (tagged :plugin/source) ahead of the
  shipped ones, which is where b sits too: after the earlier plugins, ahead
  of the built-ins. The window sees every key before the editor."
  [scope {:keys [keymap registered]} kv st]
  (let [ctx {:win (:window keymap) :ed (:editor keymap)
             :walk-of (fn [bs] (walk bs kv st registered))
             :first-match (fn [bs] (first (filter #(and (:command %) (km/matches? % kv st (constantly true))) bs)))}]
    (if (= :editor scope)
      (editor-cell-findings ctx)
      (window-cell-findings ctx))))

(defn- lost-cell?
  "Whether a cell's findings fs say a built-in always takes it first."
  [fs]
  (some #(= :unreachable (first %)) fs))

(defn- cell-results
  "[cell findings] for each key cell, with the :sometimes findings dropped
  on a cell a built-in always takes first."
  [cells scope request]
  (for [[_ kv st :as cell] cells
        :let [fs (cell-findings scope request kv st)]]
    [cell (if (lost-cell? fs) (remove #(= :sometimes (first %)) fs) fs)]))

(defn- distinct-findings
  "One [key kind binding cell] per distinct [kind binding] over
  per-cell, at the first cell it occurs on. :unreachable becomes :partly
  unless every cell is lost."
  [per-cell]
  (let [all-lost? (and (seq per-cell) (every? (comp lost-cell? second) per-cell))]
    (reduce (fn [acc [cell fs]]
              (reduce (fn [acc [kind tb]]
                        (let [kind (if (and (= :unreachable kind) (not all-lost?)) :partly kind)
                              k [kind (:command tb) (:plugin/source tb)]]
                          (if (some #(= k (first %)) acc) acc (conj acc [k kind tb cell]))))
                      acc fs))
            [] per-cell)))

(defn- finding-message
  "binding i's sentence for one distinct finding, on the chord named
  chord."
  [i chord [_ kind tb _]]
  (let [at (str "binding " i " (" chord ") ")]
    (case kind
      :unreachable (str at "can never fire: the window's " (pr-str (:command tb))
                        " takes it first; bind it in :window scope")
      :partly (str at "doesn't fire on that chord: the window's " (pr-str (:command tb))
                   " takes it first; the binding's other chords work")
      :dead (str at "never fires: " (who tb) " takes that chord first, and "
                 (name (:plugin/source tb)) " loads first")
      :sometimes (str at "takes that chord only when " (who tb) " doesn't")
      :takes (str at "takes that chord from " (who tb)))))

(defn- binding-rules
  "Typing keys and unreachable as problems, the rest as warnings, for
  binding i b in scope (its command's), over every key cell b matches: a
  typing key with neither Ctrl nor Meta held is a problem; then
  cell-findings. A built-in always taking the chord first is a problem
  only when that's so on every cell b matches; when b still fires on some
  (:mods matches extra held modifiers, so Cmd+P also matches Cmd+Shift+P,
  the palette's), it's a warning naming the chord b loses. On a cell b
  loses, the :sometimes findings there are dropped. An earlier plugin's
  binding always taking the chord is a warning that b never fires. One
  warning per distinct [kind binding], naming the first chord it occurs on."
  [i b scope {:keys [os] :as request}]
  (let [cells (matching-cells b)
        label (fn [[k _ st]] (chord-label k st os))
        typing (first (filter (fn [[_ kv st]] (and (typing-key? kv) (not (command-chord? st)))) cells))
        findings (when-not typing (distinct-findings (cell-results cells scope request)))
        msg #(finding-message i (label (nth % 3)) %)]
    {:problems (cond-> []
                 typing (conj (str "binding " i " (" (label typing) ") would take a key you type; "
                                   "hold Cmd/Ctrl"))
                 true (into (map msg (filter #(= :unreachable (second %)) findings))))
     :warnings (mapv msg (remove #(= :unreachable (second %)) findings))}))

(defn- own-commands
  "{id {:scope s :own? true}} for the plugin's own well-formed commands."
  [d]
  (into {} (for [c (when (vector? (:commands d)) (:commands d))
                 :when (and (map? c) (keyword? (:id c)))]
             [(:id c) {:scope (:scope c) :own? true}])))

(defn- binding-conditions
  "The condition ids a binding in scope-of may name, from the request's
  :conditions (a set, or a map per scope, or nothing)."
  [conditions scope-of]
  (cond
    (map? conditions) (set (if (contains? conditions scope-of)
                             (get conditions scope-of)
                             (apply concat (vals conditions))))
    (coll? conditions) (set conditions)
    :else #{}))

(defn- binding-parse-problems
  "km/problems for binding i b, worded for the plugin; nil when b is a map
  that parses."
  [i b {:keys [known ids conditions]}]
  (let [scope-of (:scope (get known (:command b)))
        ps (km/problems [b] ids (binding-conditions conditions scope-of))]
    (when (seq ps)
      (map #(str "bindings: " (str/replace-first % "binding 0" (str "binding " i))) ps))))

(defn- scoped-binding-check
  "acc with binding i b's scope, typing, unreachable and shadow findings,
  once b parses."
  [acc [i b] {:keys [request known]}]
  (let [{:keys [scope own?]} (get known (:command b))]
    (cond
      ;; a plugin command with a bad :scope is reported once, with the command
      (and own? (not (contains? #{:window :editor} scope))) acc
      (not (contains? #{:window :editor} scope))
      (update acc :problems conj
              (str "binding " i " runs " (pr-str (:command b)) ", a " (pr-str scope)
                   " command, and the " (if (= :console scope) "console" (pr-str scope))
                   " has no keymap"))
      :else
      (let [r (binding-rules i b scope request)]
        (-> acc
            (update :problems into (:problems r))
            (update :warnings into (:warnings r)))))))

(defn- binding-check
  "acc with the findings for binding i b."
  [acc [i b] ctx]
  (let [ps (when (map? b) (binding-parse-problems i b ctx))]
    (cond
      (not (map? b)) (update acc :problems conj (str "binding " i " isn't a map"))
      (seq ps) (update acc :problems into ps)
      (:outcome b) (update acc :problems conj (str "binding " i " has an :outcome; a plugin binding runs a command"))
      :else (scoped-binding-check acc [i b] ctx))))

(defn- bindings-check
  "km/problems over the plugin's bindings, then the scope, typing,
  unreachable and shadow rules for each binding km/problems passes."
  [d {:keys [registered conditions] :as request}]
  (let [bs (:bindings d)
        known (merge registered (own-commands d))
        ctx {:request request :known known :ids (set (keys known)) :conditions conditions}]
    (cond
      (nil? bs) {:problems [] :warnings []}
      (not (vector? bs)) {:problems [":bindings isn't a vector"] :warnings []}
      :else
      (reduce (fn [acc ib] (binding-check acc ib ctx))
              {:problems [] :warnings []}
              (map-indexed vector bs)))))

(defn validate
  "One plugin file's {:ok? :problems :warnings}. definitions is what the
  file's defplugin calls recorded; file-id its name without .clj;
  registered {command-id {:scope s :passes? b}}; conditions the condition ids; keymap
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
                                      (mcp-tools-problems d file-id)
                                      (:problems checked))))]
    {:ok? (empty? problems) :problems problems :warnings (vec (:warnings checked))}))
