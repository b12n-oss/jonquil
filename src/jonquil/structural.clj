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
  (:require [jonquil.commands :as commands]))

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
