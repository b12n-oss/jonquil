(ns jonquil.edit
  "Pure editor operations. Each op takes a snapshot
  {:text :caret :sel-start :sel-end} and returns
  {:edits [[start end replacement] ...] :caret n :sel [a b]}, or nil when the
  op does not apply. Offsets are character offsets into :text, edits are
  non-overlapping and listed in ascending order, and the front-end applies them
  back to front inside one undoable user action."
  (:require [clojure.string :as str]
            [jonquil.syntax :as syntax]))

(def open->close {\( \), \[ \], \{ \}})

(defn apply-edits
  "Applies :edits to text, back to front. The test oracle for every op, and
  what a front-end without native edits would do."
  [text edits]
  (reduce (fn [t [s e r]] (str (subs t 0 s) r (subs t e)))
          text
          (rseq (vec edits))))

(defn smart-enter
  "Upstream ui.clj handle-smart-enter! (41c56b8) as data. Replaces the
  selection with a newline; between an empty pair opens an indented middle
  line with the closer on its own line; otherwise indents by
  syntax/compute-smart-indent measured with the selection removed.
  {:between-pair? false} skips the pair branch: with Parinfer on the
  closer is Parinfer's to manage, as upstream's handle-smart-enter! has it."
  ([snap] (smart-enter snap {}))
  ([{:keys [text sel-start sel-end]} {:keys [between-pair?] :or {between-pair? true}}]
  (let [text' (str (subs text 0 sel-start) (subs text sel-end))
        pos sel-start
        line-start (inc (or (str/last-index-of text' "\n" (dec pos)) -1))
        line-prefix (subs text' line-start pos)
        prev-ch (when (pos? pos) (nth text' (dec pos)))
        next-ch (when (< pos (count text')) (nth text' pos))
        base-indent (or (re-find #"^[ ]+" line-prefix) "")]
    (if (and between-pair? prev-ch next-ch (= (open->close prev-ch) next-ch))
      (let [inner (str base-indent "  ")]
        {:edits [[sel-start sel-end (str "\n" inner "\n" base-indent)]]
         :caret (+ pos 1 (count inner))
         :sel nil})
      (let [ins (str "\n" (syntax/compute-smart-indent text' pos))]
        {:edits [[sel-start sel-end ins]]
         :caret (+ pos (count ins))
         :sel nil})))))

;; --- Line ranges shared by indent, unindent and toggle-comment ---
;;
;; Upstream's indent-selection!, unindent-selection! and toggle-comment! each
;; carry their own copy (via Swing's root Element / getElementIndex) of "which
;; lines does this selection span, and does the selection merely touch the
;; next line's own start without entering it". This is that logic once, in
;; character offsets instead of Swing elements.

(defn- line-starts
  "Ascending vector of the character offset each line of `text` starts at,
  always including 0."
  [^String text]
  (loop [from 0 acc [0]]
    (if-let [idx (str/index-of text "\n" from)]
      (recur (inc idx) (conj acc (inc idx)))
      acc)))

(defn- line-idx-at
  "Index into `starts` of the line containing character offset `pos`."
  [starts pos]
  (loop [i (dec (count starts))]
    (if (<= (nth starts i) pos) i (recur (dec i)))))

(defn- line-span
  "[start end] character offsets of line `idx`'s content, excluding its
  trailing newline."
  [text starts idx]
  (let [s (nth starts idx)
        e (if (< (inc idx) (count starts)) (dec (nth starts (inc idx))) (count text))]
    [s e]))

(defn- selection-lines
  "[start-line end-line starts]: the inclusive line-index range `sel-start`
  to `sel-end` spans. When the selection is non-empty and its end lands
  exactly on a line's own start offset, that line is excluded -- mirrors
  upstream's exclude-if-getStartOffset-equals-sel-end guard, so selecting up
  to (but not past) a line boundary doesn't drag the next line in."
  [text sel-start sel-end]
  (let [starts (line-starts text)
        start-line (line-idx-at starts sel-start)
        end-raw (line-idx-at starts sel-end)
        end-line (if (and (> sel-end sel-start) (= sel-end (nth starts end-raw)))
                   (dec end-raw)
                   end-raw)]
    [start-line end-line starts]))

(defn- shift-offset
  "Maps character offset `pos` in the original text to its offset after
  ascending, non-overlapping `edits` are applied. A pos at or after an edit's
  end shifts by that edit's length delta; a pos strictly inside an edit's
  span snaps to the end of that edit's replacement. Every op below that
  moves a caret or selection after editing multiple lines uses this, since
  upstream's own per-function arithmetic (new-start = sel-start + n, and so
  on) is this same rule applied by hand."
  [edits pos]
  (reduce (fn [p [s e r]]
            (cond
              (< pos s) p
              (< pos e) (+ p (- s pos) (count r))
              :else (+ p (- (count r) (- e s)))))
          pos
          edits))

(defn indent
  "Upstream ui.clj indent-selection! (41c56b8). No selection: two spaces at
  the caret, like typing them. A selection: two spaces at the start of every
  line it spans."
  [{:keys [text caret sel-start sel-end]}]
  (if (= sel-start sel-end)
    {:edits [[caret caret "  "]] :caret (+ caret 2) :sel nil}
    (let [[start-line end-line starts] (selection-lines text sel-start sel-end)
          edits (mapv (fn [idx] [(nth starts idx) (nth starts idx) "  "])
                      (range start-line (inc end-line)))]
      {:edits edits
       :caret (shift-offset edits sel-end)
       :sel [(shift-offset edits sel-start) (shift-offset edits sel-end)]})))

(defn unindent
  "Upstream ui.clj unindent-selection! (41c56b8). Strips up to one leading
  tab or two leading spaces from every line the selection spans (or just the
  caret's line, with no selection). nil when no spanned line has any
  matching leading whitespace to remove."
  [{:keys [text caret sel-start sel-end]}]
  (let [[start-line end-line starts] (selection-lines text sel-start sel-end)
        removals (keep (fn [idx]
                         (let [[s e] (line-span text starts idx)
                               line (subs text s e)]
                           (when-let [m (re-find #"^(?:[ ]{1,2}|\t)" line)]
                             [(+ s (count m)) (count m)])))
                       (range start-line (inc end-line)))]
    (when (seq removals)
      (let [edits (mapv (fn [[end n]] [(- end n) end ""]) removals)]
        (if (= sel-start sel-end)
          {:edits edits :caret (shift-offset edits caret) :sel nil}
          {:edits edits
           :caret (shift-offset edits sel-end)
           :sel [(shift-offset edits sel-start) (shift-offset edits sel-end)]})))))

(defn toggle-comment
  "Upstream ui.clj toggle-comment! (41c56b8): comments every non-blank line
  the selection spans (or the caret's line) with a leading \"; \", or, when
  every non-blank spanned line is already commented, strips one leading
  \";\" run (plus at most one following space) from each. Upstream never
  repositions the caret or selection itself -- Swing's own default
  insert/remove caret tracking does that -- so caret and :sel here are
  computed with the same shift-offset every other multi-line op uses."
  [{:keys [text caret sel-start sel-end]}]
  (let [[start-line end-line starts] (selection-lines text sel-start sel-end)
        lines (mapv (fn [idx]
                      (let [[s e] (line-span text starts idx)]
                        [(nth starts idx) (subs text s e)]))
                    (range start-line (inc end-line)))
        non-blank (remove (fn [[_ t]] (str/blank? t)) lines)
        all-commented? (and (seq non-blank)
                            (every? (fn [[_ t]] (boolean (re-find #"^[ ]*;+" t))) non-blank))
        edits (if all-commented?
                (keep (fn [[ls t]]
                        (when-let [m (re-find #"^([ ]*)(;+[ ]?)" t)]
                          (let [lead (count (nth m 1)) chars (count (nth m 2))]
                            [(+ ls lead) (+ ls lead chars) ""])))
                      lines)
                (keep (fn [[ls t]] (when-not (str/blank? t) [ls ls "; "])) lines))]
    (when (seq edits)
      (let [edits (vec edits)]
        {:edits edits
         :caret (shift-offset edits caret)
         :sel (when (not= sel-start sel-end)
                [(shift-offset edits sel-start) (shift-offset edits sel-end)])}))))

;; --- Auto-brackets ---
;;
;; Upstream's setup-auto-brackets! (41c56b8) matches on one open->matching-close
;; map that includes the quote pair (\" -> \"), which smart-enter's own
;; open->close above does not need and does not have -- so this is a second,
;; separate map rather than widening the prescribed one.

(def ^:private bracket-pairs {\( \), \[ \], \{ \}, \" \"})

(defn insert-open
  "Upstream setup-auto-brackets! keyTyped (41c56b8), the open-bracket/quote
  branch: wraps a selection in ch and its closer, steps a same quote over an
  existing one instead of nesting, or inserts a fresh empty pair with the
  caret in the middle. nil when ch isn't one of ( [ { \"."
  [{:keys [text caret sel-start sel-end]} ch]
  (when (contains? bracket-pairs ch)
    (let [close (bracket-pairs ch)]
      (cond
        (< sel-start sel-end)
        (let [wrapped (str ch (subs text sel-start sel-end) close)
              edits [[sel-start sel-end wrapped]]]
          {:edits edits
           :caret (+ sel-start (count wrapped))
           :sel [sel-start (+ sel-start (count wrapped))]})

        (and (= ch \") (< caret (count text)) (= (nth text caret) \"))
        {:edits [[caret (inc caret) "\""]] :caret (inc caret) :sel nil}

        :else
        {:edits [[caret caret (str ch close)]] :caret (inc caret) :sel nil}))))

(def ^:private closers
  ;; ) ] } only -- typing a quote goes through insert-open, whose own
  ;; step-over case already covers the quote pair.
  #{\) \] \}})

(defn insert-close
  "Upstream setup-auto-brackets! keyTyped (41c56b8), the close-delimiter
  branch: typing a closer that's already the next character steps over it
  instead of inserting a second one. nil otherwise -- let GTK insert it."
  [{:keys [text caret sel-start sel-end]} ch]
  (when (and (= sel-start sel-end)
             (contains? closers ch)
             (< caret (count text))
             (= (nth text caret) ch))
    {:edits [[caret (inc caret) (str ch)]] :caret (inc caret) :sel nil}))

(defn backspace-pair
  "Upstream setup-auto-brackets! keyPressed VK_BACK_SPACE (41c56b8): caret
  sitting inside an empty bracket or quote pair deletes both characters
  together. nil otherwise -- let GTK do a plain single-character backspace."
  [{:keys [text caret sel-start sel-end]}]
  (when (and (= sel-start sel-end)
             (pos? caret)
             (< caret (count text))
             (= (bracket-pairs (nth text (dec caret))) (nth text caret)))
    {:edits [[(dec caret) (inc caret) ""]] :caret (dec caret) :sel nil}))

(defn matching-bracket
  "Upstream ui.clj find-matching-bracket (41c56b8) and bracket-matching-test:
  :caret is the bracket's own offset (not a between-characters caret), and
  the return is [pos match] or nil, never an edit."
  [{:keys [text caret]}]
  (when (and (<= 0 caret) (< caret (count text)))
    (when-let [m (get (:matches (syntax/compute-brackets text)) caret)]
      [caret m])))

(def ^:private code-symbol-re #"^[-_a-zA-Z0-9\p{L}\p{N}.!$%&*+/<=>?#]+$")

(defn rename
  "Upstream ui.clj rename-symbol-dialog! (41c56b8), by way of
  syntax/find-symbol-occurrences rather than porting the JOptionPane dialog:
  renames every code occurrence (never inside a comment, string, regex or
  keyword) of the symbol at the selection or caret to new-name. nil when
  there's no symbol at the caret/selection, new-name is blank, isn't a valid
  Clojure symbol, equals the old name, or there are no occurrences to rename
  -- each of those is upstream's own no-op or error-dialog path, and none of
  them has anything to apply."
  [{:keys [text caret sel-start sel-end]} new-name]
  (let [new-name (str/trim (str new-name))
        selected (when (< sel-start sel-end) (str/trim (subs text sel-start sel-end)))
        old-sym (if (and selected (re-matches code-symbol-re selected))
                  selected
                  (:symbol (syntax/symbol-at-pos text caret)))]
    (when (and old-sym
               (not (str/blank? new-name))
               (re-matches code-symbol-re new-name)
               (not= new-name old-sym))
      (let [occs (syntax/find-symbol-occurrences text old-sym)]
        (when (seq occs)
          (let [edits (mapv (fn [[s e]] [s e new-name]) occs)]
            {:edits edits :caret (shift-offset edits caret) :sel nil}))))))
