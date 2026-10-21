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
  thread (the lock-caller rule -- see jonquil.gtk.callbacks -- extends
  to any blocking subprocess call). A scene's GTK, editor and console actions run
  through on-gtk, one main-loop iteration at a time, and the scene's own
  thread parks there until each one's done. No keystroke is ever
  synthesized -- console input is inserted the same way dev/gtk_selftest_
  window.clj's stdin/REPL rows do it, via gtk_text_buffer_insert_
  interactive_at_cursor + handle-return!."
  (:require [clj-server]
            [clojure.core.async :as a :refer [<! go timeout]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [gtk-async :as ga :refer [await-until go-row on-gtk]]
            [jolt.ffi :as ffi]
            [jolt.process :as p]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.file-tree-pane :as file-tree-pane]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.login-path :as login-path]
            [jonquil.main :as main]
            [jonquil.nrepl.cljs-child :as cljs-child]
            [jonquil.nrepl.jank-child :as jank-child]
            [jonquil.tool-path :as tool-path]
            [temp-dirs]))

(def ^:private images-dir "docs/images")
(def ^:private demos-dir "docs/demos")

(defn- fresh-settings-path
  "TH.T5 isolation: a settings.edn under a fresh temp directory (one per
  process -- bb docs:shots launches a fresh `jolt -M:shots <scene-id>`
  process per scene, so this is also one per scene), never the user's real
  $XDG_CONFIG_HOME/jonquil. No file exists there yet, so activate!'s
  startup load defaults to :light -- every scene starts in :light."
  []
  (str (temp-dirs/temp-dir! "jonquil-demoshots-settings-") "/settings.edn"))

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
  (do-run-selection!). It echoes \"user=> (* 6 7)\" to the console, then
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
  shoot -- no docs/images/close-prompt.png is written in that case.

  I1: the cleanup below (dismissing the real alert, resetting
  alert-pending?, mark-saved!) used to run only after a found alert's own
  screenshot, so a throw from screenshot-by-id or check!, or the :skipped
  branch itself, left the buffer dirty with alert-pending? still true --
  -main's own end-of-scene close then met close-request-fn's dirty
  branch, which never quits, and the process hung. The cleanup now runs
  in a go-level finally, unconditionally, whether or not an alert was
  ever found."
  [ui]
  (go-row :close-prompt check!
    (let [pid (own-pid)
          ed (:editor ui)
          _ (on-gtk (editor/set-text! ed (slurp (demo-path "close-prompt.clj")))
                    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) " " -1 1))
          before (await-windows pid 3000 100 seq)
          before-ids (set (map :window-id before))
          _ (on-gtk ((:request-close!* ui)))]
      (try
        (let [after (await-windows pid 5000 100
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
                      {:alert-window alert :screenshot-exit exit :screenshot-err err}))))
        (finally
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

;; This file's own private copies (gdkkeysyms.h, GTK 4.22.4 installed
;; headers), same convention as dev/gtk_selftest_window/inspector.clj's own
;; local key table -- jonquil.gtk.inspector-pane's own KEY-DOWN/KEY-RETURN
;; are private to that namespace.
(def ^:private KEY-DOWN 0xff54)
(def ^:private KEY-RETURN 0xff0d)

(defn- scene-inspector
  "P4.T1: a REPL entry with a 3-key map (a string, a long vector and a
  vector of two same-shaped maps), Cmd/Ctrl+I opens on it, then Down twice
  and Return goes in on :speakers (the map's third, last-defined key, so
  its insertion order -- PersistentArrayMap keeps it for this few keys --
  puts it at row 2), landing on a vector of two uniform maps: jonquil.
  inspect.view/table-keys' own uniform check passes, so this level renders
  as a table, not list-row's plain text -- the panel's table header is
  what this screenshot is actually for."
  [ui]
  (go-row :inspector check!
    (let [pane (:inspector ui)
          mark (on-gtk (let [m (out-end-offset ui)
                             cv (:console ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor
                          (:buf cv)
                          "{:talk \"Jonquil\" :slides (vec (range 40)) :speakers [{:name \"a\" :role \"host\"} {:name \"b\" :role \"guest\"}]}"
                          -1 1)
                         (cview/handle-return! cv false)
                         m))
          entered? (await-until 3000 20 #(and (idle? ui) (str/includes? (since ui mark) "=>")))
          _ (on-gtk ((:inspect-last!* ui)))
          opened? (await-until 2000 20
                               #(and (pos? (g/gtk-widget-get-visible (:root pane)))
                                     (= "*1" (g/label-text (:header pane)))))
          _ (on-gtk (inspector-pane/dispatch-key! pane KEY-DOWN)
                    (inspector-pane/dispatch-key! pane KEY-DOWN)
                    (inspector-pane/dispatch-key! pane KEY-RETURN))
          in? (await-until 2000 20 #(str/includes? (g/label-text (:header pane)) "/ :speakers"))
          table-shown? (await-until 2000 20 #(pos? (g/gtk-widget-get-visible (:table-header pane))))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :inspector "png") (own-pid))]
      (on-gtk (check! :inspector (and entered? opened? in? table-shown? (zero? exit))
                      {:header (g/label-text (:header pane)) :screenshot-exit exit :screenshot-err err})))))

;; --- JVM Clojure scenes ----------------------------------------------------
;; These four run against a REAL clojure -M:serve (clojure-cli.repl), which
;; -main starts through dev/clj_server.clj before the window opens, in a
;; temp project with .jonquil.edn {:dialect :clj}, and stops after the
;; window closes. They need Java 17+ and the clojure CLI, the same as
;; bb test:clj. Each scene sets its own buffer text (set-text! never marks
;; the buffer dirty, so the window still closes without a prompt), and
;; Run loads that text into the server.

(def ^:private clj-demo-text
  "(ns demo.core
  \"A small namespace to try Jonquil's JVM attach on.\")

(defn greet
  \"Says hello to someone.\"
  [who]
  (str \"Hello, \" who \"!\"))

(defn shout [s] (.toUpperCase s))
")

(defn- clj-evaluator [ui] ((:evaluator* ui)))
(defn- clj-idle? [ui] (not (ev/evaluating? (clj-evaluator ui))))

(defn- clj-enter!
  "Types code at the console prompt and presses Return, the same way
  scene-repl does, and returns the console offset before it."
  [ui code]
  (let [cv (:console ui) m (out-end-offset ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) code -1 1)
    (cview/handle-return! cv false)
    m))

(defn- clj-run-demo!
  "Sets the demo text, Runs it, and waits for the load's value line. The
  first Run also connects. The value line is matched at a line start,
  since the live prompt (user=> ) holds \"=> \" before the Run even starts."
  [ui text]
  (go
    (let [mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) text)
                         ((:run!* ui))
                         m))]
      (await-until 20000 20 #(and (clj-idle? ui) (str/includes? (since ui mark) "\n=> "))))))

(defn- scene-clj-attach
  "Run attaches to the server and loads the buffer, then two REPL entries
  run in the buffer's namespace: one returns a value, one prints first."
  [ui]
  (go-row :clj-attach check!
    (let [run-ok? (<! (clj-run-demo! ui clj-demo-text))
          m1 (on-gtk (clj-enter! ui "(greet \"Jonquil\")"))
          ok1 (await-until 5000 20 #(and (clj-idle? ui) (str/includes? (since ui m1) "=> \"Hello, Jonquil!\"")))
          m2 (on-gtk (clj-enter! ui "(do (println \"working...\") (shout \"done\"))"))
          ok2 (await-until 5000 20 #(and (clj-idle? ui) (str/includes? (since ui m2) "=> \"DONE\"")))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :clj-attach "png") (own-pid))]
      (on-gtk (check! :clj-attach (and run-ok? ok1 ok2 (zero? exit))
                      {:console (console-text ui) :screenshot-exit exit :screenshot-err err})))))

(defn- scene-clj-quick-doc
  "After a Run, quick doc on clojure.core's map: the server's own docs,
  ending with the Defined in line for a definition inside a jar."
  [ui]
  (go-row :clj-quick-doc check!
    (let [text (str clj-demo-text "\n(map shout [\"a\" \"b\"])\n")
          run-ok? (<! (clj-run-demo! ui text))
          hp (:help-popover ui)
          _ (on-gtk (place-cursor! (:buf (:editor ui)) (+ 2 (str/index-of text "(map ")))
                    ((:quick-doc!* ui)))
          ok (await-until 5000 20 #(and (popover/open? hp)
                                        (str/includes? (help-popover/quick-doc-text hp) "Defined in")))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :clj-quick-doc "png") (own-pid))]
      (on-gtk (check! :clj-quick-doc (and run-ok? ok (zero? exit))
                      {:text (help-popover/quick-doc-text hp) :screenshot-exit exit :screenshot-err err})
              (popover/hide! hp)))))

(defn- scene-clj-full-doc
  "Quick doc on map, then quick doc again: the server's doc op answers with
  ClojureDocs' full entry, examples included. The first request downloads
  the ClojureDocs export into the server's own temp config dir, so this
  waits up to 40 s for it."
  [ui]
  (go-row :clj-full-doc check!
    (let [text (str clj-demo-text "\n(map shout [\"a\" \"b\"])\n")
          run-ok? (<! (clj-run-demo! ui text))
          hp (:help-popover ui) fp (:full-doc-popover ui)
          _ (on-gtk (place-cursor! (:buf (:editor ui)) (+ 2 (str/index-of text "(map ")))
                    ((:quick-doc!* ui)))
          quick-ok (await-until 5000 20 #(popover/open? hp))
          _ (on-gtk ((:quick-doc!* ui)))
          ok (await-until 40000 50 #(and (popover/open? fp)
                                         (str/includes? (help-popover/full-doc-text fp) "clojure.core/map")))
          _ (ga/settle 4)
          {:keys [exit err]} (ga/screenshot (image-path :clj-full-doc "png") (own-pid))]
      (on-gtk (check! :clj-full-doc (and run-ok? quick-ok ok (zero? exit))
                      {:text (some-> (help-popover/full-doc-text fp) (subs 0 (min 200 (count (help-popover/full-doc-text fp)))))
                       :screenshot-exit exit :screenshot-err err})
              (popover/hide! fp)))))

(defn- scene-clj-inspector
  "P4.T1: Run attaches to the server and loads the demo text, then a REPL
  entry of the SAME 3-key map scene-inspector uses, then Cmd/Ctrl+I opens
  the panel against the real server -- the JVM's own inspect-* ops, not
  jonquil.inspect.view running locally."
  [ui]
  (go-row :clj-inspector check!
    (let [pane (:inspector ui)
          run-ok? (<! (clj-run-demo! ui clj-demo-text))
          m (on-gtk (clj-enter! ui "{:talk \"Jonquil\" :slides (vec (range 40)) :speakers [{:name \"a\" :role \"host\"} {:name \"b\" :role \"guest\"}]}"))
          entered? (await-until 5000 20 #(and (clj-idle? ui) (str/includes? (since ui m) "=>")))
          _ (on-gtk ((:inspect-last!* ui)))
          opened? (await-until 5000 20
                               #(and (pos? (g/gtk-widget-get-visible (:root pane)))
                                     (= "*1" (g/label-text (:header pane)))
                                     (str/includes? (g/label-text (:info pane)) "3 items")))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :clj-inspector "png") (own-pid))]
      (on-gtk (check! :clj-inspector (and run-ok? entered? opened? (zero? exit))
                      {:header (g/label-text (:header pane)) :info (g/label-text (:info pane))
                       :screenshot-exit exit :screenshot-err err})))))

(defn- scene-clj-source
  "After a Run, F12 on clojure.string/join: the source panel opens the real
  clojure/string.clj from the Clojure jar, read-only, on join's line."
  [ui]
  (go-row :clj-source check!
    (let [text (str clj-demo-text "\n(clojure.string/join \", \" [\"a\" \"b\"])\n")
          run-ok? (<! (clj-run-demo! ui text))
          _ (on-gtk (place-cursor! (:buf (:editor ui)) (+ 1 (str/index-of text "join")))
                    ((:jump!* ui)))
          sp (:source-pane ui)
          ok (await-until 10000 20 #(and (pos? (g/gtk-widget-get-visible (:root sp)))
                                         (str/starts-with? (g/label-text (:header sp)) "string.clj in clojure-")
                                         (:done? @(:paint (:editor sp)))))
          ;; P3.T1 fix round 1/2: jonquil.gtk.source-pane/place-caret!
          ;; keeps re-scrolling on its own shared timeout until this
          ;; is true (or a deadline passes), so this scene only needs
          ;; to wait for it -- no scene-local re-scrolling of its own.
          scrolled? (await-until 3000 20 #(source-pane/caret-visible? sp))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :clj-source "png") (own-pid))]
      (on-gtk (check! :clj-source (and run-ok? ok scrolled? (zero? exit))
                      {:header (g/label-text (:header sp)) :screenshot-exit exit :screenshot-err err})))))

(defn- scene-tabs
  "Tabs: talk/core.clj and talk/demo.clj open side by side, core Run then
  demo Run (demo calls core's hello through an alias), and demo left with
  an unsaved change so its label carries the dirty mark."
  [ui]
  (go-row :tabs check!
    (let [dir (temp-dirs/temp-dir! "jonquil-demoshots-tabs-")
          core (io/file dir "talk" "core.clj")
          demo (io/file dir "talk" "demo.clj")
          _ (io/make-parents core)
          _ (spit core "(ns talk.core)\n\n(defn hello [] \"hi\")\n")
          _ (spit demo "(ns talk.demo\n  (:require [talk.core :as c]))\n\n(c/hello)\n")
          [t1 t2] (on-gtk ((:open-file! ui) (.getPath core))
                          ((:open-file! ui) (.getPath demo))
                          ((:tabs* ui)))
          mark1 (on-gtk (let [m (out-end-offset ui)] ((:select-tab! ui) (:id t1)) ((:run!* ui)) m))
          ok1 (await-until 5000 20 #(and (idle? ui) (str/includes? (since ui mark1) "=> #'talk.core/hello")))
          mark2 (on-gtk (let [m (out-end-offset ui)] ((:select-tab! ui) (:id t2)) ((:run!* ui)) m))
          ok2 (await-until 5000 20 #(and (idle? ui) (str/includes? (since ui mark2) "=> \"hi\"")))
          _ (on-gtk (let [buf (:buf (:ed t2))]
                      (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                      (g/gtk-text-buffer-insert-interactive-at-cursor buf ";; next: greet by name\n" -1 1)))
          _ (ga/settle 2)
          labels (on-gtk (mapv (fn [t] (g/label-text (:label t))) ((:tabs* ui))))
          {:keys [exit err]} (ga/screenshot (image-path :tabs "png") (own-pid))
          ;; Saved, so the harness's own close afterwards isn't held up by
          ;; the unsaved-changes alert.
          _ (on-gtk (editor/mark-saved! (:ed t2)))]
      (on-gtk (check! :tabs (and ok1 ok2 (= ["core.clj" "demo.clj ●"] labels) (zero? exit))
                      {:labels labels :run1 (since ui mark1) :run2 (since ui mark2)
                       :screenshot-exit exit :screenshot-err err})))))

(defn- scene-file-tree
  "The file tree: a my-talk project opened as a folder, the tree shown with
  src and src/talk expanded, core.clj and demo.clj opened from it, both
  Run, and demo's row highlighted as the active tab."
  [ui]
  (go-row :file-tree check!
    (let [dir (io/file (temp-dirs/temp-dir! "jonquil-demoshots-tree-") "my-talk")
          put! (fn [rel text] (let [f (io/file dir rel)] (io/make-parents f) (spit f text)))
          _ (do (put! "deps.edn" "{:paths [\"src\"]}\n")
                ;; In process, so the shot keeps its "Jolt" label and the
                ;; scene's short waits don't race a first child start.
                (put! ".jonquil.edn" "{:runtime :in-process}\n")
                (put! "src/talk/core.clj" "(ns talk.core)\n\n(defn hello [] \"hi\")\n")
                (put! "src/talk/demo.clj" "(ns talk.demo\n  (:require [talk.core :as c]))\n\n(c/hello)\n")
                (put! "dev/user.clj" "(ns user)\n")
                (put! "README.md" "# my-talk\n"))
          root (.getCanonicalPath dir)
          p (:file-tree ui)
          click! (fn [path] (file-tree-pane/activate-path! p path))
          [t1 t2] (on-gtk ((:simulate-folder-result! ui) {:path root})
                          ((:toggle-file-tree!* ui))
                          (click! (str root "/src"))
                          (click! (str root "/src/talk"))
                          (click! (str root "/src/talk/core.clj"))
                          (click! (str root "/src/talk/demo.clj"))
                          ((:tabs* ui)))
          mark1 (on-gtk (let [m (out-end-offset ui)] ((:select-tab! ui) (:id t1)) ((:run!* ui)) m))
          ok1 (await-until 5000 20 #(and (idle? ui) (str/includes? (since ui mark1) "=> #'talk.core/hello")))
          mark2 (on-gtk (let [m (out-end-offset ui)] ((:select-tab! ui) (:id t2)) ((:run!* ui)) m))
          ok2 (await-until 5000 20 #(and (idle? ui) (str/includes? (since ui mark2) "=> \"hi\"")))
          _ (ga/settle 2)
          v (on-gtk {:selected (file-tree-pane/selected-path p)
                     :rows (mapv :name (file-tree-pane/visible-rows p))})
          {:keys [exit err]} (ga/screenshot (image-path :file-tree "png") (own-pid))]
      (on-gtk (check! :file-tree (and ok1 ok2 (= (str root "/src/talk/demo.clj") (:selected v)) (zero? exit))
                      (assoc v :screenshot-exit exit :screenshot-err err))))))

;; --- jank scene ------------------------------------------------------------
;; Runs against a REAL jank (jank-child/find-binary: JONQUIL_JANK, PATH, the
;; usual install dirs). -main opens a window on a temp project's
;; src/demo/core.jank and does nothing when no jank is found, so this scene
;; never needs the fake.

(def ^:private jank-demo-text
  "(ns demo.core)

(defn greet
  \"Says hello to someone.\"
  [who]
  (str \"Hello, \" who \"!\"))

(println (greet \"Jonquil\"))
")

(defn- scene-jank
  "Run starts jank and loads the buffer (a printed line), then one REPL
  entry runs in the buffer's namespace."
  [ui]
  (go-row :jank check!
    (let [run-ok? (<! (clj-run-demo! ui jank-demo-text))
          m1 (on-gtk (clj-enter! ui "(greet \"again\")"))
          ok1 (await-until 10000 20 #(and (clj-idle? ui) (str/includes? (since ui m1) "=> \"Hello, again!\"")))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :jank "png") (own-pid))]
      (on-gtk (check! :jank (and run-ok? ok1 (zero? exit))
                      {:console (console-text ui) :screenshot-exit exit :screenshot-err err})))))

;; --- ClojureScript scenes (node and the browser) ---------------------------
;; Run against REAL ClojureScript: the clojure CLI (cljs-child/find-clojure)
;; and, on node, node on the login PATH. -main writes a temp project with a
;; deps.edn and src/demo/core.cljs (no shadow-cljs.edn, so Jonquil launches
;; ClojureScript), plus .jonquil.edn {:cljs {:target :browser}} for the
;; browser scene, which opens a REAL tab in the default browser. A cold
;; start compiles ClojureScript first, so the waits are long.

(def ^:private cljs-deps-text
  "{:paths [\"src\"]\n :deps {org.clojure/clojurescript {:mvn/version \"1.12.42\"}}}\n")

(def ^:private cljs-demo-text
  "(ns demo.core)

(defn greet
  \"Says hello to someone.\"
  [who]
  (str \"Hello, \" who \"!\"))

(println (greet \"Jonquil\"))
")

(def ^:private cljs-scenes {:cljs-node "{}\n" :cljs-browser "{:cljs {:target :browser}}\n"})

(defn- cljs-run-demo!
  "Like clj-run-demo!, with room for a cold ClojureScript start (and, in
  the browser, the page connecting)."
  [ui]
  (go
    (let [mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) cljs-demo-text)
                         ((:run!* ui))
                         m))]
      (await-until 150000 50 #(and (clj-idle? ui) (str/includes? (since ui mark) "Hello, Jonquil!"))))))

(defn- scene-cljs
  "Run starts ClojureScript and loads the buffer (a printed line), then
  two REPL entries: one in the buffer's namespace, one asking the runtime
  where it is."
  [id entry expect ui]
  (go-row id check!
    (let [run-ok? (<! (cljs-run-demo! ui))
          m1 (on-gtk (clj-enter! ui "(greet \"again\")"))
          ok1 (await-until 20000 20 #(and (clj-idle? ui) (str/includes? (since ui m1) "=> \"Hello, again!\"")))
          m2 (on-gtk (clj-enter! ui entry))
          ok2 (await-until 20000 20 #(and (clj-idle? ui) (re-find expect (since ui m2))))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path id "png") (own-pid))]
      (on-gtk (check! id (and run-ok? ok1 ok2 (zero? exit))
                      {:console (console-text ui) :screenshot-exit exit :screenshot-err err})))))

(defn- scene-cljs-node [ui]
  (scene-cljs :cljs-node "(.-version js/process)" #"=> \"v\d+" ui))

(defn- scene-cljs-browser [ui]
  (scene-cljs :cljs-browser "(.-host js/location)" #"=> \"127\.0\.0\.1:\d+\"" ui))

;; --- nREPL dialect scene (babashka) -----------------------------------------
;; Runs against a REAL `bb nrepl-server`, started by -main on a free port in a
;; temp project whose .jonquil.edn names that port (bb writes no port file),
;; and stopped once the window closes, the same way bb test:bb does it.

(def ^:private nrepl-bb-demo-text
  "(ns demo.core)

(defn greet
  \"Says hello to someone.\"
  [who]
  (str \"Hello, \" who \"!\"))

(println (greet \"Jonquil\"))
")

(defn- scene-nrepl-bb
  "Run connects to babashka and loads the buffer (a printed line), then two
  REPL entries: one in the buffer's namespace, one asking babashka its
  version."
  [ui]
  (go-row :nrepl-bb check!
    (let [run-ok? (<! (clj-run-demo! ui nrepl-bb-demo-text))
          m1 (on-gtk (clj-enter! ui "(greet \"again\")"))
          ok1 (await-until 10000 20 #(and (clj-idle? ui) (str/includes? (since ui m1) "=> \"Hello, again!\"")))
          m2 (on-gtk (clj-enter! ui "(System/getProperty \"babashka.version\")"))
          ok2 (await-until 10000 20 #(and (clj-idle? ui) (re-find #"=> \"\d+\.\d+" (since ui m2))))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :nrepl-bb "png") (own-pid))]
      (on-gtk (check! :nrepl-bb (and run-ok? ok1 ok2 (zero? exit))
                      {:console (console-text ui) :screenshot-exit exit :screenshot-err err})))))

(defn- start-bb-nrepl!
  "Starts bb nrepl-server on a free loopback port in a fresh temp project
  and waits until it listens. {:file core.clj :proc process}."
  []
  (let [dir (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-demoshots-bb-")))
        port (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
               (.getLocalPort s))
        f (io/file dir "src/demo/core.clj")]
    (io/make-parents f)
    (spit (io/file dir "bb.edn") "{:paths [\"src\"]}\n")
    (spit (io/file dir ".jonquil.edn") (str "{:dialect :nrepl :nrepl {:port " port "}}\n"))
    (spit f nrepl-bb-demo-text)
    (let [proc (p/process {:dir dir :out (io/file dir "bb.log") :err :out}
                          (tool-path/resolve-program "bb" (login-path/path) login-path/executable-file?)
                          "nrepl-server" (str port))]
      (loop [n 0]
        (when (and (< n 100)
                   (not (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true)
                             (catch Exception _ false))))
          (Thread/sleep 50) (recur (inc n))))
      {:file (.getPath f) :proc proc})))

(defn- stop-bb-nrepl! [{:keys [proc]}]
  (when proc
    (p/destroy proc)
    (when (= ::alive (deref proc 2000 ::alive))
      (.destroyForcibly ^java.lang.Process (:proc proc)))))

;; --- shadow-cljs scene ----------------------------------------------------
;; Runs against a REAL shadow-cljs watch: -main writes a temp project
;; (shadow-cljs from Maven through the clojure CLI, one :node-script build,
;; every shadow listener on a loopback port it picks itself), installs the
;; npm package ws that shadow's node client needs, starts the watch, then
;; node on the build's output, and waits until shadow says the runtime is
;; ready. Both processes stop once the window closes.

(def ^:private shadow-demo-text
  "(ns demo.core)

(defn greet
  \"Says hello to someone.\"
  [who]
  (str \"Hello, \" who \"!\"))

(defn main []
  (println (greet \"Jonquil\"))
  (js/setInterval (fn []) 60000))
")

(defn- scene-cljs-shadow
  "Run attaches to the shadow-cljs build and loads the buffer, then two REPL
  entries run in node: one in the buffer's namespace, one asking node its
  version."
  [ui]
  (go-row :cljs-shadow check!
    ;; A shadow-cljs Run answers "; Ran demo.core (3 forms)", not a value line.
    (let [m0 (on-gtk (let [m (out-end-offset ui)]
                       (editor/set-text! (:editor ui) shadow-demo-text)
                       ((:run!* ui))
                       m))
          run-ok? (await-until 20000 20 #(and (clj-idle? ui) (str/includes? (since ui m0) "; Ran demo.core")))
          m1 (on-gtk (clj-enter! ui "(greet \"again\")"))
          ok1 (await-until 20000 20 #(and (clj-idle? ui) (str/includes? (since ui m1) "=> \"Hello, again!\"")))
          m2 (on-gtk (clj-enter! ui "(.-version js/process)"))
          ok2 (await-until 20000 20 #(and (clj-idle? ui) (re-find #"=> \"v\d+" (since ui m2))))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :cljs-shadow "png") (own-pid))]
      (on-gtk (check! :cljs-shadow (and run-ok? ok1 ok2 (zero? exit))
                      {:console (console-text ui) :screenshot-exit exit :screenshot-err err})))))

(defn- login-program [n] (tool-path/resolve-program n (login-path/path) login-path/executable-file?))

(defn- wait-for [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true
                   (> (System/currentTimeMillis) deadline) false
                   :else (do (Thread/sleep 200) (recur))))))

(defn- stop-procs! [procs]
  (doseq [proc procs :when proc]
    (p/destroy proc)
    (when (= ::alive (deref proc 5000 ::alive))
      (.destroyForcibly ^java.lang.Process (:proc proc)))))

(defn- start-shadow!
  "The temp project, the shadow-cljs watch and node, ready to attach to.
  {:file core.cljs :procs [node shadow]}; throws with the log's tail when a
  step doesn't finish."
  []
  (let [dir (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-demoshots-shadow-")))
        f (io/file dir "src/demo/core.cljs")
        shadow-log (io/file dir "shadow.log")
        node-log (io/file dir "node.log")
        tail (fn [log] (let [s (try (slurp log) (catch Exception _ ""))] (subs s (max 0 (- (count s) 1500)))))
        fail! (fn [msg data] (temp-dirs/delete-made!) (throw (ex-info msg data)))]
    (io/make-parents f)
    (spit (io/file dir "deps.edn") "{:paths [\"src\"]\n :deps {thheller/shadow-cljs {:mvn/version \"3.2.0\"}}}\n")
    (spit (io/file dir "shadow-cljs.edn")
          (str "{:deps true\n :http {:port 0 :host \"127.0.0.1\"}\n :nrepl {:host \"127.0.0.1\"}\n"
               " :builds {:app {:target :node-script :output-to \"out/app.js\" :main demo.core/main}}}\n"))
    (spit (io/file dir "package.json") "{\"private\": true}\n")
    (spit f shadow-demo-text)
    (let [npm @(p/process {:dir dir :out :string :err :out}
                          (login-program "npm") "install" "--no-audit" "--no-fund" "--save-dev" "ws")]
      (when-not (zero? (:exit npm))
        (fail! "npm install ws failed" {:out (:out npm)})))
    (let [shadow (p/process {:dir dir :out shadow-log :err :out}
                            (or (cljs-child/find-clojure) "clojure")
                            "-M" "-m" "shadow.cljs.devtools.cli" "watch" "app")]
      (when-not (wait-for 180000 #(and (.exists (io/file dir ".shadow-cljs/nrepl.port"))
                                       (str/includes? (tail shadow-log) "Build completed")))
        (stop-procs! [shadow])
        (fail! "shadow-cljs didn't finish its first build" {:log (tail shadow-log)}))
      (let [node (p/process {:dir dir :out node-log :err :out}
                            (login-program "node") "out/app.js")]
        ;; node's client prints "shadow-cljs - #N ready!" once it connected.
        (when-not (wait-for 30000 #(str/includes? (tail node-log) "ready!"))
          (stop-procs! [node shadow])
          (fail! "the node runtime didn't connect to shadow-cljs" {:node (tail node-log) :shadow (tail shadow-log)}))
        {:file (.getPath f) :procs [node shadow]}))))

(def ^:private clj-scenes #{:clj-attach :clj-quick-doc :clj-full-doc :clj-inspector :clj-source})

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
   [:rename scene-rename]
   [:inspector scene-inspector]
   [:clj-attach scene-clj-attach]
   [:clj-quick-doc scene-clj-quick-doc]
   [:clj-full-doc scene-clj-full-doc]
   [:clj-inspector scene-clj-inspector]
   [:clj-source scene-clj-source]
   [:tabs scene-tabs]
   [:file-tree scene-file-tree]
   [:jank scene-jank]
   [:cljs-node scene-cljs-node]
   [:cljs-browser scene-cljs-browser]
   [:nrepl-bb scene-nrepl-bb]
   [:cljs-shadow scene-cljs-shadow]])

;; --- driver: exactly one scene per process --------------------------------

(def ^:private PER-SCENE-MS
  ;; scene-stop's deadlines add up to 16800 ms on its failing path, and its
  ;; GIF records for 5 s alongside them; run-scene's idle wait adds 2000 ms.
  60000)

(def ^:private CLJS-SCENE-MS
  ;; A cold ClojureScript start plus the page's 60 s connect bound.
  200000)

;; I1: a plain atom flip from the window's own "destroy" signal (-main,
;; below, connects it), the same pattern dev/gtk_selftest_window.clj's own
;; window-destroyed? uses -- safe to poll without ever touching the
;; (possibly already-destroyed) widget pointer directly.
(defonce ^:private window-destroyed? (atom false))

(def ^:private WINDOW-CLOSE-DEADLINE-MS
  ;; Generous: a clean scene closes within one main-loop iteration, so
  ;; this only actually waits out its deadline when scene-close-prompt's
  ;; own cleanup didn't run (a throw, or the skip branch) and left the
  ;; buffer dirty or an alert pending -- which would otherwise hang this
  ;; process forever inside g_application_run.
  2000)

(defn- run-scene
  "Resets the editor and the console's input, waits (up to 2000 ms) for the
  window's evaluator to go idle, then runs scene and parks until its
  channel delivers."
  [ui scene]
  (go-row :scene-reset check!
    (on-gtk (editor/set-text! (:editor ui) "")
            (cview/clear-input! (:console ui)))
    ;; The window's own evaluator, not the in-process session: a JVM
    ;; scene's window has no in-process session to ask.
    (await-until 2000 20 #(clj-idle? ui))
    (<! (scene ui))))

(defn -main [& args]
  (let [scene-id (some-> (first args) keyword)
        scene-fn (some (fn [[id f]] (when (= id scene-id) f)) scenes)]
    (cond
      (and scene-fn (= :jank scene-id) (nil? (jank-child/find-binary)))
      (do (log "jank: no jank binary found (set JONQUIL_JANK); nothing captured")
          (System/exit 0))

      (and scene-fn (cljs-scenes scene-id) (nil? (cljs-child/find-clojure)))
      (do (log (name scene-id) ": no clojure CLI found (set JONQUIL_CLOJURE); nothing captured")
          (System/exit 0))

      (and (= :cljs-node scene-id)
           (nil? (tool-path/resolve-program "node" (login-path/path) login-path/executable-file?)))
      (do (log "cljs-node: no node on the login PATH; nothing captured")
          (System/exit 0))

      (and (= :nrepl-bb scene-id)
           (nil? (tool-path/resolve-program "bb" (login-path/path) login-path/executable-file?)))
      (do (log "nrepl-bb: no bb on the login PATH; nothing captured")
          (System/exit 0))

      (and (= :cljs-shadow scene-id)
           (or (nil? (cljs-child/find-clojure)) (some nil? (map login-program ["node" "npm"]))))
      (do (log "cljs-shadow: needs the clojure CLI, node and npm; nothing captured")
          (System/exit 0))

      (not scene-fn)
      (do (log "usage: jolt -M:shots <scene-id>, one of:"
               (str/join ", " (map (fn [[id _]] (name id)) scenes)))
          (log "got:" (pr-str (first args)))
          (System/exit 1))

      :else
      (let [jank-file (when (= :jank scene-id)
                        (let [f (io/file (temp-dirs/temp-dir! "jonquil-demoshots-jank-") "src/demo/core.jank")]
                          (io/make-parents f)
                          (spit f jank-demo-text)
                          (.getPath f)))
            cljs-file (when-let [edn (cljs-scenes scene-id)]
                        (let [dir (temp-dirs/temp-dir! "jonquil-demoshots-cljs-")
                              f (io/file dir "src/demo/core.cljs")]
                          (io/make-parents f)
                          (spit (io/file dir "deps.edn") cljs-deps-text)
                          (spit (io/file dir ".jonquil.edn") edn)
                          (spit f cljs-demo-text)
                          (.getCanonicalPath f)))
            bb-srv (when (= :nrepl-bb scene-id) (start-bb-nrepl!))
            shadow (when (= :cljs-shadow scene-id) (start-shadow!))
            srv (when (clj-scenes scene-id)
                  (let [srv (clj-server/start!)]
                    (spit (io/file (:dir srv) ".jonquil.edn") "{:dialect :clj}\n")
                    srv))]
        (try
          (main/start!
           {:file (or jank-file cljs-file (:file bb-srv) (:file shadow) (when srv (str (:dir srv) "/src/demo/core.clj")))
            :settings-path (fresh-settings-path)
            :on-ready (fn [ui]
                        (g/g-signal-connect-data (:window ui) "destroy"
                                                 (cb/callable (fn [_win _data] (reset! window-destroyed? true) nil)
                                                              [:pointer :pointer] :void)
                                                 ffi/null ffi/null g/CONNECT-DEFAULT)
                        (go (<! (timeout 300))
                            (<! (ga/run-rows [(fn [] (run-scene ui scene-fn))]
                             (if (cljs-scenes scene-id) CLJS-SCENE-MS PER-SCENE-MS) check!))
                            (<! (timeout 50))
                          ;; I1: scene-close-prompt (or any scene) could leave
                          ;; the buffer dirty with an alert pending if its own
                          ;; cleanup didn't run; a plain gtk-window-close would
                          ;; then meet close-request-fn's dirty branch, which
                          ;; never quits, and g_application_run would never
                          ;; return. close-window-bounded!'s on-stuck is the
                          ;; way out: it records a FAIL and exits from this go
                          ;; thread, since -main's own thread would otherwise
                          ;; stay parked inside main/start! forever.
                            (ga/close-window-bounded!
                             (:window ui) window-destroyed? WINDOW-CLOSE-DEADLINE-MS
                             (fn []
                               (check! :window-close-stuck false
                                       {:reason "window still open after the close; exiting to avoid hanging the run"})
                               (clj-server/stop! srv)
                               (stop-bb-nrepl! bb-srv)
                               (stop-procs! (:procs shadow))
                               (System/exit 1)))))})
          (finally (clj-server/stop! srv)
                   (stop-bb-nrepl! bb-srv)
                   (stop-procs! (:procs shadow))))
        (temp-dirs/delete-made!)
        (System/exit (if (and (seq @results) (every? :ok @results)) 0 1))))))
