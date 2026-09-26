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
            [jonquil.docs :as docs]
            [jonquil.eval :as eval]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.popover :as popover]
            [jonquil.help :as help]
            [jonquil.session :as session]
            [jonquil.settings :as settings]
            [jonquil.theme :as theme]))

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
;; TH.T5 addition (gdkkeysyms.h:459, GDK_KEY_m).
(def ^:private KEY-M 0x06d)

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
;; dialog. One callable per MODE, reused for every call, fixes that half.
;;
;; Fix round 1 (Important #1, the same fix as the alert dialog below): on-
;; result is tracked per DIALOG OBJECT, keyed by the completion callback's
;; own source_object (GAsyncReadyCallback's first argument), never by a
;; single "current" atom -- two dialogs of the same mode pending at once
;; used to let the second's completion finish/unref the WRONG object and
;; silently drop the first's result (the same bug the close-alert row
;; demonstrates; unlikely in normal use since Open/Save is effectively
;; modal, but the fix is the same either way).
(defonce ^:private open-on-result-by-dialog (atom {}))
(defonce ^:private save-on-result-by-dialog (atom {}))

(defn- make-file-dialog-cb [finish on-result-by-dialog]
  (callable (fn [src res _data]
              (when-let [on-result (get @on-result-by-dialog src)]
                (swap! on-result-by-dialog dissoc src)
                (try (on-result (g/finish-file finish src res))
                     (finally (g/g-object-unref src))))
              nil)
            [:pointer :pointer :pointer] :void))

(defonce ^:private open-cb (make-file-dialog-cb g/gtk-file-dialog-open-finish open-on-result-by-dialog))
(defonce ^:private save-cb (make-file-dialog-cb g/gtk-file-dialog-save-finish save-on-result-by-dialog))

(defn- file-dialog!
  "mode is :open or :save. on-result gets {:path s} or {:error msg :code n}."
  [mode window on-result]
  (let [d (g/gtk-file-dialog-new)
        [on-result-by-dialog cb start]
        (if (= mode :open)
          [open-on-result-by-dialog open-cb g/gtk-file-dialog-open]
          [save-on-result-by-dialog save-cb g/gtk-file-dialog-save])]
    (swap! on-result-by-dialog assoc d on-result)
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

;; Fix round 1 (Important #1): each pending alert dialog tracks its OWN
;; on-choice fn, keyed by the completion callback's own source_object
;; (GAsyncReadyCallback's first argument -- for gtk_alert_dialog_choose
;; this is the GtkAlertDialog itself), never a single "current dialog"
;; atom. With two alerts pending, the old shared-atom design let the
;; second overwrite which dialog/callback the FIRST's own real completion
;; would use: gtk_alert_dialog_choose_finish(wrong-dialog, first-result)
;; fails GTK's own source-object check, returns -1 with NO GError, decodes
;; as {:error "no index and no GError"}, and close-choice!'s error branch
;; treats that as a dismissal -- silently dropping the user's real Save/
;; Don't Save choice -- while the WRONG dialog gets released, and the
;; RIGHT one (still its own pending operation's real source object) never
;; does, a leak that becomes a double release once it later completes for
;; real too.
(defonce ^:private alert-on-choice-by-dialog (atom {}))

;; Swappable only for the self-test (row-close-alert-routing): lets
;; complete-alert! be driven with synthetic, non-GTK "dialog" identities
;; without ever calling the real g_object_unref on something that isn't a
;; live GObject. Defaults to the real release.
(defonce ^:private alert-release! (atom g/g-object-unref))

;; Self-test observability only: counts real GtkAlertDialogs actually
;; built, so row-close-alert-pending-guard can assert the guard below
;; really did prevent a second one.
(defonce ^:private alert-dialog-count (atom 0))

;; Guards close-request-fn (below) against opening a SECOND alert while
;; one is already pending: set true right before gtk_alert_dialog_choose
;; starts, cleared once THIS dialog's own on-choice actually runs
;; (success, Cancel, or dismissal all clear it, via complete-alert!).
(defonce ^:private alert-pending? (atom false))

(defn- register-alert-choice!
  "Tracks on-choice under dialog's own identity: a raw pointer for a real
  GtkAlertDialog (show-close-dialog!, below), or any comparable value in
  a self-test row driving complete-alert! directly."
  [dialog on-choice]
  (swap! alert-on-choice-by-dialog assoc dialog on-choice))

(defn- complete-alert!
  "Routes ONE alert's async completion by its own identity (src). Looks
  up and removes src's own on-choice, calls it with the already-decoded
  result, and releases src exactly once, then clears alert-pending? so a
  later close-request can open a fresh alert. A src with no registration
  (already completed, or never registered) is a no-op -- idempotent
  against a stray re-invocation, so calling this twice with the SAME src
  never double-releases."
  [src result]
  (when-let [on-choice (get @alert-on-choice-by-dialog src)]
    (swap! alert-on-choice-by-dialog dissoc src)
    (try (on-choice result)
         (finally (@alert-release! src) (reset! alert-pending? false)))))

(defonce ^:private alert-choose-cb
  (callable (fn [src res _data] (complete-alert! src (g/finish-choice src res)) nil)
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
    (swap! alert-dialog-count inc)
    (register-alert-choice! d on-choice)
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

;; --- popover teardown (P4.T4 fix round 1) -----------------------------

(defn- unparent-registered-popovers!
  "Unparents every popover in popovers (a jonquil.gtk.popover map) whose
  GtkWidget parent isn't already null -- gtk_widget_unparent asserts a
  non-null parent (a critical warning, not a crash, but pointless noise),
  so an already-unparented entry (a self-test row's own throwaway popover
  that unparents itself right after hide!, e.g.
  row-window-escape-closes-popover) is skipped rather than warned at.

  P4.T4 fix round 2: hide!s an OPEN popover FIRST (same idiom
  close-popovers! uses), before unparenting it. Without this, an open
  popover's own :open? atom stayed true after its widget was unparented
  (and, for a floating-ref GtkWidget with no other reference, finalized
  right there); window teardown itself then clears the editor's focus,
  GtkEventControllerFocus's \"leave\" fires this window's own hide-on-
  focus-lost listener, (popover/open? p) still reads true, and hide! calls
  gtk_popover_popdown on the now-freed widget -- measured live: \"Gtk-
  CRITICAL: gtk_popover_popdown: assertion 'GTK_IS_POPOVER (popover)'
  failed\" (see the report's reproduction). Resetting :open? here, before
  unparenting, means every later teardown-triggered listener's own
  (popover/open? p) check already reads false and skips calling hide!
  again -- the same reasoning that makes this function idempotent against
  an already-unparented entry above.

  Called from close-request-fn's own REAL-quit branch only (below), right
  before g_application_quit and the return 0 that lets GTK actually start
  tearing the window down (close-request-fn's own docstring: \"once it
  returns 0, GTK proceeds to actually tear the window down\"). A session-
  lifetime popover (jonquil.gtk.popover's hide! never unparents -- see its
  ns docstring, an intentional P4.T3 design) is still parented to its
  scrolled-window parent at that point; without this, GTK's own dispose
  finds it still there when that widget finalizes and reports \"Finalizing
  GtkScrolledWindow ..., but it still has children left: GtkPopover ...\"
  (measured live, P4.T4 fix round 1's own report). Never called from the
  self-test's suppress-close? branch -- that path returns 1 and never
  actually closes anything, so unparenting there would silently break
  every later row's use of the SAME session-lifetime popover."
  [popovers]
  (doseq [p @popovers]
    (let [pop (:popover p)]
      (when (popover/open? p) (popover/hide! p))
      (when-not (ffi/null? (g/gtk-widget-get-parent pop))
        (g/gtk-widget-unparent pop)))))

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
  themselves afterward. See the report.

  Fix round 1 (Important #1): a second close-request while an alert is
  already pending (alert-pending?) no longer opens a second one -- it
  still suppresses the close (returns 1), same as any other dirty-and-
  unconfirmed attempt, so the user just sees the same already-open alert
  again instead of a confusing second window."
  [ed cv win app path-atom confirmed-close? suppress-close? closing? popovers]
  (fn [_win _data]
    (if (and (editor/dirty? ed) (not @confirmed-close?))
      (do (when-not @alert-pending?
            (reset! alert-pending? true)
            ;; P3 fix wave #10: a throw while building the alert used to
            ;; leave alert-pending? true for the rest of the session (every
            ;; later close silently did nothing) and escape into GTK's
            ;; signal emission. Reset the flag and report it instead; the
            ;; window stays open, since the text is still unsaved.
            (try
              (show-close-dialog!
               win (if-let [p @path-atom] (file-name p) "Untitled")
               (fn [result] (close-choice! ed cv win path-atom confirmed-close? result)))
              (catch :default e
                (reset! alert-pending? false)
                (cview/append! cv :error (str "; close: " (ex-message e) "\n")))))
          1)
      (do (reset! closing? true)
          (if @suppress-close?
            (do (reset! confirmed-close? false) 1)
            ;; P4.T4 fix round 1: unparent every registered P4 popover
            ;; BEFORE the real quit -- see unparent-registered-popovers!'s
            ;; own docstring for why this branch specifically, never the
            ;; suppress-close? one above.
            (do (unparent-registered-popovers! popovers)
                (g/g-application-quit app)
                0))))))

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
                  ;; P4.T2 review's Important, widened by P4.T4: docs/
                  ;; symbol-doc memoises on [ns sym text], so a name looked
                  ;; up before this evaluation defined (or removed) it
                  ;; would otherwise keep serving that stale answer for the
                  ;; SAME [ns sym text] forever after. Clearing here covers
                  ;; every completed evaluation, Run and REPL entry alike
                  ;; -- make-eval-opts is shared by both (is-run? only
                  ;; gates the EOF hint below) -- not only after Run. A
                  ;; plain atom reset, safe to call from this worker
                  ;; thread with no on-gui! (unlike everything else here).
                  (docs/clear-doc-cache!)
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

  P3.T5: commands/pre-run-problem runs against the editor's own text, but
  ONLY when the engine is idle. When it names a bracket problem, Run
  never reaches session/run-definitions! at all -- nothing is evaluated,
  even a form before the broken one, so an earlier form's own unresolved-
  symbol error can never hide the bracket message (row pre-run-hidden-by-
  error). The banner still prints before the problem is appended as
  :error, and the status is set to Ready.

  Fix round 1 (Important #2): a Run while one is ALREADY running must
  stay exactly the P2 path -- eval/evaluating? is checked FIRST, before
  the bracket check ever runs, so a second Run never sets the status to
  Ready (the first program is still running) and never appends a bracket
  :error into the middle of its output; only session/run-definitions!'s
  own busy-refusal warning is appended, same as P2, with no banner (the
  busy path never printed one) and no status change (eval/eval-async's
  own busy branch returns its warning string without ever calling
  on-status-change)."
  [ed cv session status-label]
  (if (eval/evaluating? (:ctx session))
    (let [warning (session/run-definitions! session (editor/editor-text ed)
                                            (make-eval-opts ed cv status-label true))]
      (when warning (cview/append! cv :system warning)))
    (let [text (editor/editor-text ed)
          problem (commands/pre-run-problem text)]
      (cview/append! cv :system "; --- Running Definitions ---\n")
      (if problem
        (do (cview/append! cv :error (str problem "\n"))
            (set-status! status-label "Ready"))
        (let [warning (session/run-definitions! session text (make-eval-opts ed cv status-label true))]
          (when warning (cview/append! cv :system warning)))))))

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

;; --- popovers (P4.T3): a registry so Escape can close whichever P4
;; popover happens to be open -------------------------------------------

(defn- register-popover!
  "Adds p (a jonquil.gtk.popover map) to popovers, this window's own
  registry of every P4 popover it has created. Completion, quick doc and
  rename (P4.T4-T7) each register their own popover once, at construction
  -- created once, shown/hidden repeatedly, the same lifecycle
  :close-popovers!* below assumes."
  [popovers p]
  (swap! popovers conj p)
  nil)

(defn- close-popovers!
  "Closes every OPEN popover in popovers (jonquil.gtk.popover/hide!) and
  returns whether any was open -- the window's own :close-popovers!*, which
  on-window-key's new Escape clause calls ahead of the evaluating? Stop
  clause (probe 4 finding #2: the window's capture-phase controller runs
  before a popover ever sees the key, so closing a popover during a run
  must happen here, before Escape can also Stop the program)."
  [popovers]
  (let [open (filter popover/open? @popovers)]
    (doseq [p open] (popover/hide! p))
    (boolean (seq open))))

;; --- quick doc and jump (P4.T4) -----------------------------------------

(defn- place-caret-and-scroll!
  "Places ed's cursor at offset and scrolls it into view -- the same
  gtk_text_view_scroll_to_iter pattern jonquil.gtk.find-bar's select-
  match! uses (P4 probe 2), in two with-iters* calls since scroll-to-iter
  needs its own fresh iter."
  [ed offset]
  (let [buf (:buf ed)]
    (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
    (g/with-iters* buf offset offset
      (fn [a _] (g/gtk-text-view-scroll-to-iter (:view ed) a 0.0 0 0.0 0.0)))))

(defn- do-quick-doc!
  "Ctrl+Q/Shift+F1 (P4.T4), the ui's own :quick-doc!*: help/target on ed's
  current snapshot; none sets status \"No symbol at the caret.\"
  Otherwise looks up docs/symbol-doc in the session's own prompt
  namespace and shows help/doc-text in the quick-doc popover at the
  caret. Wrapped in try/catch (the reviewer clause: no lookup may throw
  out of a key handler) even though docs/symbol-doc already catches its
  own exceptions -- help/doc-text and syntax/format-autocomplete-doc run
  outside that catch, and this is the editor's own key dispatch calling
  straight through, with nothing else between it and a throw.

  P4.T5: close-popovers-fn runs right before show-doc! -- so opening quick
  doc closes completion's popover too, through the SAME registry Escape
  already uses (P4.T3), never a pairwise special case against completion
  by name."
  [ed session status-label help-pop close-popovers-fn]
  (try
    (let [snap (editor/snapshot ed)]
      (if-let [{:keys [symbol start]} (help/target snap)]
        (let [doc (docs/symbol-doc (:prompt-ns @(:state session)) symbol (:text snap) start)]
          (close-popovers-fn)
          (help-popover/show-doc! help-pop (help/doc-text symbol doc)))
        (set-status! status-label "No symbol at the caret.")))
    (catch :default e
      (set-status! status-label (str "; quick doc: " (ex-message e))))))

(defn- do-jump!
  "F12/Ctrl+B (P4.T4), the ui's own :jump!*: help/jump-target on ed's
  current snapshot, using docs/symbol-doc as the doc-info external
  fallback. :buffer places the cursor at :offset and scrolls it into
  view, status \"Jumped to <sym>.\"; :external shows its own doc in the
  quick-doc popover (closing every OTHER open P4 popover first, same
  close-popovers-fn reasoning as do-quick-doc! above); jump-target's own
  nil case (no definition anywhere) reads as \"No definition found for
  <sym>.\" -- the brief's three cases. No symbol at the caret at all
  (help/target nil, so there is no sym to name) falls back to quick doc's
  own \"No symbol at the caret.\" message instead of naming a nil symbol;
  no row exercises this edge, it's just the sane completion of the two
  named cases. Same try/catch reasoning as do-quick-doc!."
  [ed session status-label help-pop close-popovers-fn]
  (try
    (let [snap (editor/snapshot ed)]
      (if-let [{:keys [symbol start]} (help/target snap)]
        (let [doc (docs/symbol-doc (:prompt-ns @(:state session)) symbol (:text snap) start)
              target (help/jump-target snap doc)]
          (cond
            (= :buffer (:kind target))
            (do (place-caret-and-scroll! ed (:offset target))
                (set-status! status-label (str "Jumped to " symbol ".")))

            (= :external (:kind target))
            (do (close-popovers-fn)
                (help-popover/show-doc! help-pop (help/doc-text symbol (:doc target))))

            :else
            (set-status! status-label (str "No definition found for " symbol "."))))
        (set-status! status-label "No symbol at the caret.")))
    (catch :default e
      (set-status! status-label (str "; jump: " (ex-message e))))))

;; --- completion (P4.T5) -------------------------------------------------

(defn- do-complete!
  "Ctrl+Space, the ui's own :complete!*: completion/open! on ed's current
  snapshot. Wrapped in try/catch, the same reviewer-clause reasoning as
  do-quick-doc!/do-jump! above -- completion/open! runs help/prefix-at,
  docs/candidates, help/open-state and, for a single candidate, editor/
  apply-op! straight through, with nothing else between it and a throw."
  [ed completion status-label]
  (try
    (completion/open! completion (editor/snapshot ed))
    (catch :default e
      (set-status! status-label (str "; complete: " (ex-message e))))))

;; --- find/replace bar (P3.T4) -----------------------------------------

(defn- find-bar-focused?
  "True when keyboard focus is anywhere inside the find bar (P3.T3's own
  has_focus caveat applies here too, so this reads the ROOT's focus-widget
  pointer via gtk_root_get_focus, not gtk_widget_has_focus). Used only to
  route Escape: the window's own capture-phase key controller runs BEFORE
  the bar's own (win is a more outer ancestor than the bar's box), so
  without this check Escape would always reach the window's
  evaluating?-gated Stop handling first, regardless of where focus
  actually is.

  Fix round 3: this used to compare the focus widget against (:query fb)/
  (:replacement fb) directly, which is always false -- fix round 2's own
  diagnosis measured that gtk_root_get_focus actually returns an entry's
  internal GtkText DELEGATE, never the outer GtkSearchEntry/GtkEntry
  itself. gtk_widget_is_ancestor(focus, box) sidesteps needing to know
  about the delegate at all: true for anything inside the bar's box,
  including the delegate, measured true with focus in the query and the
  replacement entry, false with focus in the editor and the console."
  [win fb]
  (let [f (g/gtk-root-get-focus win)]
    (and (not (ffi/null? f)) (not (zero? (g/gtk-widget-is-ancestor f (:box fb)))))))

(defn- reload-code-css!
  "Loads theme t's CSS (jonquil.theme/code-css) at font size px into the
  one shared GtkCssProvider, and remembers the CSS string in code-css
  (TH.T4, an atom kept for now; fix wave item 7 moved :code-css*'s own
  self-test read to the provider's own gtk_css_provider_to_string
  serialisation instead, via g/css-provider-string, so this mirror is no
  longer read anywhere -- left in place since removing it threads a
  parameter change through five functions for no behavioural gain, out of
  scope for this item)."
  [provider t px code-css]
  (let [css (theme/code-css t px)]
    (reset! code-css css)
    (g/gtk-css-provider-load-from-string provider css)))

(defn- do-zoom!
  "Cmd/Ctrl+=/-/0: the next font size from commands/zoom (P3.T1), reloaded
  into the one shared GtkCssProvider (added to the display once, in
  activate!) so both views resize live -- probe 4's measured behaviour.
  TH.T4: reloads under the CURRENT theme (theme-name), not a fixed one, so
  a zoom never drops back to the default theme's colours."
  [provider zoom-px theme-name code-css direction]
  (let [px (commands/zoom @zoom-px direction)]
    (reset! zoom-px px)
    (reload-code-css! provider (theme/theme @theme-name) px code-css)))

(defn- apply-window-theme!
  "TH.T4: applies theme k to the editor, console, find bar, the shared CSS
  provider (at the current @zoom-px) and GtkSettings' colour-scheme, and
  resets theme-name to k. Only ever writes tag properties, the CSS
  provider and the GtkSettings property -- never buffer text, marks, the
  selection or undo history.

  Fix wave item 2: gtk-application-prefer-dark-theme is deprecated (GTK
  4.20+, a g_warning from 4.23.1); this sets GtkSettings' replacement,
  gtk-interface-color-scheme, instead (g/set-color-scheme!, measured
  live to darken the chrome exactly as the old boolean did -- see
  ffi.clj's own citation)."
  [ed cv fb provider zoom-px theme-name code-css k]
  (let [t (theme/theme k)]
    (editor/apply-theme! ed t)
    (cview/apply-theme! cv t)
    (find-bar/apply-theme! fb t)
    (reload-code-css! provider t @zoom-px code-css)
    (g/set-color-scheme! (g/gtk-settings-get-default) (:dark? t))
    (reset! theme-name k))
  nil)

(defn- load-and-apply-theme!
  "TH.T5: jonquil.settings/load-settings against path, applied with
  apply-window-theme! -- a missing file is silently :light (load-settings'
  own contract), a present-but-broken one still resolves to a real theme,
  with a :warning appended to the console as :system once. Used both by
  activate! at startup and as the :load-settings!* self-test hook, so a row
  can drive the exact same startup path against a synthetic path."
  [ed cv fb provider zoom-px theme-name code-css path]
  (let [{:keys [theme warning]} (settings/load-settings path)]
    (apply-window-theme! ed cv fb provider zoom-px theme-name code-css theme)
    (when warning
      (cview/append! cv :system (str "; settings: " warning "\n")))))

(defn- do-cycle-theme!
  "TH.T5: the chord/:cycle-theme!* action. Advances to the next theme
  (theme/next-theme, wrapping) and applies it, then saves {:theme k} to
  settings-path -- read at CALL time (not captured once), so a self-test
  row can repoint it via :settings-path!* right before calling this. A
  save error is reported on the console as :system, but the theme switch
  itself already happened and is kept regardless. Locked decision (the
  brief): while a program is running, the status label is left alone --
  overwriting a live Running…/Waiting for input would make the status
  lie, so only the idle case gets 'Theme: <name>'."
  [ed cv fb provider zoom-px theme-name code-css settings-path status-label evaluating?]
  (let [k (theme/next-theme @theme-name)]
    (apply-window-theme! ed cv fb provider zoom-px theme-name code-css k)
    (when-let [err (settings/save-settings! @settings-path {:theme k})]
      (cview/append! cv :system (str "; settings: " err "\n")))
    (when-not (evaluating?)
      (set-status! status-label (str "Theme: " (name k))))))

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
  handler a real close-button click or OS window-close would
  (close-request-fn): since P3.T5 that asks before closing a dirty buffer,
  and otherwise calls g_application_quit -- so this needs no prompt or quit
  call of its own.

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
  the running program instead of the IME composition.

  P3 fix wave I4: F3/Cmd+G (next match) and Shift+F3/Cmd+Shift+G
  (previous) go to find-bar/handle-nav-key! whenever the bar is open, so
  they also work with focus in the editor or the console.

  TH.T5: Cmd/Ctrl+Shift+M cycles the theme. keyval is already lower-cased
  above, so Shift+M's 0x04d becomes 0x06d the same way every other letter
  chord here is normalised; this clause doesn't consult the editor's IME
  state, same as every other window-level chord.

  P4.T3, two measured key-order changes (probe 4):
  - The F6 clause now also requires (not (shift-held? state)): plain F6
    (findings #4) toggles focus as before, but Shift+F6 no longer matches
    it, so Shift+F6 (rename, P4.T7) falls all the way through to :else and
    reaches the editor -- before this it never got there, since this
    clause had no modifier check and ran first.
  - A new clause, ahead of the evaluating? Stop clause: when
    close-popovers-fn finds any P4 popover open, Escape closes it and
    returns 1 without touching evaluating? at all, so closing a popover
    during a run never also Stops the program."
  [run-fn stop-fn open-fn save-fn save-as-fn close-fn
   run-selection-fn toggle-focus-fn clear-console-fn zoom-fn evaluating?
   win fb open-find-fn open-replace-fn theme-cycle-fn close-popovers-fn]
  (fn [_ctl keyval0 _keycode state _data]
    (let [keyval (g/gdk-keyval-to-lower keyval0)]
      (cond
        (= keyval KEY-F5) (do (run-fn) 1)
        (and (= keyval KEY-ESCAPE) (find-bar-focused? win fb))
        (find-bar/handle-key! fb keyval0 state)
        (and (= keyval KEY-ESCAPE) (close-popovers-fn)) 1
        (and (= keyval KEY-ESCAPE) (evaluating?)) (do (stop-fn) 1)
        (= keyval KEY-ESCAPE) 0
        ;; P3 fix wave I4: F3/Cmd+G and their Shift forms whenever the bar
        ;; is open, wherever focus is (1 only when the bar acted).
        (= 1 (find-bar/handle-nav-key! fb keyval0 state)) 1
        (and (= keyval KEY-F6) (not (shift-held? state))) (do (toggle-focus-fn) 1)
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
        (and (command-chord? state) (shift-held? state) (= keyval KEY-M)) (do (theme-cycle-fn) 1)
        :else 0))))

;; --- construction ------------------------------------------------------

(defn- activate! [app {:keys [file on-ready settings-path]}]
  (let [session (session/make-session)
        ;; TH.T5: an atom, not a plain value -- :settings-path!* (self-test
        ;; hook only, row-theme-save-failure-reported) repoints it at an
        ;; impossible path so a save can be forced to fail without ever
        ;; touching a real filesystem race. Defaults to the real settings
        ;; file; every harness (dev/gtk_selftest_window.clj, dev/demo_shots.clj,
        ;; dev/smoke/binary_smoke.clj's XDG_CONFIG_HOME) is responsible for
        ;; passing its own isolated :settings-path instead.
        ;;
        ;; Fix wave item 8: named settings-path* (not settings-path) so it
        ;; no longer shadows the destructured :settings-path option above
        ;; -- this let's own (or settings-path ...) init form still reads
        ;; that option correctly either way (a let binding's own init
        ;; expression sees the OUTER scope, never itself), but the shadow
        ;; made that non-obvious to a reader.
        settings-path* (atom (or settings-path (settings/settings-path)))
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
        ;; TH.T4: the active theme's name and the last CSS string loaded,
        ;; kept next to zoom-px -- TH.T5 loads theme-name's initial value
        ;; from settings. code-css is now write-only (fix wave item 7:
        ;; :code-css* reads the CSS provider itself instead) -- see
        ;; reload-code-css!'s docstring.
        theme-name (atom theme/default-theme)
        code-css (atom nil)
        ;; P4.T3: this window's own registry of P4 popovers (completion,
        ;; quick doc, rename -- P4.T4-T7 register theirs via
        ;; register-popover-fn as they're created); close-popovers-fn is
        ;; what the window's own Escape clause and :close-popovers!* call.
        popovers (atom [])
        close-popovers-fn (fn [] (close-popovers! popovers))
        register-popover-fn (fn [p] (register-popover! popovers p))
        ;; P4.T4: the quick-doc popover, built once on ed's view and
        ;; joined to the SAME registry above (Escape closes it, like every
        ;; other P4 popover); quick-doc-fn/jump-fn are the ui's own
        ;; :quick-doc!*/:jump!* and are also what editor.clj's own key
        ;; dispatch calls (set-quick-doc-fn!/set-jump-fn! below), since ed
        ;; itself is built before session/status-label/help-pop exist.
        help-pop (help-popover/make-help-popover (:view ed))
        quick-doc-fn (fn [] (do-quick-doc! ed session status-label help-pop close-popovers-fn))
        jump-fn (fn [] (do-jump! ed session status-label help-pop close-popovers-fn))
        ;; P4.T5: the completion popover, built once on ed's view and
        ;; joined to the SAME registry (Escape closes it, teardown
        ;; unparents it); close-others! is close-popovers-fn itself, so
        ;; completion's own open! closes quick doc/jump's popover before
        ;; showing (context bullet #3), through the registry, never a
        ;; pairwise special case. ns-fn is called at every lookup, not
        ;; captured once -- the running program's namespace can change
        ;; between Runs. P4.T5 fix round 1: on-error is completion's own
        ;; handle-key! reporting a throw from move!/commit!/close! to the
        ;; status bar, the same set-status! every other lookup here uses.
        completion (completion/make-completion
                    (:view ed)
                    {:ed ed
                     :ns-fn (fn [] (:prompt-ns @(:state session)))
                     :close-others! close-popovers-fn
                     :on-error (fn [msg] (set-status! status-label msg))})
        complete-fn (fn [] (do-complete! ed completion status-label))
        run-fn (fn [] (do-run! ed cv session status-label))
        stop-fn (fn [] (do-stop! session))
        open-fn (fn [] (do-open! ed cv win path discard-warned?))
        save-fn (fn [] (do-save! ed cv win path))
        save-as-fn (fn [] (do-save-as! ed cv win path))
        close-fn (fn [] (g/gtk-window-close win))
        run-selection-fn (fn [] (do-run-selection! ed cv session status-label))
        toggle-focus-fn (fn [] (do-toggle-focus! win ed cv))
        clear-console-fn (fn [] (do-clear-console! cv))
        zoom-fn (fn [direction] (do-zoom! provider zoom-px theme-name code-css direction))
        set-theme-fn (fn [k] (apply-window-theme! ed cv fb provider zoom-px theme-name code-css k))
        ;; TH.T5: the chord's/:cycle-theme!*'s action.
        cycle-theme-fn (fn [] (do-cycle-theme! ed cv fb provider zoom-px theme-name code-css
                                               settings-path* status-label
                                               (fn [] (eval/evaluating? (:ctx session)))))
        open-find-fn (fn [] (find-bar/open! fb :find))
        open-replace-fn (fn [] (find-bar/open! fb :replace))
        window-key-fn (on-window-key run-fn stop-fn open-fn save-fn save-as-fn close-fn
                                     run-selection-fn toggle-focus-fn clear-console-fn zoom-fn
                                     (fn [] (eval/evaluating? (:ctx session)))
                                     win fb open-find-fn open-replace-fn cycle-theme-fn
                                     close-popovers-fn)
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
    ;; TH.T5: the saved theme, in place of TH.T4's fixed theme/default-theme.
    (load-and-apply-theme! ed cv fb provider zoom-px theme-name code-css @settings-path*)
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
                                                         suppress-close? closing? popovers)
                                       [:pointer :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; P3 fix wave C3: without an application "quit" action, GTK's macOS
    ;; delegate lets a Quit AppleEvent (Dock -> Quit, logout, shutdown)
    ;; terminate the process at once -- measured: a dirty ./jonquil sent
    ;; one by pid exited 0 in ~250ms with no prompt. With it, the delegate
    ;; activates this action and cancels the terminate; the action closes
    ;; the window, so a dirty buffer gets the same prompt as the close
    ;; button (close-request-fn) and a clean one quits. It also takes GTK's
    ;; default Cmd+Q accelerator for app.quit on macOS.
    (let [quit-action (g/g-simple-action-new "quit" ffi/null)]
      (g/g-signal-connect-data quit-action "activate"
                               (callable (fn [_action _param _data] (g/gtk-window-close win) nil)
                                         [:pointer :pointer :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT)
      (g/g-action-map-add-action app quit-action))
    (editor/on-dirty-changed! ed (fn [_dirty] (update-title! ed win path)))
    ;; I4: a dirty->clean transition (a successful Save) re-arms the
    ;; discard warning, so an unrelated LATER dirty streak earns its own.
    (editor/on-dirty-changed! ed (fn [dirty] (when-not dirty (reset! discard-warned? false))))
    ;; P3.T5: the status bar's Ln/Col label, editor position only (probe 6).
    (editor/on-cursor-moved!
     ed (fn [[line col]] (g/gtk-label-set-text line-col-label (str "Ln " (inc line) ", Col " (inc col)))))
    ;; P4.T4: joins the SAME popover registry P4.T3 built (Escape closes
    ;; it), wires the editor's own Ctrl+Q/Shift+F1/F12/Ctrl+B key clauses
    ;; to quick-doc-fn/jump-fn, and closes the popover on any caret move
    ;; or focus leaving the editor (the brief's own close conditions,
    ;; beyond Escape, which the registry above already covers).
    (register-popover-fn help-pop)
    (editor/set-quick-doc-fn! ed quick-doc-fn)
    (editor/set-jump-fn! ed jump-fn)
    (editor/on-cursor-moved! ed (fn [_lc] (when (popover/open? help-pop) (popover/hide! help-pop))))
    (editor/on-focus-lost! ed (fn [] (when (popover/open? help-pop) (popover/hide! help-pop))))
    ;; P4.T5: joins the SAME popover registry, wires the editor's own
    ;; Ctrl+Space chord and its nav/commit/escape keys to completion/
    ;; open!/handle-key!, and refines/closes it on the SAME on-cursor-
    ;; moved! hook Ln/Col already uses (covers both a buffer change and a
    ;; plain caret move out of the word in one place -- see jonquil.gtk.
    ;; completion's own ns docstring) plus on-focus-lost!. Both callbacks
    ;; run inside try/catch, the same reviewer-clause reasoning as do-
    ;; quick-doc!/do-jump! -- a throw here would otherwise cross back into
    ;; GTK's own "notify::cursor-position"/key-pressed signal dispatch.
    (register-popover-fn completion)
    (editor/set-completion-fns!
     ed {:complete! complete-fn
         :handle-key! (fn [keyval state]
                        (try
                          (completion/handle-key! completion keyval state)
                          (catch :default e
                            (set-status! status-label (str "; complete: " (ex-message e)))
                            0)))})
    (editor/on-cursor-moved!
     ed (fn [_lc]
          (try
            (completion/refine! completion)
            (catch :default e
              (set-status! status-label (str "; complete: " (ex-message e)))))))
    (editor/on-focus-lost! ed (fn [] (completion/close! completion)))
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
                 ;; TH.T4 self-test hooks: applies a theme (never saves --
                 ;; TH.T5's job), reads back the active theme name, and
                 ;; reads back the shared CSS provider's OWN serialisation
                 ;; (fix wave item 7 -- the code-css atom was a mirror the
                 ;; rows could drift from; g/css-provider-string asks the
                 ;; provider itself instead).
                 :set-theme!* set-theme-fn
                 :theme-name* (fn [] @theme-name)
                 :code-css* (fn [] (g/css-provider-string provider))
                 ;; TH.T5: the chord's own action, the settings path this
                 ;; window reads/writes, and the exact startup-load fn
                 ;; activate! itself just ran (so a row can drive the same
                 ;; path against a synthetic settings file).
                 :cycle-theme!* cycle-theme-fn
                 :settings-path* (fn [] @settings-path*)
                 :load-settings!* (fn [p] (load-and-apply-theme! ed cv fb provider zoom-px theme-name code-css p))
                 ;; TH.T5 self-test hook only (row-theme-save-failure-
                 ;; reported): repoints the window's own settings path so a
                 ;; save can be forced to fail without touching the real
                 ;; settings file or racing the filesystem.
                 :settings-path!* (fn [p] (reset! settings-path* p))
                 ;; P4.T3: the window's own popover registry -- close-
                 ;; popovers-fn is exactly what the new Escape clause calls;
                 ;; register-popover-fn is how a popover (a self-test row's
                 ;; own test popover today, completion/quick-doc/rename from
                 ;; P4.T4-T7 on) joins that registry.
                 :close-popovers!* close-popovers-fn
                 :register-popover!* register-popover-fn
                 ;; P4.T4: the ui's own quick-doc/jump actions -- the exact
                 ;; fns editor.clj's own Ctrl+Q/Shift+F1/F12/Ctrl+B clauses
                 ;; call (set-quick-doc-fn!/set-jump-fn!, above) -- and the
                 ;; quick-doc popover itself, for rows to read back via
                 ;; jonquil.gtk.popover/open? and help-popover/quick-doc-text.
                 :quick-doc!* quick-doc-fn
                 :jump!* jump-fn
                 :help-popover help-pop
                 ;; P4.T5: the ui's own completion action -- the exact fn
                 ;; editor.clj's own Ctrl+Space clause calls -- and the
                 ;; completion popover itself, for rows to read back via
                 ;; jonquil.gtk.popover/open? and jonquil.gtk.completion/
                 ;; state.
                 :complete!* complete-fn
                 :completion completion
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
                 ;; P3 fix wave C3: activates the application's "quit"
                 ;; action exactly as GTK's macOS delegate does on a Quit
                 ;; AppleEvent.
                 :activate-quit-action!* (fn [] (g/g-action-group-activate-action app "quit" ffi/null))
                 :request-close!* (fn [] (g/gtk-window-close win))
                 :closing? closing?
                 :suppress-close? suppress-close?
                 ;; Fix round 1 self-test hooks only (Important #1): drive
                 ;; complete-alert!'s per-dialog routing directly, with
                 ;; synthetic, non-GTK "dialog" identities -- see the report.
                 :alert-dialog-count alert-dialog-count
                 :alert-pending? alert-pending?
                 :alert-release! alert-release!
                 :register-alert-choice-for-test! register-alert-choice!
                 :complete-alert-for-test! complete-alert!}))))

(defn start!
  "Builds the Jonquil window and blocks in the GTK main loop. opts is
  {:file path-or-nil :on-ready (fn [ui]) :settings-path path-or-nil}, where
  :settings-path defaults to (jonquil.settings/settings-path) -- every
  self-test/docs-shots/smoke harness must pass its own isolated path so it
  never touches the user's real settings file (TH.T5). ui is {:editor
  :console :session :status-label :line-col-label :window :find-bar :run!*
  :stop!* :open-path! :save-to! :run-selection!* :toggle-focus!*
  :clear-console!* :zoom!* :set-theme!* :theme-name* :code-css*
  :cycle-theme!* :settings-path* :load-settings!* :settings-path!*
  :close-popovers!* :register-popover!* :quick-doc!* :jump!* :help-popover
  :complete!* :completion
  :handle-window-key! :simulate-open-result! :simulate-save-result!
  :close-choice! :activate-quit-action!* :request-close!* :closing? :suppress-close?
  :alert-dialog-count :alert-pending? :alert-release!
  :register-alert-choice-for-test! :complete-alert-for-test!}; the
  self-test rows call these functions directly, exactly as the buttons and
  keys do. :find-bar is the jonquil.gtk.find-bar map (P3.T4); rows call
  jonquil.gtk.find-bar's own functions on it. :cycle-theme!* is what
  Cmd/Ctrl+Shift+M runs; :settings-path* reads back the window's own
  settings path; :load-settings!* re-runs the startup load against any
  path; :settings-path!* is self-test-only (row-theme-save-failure-
  reported), repointing the window's own settings path. :close-popovers!*
  is what the window's own Escape clause calls (P4.T3); :register-popover!*
  joins a jonquil.gtk.popover map to this window's registry so
  :close-popovers!* knows about it -- P4.T4-T7's completion/quick-doc/
  rename popovers register theirs the same way a self-test row's own test
  popover does. :quick-doc!*/:jump!* (P4.T4) are exactly what the editor's
  own Ctrl+Q/Shift+F1/F12/Ctrl+B key clauses call; :help-popover is the
  jonquil.gtk.help-popover map those two actions show doc text in --
  jonquil.gtk.popover/open? and help-popover/quick-doc-text read it back.
  :complete!* (P4.T5) is exactly what the editor's own Ctrl+Space clause
  calls; :completion is the jonquil.gtk.completion map it acts on --
  jonquil.gtk.popover/open? and jonquil.gtk.completion/state read it
  back."
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
