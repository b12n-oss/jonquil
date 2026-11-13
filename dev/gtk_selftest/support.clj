(ns gtk-selftest.support
  "Shared plumbing for the editor and console self-test rows: the results
  atom and its `check` recorder, the settle and offset helpers, the fresh
  editor window, the Parinfer typing helpers and the console stubs.
  Each row is (fn [target] ch): a go-row (dev/gtk_async.clj) that records
  exactly one check."
  (:require [selftest-keys]
            [clojure.core.async :refer [<! timeout]]
            [gtk-async :refer [on-gtk]]
            [jolt.ffi :as ffi]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(defonce results (atom []))

(defonce true-fire-count (atom 0))

(defn check [row ok detail]
  (swap! results conj [row (boolean ok) detail])
  (binding [*out* *err*]
    (println "[gtk-selftest]" (name row) (if ok "PASS" "FAIL") (pr-str detail))))

(defn mark-offset
  "mark's current character offset in buf -- console-view keeps its marks
  (:out-end, :input-start) in its returned map, but doesn't expose an
  offset-of-mark reader, so rows that need one (to compute an offset
  relative to a mark rather than a hard-coded number) do it here the same
  way jonquil.gtk.ffi/cursor-offset does for the built-in insert mark."
  [buf mark]
  (let [it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-buffer-get-iter-at-mark buf it mark)
      (g/gtk-text-iter-get-offset it)
      (finally (ffi/free it)))))

(defn tail
  "The last n characters of s, for a self-test detail map. row-console-flood
  leaves ~200k chars of accumulated output in the buffer, and no row resets
  output history (only input), so every row after it inherits that buffer
  -- printing it in full on a PASS/FAIL line would dump ~200 KB per row.
  Pass conditions elsewhere are computed against the real, untrimmed text;
  this only trims what gets reported."
  [s n]
  (if (> (count s) n) (subs s (- (count s) n)) s))

(defn await-fire-count-settled
  "Go-style settle-fire-count!: parks until @true-fire-count has gone
  unchanged for two consecutive 20ms ticks (>=40ms stable), or deadline-ms
  elapses; true or false. A condition-based replacement for a fixed sleep:
  it waits for any dirty-flip left pending from an EARLIER row's edit to
  actually fire and be counted, so the caller's own baseline isn't captured
  mid-flip. The stability window is wall-clock, which can't prove the main
  loop turned (a frozen loop reads as stable), so a trailing on-gtk round
  trip proves it, as settle's does."
  [deadline-ms]
  (let [t0 (System/currentTimeMillis)]
    (loop [last-seen @true-fire-count
           stable-since t0]
      (<! (timeout 20))
      (let [now @true-fire-count
            t (System/currentTimeMillis)]
        (cond
          (not= now last-seen) (recur now t)
          (>= (- t stable-since) 40) (do (on-gtk nil) true)
          (>= (- t t0) deadline-ms) false
          :else (recur now stable-since))))))

(defn with-fresh-editor
  "Builds a fresh editor in its own transient GtkWindow, shows it, calls
  (f ed) from the calling go block, then closes the window. Every GTK call
  runs on the GTK thread; f may park."
  [f]
  (let [{:keys [win ed]} (on-gtk (let [win (g/gtk-window-new)
                                       ed (editor/make-editor)]
                                   (g/gtk-window-set-default-size win 500 400)
                                   (g/gtk-window-set-child win (:scroll ed))
                                   (selftest-keys/watch! win)
                                   (g/gtk-window-present win)
                                   {:win win :ed ed}))]
    (try (f ed)
         (finally (on-gtk (g/gtk-window-close win))))))

(defn big-text
  "n lines, each with a string and a keyword, so every line gets tags."
  [n]
  (apply str (map (fn [i] (str "(defn f" i " [x] (str \"s" i "\" :k" i " x))\n")) (range n))))

(defn parinfer-on! [ed mode] (editor/set-parinfer-fns! ed (constantly mode) nil))

(defn parinfer-off! [ed] (editor/set-parinfer-fns! ed (constantly :off) nil))

(defn type-str! [ed s]
  (let [buf (:buf ed)]
    (g/gtk-text-buffer-begin-user-action buf)
    (g/gtk-text-buffer-insert-interactive-at-cursor buf s -1 1)
    (g/gtk-text-buffer-end-user-action buf)))

(defn delete-action! [ed a b]
  (let [buf (:buf ed)]
    (g/gtk-text-buffer-begin-user-action buf)
    (g/with-iters* buf a b (fn [i j] (g/gtk-text-buffer-delete buf i j)))
    (g/gtk-text-buffer-end-user-action buf)))

(defn caret! [ed pos]
  (g/with-iters* (:buf ed) pos pos (fn [a _] (g/gtk-text-buffer-place-cursor (:buf ed) a))))

(defonce console-submits (atom []))

(defonce history-prev-calls (atom []))

(defonce history-next-calls (atom []))

(defn stub-on-submit! [action] (swap! console-submits conj action))

(defn stub-history-prev [current] (swap! history-prev-calls conj current) "(inc 1)")

(defn stub-history-next [] (swap! history-next-calls conj :called) nil)
