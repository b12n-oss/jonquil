(ns jonquil.parinfer-edit
  "Parinfer for the editor. adjust takes the text before and after one edit
  and returns jonquil.edit's {:edits :caret} for what Parinfer wants there,
  or nil. It runs jonquil.parinfer on the top-level forms the edit touched,
  never the whole buffer: a Smart pass over a 4,574-line file takes about
  426 ms (measured 2026-10-29).

  Smart and Indent mode trust indentation, so on a form whose indentation
  disagrees with its brackets they move closers. A form is checked before
  each edit: when Paren mode would change it, this edit fixes its
  indentation first, and later edits usually find it stable (one that
  unbalances the form can leave Paren mode refusing). Opening a file
  changes nothing, and forms nobody edits stay as they are, by design. Pure: no GTK."
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
  line-col->offset didn't; our copy counts CRLF, and the editor keeps
  these helpers, see UPSTREAM.md)."
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

(defn remap-offset
  "Offset x in text `from`, re-found in text `to` by its line and column,
  both clamped to what `to` has. A Parinfer fix moves brackets and
  indentation but keeps the user's lines, so the line/column frame crosses
  it; the editor carries a selection over a fix this way."
  [from to x]
  (let [[l c] (line-col from x)]
    (offset to l c)))

(defn- diff
  "[p a-end b-end]: a[p, a-end) became b[p, b-end), with the longest common
  prefix and suffix left out. limit caps the prefix: an edit's change ends
  at the caret, so without it an insert or delete that repeats the text
  after it would slide into the next form."
  [^String a ^String b & [limit]]
  (let [na (count a) nb (count b)
        n (min na nb)
        m (cond-> n limit (min (max 0 limit)))
        p (loop [i 0] (if (and (< i m) (= (.charAt a i) (.charAt b i))) (recur (inc i)) i))
        q (loop [i 0] (if (and (< i (- n p)) (= (.charAt a (- na 1 i)) (.charAt b (- nb 1 i))))
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

(defn- inside-span?
  "Whether some [s e] in spans (sorted by s, not overlapping) has s < L < e."
  [spans L]
  (loop [lo 0 hi (dec (count spans))]
    (when (<= lo hi)
      (let [mid (quot (+ lo hi) 2)
            [s e] (nth spans mid)]
        (cond (<= L s) (recur lo (dec mid))
              (< L e) true
              :else (recur (inc mid) hi))))))

(defn- boundary?
  "A line starting at L opens a region by indentation: its first char is at
  column 0, isn't whitespace, a closer or a ; comment (Parinfer doesn't take
  indentation from comment lines), and isn't inside a string. strings is a
  vector of [start end] spans sorted by start."
  [^String text strings L]
  (and (< L (count text))
       (not (contains? #{\space \tab \return \newline \) \] \} \;} (.charAt text L)))
       (not (inside-span? strings L))))

(defn- indent-region
  "The region for unbalanced text: from the last boundary line at or before
  a's line to the line before the next boundary after b's line."
  [^String text a b]
  (let [n (count text)
        spans (vec (for [[t s e] (syntax/tokenize-equal text)
                         :when (contains? #{:string :regex} t)]
                     [s e]))
        s (loop [L (line-start text a)]
            (if (or (zero? L) (boundary? text spans L)) L (recur (line-start text (dec L)))))
        e (loop [L (inc (line-end text b))]
            (cond (>= L n) (line-end text n)
                  ;; (dec L) is the newline ending the previous line; a \r
                  ;; before it belongs to that line's ending, not the region.
                  (boundary? text spans L)
                  (let [e (dec L)]
                    (if (and (pos? e) (= \return (.charAt text (dec e)))) (dec e) e))
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
   (let [{:keys [bracket-tokens matches unmatched]} (syntax/compute-brackets-equal text)
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
               ;; Paren mode also rewrites a tab in code as two spaces, so
               ;; the code before x must be unchanged for x' to hold.
               (= (subs p-line ip x) (subs f-line (leading-ws f-line) x'))
               (<= (+ x' (count old-text)) (count f-line))
               (= old-text (subs f-line x' (+ x' (count old-text))))
               (<= c0 rel-caret (+ c0 (count new-text))))
      (let [R2 (str/join "\n" (assoc F-lines line-no
                                     (str (subs f-line 0 x') new-text (subs f-line (+ x' (count old-text))))))
            [cl cx] (line-col R2 (+ (offset R2 line-no 0) x' (- rel-caret c0)))]
        (mode-fn R2 {:changes [(assoc change :x x')] :cursor-line cl :cursor-x cx})))))

(defn- indent-width
  "A line's indentation width, a tab counting two, as Parinfer reads it."
  [^String line]
  (reduce + (map #(if (= \tab %) 2 1) (take-while #(or (= \space %) (= \tab %)) line))))

(defn- reindents?
  "Whether Paren mode's text F for P changes some line's indentation width.
  A change that only tidies whitespace in code, such as (foo ) to (foo),
  doesn't count: Smart mode leaves that behind itself."
  [^String P ^String F]
  (let [ps (str/split P #"\n" -1) fs (str/split F #"\n" -1)]
    (or (not= (count ps) (count fs))
        (not= (map indent-width ps) (map indent-width fs)))))

(defn- run-mode
  "Parinfer's result for the edited form R. P is the form before the edit.
  prev-consistent? skips the re-indent probe: the editor only sets it
  when P is the form its own previous action produced, which Paren mode
  cannot want to change."
  [mode P R change rel-caret opts prev-consistent?]
  (if (= mode :paren)
    (parinfer/paren-mode R opts)
    (let [mode-fn (if (= mode :indent) parinfer/indent-mode parinfer/smart-mode)
          pr (when-not prev-consistent? (parinfer/paren-mode P))
          fixed (when (and pr (:success pr) (reindents? P (:text pr))) (:text pr))]
      (if fixed
        ;; A refused re-apply is nil, so the Paren fallback really runs; an
        ;; "unhandled" one is kept, so the shim gap is named instead of
        ;; silently taking Paren mode's fix.
        (or (when-let [r (rebased mode-fn P fixed change rel-caret)]
              (when (or (:success r) (= "unhandled" (get-in r [:error :name]))) r))
            (parinfer/paren-mode R opts))
        (mode-fn R (assoc opts :changes [change]))))))

(defn- prev-form-start
  "The start of the nearest non-blank region strictly above offset s,
  walking past blank lines; s itself when nothing is above."
  [text s]
  (loop [q (- s 2)]
    (if (neg? q)
      s
      (let [[s2 e2] (region text q)]
        (if (str/blank? (subs text s2 e2))
          (recur (- s2 2))
          s2)))))

(def default-max-lines
  "adjust leaves a form longer than this as typed. Parinfer costs about 0.22
  ms a line for each of its two passes (36 lines: 3.9 ms Paren, 4.0 ms
  Smart; a 1,321-line form: 327 ms a keystroke, measured 2026-10-29), so
  300 lines is about 66 ms."
  300)

(defn- gap-flags
  "For s's adjacent pairs of non-whitespace characters, whether whitespace
  sits between each pair, in order."
  [^String s]
  (loop [i 0 ws-since? false seen? false flags []]
    (if (>= i (count s))
      flags
      (let [c (.charAt s i)]
        (if (contains? #{\space \tab \return} c)
          (recur (inc i) true seen? flags)
          (recur (inc i) false true (if seen? (conj flags ws-since?) flags)))))))

(defn- tokens-kept?
  "Whether every whitespace run between two non-bracket characters survives
  from p to q and back. Whitespace beside a bracket may come and go (a
  paren-trail tidy): brackets delimit by themselves. Between two atom
  characters it is the only thing keeping them two tokens, and inside a
  string literal it is the string's own text, so there it must stay.
  Precondition: p and q hold the same non-whitespace characters in order
  (whitespace-only? checks that first)."
  [^String p ^String q]
  (let [bracket? #{\( \) \[ \] \{ \}}
        cs (vec (remove #(contains? #{\space \tab \return} %) p))]
    (every? true?
            (map-indexed (fn [i [gp gq]]
                           (or (= gp gq)
                               (contains? bracket? (nth cs i))
                               (contains? bracket? (nth cs (inc i)))))
                         (map vector (gap-flags p) (gap-flags q))))))

(defn- whitespace-only?
  "Whether q differs from p by whitespace alone, with every token kept: the
  same lines, each equal once every space, tab and carriage return is
  removed, and no whitespace run appearing or vanishing except beside a
  bracket (tokens-kept?). Paren mode on a balanced form moves indentation,
  tidies a paren trail's spaces and rewrites a tab in code as two spaces
  (a left-trim or same-indentation guard would reject one of those and
  bring back the first-keystroke fight), and all three pass; a space vanishing between two atom characters would
  merge them into one token, so it never does, however the engine
  misbehaves. paren-stable refuses any result beyond that."
  [^String p ^String q]
  (let [ws #"[ \t\r]" ps (str/split p #"\n" -1) qs (str/split q #"\n" -1)]
    (and (= (count ps) (count qs))
         (every? true? (map #(and (= (str/replace %1 ws "") (str/replace %2 ws ""))
                                  (tokens-kept? %1 %2))
                            ps qs)))))

(defn paren-stable
  "text with every top-level form the character range [a b] touches
  re-indented the way Paren mode reads it, so the first keystroke in a
  freshly formatted form has nothing to move. Every touched form takes the
  pass, however long: the cost was accepted over a 300-line carve-out
  (the full pass measured 425 ms over the 4,574-line window.clj,
  2026-10-30). One form at a time: a form Paren
  mode refuses, or would change beyond spaces and tabs, stays as given.
  Text with an unmatched bracket comes back whole: Paren mode moves
  brackets there, and a format must never change code. Top-level text
  outside brackets (comments, bare values) stays as given. [a b] defaults
  to all of text."
  ([text] (paren-stable text 0 (count text)))
  ([^String text a b]
   (let [{:keys [bracket-tokens matches unmatched]} (syntax/compute-brackets-equal text)]
     (if (seq unmatched)
       text
       (let [spans (sort (for [[o _ depth kind] bracket-tokens
                               :when (and (zero? depth) (= kind :open))]
                           [(line-start text o) (line-end text (get matches o))]))
             ;; forms sharing a line merge, the way region reads them
             spans (reduce (fn [acc [s e]]
                             (let [[ps pend] (peek acc)]
                               (if (and ps (<= s pend))
                                 (conj (pop acc) [ps (max pend e)])
                                 (conj acc [s e]))))
                           [] spans)]
         (loop [pieces [] pos 0 spans (seq spans)]
           (if-not spans
             (apply str (conj pieces (subs text pos)))
             (let [[s e] (first spans)
                   form (subs text s e)
                   r (when (and (< s b) (< a e))
                       (parinfer/paren-mode form))
                   fixed (when (and (:success r) (string? (:text r))
                                    (not= (:text r) form)
                                    (whitespace-only? form (:text r)))
                           (:text r))]
               (recur (conj pieces (subs text pos s) (or fixed form))
                      e (next spans))))))))))

(defn- caret-opts
  "Parinfer's cursor options for the caret inside the region: its place in R
  and, when the previous caret was in the region too, its place in P."
  [{:keys [P R s e prev-caret]} caret]
  (let [[cl cx] (line-col R (- caret s))]
    (cond-> {:cursor-line cl :cursor-x cx}
      (and prev-caret (<= s prev-caret e))
      (merge (let [[pl px] (line-col P (- prev-caret s))]
               {:prev-cursor-line pl :prev-cursor-x px})))))

(defn- change-of
  "The change run-mode is told about: the edit's line within R, its column,
  and the old and new text."
  [{:keys [prev text R s p old-end new-end]}]
  (let [ls (line-start text p)]
    {:line-no (first (line-col R (- ls s)))
     :x (- p ls)
     :old-text (subs prev p old-end)
     :new-text (subs text p new-end)}))

(defn- region-edit
  "The {:edits :caret} that turns R into Parinfer's NR, mapping the caret
  through it."
  [{:keys [R s e' result caret caret-in?]}]
  (let [NR (:text result)
        [q r-end n-end] (diff R NR)]
    {:edits [[(+ s q) (+ s r-end) (subs NR q n-end)]]
     :caret (cond
              caret-in? (+ s (offset NR (:cursor-line result) (:cursor-x result)))
              (> caret e') (+ caret (- (count NR) (count R)))
              :else caret)}))

(defn- adjust-region
  "adjust once the region [s e] of prev is known. ctx holds :prev, :prev-caret,
  :text, :p, :old-end and :new-end (diff's answer for prev and text), :s, :e
  and :prev-consistent?; the caret is passed apart."
  [{:keys [mode prev text s e prev-consistent?] :as ctx} caret]
  (let [e' (max s (+ e (- (count text) (count prev))))
        P (subs prev s e)
        R (subs text s e')
        ctx (assoc ctx :e' e' :P P :R R)
        caret-in? (<= s caret e')
        opts (if caret-in? (caret-opts ctx caret) {})
        result (run-mode mode P R (change-of ctx) (- caret s) opts prev-consistent?)
        NR (:text result)]
    (cond
      (and (:success result) (string? NR) (not= NR R))
      (region-edit (assoc ctx :result result :caret caret :caret-in? caret-in?))
      ;; "unhandled" is the engine catching a Throwable, a Jolt shim gap
      ;; the editor's error line (the status bar) should name; Parinfer's
      ;; own refusals stay nil.
      (and (not (:success result)) (= "unhandled" (:name (:error result))))
      {:engine-error (or (:message (:error result)) "unhandled")})))

(defn- bounded-span
  "[p old-end new-end] from the signals' [bp bq] bounds, trimmed the way diff
  trims. The signals' span is sound but not minimal: a replacement sharing an
  affix with what it replaced must be trimmed the way diff trims, since the
  engine keys its change lookup on the span's end. Extending from the
  seed costs the span's width, not the buffer's."
  [{:keys [prev text caret bounds]}]
  ;; The signals' span is sound but not minimal: a replacement
  ;; sharing an affix with what it replaced must be trimmed the
  ;; way diff trims, since the engine keys its change lookup on
  ;; the span's end. Extending from the
  ;; seed costs the span's width, not the buffer's.
  (let [^String prev prev
        ^String text text
        [bp bq] bounds
        n (count prev) m (count text)
        lim (min (min n m) (max 0 (- caret (max 0 (- m n)))))
        p (loop [i (min bp lim)]
            (if (and (< i lim)
                     (= (.charAt prev i) (.charAt text i)))
              (recur (inc i)) i))
        qmax (- (min n m) p)
        q (loop [i (min bq qmax)]
            (if (and (< i qmax)
                     (= (.charAt prev (- n 1 i))
                        (.charAt text (- m 1 i))))
              (recur (inc i)) i))]
    [p (- n q) (- m q)]))

(defn- edit-span
  "[p old-end new-end]: the edit's span in prev and text, from the
  signals' bounds when given, else from diff."
  [{:keys [prev text caret bounds] :as span}]
  (if bounds
    (bounded-span span)
    (diff prev text (- caret (max 0 (- (count text) (count prev)))))))

(defn- region-end-before-edit
  "Whole lines removed: the line after them isn't part of the edit."
  [prev p old-end]
  ;; Whole lines removed: the line after them isn't part of the edit.
  (if (and (< p old-end) (= p (line-start prev p)) (= old-end (line-start prev old-end)))
    (dec old-end)
    old-end))

(defn- region-start
  "s, moved up to the form above when the edit indents a top-level line."
  [{:keys [prev text p old-end new-end]} s]
  ;; Indenting a top-level line is Parinfer's "pull it into the
  ;; form above": pure whitespace inserted at the
  ;; start of a line that was its own region joins the form above.
  (if (and (pos? s) (= s p) (= old-end p) (= s (line-start prev p))
           (str/blank? (subs text p new-end)))
    (prev-form-start prev s)
    s))

(defn- within-max-lines?
  "Whether the region [s e] and its edited after-text fit in max-lines."
  [{:keys [prev text s e max-lines]}]
  ;; The cap counts the edited after-text too, or a long paste onto
  ;; a blank line would run Parinfer over all of it.
  (<= (inc (max (count (filter #(= \newline %) (subs prev s e)))
                (count (filter #(= \newline %)
                               (subs text s (max s (+ e (- (count text) (count prev)))))))))
      max-lines))

(defn adjust
  "What Parinfer wants after one edit, as jonquil.edit's {:edits [[s e r]]
  :caret n}; nil when it wants nothing, can't run, or Parinfer refuses;
  {:engine-error msg} when the engine caught a Throwable (an \"unhandled\"
  result, a Jolt shim gap the editor's error line names). mode is
  :smart, :indent or :paren; prev-text and prev-caret are the buffer and
  caret before the edit, text and caret after it. A form of more than
  max-lines lines (default-max-lines) is left as typed, answered as
  ::too-long so the editor can say why nothing happened.

  Two editor-fed speedups, each optional and behaviour-identical:
  :bounds [prefix suffix] is the edit's untouched head and tail length
  (GTK's insert/delete signals know it), replacing the whole-buffer diff;
  :prev-consistent? true skips the form's re-indent probe; the editor sets
  it only when this edit starts in the same form its own previous action
  edited, which is the one form a cache hit proves Parinfer-consistent."
  [{:keys [mode prev-text prev-caret text caret max-lines bounds prev-consistent?]
    :or {max-lines default-max-lines}}]
  (let [prev (or prev-text "")
        text (or text "")]
    (when (not= prev text)
      (let [[p old-end new-end] (edit-span {:prev prev :text text :caret caret :bounds bounds})
            b (region-end-before-edit prev p old-end)
            [s e] (region prev p b)
            ctx {:mode mode :prev prev :prev-caret prev-caret :text text
                 :p p :old-end old-end :new-end new-end :e e
                 :prev-consistent? prev-consistent?}
            s (region-start ctx s)
            ctx (assoc ctx :s s)]
        (if (within-max-lines? (assoc ctx :max-lines max-lines))
          (adjust-region ctx caret)
          ::too-long)))))
