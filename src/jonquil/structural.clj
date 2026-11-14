(ns jonquil.structural
  "Structural editing as edit ops: slurp and barf, raise, splice, wrap, kill
  and copy a form, and moves by form. Each takes a snapshot ({:text :caret})
  and answers an op, {:edits [[start end replacement] ...] :caret n} with
  the edits ascending, as jonquil.edit's fns do, or nil when it doesn't
  apply. kill-form and copy-form add :copy, the text for the clipboard. No
  GTK here.

  Forms come from jonquil.commands/form-tree, so brackets in strings,
  comments and character literals don't count, a reader prefix ('x, #{..},
  ^meta) belongs to its form, and an unclosed list has no forms: nothing
  applies inside one."
  (:require [clojure.string :as str]
            [jonquil.commands :as commands]))

;; --- where the caret is ------------------------------------------------------

(defn- inside?
  "True when caret is between form's brackets: after its opener, at or
  before its closer."
  [{:keys [open end]} caret]
  (and open (< open caret) (< caret end)))

(defn- place
  "The caret's place in text: {:lists [outermost ... innermost] :forms
  siblings}. :lists are the lists around the caret and :forms the forms
  beside it, the innermost list's or the top level's."
  [{:keys [text caret]}]
  (loop [forms (commands/form-tree text) lists []]
    (if-let [l (first (filter #(inside? % caret) forms))]
      (recur (:kids l) (conj lists l))
      {:lists lists :forms forms})))

(defn- form-at
  "The form of forms the caret is on: the one it is in or at the start of,
  else the next one after it."
  [forms caret]
  (or (first (filter (fn [{:keys [start end]}] (and (<= start caret) (< caret end))) forms))
      (first (filter (fn [{:keys [start]}] (>= start caret)) forms))))

(defn- siblings-of
  "The forms beside list l: its parent's, the list before it in lists or
  the top level's."
  [text lists l]
  (let [i (.indexOf ^java.util.List lists l)]
    (if (pos? i)
      (:kids (nth lists (dec i)))
      (commands/form-tree text))))

(defn- opener
  "form's opening text: its reader prefix and its bracket."
  [text {:keys [start open]}]
  (subs text start (inc open)))

(defn- closer
  "form's closing bracket, as a string."
  [text {:keys [end]}]
  (subs text (dec end) end))

;; --- slurp and barf ----------------------------------------------------------

(defn slurp-forward
  "The list around the caret takes in the form after it: \"(a|) b\" becomes
  \"(a| b)\". When the innermost list has nothing after it, the next one
  out slurps."
  [{:keys [text caret] :as snapshot}]
  (let [{:keys [lists]} (place snapshot)]
    (first
     (for [l (rseq lists)
           :let [n (first (filter #(>= (:start %) (:end l)) (siblings-of text lists l)))]
           :when n]
       (let [c (dec (:end l))]
         {:edits [[c (if (empty? (:kids l)) (:start n) (inc c)) ""]
                  [(:end n) (:end n) (closer text l)]]
          :caret caret})))))

(defn barf-forward
  "The list around the caret gives up its last form: \"(a| b)\" becomes
  \"(a|) b\"."
  [{:keys [text caret] :as snapshot}]
  (when-let [l (peek (:lists (place snapshot)))]
    (when-let [k (peek (:kids l))]
      (let [c (dec (:end l))
            before (peek (pop (:kids l)))
            [at upto added] (if before
                              [(:end before) (:end before) (closer text l)]
                              [(inc (:open l)) (:start k) (str (closer text l) " ")])]
        {:edits [[at upto added] [c (inc c) ""]]
         :caret (if (> caret at) (+ caret (- (count added) (- upto at))) caret)}))))

(defn slurp-backward
  "The list around the caret takes in the form before it: \"a (|b)\"
  becomes \"(a |b)\". When the innermost list has nothing before it, the
  next one out slurps."
  [{:keys [text caret] :as snapshot}]
  (let [{:keys [lists]} (place snapshot)]
    (first
     (for [l (rseq lists)
           :let [p (last (filter #(<= (:end %) (:start l)) (siblings-of text lists l)))]
           :when p]
       {:edits [[(:start p) (:start p) (opener text l)]
                [(:start l) (inc (:open l)) ""]]
        :caret caret}))))

(defn barf-backward
  "The list around the caret gives up its first form: \"(a |b)\" becomes
  \"a (|b)\"."
  [{:keys [text caret] :as snapshot}]
  (when-let [l (peek (:lists (place snapshot)))]
    (when-let [k (first (:kids l))]
      (let [o (opener text l)
            n (count o)
            c (dec (:end l))
            second-kid (second (:kids l))]
        (if second-kid
          {:edits [[(:start l) (inc (:open l)) ""]
                   [(:start second-kid) (:start second-kid) o]]
           :caret (if (>= caret (:start second-kid)) caret (- caret n))}
          {:edits [[(:start l) (inc (:open l)) ""]
                   [(:end k) c (str " " o)]]
           :caret (if (>= caret (:end k)) (inc (:end k)) (- caret n))})))))

;; --- raise, splice, wrap -----------------------------------------------------

(defn raise
  "The form at the caret replaces the list around it: \"(a |b c)\" becomes
  \"|b\"."
  [{:keys [text caret] :as snapshot}]
  (let [{:keys [lists forms]} (place snapshot)]
    (when-let [l (peek lists)]
      (when-let [f (form-at forms caret)]
        {:edits [[(:start l) (:end l) (subs text (:start f) (:end f))]]
         :caret (+ (:start l) (max 0 (- caret (:start f))))}))))

(defn splice
  "The list around the caret loses its brackets: \"(a |b)\" becomes \"a |b\"."
  [{:keys [text caret] :as snapshot}]
  (when-let [l (peek (:lists (place snapshot)))]
    (let [c (dec (:end l))]
      {:edits [[(:start l) (inc (:open l)) ""] [c (inc c) ""]]
       :caret (- caret (count (opener text l)))})))

(defn- wrap
  "The form at the caret goes inside open and close; with no form there, an
  empty pair is typed at the caret."
  [{:keys [caret] :as snapshot} open close]
  (if-let [f (form-at (:forms (place snapshot)) caret)]
    {:edits [[(:start f) (:start f) open] [(:end f) (:end f) close]]
     :caret (if (>= caret (:start f)) (inc caret) caret)}
    {:edits [[caret caret (str open close)]] :caret (inc caret)}))

(defn wrap-round "Wraps the form at the caret in ()." [snapshot] (wrap snapshot "(" ")"))
(defn wrap-square "Wraps the form at the caret in []." [snapshot] (wrap snapshot "[" "]"))
(defn wrap-curly "Wraps the form at the caret in {}." [snapshot] (wrap snapshot "{" "}"))

;; --- kill and copy -----------------------------------------------------------

(defn copy-form
  "The form at the caret, for the clipboard: {:copy text}."
  [{:keys [text caret] :as snapshot}]
  (when-let [f (form-at (:forms (place snapshot)) caret)]
    {:copy (subs text (:start f) (:end f))}))

(defn kill-form
  "Removes the form at the caret with the gap after it, or the gap before
  it when it is the last one: \"(a |b c)\" becomes \"(a |c)\". :copy is the
  form's text."
  [{:keys [text caret] :as snapshot}]
  (let [forms (:forms (place snapshot))]
    (when-let [f (form-at forms caret)]
      (let [after (first (filter #(>= (:start %) (:end f)) forms))
            before (last (filter #(<= (:end %) (:start f)) forms))
            [from to] (cond after [(:start f) (:start after)]
                            before [(:end before) (:end f)]
                            :else [(:start f) (:end f)])]
        {:edits [[from to ""]] :caret from :copy (subs text (:start f) (:end f))}))))

;; --- moves -------------------------------------------------------------------

(defn- move [caret] {:edits [] :caret caret})

(defn forward
  "The caret goes to the end of the form it is on or before, or out past
  the closer when no form is left in the list."
  [{:keys [caret] :as snapshot}]
  (let [{:keys [lists forms]} (place snapshot)]
    (if-let [f (form-at forms caret)]
      (move (:end f))
      (when-let [l (peek lists)] (move (:end l))))))

(defn backward
  "The caret goes to the start of the form it is in or after, or out before
  the opener when no form is before it in the list."
  [{:keys [caret] :as snapshot}]
  (let [{:keys [lists forms]} (place snapshot)]
    (if-let [f (last (filter #(< (:start %) caret) forms))]
      (move (:start f))
      (when-let [l (peek lists)] (move (:start l))))))

(defn up
  "The caret goes to the start of the list around it."
  [snapshot]
  (when-let [l (peek (:lists (place snapshot)))]
    (move (:start l))))

(defn down
  "The caret goes inside the next list at or after it."
  [{:keys [caret] :as snapshot}]
  (when-let [l (first (filter #(and (:open %) (>= (:open %) caret)) (:forms (place snapshot))))]
    (move (inc (:open l)))))

;; --- selecting by form -------------------------------------------------------

(defn- selection
  "The snapshot's selection as [start end], the caret twice when there is none."
  [{:keys [caret sel-start sel-end]}]
  (if (and sel-start sel-end (< sel-start sel-end)) [sel-start sel-end] [caret caret]))

(defn- select [[a b]] {:edits [] :caret b :sel [a b]})

(defn- covering
  "The smallest form of text that holds [a b] and is larger than it, as
  [start end], or nil."
  [text a b]
  (loop [forms (commands/form-tree text) best nil]
    (if-let [f (first (filter #(and (<= (:start %) a) (<= b (:end %))) forms))]
      (recur (:kids f) (if (= [a b] [(:start f) (:end f)]) best [(:start f) (:end f)]))
      best)))

(defonce ^{:private true
           :doc "What Expand selection grew from, for Shrink selection: {:text
  the buffer then, :now the range selected last, :stack the ranges before it}."}
  grown
  (atom nil))

(defn forget-selection!
  "Drops what Shrink selection would go back to."
  []
  (reset! grown nil))

(defn expand-selection
  "Selects the form at the caret, then on each further call the next form
  out. Shrink selection goes back the way it came."
  [{:keys [text] :as snapshot}]
  (let [[a b :as now] (selection snapshot)]
    (when-let [wider (covering text a b)]
      (swap! grown (fn [g]
                     {:text text :now wider
                      :stack (if (and g (= text (:text g)) (= now (:now g)))
                               (conj (:stack g) now)
                               [now])}))
      (select wider))))

(defn shrink-selection
  "Goes back one step of Expand selection: the range selected before, or
  the bare caret. nil when the buffer or the selection changed since."
  [{:keys [text] :as snapshot}]
  (let [g @grown]
    (when (and g (= text (:text g)) (= (selection snapshot) (:now g)) (seq (:stack g)))
      (let [[a b :as prev] (peek (:stack g))]
        (reset! grown (assoc g :now prev :stack (pop (:stack g))))
        (if (= a b) (move a) (select prev))))))

(defn select-forward
  "Extends the selection to the end of the next form."
  [snapshot]
  (let [[a b] (selection snapshot)
        forms (:forms (place (assoc snapshot :caret b)))]
    (when-let [f (form-at forms b)]
      (select [a (:end f)]))))

(defn select-backward
  "Extends the selection back to the start of the form before it."
  [snapshot]
  (let [[a b] (selection snapshot)
        forms (:forms (place (assoc snapshot :caret a)))]
    (when-let [f (last (filter #(< (:start %) a) forms))]
      {:edits [] :caret (:start f) :sel [(:start f) b]})))

;; --- dragging, transposing, joining and splitting -----------------------------

(defn- form-on
  "The form of forms the caret touches, in it or at either end, else the
  next one after it."
  [forms caret]
  (or (first (filter (fn [{:keys [start end]}] (and (<= start caret) (<= caret end))) forms))
      (form-at forms caret)))

(defn- swap
  "The edits that swap forms a and b of text, a first."
  [text a b]
  [[(:start a) (:end a) (subs text (:start b) (:end b))]
   [(:start b) (:end b) (subs text (:start a) (:end a))]])

(defn drag-forward
  "Swaps the form at the caret with the one after it; the caret goes along."
  [{:keys [text caret] :as snapshot}]
  (let [forms (:forms (place snapshot))]
    (when-let [f (form-on forms caret)]
      (when-let [n (first (filter #(>= (:start %) (:end f)) forms))]
        {:edits (swap text f n)
         :caret (+ (:start f) (- (:end n) (:start n)) (- (:start n) (:end f))
                   (max 0 (- caret (:start f))))}))))

(defn drag-backward
  "Swaps the form at the caret with the one before it; the caret goes along."
  [{:keys [text caret] :as snapshot}]
  (let [forms (:forms (place snapshot))]
    (when-let [f (form-on forms caret)]
      (when-let [p (last (filter #(<= (:end %) (:start f)) forms))]
        {:edits (swap text p f)
         :caret (+ (:start p) (max 0 (- caret (:start f))))}))))

(defn- around
  "[before after]: the form ending at or before the caret and the one
  starting at or after it, either nil."
  [forms caret]
  [(last (filter #(<= (:end %) caret) forms))
   (first (filter #(>= (:start %) caret) forms))])

(defn transpose
  "Swaps the two forms around the caret, leaving it after both."
  [{:keys [text caret] :as snapshot}]
  (let [[a b] (around (:forms (place snapshot)) caret)]
    (when (and a b)
      {:edits (swap text a b) :caret (:end b)})))

(defn- string-form?
  "True when form is a string literal."
  [text {:keys [start end open]}]
  (and (not open) (> (- end start) 1)
       (= \" (.charAt ^String text (int start)))
       (= \" (.charAt ^String text (int (dec end))))))

(defn join
  "Joins the two lists of one kind, or the two strings, around the caret:
  \"(a)| (b)\" becomes \"(a| b)\"."
  [{:keys [text caret] :as snapshot}]
  (let [[a b] (around (:forms (place snapshot)) caret)]
    (cond
      (not (and a b)) nil
      (and (:open a) (:open b) (= (opener text a) (opener text b)))
      {:edits [[(dec (:end a)) (inc (:open b)) " "]] :caret (dec (:end a))}
      (and (string-form? text a) (string-form? text b))
      {:edits [[(dec (:end a)) (inc (:start b)) ""]] :caret (dec (:end a))})))

(defn split
  "Splits the list around the caret in two at the caret, or the string it
  is in: \"(a |b)\" becomes \"(a)| (b)\"."
  [{:keys [text caret] :as snapshot}]
  (let [{:keys [lists forms]} (place snapshot)
        inside (first (filter #(and (< (:start %) caret) (< caret (:end %))) forms))]
    (cond
      (and inside (string-form? text inside))
      {:edits [[caret caret "\" \""]] :caret (inc caret)}
      inside nil
      :else
      (when-let [l (peek lists)]
        (let [[p n] (around forms caret)
              from (if p (:end p) caret)
              to (if n (:start n) caret)]
          {:edits [[from to (str (closer text l) " " (opener text l))]]
           :caret (inc from)})))))

;; --- more kills ---------------------------------------------------------------

(defn- kill
  "The op that removes [from to) of text, with what it removed for the
  clipboard; nil for an empty range."
  [text from to]
  (when (< from to)
    {:edits [[from to ""]] :caret from :copy (subs text from to)}))

(defn kill-list-forward
  "Removes everything from the caret to the end of the list around it."
  [{:keys [text caret] :as snapshot}]
  (when-let [l (peek (:lists (place snapshot)))]
    (kill text caret (dec (:end l)))))

(defn kill-list-backward
  "Removes everything from the start of the list around the caret up to it."
  [{:keys [text caret] :as snapshot}]
  (when-let [l (peek (:lists (place snapshot)))]
    (kill text (inc (:open l)) caret)))

(defn- in-string
  "The string literal the caret is strictly inside, or nil."
  [text forms caret]
  (first (filter #(and (string-form? text %) (< (:start %) caret) (< caret (:end %))) forms)))

(defn kill-right
  "Removes from the caret to the end of the line, taking whole any form
  that starts on the line, and never past the closer of the list around
  the caret. Inside a string it stops at the closing quote."
  [{:keys [text caret] :as snapshot}]
  (let [{:keys [lists forms]} (place snapshot)]
    (if-let [s (in-string text forms caret)]
      (kill text caret (dec (:end s)))
      (let [limit (if-let [l (peek lists)] (dec (:end l)) (count text))
            eol (or (str/index-of text "\n" caret) (count text))
            to (reduce (fn [to f] (if (and (< (:start f) eol) (> (:end f) to)) (:end f) to))
                       eol
                       (filter #(> (:end %) caret) forms))]
        (kill text caret (min limit (max to caret)))))))

(defn kill-left
  "Removes from the start of the line to the caret, taking whole any form
  that ends on the line, and never past the opener of the list around the
  caret. Inside a string it stops at the opening quote."
  [{:keys [text caret] :as snapshot}]
  (let [{:keys [lists forms]} (place snapshot)]
    (if-let [s (in-string text forms caret)]
      (kill text (inc (:start s)) caret)
      (let [limit (if-let [l (peek lists)] (inc (:open l)) 0)
            bol (inc (or (str/last-index-of text "\n" (dec caret)) -1))
            from (reduce (fn [from f] (if (and (> (:end f) bol) (< (:start f) from)) (:start f) from))
                         bol
                         (filter #(< (:start %) caret) forms))]
        (kill text (max limit (min from caret)) caret)))))

(defn kill-form-backward
  "Removes the form before the caret, up to the caret."
  [{:keys [text caret] :as snapshot}]
  (when-let [p (last (filter #(<= (:end %) caret) (:forms (place snapshot))))]
    (kill text (:start p) caret)))

(defn splice-killing-forward
  "Removes everything from the caret to the end of the list around it,
  then the list's brackets."
  [{:keys [text caret] :as snapshot}]
  (when-let [l (peek (:lists (place snapshot)))]
    {:edits [[(:start l) (inc (:open l)) ""] [caret (:end l) ""]]
     :caret (- caret (count (opener text l)))}))

(defn splice-killing-backward
  "Removes everything from the start of the list around the caret up to
  it, then the list's brackets."
  [{:keys [caret] :as snapshot}]
  (when-let [l (peek (:lists (place snapshot)))]
    {:edits [[(:start l) caret ""] [(dec (:end l)) (:end l) ""]]
     :caret (:start l)}))

(defn convolute
  "Turns the two lists around the caret inside out: what is before the
  caret in the inner list becomes the head of the outer one.
  \"(let [x 1] (frob |a b))\" becomes \"(frob (let [x 1] |a b))\"."
  [{:keys [text caret] :as snapshot}]
  (let [lists (:lists (place snapshot))
        inner (peek lists)
        outer (when (> (count lists) 1) (peek (pop lists)))]
    (when (and inner outer)
      (let [head (str/trimr (subs text (inc (:open inner)) caret))
            tail (str/triml (subs text caret (dec (:end inner))))
            before (str (opener text inner) head (when (seq head) " ")
                        (opener text outer) (subs text (inc (:open outer)) (:start inner)))
            after (str tail (subs text (:end inner) (dec (:end outer)))
                       (closer text outer) (closer text inner))]
        {:edits [[(:start outer) (:end outer) (str before after)]]
         :caret (+ (:start outer) (count before))}))))

;; --- more wraps, and rewrap ---------------------------------------------------

(defn wrap-set "Wraps the form at the caret in #{}." [snapshot]
  (some-> (wrap snapshot "#{" "}") (update :caret inc)))
(defn wrap-quote "Wraps the form at the caret in \"\"." [snapshot] (wrap snapshot "\"" "\""))

(defn- rewrap
  "The list around the caret gets open and close as its brackets. A reader
  prefix stays, except a set's #, which goes with its brace."
  [{:keys [text caret] :as snapshot} open close]
  (when-let [l (peek (:lists (place snapshot)))]
    (let [old (opener text l)
          prefix (subs old 0 (- (count old) (if (str/ends-with? old "#{") 2 1)))
          new (str prefix open)]
      {:edits [[(:start l) (inc (:open l)) new] [(dec (:end l)) (:end l) close]]
       :caret (+ caret (- (count new) (count old)))})))

(defn rewrap-round "Changes the brackets around the caret to ()." [snapshot] (rewrap snapshot "(" ")"))
(defn rewrap-square "Changes the brackets around the caret to []." [snapshot] (rewrap snapshot "[" "]"))
(defn rewrap-curly "Changes the brackets around the caret to {}." [snapshot] (rewrap snapshot "{" "}"))
(defn rewrap-set "Changes the brackets around the caret to #{}." [snapshot] (rewrap snapshot "#{" "}"))

;; --- more moves ---------------------------------------------------------------

(defn forward-up
  "The caret goes out of the list around it, past its closer."
  [snapshot]
  (when-let [l (peek (:lists (place snapshot)))]
    (move (:end l))))

(defn backward-down
  "The caret goes into the list before it, from the back."
  [{:keys [caret] :as snapshot}]
  (when-let [l (last (filter #(and (:open %) (<= (:end %) caret)) (:forms (place snapshot))))]
    (move (dec (:end l)))))

(defn list-end
  "The caret goes to the end of the list around it, before its closer."
  [snapshot]
  (when-let [l (peek (:lists (place snapshot)))]
    (move (dec (:end l)))))

(defn list-start
  "The caret goes to the start of the list around it, after its opener."
  [snapshot]
  (when-let [l (peek (:lists (place snapshot)))]
    (move (inc (:open l)))))
