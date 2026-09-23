(ns jonquil.gtk.window
  "The Jonquil window: a toolbar (Run/Stop/Open/Save/Reset-on-Run) over a
  vertical GtkPaned holding the Definitions editor (P2.T5) above the
  Interactions console (P2.T6), over a status label. Wires jonquil.session's
  Run and REPL, both over one jonquil.eval context, to the two views.

  Lock caller rule (global.md, jonquil.eval's docstring): on-output,
  on-wait-input, on-status-change and on-complete all run on the eval worker
  thread, never the GTK thread. Every one of them here only enqueues, via
  jonquil.gtk.console-view/append! (safe from any thread) or
  jonquil.gtk.callbacks/on-gui! (also safe from any thread, used to reach
  the GTK thread for the status label and nothing else) -- never a direct
  GTK call, never a block, never an invoke-and-wait. Everything else in this
  namespace (button/key handlers, the file dialogs' async callbacks, the
  dirty-changed callback, the self-test's direct calls) already runs on the
  GTK thread, so it calls GTK directly.

  file-dialog! is the S2/S4 spike's helper (spikes/s2/src/spike/window.clj),
  ported unchanged in shape."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.console :as console]
            [jonquil.eval :as eval]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.session :as session]))

;; --- keys (gtk-keys.md; KEY-R and KEY-S-SHIFT are P2.T7 additions the
;; table doesn't carry) ------------------------------------------------------

(def ^:private KEY-F5 0xffc2)
(def ^:private KEY-ESCAPE 0xff1b)
(def ^:private KEY-R 0x72)
(def ^:private KEY-O 0x6f)
(def ^:private KEY-S 0x73)
;; GDK reports a capital S when Shift is held (controller ruling); Save As
;; accepts either keyval as long as Shift is down.
(def ^:private KEY-S-SHIFT 0x53)

(defn- command-chord?
  "A command chord is Control or Meta (gtk-keys.md), so the same shortcut
  works on Linux and macOS."
  [state]
  (not (zero? (bit-and state (bit-or g/CONTROL-MASK g/META-MASK)))))

(defn- shift-held? [state]
  (not (zero? (bit-and state g/SHIFT-MASK))))

;; --- title -------------------------------------------------------------

(defn- file-name [path]
  (let [i (str/last-index-of path "/")]
    (if i (subs path (inc i)) path)))

(defn- update-title!
  "\"<name> - Jonquil\", name the file's basename or Untitled, prefixed with
  \"* \" while dirty. GTK-thread only -- every caller here already is one:
  button/key handlers, the dialog callbacks, the dirty-changed callback, and
  self-test rows (all invoked from g_timeout_add ticks)."
  [ed win path-atom]
  (g/gtk-window-set-title
   win
   (str (when (editor/dirty? ed) "* ")
        (if-let [p @path-atom] (file-name p) "Untitled")
        " - Jonquil")))

;; --- file dialogs, ported from the S2/S4 spike's spike.window/file-dialog! --

(defn- file-dialog!
  "mode is :open or :save. on-result gets {:path s} or {:error msg :code n}."
  [mode window on-result]
  (let [d (g/gtk-file-dialog-new)
        [start finish] (if (= mode :open)
                         [g/gtk-file-dialog-open g/gtk-file-dialog-open-finish]
                         [g/gtk-file-dialog-save g/gtk-file-dialog-save-finish])
        cb (callable (fn [_src res _data] (on-result (g/finish-file finish d res)))
                     [:pointer :pointer :pointer] :void)]
    (start d window ffi/null cb ffi/null)))

;; --- open / save: the no-dialog forms the rows (and the dialog callbacks
;; below) call -------------------------------------------------------------

(defn- open-path!
  "Loads path into the editor: slurp, set-text!, remember the path. An
  error is appended as :error, never swallowed. Doesn't touch the title --
  callers hold the window, this doesn't, so update-title! is theirs to
  call afterward (do-open!, and the ui map's :open-path! below).

  I6: Jolt's slurp maps bad bytes to U+FFFD without throwing, so a
  non-UTF-8 file loads silently and Save would then write EF BF BD in
  place of whatever the original bytes were. When the loaded text
  contains U+FFFD, appends a :system warning naming the risk."
  [ed cv path-atom path]
  (try
    (let [text (slurp path)]
      (editor/set-text! ed text)
      (reset! path-atom path)
      (when (str/includes? text "�")
        (cview/append! cv :system
                       "This file isn't valid UTF-8; unreadable bytes show as � and saving will replace them.\n")))
    (catch :default e
      (cview/append! cv :error (str "; open: " (ex-message e) "\n")))))

(defn- temp-path-for
  "A hidden temp file in the same directory as path (M12): the write-then-
  rename below needs both on the same filesystem for the rename to be
  possible at all, atomic or not."
  [path]
  (let [i (str/last-index-of path "/")
        dir (if i (subs path 0 (inc i)) "")
        name (if i (subs path (inc i)) path)]
    (str dir "." name ".jonquil-tmp-" (System/nanoTime))))

(defn- save-to!
  "Writes the editor text to path without ever truncating it in place
  (M12): spits to a temp file beside it, then renames over the target with
  java.nio.file.Files/move + ATOMIC_MOVE (probed against this Jolt: see
  the fix-wave report) so a failure mid-write can't leave a half-written
  target. mark-saved! and remembering the path only happen once the rename
  has actually succeeded. An error is appended as :error, never swallowed
  -- losing the mark that a save failed would risk the user believing
  unsaved work is safe when it isn't -- and the temp file is cleaned up on
  any failure. Doesn't touch the title; see open-path!'s docstring."
  [ed cv path-atom path]
  (let [tmp (temp-path-for path)]
    (try
      (spit tmp (editor/editor-text ed))
      (java.nio.file.Files/move
       (.toPath (java.io.File. ^String tmp))
       (.toPath (java.io.File. ^String path))
       (into-array java.nio.file.CopyOption
                   [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                    java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      (editor/mark-saved! ed)
      (reset! path-atom path)
      (catch :default e
        (let [f (java.io.File. ^String tmp)]
          (when (.exists f) (.delete f)))
        (cview/append! cv :error (str "; save: " (ex-message e) "\n"))))))

(defn- guarded-open!
  "I4's dirty-discard gate, shared by do-open! (real button/chord, which
  opens a file dialog) and the ui map's dialog-free :open-path! (so a
  self-test row exercises the SAME gate the button does): when ed is dirty
  and the previous attempt hasn't been warned about it yet, refuses and
  appends a :system warning instead of calling proceed!. The next attempt
  while STILL dirty (discard-warned? already true) calls proceed! and
  clears the flag; a dirty->clean transition elsewhere (a successful Save)
  also clears it, via the on-dirty-changed! registration in activate!, so
  a later, unrelated dirty streak earns its own warning."
  [ed cv discard-warned? proceed!]
  (if (and (editor/dirty? ed) (not @discard-warned?))
    (do (reset! discard-warned? true)
        (cview/append! cv :system "Unsaved changes. Save first, or Open again to discard them.\n"))
    (do (reset! discard-warned? false)
        (proceed!))))

(defn- handle-open-result!
  "The file-dialog! :open callback's decision, factored out so a self-test
  row can drive it with a synthetic result map shaped like g/finish-file's
  (M2's dismissed-dialog case has no other way to reach it without a live
  dialog)."
  [ed cv win path-atom {:keys [path error code]}]
  (cond
    path (do (open-path! ed cv path-atom path)
             (update-title! ed win path-atom))
    ;; M2: a cancelled/dismissed dialog is not an error.
    (= code g/GTK-DIALOG-ERROR-DISMISSED) nil
    :else (cview/append! cv :error (str "; open: " error "\n"))))

(defn- do-open! [ed cv win path-atom discard-warned?]
  (guarded-open!
   ed cv discard-warned?
   (fn [] (file-dialog! :open win (fn [result] (handle-open-result! ed cv win path-atom result))))))

(defn- handle-save-result!
  "handle-open-result!'s counterpart for file-dialog! :save."
  [ed cv win path-atom {:keys [path error code]}]
  (cond
    path (do (save-to! ed cv path-atom path)
             (update-title! ed win path-atom))
    (= code g/GTK-DIALOG-ERROR-DISMISSED) nil
    :else (cview/append! cv :error (str "; save: " error "\n"))))

(defn- do-save-as! [ed cv win path-atom]
  (file-dialog! :save win (fn [result] (handle-save-result! ed cv win path-atom result))))

(defn- do-save! [ed cv win path-atom]
  (if-let [path @path-atom]
    (do (save-to! ed cv path-atom path)
        (update-title! ed win path-atom))
    (do-save-as! ed cv win path-atom)))

;; --- Run / Stop ------------------------------------------------------------

(defn- set-status!
  "Safe from any thread: hands the label update to the GTK thread."
  [status-label text]
  (on-gui! (fn [] (g/gtk-label-set-text status-label text))))

(defn- make-eval-opts
  "The opts run-definitions!/submit-repl! share (global.md: every one of
  these runs on the eval worker thread and must only enqueue).

  I2: the console's :evaluating?/:waiting? are wired at construction to
  read live from the engine (see activate!), not mirrored by callbacks
  here -- there's nothing left for a fast eval's on-complete to race
  against and strand, because there's no separate flag to strand.

  :on-complete sets the status label FIRST, before result-line/
  format-error run: a throwing result-line or format-error (both call
  user-supplied formatting logic) can then never leave the status label
  stuck on \"Running…\"; the same goes for reset-busy-hint! (M3), which
  must re-arm for the next run whether or not the append below throws."
  [ed cv status-label]
  {:on-output (fn [kind s] (cview/append! cv kind s))
   :on-wait-input (fn [waiting?]
                    (set-status! status-label (if waiting? "Waiting for input" "Running…")))
   :on-status-change (fn [status]
                       (set-status! status-label
                                    (case status
                                      :running "Running…"
                                      :waiting-input "Waiting for input"
                                      :idle "Ready")))
   :on-complete (fn [result]
                  (set-status! status-label (if (= :interrupted (:status result)) "Stopped" "Ready"))
                  (cview/reset-busy-hint! cv)
                  (let [[kind text] (console/result-line result)]
                    (cview/append! cv kind text))
                  (when (and (= :error (:status result))
                             (str/includes? (eval/format-error (:error result)) "EOF while reading"))
                    ;; editor/editor-text calls GTK text-buffer functions, which
                    ;; is a real GTK-thread requirement, not just this project's
                    ;; own lock -- on-complete runs on the eval worker thread, so
                    ;; the read (and the append! that depends on it) is handed to
                    ;; on-gui! rather than called here directly.
                    (on-gui! (fn []
                               (when-let [msg (console/unclosed-bracket-message (editor/editor-text ed))]
                                 (cview/append! cv :system (str msg "\n")))))))})

(defn- do-run!
  "1. the upstream banner. 2. run-definitions!, appending a warning if the
  engine was already busy."
  [ed cv session status-label]
  (cview/append! cv :system "; --- Running Definitions ---\n")
  (let [warning (session/run-definitions! session (editor/editor-text ed) (make-eval-opts ed cv status-label))]
    (when warning (cview/append! cv :system warning))))

(defn- do-stop! [session]
  (eval/stop-eval! (:ctx session))
  nil)

;; --- REPL: the console's on-submit, and the history adapters ---------------

(defn- do-repl-eval!
  "submit-repl! with the same opts run uses, minus the banner -- and the
  same warning-append bookkeeping, since submit-repl! can return the same
  busy warning run-definitions! can."
  [ed cv session status-label text]
  (let [warning (session/submit-repl! session text (make-eval-opts ed cv status-label))]
    (when warning (cview/append! cv :system warning))))

(defn- make-on-submit
  "on-submit is built and handed to make-console-view BEFORE the console
  view it will act on exists, so it takes an atom and derefs it lazily, at
  call time -- by then activate! has reset! it to the real cv (P2.T7's only
  forward reference; every other closure below is built after its pieces
  already exist)."
  [ed *cv session status-label]
  (fn [{:keys [action text]}]
    (case action
      :eval (do-repl-eval! ed @*cv session status-label text)
      :send-stdin (eval/push-stdin! (:ctx session) text)
      :eof (eval/push-stdin-eof! (:ctx session)))))

(defn- history-prev-fn [session]
  (fn [current-text]
    (let [ctx (:ctx session)
          [new-hist text] (eval/history-prev @(:history ctx) current-text)]
      (reset! (:history ctx) new-hist)
      text)))

(defn- history-next-fn
  "I3: eval/history-next returns \"\" both when there's no history at all
  and when the browsing index is already at the draft (not currently
  browsing) -- console/handle-history! treats any non-nil result as a real
  replacement, so passing that \"\" straight through erased whatever the
  user had typed. Only call through to eval/history-next (and only reset!
  the history atom) when the index is actually behind the item count, i.e.
  there is somewhere for Down to move to; otherwise nil leaves the input
  untouched. jonquil.eval's own contract is unchanged -- this is purely the
  adapter's job of telling \"nothing to move to\" apart from \"moved to an
  empty draft\"."
  [session]
  (fn []
    (let [ctx (:ctx session)
          hist @(:history ctx)]
      (when (< (:index hist) (count (:items hist)))
        (let [[new-hist text] (eval/history-next hist)]
          (reset! (:history ctx) new-hist)
          text)))))

;; --- window-level keys: F5, Escape, a command chord plus R/O/S -------------

(defn- on-window-key
  "evaluating? is (fn [] bool), read live from the engine. M5: the window
  consumes Escape (to Stop) only while something is actually evaluating;
  otherwise it returns 0 so Escape can reach an IME composition (or GTK's
  own default handling) instead of being swallowed here every time."
  [run-fn stop-fn open-fn save-fn save-as-fn evaluating?]
  (fn [_ctl keyval _keycode state _data]
    (cond
      (= keyval KEY-F5) (do (run-fn) 1)
      (and (= keyval KEY-ESCAPE) (evaluating?)) (do (stop-fn) 1)
      (= keyval KEY-ESCAPE) 0
      (and (command-chord? state) (= keyval KEY-R)) (do (run-fn) 1)
      (and (command-chord? state) (= keyval KEY-O)) (do (open-fn) 1)
      (and (command-chord? state) (shift-held? state)
           (contains? #{KEY-S KEY-S-SHIFT} keyval))
      (do (save-as-fn) 1)
      (and (command-chord? state) (= keyval KEY-S)) (do (save-fn) 1)
      :else 0)))

;; --- construction ------------------------------------------------------

(defn- activate! [app {:keys [file on-ready]}]
  (let [session (session/make-session)
        path (atom file)
        discard-warned? (atom false)
        ed (editor/make-editor)
        win (g/gtk-application-window-new app)
        status-label (g/gtk-label-new "Ready")
        *cv (atom nil)
        cv (cview/make-console-view {:on-submit (make-on-submit ed *cv session status-label)
                                     :history-prev (history-prev-fn session)
                                     :history-next (history-next-fn session)
                                     ;; I2: the console reads busy/waiting state
                                     ;; straight from the engine, never a mirror.
                                     :evaluating? (fn [] (eval/evaluating? (:ctx session)))
                                     :waiting? (fn [] (eval/waiting-for-input? (:ctx session)))})
        _ (reset! *cv cv)
        run-fn (fn [] (do-run! ed cv session status-label))
        stop-fn (fn [] (do-stop! session))
        open-fn (fn [] (do-open! ed cv win path discard-warned?))
        save-fn (fn [] (do-save! ed cv win path))
        save-as-fn (fn [] (do-save-as! ed cv win path))
        window-key-fn (on-window-key run-fn stop-fn open-fn save-fn save-as-fn
                                     (fn [] (eval/evaluating? (:ctx session))))
        toolbar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        run-btn (g/gtk-button-new-with-label "Run (F5)")
        stop-btn (g/gtk-button-new-with-label "Stop (Esc)")
        open-btn (g/gtk-button-new-with-label "Open…")
        save-btn (g/gtk-button-new-with-label "Save…")
        reset-chk (g/gtk-check-button-new-with-label "Reset on Run")
        paned (g/gtk-paned-new g/ORIENTATION-VERTICAL)
        vbox (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        keyctl (g/gtk-event-controller-key-new)]
    (g/gtk-box-append toolbar run-btn)
    (g/gtk-box-append toolbar stop-btn)
    (g/gtk-box-append toolbar open-btn)
    (g/gtk-box-append toolbar save-btn)
    (g/gtk-box-append toolbar reset-chk)
    (g/gtk-check-button-set-active reset-chk 1)
    (g/gtk-widget-set-vexpand paned 1)
    (g/gtk-paned-set-start-child paned (:scroll ed))
    (g/gtk-paned-set-end-child paned (:scroll cv))
    (g/gtk-paned-set-position paned 430)
    (g/gtk-box-append vbox toolbar)
    (g/gtk-box-append vbox paned)
    (g/gtk-box-append vbox status-label)
    (g/gtk-window-set-child win vbox)
    (g/gtk-window-set-default-size win 900 700)
    (g/g-signal-connect-data run-btn "clicked"
                             (callable (fn [_ _] (run-fn)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data stop-btn "clicked"
                             (callable (fn [_ _] (stop-fn)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data open-btn "clicked"
                             (callable (fn [_ _] (open-fn)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data save-btn "clicked"
                             (callable (fn [_ _] (save-fn)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data reset-chk "toggled"
                             (callable (fn [btn _data]
                                         (session/set-mode! session
                                                            (if (zero? (g/gtk-check-button-get-active btn))
                                                              :accumulate :reset)))
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
    (g/g-signal-connect-data keyctl "key-pressed"
                             (callable window-key-fn [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller win keyctl)
    (g/g-signal-connect-data win "close-request"
                             (callable (fn [_win _data] (g/g-application-quit app) 0)
                                       [:pointer :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (editor/on-dirty-changed! ed (fn [_dirty] (update-title! ed win path)))
    ;; I4: a dirty->clean transition (a successful Save) re-arms the
    ;; discard warning, so an unrelated LATER dirty streak earns its own.
    (editor/on-dirty-changed! ed (fn [dirty] (when-not dirty (reset! discard-warned? false))))
    (when file
      ;; A file argument that doesn't exist opens an empty editor with the
      ;; path remembered anyway, so Save creates it -- unlike the Open
      ;; button's dialog result, this is the documented normal case, not an
      ;; error to report.
      (try
        (let [text (slurp file)]
          (editor/set-text! ed text)
          ;; I6: same non-UTF-8 warning as open-path!.
          (when (str/includes? text "�")
            (cview/append! cv :system
                           "This file isn't valid UTF-8; unreadable bytes show as � and saving will replace them.\n")))
        (catch :default _ nil)))
    (update-title! ed win path)
    (g/gtk-window-present win)
    (editor/focus! ed)
    (when on-ready
      (on-ready {:editor ed :console cv :session session :status-label status-label :window win
                 :run!* run-fn :stop!* stop-fn
                 :open-path! (fn [p]
                               (guarded-open! ed cv discard-warned?
                                              (fn [] (open-path! ed cv path p) (update-title! ed win path))))
                 :save-to! (fn [p] (save-to! ed cv path p) (update-title! ed win path))
                 ;; M5/M4 self-test hook: the exact fn the "key-pressed"
                 ;; signal calls, so a row can invoke it directly with a
                 ;; synthetic keyval/state -- an ordinary function call, not
                 ;; a synthesized OS keystroke.
                 :handle-window-key! window-key-fn
                 ;; M2 self-test hook: the exact dialog-result decisions,
                 ;; driven with a synthetic result map shaped like
                 ;; g/finish-file's -- file-dialog! itself can't be driven
                 ;; without a live dialog.
                 :simulate-open-result! (fn [result] (handle-open-result! ed cv win path result))
                 :simulate-save-result! (fn [result] (handle-save-result! ed cv win path result))}))))

(defn start!
  "Builds the Jonquil window and blocks in the GTK main loop. opts is
  {:file path-or-nil :on-ready (fn [ui])}. ui is {:editor :console :session
  :status-label :window :run!* :stop!* :open-path! :save-to!
  :handle-window-key! :simulate-open-result! :simulate-save-result!}; the
  self-test rows call these functions directly, exactly as the buttons and
  keys do."
  [opts]
  (let [app (g/gtk-application-new "net.b12n.jonquil" g/APPLICATION-DEFAULT-FLAGS)]
    (g/g-signal-connect-data app "activate"
                             (callable (fn [app _data] (activate! app opts)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-application-run app 0 ffi/null)
    nil))
