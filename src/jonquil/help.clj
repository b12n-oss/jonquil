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

(defn jump-target
  "Where jump to definition goes from snapshot: {:kind :buffer :offset n} for
  a definition in the buffer, else {:kind :external :doc d} when doc-info (a
  jonquil.docs/symbol-doc result for the target symbol) found a var, else
  nil. syntax/find-definition takes the START of the symbol under the
  caret, not the caret: it searches local bindings among the tokens before
  that position, and a caret inside the word would count the usage itself
  as its own binding (measured 2026-09-26; upstream's own test passes the
  usage's start)."
  [snapshot doc-info]
  (when-let [{:keys [symbol start]} (target snapshot)]
    (if-let [d (syntax/find-definition (:text snapshot) symbol start)]
      {:kind :buffer :offset (:start d)}
      (when (= :found (:status doc-info))
        {:kind :external :doc doc-info}))))

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
