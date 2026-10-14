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
            [jonquil.paint :as paint]))

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
  "Pumps the GTK main loop until (done?) or timeout-ms passes. Returns the
  longest single main-context iteration, in ms."
  [done? timeout-ms]
  (let [end (+ (now-ms) timeout-ms)]
    (loop [worst 0.0]
      (if (or (done?) (> (now-ms) end))
        worst
        (recur (max worst (timed (fn [] (main-iteration* ffi/null 1)))))))))

(defn- line-offset
  "Character offset of the start of 0-based line i in text."
  [text i]
  (loop [pos 0 k 0]
    (if (= k i) pos (recur (inc (str/index-of text "\n" pos)) (inc k)))))

(defn- stall
  "Places the caret at offset, inserts s the way typing does, then pumps the
  main loop for 2000 ms. The longest iteration is the stall."
  [ed offset s]
  (let [buf (:buf ed)]
    (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
    (g/gtk-text-buffer-insert-interactive-at-cursor buf s -1 1)
    (pump-until (fn [] false) 2000)))

(defn- report [measure n ms]
  (println (str "bench " measure " lines=" n " ms=" (format "%.1f" (double ms))))
  (flush))

(defn- jump-ms [text]
  (let [caret (+ 1 (str/last-index-of text (str "(" @jump-name " 1)")))
        snap {:text text :caret caret :sel-start caret :sel-end caret}]
    (when-not (help/jump-target snap nil)
      (println "bench jump: no target found, the measure is void"))
    (median (repeatedly 3 (fn [] (timed (fn [] (help/jump-target snap nil))))))))

(defn- run-size [win-new n]
  (let [text (text-of n)
        win (win-new)
        ed (editor/make-editor)]
    (g/gtk-window-set-default-size win 900 700)
    (g/gtk-window-set-child win (:scroll ed))
    (g/gtk-window-present win)
    (editor/set-text! ed text)
    (editor/paint-async! ed)
    (pump-until (fn [] (:done? @(:paint ed))) 600000)
    (report "plan" n (median (repeatedly (if (> n 5000) 3 5) (fn [] (timed (fn [] (paint/paint-plan text)))))))
    (let [l10 (line-offset text 9)]
      (report "stall-edit" n (stall ed (+ l10 3) "x"))
      ;; Let the repaint from the edit finish, then undo nothing: the quote
      ;; goes into the already-edited buffer, one char further along.
      (pump-until (fn [] false) 500)
      (report "stall-quote" n (stall ed (+ l10 1) "\"")))
    (report "jump" n (jump-ms text))
    (window-destroy* win)
    (pump-until (fn [] false) 200)))

(defn -main [& args]
  (gtk-init*)
  (let [sizes (if (seq args) (mapv parse-long args) [200 2000 8000 20000])]
    (doseq [n sizes] (run-size g/gtk-window-new n))
    (System/exit 0)))
