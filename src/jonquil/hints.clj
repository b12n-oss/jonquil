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

(defn- first-chord [keymap os id]
  (when-let [b (first (filter #(= id (:command %)) (concat (:editor keymap) (:window keymap))))]
    (palette/chord-label b os)))

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

(defn at-caret
  "What the caret at pos in text is on: :symbol, or nil (blank, a comment,
  a string, a number)."
  [text pos]
  (when (syntax/symbol-at-pos text pos) :symbol))
