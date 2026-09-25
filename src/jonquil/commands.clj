(ns jonquil.commands
  "Pure decisions behind Phase 3's window commands: what Run Selection sends,
  the bracket check before Run, find and replace over a snapshot, and zoom
  steps. No GTK here; the front-end hands in plain text and offsets."
  (:require [clojure.string :as str]
            [jonquil.console :as console]
            [jonquil.syntax :as syntax]))

(defn- line-bounds
  "[start end] of the line holding `caret`, without its newline."
  [text caret]
  (let [nl (when (pos? caret) (str/last-index-of text "\n" (dec caret)))]
    [(if nl (inc nl) 0)
     (or (str/index-of text "\n" caret) (count text))]))

(defn run-selection-text
  "The code Run Selection sends to the REPL: the selected text when there is
  a selection, otherwise the whole line the caret is on. nil when that text
  is blank, so an empty line sends nothing."
  [{:keys [text caret sel-start sel-end]}]
  (let [s (if (and sel-start sel-end (< sel-start sel-end))
            (subs text sel-start sel-end)
            (let [[start end] (line-bounds text caret)]
              (subs text start end)))]
    (when-not (str/blank? s) s)))

(defn format-all-op
  "Re-indents the whole buffer as one edit (one undo step). nil when the
  text is already formatted."
  [{:keys [text caret]}]
  (let [out (syntax/format-code text)]
    (when (not= out text)
      {:edits [[0 (count text) out]] :caret (min caret (count out)) :sel nil})))

(defn format-selection-op
  "Re-indents the lines the selection spans, or the caret's line when there
  is no selection, as one edit. nil when nothing changes."
  [{:keys [text caret sel-start sel-end]}]
  (let [[s e] (if (and sel-start sel-end (< sel-start sel-end))
                [sel-start sel-end]
                (line-bounds text caret))
        out (if (< s e) (syntax/format-selection-text text s e) text)]
    (when (not= out text)
      {:edits [[0 (count text) out]] :caret (min caret (count out)) :sel nil})))

(defn pre-run-problem
  "A sentence naming the first bracket problem in `text`, checked before Run
  so the message shows even when an earlier form would throw first. nil when
  every bracket is matched. Brackets inside strings, comments and character
  literals don't count (the tokenizer skips them)."
  [text]
  (let [{:keys [unmatched]} (syntax/compute-brackets text)]
    (when-let [p (first (sort unmatched))]
      (let [ch (nth text p)
            line (syntax/line-number-at-offset text p)]
        (if (#{\( \[ \{} ch)
          (console/unclosed-bracket-message text)
          (str "The " ch " on line " line " has no matching opener."))))))

(defn next-match
  "The match to select after the caret, wrapping to the first. `matches` is
  a vector of [start end]; returns one of them or nil when there are none."
  [matches caret]
  (when (seq matches)
    (or (first (filter (fn [[s _]] (>= s caret)) matches))
        (first matches))))

(defn prev-match
  "The last match that ends before `caret`, wrapping to the last match."
  [matches caret]
  (when (seq matches)
    (or (last (filter (fn [[_ e]] (<= e caret)) matches))
        (last matches))))

(defn replace-current
  "Replaces the selection when it is exactly one of `matches`, then selects
  the next match. Returns an edit result for `apply-op!`, or nil when the
  selection is not a match (the caller then just finds the next one)."
  [{:keys [sel-start sel-end]} matches replacement]
  (when (some #{[sel-start sel-end]} matches)
    (let [delta (- (count replacement) (- sel-end sel-start))
          after (next-match (vec (remove #{[sel-start sel-end]} matches)) sel-end)
          after (when after (if (>= (first after) sel-end)
                              [(+ (first after) delta) (+ (second after) delta)]
                              after))
          caret (+ sel-start (count replacement))]
      {:edits [[sel-start sel-end replacement]]
       :caret (if after (second after) caret)
       :sel (or after [caret caret])})))

(defn replace-all
  "Replaces every match. One edit per match, so undo sees one step when the
  front-end applies them inside one user action. nil when there are none."
  [matches replacement]
  (when (seq matches)
    (let [n (count matches)
          [s0 _] (first matches)]
      {:edits (vec (for [[s e] matches] [s e replacement]))
       :caret (+ s0 (count replacement))
       :sel nil
       :count n})))

(def ZOOM-SIZES
  ;; Font sizes in pixels the zoom keys step through; 13 is the default.
  [9 10 11 12 13 14 16 18 20 24 28 32])

(def DEFAULT-FONT-PX 13)

(defn zoom
  "The next font size for `direction` (:in, :out or :reset) from `current`."
  [current direction]
  (case direction
    :reset DEFAULT-FONT-PX
    :in (or (first (filter (fn [s] (> s current)) ZOOM-SIZES)) (peek ZOOM-SIZES))
    :out (or (last (filter (fn [s] (< s current)) ZOOM-SIZES)) (first ZOOM-SIZES))))
