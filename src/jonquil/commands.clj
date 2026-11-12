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

;; --- Run Form ---------------------------------------------------------------

(def ^:private prefix-chars
  ;; Reader prefixes that belong to the form right after them: quote,
  ;; syntax-quote, unquote, deref, and # (sets, fns, reader conditionals).
  #{\' \` \~ \@ \# \?})

(defn- hash-marked-symbol?
  "True when tok is the symbol sym written right after a # (the ? of #? or
  the _ of #_), which the tokenizer reads as a symbol of its own."
  [text sym [t s e]]
  (and (= :symbol t) (pos? s) (= sym (subs text s e))
       (= \# (.charAt ^String text (int (dec s))))))

(defn- meta-bracket?
  "True when the bracket at offset open (or nil) is written right after a ^."
  [text open]
  (and open (pos? open) (= \^ (.charAt ^String text (int (dec open))))))

(defn- absorb-form
  "parent with form folded in: a metadata tag or ^-prefixed bracket is held
  as :meta-start for the next form, a pending #_ swallows the form, and
  anything else joins parent's :kids (taking the held metadata start)."
  [text parent {:keys [start open kind] :as form}]
  (cond
    (= kind :metatag)
    (update parent :meta-start (fn [ms] (or ms start)))

    (meta-bracket? text open)
    (update parent :meta-start (fn [ms] (or ms (dec open))))

    :else
    (let [ms (:meta-start parent)
          form (cond-> form ms (assoc :start ms))
          parent (dissoc parent :meta-start)]
      (if (pos? (:discards parent 0))
        (update parent :discards dec)
        (update parent :kids conj form)))))

(defn- prefix-start
  "The offset where the form whose token i starts at s really starts: s
  moved back over reader prefix characters, but not into the token before."
  [{:keys [text toks]} i s]
  (let [floor (if (pos? i) (nth (nth toks (dec i)) 2) 0)]
    (loop [p s]
      (if (and (> p floor) (contains? prefix-chars (.charAt ^String text (int (dec p)))))
        (recur (dec p))
        p))))

(defn- close-bracket
  "stack after a closing bracket ch ending at e: the open form on top is
  finished and absorbed by its parent when ch matches it, else the closer
  is ignored."
  [{:keys [text stack ch e]}]
  (let [top (peek stack)]
    (if (and (> (count stack) 1) (= ch (:close-ch top)))
      (let [form (-> top (dissoc :close-ch) (assoc :end e))
            stack (pop stack)]
        (conj (pop stack) (absorb-form text (peek stack) form)))
      stack)))

(defn- tree-step
  "stack after reading token i of ctx's :toks."
  [{:keys [text toks] :as ctx} stack i]
  (let [[t s e :as tok] (nth toks i)
        ch (when (= :bracket t) (.charAt ^String text (int s)))]
    (cond
      (hash-marked-symbol? text "_" tok)
      (conj (pop stack) (update (peek stack) :discards (fnil inc 0)))

      (and ch (syntax/open->close ch))
      (conj stack {:start (prefix-start ctx i s) :open s :close-ch (syntax/open->close ch) :kids []})

      ch
      (close-bracket {:text text :stack stack :ch ch :e e})

      :else
      (let [form (cond-> {:start (prefix-start ctx i s) :end e} (= t :metatag) (assoc :kind :metatag))]
        (conj (pop stack) (absorb-form text (peek stack) form))))))

(defn- form-tree
  "text's forms as a tree: a vector of top-level {:start :end :open :kids}.
  A bracketed form has :open (its opening bracket's offset) and :kids, an
  atom (symbol, keyword, number, string, character, regex) has neither.
  :start takes in any reader prefix written right before the form ('(..),
  #{..}, @x, #?(..)), and a leading ^meta or ^{...} metadata form stays
  attached the same way (it can stack: ^:a ^:b (def x 1)). A form right
  after #_ is discarded and never appears in the tree; #_ #_ discards two.
  Comments are skipped, a closer that doesn't match is ignored, and a form
  never closed is dropped along with everything in it, so an unfinished
  buffer has nothing to run there. The tokenizer reads the ? of #? (a
  reader conditional) and the _ of #_ as a symbol of its own; the ? is
  dropped and its two characters count as prefix, the _ is dropped and
  marks the discard."
  [text]
  (let [toks (filterv (fn [[t :as tok]] (not (or (= :comment t) (hash-marked-symbol? text "?" tok))))
                      (syntax/tokenize text))
        ctx {:text text :toks toks}
        n (count toks)]
    (loop [i 0 stack [{:kids []}]]
      (if (= i n)
        (:kids (first stack))
        (recur (inc i) (tree-step ctx stack i))))))

(defn- form-around
  "The form in forms (siblings in order) that holds caret: the one it is
  inside or at the start of, else the one it sits right after."
  [forms caret]
  (or (first (filter (fn [{:keys [start end]}] (and (<= start caret) (< caret end))) forms))
      (first (filter (fn [{:keys [end]}] (= end caret)) forms))))

(defn- comment-block?
  "True for a (comment ...) list: an unprefixed ( whose first child is the
  symbol comment."
  [text {:keys [start open kids]}]
  (let [head (first kids)]
    (boolean (and open (= start open) (= \( (.charAt ^String text (int open)))
                  head (nil? (:open head))
                  (= "comment" (subs text (:start head) (:end head)))))))

(defn top-level-forms
  "text's top-level forms, in order, as form-tree gives them: {:start :end},
  plus :open and :kids for a bracketed one."
  [text]
  (if (str/blank? text) [] (form-tree text)))

(defn comment-form?
  "True when form (one of top-level-forms') is a (comment ...) block."
  [text form]
  (comment-block? text form))

(defn- run-target
  "The form Run Form sends for top, the top-level form around the caret: top
  itself, or for a (comment ...) block the form directly inside it that holds
  the caret, nil when there is none."
  [text top caret]
  (if (comment-block? text top)
    (let [inner (form-around (rest (:kids top)) caret)]
      (when (and inner (> caret (:end (first (:kids top))))) inner))
    top))

(defn run-form
  "What Run Form (Cmd/Ctrl+Enter) sends, CIDER's C-c C-c: the top-level form
  around the caret, or, when that form is a (comment ...) block, the form
  directly inside it that holds the caret (clojure-toplevel-inside-comment-
  form). {:text :start :end}, or nil when the caret is on no form, on the
  comment symbol itself, or between the forms of a comment block. A caret
  right after a form's last character still counts as that form. Brackets
  in strings, comments and character literals don't count."
  [{:keys [text caret]}]
  (when-not (str/blank? text)
    (when-let [top (form-around (form-tree text) caret)]
      (when-let [form (run-target text top caret)]
        {:text (subs text (:start form) (:end form)) :start (:start form) :end (:end form)}))))

(defn- skip-blanks
  "The first offset in text at or after i that is not a space or tab, or
  where stop? (a fn of the offset) says to halt, or the text's end."
  [text i stop?]
  (let [n (count text)]
    (loop [j i]
      (if (and (< j n) (#{\space \tab} (.charAt ^String text (int j))) (not (stop? j)))
        (recur (inc j))
        j))))

(defn- settle-on-caret-line
  "Where the caret lands once the scan in new has reached offset i, the
  caret's line and after its code: keep the column if the caret was in
  leading space, else stop here. old, before (old's text up to caret),
  caret and col (its column) describe the caret's place in old."
  [{:keys [old new before caret col i]}]
  (let [line-start (inc (or (str/last-index-of (subs new 0 i) "\n") -1))
        blank-before? (str/blank? (subs before (- caret col)))
        at-code? (and (< caret (count old))
                      (not (Character/isWhitespace (.charAt ^String old caret))))]
    (cond
      ;; mid-line: step over the same spaces and tabs the caret had
      ;; after its code (format never changes space inside a line)
      (not blank-before?) (let [gap (count (take-while #{\space \tab} (reverse before)))]
                            (skip-blanks new i (fn [j] (>= (- j i) gap))))
      at-code? (skip-blanks new i (constantly false))
      :else (skip-blanks new i (fn [j] (>= (- j line-start) col))))))

(defn- scan-char
  "One step of the scan through new: ch with seen, the [seen-code
  seen-lines] counts of non-whitespace characters and newlines already
  passed, gives the [seen-code seen-lines] to continue with, or nil to stop
  at ch (the caret's code has been passed and ch starts the next thing)."
  [ch code [seen-code seen-lines]]
  (cond
    (and (= seen-code code) (not= ch \newline) (Character/isWhitespace ch)) [seen-code seen-lines]
    (= seen-code code) (when (= ch \newline) [seen-code (inc seen-lines)])
    (= ch \newline) [seen-code (inc seen-lines)]
    (Character/isWhitespace ch) [seen-code seen-lines]
    :else [(inc seen-code) seen-lines]))

(defn carry-caret
  "Where caret, an offset in old, lands in new when the two differ only in
  whitespace (formatting re-indents): after the same number of
  non-whitespace characters and newlines. A caret right before a line's
  code stays right before it; one inside the indentation (or on a blank
  line) keeps its column, up to the new indentation's end."
  [old new caret]
  (let [caret (min caret (count old))
        before (subs old 0 caret)
        code (count (remove #(Character/isWhitespace ^char %) before))
        lines (count (filter #(= \newline %) before))
        col (- caret (inc (or (str/last-index-of before "\n") -1)))
        n (count new)]
    (loop [i 0 seen [0 0]]
      (cond
        (and (= (nth seen 0) code) (= (nth seen 1) lines))
        (settle-on-caret-line {:old old :new new :before before :caret caret :col col :i i})

        (>= i n) n

        :else
        (if-let [seen' (scan-char (.charAt ^String new i) code seen)]
          (recur (inc i) seen')
          i)))))

(defn format-all-op
  "Re-indents the whole buffer as one edit (one undo step). nil when the
  text is already formatted. :stabilize, a (fn [text a b] text') or nil,
  runs over the formatter's output as a last step: the editor passes
  parinfer-edit/paren-stable while Parinfer is on or the project pins
  :format-style :paren-stable (and nothing under a :plain pin), so format
  output takes Paren mode's indentation and nothing ping-pongs on the
  next edit. A
  function, not a require: the plugin checker embeds this namespace's
  closure, which must not grow by the Parinfer engine."
  ([snap] (format-all-op snap {}))
  ([{:keys [text caret]} {:keys [stabilize]}]
   (let [fmt (syntax/format-code text)
         out (if stabilize (stabilize fmt 0 (count fmt)) fmt)]
     (when (not= out text)
       {:edits [[0 (count text) out]] :caret (carry-caret text out caret) :sel nil}))))

(defn- line-span
  "[start end] character offsets of lines l1..l2 (both inclusive) in text."
  [^String text l1 l2]
  (let [lines (str/split text #"\n" -1)
        starts (vec (reductions (fn [o l] (+ o (count l) 1)) 0 lines))]
    [(nth starts l1) (+ (nth starts l2) (count (nth lines l2)))]))

(defn format-selection-op
  "Re-indents the lines the selection spans, or the caret's line when there
  is no selection, as one edit. nil when nothing changes. :stabilize runs
  over the top-level forms those lines touch, as format-all-op's doc says;
  the formatter keeps line counts, so the touched lines are found again by
  line number."
  ([snap] (format-selection-op snap {}))
  ([{:keys [text caret sel-start sel-end]} {:keys [stabilize]}]
   (let [[s e] (if (and sel-start sel-end (< sel-start sel-end))
                 [sel-start sel-end]
                 (line-bounds text caret))
         fmt (if (< s e) (syntax/format-selection-text text s e) text)
         out (if (and stabilize (< s e))
               (let [line-of (fn [pos] (count (filter #(= \newline %) (subs text 0 pos))))
                     [a b] (line-span fmt (line-of s) (line-of (max s (dec e))))]
                 (stabilize fmt a b))
               fmt)]
     (when (not= out text)
       {:edits [[0 (count text) out]] :caret (carry-caret text out caret) :sel nil}))))

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

;; --- Parinfer (step 2 Part B) ----------------------------------------------

(def parinfer-cycle
  "The order Cmd/Ctrl+Alt+P walks, upstream's: off, Smart, Indent, Paren."
  [:off :smart :indent :paren])

(defn parinfer-step
  "The mode after choosing choice from current: a mode names itself, :next
  steps parinfer-cycle (an unknown current steps to :smart)."
  [current choice]
  (if (= :next choice)
    (let [i (count (take-while #(not= current %) parinfer-cycle))]
      (nth parinfer-cycle (mod (inc i) (count parinfer-cycle))))
    choice))

(defn parinfer-effective
  "The mode a tab runs under: its project's .jonquil.edn :parinfer when it
  names one (:off pins Parinfer off there), else the global setting."
  [override global]
  (or override global))

(defn parinfer-mode-word
  "A mode as the status bar writes it: off stays lower case, a mode is
  capitalized."
  [mode]
  (if (= :off mode) "off" (str/capitalize (name mode))))

(defn parinfer-pinned-note
  "The suffix a global mode change shows while the active tab's project
  pins another mode, or nil: no override, or the global landed on the pin
  itself, says nothing."
  [global override]
  (when (and override (not= global override))
    (str " (this project says " (parinfer-mode-word override) ")")))

(defn parinfer-label-text
  "The status bar's text for mode, nil for :off (the label hides)."
  [mode]
  (when (not= :off mode)
    (str "Parinfer: " (parinfer-mode-word mode))))

(defn parinfer-status-text
  "The label for the active tab: its effective mode, with \" (project)\"
  when the tab's .jonquil.edn decided. nil hides the label: a plain :off,
  and a pinned :off while the global is off too, since nothing differs.
  A pinned :off under a non-off global stays visible, so the
  tab that ignores the global says why it's quiet."
  [effective override? global]
  (cond
    (and override? (= :off effective)) (when (not= :off global) "Parinfer: off (project)")
    override? (str "Parinfer: " (parinfer-mode-word effective) " (project)")
    :else (parinfer-label-text effective)))
