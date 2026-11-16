(ns jonquil.mcp.result
  "What an eval tool answers besides its text: a capped collector for the
  evaluation's output and the structured data an MCP client reads without
  parsing that text. Pure, no GTK and no evaluator."
  (:require [clojure.string :as str]))

(def default-max-output-chars 100000)
(def ^:private hard-max-output-chars 1000000)

(defn max-output-of
  "The call's max_output_chars when a positive number, at most 1000000,
  else the 100000 default."
  [args]
  (let [n (get args "max_output_chars")]
    (if (and (number? n) (pos? n))
      (min (long n) hard-max-output-chars)
      default-max-output-chars)))

;; --- collecting output -------------------------------------------------------

(def empty-output
  "A collector: :chunks is a vector of [kind s], :size their characters,
  :dropped the characters already thrown away from the front."
  {:chunks [] :size 0 :dropped 0})

(defn- keep-tail
  "chunks cut from the front to their last cap characters: [chunks dropped]."
  [chunks size cap]
  (loop [cs chunks over (- size cap)]
    (if (<= over 0)
      [(vec cs) (- size cap)]
      (let [[kind s] (first cs)
            n (count s)]
        (if (<= n over)
          (recur (rest cs) (- over n))
          (recur (cons [kind (subs s over)] (rest cs)) 0))))))

(defn add-output
  "state with chunk s of kind (:out or :err) added. Once it holds more than
  twice cap characters it is cut back to its last cap, so a program that
  prints without end costs a bounded amount of memory."
  [{:keys [chunks size dropped]} kind s cap]
  (let [s (str s)
        chunks (conj chunks [kind s])
        size (+ size (count s))]
    (if (> size (* 2 cap))
      (let [[kept gone] (keep-tail chunks size cap)]
        {:chunks kept :size cap :dropped (+ dropped gone)})
      {:chunks chunks :size size :dropped dropped})))

(defn output-of
  "{:output :err :dropped} for a collector: its last cap characters as one
  string in the order they arrived, the :err chunks among them alone, and
  how many characters came before them."
  [{:keys [chunks size dropped]} cap]
  (let [[kept gone] (if (> size cap) (keep-tail chunks size cap) [chunks 0])]
    {:output (apply str (map second kept))
     :err (apply str (keep (fn [[kind s]] (when (= :err kind) s)) kept))
     :dropped (+ dropped gone)}))

(defn clip
  "{:output :dropped} for text already collected as one string: its last
  cap characters."
  [text cap]
  (let [text (str text)
        n (count text)]
    (if (> n cap)
      {:output (subs text (- n cap)) :dropped (- n cap)}
      {:output text :dropped 0})))

(defn dropped-line
  "The line that stands in for output cut from the front, \"\" for none."
  [dropped]
  (if (pos? dropped)
    (str "; " dropped " earlier characters of output dropped\n")
    ""))

;; --- structured data ---------------------------------------------------------

(defn error-fields
  "An error's text as {\"message\" \"type\" \"line\" \"column\"}. The type
  and the place are read from the shape jonquil.eval/format-error writes
  (\"Error [Type]: message\", then \"Location: line L, col C\") and are left
  out when the text has another shape; \"message\" is then the whole text."
  [error-text]
  (let [text (str/trim (str error-text))
        [_ type-name msg] (re-find #"(?s)^Error \[([^\]]+)\]: (.*)$" text)
        [place line column] (re-find #"\nLocation: line (\d+)(?:, col (\d+))?" text)
        msg (if (and msg place) (str/replace msg place "") msg)]
    (cond-> {"message" (str/trim (or msg text))}
      type-name (assoc "type" type-name)
      line (assoc "line" (parse-long line))
      column (assoc "column" (parse-long column)))))

(defn eval-data
  "The structuredContent of an eval tool's answer. :status is a keyword
  (:ok :error :interrupted :notice :timeout :waiting-for-input :refused),
  :output what the evaluation printed, :err its stderr part when known,
  :dropped the characters cut from the output's front, :ms the wall time.
  :value is the printed result of an :ok, :error-text an :error's text."
  [{:keys [status value output err dropped ms ns error-text]}]
  (cond-> {"status" (name status)
           "output" (str output)}
    (some? value) (assoc "value" (str value))
    (seq err) (assoc "err" err)
    (and dropped (pos? dropped)) (assoc "dropped_chars" dropped)
    ms (assoc "ms" ms)
    ns (assoc "ns" (str ns))
    error-text (assoc "error" (error-fields error-text))))
