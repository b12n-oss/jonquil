(ns jonquil.console
  "Pure decisions for the Interactions console: what Return does, how much
  old output to trim, how a result is shown, and a friendlier message for an
  unclosed bracket. The GTK console owns the text; these functions only
  decide."
  (:require [clojure.string :as str]
            [jonquil.eval :as eval]
            [jonquil.syntax :as syntax]))

(def styles
  ;; Upstream ui.clj console attrs at 41c56b8, as hex.
  {:out    {:fg "#282828"}
   :err    {:fg "#b41414"}
   :prompt {:fg "#0064c8" :bold true}
   :result {:fg "#147828" :bold true}
   :error  {:fg "#b41414"}
   :system {:fg "#6e6e6e" :italic true}})

(defn input-action
  "What Return in the console input does. Shift+Return always inserts a
  newline. While the program waits on read-line, the input goes to stdin.
  While a program runs without waiting, the input is held (:busy). Blank
  input does nothing, unbalanced input gets a newline so multi-line forms
  can be typed, and anything else is evaluated."
  [{:keys [input waiting? evaluating? shift?]}]
  (cond
    shift? :newline
    waiting? :send-stdin
    evaluating? :busy
    (str/blank? input) :noop
    (seq (:unmatched (syntax/compute-brackets input))) :newline
    :else :eval))

(defn trim-length
  "How many characters to delete from the front of text so it is at most
  max-chars long, cutting after a newline where one exists in the part that
  must go. 0 when nothing needs trimming."
  [text max-chars]
  (let [n (count text)]
    (if (<= n max-chars)
      0
      (let [must (- n max-chars)
            nl (str/index-of text "\n" (dec must))]
        (if nl (inc nl) must)))))

(defn result-line
  "[kind text] for an engine result, ready to append to the console."
  [{:keys [status printed error]}]
  (case status
    :ok [:result (str "=> " printed "\n")]
    :error [:error (str (eval/format-error error) "\n")]
    :interrupted [:system "; interrupted\n"]))

(defn unclosed-bracket-message
  "For a buffer with an opening bracket that is never closed, a sentence
  naming the first such bracket and its line. nil when every opener is
  closed."
  [text]
  (let [{:keys [unmatched]} (syntax/compute-brackets text)
        opens (sort (filter (fn [p] (#{\( \[ \{} (nth text p))) unmatched))]
    (when-let [p (first opens)]
      (str "The " (nth text p) " on line "
           (syntax/line-number-at-offset text p) " is never closed."))))
