(ns demo-shots
  "P2.T9: opens the REAL Jonquil window via jonquil.main/start! and drives it
  through a fixed list of scenes, screenshotting each one with cgevent once
  its expected text is visible and the frame has settled, then closes the
  window. One window for the whole run (never rebuilt between scenes), which
  is what keeps every screenshot the same size -- window.clj already opens
  at a fixed 900x700 default size on every construction.

  Timing follows dev/gtk_selftest_window.clj's own pattern verbatim (later,
  poll-until, since, idle?, out-end-offset are small copies of that file's
  private helpers, not a refactor of it): every wait polls a condition
  against a deadline, never a fixed sleep. On top of that, every capture
  waits one more short settle (two 20ms ticks) after its text condition
  passes, so the frame has actually repainted before cgevent reads it -- a
  settle for painting, not a sleep for correctness.

  cgevent's screenshot/record-gif both block for real wall-clock time, so
  both run from a `future`, never the GTK thread (the lock-caller rule in
  global.md/AGENTS.md extends to any blocking subprocess call); each one
  hands control back to the GTK thread via jonquil.gtk.callbacks/on-gui!
  once the subprocess exits. No keystroke is ever synthesized -- console
  input is inserted the same way dev/gtk_selftest_window.clj's stdin/REPL
  rows do it, via gtk_text_buffer_insert_interactive_at_cursor +
  handle-return!."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.eval :as eval]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]))

(def ^:private images-dir "docs/images")
(def ^:private demos-dir "docs/demos")

(defonce results (atom []))

(defn- log [& args]
  (binding [*out* *err*]
    (apply println "[demo-shots]" args)))

(defn- check! [scene ok? detail]
  (swap! results conj {:scene scene :ok (boolean ok?) :detail detail})
  (log (name scene) (if ok? "PASS" "FAIL") (pr-str detail)))

(defn- own-pid [] (.pid (java.lang.ProcessHandle/current)))

(defn- demo-path [name] (str demos-dir "/" name))
(defn- image-path [id ext] (str images-dir "/" (clojure.core/name id) "." ext))

;; --- copied from dev/gtk_selftest_window.clj (small helpers; see that
;; file's own docstring for why each wait is a deadline-bound poll) --------

(defn- later [ms f]
  (g/g-timeout-add ms (cb/callable (fn [_data] (f) 0) [:pointer] :int) ffi/null))

(defn- poll-until
  "Polls (pred?) every interval-ms via g_timeout_add until it's truthy or
  deadline-ms elapses, then calls (done final-pred-value). Never sleeps."
  [deadline-ms interval-ms pred? done]
  (let [t0 (System/currentTimeMillis)]
    (letfn [(step []
              (cond
                (pred?) (done true)
                (>= (- (System/currentTimeMillis) t0) deadline-ms) (done false)
                :else (later interval-ms step)))]
      (step))))

(defn- console-text [ui] (g/buffer-text (:buf (:console ui))))
(defn- idle? [ui] (not (eval/evaluating? (:ctx (:session ui)))))

(defn- mark-offset [buf mark]
  (let [it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-buffer-get-iter-at-mark buf it mark)
      (g/gtk-text-iter-get-offset it)
      (finally (ffi/free it)))))

(defn- out-end-offset [ui]
  (let [cv (:console ui)]
    (mark-offset (:buf cv) (:out-end cv))))

(defn- since [ui mark] (subs (console-text ui) mark))

;; --- settle: wait a couple more repaint ticks after a text condition -----

(defn- settle!
  "Waits n more 20ms GTK ticks (a settle for painting, never a sleep for
  correctness -- the text condition already passed; this only gives the
  buffer's own repaint/scroll a couple of ticks to actually land before a
  screenshot reads the frame) then calls done."
  [n done]
  (if (<= n 0)
    (done)
    (later 20 (fn [] (settle! (dec n) done)))))

;; --- captures: cgevent from a future, never the GTK thread ----------------

(defn- screenshot!
  "Runs cgevent screenshot in a future and hands the result back to the GTK
  thread via on-gui!."
  [path pid done]
  (future
    (let [r (sh/sh "cgevent" "screenshot" path "--pid" (str pid))]
      (cb/on-gui! (fn [] (done r))))))

(defonce ^:private gif-done? (atom false))
(defonce ^:private gif-result (atom nil))

(defn- record-gif-async!
  "Starts cgevent record-gif in a future; polls @gif-done? to find out when
  it's finished (a deadline-bound poll, same as everything else here) rather
  than blocking anything on the GTK thread."
  [path pid duration-secs fps width]
  (reset! gif-done? false)
  (reset! gif-result nil)
  (future
    (let [r (sh/sh "cgevent" "record-gif" "--pid" (str pid) "--duration" (str duration-secs)
                   "-o" path "--fps" (str fps) "--width" (str width))]
      (reset! gif-result r)
      (reset! gif-done? true))))

;; --- scenes ----------------------------------------------------------------
;; Each scene is (fn [ui next] ...): drives ui, records exactly one check!,
;; screenshots docs/images/<scene>.png, then calls (next).

(defn- scene-window
  "hello.clj Run to completion: a colourful definition-pane and a Run
  result underneath."
  [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) (slurp (demo-path "hello.clj")))
    ((:run!* ui))
    (poll-until 5000 20
                (fn [] (and (idle? ui)
                            (str/includes? (since ui mark) "Hello, Jonquil!")
                            (str/includes? (since ui mark) "=> 3")))
                (fn [ok]
                  (settle! 2
                           (fn []
                             (screenshot! (image-path :window "png") (own-pid)
                                          (fn [{:keys [exit err]}]
                                            (check! :window (and ok (zero? exit))
                                                    {:console (since ui mark) :screenshot-exit exit :screenshot-err err})
                                            (next)))))))))

(defn- scene-repl
  "hello.clj Run, then a REPL entry evaluated afterward, in the same
  window."
  [ui next]
  (let [mark1 (out-end-offset ui)]
    (editor/set-text! (:editor ui) (slurp (demo-path "hello.clj")))
    ((:run!* ui))
    (poll-until 5000 20
                (fn [] (and (idle? ui) (str/includes? (since ui mark1) "=> 3")))
                (fn [run-ok?]
                  (let [cv (:console ui)
                        mark2 (out-end-offset ui)]
                    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(* 6 7)" -1 1)
                    (cview/handle-return! cv false)
                    (poll-until 3000 20
                                (fn [] (and (idle? ui) (str/includes? (since ui mark2) "=> 42")))
                                (fn [repl-ok?]
                                  (settle! 2
                                           (fn []
                                             (screenshot! (image-path :repl "png") (own-pid)
                                                          (fn [{:keys [exit err]}]
                                                            (check! :repl (and run-ok? repl-ok? (zero? exit))
                                                                    {:console (since ui mark1) :screenshot-exit exit :screenshot-err err})
                                                            (next))))))))))))

(defn- scene-stdin
  "greet.clj Run: the prompt waits on read-line, then the typed answer's
  result -- both visible in the same shot."
  [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) (slurp (demo-path "greet.clj")))
    ((:run!* ui))
    (poll-until 3000 20
                (fn [] ((:waiting? (:console ui))))
                (fn [waiting?]
                  (let [cv (:console ui)]
                    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "Ada" -1 1)
                    (cview/handle-return! cv false)
                    (poll-until 3000 20
                                (fn [] (and (idle? ui) (str/includes? (since ui mark) "=> \"Hello, Ada!\"")))
                                (fn [ok]
                                  (settle! 2
                                           (fn []
                                             (screenshot! (image-path :stdin "png") (own-pid)
                                                          (fn [{:keys [exit err]}]
                                                            (check! :stdin (and waiting? ok (zero? exit))
                                                                    {:console (since ui mark) :screenshot-exit exit :screenshot-err err})
                                                            (next))))))))))))

(defn- scene-stop
  "forever.clj Run, a few prints, then Stop: \"; interrupted\" in the
  console. Also records stop.gif over the same window of time -- the GIF
  recording runs to completion (its own future, polled via gif-done?)
  BEFORE the still screenshot, so the two cgevent subprocesses never touch
  the same window at once."
  [ui next]
  (let [mark (out-end-offset ui)
        pid (own-pid)]
    (editor/set-text! (:editor ui) (slurp (demo-path "forever.clj")))
    (record-gif-async! (image-path :stop "gif") pid 5 6 640)
    (later 400
           (fn []
             ((:run!* ui))
             (poll-until 3000 20
                         (fn [] (str/includes? (since ui mark) "2\n"))
                         (fn [printed?]
                           (later 400
                                  (fn []
                                    ((:stop!* ui))
                                    (poll-until 3000 20
                                                (fn [] (str/includes? (since ui mark) "; interrupted"))
                                                (fn [stopped?]
                                                  (poll-until 10000 100 (fn [] @gif-done?)
                                                              (fn [gif-finished?]
                                                                (settle! 2
                                                                         (fn []
                                                                           (screenshot! (image-path :stop "png") pid
                                                                                        (fn [{:keys [exit err]}]
                                                                                          (check! :stop
                                                                                                  (and printed? stopped? gif-finished?
                                                                                                       (zero? (:exit @gif-result)) (zero? exit))
                                                                                                  {:console (since ui mark) :screenshot-exit exit
                                                                                                   :screenshot-err err :gif-result @gif-result})
                                                                                          (next)))))))))))))))))

(defn- scene-unbalanced
  "unbalanced.clj Run: the friendly never-closed-bracket message."
  [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) (slurp (demo-path "unbalanced.clj")))
    ((:run!* ui))
    (poll-until 3000 20
                (fn [] (and (idle? ui) (str/includes? (since ui mark) "is never closed.")))
                (fn [ok]
                  (settle! 2
                           (fn []
                             (screenshot! (image-path :unbalanced "png") (own-pid)
                                          (fn [{:keys [exit err]}]
                                            (check! :unbalanced (and ok (zero? exit))
                                                    {:console (since ui mark) :screenshot-exit exit :screenshot-err err})
                                            (next)))))))))

(defn- scene-unicode
  "unicode.clj Run: Hangul in the definition, Hangul and emoji in the
  output."
  [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) (slurp (demo-path "unicode.clj")))
    ((:run!* ui))
    (poll-until 3000 20
                (fn [] (and (idle? ui)
                            (str/includes? (since ui mark) "👋")
                            (str/includes? (since ui mark) "🎉")))
                (fn [ok]
                  (settle! 2
                           (fn []
                             (screenshot! (image-path :unicode "png") (own-pid)
                                          (fn [{:keys [exit err]}]
                                            (check! :unicode (and ok (zero? exit))
                                                    {:console (since ui mark) :screenshot-exit exit :screenshot-err err})
                                            (next)))))))))

(defn- scene-run-while-running
  "An infinite loop, Run again while it's running (the busy warning), then
  Stop -- leaves the engine idle before returning control, same discipline
  as dev/gtk_selftest_window.clj's rows."
  [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(loop [] (recur))")
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  ((:run!* ui))
                  (poll-until 2000 20
                              (fn [] (str/includes? (since ui mark) "already in progress"))
                              (fn [warned?]
                                (settle! 2
                                         (fn []
                                           (screenshot! (image-path :run-while-running "png") (own-pid)
                                                        (fn [{:keys [exit err]}]
                                                          ((:stop!* ui))
                                                          (poll-until 3000 20
                                                                      (fn [] (str/includes? (since ui mark) "; interrupted"))
                                                                      (fn [stopped?]
                                                                        (check! :run-while-running
                                                                                (and started? warned? stopped? (zero? exit))
                                                                                {:console (since ui mark) :screenshot-exit exit :screenshot-err err})
                                                                        (next)))))))))))))

(def ^:private scene-fns
  [scene-window
   scene-repl
   scene-stdin
   scene-stop
   scene-unbalanced
   scene-unicode
   scene-run-while-running])

;; --- driver ------------------------------------------------------------

(defn- run-scene! [ui scene-fn next]
  (editor/set-text! (:editor ui) "")
  (cview/clear-input! (:console ui))
  (poll-until 2000 20 (fn [] (idle? ui))
              (fn [_idle?] (scene-fn ui next))))

(defn- run-scenes! [ui fns]
  (if (empty? fns)
    (let [passed (count (filter :ok @results)) total (count @results)]
      (log "SUMMARY" passed "of" total "scenes passed")
      (later 50 (fn [] (g/gtk-window-close (:window ui)))))
    (run-scene! ui (first fns) (fn [] (run-scenes! ui (rest fns))))))

(defn -main [& _]
  (main/start! {:on-ready (fn [ui] (later 300 (fn [] (run-scenes! ui scene-fns))))})
  (System/exit (if (and (seq @results) (every? :ok @results)) 0 1)))
