(ns gtk-selftest.incremental
  "Editor rows for the incremental repaint: its tags must equal a full repaint."
  (:require [clojure.core.async :refer [<! timeout]]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest.support :refer [check with-fresh-editor big-text]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.paint :as paint]))

;; --- the incremental repaint ---------------------------
(defn- expected-tags
  "{offset #{style-key}} at each offset in offs (a sorted set): the per-char
  model of a full repaint of text under profile, built only where sampled."
  [profile text offs]
  (reduce (fn [m [k s e]] (reduce (fn [m o] (update m o conj k)) m (subseq offs >= s < e)))
          (zipmap offs (repeat #{}))
          (paint/paint-plan profile text)))

(defn- tag-mismatches
  "Up to 5 [offset key expected?] where a tag in (:tags ed) disagrees with
  expected. Runs on the GTK thread."
  [ed expected]
  (let [buf (:buf ed)]
    (vec (take 5 (for [[o ks] (sort expected)
                       [k tag] (:tags ed)
                       :when (not= (contains? ks k) (g/has-tag-at? buf o tag))]
                   [o k (contains? ks k)])))))

(defn- compare-with-full
  "Parks. Compares ed's tags with a full repaint of its current text, at the
  offsets (offs-fn text) returns. The paint plan is computed off the GTK thread.
  Returns tag-mismatches' vector, empty when they agree."
  [ed offs-fn]
  (let [[text profile] (on-gtk [(editor/editor-text ed) @(:profile ed)])
        expected (expected-tags profile text (into (sorted-set) (offs-fn text)))]
    (on-gtk (tag-mismatches ed expected))))

(defn- sampled-offsets
  "2,000 seeded random offsets into text plus every offset within 300 chars
  of caret."
  [caret]
  (fn [text]
    (let [n (count text)
          r (java.util.Random. 4)]
      (concat (repeatedly 2000 (fn [] (.nextInt r n)))
              (range (max 0 (- caret 300)) (min n (+ caret 300)))))))

(defn- painted-settled?
  "The repaint has caught up with the buffer: :painted holds its current
  text. Runs on the GTK thread."
  [ed]
  (let [p @(:painted ed)]
    (and p (= (:text p) (editor/editor-text ed)))))

(defn- line-start [text line]
  (loop [pos 0 k 0]
    (if (= k line) pos (recur (inc (str/index-of text "\n" pos)) (inc k)))))

(def ^:private clojure-sample
  (apply str (map (fn [i]
                    (str ";; block " i "\n"
                         "(defn greet" i "\n  \"Says hello.\"\n  [name & {:keys [loud?]}]\n"
                         "  (let [s (str \"Hello, \" name \\!)]\n"
                         "    (if loud? (str/upper-case s) s)))\n\n"
                         "(def table" i " {:a 1 :b [2 3] :c #{4 5} :d 'sym})\n\n"))
                  (range 10))))

(defn row-incremental-matches-full
  "After each of 12 scripted edits, once the repaint settles, every tag in
  (:tags ed) at every offset matches a full repaint of the new text. The
  edits cover a char inside a symbol, ( typed just before an existing (, a
  \" opening a string, its deletion, a newline, a 200-char paste, an undo,
  a deletion across brackets, a ; commenting out a line, an opened #{, a
  deletion at offset 0 and an unclosed form typed at the end. No full
  repaint may run during the edits (the :full-gen paint-async! records stays
  put), so the diff path did the work."
  [_ed]
  (go-row :incremental-matches-full check
    (with-fresh-editor
      (fn [ed]
        (let [buf (:buf ed)
              text clojure-sample
              at (fn [needle] (str/index-of (editor/editor-text ed) needle))
              ins! (fn [o s] (g/with-iters* buf o o (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                     (g/gtk-text-buffer-insert-interactive-at-cursor buf s -1 1))
              del! (fn [a b] (g/with-iters* buf a b (fn [i j] (g/gtk-text-buffer-delete-interactive buf i j 1))))
              paste (apply str (repeat 8 "(f :k \"s\" [1 2])\n"))
              edits [[:symbol-char (fn [] (ins! (+ 3 (at "greet3")) "x"))]
                     [:paren-before-paren (fn [] (ins! (at "(if loud?") "("))]
                     [:open-string (fn [] (ins! (line-start (editor/editor-text ed) 3) "\""))]
                     [:delete-quote (fn [] (let [o (line-start (editor/editor-text ed) 3)] (del! o (inc o))))]
                     [:newline (fn [] (ins! (at "[2 3]") "\n"))]
                     [:paste (fn [] (ins! (at ";; block 5") (subs (str paste paste) 0 200)))]
                     [:undo (fn [] (g/gtk-text-buffer-undo buf))]
                     [:delete-across-brackets (fn [] (let [o (at "[name & {")] (del! o (+ o 30))))]
                     [:comment-out (fn [] (ins! (at "(def table7") ";"))]
                     [:open-set (fn [] (ins! (at ":d 'sym") "#{"))]
                     [:delete-first (fn [] (del! 0 1))]
                     [:unclosed-at-end (fn [] (ins! (count (editor/editor-text ed)) "(foo \"bar"))]]
              _ (on-gtk (editor/set-text! ed text) (editor/paint-async! ed))
              ready? (await-until 5000 20 #(painted-settled? ed))
              full-gen (on-gtk (:full-gen @(:paint ed)))
              results (loop [es edits acc []]
                        (if-let [[id f] (first es)]
                          (do (on-gtk (f))
                              (let [settled? (await-until 5000 20 #(painted-settled? ed))
                                    bad (compare-with-full ed (fn [t] (range (count t))))]
                                (recur (rest es) (conj acc {:edit id :settled settled? :mismatches bad}))))
                          acc))
              failed (remove (fn [r] (and (:settled r) (empty? (:mismatches r)))) results)
              full-gen-after (on-gtk (:full-gen @(:paint ed)))]
          (check :incremental-matches-full (and ready? (empty? failed) (= full-gen full-gen-after))
                 {:ready ready? :edits (count results) :failed (vec failed)
                  :full-gen full-gen :full-gen-after full-gen-after}))))))

(defn row-incremental-supersede
  "30 chars typed 150 ms apart into a 20,000-line buffer. Each lands after
  its predecessor's 60 ms debounce but while that tick's plan future
  (~650 ms) is still in flight, so at least one tick result is dropped
  (:repaint-drops). Once settled, the tags match a full repaint at 2,000
  sampled offsets and around the caret."
  [_ed]
  (go-row :incremental-supersede check
    (with-fresh-editor
      (fn [ed]
        (let [buf (:buf ed)
              text (big-text 20000)
              typed "(let [a \"b\"] (str a :k 12)) ;x"
              _ (on-gtk (editor/set-text! ed text) (editor/paint-async! ed))
              ready? (await-until 60000 50 #(painted-settled? ed))
              start (line-start text 10)
              drops0 (on-gtk (g/with-iters* buf start start (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                             @(:repaint-drops ed))
              _ (loop [cs (seq typed)]
                  (when-let [c (first cs)]
                    (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor buf (str c) -1 1))
                    (<! (timeout 150))
                    (recur (rest cs))))
              settled? (await-until 60000 50 #(painted-settled? ed))
              caret (on-gtk (g/cursor-offset buf))
              bad (compare-with-full ed (sampled-offsets caret))
              drops (- (on-gtk @(:repaint-drops ed)) drops0)]
          (check :incremental-supersede (and ready? settled? (pos? drops) (empty? bad))
                 {:ready ready? :settled settled? :typed (count typed) :drops drops
                  :mismatches bad}))))))

(defn row-incremental-open-string
  "A \" typed at line 10 of a 2,000-line buffer turns what follows into
  strings. Within 200 ms of the first chunk landing (the quote itself
  coloured) lines 10 to 40 match a full repaint's :string tag, and once
  settled every tag matches at the sampled offsets."
  [_ed]
  (go-row :incremental-open-string check
    (with-fresh-editor
      (fn [ed]
        (let [buf (:buf ed)
              text (big-text 2000)
              string-tag (get (:tags ed) :string)
              _ (on-gtk (editor/set-text! ed text) (editor/paint-async! ed))
              ready? (await-until 30000 20 #(painted-settled? ed))
              o (line-start text 10)
              new-text (str (subs text 0 o) "\"" (subs text o))
              visible (into (sorted-set) (range o (line-start new-text 40)))
              want (expected-tags @(:profile ed) new-text visible)
              visible-right? (fn [] (every? (fn [[p ks]] (= (contains? ks :string) (g/has-tag-at? buf p string-tag)))
                                            want))
              _ (on-gtk (g/with-iters* buf o o (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                        (g/gtk-text-buffer-insert-interactive-at-cursor buf "\"" -1 1))
              first? (await-until 10000 5 #(g/has-tag-at? buf o string-tag))
              t1 (System/currentTimeMillis)
              visible? (await-until 10000 5 visible-right?)
              ms (- (System/currentTimeMillis) t1)
              settled? (await-until 30000 20 #(painted-settled? ed))
              bad (compare-with-full ed (sampled-offsets (inc o)))]
          (check :incremental-open-string
                 (and ready? first? visible? (<= ms 200) settled? (empty? bad))
                 {:ready ready? :first-chunk first? :visible visible? :visible-ms ms
                  :settled settled? :mismatches bad}))))))

(defn row-paint-async-drops-after-edit
  "An edit while a paint-async! is in flight drops the rest of that paint:
  its spans carry the old text's offsets. Paints 8,000 lines with the
  caret at 0, waits for the first chunk, then inserts 3 chars at offset 0
  in the next turn. 40 ms later, before the edit's 60 ms debounce can
  start the tick, no :string tag may sit on an offset that is a string
  only in the old text: GTK moved the tags already applied along with
  their chars, so a :string there can only come from a stale chunk. Once
  settled, the tags match a full repaint at the sampled offsets."
  [_ed]
  (go-row :paint-async-drops-after-edit check
    (with-fresh-editor
      (fn [ed]
        (let [buf (:buf ed)
              text (big-text 8000)
              new-text (str "xx " text)
              string-tag (get (:tags ed) :string)
              str-offs (fn [t] (set (mapcat (fn [[k s e]] (when (= k :string) (range s e)))
                                            (paint/paint-plan t))))
              old-only (sort (remove (str-offs new-text) (str-offs text)))
              _ (on-gtk (editor/set-text! ed text)
                        (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                        (editor/paint-async! ed))
              first? (await-until 10000 2 #(g/has-tag-at? buf (inc (str/index-of text "\"")) string-tag))
              _ (on-gtk (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-insert buf a "xx " -1))))
              _ (<! (timeout 40))
              stale (on-gtk (vec (take 5 (filter (fn [o] (g/has-tag-at? buf o string-tag)) old-only))))
              settled? (await-until 30000 20 #(painted-settled? ed))
              bad (compare-with-full ed (sampled-offsets 0))]
          (check :paint-async-drops-after-edit
                 (and first? (seq old-only) (empty? stale) settled? (empty? bad))
                 {:first-chunk first? :old-only (count old-only) :stale stale
                  :settled settled? :mismatches bad}))))))
