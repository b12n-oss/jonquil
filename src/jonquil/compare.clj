(ns jonquil.compare
  "LM3's compare: the buffer against another version of its file (on disk,
  or in git HEAD), as differences a person steps through in the Locations
  list and takes the other version of one at a time. Lines are counted
  from 0 here; the list shows them from 1. No GTK here."
  (:require [clojure.string :as str]
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
