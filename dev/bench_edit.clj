(ns bench-edit
  "Benchmark for editing large files: bb bench:edit [sizes...]. Opens a real
  GTK window per size, so keep your hands off the keyboard while it runs.

  Per size (default 200 2000 8000 20000 lines of this repo's own source,
  cycled) it prints one line per measure:
    bench <measure> lines=<n> ms=<x>
  plan        median paint/paint-plan over the whole text
  stall-edit  longest single g_main_context_iteration in the 2000 ms after
              inserting one character inside a symbol at line 10
  stall-quote the same, inserting a double quote at line 10 (worst case:
              everything below turns into a string)
  jump        median help/jump-target, caret on a use of a top-level name
              defined near the start, at the end of the text"
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
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

(def ^:private source-lines
  (delay (str/split-lines (apply str (map (fn [f] (str (slurp f) "\n")) sources)))))

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
  line. Typing a quote here opens a string that swallows the rest of the
  file, the worst case. Checked against the tokenizer; exits non-zero when
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

(defn- stall
  "Median over 3 repetitions of: reset to text, settle, place the caret at
  offset, insert s the way typing does, pump for 2000 ms and take the
  longest dispatching iteration."
  [ed text offset s]
  (let [buf (:buf ed)]
    (median
     (repeatedly 3
                 (fn []
                   (fresh! ed text)
                   (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                   (g/gtk-text-buffer-insert-interactive-at-cursor buf s -1 1)
                   (:worst (pump-until (fn [] false) 2000)))))))

(defn- report [measure n ms]
  (println (str "bench " measure " lines=" n " ms=" (format "%.1f" (double ms))))
  (flush))

(defn- jump-ms [text]
  (let [caret (+ 1 (str/last-index-of text (str "(" @jump-name " 1)")))
        snap {:text text :caret caret :sel-start caret :sel-end caret}]
    (when-not (help/jump-target snap nil)
      (println "bench jump: no target found, the measure is void"))
    (median (repeatedly 3 (fn [] (timed (fn [] (help/jump-target snap nil))))))))

(defn- run-size [n]
  (let [text (text-of n)
        win (g/gtk-window-new)
        ed (editor/make-editor)]
    (g/gtk-window-set-default-size win 900 700)
    (g/gtk-window-set-child win (:scroll ed))
    (g/gtk-window-present win)
    (fresh! ed text)
    (report "plan" n (median (repeatedly (if (> n 5000) 3 5) (fn [] (timed (fn [] (paint/paint-plan text)))))))
    (let [off (edit-offset text)]
      (report "stall-edit" n (stall ed text off "x"))
      (report "stall-quote" n (stall ed text off "\"")))
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
    (stall ed text (edit-offset text) "x")
    (window-destroy* win)
    (settle 200)))

(defn -main [& args]
  (let [sizes (try (if (seq args) (mapv parse-long args) [200 2000 8000 20000])
                   (catch Exception _ nil))]
    (when (or (nil? sizes) (some (fn [n] (not (and n (pos? n)))) sizes))
      (println (str "usage: bb bench:edit [sizes...]  (positive line counts, got: " (str/join " " args) ")"))
      (System/exit 2))
    (gtk-init*)
    (warmup!)
    (doseq [n sizes] (run-size n))
    (System/exit 0)))
