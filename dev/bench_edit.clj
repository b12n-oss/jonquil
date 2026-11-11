(ns bench-edit
  "Benchmark for editing large files: jolt bench:edit [sizes...]. Opens a real
  GTK window per size, so keep your hands off the keyboard while it runs.

  Per size (default 200 2000 8000 20000 lines of this repo's own source,
  cycled) it prints one line per measure:
    bench <measure> lines=<n> ms=<x>
  plan        median paint/paint-plan over a fresh copy of the whole text
  stall-edit  longest single g_main_context_iteration in the 2000 ms after
              inserting one character one character into the first defn name
              in the first 40 lines of the pinned text
  stall-quote the same, inserting a double quote there (the quote flips
              string parity from that point on)
  settle-stall-edit, settle-stall-quote
              ms from the edit until :painted matches the buffer
  A stall row whose repaint did not settle in the window prints
  `bench <measure> lines=<n> VOID: ...` instead of a number.
  jump        median help/jump-target, caret on a use of a top-level name
              defined near the start, at the end of the text"
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jolt.process :as p]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.help :as help]
            [jonquil.paint :as paint]
            [jonquil.syntax :as syntax]))

(ffi/defcfn gtk-init* "gtk_init" [] :void)
(ffi/defcfn main-iteration* "g_main_context_iteration" [:pointer :int] :int)
(ffi/defcfn window-destroy* "gtk_window_destroy" [:pointer] :void)

(def ^:private sources
  ["src/jonquil/syntax.clj" "src/jonquil/gtk/editor.clj"
   "src/jonquil/gtk/window.clj" "src/jonquil/nrepl/jvm.clj"])

;; Corpus pinned to the commit the "before" numbers were measured on, so the comparison stays like for like.
(def ^:private corpus-commit "6fd9133")

(defn- corpus-text
  "Contents of path at corpus-commit, from git."
  [path]
  (let [r (p/sh "git" "show" (str corpus-commit ":" path))]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "bench: cannot read " path " at " corpus-commit) {:out (:out r)})))
    (:out r)))

(def ^:private source-lines
  (delay (str/split-lines (apply str (map (fn [f] (str (corpus-text f) "\n")) sources)))))

(def ^:private jump-name
  (delay (some (fn [l] (second (re-find (re-pattern "^\\(defn-? ([^\\s\\[]+)") l)))
               @source-lines)))

(defn- text-of
  "n lines of the cycled source. Near the end, at the last line that starts a
  top-level form (so the cut can't land inside a string), a use of a
  top-level name defined near the start replaces the rest, which is padded
  with blank lines. jump-target then has real work to do."
  [n]
  (let [ls (vec (take n (cycle @source-lines)))
        k  (or (last (filter (fn [i] (str/starts-with? (ls i) "(")) (range (dec n)))) (dec n))]
    (str/join "\n" (into (subvec ls 0 k)
                         (cons (str "(" @jump-name " 1)") (repeat (- n k 1) ""))))))

(defn- now-ms [] (/ (System/nanoTime) 1e6))

(defn- timed [f] (let [t (now-ms)] (f) (- (now-ms) t)))

(defn- median [xs] (nth (sort xs) (quot (count xs) 2)))

(defn- pump-until
  "Pumps the GTK main loop until (done?) or timeout-ms passes, without
  blocking: g_main_context_iteration with may_block=0 returns 0 when nothing
  was ready, and then the thread sleeps 1 ms instead of timing the idle wait.
  Returns {:worst ms :timed-out? bool}, worst being the longest single call
  that dispatched something."
  [done? timeout-ms]
  (let [end (+ (now-ms) timeout-ms)]
    (loop [worst 0.0]
      (cond
        (done?) {:worst worst :timed-out? false}
        (> (now-ms) end) {:worst worst :timed-out? true}
        :else
        (let [t (now-ms)
              r (main-iteration* ffi/null 0)
              dt (- (now-ms) t)]
          (if (zero? r)
            (do (Thread/sleep 1) (recur worst))
            (recur (max worst dt))))))))

(defn- settle [ms] (pump-until (fn [] false) ms))

(defn- line-offset
  "Character offset of the start of 0-based line i in text."
  [text i]
  (loop [pos 0 k 0]
    (if (= k i) pos (recur (inc (str/index-of text "\n" pos)) (inc k)))))

(defn- edit-offset
  "An offset one character into the name of the first (defn within the first
  40 lines: inside a :symbol token, in plain code with no ; earlier on its
  line. Typing a quote here flips string parity from there on. Checked against the tokenizer; exits non-zero when
  the position is not plain code."
  [text]
  (let [head (subs text 0 (line-offset text 40))
        i    (str/index-of head "(defn ")
        off  (when i (+ i 7))
        tok  (when off (first (filter (fn [[_ s e]] (and (<= s off) (< off e))) (syntax/tokenize text))))
        ls   (when off (str/last-index-of text "\n" off))
        pre  (when off (subs text (if ls (inc ls) 0) off))]
    (when-not (and tok (= :symbol (first tok)) (not (str/includes? pre ";")))
      (println (str "bench: edit offset " off " is not inside a plain-code symbol (token " (pr-str tok) "); the stall rows would be void"))
      (System/exit 1))
    off))

(defn- fresh!
  "Resets ed to text, repaints it and lets everything pending run, so each
  edit starts from the same coloured buffer."
  [ed text]
  (editor/set-text! ed text)
  (editor/paint-async! ed)
  (when (:timed-out? (pump-until (fn [] (:done? @(:paint ed))) 600000))
    (println "bench: warning, paint did not finish within 600 s"))
  (settle 500))

(defn- settled?
  "True when the baseline the tick keeps (:painted) is the buffer's text. Copies
  the whole buffer, so it runs once per repetition, after the pump."
  [ed]
  (= (:text @(:painted ed)) (editor/editor-text ed)))

(defn- repainted?
  "Cheap settle check for inside the pump: :painted is a new baseline (not the
  one captured before the edit) whose text has the buffer's length. The full
  text copy would starve the repaint it is waiting for."
  [ed before]
  (let [p @(:painted ed)]
    (boolean (and p
                  (not (identical? p before))
                  (= (count (:text p)) (g/gtk-text-buffer-get-char-count (:buf ed)))))))

(defn- stall-rep
  "One repetition: reset to text, settle, place the caret at offset, insert s
  the way typing does, then pump until window-ms has passed (and at least
  until the repaint settles). Returns {:worst ms :settle ms :settled? bool},
  worst the longest dispatching iteration, settle the time from the edit to
  :painted matching the buffer."
  [ed text offset s window-ms]
  (let [buf (:buf ed)]
    (fresh! ed text)
    (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
    (let [before @(:painted ed)
          t0 (now-ms)
          _ (g/gtk-text-buffer-insert-interactive-at-cursor buf s -1 1)
          r1 (pump-until (fn [] (repainted? ed before)) window-ms)
          settle (- (now-ms) t0)
          r2 (pump-until (fn [] false) (max 0 (- 2000 settle)))]
      {:worst (max (:worst r1) (:worst r2))
       :settle settle
       :settled? (and (not (:timed-out? r1)) (settled? ed))})))

(defn- stall
  "Median over 3 repetitions of stall-rep. Returns {:worst :settle :settled?},
  settled? true only when every repetition's repaint landed in the window.
  Stops at the first repetition that did not settle."
  [ed text offset s window-ms]
  (let [reps (loop [acc []]
               (let [r (stall-rep ed text offset s window-ms)
                     acc (conj acc r)]
                 (if (and (:settled? r) (< (count acc) 3)) (recur acc) acc)))]
    {:worst (median (map :worst reps))
     :settle (median (map :settle reps))
     :settled? (every? :settled? reps)}))

(defn- report [measure n ms]
  (println (str "bench " measure " lines=" n " ms=" (format "%.1f" (double ms))))
  (flush))

(defn- report-stall
  "Runs a stall row, lengthening the window until the repaint settles, and
  prints the stall and settle rows. A row that never settles prints VOID
  and no number."
  [ed n measure text offset s]
  (let [r (loop [ws [2000 10000 30000]]
            (let [r (stall ed text offset s (first ws))]
              (if (or (:settled? r) (empty? (rest ws)))
                (assoc r :window (first ws))
                (recur (rest ws)))))]
    (if (:settled? r)
      (do (when (> (:window r) 2000)
            (println (str "bench note: " measure " lines=" n " needed a " (:window r) " ms window")))
          (report measure n (:worst r))
          (report (str "settle-" measure) n (:settle r)))
      (do (println (str "bench " measure " lines=" n " VOID: repaint did not settle in the window"))
          (flush)))))

(defn- jump-ms
  "Median jump-target time. Each timed call gets a fresh copy of text, built
  before the timer starts: the tokenizer caches by identity, so a reused string
  would time a cache hit, not the tokenise a real jump pays."
  [text]
  (let [caret (+ 1 (str/last-index-of text (str "(" @jump-name " 1)")))
        snap-of (fn [t] {:text t :caret caret :sel-start caret :sel-end caret})]
    (when-not (help/jump-target (snap-of text) nil)
      (println "bench jump: no target found, the measure is void"))
    (median (repeatedly 3 (fn [] (let [fresh (str (StringBuilder. ^String text))]
                                   (timed (fn [] (help/jump-target (snap-of fresh) nil)))))))))

(defn- run-size [n]
  (let [text (text-of n)
        win (g/gtk-window-new)
        ed (editor/make-editor)]
    (g/gtk-window-set-default-size win 900 700)
    (g/gtk-window-set-child win (:scroll ed))
    (g/gtk-window-present win)
    (fresh! ed text)
    (report "plan" n (median (repeatedly (if (> n 5000) 3 5) (fn [] (let [fresh (str (StringBuilder. ^String text))]
                                                                      (timed (fn [] (paint/paint-plan fresh))))))))
    (let [off (edit-offset text)]
      (report-stall ed n "stall-edit" text off "x")
      (report-stall ed n "stall-quote" text off "\""))
    (report "jump" n (jump-ms text))
    (window-destroy* win)
    (settle 200)))

(defn- warmup!
  "One discarded pass at a small size (set-text, paint, edit, pump) so JIT-like
  first-call costs don't land in the first reported row."
  []
  (let [text (text-of 200)
        win (g/gtk-window-new)
        ed (editor/make-editor)]
    (g/gtk-window-set-child win (:scroll ed))
    (g/gtk-window-present win)
    (stall ed text (edit-offset text) "x" 2000)
    (window-destroy* win)
    (settle 200)))

(defn -main [& args]
  (let [sizes (try (if (seq args) (mapv parse-long args) [200 2000 8000 20000])
                   (catch Exception _ nil))]
    (when (or (nil? sizes) (some (fn [n] (not (and n (pos? n)))) sizes))
      (println (str "usage: jolt bench:edit [sizes...]  (positive line counts, got: " (str/join " " args) ")"))
      (System/exit 2))
    (gtk-init*)
    (warmup!)
    (doseq [n sizes] (run-size n))
    (System/exit 0)))
