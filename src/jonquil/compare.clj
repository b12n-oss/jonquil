(ns jonquil.compare
  "LM3's compare: the buffer against another version of its file (on disk,
  or in git HEAD), as differences a person steps through in the Locations
  list and takes the other version of one at a time. Lines are counted
  from 0 here; the list shows them from 1. One form can be compared too:
  splice-form puts the other version's form in place of the buffer's, so
  only that form's lines differ. No GTK here."
  (:require [clojure.string :as str]
            [jonquil.commands :as commands]
            [jonquil.plugin-review :as review]))

(defn- lines [s] (if (empty? s) [] (str/split-lines s)))

(defn- ops->hunks
  "The hunks of a line diff's ops ([:same|:del|:add line]), the first line
  being line 0-based in the buffer: runs of :del and :add between :same
  lines become one {:line :here :there} each."
  [ops line]
  (let [fresh (fn [cur line] (or cur {:line line :here [] :there []}))
        close (fn [acc cur] (if cur (conj acc cur) acc))]
    (loop [ops ops line line cur nil acc []]
      (if-let [[k l] (first ops)]
        (case k
          :same (recur (rest ops) (inc line) nil (close acc cur))
          :del (recur (rest ops) line (update (fresh cur line) :there conj l) acc)
          :add (recur (rest ops) (inc line) (update (fresh cur line) :here conj l) acc))
        (close acc cur)))))

(defn- common-ends
  "[p q]: how many leading and trailing lines vectors a and b share."
  [a b]
  (let [p (count (take-while true? (map = a b)))
        q (count (take-while true? (map = (rseq (subvec a p)) (rseq (subvec b p)))))]
    [p q]))

(defn hunks
  "The differences from other to buffer, in buffer order: {:line l :here
  [buffer lines] :there [other lines]}, l the 0-based buffer line the
  difference starts at (where the other's lines would go when :here is
  empty). Line endings don't count. The lines the two share at the start
  and the end are set aside before the line diff, so a big file with a
  local change stays under plugin-review's cap and gives a small hunk."
  [other buffer]
  (let [a (vec (lines other))
        b (vec (lines buffer))]
    (if (= a b)
      []
      (let [[p q] (common-ends a b)
            mid (fn [v] (str/join "\n" (subvec v p (- (count v) q))))]
        (ops->hunks (review/line-diff (mid a) (mid b)) p)))))

(defn- line-starts
  "The offset each line of text starts at, plus the text's length last."
  [text]
  (loop [i 0 acc [0]]
    (if-let [j (str/index-of text "\n" i)]
      (recur (inc j) (conj acc (inc j)))
      (conj acc (count text)))))

(defn- offset-of [starts line] (nth starts (min line (dec (count starts)))))

(defn- eol [buffer] (if (str/includes? buffer "\r\n") "\r\n" "\n"))

(defn take-edit
  "The edit, [start end replacement] in buffer offsets, that puts the
  other version's lines in place of hunk h's lines in buffer, joined with
  the buffer's own line ending. A buffer that doesn't end in one keeps not
  ending in one, and lines added past such a last line start on a line of
  their own."
  [buffer {:keys [line here there]}]
  (let [nl (eol buffer)
        starts (line-starts buffer)
        s (offset-of starts line)
        e (offset-of starts (+ line (count here)))
        open-end? (and (pos? (count buffer)) (= e (count buffer)) (not (str/ends-with? buffer "\n")))
        r (apply str (map #(str % nl) there))
        r (if (and open-end? (seq r)) (subs r 0 (- (count r) (count nl))) r)
        r (if (and open-end? (empty? here) (seq r)) (str nl r) r)]
    [s e r]))

(defn- lines-word [n] (case n 0 "none" 1 "1 line" (str n " lines")))

(defn- shown-line
  "The 0-based buffer line a hunk's row points at: its own, or for lines
  only in the other version past the buffer's last line, that last line."
  [buffer {:keys [line here]}]
  (let [n (count (lines buffer))]
    (if (and (empty? here) (>= line n) (pos? n)) (dec n) line)))

(defn location-list
  "A Locations list of the differences from other to buffer, titled for
  label (\"disk\", \"HEAD\"): one entry per hunk, its range the hunk's
  lines in buffer (for lines only in the other version, the line they'd go
  at, so the list can step there), its 1-based line, and what differs."
  [label other buffer]
  (let [hs (hunks other buffer)
        starts (line-starts buffer)]
    {:title (str "Differences from " label)
     :index nil
     :entries (mapv (fn [h]
                      (let [sl (shown-line buffer h)
                            [s e] (if (seq (:here h))
                                    [(offset-of starts (:line h)) (offset-of starts (+ (:line h) (count (:here h))))]
                                    [(offset-of starts sl) (offset-of starts (inc sl))])]
                        {:start s :end e :line (inc sl) :hunk h
                         :text (str (lines-word (count (:here h))) " here, "
                                    (lines-word (count (:there h))) " "
                                    (if (= "disk" label) "on disk" (str "in " label))
                                    ": " (or (first (:there h)) (first (:here h)) ""))}))
                    hs)}))

(defn hunk-at
  "The hunk in hs that 0-based line of buffer is in: one whose buffer
  lines hold it, or one with no lines here that sits at it (or just past
  the buffer's last line, when line is that last line)."
  [hs line buffer]
  (let [n (count (lines buffer))]
    (some (fn [{l :line here :here :as h}]
            (when (if (empty? here)
                    (or (= l line) (and (>= l n) (= line (dec l))))
                    (<= l line (+ l (count here) -1)))
              h))
          hs)))

;; --- one form ------------------------------------------------------------------

(defn- form-sig
  "A top-level form's signature in text: for a bracketed form its first
  two elements' text ((defn f ...) is [\"defn\" \"f\"]), three for a
  defmethod (its dispatch value tells the methods apart), just the head for
  a (comment ...) block (its first form is code being edited); for an
  atom, or an empty list, its own text."
  [^String text {:keys [start end open kids]}]
  (let [elems (fn [n] (mapv (fn [{s :start e :end}] (subs text s e)) (take n kids)))
        head (first (elems 1))]
    (cond
      (or (not open) (empty? kids)) [(subs text start end)]
      (= "comment" head) ["comment"]
      (= "defmethod" head) (elems 3)
      :else (elems 2))))

(defn- ordinal
  "1st, 2nd, 3rd, 4th, ..., 11th, 12th, 13th, 21st."
  [n]
  (str n (if (<= 11 (mod n 100) 13) "th" (case (mod n 10) 1 "st" 2 "nd" 3 "rd" "th"))))

(defn form-name
  "A name for messages: \"defn f\", with \" (2nd)\" and so on after it for
  a later form (n, 0-based) of the same signature."
  [sig n]
  (str (str/join " " sig) (when (pos? n) (str " (" (ordinal (inc n)) ")"))))

(defn form-keys
  "Every top-level form of text with its key, in buffer order: {:start :end
  :sig :nth :name}, as form-key makes it for a caret in the form."
  [text]
  (let [forms (commands/top-level-forms text)]
    (loop [forms forms seen {} acc []]
      (if-let [{:keys [start end] :as f} (first forms)]
        (let [sig (form-sig text f)
              n (get seen sig 0)]
          (recur (rest forms) (assoc seen sig (inc n))
                 (conj acc {:start start :end end :sig sig :nth n
                            :name (form-name sig n)})))
        acc))))

(defn form-key
  "{:sig :nth :name} for the top-level form of text around caret: its
  signature, how many earlier forms share it (so a second (comment ...) is
  told apart from the first) and a name for messages, \"defn f\". nil
  when the caret is between forms."
  [text caret]
  (let [forms (form-keys text)]
    ;; as Run Form takes it: the form the caret is in, else the one it's
    ;; right after
    (when-let [f (or (first (filter (fn [{:keys [start end]}] (and (<= start caret) (< caret end))) forms))
                     (first (filter (fn [{:keys [end]}] (= end caret)) forms)))]
      (select-keys f [:sig :nth :name]))))

(defn form-range
  "{:start :end} of the top-level form of text with key's signature, at its
  :nth place, or nil."
  [text {:keys [sig] k :nth}]
  (some-> (first (filter #(and (= sig (:sig %)) (= k (:nth %))) (form-keys text)))
          (select-keys [:start :end])))

(defn form-shown
  "How messages name the form key: (defn f ...), with \", the 2nd\" and so on
  after it for a later form of the same signature."
  [{:keys [sig] k :nth}]
  (str "(" (str/join " " sig) " ...)"
       (when (pos? k) (str ", the " (ordinal (inc k))))))

(defn- find-form
  "The top-level form of text with key's signature, at its :nth place, or nil."
  [text {:keys [sig] k :nth}]
  (nth (filterv #(= sig (form-sig text %)) (commands/top-level-forms text)) k nil))

(defn splice-form
  "{:text t}: buffer with the form key names replaced by the same form in
  other, so comparing other's text with buffer shows only that form's
  differences, or {:error msg} when label's version (\"HEAD\", \"disk\")
  or the buffer has no such form."
  [buffer other key label]
  (let [here (find-form buffer key)
        there (find-form other key)
        shown (form-shown key)]
    (cond
      (nil? here) {:error (str "This tab has no " shown " any more.")}
      (nil? there) {:error (str (if (= "disk" label) "The file on disk" (str/capitalize (subs label 0 1)))
                                (when-not (= "disk" label) (subs label 1))
                                " has no " shown ".")}
      :else {:text (str (subs buffer 0 (:start here))
                        (subs other (:start there) (:end there))
                        (subs buffer (:end here)))})))
