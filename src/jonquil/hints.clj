(ns jonquil.hints
  "LM6's line in the status bar that says what keys do at the caret,
  Genera's mouse documentation line for a keyboard-first editor: on a
  symbol, the chords for quick doc, jump and rename, read from the live
  keymap so a person's own bindings show. No GTK here."
  (:require [clojure.string :as str]
            [jonquil.palette :as palette]
            [jonquil.syntax :as syntax]))

(def ^:private ACTIONS
  ;; what the line names on a symbol, in order: [command word]
  {:symbol [[:help/quick-doc "doc"] [:help/jump "jump"] [:edit/rename "rename"]]})

(defn- chord [b] [(:key b) (:keys b) (:mods b) (:exact b)])

(defn- first-chord
  "The first chord in keymap that runs id: a binding with a :when is
  skipped (it may not apply at the caret), and so is one whose chord an
  earlier binding takes for another command (dispatch runs the first)."
  [keymap os id]
  (let [bs (vec (concat (:editor keymap) (:window keymap)))]
    (some (fn [i]
            (let [b (nth bs i)]
              (when (and (= id (:command b)) (nil? (:when b))
                         (not-any? #(and (= (chord %) (chord b)) (not= id (:command %))) (subvec bs 0 i)))
                (palette/chord-label b os))))
          (range (count bs)))))

(defn hint
  "The status bar's hint for what's at the caret (:symbol, or nil for
  nothing), \"Ctrl+Q doc · F12 jump\", each action's first chord in keymap
  ({:editor [...] :window [...]}); an action with no key is left out; nil
  when there's nothing to say."
  [{:keys [at keymap os]}]
  (let [parts (for [[id word] (get ACTIONS at)
                    :let [c (first-chord keymap os id)]
                    :when c]
                (str c " " word))]
    (when (seq parts) (str/join " · " parts))))

(defn line-at
  "[the line of text holding pos, pos within that line]."
  [text pos]
  (let [pos (min (count text) (max 0 pos))
        start (inc (or (str/last-index-of text "\n" (max 0 (dec pos))) -1))
        start (if (and (pos? pos) (= \newline (nth text (dec pos)))) pos start)
        end (or (str/index-of text "\n" pos) (count text))]
    [(subs text start end) (- pos start)]))

(defn at-caret
  "What the caret at pos in text (a line, the window passes the caret's
  own) is on: :symbol, or nil (blank, a comment, a string, a number). Never
  touches syntax/tokenize's cache."
  [text pos]
  (when (syntax/symbol-at-pos text pos true) :symbol))
