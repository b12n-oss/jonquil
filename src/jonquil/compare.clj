(ns jonquil.compare
  "LM3's compare: the buffer against another version of its file (on disk,
  or in git HEAD), as differences a person steps through in the Locations
  list and takes the other version of one at a time. Lines are counted
  from 0 here; the list shows them from 1. No GTK here."
  (:require [clojure.string :as str]
            [jonquil.plugin-review :as review]))

(defn hunks
  "The differences from other to buffer, in buffer order: {:line l :here
  [buffer lines] :there [other lines]}, l the 0-based buffer line the
  difference starts at (where the other's lines would go when :here is
  empty)."
  [other buffer]
  (loop [ops (review/line-diff other buffer) line 0 cur nil acc []]
    (let [close (fn [acc] (if cur (conj acc cur) acc))]
      (if-let [[k l] (first ops)]
        (case k
          :same (recur (rest ops) (inc line) nil (close acc))
          :del (recur (rest ops) line (update (or cur {:line line :here [] :there []}) :there conj l) acc)
          :add (recur (rest ops) (inc line) (update (or cur {:line line :here [] :there []}) :here conj l) acc))
        (close acc)))))

(defn- line-starts
  "The offset each line of text starts at, plus the text's length last."
  [text]
  (loop [i 0 acc [0]]
    (if-let [j (str/index-of text "\n" i)]
      (recur (inc j) (conj acc (inc j)))
      (conj acc (count text)))))

(defn- offset-of [starts line] (nth starts (min line (dec (count starts)))))

(defn take-edit
  "The edit, [start end replacement] in buffer offsets, that puts the
  other version's lines in place of hunk h's lines in buffer. A buffer
  that doesn't end in a newline keeps not ending in one."
  [buffer {:keys [line here there]}]
  (let [starts (line-starts buffer)
        s (offset-of starts line)
        e (offset-of starts (+ line (count here)))
        r (apply str (map #(str % "\n") there))
        r (if (and (= e (count buffer)) (not (str/ends-with? buffer "\n")) (seq r))
            (subs r 0 (dec (count r)))
            r)]
    [s e r]))

(defn- lines-word [n] (case n 0 "none" 1 "1 line" (str n " lines")))

(defn location-list
  "A Locations list of the differences from other to buffer, titled for
  label (\"disk\", \"HEAD\"): one entry per hunk, its range the hunk's
  lines in buffer, its 1-based line, and what differs."
  [label other buffer]
  (let [hs (hunks other buffer)]
    {:title (str "Differences from " label)
     :index nil
     :entries (mapv (fn [h]
                      (let [[s e] (take-edit buffer h)]
                        {:start s :end e :line (inc (:line h)) :hunk h
                         :text (str (lines-word (count (:here h))) " here, "
                                    (str/lower-case (lines-word (count (:there h)))) " "
                                    (if (= "disk" label) "on disk" (str "in " label))
                                    ": " (or (first (:there h)) (first (:here h)) ""))}))
                    hs)}))

(defn hunk-at
  "The hunk in hs whose buffer lines hold 0-based line, or one that sits
  right at line when it has no lines here; nil when none does."
  [hs line]
  (some (fn [{l :line here :here :as h}]
          (when (if (empty? here) (= l line) (<= l line (+ l (count here) -1))) h))
        hs))
