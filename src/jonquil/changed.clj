(ns jonquil.changed
  "Which top-level forms changed since they last ran (LM3). A form's key is
  its tokens without comments, joined by a space, so reindenting,
  reformatting and comment edits don't count as a change; a tab's ran set
  holds the keys of its forms as they last ran. (comment ...) blocks are
  never marked. No GTK here."
  (:require [clojure.string :as str]
            [jonquil.commands :as commands]
            [jonquil.syntax :as syntax]))

(defn forms
  "text's top-level forms as [{:start :end :key}], (comment ...) blocks
  left out."
  [text]
  (let [tops (remove #(commands/comment-form? text %) (commands/top-level-forms text))
        toks (filterv (fn [[t]] (not= :comment t)) (syntax/tokenize text))]
    ;; one sweep: forms and tokens are both in offset order
    (loop [tops tops toks toks acc []]
      (if-let [{:keys [start end]} (first tops)]
        (let [toks (drop-while (fn [[_ _ e]] (<= e start)) toks)
              [inside after] (split-with (fn [[_ s]] (< s end)) toks)]
          (recur (rest tops) after
                 (conj acc {:start start :end end
                            :key (str/join " " (map (fn [[_ s e]] (subs text s e)) inside))})))
        acc))))

(defn changed
  "The forms of text whose key isn't in ran; [] when ran is nil (the tab
  never ran)."
  [text ran]
  (if (nil? ran) [] (filterv #(not (contains? ran (:key %))) (forms text))))

(defn record
  "ran after a run that ended :ok: a :run replaces it with every form's key
  in text; :form and :selection add the keys of the forms in text, the
  code they sent."
  [ran text kind]
  (let [ks (set (map :key (forms text)))]
    (if (= :run kind) ks (into (or ran #{}) ks))))
