(ns jonquil.doc-examples
  "ClojureDocs examples inside a doc result: split the server's text into the
  doc and its examples, clean an example's REPL transcript into code that can
  run, and place that code in a Definitions buffer. Pure; no GTK."
  (:require [clojure.string :as str]
            [jonquil.commands :as commands]))

;; Cleaning

(def ^:private named-prompt-re
  ;; user=>, cljs.user=>, and CIDER's user> (a name, then > before a space or
  ;; the line's end). A bare > or => has no name and is not a prompt.
  #"^[ \t]*(?:[A-Za-z0-9_.*+!?<>-]+=>|[A-Za-z_][A-Za-z0-9_.*+!?<>-]*?>(?=[ \t]|$))")
(def ^:private bare-marker-re #"^[ \t]*=>")

(defn- eol
  "Index of the newline ending the line that holds i, or the text's length."
  [text i]
  (let [j (str/index-of text "\n" i)] (or j (count text))))

(defn- skip-blanks
  "First index at or after i that is not a space or tab."
  [text i]
  (loop [i i]
    (if (and (< i (count text)) (contains? #{\space \tab} (.charAt ^String text (int i)))) (recur (inc i)) i)))

(defn- form-end
  "Absolute end of the form starting exactly at i in text, or nil when no
  closed form starts there (unclosed, or only a comment)."
  [text i]
  (let [f (first (commands/top-level-forms (subs text i)))]
    (when (and f (zero? (:start f))) (+ i (:end f)))))

(defn- output-line?
  "False for a line that ends a prompt's printed output: blank, a ; comment
  or another prompt."
  [line]
  (not (or (str/blank? line)
           (str/starts-with? (str/triml line) ";")
           (re-find named-prompt-re line))))

(defn- skip-output
  "Index of the first line start at or after i that is not printed output."
  [text i]
  (loop [i i]
    (if (>= i (count text))
      i
      (let [e (eol text i)]
        (if (output-line? (subs text i e)) (recur (min (count text) (inc e))) i)))))

(defn- prompt-step
  "clean's step over a line at i holding a named prompt: [next-i acc']. A
  prompt with nothing after it on its line is dropped alone; else the form
  after it, with the rest of its line, is kept and its printed output
  skipped. A form that never ends keeps the rest of body and ends the walk."
  [^String body line i acc]
  (let [n (count body)
        p (skip-blanks body (+ i (.end (doto (re-matcher named-prompt-re line) .find))))]
    (if (or (>= p n) (contains? #{\newline \;} (.charAt body (int p))))
      ;; nothing after the prompt on its line: drop the prompt only
      [p acc]
      (if-let [fe (form-end body p)]
        (let [le (eol body fe)]
          ;; the form's whole line stays (a second form, a ; comment)
          [(skip-output body (min n (inc le))) (conj acc (subs body p le))])
        [n (conj acc (subs body p))]))))

(defn- past-marker
  "Where clean goes on after a line at i holding a bare => marker: past the
  form after it, or past the marker's line."
  [body line i]
  (let [n (count body)
        p (skip-blanks body (+ i (.end (doto (re-matcher bare-marker-re line) .find))))
        fe (when (< p n) (form-end body p))]
    (min n (inc (eol body (or fe p))))))

(defn clean
  "An example body as code: REPL prompts (user=> ), printed output and => result
  markers dropped, the rest as written. See the design spec's 'Splitting and
  cleaning'. Blank lines at either end go. \"\" when nothing is left."
  [body]
  (let [n (count body)
        out
        (loop [i 0 acc []]
          (if (>= i n)
            acc
            (let [e (eol body i)
                  line (subs body i e)
                  next-i (min n (inc e))]
              (cond
                (re-find named-prompt-re line)
                (let [[i' acc'] (prompt-step body line i acc)] (recur i' acc'))

                (re-find bare-marker-re line) (recur (past-marker body line i) acc)

                :else (recur next-i (conj acc line))))))]
    (-> (str/join "\n" out)
        (str/replace #"\A(?:[ \t]*\n)+" "")
        (str/replace #"(?:\n[ \t]*)+\z" ""))))

;; Splitting

(def ^:private title-re #"(?m)^== (.*)$")
(def ^:private example-re #"(?m)^= example (\d+)\n")

(defn split
  "Split a doc result into {:doc :title :examples [{:n :body :code} ...]}.
  The server's shape is the doc, a blank line, a '== ns/name' line, a blank
  line, then examples each headed '= example N'. Text of any other shape gives
  :examples [] with :doc the whole text and :title nil."
  [text]
  (let [text (str/replace text "\r\n" "\n")
        none {:doc text :title nil :examples []}
        tm (re-matcher title-re text)]
    (if-not (.find tm)
      none
      (let [title (str/trim (.group tm 1))
            rest-text (subs text (.end tm))
            em (re-matcher example-re rest-text)
            heads (loop [acc []]
                    (if (.find em)
                      (recur (conj acc {:n (parse-long (.group em 1)) :start (.start em) :end (.end em)}))
                      acc))]
        (if (or (empty? heads)
                (not (str/blank? (subs rest-text 0 (:start (first heads))))))
          none
          {:doc (str/replace (subs text 0 (.start tm)) #"\n+\z" "")
           :title title
           :examples (vec (map-indexed
                           (fn [i {:keys [n end]}]
                             (let [stop (if-let [nxt (nth heads (inc i) nil)] (:start nxt) (count rest-text))
                                   body (str/replace (subs rest-text end stop) #"\n\n\z" "")]
                               {:n n :body body :code (clean body)}))
                           heads))})))))

;; Inserting

(defn- line-start
  "Index of the first character of the line that holds i."
  [text i]
  (if-let [j (when (pos? i) (str/last-index-of text "\n" (dec i)))] (inc j) 0))

(defn- insert-in-comment-op
  "insert-op for a caret in a (comment ...) block: the code goes on its own
  line before the block's closing paren, indented two spaces on its first
  line, with a blank line before it. A paren on its own line stays on the line
  after the code; otherwise it follows the code directly."
  [text form code]
  (let [close (dec (:end form))
        ls (line-start text close)
        own-line? (str/blank? (subs text ls close))
        prev-blank? (or (zero? ls) (str/blank? (subs text (line-start text (dec ls)) (dec ls))))
        [pos prefix suffix] (if own-line?
                              [ls (if prev-blank? "" "\n") "\n"]
                              [close "\n\n" ""])
        indented (str "  " code)]
    {:edits [[pos pos (str prefix indented suffix)]] :caret (+ pos (count prefix) (count indented))}))

(defn insert-op
  "The edit that puts code after the top-level form holding the caret (a
  caret just past its last character counts), else after the caret's line.
  It goes after the end of the line the form ends on, so a trailing comment
  or a second form stays with its form, one blank line before it (none at
  the start of the buffer, none doubled on a blank line) and one after it
  when text follows. {:edits [[pos pos inserted]] :caret int}, the shape
  jonquil.gtk.editor/apply-op! takes; :caret is the end of the code. A caret in
  a (comment ...) block puts the code inside it instead (insert-in-comment-op)."
  [{:keys [text caret]} code]
  (let [forms (commands/top-level-forms text)
        form (or (first (filter (fn [{:keys [start end]}] (and (<= start caret) (< caret end))) forms))
                 (first (filter (fn [{:keys [end]}] (= end caret)) forms)))
        pos (eol text (if form (:end form) caret))
        ls (line-start text pos)
        blank-line? (str/blank? (subs text ls pos))
        ;; a blank line already gives one of the two newlines
        prefix (cond (not blank-line?) "\n\n"
                     (zero? ls) ""
                     (str/blank? (subs text (line-start text (dec ls)) (dec ls))) ""
                     :else "\n")
        next-start (inc pos)
        suffix (if (and (< next-start (count text))
                        (not (str/blank? (subs text next-start (eol text next-start)))))
                 "\n"
                 "")]
    (if (and form (commands/comment-form? text form))
      (insert-in-comment-op text form code)
      {:edits [[pos pos (str prefix code suffix)]] :caret (+ pos (count prefix) (count code))})))
