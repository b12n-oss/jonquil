(ns jonquil.parinfer-edit
  "Parinfer for the editor. adjust takes the text before and after one edit
  and returns jonquil.edit's {:edits :caret} for what Parinfer wants there,
  or nil. It runs jonquil.parinfer on the top-level forms the edit touched,
  never the whole buffer: a Smart pass over a 4,574-line file takes about
  426 ms (Parinfer step 1, 2026-10-29).

  Smart and Indent mode trust indentation, so on a form whose indentation
  disagrees with its brackets they move closers. A form is checked before
  each edit: when Paren mode would change it, this edit fixes its
  indentation first, and the form is stable from then on. Opening a file
  changes nothing, and forms nobody edits stay as they are (the user's
  choice, 2026-10-29). Pure: no GTK."
  (:require [clojure.string :as str]
            [jonquil.parinfer :as parinfer]
            [jonquil.syntax :as syntax]))

;; --- lines -----------------------------------------------------------------

(defn- line-start
  "The offset where the line holding pos starts."
  [^String text pos]
  (loop [i pos]
    (if (and (pos? i) (not= \newline (.charAt text (dec i))))
      (recur (dec i))
      i)))

(defn- line-end
  "The offset where the line holding pos ends, before its \\n or \\r\\n."
  [^String text pos]
  (let [n (count text)
        e (loop [i pos]
            (if (and (< i n) (not= \newline (.charAt text i)))
              (recur (inc i))
              i))]
    (if (and (> e pos) (= \return (.charAt text (dec e))))
      (dec e)
      e)))

(defn- line-col
  "[line x] of offset off in text, both from 0. A \\r ending a line belongs
  to that line, so CRLF text gives the same x as LF text (upstream's
  line-col->offset doesn't, see UPSTREAM.md)."
  [^String text off]
  (let [s (line-start text off)]
    [(count (filter #(= \newline %) (subs text 0 s))) (- off s)]))

(defn- offset
  "The offset of [line x] in text, x clamped to that line."
  [^String text line x]
  (let [n (count text)
        s (loop [i 0 l 0]
            (cond (= l line) i
                  (>= i n) n
                  (= \newline (.charAt text i)) (recur (inc i) (inc l))
                  :else (recur (inc i) l)))]
    (+ s (max 0 (min x (- (line-end text s) s))))))

(defn- diff
  "[p a-end b-end]: a[p, a-end) became b[p, b-end), with the longest common
  prefix and suffix left out."
  [^String a ^String b]
  (let [na (count a) nb (count b) m (min na nb)
        p (loop [i 0] (if (and (< i m) (= (.charAt a i) (.charAt b i))) (recur (inc i)) i))
        q (loop [i 0] (if (and (< i (- m p)) (= (.charAt a (- na 1 i)) (.charAt b (- nb 1 i))))
                        (recur (inc i))
                        i))]
    [p (- na q) (- nb q)]))

;; --- the region ------------------------------------------------------------

(defn- grow
  "[s e] widened to whole lines of every top-level form in spans ([open
  close] offsets) that overlaps it, until nothing more does."
  [text spans [s e]]
  (let [[s' e'] (reduce (fn [[s e] [o c]]
                          (if (and (<= o e) (>= c s))
                            [(min s (line-start text o)) (max e (line-end text c))]
                            [s e]))
                        [s e]
                        spans)]
    (if (= [s e] [s' e']) [s e] (recur text spans [s' e']))))

(defn- boundary?
  "A line starting at L opens a region by indentation: its first char is at
  column 0, isn't whitespace or a closer, and isn't inside a string."
  [^String text strings L]
  (and (< L (count text))
       (not (contains? #{\space \tab \return \newline \) \] \}} (.charAt text L)))
       (not-any? (fn [[s e]] (< s L e)) strings)))

(defn- indent-region
  "The region for unbalanced text: from the last boundary line at or before
  a's line to the line before the next boundary after b's line."
  [^String text a b]
  (let [n (count text)
        strings (for [[t s e] (syntax/tokenize text) :when (= t :string)] [s e])
        s (loop [L (line-start text a)]
            (if (or (zero? L) (boundary? text strings L)) L (recur (line-start text (dec L)))))
        e (loop [L (inc (line-end text b))]
            (cond (>= L n) (line-end text n)
                  (boundary? text strings L) (line-end text (dec L))
                  :else (recur (let [nl (line-end text L)]
                                 (inc (if (and (< nl n) (= \return (.charAt text nl))) (inc nl) nl))))))]
    [s (max e (line-end text b))]))

(defn region
  "[start end]: the whole lines of the top-level forms in text that the
  range [a b] touches (pos alone for a caret). Balanced text is split by
  brackets; text with an unmatched bracket by indentation, the way
  Parinfer reads it, with a string's column-0 lines left inside the string."
  ([text pos] (region text pos pos))
  ([text a b]
   (let [{:keys [bracket-tokens matches unmatched]} (syntax/compute-brackets text)
         s (line-start text a)
         e (line-end text b)]
     (if (seq unmatched)
       (indent-region text a b)
       (grow text
             (for [[o _ depth kind] bracket-tokens :when (and (zero? depth) (= kind :open))]
               [o (get matches o)])
             [s e])))))

;; --- running Parinfer ------------------------------------------------------

(defn- leading-ws [^String line]
  (count (take-while #(or (= \space %) (= \tab %)) line)))

(defn- rebased
  "Smart or Indent mode run on the form with its indentation fixed (fixed,
  Paren mode's text for P) and the edit re-applied on top, or nil when the
  edit isn't a change within one line's code that can be moved across. P is
  the form before the edit, change and rel-caret are relative to it."
  [mode-fn ^String P ^String fixed change rel-caret]
  (let [P-lines (str/split P #"\n" -1)
        F-lines (str/split fixed #"\n" -1)
        {:keys [line-no x old-text new-text]} change
        p-line (get P-lines line-no)
        f-line (get F-lines line-no)
        ip (leading-ws (or p-line ""))
        x' (+ (- x ip) (leading-ws (or f-line "")))
        c0 (offset P line-no x)]
    (when (and p-line f-line
               (= (count P-lines) (count F-lines))
               (not (str/includes? old-text "\n"))
               (>= x ip)
               (<= (+ x' (count old-text)) (count f-line))
               (= old-text (subs f-line x' (+ x' (count old-text))))
               (<= c0 rel-caret (+ c0 (count new-text))))
      (let [R2 (str/join "\n" (assoc F-lines line-no
                                     (str (subs f-line 0 x') new-text (subs f-line (+ x' (count old-text))))))
            [cl cx] (line-col R2 (+ (offset R2 line-no 0) x' (- rel-caret c0)))]
        (mode-fn R2 {:changes [(assoc change :x x')] :cursor-line cl :cursor-x cx})))))

(defn- run-mode
  "Parinfer's result for the edited form R. P is the form before the edit."
  [mode P R change rel-caret opts]
  (if (= mode :paren)
    (parinfer/paren-mode R opts)
    (let [mode-fn (if (= mode :indent) parinfer/indent-mode parinfer/smart-mode)
          pr (parinfer/paren-mode P)
          fixed (when (and (:success pr) (not= (:text pr) P)) (:text pr))]
      (if fixed
        (or (rebased mode-fn P fixed change rel-caret)
            (parinfer/paren-mode R opts))
        (mode-fn R (assoc opts :changes [change]))))))

(defn adjust
  "What Parinfer wants after one edit, as jonquil.edit's {:edits [[s e r]]
  :caret n}, or nil when it wants nothing, can't run, or fails. mode is
  :smart, :indent or :paren; prev-text and prev-caret are the buffer and
  caret before the edit, text and caret after it."
  [{:keys [mode prev-text prev-caret text caret]}]
  (let [prev (or prev-text "")
        text (or text "")]
    (when (not= prev text)
      (let [[p old-end new-end] (diff prev text)
            [s e] (region prev p old-end)
            e' (+ e (- (count text) (count prev)))
            P (subs prev s e)
            R (subs text s e')
            ls (line-start text p)
            change {:line-no (first (line-col R (- ls s)))
                    :x (- p ls)
                    :old-text (subs prev p old-end)
                    :new-text (subs text p new-end)}
            caret-in? (<= s caret e')
            opts (if caret-in?
                   (let [[cl cx] (line-col R (- caret s))]
                     (cond-> {:cursor-line cl :cursor-x cx}
                       (and prev-caret (<= s prev-caret e))
                       (merge (let [[pl px] (line-col P (- prev-caret s))]
                                {:prev-cursor-line pl :prev-cursor-x px}))))
                   {})
            result (run-mode mode P R change (- caret s) opts)
            NR (:text result)]
        (when (and (:success result) (string? NR) (not= NR R))
          (let [[q r-end n-end] (diff R NR)]
            {:edits [[(+ s q) (+ s r-end) (subs NR q n-end)]]
             :caret (cond
                      caret-in? (+ s (offset NR (:cursor-line result) (:cursor-x result)))
                      (> caret e') (+ caret (- (count NR) (count R)))
                      :else caret)}))))))
