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

  Every scene is a go block (gtk-async/go-row) that steps top to bottom on
  its own thread. Timing follows dev/gtk_selftest_window.clj's own pattern:
  every wait parks in gtk-async/await-until, a condition checked against a
  deadline, never a fixed sleep. since, idle? and out-end-offset are small
  copies of that file's private helpers, not a refactor of it. On top of
  that, every capture waits one more short settle (two 20ms ticks,
  gtk-async/settle) after its text condition passes, so the frame has
  actually repainted before cgevent reads it -- a settle for painting, not
  a sleep for correctness.

  cgevent's screenshot/record-gif both block for real wall-clock time, so
  both run on their own thread (gtk-async/sh's a/thread), never the GTK
  thread (the lock-caller rule in global.md/AGENTS.md extends to any
  blocking subprocess call). A scene's GTK, editor and console actions run
  through on-gtk, one main-loop iteration at a time, and the scene's own
  thread parks there until each one's done. No keystroke is ever
  synthesized -- console input is inserted the same way dev/gtk_selftest_
  window.clj's stdin/REPL rows do it, via gtk_text_buffer_insert_
  interactive_at_cursor + handle-return!."
  (:require [clojure.core.async :as a :refer [<! go timeout]]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [gtk-async :as ga :refer [on-gtk await-until go-row]]
            [jolt.ffi :as ffi]
            [jonquil.eval :as eval]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.popover :as popover]
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

;; --- copied from dev/gtk_selftest_window.clj (small state-reading
;; helpers; see that file's own docstring for why each is a small copy,
;; not a refactor of it) -----------------------------------------------

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

(defn- place-cursor!
  "P4.T8: places buf's cursor at offset -- an ordinary GtkTextBuffer call,
  copied from dev/gtk_selftest_window.clj's own place-cursor! (same
  category as this file's own g/gtk-text-buffer-insert-interactive-at-
  cursor calls elsewhere: never a synthesized keystroke)."
  [buf offset]
  (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))

;; --- helpers beyond gtk-async's own: the close-prompt scene's alert-
;; window lookup and the GIF recording, both off the GTK thread ----------

(defn- windows
  "`cgevent windows --pid <pid> --edn` on its own thread (ga/sh), parsed. A
  failed or unparseable call reads as no windows, never an exception, so a
  poller sees it as \"not there yet\"."
  [pid]
  (let [{:keys [out exit]} (ga/sh "cgevent" "windows" "--pid" (str pid) "--edn")]
    (if (zero? exit)
      (try (edn/read-string out) (catch Exception _ []))
      [])))

(defn- await-windows
  "Like await-until, but the condition is (pred? (windows pid)), checked
  every interval-ms up to deadline-ms. Returns the last windows read,
  whether or not pred? passed. Never touches the GTK thread."
  [pid deadline-ms interval-ms pred?]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (let [ws (windows pid)]
        (if (or (pred? ws) (>= (- (System/currentTimeMillis) t0) deadline-ms))
          ws
          (do (<! (timeout interval-ms)) (recur)))))))

(defn- screenshot-by-id
  "Like ga/screenshot, but by --window-id instead of --pid, for a window
  that isn't this process's main one (the close-prompt scene's alert)."
  [path window-id]
  (ga/sh "cgevent" "screenshot" path "--window-id" (str window-id)))

(defn- record-gif
  "Starts cgevent record-gif on its own thread (a/thread) and returns the
  channel that delivers its result map once the recording ends, so a scene
  can go on driving the window meanwhile and take the result later."
  [path pid duration-secs fps width]
  (a/thread (sh/sh "cgevent" "record-gif" "--pid" (str pid) "--duration" (str duration-secs)
                   "-o" path "--fps" (str fps) "--width" (str width))))

;; --- scenes ----------------------------------------------------------------
;; Each scene is (fn [ui] ch): a go-row that drives ui, records exactly one
;; check!, screenshots docs/images/<scene>.png, and returns a channel that
;; delivers once it's done.

(defn- scene-window
  "hello.clj Run to completion: a colourful definition-pane and a Run
  result underneath."
  [ui]
  (go-row :window check!
    (let [mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) (slurp (demo-path "hello.clj")))
                         ((:run!* ui))
                         m))
          ok (await-until 5000 20 #(and (idle? ui)
                                        (str/includes? (since ui mark) "Hello, Jonquil!")
                                        (str/includes? (since ui mark) "=> 3")))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :window "png") (own-pid))]
      (on-gtk (check! :window (and ok (zero? exit))
                      {:console (since ui mark) :screenshot-exit exit :screenshot-err err})))))

(defn- scene-repl
  "hello.clj Run, then a REPL entry evaluated afterward, in the same
  window."
  [ui]
  (go-row :repl check!
    (let [mark1 (on-gtk (let [m (out-end-offset ui)]
                          (editor/set-text! (:editor ui) (slurp (demo-path "hello.clj")))
                          ((:run!* ui))
                          m))
          run-ok? (await-until 5000 20 #(and (idle? ui) (str/includes? (since ui mark1) "=> 3")))
          mark2 (on-gtk (let [cv (:console ui)
                              m (out-end-offset ui)]
                          (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(* 6 7)" -1 1)
                          (cview/handle-return! cv false)
                          m))
          repl-ok? (await-until 3000 20 #(and (idle? ui) (str/includes? (since ui mark2) "=> 42")))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :repl "png") (own-pid))]
      (on-gtk (check! :repl (and run-ok? repl-ok? (zero? exit))
                      {:console (since ui mark1) :screenshot-exit exit :screenshot-err err})))))

(defn- scene-stdin
  "greet.clj Run: the prompt waits on read-line, then the typed answer's
  result -- both visible in the same shot."
  [ui]
  (go-row :stdin check!
    (let [mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) (slurp (demo-path "greet.clj")))
                         ((:run!* ui))
                         m))
          waiting? (await-until 3000 20 #((:waiting? (:console ui))))
          _ (on-gtk (let [cv (:console ui)]
                      (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "Ada" -1 1)
                      (cview/handle-return! cv false)))
          ok (await-until 3000 20 #(and (idle? ui) (str/includes? (since ui mark) "=> \"Hello, Ada!\"")))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :stdin "png") (own-pid))]
      (on-gtk (check! :stdin (and waiting? ok (zero? exit))
                      {:console (since ui mark) :screenshot-exit exit :screenshot-err err})))))

(defn- scene-stop
  "forever.clj Run, a few prints, then Stop: \"; interrupted\" in the
  console. Also records stop.gif over the same window of time -- the
  recording starts on its own thread (record-gif's a/thread) alongside the
  rest of the scene, and its result is taken via a 10000 ms alts! right
  before the still screenshot, so the two cgevent subprocesses never touch
  the same window at once."
  [ui]
  (go-row :stop check!
    (let [pid (own-pid)
          mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) (slurp (demo-path "forever.clj")))
                         m))
          gif (record-gif (image-path :stop "gif") pid 5 6 640)
          _ (<! (timeout 400))
          _ (on-gtk ((:run!* ui)))
          printed? (await-until 3000 20 #(str/includes? (since ui mark) "2\n"))
          _ (<! (timeout 400))
          _ (on-gtk ((:stop!* ui)))
          stopped? (await-until 3000 20 #(str/includes? (since ui mark) "; interrupted"))
          [gif-result port] (a/alts! [gif (timeout 10000)])
          gif-finished? (= port gif)
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :stop "png") pid)]
      (on-gtk (check! :stop
                      (and printed? stopped? gif-finished?
                           (zero? (:exit gif-result)) (zero? exit))
                      {:console (since ui mark) :screenshot-exit exit
                       :screenshot-err err :gif-result gif-result})))))

(defn- scene-unbalanced
  "unbalanced.clj Run: the friendly never-closed-bracket message."
  [ui]
  (go-row :unbalanced check!
    (let [mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) (slurp (demo-path "unbalanced.clj")))
                         ((:run!* ui))
                         m))
          ok (await-until 3000 20 #(and (idle? ui) (str/includes? (since ui mark) "is never closed.")))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :unbalanced "png") (own-pid))]
      (on-gtk (check! :unbalanced (and ok (zero? exit))
                      {:console (since ui mark) :screenshot-exit exit :screenshot-err err})))))

(defn- scene-unicode
  "unicode.clj Run: Hangul in the definition, Hangul and emoji in the
  output."
  [ui]
  (go-row :unicode check!
    (let [mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) (slurp (demo-path "unicode.clj")))
                         ((:run!* ui))
                         m))
          ok (await-until 3000 20 #(and (idle? ui)
                                        (str/includes? (since ui mark) "👋")
                                        (str/includes? (since ui mark) "🎉")))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :unicode "png") (own-pid))]
      (on-gtk (check! :unicode (and ok (zero? exit))
                      {:console (since ui mark) :screenshot-exit exit :screenshot-err err})))))

(defn- scene-run-while-running
  "An infinite loop, Run again while it's running (the busy warning), then
  Stop -- leaves the engine idle before returning control, same discipline
  as dev/gtk_selftest_window.clj's rows."
  [ui]
  (go-row :run-while-running check!
    (let [mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(loop [] (recur))")
                         ((:run!* ui))
                         m))
          started? (await-until 2000 20 #(eval/evaluating? (:ctx (:session ui))))
          _ (on-gtk ((:run!* ui)))
          warned? (await-until 2000 20 #(str/includes? (since ui mark) "already in progress"))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :run-while-running "png") (own-pid))
          _ (on-gtk ((:stop!* ui)))
          stopped? (await-until 3000 20 #(str/includes? (since ui mark) "; interrupted"))]
      (on-gtk (check! :run-while-running
                      (and started? warned? stopped? (zero? exit))
                      {:console (since ui mark) :screenshot-exit exit :screenshot-err err})))))

(defn- scene-find
  "P3.T7: find.clj open, find-bar/open! (the same fn Cmd+F calls), then a
  query set the same way dev/gtk_selftest_window.clj's own find-bar rows
  do it -- g/gtk-editable-set-text on the query entry, which fires the
  entry's real \"changed\" signal and so runs recompute-matches! exactly
  as a typed keystroke would. \"jonquil\" (case-insensitive,
  find-text-matches) matches three places in find.clj: every match gets
  the search-match tag (#fff2a8) applied before this screenshot."
  [ui]
  (go-row :find check!
    (let [fb (:find-bar ui)
          _ (on-gtk (editor/set-text! (:editor ui) (slurp (demo-path "find.clj")))
                    (find-bar/open! fb :find)
                    (g/gtk-editable-set-text (:query fb) "jonquil"))
          ok (await-until 3000 20 #(>= (count @(:matches fb)) 2))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :find "png") (own-pid))]
      (on-gtk (check! :find (and ok (zero? exit))
                      {:match-count (count @(:matches fb))
                       :screenshot-exit exit :screenshot-err err})))))

(defn- scene-run-selection
  "P3.T7: run-selection.clj's last line, with the caret already there
  (editor/set-text! leaves the caret at the end, the same fact
  binary_smoke.clj's own step-run-selection! measured live and depends
  on), run via :run-selection!* -- the exact fn Cmd+E calls
  (do-run-selection!). It echoes \"> (* 6 7)\" to the console, then
  evaluates it through the same REPL path a typed entry uses, showing
  \"=> 42\" underneath."
  [ui]
  (go-row :run-selection check!
    (let [mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) (slurp (demo-path "run-selection.clj")))
                         ((:run-selection!* ui))
                         m))
          ok (await-until 3000 20 #(and (idle? ui)
                                        (str/includes? (since ui mark) "(* 6 7)")
                                        (str/includes? (since ui mark) "=> 42")))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :run-selection "png") (own-pid))]
      (on-gtk (check! :run-selection (and ok (zero? exit))
                      {:console (since ui mark) :screenshot-exit exit :screenshot-err err})))))

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
  so the driver's own end-of-scene gtk_window_close (in -main, once this
  scene's channel delivers) takes the plain not-dirty path and quits for
  real instead of popping a second, never-to-be-resolved alert that would
  hang this process forever.

  If cgevent never lists a second window, this scene records a PASS with
  :skipped true and says why in its detail map (the brief: \"otherwise
  skip that scene and say so\") rather than guessing which window to
  shoot -- no docs/images/close-prompt.png is written in that case."
  [ui]
  (go-row :close-prompt check!
    (let [pid (own-pid)
          ed (:editor ui)
          _ (on-gtk (editor/set-text! ed (slurp (demo-path "close-prompt.clj")))
                    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) " " -1 1))
          before (await-windows pid 3000 100 seq)
          before-ids (set (map :window-id before))
          _ (on-gtk ((:request-close!* ui)))
          after (await-windows pid 5000 100
                               (fn [ws] (some (fn [w] (not (before-ids (:window-id w)))) ws)))
          alert (some (fn [w] (when-not (before-ids (:window-id w)) w)) after)]
      (if-not alert
        (check! :close-prompt true
                {:skipped true
                 :reason "cgevent windows never listed a second (alert) window for this pid"
                 :before before :after after})
        (let [_ (ga/settle 2)
              {:keys [exit err]} (screenshot-by-id (image-path :close-prompt "png") (:window-id alert))]
          (check! :close-prompt (zero? exit)
                  {:alert-window alert :screenshot-exit exit :screenshot-err err})
          (on-gtk ((:close-choice! ui) {:index 2})
                  (reset! (:alert-pending? ui) false)
                  (editor/mark-saved! ed)))))))

(defn- scene-theme
  "TH.T6: theme-sample.clj (a comment, a string, a keyword, a number, a
  defn and three levels of nested brackets) Run to completion so the
  console shows both output and a result, then (:set-theme!* ui) k --
  the same fn Cmd/Ctrl+Shift+M's cycle calls, applied directly rather
  than cycled to, so each of the four scenes lands on its own theme
  regardless of cycle order. settle! (2 more ticks) gives the retint's
  own queued repaint a moment to land before the screenshot reads the
  frame -- the same 'wait a later main-loop tick' every other capture
  here already does, since apply-window-theme! only sets GObject tag/CSS
  properties synchronously; GTK's own repaint is still queued, not
  immediate. id is the scene id (also docs/images/<id>.png's stem via
  image-path); k is the theme keyword passed to set-theme!*."
  [id k ui]
  (go-row id check!
    (let [mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) (slurp (demo-path "theme-sample.clj")))
                         ((:run!* ui))
                         m))
          ok (await-until 5000 20 #(and (idle? ui)
                                        (str/includes? (since ui mark) "Hi, Jonquil")
                                        (str/includes? (since ui mark) ":greeting")))
          _ (on-gtk ((:set-theme!* ui) k))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path id "png") (own-pid))]
      (on-gtk (check! id (and ok (zero? exit))
                      {:console (since ui mark) :theme k
                       :screenshot-exit exit :screenshot-err err})))))

(defn- scene-theme-light [ui] (scene-theme :theme-light :light ui))
(defn- scene-theme-dark [ui] (scene-theme :theme-dark :dark ui))
(defn- scene-theme-solarized [ui] (scene-theme :theme-solarized :solarized ui))
(defn- scene-theme-navy [ui] (scene-theme :theme-navy :navy ui))

(defn- scene-completion
  "P4.T8: \"(ma\" in the editor, :complete!* -- the exact fn Ctrl+Space
  calls (jonquil.gtk.window's own do-complete!). \"ma\" has several
  candidates (docs/candidates, measured live: macroexpand and friends),
  so the popover opens with its own rows AND the selected candidate's doc
  preview, both visible in the same shot -- the brief's own \"popup with
  its preview\". set-text! already leaves the caret at the end of the
  buffer (this file's own editor/set-text! calls elsewhere never move it
  afterward either), so no place-cursor! call is needed here."
  [ui]
  (go-row :completion check!
    (let [ed (:editor ui) c (:completion ui)
          _ (on-gtk (editor/set-text! ed "(ma")
                    ((:complete!* ui)))
          ok (await-until 3000 20 #(some? (completion/state c)))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :completion "png") (own-pid))]
      (on-gtk (check! :completion (and ok (zero? exit))
                      {:opened ok :screenshot-exit exit :screenshot-err err})
              (completion/close! c)))))

(defn- scene-quick-doc
  "P4.T8: \"(map inc [1])\", caret on \"map\" (offset 2, place-cursor!),
  :quick-doc!* -- the exact fn Ctrl+Q calls (do-quick-doc!). The popover
  opens over clojure.core/map's own arglists and doc."
  [ui]
  (go-row :quick-doc check!
    (let [ed (:editor ui) hp (:help-popover ui)
          _ (on-gtk (editor/set-text! ed "(map inc [1])")
                    (place-cursor! (:buf ed) 2)
                    ((:quick-doc!* ui)))
          ok (await-until 3000 20 #(popover/open? hp))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :quick-doc "png") (own-pid))]
      (on-gtk (check! :quick-doc (and ok (zero? exit))
                      {:opened ok :screenshot-exit exit :screenshot-err err})
              (popover/hide! hp)))))

(defn- scene-rename
  "P4.T8: \"(defn greet [n] n)\\n(greet 1)\", caret on the call's own
  greet (offset 22, place-cursor! -- the same fixture/offset dev/smoke/
  binary_smoke.clj's own step-rename! measures against the built binary),
  :rename!* -- the exact fn Shift+F6/F2 calls (do-rename!). The rename
  popover opens with its entry prefilled with \"greet\" and selected."
  [ui]
  (go-row :rename check!
    (let [ed (:editor ui) rp (:rename-popover ui)
          _ (on-gtk (editor/set-text! ed "(defn greet [n] n)\n(greet 1)")
                    (place-cursor! (:buf ed) 22)
                    ((:rename!* ui)))
          ok (await-until 3000 20 #(popover/open? rp))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :rename "png") (own-pid))]
      (on-gtk (check! :rename (and ok (zero? exit))
                      {:opened ok :screenshot-exit exit :screenshot-err err})
              (popover/hide! rp)))))

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
   [:close-prompt scene-close-prompt]
   [:theme-light scene-theme-light]
   [:theme-dark scene-theme-dark]
   [:theme-solarized scene-theme-solarized]
   [:theme-navy scene-theme-navy]
   [:completion scene-completion]
   [:quick-doc scene-quick-doc]
   [:rename scene-rename]])

;; --- driver: exactly one scene per process --------------------------------

(def ^:private PER-SCENE-MS
  ;; scene-stop's deadlines add up to 16800 ms on its failing path, and its
  ;; GIF records for 5 s alongside them; run-scene's idle wait adds 2000 ms.
  60000)

(defn- run-scene
  "Resets the editor and the console's input, waits (up to 2000 ms) for the
  engine to go idle, then runs scene and parks until its channel delivers."
  [ui scene]
  (go-row :scene-reset check!
    (on-gtk (editor/set-text! (:editor ui) "")
            (cview/clear-input! (:console ui)))
    (await-until 2000 20 #(idle? ui))
    (<! (scene ui))))

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
                      (go (<! (timeout 300))
                          (<! (ga/run-rows [(fn [] (run-scene ui scene-fn))] PER-SCENE-MS check!))
                          (<! (timeout 50))
                          (on-gtk (g/gtk-window-close (:window ui)))))})
        (System/exit (if (and (seq @results) (every? :ok @results)) 0 1))))))
