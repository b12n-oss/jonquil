(ns jonquil.help
  "The decisions behind P4's help features, pure. Snapshots are the editor's
  {:text :caret :sel-start :sel-end}; edit results are the
  {:edits [[s e r]] :caret n :sel nil} shape jonquil.gtk.editor/apply-op!
  takes. Lookups that depend on the running program (candidates, docs)
  happen in the GTK layer through jonquil.docs; the results are passed in
  here as plain data."
  (:require [clojure.string :as str]
            [jonquil.edit :as edit]
            [jonquil.syntax :as syntax]))

;; --- completion ------------------------------------------------------------

(defn prefix-at
  "The word prefix before the caret, as syntax/get-symbol-prefix-at-pos
  returns it ({:prefix :start :end :word-end}), or nil when there is a
  selection or the prefix is empty (Ctrl+Space then does nothing)."
  [{:keys [text caret sel-start sel-end]}]
  (when (= sel-start sel-end)
    (let [p (syntax/get-symbol-prefix-at-pos text caret)]
      (when-not (str/blank? (:prefix p)) p))))

(defn open-state
  "The popup state for prefix-info p and the candidate vector (maps with
  :symbol, as syntax/get-autocomplete-candidates returns them), or nil
  when there are none. :single? is true for exactly one candidate, which
  the front-end commits at once."
  [p candidates]
  (when (and p (seq candidates))
    {:prefix-info p
     :candidates (vec candidates)
     :index 0
     :single? (= 1 (count candidates))}))

(defn move
  "Moves the selection by delta (negative is up), clamped to the list."
  [state delta]
  (let [n (count (:candidates state))]
    (assoc state :index (max 0 (min (dec n) (+ (:index state) delta))))))

(defn selected
  "The selected candidate map."
  [state]
  (nth (:candidates state) (:index state)))

(defn commit-op
  "The edit that inserts the selected candidate: it replaces the whole word
  around the caret (prefix start to word end), and the caret lands after
  the inserted symbol."
  [state]
  (let [{:keys [start word-end]} (:prefix-info state)
        sym (:symbol (selected state))]
    {:edits [[start word-end sym]]
     :caret (+ start (count sym))
     :sel nil}))

(defn still-valid?
  "Whether an open popup should stay open for the new snapshot: the caret
  is still in a word that starts where the popup's prefix started."
  [state snapshot]
  (let [p (prefix-at snapshot)]
    (boolean (and p (= (:start p) (get-in state [:prefix-info :start]))))))

(defn refine
  "The popup state after the text changed: re-opened on the new prefix with
  the new candidate vector, or nil (close) when it no longer applies."
  [state snapshot candidates]
  (when (still-valid? state snapshot)
    (open-state (prefix-at snapshot) candidates)))

;; --- the symbol quick doc, jump and rename act on ---------------------------

(defn target
  "The symbol quick doc, jump and rename act on, as {:symbol s :start n :end n}:
  the selection when it is exactly one symbol, else the symbol at or around
  the caret. nil when there is none (for example inside a comment or a
  string)."
  [{:keys [text caret sel-start sel-end]}]
  (if (< sel-start sel-end)
    (let [at (syntax/symbol-at-pos text sel-start)]
      (when (and at (= (:start at) sel-start) (= (:end at) sel-end))
        (select-keys at [:symbol :start :end])))
    (some-> (syntax/symbol-at-pos text caret) (select-keys [:symbol :start :end]))))

(defn target-symbol
  "The target's symbol string, or nil."
  [snapshot]
  (:symbol (target snapshot)))

(defn doc-text
  "The quick-doc text for a jonquil.docs/symbol-doc result, using upstream's
  formatter; a nil result reads as not found."
  [sym doc-info]
  (syntax/format-autocomplete-doc (or doc-info {:status :not-found :symbol sym})
                                  {:symbol sym}))

;; --- jump to definition: scope-aware local search --------------------------
;;
;; syntax/find-definition (lifted, unchanged) checks top-level def* forms
;; first and only then a scope-less "last earlier token with this text" --
;; wrong for a shadowed name (a param or a let hides a same-named top-level
;; def) and for a plain unbound usage (it lands on an earlier USE, not a
;; definition). The fix lives here, not there: a real scope search first,
;; then find-definition called with no pos at all, which runs only its
;; top-level def* half.
;;
;; The search walks the '(' forms enclosing the target's start, innermost
;; first, and for each one collects the names it binds: let/loop/binding/
;; when-let/when-some/when-first/if-let/if-some's own bindings vector (fix
;; wave item 1b -- if-let/if-some's own binding only reaches their THEN
;; branch, via scoped-for?, below), for/doseq's own bindings vector,
;; fn/defn/defn-/defmacro params (every arity, and only a REAL arity
;; clause -- fix wave item 1a's bare-arity-clause? -- never quoted or
;; literal data that merely looks like one), and letfn's names. Vector and
;; map destructuring patterns are unpacked recursively (:keys/:strs/:syms/
;; :as, & for a vector's rest); :or is skipped, since its defaults
;; reference already-bound names rather than introducing new ones. Not
;; covered (see the fixwave report): for's own :let clause, and any other
;; macro that introduces bindings under a different shape.

;; Fix wave item 1b: when-let/when-some/when-first share let's own
;; [pattern val ...] bindings-vector shape (bindings-vector-names doesn't
;; care how many pairs there are, so one pair works the same as let's own
;; many), and their binding is in scope for every body form the same way
;; let's is. if-let/if-some share the same shape but the binding is in
;; scope ONLY in the THEN branch, not the ELSE branch -- see
;; if-let-like-forms and scoped-for? below.
(def ^:private let-like-forms
  #{"let" "loop" "binding" "when-let" "when-some" "when-first" "if-let" "if-some"})
(def ^:private if-let-like-forms #{"if-let" "if-some"})
(def ^:private fn-like-forms #{"fn" "fn*" "defn" "defn-" "defmacro"})

(defn- non-comment-tokens [text] (vec (remove #(= :comment (first %)) (syntax/tokenize text))))

(defn- children-spans
  "Top-level child spans [start end) within [lo hi) -- hi stops before the
  enclosing form's own closing bracket. tokens is every non-comment token
  in the buffer in order; matches is syntax/compute-brackets' bidirectional
  open<->close map. A bracketed child comes back as one span covering its
  own brackets; anything else is a single token span."
  [tokens matches lo hi]
  (loop [ts (drop-while (fn [[_ s _]] (< s lo)) tokens)
         acc []]
    (if (empty? ts)
      acc
      (let [[t-type s e] (first ts)]
        (if (>= s hi)
          acc
          (if (and (= t-type :bracket) (contains? matches s) (> (get matches s) s))
            (let [close (get matches s)]
              (recur (drop-while (fn [[_ ts2 _]] (<= ts2 close)) ts) (conj acc [s (inc close)])))
            (recur (rest ts) (conj acc [s e]))))))))

(defn- form-children
  "children-spans for the bracketed form spanning [form-start form-end)."
  [tokens matches form-start form-end]
  (children-spans tokens matches (inc form-start) (dec form-end)))

(defn- enclosing-parens
  "[open close] for every '(' enclosing pos, innermost (largest open) first.
  close is the matching ')' 's own position, not one past it."
  [^String text matches pos]
  (->> matches
       (keep (fn [[open close]]
               (when (and (< open close) (< open pos) (< pos close) (= \( (.charAt text open)))
                 [open close])))
       (sort-by first >)))

(defn- pairwise-patterns
  "The pattern half of an alternating [pattern val pattern val ...] vector's
  own children (let/loop/binding shape)."
  [children]
  (keep-indexed (fn [i c] (when (even? i) c)) children))

(declare binding-names params-vector-names)

(defn- vector-pattern-names
  "Names bound by a [a b & rest :as all] destructuring pattern: every
  element except & itself and :as's own marker (its target is still
  walked, via the recursive binding-names call, same as any other
  element)."
  [text tokens matches s e]
  (mapcat (fn [c] (binding-names text tokens matches (first c) (second c)))
          (remove (fn [[cs ce]] (contains? #{"&" ":as"} (subs text cs ce)))
                  (form-children tokens matches s e))))

(defn- map-pattern-names
  "Names bound by a {:keys [...] :strs [...] :syms [...] :as x} or a plain
  {sym :key ...} destructuring pattern. :or's own map is skipped -- its
  keys reference already-bound names, not new ones."
  [text tokens matches s e]
  (loop [cs (form-children tokens matches s e) acc []]
    (if (or (empty? cs) (nil? (second cs)))
      acc
      (let [[c0s c0e] (first cs)
            head (subs text c0s c0e)]
        (cond
          (contains? #{":keys" ":strs" ":syms"} head)
          (let [[vs ve] (second cs)]
            (recur (drop 2 cs)
                   (into acc (map (fn [[ns ne]] {:symbol (subs text ns ne) :start ns :end ne})
                                  (form-children tokens matches vs ve)))))

          (= ":as" head)
          (let [[ns ne] (second cs)]
            (recur (drop 2 cs) (conj acc {:symbol (subs text ns ne) :start ns :end ne})))

          (= ":or" head)
          (recur (drop 2 cs) acc)

          (str/starts-with? head ":")
          (recur (drop 2 cs) acc)

          :else
          (recur (drop 2 cs) (conj acc {:symbol head :start c0s :end c0e})))))))

(defn- binding-names
  "The names a destructuring pattern spanning [s e) binds, recursively
  through vector and map patterns; a plain symbol span is itself the one
  bound name."
  [^String text tokens matches s e]
  (let [ch (.charAt text s)]
    (cond
      (= ch \[) (vector-pattern-names text tokens matches s e)
      (= ch \{) (map-pattern-names text tokens matches s e)
      :else [{:symbol (subs text s e) :start s :end e}])))

(defn- bindings-vector-names
  "Plain alternating [pattern val pattern val ...] bindings, as let/loop/
  binding use."
  [text tokens matches vs ve]
  (mapcat (fn [[ps pe]] (binding-names text tokens matches ps pe))
          (pairwise-patterns (form-children tokens matches vs ve))))

(defn- for-doseq-names
  "for/doseq's own bindings vector: [pattern seq-expr pattern seq-expr ...],
  skipping (not unpacking) a :when/:while/:let modifier and its one
  following form -- :let's own bindings aren't covered, see the ns note
  above this section."
  [^String text tokens matches vs ve]
  (loop [cs (form-children tokens matches vs ve) acc []]
    (if (or (empty? cs) (nil? (second cs)))
      acc
      (let [[c0s c0e] (first cs)]
        (if (contains? #{":when" ":while" ":let"} (subs text c0s c0e))
          (recur (drop 2 cs) acc)
          (recur (drop 2 cs) (into acc (binding-names text tokens matches c0s c0e))))))))

(defn- letfn-names
  "letfn's own bindings vector: a list of (name [params] body) per entry --
  only the names, not each function's own params (see the ns note above)."
  [text tokens matches vs ve]
  (mapcat (fn [[ls le]]
            (when-let [[ns ne] (first (form-children tokens matches ls le))]
              [{:symbol (subs text ns ne) :start ns :end ne}]))
          (form-children tokens matches vs ve)))

(defn- fn-arity-params
  "The params-vector span among a fn/defn/defn-/defmacro form's children
  after its own operator: skips an optional name symbol, docstring and
  attr-map, stopping at the first vector (single arity -- this is it) or
  list (multi-arity -- each arity clause is its own ancestor further in,
  nothing to bind at this level). nil when neither turns up."
  [^String text children]
  (loop [cs children]
    (when (seq cs)
      (let [[s _] (first cs)
            ch (.charAt text s)]
        (cond
          (= ch \[) (first cs)
          (= ch \() nil
          :else (recur (rest cs)))))))

(defn- bare-arity-clause?
  "Whether the '(' at open is one of a multi-arity fn/defn/defn-/defmacro
  form's own clause lists, not quoted or literal data that merely LOOKS
  like one because its own first child happens to be a vector (fix wave
  item 1a). open's immediate structural parent -- the smallest '('
  strictly enclosing open itself, i.e. enclosing-parens' own innermost
  result -- must be a fn-like form, AND that parent must actually be in
  multi-arity mode: fn-arity-params on its children (after its own
  operator) returns nil, meaning the first non-skipped child there is a
  LIST, not this clause's own params vector directly. A list can't occur
  among a fn-like form's own name/docstring/attr-map prefix (none of
  those are '(' forms), so once the parent is confirmed multi-arity, open
  is necessarily one of its arity-clause lists."
  [^String text tokens matches open]
  (when-let [[popen pclose] (first (enclosing-parens text matches open))]
    (let [pchildren (form-children tokens matches popen pclose)]
      (when-let [[h0s h0e] (first pchildren)]
        (and (contains? fn-like-forms (subs text h0s h0e))
             (nil? (fn-arity-params text (rest pchildren))))))))

(defn- ancestor-names
  "The names the '(' form spanning [open close] binds, or nil when it
  binds nothing. A bare arity clause -- a list whose OWN first child is a
  vector, as each clause of a multi-arity fn/defn/defmacro is -- is
  detected directly (its parens are their own ancestor of anything in
  their body), without needing to consult the outer fn-def form's own
  operator, PROVIDED bare-arity-clause? confirms it really is one (fix
  wave item 1a) -- quoted/literal data whose first child happens to be a
  vector (e.g. `'([q] q)`) is never mistaken for one, since its immediate
  parent isn't a fn-like form in multi-arity mode."
  [^String text tokens matches open close]
  (let [children (form-children tokens matches open close)]
    (when-let [[h0s h0e] (first children)]
      (if (and (= \[ (.charAt text h0s)) (bare-arity-clause? text tokens matches open))
        (params-vector-names text tokens matches h0s h0e)
        (let [op (subs text h0s h0e)]
          (cond
            (contains? let-like-forms op)
            (when-let [[vs ve] (second children)] (bindings-vector-names text tokens matches vs ve))

            (contains? #{"for" "doseq"} op)
            (when-let [[vs ve] (second children)] (for-doseq-names text tokens matches vs ve))

            (= "letfn" op)
            (when-let [[vs ve] (second children)] (letfn-names text tokens matches vs ve))

            (contains? fn-like-forms op)
            (when-let [[vs ve] (fn-arity-params text (rest children))]
              (params-vector-names text tokens matches vs ve))

            :else nil))))))

(defn- params-vector-names
  "Names bound by a fn/defn param vector or a bare multi-arity clause's own
  vector: every element except a lone & (its target is still walked)."
  [text tokens matches s e]
  (mapcat (fn [[cs ce]] (binding-names text tokens matches cs ce))
          (remove (fn [[cs ce]] (= "&" (subs text cs ce))) (form-children tokens matches s e))))

(defn- scoped-for?
  "Whether the [open close] ancestor's own binding, if any, is in scope at
  pos (fix wave item 1b) -- false only for if-let/if-some when pos sits in
  the ELSE branch (children index 3), the one place the shared let-shaped
  bindings-vector-names extraction would otherwise be wrong: if-let/
  if-some's binding covers only the THEN branch (children index 2), never
  the else. Every other ancestor (let-like, for/doseq, letfn, fn-like, or
  anything unrecognised) is always in scope, same as before this item."
  [^String text tokens matches open close pos]
  (let [children (form-children tokens matches open close)]
    (if-let [[h0s h0e] (first children)]
      (if (contains? if-let-like-forms (subs text h0s h0e))
        (if-let [[ts te] (nth children 2 nil)]
          (and (<= ts pos) (< pos te))
          true)
        true)
      true)))

(defn- local-binding
  "The innermost binding of sym-name in scope at pos, as {:symbol :start
  :end}, or nil. Walks '(' forms enclosing pos outward; the first ancestor
  with a match wins, and within that ancestor's own name list the LAST
  match wins (a repeated name in one binding vector prefers the one
  closest to the body). scoped-for? (fix wave item 1b) skips an ancestor
  entirely -- as if it bound nothing -- when it's an if-let/if-some whose
  binding doesn't reach pos (the ELSE branch)."
  [text pos sym-name]
  (let [{:keys [matches]} (syntax/compute-brackets text)
        tokens (non-comment-tokens text)]
    (some (fn [[open close]]
            (when (scoped-for? text tokens matches open close pos)
              (last (filter #(= sym-name (:symbol %))
                            (ancestor-names text tokens matches open close)))))
          (enclosing-parens text matches pos))))

(defn jump-target
  "Where jump to definition goes from snapshot: {:kind :buffer :offset n} for
  a definition in the buffer, else {:kind :external :doc d} when doc-info (a
  jonquil.docs/symbol-doc result for the target symbol) found a var, else
  nil. Resolution order: the nearest enclosing local binding of the target
  symbol (local-binding, above) in scope at its own start; then a top-level
  def* anywhere in the buffer (syntax/find-definition called with NO pos,
  so only its top-level half runs -- its own local search is scope-less and
  would wrongly favour an earlier USE over an unbound name's own nil, see
  the section note above); then external; else nil."
  [snapshot doc-info]
  (when-let [{:keys [symbol start]} (target snapshot)]
    (let [text (:text snapshot)]
      (if-let [b (local-binding text start symbol)]
        {:kind :buffer :offset (:start b)}
        (if-let [d (syntax/find-definition text symbol)]
          {:kind :buffer :offset (:start d)}
          (when (= :found (:status doc-info))
            {:kind :external :doc doc-info}))))))

(defn rename-problem
  "Why renaming the target symbol to new-name would do nothing, as a short
  sentence for the rename popover, or nil when edit/rename has edits to
  apply. Mirrors edit/rename's own nil cases."
  [snapshot new-name]
  (let [old (target-symbol snapshot)
        nn (str/trim (or new-name ""))]
    (cond
      (nil? old) "Put the caret on a symbol first."
      (str/blank? nn) "Type a new name."
      (= nn old) "That's the current name."
      (nil? (edit/rename snapshot nn)) (str "Can't rename '" old "' to '" nn "'.")
      :else nil)))
