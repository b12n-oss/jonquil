(ns demo-shots
  "P2.T9: opens the REAL Jonquil window via jonquil.main/start! and drives it
  through exactly ONE scene per process, screenshotting it with cgevent
  once its expected text is visible and the frame has settled, then closes
  the window and exits. `-main` takes the scene id as its one argument
  (`jolt -M:shots window`, etc.); bb's docs:shots task loops over every
  scene id and launches a fresh `jolt -M:shots <scene-id>` process for
  each one (see bb.edn's docs:shots task and this file's own `scenes`
  list, hand-kept in sync the same way dev/check.clj and
  dev/test_runner.clj's namespace lists are).

  Round 1 (P2.T9 controller review) replaced an earlier version that ran
  all 7 scenes against ONE shared window/process: every scene after the
  first inherited every earlier scene's console history, so stdin.png
  showed hello.clj's output twice plus a REPL entry, and
  run-while-running.png was mostly other scenes' leftover text. A fresh
  process per scene means a fresh session, a fresh window and an empty
  console every time -- each image shows only its own scene. Window size
  stays identical across scenes for the same reason it always did:
  window.clj opens at a fixed 900x700 default size on every construction,
  and every scene here still only ever builds one window for its one
  scene, never rebuilding.

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
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.eval :as eval]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.main :as main]))

(def ^:private images-dir "docs/images")
(def ^:private demos-dir "docs/demos")

(defn- fresh-settings-path
  "TH.T5 isolation: a settings.edn under a fresh temp directory (one per
  process -- bb docs:shots launches a fresh `jolt -M:shots <scene-id>`
  process per scene, so this is also one per scene), never the user's real
  $XDG_CONFIG_HOME/jonquil. No file exists there yet, so activate!'s
  startup load defaults to :light -- every scene starts in :light."
  []
  (str (System/getProperty "java.io.tmpdir")
       "/jonquil-demoshots-settings-" (System/currentTimeMillis) "-" (System/nanoTime)
       "/settings.edn"))

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

(defn- screenshot-by-id!
  "Like screenshot!, but by --window-id instead of --pid -- for a window
  that isn't this process's main one (the close-prompt scene's alert)."
  [path window-id done]
  (future
    (let [r (sh/sh "cgevent" "screenshot" path "--window-id" (str window-id))]
      (cb/on-gui! (fn [] (done r))))))

(defn- cgevent-windows!
  "Runs `cgevent windows --pid <pid> --edn` in a future and hands the
  parsed vector back to the GTK thread via on-gui! -- the same blocking-
  subprocess-never-on-the-GTK-thread discipline as screenshot!/
  record-gif-async!. An unparseable or failed call reads as no windows,
  never an exception, so a poller sees it as \"not there yet\"."
  [pid done]
  (future
    (let [{:keys [out exit]} (sh/sh "cgevent" "windows" "--pid" (str pid) "--edn")]
      (cb/on-gui! (fn [] (done (if (zero? exit)
                                 (try (edn/read-string out) (catch Exception _ []))
                                 [])))))))

(defn- poll-windows!
  "Like poll-until, but the predicate is checked against cgevent-windows!'s
  own result instead of an in-process condition: polls every interval-ms,
  up to deadline-ms, calling (done final-windows) once (pred? windows) is
  truthy or the deadline elapses. Never blocks the GTK thread -- each
  check is its own cgevent-windows! future."
  [pid deadline-ms interval-ms pred? done]
  (let [t0 (System/currentTimeMillis)]
    (letfn [(step []
              (cgevent-windows!
               pid
               (fn [windows]
                 (cond
                   (pred? windows) (done windows)
                   (>= (- (System/currentTimeMillis) t0) deadline-ms) (done windows)
                   :else (later interval-ms step)))))]
      (step))))

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

(defn- scene-find
  "P3.T7: find.clj open, find-bar/open! (the same fn Cmd+F calls), then a
  query set the same way dev/gtk_selftest_window.clj's own find-bar rows
  do it -- g/gtk-editable-set-text on the query entry, which fires the
  entry's real \"changed\" signal and so runs recompute-matches! exactly
  as a typed keystroke would. \"jonquil\" (case-insensitive,
  find-text-matches) matches three places in find.clj: every match gets
  the search-match tag (#fff2a8) applied before this screenshot."
  [ui next]
  (let [fb (:find-bar ui)]
    (editor/set-text! (:editor ui) (slurp (demo-path "find.clj")))
    (find-bar/open! fb :find)
    (g/gtk-editable-set-text (:query fb) "jonquil")
    (poll-until 3000 20
                (fn [] (>= (count @(:matches fb)) 2))
                (fn [ok]
                  (settle! 2
                           (fn []
                             (screenshot! (image-path :find "png") (own-pid)
                                          (fn [{:keys [exit err]}]
                                            (check! :find (and ok (zero? exit))
                                                    {:match-count (count @(:matches fb))
                                                     :screenshot-exit exit :screenshot-err err})
                                            (next)))))))))

(defn- scene-run-selection
  "P3.T7: run-selection.clj's last line, with the caret already there
  (editor/set-text! leaves the caret at the end, the same fact
  binary_smoke.clj's own step-run-selection! measured live and depends
  on), run via :run-selection!* -- the exact fn Cmd+E calls
  (do-run-selection!). It echoes \"> (* 6 7)\" to the console, then
  evaluates it through the same REPL path a typed entry uses, showing
  \"=> 42\" underneath."
  [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) (slurp (demo-path "run-selection.clj")))
    ((:run-selection!* ui))
    (poll-until 3000 20
                (fn [] (and (idle? ui)
                            (str/includes? (since ui mark) "(* 6 7)")
                            (str/includes? (since ui mark) "=> 42")))
                (fn [ok]
                  (settle! 2
                           (fn []
                             (screenshot! (image-path :run-selection "png") (own-pid)
                                          (fn [{:keys [exit err]}]
                                            (check! :run-selection (and ok (zero? exit))
                                                    {:console (since ui mark) :screenshot-exit exit :screenshot-err err})
                                            (next)))))))))

(defn- scene-close-prompt
  "P3.T7: close-prompt.clj, dirtied by one inserted character (the same
  editor/set-text! + insert-interactive-at-cursor shape dev/gtk_selftest_
  window.clj's row-close-dirty-cancel uses), then :request-close!* -- the
  real gtk_window_close path Cmd+W/Cmd+Q both take (P3 fix wave C3) --
  which shows the REAL GtkAlertDialog (Save / Don't Save / Cancel)
  because the buffer is dirty and unconfirmed (close-request-fn). The
  alert is its own top-level window, never a native macOS sheet (measured
  live, a cgevent windows --pid --edn probe this session): found here by
  diffing that call's result against the ONE window id present just
  before the close request, then screenshotted by --window-id (the
  brief's own instruction; measured live this session that on this build
  it captures the alert together with the main window's own dirty '*'
  title behind it, not just the alert's own small bounds).

  The real alert is then abandoned rather than resolved through its own
  GTK completion, exactly as row-close-dirty-cancel does and explains: a
  synthetic {:index 2} (Cancel) via the :close-choice! hook leaves the
  window open and the text untouched, then :alert-pending? is reset by
  hand so a LATER close-request isn't silently suppressed by a stale
  \"already pending\" flag -- same cleanup that row performs. editor/
  mark-saved! then clears dirty? without writing anything to disk, purely
  so the driver's own end-of-scene gtk_window_close (run-scene!'s `next`,
  in -main) takes the plain not-dirty path and quits for real instead of
  popping a second, never-to-be-resolved alert that would hang this
  process forever.

  If cgevent never lists a second window, this scene records a PASS with
  :skipped true and says why in its detail map (the brief: \"otherwise
  skip that scene and say so\") rather than guessing which window to
  shoot -- no docs/images/close-prompt.png is written in that case."
  [ui next]
  (let [pid (own-pid)
        ed (:editor ui)]
    (editor/set-text! ed (slurp (demo-path "close-prompt.clj")))
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) " " -1 1)
    (poll-windows!
     pid 3000 100 seq
     (fn [before]
       (let [before-ids (set (map :window-id before))]
         ((:request-close!* ui))
         (poll-windows!
          pid 5000 100
          (fn [ws] (some (fn [w] (not (before-ids (:window-id w)))) ws))
          (fn [after]
            (let [alert (some (fn [w] (when-not (before-ids (:window-id w)) w)) after)]
              (if-not alert
                (do (check! :close-prompt true
                            {:skipped true
                             :reason "cgevent windows never listed a second (alert) window for this pid"
                             :before before :after after})
                    (next))
                (settle!
                 2
                 (fn []
                   (screenshot-by-id!
                    (image-path :close-prompt "png") (:window-id alert)
                    (fn [{:keys [exit err]}]
                      (check! :close-prompt (zero? exit)
                              {:alert-window alert :screenshot-exit exit :screenshot-err err})
                      ((:close-choice! ui) {:index 2})
                      (reset! (:alert-pending? ui) false)
                      (editor/mark-saved! ed)
                      (next))))))))))))))

;; id -> scene fn, in the order bb.edn's docs:shots task launches them.
;; Kept in sync by hand with that task's own scene-id list, the same way
;; dev/check.clj and dev/test_runner.clj's namespace lists are.
(def ^:private scenes
  [[:window scene-window]
   [:repl scene-repl]
   [:stdin scene-stdin]
   [:stop scene-stop]
   [:unbalanced scene-unbalanced]
   [:unicode scene-unicode]
   [:run-while-running scene-run-while-running]
   [:find scene-find]
   [:run-selection scene-run-selection]
   [:close-prompt scene-close-prompt]])

;; --- driver: exactly one scene per process --------------------------------

(defn- run-scene! [ui scene-fn done]
  (editor/set-text! (:editor ui) "")
  (cview/clear-input! (:console ui))
  (poll-until 2000 20 (fn [] (idle? ui))
              (fn [_idle?] (scene-fn ui done))))

(defn -main [& args]
  (let [scene-id (some-> (first args) keyword)
        scene-fn (some (fn [[id f]] (when (= id scene-id) f)) scenes)]
    (if-not scene-fn
      (do (log "usage: jolt -M:shots <scene-id>, one of:"
               (str/join ", " (map (fn [[id _]] (name id)) scenes)))
          (log "got:" (pr-str (first args)))
          (System/exit 1))
      (do
        (main/start!
         {:settings-path (fresh-settings-path)
          :on-ready (fn [ui]
                      (later 300
                             (fn []
                               (run-scene! ui scene-fn
                                           (fn []
                                             (later 50 (fn [] (g/gtk-window-close (:window ui)))))))))})
        (System/exit (if (and (seq @results) (every? :ok @results)) 0 1))))))
