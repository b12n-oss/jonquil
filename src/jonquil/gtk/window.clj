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
            [jonquil.commands :as commands]
            [jonquil.console :as console]
            [jonquil.eval :as eval]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.session :as session]))

;; --- keys (gtk-keys.md; KEY-R is a P2.T7 addition the table doesn't carry)
;; ----------------------------------------------------------------------

(def ^:private KEY-F5 0xffc2)
(def ^:private KEY-ESCAPE 0xff1b)
(def ^:private KEY-R 0x72)
(def ^:private KEY-O 0x6f)
(def ^:private KEY-S 0x73)
(def ^:private KEY-W 0x77)
;; P3.T3 additions (gtk-keys.md, values read from gdkkeysyms.h 2026-09-24).
(def ^:private KEY-F6 0xffc3)
(def ^:private KEY-E 0x065)
(def ^:private KEY-L 0x06c)
(def ^:private KEY-EQUAL 0x03d)
(def ^:private KEY-PLUS 0x02b)
(def ^:private KEY-MINUS 0x02d)
(def ^:private KEY-0 0x030)
;; P3.T4 additions.
(def ^:private KEY-F 0x066)
(def ^:private KEY-H 0x068)

(defn- command-chord?
  "A command chord is Control or Meta (gtk-keys.md), so the same shortcut
  works on Linux and macOS."
  [state]
  (not (zero? (bit-and state (bit-or g/CONTROL-MASK g/META-MASK)))))

(defn- shift-held? [state]
  (not (zero? (bit-and state g/SHIFT-MASK))))

(defn- alt-held?
  "Guards Cmd/Ctrl+L (clear console) from also matching editor.clj's
  Cmd/Ctrl+Alt+L (format current line): the window-level key controller
  runs in the capture phase, before the editor's own, so without this
  guard Cmd+Alt+L would be swallowed here and never reach it."
  [state]
  (not (zero? (bit-and state g/ALT-MASK))))

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

;; P3.T5 (parked P2 item M9): file-dialog! used to build a FRESH foreign-
;; callable on every Open/Save, retained forever (jonquil.gtk.callbacks's
;; own contract), so a long session leaked one native trampoline per
;; dialog. One callable per MODE, reused for every call, fixes it: the
;; callable reads the CURRENT dialog object and on-result fn from these
;; atoms, which file-dialog! resets just before starting each async call.
;; It also unrefs the GtkFileDialog once its result has arrived, the
;; leak's other half.
(defonce ^:private open-dialog-atom (atom nil))
(defonce ^:private open-on-result-atom (atom nil))
(defonce ^:private save-dialog-atom (atom nil))
(defonce ^:private save-on-result-atom (atom nil))

(defn- make-file-dialog-cb [finish dialog-atom on-result-atom]
  (callable (fn [_src res _data]
              (let [d @dialog-atom on-result @on-result-atom]
                (try (on-result (g/finish-file finish d res))
                     (finally (g/g-object-unref d))))
              nil)
            [:pointer :pointer :pointer] :void))

(defonce ^:private open-cb
  (make-file-dialog-cb g/gtk-file-dialog-open-finish open-dialog-atom open-on-result-atom))
(defonce ^:private save-cb
  (make-file-dialog-cb g/gtk-file-dialog-save-finish save-dialog-atom save-on-result-atom))

(defn- file-dialog!
  "mode is :open or :save. on-result gets {:path s} or {:error msg :code n}."
  [mode window on-result]
  (let [d (g/gtk-file-dialog-new)
        [dialog-atom on-result-atom cb start]
        (if (= mode :open)
          [open-dialog-atom open-on-result-atom open-cb g/gtk-file-dialog-open]
          [save-dialog-atom save-on-result-atom save-cb g/gtk-file-dialog-save])]
    (reset! dialog-atom d)
    (reset! on-result-atom on-result)
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

(defn- resolve-target
  "path's real underlying file (M12 follow-up (a)). .getCanonicalPath
  follows a symlink to what it points at, and works even when path (or
  its parent directories) doesn't exist yet -- probed against this Jolt.
  java.nio.file.Files/move onto a symlink's OWN path replaces the link
  itself with a regular file and leaves whatever it pointed at untouched
  (probed: after such a move the link path is no longer a symlink at all,
  and the file it used to point at is unchanged); moving onto the
  resolved target instead updates the real file and leaves the link a
  link, still pointing at the same place, with the new content visible
  through it."
  [path]
  (.getCanonicalPath (java.io.File. ^String path)))

(defn- copy-permissions!
  "Copies target's POSIX permissions onto tmp when target already exists
  (M12 follow-up (b)): a freshly spit temp file gets the process umask's
  default mode (probed: rw-r--r--, i.e. 644), so saving over an
  executable (755) file silently dropped its +x. Files/setPosixFile-
  Permissions exists and works on this Jolt; java.io.File's
  setExecutable/setReadable/setWritable do NOT -- probed, and both the
  1-arg and 2-arg forms raise 'No matching method ... found' -- so there
  is no fallback to reach for here."
  [target tmp]
  (when (.exists (java.io.File. ^String target))
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath (java.io.File. ^String tmp))
     (java.nio.file.Files/getPosixFilePermissions (.toPath (java.io.File. ^String target))))))

(defn- name-user-path
  "Rewrites any occurrence of tmp (the hidden temp filename) or target
  (path resolved through symlinks, which can differ from what the user
  typed) inside an exception message back to path -- an exact literal
  replacement, not a guessed pattern, since both strings are ones this
  namespace itself built or resolved a moment earlier. Used so a save
  error never names a file the user never typed (M12 follow-up)."
  [msg tmp target path]
  (-> msg (str/replace tmp path) (str/replace target path)))

(defn- save-to!
  "Writes the editor text to path without ever truncating it in place
  (M12), and without breaking a symlink or dropping the target's
  permissions (M12 follow-up): resolves path to its real underlying file
  (resolve-target), spits to a temp file in THAT file's directory, copies
  its POSIX permissions onto the temp file when it already exists
  (copy-permissions!), then renames the temp file over the RESOLVED
  target with java.nio.file.Files/move + ATOMIC_MOVE (probed against this
  Jolt: see the fix-wave report) so a failure mid-write can't leave a
  half-written target -- and so a symlink target gets updated in place
  rather than replaced by the rename. mark-saved! and remembering path
  (the user's own path, not the resolved target) only happen once the
  rename has actually succeeded. An error is appended as :error, naming
  path rather than the hidden temp file or a resolved target that
  differs from it, and never swallowed -- losing the mark that a save
  failed would risk the user believing unsaved work is safe when it
  isn't -- and the temp file is cleaned up on any failure; the rename
  never runs at all when the write itself fails, so the target is never
  touched. Doesn't touch the title; see open-path!'s docstring."
  [ed cv path-atom path]
  (let [target (resolve-target path)
        tmp (temp-path-for target)]
    (try
      (spit tmp (editor/editor-text ed))
      (copy-permissions! target tmp)
      (java.nio.file.Files/move
       (.toPath (java.io.File. ^String tmp))
       (.toPath (java.io.File. ^String target))
       (into-array java.nio.file.CopyOption
                   [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                    java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      (editor/mark-saved! ed)
      (reset! path-atom path)
      (catch :default e
        (let [f (java.io.File. ^String tmp)]
          (when (.exists f) (.delete f)))
        (cview/append! cv :error
                       (str "; save: " (name-user-path (ex-message e) tmp target path) "\n"))))))

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

(defn- dialog-dismissed?
  "True only for GTK's own GTK_DIALOG_ERROR_DISMISSED (M2), checking BOTH
  the numeric code AND the GError domain quark: code 2 is also
  G_IO_ERROR_EXISTS in a different domain, and code alone can't tell them
  apart -- see ffi.clj's gtk-dialog-error-quark citation."
  [{:keys [code domain]}]
  (and (= code g/GTK-DIALOG-ERROR-DISMISSED)
       (= domain (g/gtk-dialog-error-quark))))

(defn- handle-open-result!
  "The file-dialog! :open callback's decision, factored out so a self-test
  row can drive it with a synthetic result map shaped like g/finish-file's
  (M2's dismissed-dialog case has no other way to reach it without a live
  dialog)."
  [ed cv win path-atom {:keys [path error] :as result}]
  (cond
    path (do (open-path! ed cv path-atom path)
             (update-title! ed win path-atom))
    ;; M2: a cancelled/dismissed dialog is not an error.
    (dialog-dismissed? result) nil
    :else (cview/append! cv :error (str "; open: " error "\n"))))

(defn- do-open! [ed cv win path-atom discard-warned?]
  (guarded-open!
   ed cv discard-warned?
   (fn [] (file-dialog! :open win (fn [result] (handle-open-result! ed cv win path-atom result))))))

(defn- handle-save-result!
  "handle-open-result!'s counterpart for file-dialog! :save."
  [ed cv win path-atom {:keys [path error] :as result}]
  (cond
    path (do (save-to! ed cv path-atom path)
             (update-title! ed win path-atom))
    (dialog-dismissed? result) nil
    :else (cview/append! cv :error (str "; save: " error "\n"))))

(defn- do-save-as! [ed cv win path-atom]
  (file-dialog! :save win (fn [result] (handle-save-result! ed cv win path-atom result))))

(defn- do-save! [ed cv win path-atom]
  (if-let [path @path-atom]
    (do (save-to! ed cv path-atom path)
        (update-title! ed win path-atom))
    (do-save-as! ed cv win path-atom)))

;; --- ask before closing (P3.T5, probe 3) ------------------------------------

;; The alert dialog's own retained callable (M9, same reasoning as
;; open-cb/save-cb above): built once and reused for every close attempt.
;; alert-dialog-atom/alert-on-choice-atom hold the CURRENT attempt's dialog
;; object and decision fn, reset just before gtk_alert_dialog_choose starts.
(defonce ^:private alert-dialog-atom (atom nil))
(defonce ^:private alert-on-choice-atom (atom nil))
(defonce ^:private alert-choose-cb
  (callable (fn [_src res _data]
              (let [d @alert-dialog-atom on-choice @alert-on-choice-atom]
                (try (on-choice (g/finish-choice d res))
                     (finally (g/g-object-unref d))))
              nil)
            [:pointer :pointer :pointer] :void))

(defn- show-close-dialog!
  "Builds a fresh GtkAlertDialog (probe 3: g_object_new_with_properties,
  0 properties -- gtk_alert_dialog_new is variadic and is never bound) for
  THIS close attempt (the message needs the current file name, which can
  differ between attempts after a Save As), but reuses the ONE retained
  alert-choose-cb across every attempt. Cancel button 2, default button 0,
  per the brief."
  [win name on-choice]
  (let [d (g/g-object-new-with-properties (g/gtk-alert-dialog-get-type) 0 ffi/null ffi/null)]
    (reset! alert-dialog-atom d)
    (reset! alert-on-choice-atom on-choice)
    (g/gtk-alert-dialog-set-message d (str "Save changes to " name " before closing?"))
    (ffi/with-c-string-array [labels 4] ["Save" "Don't Save" "Cancel" nil]
      (g/gtk-alert-dialog-set-buttons d labels))
    (g/gtk-alert-dialog-set-cancel-button d 2)
    (g/gtk-alert-dialog-set-default-button d 0)
    (g/gtk-alert-dialog-choose d win ffi/null alert-choose-cb ffi/null)))

(defn- finish-close!
  "Confirms and re-triggers the window's own close-request (probe 3):
  gtk_window_close synchronously re-enters close-request-fn (below), which
  this time (confirmed-close? true) either quits for real or, when
  suppress-close? is set, only records that it WOULD have -- see
  close-request-fn's docstring for why."
  [win confirmed-close?]
  (reset! confirmed-close? true)
  (g/gtk-window-close win))

(defn- close-choice!
  "The GtkAlertDialog decision (probe 3; the brief's :close-choice!):
  index 0 Save, 1 Don't Save, 2 Cancel; an :error means the dialog was
  dismissed. Index 2 and an error both do nothing -- the window stays open
  and the text untouched. Index 0 with no remembered path opens the Save
  dialog; a successful save (editor no longer dirty afterward -- save-to!
  cleared it) confirms and closes, a cancelled or failed save leaves the
  window open. Rows call this directly with a synthetic {:index n}
  (probe 3), exactly as the real dialog's own completion callback does."
  [ed cv win path-atom confirmed-close? {:keys [index error]}]
  (cond
    error nil
    (= index 2) nil
    (= index 1) (finish-close! win confirmed-close?)
    (= index 0)
    (if-let [path @path-atom]
      (do (save-to! ed cv path-atom path)
          (update-title! ed win path-atom)
          (when-not (editor/dirty? ed) (finish-close! win confirmed-close?)))
      (file-dialog!
       :save win
       (fn [{:keys [path error] :as save-result}]
         (cond
           path (do (save-to! ed cv path-atom path)
                    (update-title! ed win path-atom)
                    (when-not (editor/dirty? ed) (finish-close! win confirmed-close?)))
           (dialog-dismissed? save-result) nil
           :else (cview/append! cv :error (str "; save: " error "\n"))))))
    :else nil))

(defn- close-request-fn
  "The window's close-request handler (P3.T5). Dirty and unconfirmed shows
  the alert and suppresses the close (returns 1, GTK does not proceed);
  otherwise it proceeds to quit -- unless suppress-close? is set, in which
  case it only flips closing? and still returns 1.

  suppress-close?/closing? exist ONLY for the self-test (dev/gtk_selftest_
  window.clj's close-dirty-discard/close-dirty-save/close-clean rows): a
  REAL gtk_window_close/g_application_quit synchronously re-enters this
  same handler and, once it returns 0, GTK proceeds to actually tear the
  window down and the pending quit fires as soon as control next reaches
  the main loop (the very next deferred g_timeout_add wait, which most
  later rows need) -- ending the shared self-test process before the rest
  of row-fns runs, silently dropping them from the summary. Only
  row-command-chord-close (P2.T10, already the deliberately-last row) is
  allowed to trigger the real thing; every other close-confirmation row
  sets suppress-close? true first and asserts @closing? instead of an
  actual close. While suppressed, confirmed-close? is also reset to false
  right here, so the attempt leaves no residue for the next row's own
  close-request to see -- rows only need to reset suppress-close?/closing?
  themselves afterward. See the report."
  [ed cv win app path-atom confirmed-close? suppress-close? closing?]
  (fn [_win _data]
    (if (and (editor/dirty? ed) (not @confirmed-close?))
      (do (show-close-dialog!
           win (if-let [p @path-atom] (file-name p) "Untitled")
           (fn [result] (close-choice! ed cv win path-atom confirmed-close? result)))
          1)
      (do (reset! closing? true)
          (if @suppress-close?
            (do (reset! confirmed-close? false) 1)
            (do (g/g-application-quit app) 0))))))

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
  must re-arm for the next run whether or not the append below throws.

  is-run? is true for run-definitions! and false for submit-repl! (M11):
  the EOF-unclosed-bracket hint reads the DEFINITIONS editor's own text,
  which is only relevant to a Run's own EOF -- a REPL entry's EOF (e.g. an
  unbalanced string argument to a nested load-string, since a genuinely
  unbalanced top-level REPL entry never reaches :eval at all per
  console/input-action) has nothing to do with whatever the Definitions
  pane currently holds, and must not name it as the culprit."
  [ed cv status-label is-run?]
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
                  (when (and is-run?
                             (= :error (:status result))
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
  "The upstream banner, printed ONLY when the Run actually starts (M1).
  eval/evaluating? is checked BEFORE run-definitions! and the banner (when
  printed) goes first, in that order, so it's never possible for the
  program's own output to land ahead of a banner that hasn't been
  enqueued yet. When the engine is already busy, run-definitions! returns
  its warning string instead of starting anything, and only that line is
  appended -- no banner.

  P3.T5: commands/pre-run-problem runs FIRST, against the editor's own
  text. When it names a bracket problem, Run never reaches session/run-
  definitions! at all -- nothing is evaluated, even a form before the
  broken one, so an earlier form's own unresolved-symbol error can never
  hide the bracket message (row pre-run-hidden-by-error). The P2 banner
  still prints (same not-already-evaluating guard as the normal path)
  before the problem is appended as :error, and the status is set to
  Ready."
  [ed cv session status-label]
  (let [text (editor/editor-text ed)
        problem (commands/pre-run-problem text)]
    (when-not (eval/evaluating? (:ctx session))
      (cview/append! cv :system "; --- Running Definitions ---\n"))
    (if problem
      (do (cview/append! cv :error (str problem "\n"))
          (set-status! status-label "Ready"))
      (let [warning (session/run-definitions! session text (make-eval-opts ed cv status-label true))]
        (when warning (cview/append! cv :system warning))))))

(defn- do-stop! [session]
  (eval/stop-eval! (:ctx session))
  nil)

;; --- REPL: the console's on-submit, and the history adapters ---------------

(defn- do-repl-eval!
  "submit-repl! with the same opts run uses, minus the banner -- and the
  same warning-append bookkeeping, since submit-repl! can return the same
  busy warning run-definitions! can."
  [ed cv session status-label text]
  (let [warning (session/submit-repl! session text (make-eval-opts ed cv status-label false))]
    (when warning (cview/append! cv :system warning))))

(defn- do-run-selection!
  "Cmd/Ctrl+E: commands/run-selection-text (P3.T1) on the editor's snapshot
  is nil for a blank line, else the selection or the caret's whole line.
  When non-nil, echoes \"> text\" to the console as if typed, then goes
  through do-repl-eval! -- the SAME session path a typed REPL entry uses
  (history via session/submit-repl!, the busy-refusal warning, the same
  make-eval-opts)."
  [ed cv session status-label]
  (when-let [text (commands/run-selection-text (editor/snapshot ed))]
    (cview/echo-entry! cv text)
    (do-repl-eval! ed cv session status-label text)))

;; --- window commands (P3.T3): F6 focus toggle, clear console, zoom --------

(defn- do-toggle-focus!
  "F6 (gtk-keys.md, Jonquil's own key): moves focus between the editor and
  console panes. Compares gtk_root_get_focus's FOCUS WIDGET pointer, not
  gtk_widget_has_focus -- the P3 probe (findings #1) measured has_focus
  reading false on a window that isn't the OS key window, which a
  self-test window (and bb gtk:selftest's own window) usually isn't. When
  the console gets focus, cview/focus! also places its cursor at the end
  of the input."
  [win ed cv]
  (if (= (g/gtk-root-get-focus win) (:view ed))
    (cview/focus! cv)
    (editor/focus! ed)))

(defn- do-clear-console! [cv]
  (cview/clear-output! cv))

;; --- find/replace bar (P3.T4) -----------------------------------------

(defn- find-bar-focused?
  "True when the find bar's query or replacement entry currently holds
  gtk_root_get_focus's focus widget (P3.T3's own has_focus caveat applies
  here too, so this reads the ROOT's focus-widget pointer, not
  gtk_widget_has_focus). Used only to route Escape: the window's own
  capture-phase key controller runs BEFORE the bar's own (win is a more
  outer ancestor than the bar's box), so without this check Escape would
  always reach the window's evaluating?-gated Stop handling first,
  regardless of where focus actually is."
  [win fb]
  (let [f (g/gtk-root-get-focus win)]
    (or (= f (:query fb)) (= f (:replacement fb)))))

(defn- reload-zoom-css! [provider px]
  (g/gtk-css-provider-load-from-string provider (str ".jonquil-code { font-size: " px "px; }")))

(defn- do-zoom!
  "Cmd/Ctrl+=/-/0: the next font size from commands/zoom (P3.T1), reloaded
  into the one shared GtkCssProvider (added to the display once, in
  activate!) so both views resize live -- probe 4's measured behaviour."
  [provider zoom-px direction]
  (let [px (commands/zoom @zoom-px direction)]
    (reset! zoom-px px)
    (reload-zoom-css! provider px)))

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

;; --- window-level keys: F5, Escape, a command chord plus R/O/S/W ----------

(defn- on-window-key
  "evaluating? is (fn [] bool), read live from the engine. M5: the window
  consumes Escape (to Stop) only while something is actually evaluating;
  otherwise it returns 0 so Escape can reach an IME composition (or GTK's
  own default handling) instead of being swallowed here every time.

  M4: keyval is normalised with gdk_keyval_to_lower before every
  comparison below. Caps Lock reports the UPPERCASE keyval with no Shift
  bit set, so comparing the raw keyval against lowercase KEY-R/O/S/W broke
  those chords under Caps Lock; lower-casing first makes Ctrl+R/O/S/W fire
  regardless of Caps Lock, while Save As's own Shift requirement is still
  checked separately via the modifier mask, not the keyval's case.

  P2.T10: a command chord plus W closes the window, normalised and checked
  the same way as R/O/S. gtk_window_close fires the same close-request
  handler a real close-button click or OS window-close would, which already
  calls g_application_quit -- so this needs no extra quit call of its own,
  and (per the task) no dirty-buffer prompt, same as the close-button/OS
  path today.

  P3.T4: the find bar's own handle-key! is checked FIRST for Escape, before
  evaluating? even runs -- see find-bar-focused?'s docstring for why this
  can't just rely on the bar's own (separately wired) capture-phase
  controller. Cmd+F/Cmd+H open the bar; Alt and Shift are excluded from
  Cmd+F so it doesn't shadow editor.clj's Cmd+Alt+F (format selection) or
  Cmd+Shift+F (format all), the same guard style KEY-L already uses
  against Cmd+Alt+L.

  P3.T4 fix round 1: when the bar has focus, handle-key!'s OWN return
  value is this clause's final answer -- 1 (closed) or 0 (an IME preedit
  composition is active, per handle-key!'s own docstring). It must NOT be
  coerced into always-1-or-fall-through: a 0 has to fall all the way
  through to GTK's default handling (the composing entry's own IM
  context), never into evaluating?'s Stop below, or Escape would cancel
  the running program instead of the IME composition."
  [run-fn stop-fn open-fn save-fn save-as-fn close-fn
   run-selection-fn toggle-focus-fn clear-console-fn zoom-fn evaluating?
   win fb open-find-fn open-replace-fn]
  (fn [_ctl keyval0 _keycode state _data]
    (let [keyval (g/gdk-keyval-to-lower keyval0)]
      (cond
        (= keyval KEY-F5) (do (run-fn) 1)
        (and (= keyval KEY-ESCAPE) (find-bar-focused? win fb))
        (find-bar/handle-key! fb keyval0 state)
        (and (= keyval KEY-ESCAPE) (evaluating?)) (do (stop-fn) 1)
        (= keyval KEY-ESCAPE) 0
        (= keyval KEY-F6) (do (toggle-focus-fn) 1)
        (and (command-chord? state) (= keyval KEY-R)) (do (run-fn) 1)
        (and (command-chord? state) (= keyval KEY-O)) (do (open-fn) 1)
        (and (command-chord? state) (shift-held? state) (= keyval KEY-S)) (do (save-as-fn) 1)
        (and (command-chord? state) (= keyval KEY-S)) (do (save-fn) 1)
        (and (command-chord? state) (= keyval KEY-W)) (do (close-fn) 1)
        (and (command-chord? state) (= keyval KEY-E)) (do (run-selection-fn) 1)
        (and (command-chord? state) (not (alt-held? state)) (= keyval KEY-L)) (do (clear-console-fn) 1)
        (and (command-chord? state) (not (alt-held? state)) (not (shift-held? state)) (= keyval KEY-F))
        (do (open-find-fn) 1)
        (and (command-chord? state) (= keyval KEY-H)) (do (open-replace-fn) 1)
        (and (command-chord? state) (contains? #{KEY-EQUAL KEY-PLUS} keyval)) (do (zoom-fn :in) 1)
        (and (command-chord? state) (= keyval KEY-MINUS)) (do (zoom-fn :out) 1)
        (and (command-chord? state) (= keyval KEY-0)) (do (zoom-fn :reset) 1)
        :else 0))))

;; --- construction ------------------------------------------------------

(defn- activate! [app {:keys [file on-ready]}]
  (let [session (session/make-session)
        path (atom file)
        discard-warned? (atom false)
        ed (editor/make-editor)
        fb (find-bar/make-find-bar ed)
        win (g/gtk-application-window-new app)
        status-label (g/gtk-label-new "Ready")
        line-col-label (g/gtk-label-new "Ln 1, Col 1")
        confirmed-close? (atom false)
        ;; P3.T5 self-test hooks only -- see close-request-fn's docstring.
        suppress-close? (atom false)
        closing? (atom false)
        *cv (atom nil)
        cv (cview/make-console-view {:on-submit (make-on-submit ed *cv session status-label)
                                     :history-prev (history-prev-fn session)
                                     :history-next (history-next-fn session)
                                     ;; I2: the console reads busy/waiting state
                                     ;; straight from the engine, never a mirror.
                                     :evaluating? (fn [] (eval/evaluating? (:ctx session)))
                                     :waiting? (fn [] (eval/waiting-for-input? (:ctx session)))})
        _ (reset! *cv cv)
        provider (g/gtk-css-provider-new)
        zoom-px (atom commands/DEFAULT-FONT-PX)
        run-fn (fn [] (do-run! ed cv session status-label))
        stop-fn (fn [] (do-stop! session))
        open-fn (fn [] (do-open! ed cv win path discard-warned?))
        save-fn (fn [] (do-save! ed cv win path))
        save-as-fn (fn [] (do-save-as! ed cv win path))
        close-fn (fn [] (g/gtk-window-close win))
        run-selection-fn (fn [] (do-run-selection! ed cv session status-label))
        toggle-focus-fn (fn [] (do-toggle-focus! win ed cv))
        clear-console-fn (fn [] (do-clear-console! cv))
        zoom-fn (fn [direction] (do-zoom! provider zoom-px direction))
        open-find-fn (fn [] (find-bar/open! fb :find))
        open-replace-fn (fn [] (find-bar/open! fb :replace))
        window-key-fn (on-window-key run-fn stop-fn open-fn save-fn save-as-fn close-fn
                                     run-selection-fn toggle-focus-fn clear-console-fn zoom-fn
                                     (fn [] (eval/evaluating? (:ctx session)))
                                     win fb open-find-fn open-replace-fn)
        toolbar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        run-btn (g/gtk-button-new-with-label "Run (F5)")
        stop-btn (g/gtk-button-new-with-label "Stop (Esc)")
        open-btn (g/gtk-button-new-with-label "Open…")
        save-btn (g/gtk-button-new-with-label "Save…")
        reset-chk (g/gtk-check-button-new-with-label "Reset on Run")
        ;; P3.T5: the status bar becomes a horizontal box -- the P2 label
        ;; on the left (hexpand, so it claims the box's spare width) and
        ;; the new Ln/Col label on the right.
        status-box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        paned (g/gtk-paned-new g/ORIENTATION-VERTICAL)
        vbox (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        ;; P3.T4: the find bar's box sits above the editor's own scroll
        ;; view, inside a dedicated vertical box that becomes the paned's
        ;; start child (in place of (:scroll ed) directly) -- the scroll
        ;; view keeps vexpand so it, not the bar, absorbs the pane's
        ;; height.
        editor-pane (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        keyctl (g/gtk-event-controller-key-new)]
    (g/gtk-box-append toolbar run-btn)
    (g/gtk-box-append toolbar stop-btn)
    (g/gtk-box-append toolbar open-btn)
    (g/gtk-box-append toolbar save-btn)
    (g/gtk-box-append toolbar reset-chk)
    (g/gtk-check-button-set-active reset-chk 1)
    (g/gtk-widget-set-vexpand paned 1)
    (g/gtk-box-append editor-pane (:box fb))
    (g/gtk-widget-set-vexpand (:scroll ed) 1)
    (g/gtk-box-append editor-pane (:scroll ed))
    (g/gtk-paned-set-start-child paned editor-pane)
    (g/gtk-paned-set-end-child paned (:scroll cv))
    (g/gtk-paned-set-position paned 430)
    (g/gtk-box-append vbox toolbar)
    (g/gtk-box-append vbox paned)
    (g/gtk-widget-set-hexpand status-label 1)
    (g/gtk-box-append status-box status-label)
    (g/gtk-box-append status-box line-col-label)
    (g/gtk-box-append vbox status-box)
    (g/gtk-window-set-child win vbox)
    (g/gtk-window-set-default-size win 900 700)
    ;; P3.T3: one shared GtkCssProvider for the display, added once; both
    ;; views get the jonquil-code CSS class so a single reload resizes both
    ;; live (probe 4).
    (g/gtk-widget-add-css-class (:view ed) "jonquil-code")
    (g/gtk-widget-add-css-class (:view cv) "jonquil-code")
    (g/gtk-style-context-add-provider-for-display
     (g/gdk-display-get-default) provider g/STYLE-PROVIDER-PRIORITY-APPLICATION)
    (reload-zoom-css! provider @zoom-px)
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
                             (callable (close-request-fn ed cv win app path confirmed-close?
                                                         suppress-close? closing?)
                                       [:pointer :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (editor/on-dirty-changed! ed (fn [_dirty] (update-title! ed win path)))
    ;; I4: a dirty->clean transition (a successful Save) re-arms the
    ;; discard warning, so an unrelated LATER dirty streak earns its own.
    (editor/on-dirty-changed! ed (fn [dirty] (when-not dirty (reset! discard-warned? false))))
    ;; P3.T5: the status bar's Ln/Col label, editor position only (probe 6).
    (editor/on-cursor-moved!
     ed (fn [[line col]] (g/gtk-label-set-text line-col-label (str "Ln " (inc line) ", Col " (inc col)))))
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
                 :line-col-label line-col-label
                 :find-bar fb
                 :run!* run-fn :stop!* stop-fn
                 :open-path! (fn [p]
                               (guarded-open! ed cv discard-warned?
                                              (fn [] (open-path! ed cv path p) (update-title! ed win path))))
                 :save-to! (fn [p] (save-to! ed cv path p) (update-title! ed win path))
                 ;; P3.T3 self-test hooks: the exact fns the F6/Cmd+E/Cmd+L/
                 ;; zoom key chords call, so rows can drive them directly.
                 :run-selection!* run-selection-fn
                 :toggle-focus!* toggle-focus-fn
                 :clear-console!* clear-console-fn
                 :zoom!* zoom-fn
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
                 :simulate-save-result! (fn [result] (handle-save-result! ed cv win path result))
                 ;; P3.T5: close-choice! is the exact fn the real alert
                 ;; dialog's own completion callback calls; request-close!*
                 ;; runs gtk_window_close, the same path the window's close
                 ;; button/OS chrome uses. closing?/suppress-close? are
                 ;; self-test-only hooks (close-request-fn's docstring).
                 :close-choice! (fn [result] (close-choice! ed cv win path confirmed-close? result))
                 :request-close!* (fn [] (g/gtk-window-close win))
                 :closing? closing?
                 :suppress-close? suppress-close?}))))

(defn start!
  "Builds the Jonquil window and blocks in the GTK main loop. opts is
  {:file path-or-nil :on-ready (fn [ui])}. ui is {:editor :console :session
  :status-label :line-col-label :window :find-bar :run!* :stop!*
  :open-path! :save-to! :run-selection!* :toggle-focus!* :clear-console!*
  :zoom!* :handle-window-key! :simulate-open-result! :simulate-save-result!
  :close-choice! :request-close!* :closing? :suppress-close?}; the
  self-test rows call these functions directly, exactly as the buttons and
  keys do. :find-bar is the jonquil.gtk.find-bar map (P3.T4); rows call
  jonquil.gtk.find-bar's own functions on it."
  [opts]
  ;; M6: G_APPLICATION_NON_UNIQUE, so a second launch starts its own
  ;; process instead of forwarding "activate" into an already-running one
  ;; over D-Bus (the default GApplication behaviour for a shared app id).
  (let [app (g/gtk-application-new "net.b12n.jonquil"
                                   (bit-or g/APPLICATION-DEFAULT-FLAGS g/G-APPLICATION-NON-UNIQUE))]
    (g/g-signal-connect-data app "activate"
                             (callable (fn [app _data] (activate! app opts)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-application-run app 0 ffi/null)
    nil))
