(ns jonquil.console
  "Pure decisions for the Interactions console: what Return does, how much
  old output to trim, how a result is shown, and a friendlier message for an
  unclosed bracket. The GTK console owns the text; these functions only
  decide."
  (:require [clojure.string :as str]
            [jonquil.eval :as eval]
            [jonquil.syntax :as syntax]
            [jonquil.theme :as theme]))

(def styles
  ;; Each console kind's bold/italic flags (upstream ui.clj console attrs at
  ;; 41c56b8). Colours come from the active theme (jonquil.theme). The
  ;; declaration order matters: jonquil.gtk.console-view adds one tag per
  ;; key in this order, and GTK's tag priority follows it.
  {:out    {}
   :err    {}
   :prompt {:bold true}
   :result {:bold true}
   :error  {}
   :system {:italic true}})

(defn style
  "The style map for console kind k under theme t (a jonquil.theme map):
  :fg from the theme, :bold/:italic from the kind. nil for an unknown k."
  [t k]
  (when-let [flags (get styles k)]
    (assoc flags :fg (theme/console-color t k))))

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

(defn error-text
  "An :error result's message: :error-text when an out-of-process runtime
  sent text, else jonquil.eval/format-error of the in-process :error."
  [{:keys [error error-text]}]
  (or error-text (eval/format-error error)))

(defn result-line
  "[kind text] for an engine result, ready to append to the console. A
  :notice result (the attach evaluator's quiet load summary and its
  not-running sentence) is a :system line holding its own :text."
  [{:keys [status printed text] :as result}]
  (case status
    :ok [:result (str "=> " printed "\n")]
    :error [:error (str (error-text result) "\n")]
    :interrupted [:system "; interrupted\n"]
    :notice [:system (str text "\n")]))

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
