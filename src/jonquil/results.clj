(ns jonquil.results
  "The console results' verbs (LM1), as data: a flat set of kinds
  (jonquil.results-rt/kind) and the verbs each offers, the way keys are a
  table. No GTK here."
  (:require [clojure.string :as str]))

(def ^:private by-kind
  {:var [:inspect :copy :define :jump :doc]})

(defn verbs
  "The verbs a result of kind offers, in menu order."
  [kind]
  (get by-kind kind [:inspect :copy :define]))

(defn offered?
  "True when a result of kind offers verb: what each of the console's verb
  commands checks before acting."
  [verb kind]
  (boolean (some #{verb} (verbs kind))))

(def ^:private labels
  {:inspect "Inspect This Result"
   :copy "Copy This Result"
   :define "Define a Var for This Result"
   :jump "Jump to This Var"
   :doc "Quick Doc for This Var"})

(defn label [verb] (get labels verb))

(defn define-input
  "The prompt's text and caret for Define, from a result's ref: (def  ...)
  holding the live value, the caret where the name goes."
  [{:keys [id epoch]}]
  {:text (str "(def  (jonquil.results-rt/value! " id " " (pr-str epoch) "))") :caret 5})

(defn var-name
  "The qualified name a var result line prints (=> #'ns/name), or nil."
  [line]
  (second (re-find #"^=> #'(\S+)" line)))

(defn doc-console-text
  "A var's quick-doc text as the console shows it for the quick-doc verb:
  each line a comment, a blank one a bare ;, ending in a newline."
  [text]
  (apply str (map (fn [l] (if (str/blank? l) ";\n" (str "; " l "\n")))
                  (str/split-lines text))))

(defn copy-text
  "A result line as Copy copies it: without the => prefix or the newline."
  [line]
  (let [s (str/replace line #"\n$" "")]
    (if (str/starts-with? s "=> ") (subs s 3) s)))
