(ns jonquil.results
  "The console results' verbs (LM1), as data: a flat set of kinds
  (jonquil.results-rt/kind) and the verbs each offers, the way keys are a
  table. No GTK here."
  (:require [clojure.string :as str]))

(def ^:private by-kind
  {:var [:inspect :copy :define :jump]})

(defn verbs
  "The verbs a result of kind offers, in menu order."
  [kind]
  (get by-kind kind [:inspect :copy :define]))

(def ^:private labels
  {:inspect "Inspect This Result"
   :copy "Copy This Result"
   :define "Define a Var for This Result"
   :jump "Jump to This Var"})

(defn label [verb] (get labels verb))

(defn define-input
  "The prompt's text and caret for Define: (def  ...) holding the live
  value, the caret where the name goes."
  [id]
  {:text (str "(def  (jonquil.results-rt/value! " id "))") :caret 5})

(defn var-name
  "The qualified name a var result line prints (=> #'ns/name), or nil."
  [line]
  (second (re-find #"^=> #'(\S+)" line)))

(defn copy-text
  "A result line as Copy copies it: without the => prefix or the newline."
  [line]
  (let [s (str/replace line #"\n$" "")]
    (if (str/starts-with? s "=> ") (subs s 3) s)))
