(ns jonquil.gtk.window
  "The Jonquil window: a toolbar (Run/Stop/Open/Save/Reset-on-Run) over a
  vertical GtkPaned holding the Definitions editor (P2.T5) above the
  Interactions console (P2.T6), over a status label. Wires the window's
  jonquil.evaluator (jonquil.session's in-process record by default) to the
  two views: Run and the REPL both go through it.

  Lock caller rule (jonquil.eval's docstring): on-output,
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
            [jonquil.cljd :as cljd]
            [jonquil.cljs :as cljs]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.commands :as commands]
            [jonquil.console :as console]
            [jonquil.dialect :as dialect]
            [jonquil.docs :as docs]
            [jonquil.edit :as edit]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.file-ops :as ops]
            [jonquil.file-tree :as ft]
            [jonquil.gtk.appearance :as appearance]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.file-tree-pane :as file-tree-pane]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.gtk.tab :as tab]
            [jonquil.gtk.trash :as trash]
            [jonquil.help :as help]
            [jonquil.inspect :as inspect]
            [jonquil.last-session :as last-session]
            [jonquil.login-path :as login-path]
            [jonquil.mcp.window :as mcp-window]
            [jonquil.nrepl.attach]
            [jonquil.nrepl.cljs-node]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.evaluator :as nrepl]
            [jonquil.nrepl.jank]
            [jonquil.nrepl.jvm]
            [jonquil.nrepl.shadow]
            [jonquil.palette :as pal-data]
            [jonquil.plugin-hooks :as plugin-hooks]
            [jonquil.plugin-loader :as plugin-loader]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.runtime-registry :as registry]
            [jonquil.save-path :as save-path]
            [jonquil.session]
            [jonquil.settings :as settings]
            [jonquil.source :as source]
            [jonquil.tabs :as tabs]
            [jonquil.theme :as theme]))

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

;; --- dialect and evaluator (D1.T4) ------------------------------------------

(defn- select-for
  "jonquil.dialect/select for path (nil for an unsaved window), with the
  settings file's :dialect as the fallback. Never throws: a failure while
  reading the project becomes a warning on the fallback selection."
  [path settings-path]
  (try
    (dialect/select dialect/profiles
                    (dialect/find-project dialect/profiles path)
                    path
                    (:dialect (settings/load-settings settings-path)))
    (catch :default e
      (update (dialect/select dialect/profiles nil nil nil) :warnings conj
              (str "couldn't read the project: " (ex-message e))))))

(defn- show-selection!
  "Puts sel's label in the status bar and each warning on the console.
  GTK thread only (the label); cview/append! is safe from any thread."
  [cv dialect-label sel]
  (g/gtk-label-set-text dialect-label (dialect/label sel))
  (doseq [w (:warnings sel)]
    (cview/append! cv :system (str "; project: " w "\n"))))

;; --- the ClojureDart attach evaluator's window state (cljd plan P2.T4) -----

(defn- dialect-text
  "The status bar's dialect label for selection sel run by evaluator e: an
  attach evaluator's label says whether it holds a live connection."
  [sel e]
  (dialect/label sel (when (satisfies? connection/Attached e)
                       (if (connection/connected? e) :attached :not-running))))

(defn- refresh-prompt!
  "Makes the console's prompt read the window's evaluator's own namespace
  (`user=> `, `demo.core=> `). Safe from any thread: the rewrite goes
  through on-gui!, and a throw from prompt-ns or set-prompt! lands on the
  console instead of reaching GTK."
  [cv ev*]
  (on-gui! (fn []
             (try (cview/set-prompt! cv (str (or (ev/prompt-ns @ev*) 'user)))
                  (catch :default e
                    (cview/append! cv :error (str "; prompt: " (ex-message e) "\n")))))))

(defn- watch-connection!
  "For an attach evaluator e, keeps the dialect label and the console's
  prompt in step with its connection (a new connection is a fresh session,
  back in its default namespace). The listener runs on whatever thread
  connected or lost the socket, so it hands both to the GTK thread, and it
  does nothing once e is no longer the window's evaluator (a later switch
  made another)."
  [e ev* sel* dialect-label cv]
  (when (satisfies? connection/Attached e)
    (connection/on-connection! e (fn [_]
                                   (on-gui! (fn []
                                              (when (identical? e @ev*)
                                                (g/gtk-label-set-text dialect-label (dialect-text @sel* e))
                                                (refresh-prompt! cv ev*))))))))

(defn- apply-reset-toggle!
  "Reset on Run as dialect/reset-toggle decides for sel: unticked and
  insensitive, with the profile's own :reset-tooltip, for an attach
  runtime, and the person's own tick back, with no tooltip, for every
  other runtime. reset-state is the window's atom holding the tick an
  attach runtime took down. Setting the tick fires the box's toggled
  handler, which only calls set-mode! on the current evaluator: a no-op
  on an attach one, and harmless on the evaluator a switch is about to
  replace. GTK thread only."
  [reset-chk reset-state sel]
  (let [{:keys [state active? sensitive? tooltip]}
        (dialect/reset-toggle @reset-state sel (not (zero? (g/gtk-check-button-get-active reset-chk))))]
    (reset! reset-state state)
    (g/gtk-check-button-set-active reset-chk (if active? 1 0))
    (g/gtk-widget-set-sensitive reset-chk (if sensitive? 1 0))
    (g/gtk-widget-set-tooltip-text reset-chk tooltip)))

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

(defn- make-file-dialog-cb
  "The completion callback one dialog mode shares across every window.
  on-result is the window's own and contains its own throws (the callers
  of file-dialog! below report them on their console); anything else that
  throws here still unrefs the dialog and stops at callable's backstop."
  [finish on-result-by-dialog]
  (callable (fn [src res _data]
              (when-let [on-result (get @on-result-by-dialog src)]
                (swap! on-result-by-dialog dissoc src)
                (try (on-result (g/finish-file finish src res))
                     (finally (g/g-object-unref src))))
              nil)
            [:pointer :pointer :pointer] :void))

(defonce ^:private open-cb (make-file-dialog-cb g/gtk-file-dialog-open-finish open-on-result-by-dialog))
(defonce ^:private save-cb (make-file-dialog-cb g/gtk-file-dialog-save-finish save-on-result-by-dialog))
;; File-tree plan P2.T2: Open Folder..., same per-dialog routing.
(defonce ^:private folder-on-result-by-dialog (atom {}))
(defonce ^:private folder-cb (make-file-dialog-cb g/gtk-file-dialog-select-folder-finish folder-on-result-by-dialog))

(defn- file-dialog!
  "mode is :open, :save or :folder (Open Folder..., file-tree plan P2.T2).
  on-result gets {:path s} or {:error msg :code n}. initial-folder, when
  given, is where the dialog starts."
  ([mode window on-result] (file-dialog! mode window on-result nil))
  ([mode window on-result initial-folder]
   (let [d (g/gtk-file-dialog-new)
         [on-result-by-dialog cb start]
         (case mode
           :open [open-on-result-by-dialog open-cb g/gtk-file-dialog-open]
           :save [save-on-result-by-dialog save-cb g/gtk-file-dialog-save]
           :folder [folder-on-result-by-dialog folder-cb g/gtk-file-dialog-select-folder])]
     (when initial-folder
       (let [gf (g/g-file-new-for-path initial-folder)]
         (g/gtk-file-dialog-set-initial-folder d gf)
         ;; The dialog keeps its own reference (final review nit: one GFile
         ;; leaked per Open Folder...).
         (g/g-object-unref gf)))
     (swap! on-result-by-dialog assoc d on-result)
     (start d window ffi/null cb ffi/null))))

;; --- open / save: the no-dialog forms the rows (and the dialog callbacks
;; below) call -------------------------------------------------------------

(defn- open-path!
  "Loads path into the editor: slurp, set-text!, remember the path. An
  error is appended as :error, never swallowed, and returned as {:error
  msg} too (nil on success) -- source.clj's own read-text/load! shape --
  so a caller that doesn't hold a console in view (do-source-open!) can
  still tell whether the open actually landed. Doesn't touch the title --
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
                       "This file isn't valid UTF-8; unreadable bytes show as � and saving will replace them.\n"))
      nil)
    (catch :default e
      (cview/append! cv :error (str "; open: " (ex-message e) "\n"))
      {:error (ex-message e)})))

(defn- temp-path-for
  "A hidden temp file in the same directory as path (M12): the write-then-
  rename below needs both on the same filesystem for the rename to be
  possible at all, atomic or not."
  [path]
  (let [i (str/last-index-of path "/")
        dir (if i (subs path 0 (inc i)) "")
        name (if i (subs path (inc i)) path)]
    (str dir "." name ".jonquil-tmp-" (System/nanoTime))))

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

(defonce ^:private on-saved
  ;; plugins P4: (fn [path ed]) run after a save succeeds; the window sets
  ;; it to reload a saved plugin file and, P6, to fire :after-save hooks
  (atom nil))

(defn- save-to!
  "Writes the editor text to path without ever truncating it in place
  (M12), and without breaking a symlink or dropping the target's
  permissions (M12 follow-up): resolves path to its real underlying file
  (save-path/resolve-target, which follows links by hand because
  .getCanonicalPath does not follow a dangling one on Jolt), creates the
  temp file in THAT file's directory with mode rw------- when the target
  exists (save-path/create-temp!, so the new content is never in a wider
  file), spits into it, copies the target's POSIX permissions onto it
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
  isn't -- and the temp file is cleaned up on any failure (a failure to
  resolve path, such as a symlink loop, is reported the same way, before
  any temp file exists); the rename
  never runs at all when the write itself fails, so the target is never
  touched. Doesn't touch the title; see open-path!'s docstring."
  [ed cv path-atom path]
  (let [target (try (save-path/resolve-target path)
                    (catch :default e
                      (cview/append! cv :error (str "; save: " (ex-message e) "\n"))
                      nil))]
    (when target
      (let [tmp (temp-path-for target)]
        (try
          (save-path/create-temp! tmp target)
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
          (when-let [f @on-saved]
            (try (f path ed)
                 (catch :default e
                   (cview/append! cv :error (str "; reloading the saved plugin failed: " (ex-message e) "\n")))))
          (catch :default e
            (let [f (java.io.File. ^String tmp)]
              (when (.exists f) (.delete f)))
            (cview/append! cv :error
                           (str "; save: " (name-user-path (ex-message e) tmp target path) "\n"))))))))

(defn- guarded-open!
  "I4's dirty-discard gate for loading a file into the active tab: when ed
  is dirty and the previous attempt hasn't been warned about it yet,
  refuses and appends a :system warning instead of calling proceed!. The
  next attempt while STILL dirty (discard-warned? already true) calls
  proceed! and clears the flag; a dirty->clean transition elsewhere (a
  successful Save) also clears it, via wire-tab!'s on-dirty-changed!
  registration, so a later, unrelated dirty streak earns its own warning.
  Since tabs (P2.T3) Open and Open in window open a tab instead of
  replacing a buffer, so the ui's :open-path! is this gate's only caller
  (ruling 4)."
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
  [open! cv {:keys [path error] :as result}]
  (cond
    path (open! path)
    ;; M2: a cancelled/dismissed dialog is not an error.
    (dialog-dismissed? result) nil
    :else (cview/append! cv :error (str "; open: " error "\n"))))

(defn- contained-open-result!
  "handle-open-result!, with a throw reported on the console as \"; open:
  ...\" instead of reaching the shared dialog callback. The dialog's
  on-result, and the self-test's :simulate-open-result!. ctx is {:ed :cv
  :open!}: open! (fn [path]) is how a chosen file opens -- the real Open
  goes through open-file! (tabs plan P2.T3); the ui's simulated result
  loads into the active tab, as :open-path! does (ruling 4)."
  [{:keys [cv open!]} result]
  (try (handle-open-result! open! cv result)
       (catch :default e
         (cview/append! cv :error (str "; open: " (ex-message e) "\n")))))

(defn- do-open!
  "Open: the file dialog, then open! with the chosen path. Since tabs
  (P2.T3) Open never replaces a buffer with unsaved changes -- it opens a
  tab or fills an untouched Untitled one -- so there's no discard gate."
  [cv win open!]
  (file-dialog! :open win (fn [result] (contained-open-result! {:cv cv :open! open!} result))))

(defn- handle-save-result!
  "handle-open-result!'s counterpart for file-dialog! :save."
  [ed cv win path-atom {:keys [path error] :as result}]
  (cond
    path (do (save-to! ed cv path-atom path)
             (update-title! ed win path-atom))
    (dialog-dismissed? result) nil
    :else (cview/append! cv :error (str "; save: " error "\n"))))

(defn- contained-save-result!
  "contained-open-result!'s counterpart for :save."
  [{:keys [ed cv win path-atom]} result]
  (try (handle-save-result! ed cv win path-atom result)
       (catch :default e
         (cview/append! cv :error (str "; save: " (ex-message e) "\n")))))

(defn- do-save-as! [ed cv win path-atom]
  (file-dialog! :save win (fn [result] (contained-save-result! {:ed ed :cv cv :win win :path-atom path-atom} result))))

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
  GtkAlertDialog (show-alert!, below), or any comparable value in
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

(defn- show-alert!
  "Builds a fresh GtkAlertDialog (probe 3: g_object_new_with_properties,
  0 properties -- gtk_alert_dialog_new is variadic and is never bound) for
  THIS attempt (the message needs the current file name, which can differ
  between attempts after a Save As), but reuses the ONE retained
  alert-choose-cb across every attempt. cancel and default are button
  indexes: the close alerts pass 2 and 0, the tree's trash alert 1 and 1."
  [win {:keys [message buttons]} {:keys [cancel default]} on-choice]
  (let [d (g/g-object-new-with-properties (g/gtk-alert-dialog-get-type) 0 ffi/null ffi/null)]
    (swap! alert-dialog-count inc)
    (register-alert-choice! d on-choice)
    (g/gtk-alert-dialog-set-message d message)
    (ffi/with-c-string-array [labels 4] (conj (vec buttons) nil)
      (g/gtk-alert-dialog-set-buttons d labels))
    (g/gtk-alert-dialog-set-cancel-button d cancel)
    (g/gtk-alert-dialog-set-default-button d default)
    (g/gtk-alert-dialog-choose d win ffi/null alert-choose-cb ffi/null)))

(def ^:private CLOSE-ALERT {:cancel 2 :default 0})
(def ^:private TRASH-ALERT {:cancel 1 :default 1})

(defn- finish-close!
  "Confirms and re-triggers the window's own close-request (probe 3):
  gtk_window_close synchronously re-enters close-request-fn (below), which
  this time (confirmed-close? true) either quits for real or, when
  suppress-close? is set, only records that it WOULD have -- see
  close-request-fn's docstring for why."
  [win confirmed-close?]
  (reset! confirmed-close? true)
  (g/gtk-window-close win))

(defn- save-all!
  "Save All (tabs plan P2.T5): saves each tab in dirty-tabs in order -- a
  tab with a path through save-to!, an Untitled one through the Save
  dialog, continuing from its callback. The first tab still dirty after
  its save (the save failed) or whose dialog is dismissed stops the
  sequence and is selected, and the window stays open. All saved closes
  the window. ctx is {:cv :win :confirmed-close? :select!}."
  [{:keys [cv win confirmed-close? select!] :as ctx} dirty-tabs]
  (if-let [[{:keys [ed path] :as t} & more] (seq dirty-tabs)]
    (let [next! (fn [] (if (editor/dirty? ed) (select! t) (save-all! ctx more)))]
      (if-let [p @path]
        (do (save-to! ed cv path p) (next!))
        (do (select! t)
            (file-dialog!
             :save win
             (fn [{p :path err :error :as r}]
               (cond
                 p (do (save-to! ed cv path p) (next!))
                 (dialog-dismissed? r) nil
                 :else (cview/append! cv :error (str "; save: " err "\n"))))))))
    (finish-close! win confirmed-close?)))

(defn- close-choice!
  "The window-close alert's decision (probe 3; the brief's :close-choice!):
  index 0 Save (Save All with several dirty tabs), 1 Don't Save, 2 Cancel;
  an :error means the dialog was dismissed. Index 2 and an error both do
  nothing -- the window stays open and the text untouched. Index 0 runs
  save-all! over the tabs that were dirty, which closes the window only
  once every one of them saved. Rows call this directly with a synthetic
  {:index n} (probe 3), exactly as the real dialog's own completion
  callback does. ctx is save-all!'s plus :dirty-tabs."
  [{:keys [dirty-tabs win confirmed-close?] :as ctx} {:keys [index error]}]
  (cond
    error nil
    (= index 2) nil
    (= index 1) (finish-close! win confirmed-close?)
    (= index 0) (save-all! ctx dirty-tabs)
    :else nil))

;; --- popover teardown --------------------------------------------------

(defn- unparent-registered-popovers!
  "Unparents every popover in popovers (a jonquil.gtk.popover map) whose
  GtkWidget parent isn't already null -- gtk_widget_unparent asserts a
  non-null parent (a critical warning, not a crash, but pointless noise),
  so an already-unparented entry (a self-test row's own throwaway popover
  that unparents itself right after hide!, e.g.
  row-window-escape-closes-popover) is skipped rather than warned at.

  Hides an OPEN popover FIRST (same idiom close-popovers! uses), before
  unparenting it. Without this, an open popover's own :open? atom stays
  true after its widget is unparented (and, for a floating-ref GtkWidget
  with no other reference, finalized right there); window teardown itself
  then clears the editor's focus, GtkEventControllerFocus's \"leave\"
  fires this window's own hide-on-focus-lost listener, (popover/open? p)
  still reads true, and hide! calls gtk_popover_popdown on the now-freed
  widget -- measured live: \"Gtk-CRITICAL: gtk_popover_popdown: assertion
  'GTK_IS_POPOVER (popover)' failed\". Resetting :open? here, before
  unparenting, means every later teardown-triggered listener's own
  (popover/open? p) check already reads false and skips calling hide!
  again -- the same reasoning that makes this function idempotent against
  an already-unparented entry above.

  Called from close-request-fn's own REAL-quit branch only (below), right
  before g_application_quit and the return 0 that lets GTK actually start
  tearing the window down (close-request-fn's own docstring: \"once it
  returns 0, GTK proceeds to actually tear the window down\"). A session-
  lifetime popover (jonquil.gtk.popover's hide! never unparents -- see its
  ns docstring, an intentional design) is still parented to its scrolled-
  window parent at that point; without this, GTK's own dispose finds it
  still there when that widget finalizes and reports \"Finalizing
  GtkScrolledWindow ..., but it still has children left: GtkPopover ...\"
  (measured live). Never called from the self-test's suppress-close?
  branch -- that path returns 1 and never actually closes anything, so
  unparenting there would silently break every later row's use of the
  SAME session-lifetime popover."
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
  again instead of a confusing second window.

  D1.T4: ev* is stopped (ev/stop!, guarded) right before g_application_
  quit, in the real-quit branch only -- never in the suppress-close?
  self-test branch, which doesn't actually quit anything.

  Session-tabs plan Task 2: save-session! (the open tabs, for the next
  launch) runs in the real-quit branch only, beside save-tree-width!."
  [{:keys [tabs cv win app confirmed-close? suppress-close? closing? popovers ev* select! save-tree-width!
           save-session!]}]
  (fn [_win _data]
    ;; Tabs plan P2.T5: one alert naming every dirty tab, in tab order.
    (let [ts @tabs
          names (tabs/labels (map (fn [t] {:path @(:path t)}) ts))
          dirty (filterv (fn [t] (editor/dirty? (:ed t))) ts)
          dirty-names (keep-indexed (fn [i t] (when (editor/dirty? (:ed t)) (nth names i))) ts)]
      (if (and (seq dirty) (not @confirmed-close?))
        (do (when-not @alert-pending?
              (reset! alert-pending? true)
            ;; P3 fix wave #10: a throw while building the alert used to
            ;; leave alert-pending? true for the rest of the session (every
            ;; later close silently did nothing) and escape into GTK's
            ;; signal emission. Reset the flag and report it instead; the
            ;; window stays open, since the text is still unsaved.
              (try
                (show-alert!
                 win (tabs/close-alert dirty-names) CLOSE-ALERT
                 (fn [result] (close-choice! {:dirty-tabs dirty :cv cv :win win :confirmed-close? confirmed-close?
                                              :select! select!}
                                             result)))
                (catch :default e
                  (reset! alert-pending? false)
                  (cview/append! cv :error (str "; close: " (ex-message e) "\n")))))
            1)
        (do (reset! closing? true)
            (if @suppress-close?
              (do (reset! confirmed-close? false) 1)
            ;; Unparents every registered P4 popover BEFORE the real quit
            ;; -- see unparent-registered-popovers!'s own docstring for
            ;; why this branch specifically, never the suppress-close?
            ;; one above.
              (do (unparent-registered-popovers! popovers)
                  ;; File-tree plan P2.T2: the tree's width, when it's showing.
                  (when save-tree-width!
                    (try (when-let [err (save-tree-width!)]
                           (binding [*out* *err*]
                             (println "jonquil: saving the file tree's width failed:" err)))
                         (catch :default e
                           (binding [*out* *err*]
                             (println "jonquil: saving the file tree's width failed:" (ex-message e))))))
                  (when save-session!
                    (try (when-let [err (save-session!)]
                           (binding [*out* *err*]
                             (println "jonquil: saving the session failed:" err)))
                         (catch :default e
                           (binding [*out* *err*]
                             (println "jonquil: saving the session failed:" (ex-message e))))))
                  (try (ev/stop! @ev*)
                       (catch :default e
                       ;; The window is going away, so the console can't show
                       ;; it; the process's real stderr can.
                         (binding [*out* *err*]
                           (println "jonquil: stopping the runtime failed:" (ex-message e))))
                     ;; Leaves jonquil.runtime-registry: this evaluator is
                     ;; stopped (or its stop! call at least ran), so
                     ;; jonquil.main's stop-all! has nothing left to do for
                     ;; it once the whole app quits.
                       (finally (registry/unregister! @ev*)))
                  (g/g-application-quit app)
                  0)))))))

;; --- Run / Stop ------------------------------------------------------------

(defn- set-status!
  "Safe from any thread: hands the label update to the GTK thread."
  [status-label text]
  (on-gui! (fn [] (g/gtk-label-set-text status-label text))))

(defn- contain-click
  "A clicked/toggled handler (two pointers in, nothing out) that runs
  (f button) and puts a throw on the status label as \"; where: message\",
  the way the editor's and console's own handlers report theirs."
  [status-label where f]
  (fn [button _data]
    (try (f button)
         (catch :default e
           (set-status! status-label (str "; " where ": " (ex-message e)))))
    nil))

;; I5 fix wave item 3, cosmetic half: the start report's project path is
;; usually somewhere under the user's home directory, and printing it in
;; full was one more way a line could run wide enough to wrap. Shortening
;; it to a ~-relative form doesn't fix the wrap bug (the wrap-mode change
;; above does that for every line, not just this one) but keeps this
;; common case tidy.
(defn- home-relative
  "path with a leading $HOME replaced by ~, or path unchanged when it isn't
  under $HOME (or $HOME can't be read)."
  [path]
  (let [home (System/getProperty "user.home")]
    (if (and home (str/starts-with? path (str home "/")))
      (str "~" (subs path (count home)))
      path)))

;; start-and-report! sits here, after set-status! (just above) and before
;; apply-selection! (below), which calls it: Jolt resolves an unqualified
;; symbol at compile time, not lazily, so a forward reference to either fn
;; from earlier in the file fails bb check with "Unable to resolve symbol".
(defn- start-and-report!
  "Starts an out-of-process evaluator on a future and says so on the
  console: pid, port and project, then each limitation, or why it couldn't
  start. The in-process engine has nothing to start or report."
  [cv status-label e sel]
  (cond
    (instance? jonquil.nrepl.evaluator.NRepl e)
    (do (set-status! status-label (str "Starting " (dialect/label sel) "…"))
        (future
          (try
            (if-let [problem (ev/start! e)]
              (cview/append! cv :error (str "; " problem "\n; Run will try again.\n"))
              (do (cview/append! cv :system
                                 (str "; " (dialect/label sel) " is running (pid " (nrepl/child-pid e)
                                      ", port " (nrepl/child-port e) ") in " (home-relative (:project-dir sel)) ".\n"))
                  (doseq [l (ev/limitations e)]
                    (cview/append! cv :system (str "; Limit: " l "\n")))))
            (catch :default ex
              (cview/append! cv :error (str "; start: " (ex-message ex) "\n")))
            (finally
              (when-not (ev/evaluating? e) (set-status! status-label "Ready"))))))

    ;; cljd plan P2.T4: an attach evaluator connects on the first Run, so
    ;; there's nothing to start; say it's experimental and what it can't do.
    ;; clj plan P2.T4: satisfies? connection/Attached, not an instance? on
    ;; any one dialect's record, so a :clj window prints its own limits too
    ;; -- the experimental line stays gated on the profile's own flag,
    ;; which cljd and jank set.
    (satisfies? connection/Attached e)
    (do (when (:experimental (:profile sel))
          (cview/append! cv :system (str (:experimental-message (:profile sel)) "\n")))
        (when (satisfies? ev/StartNote e)
          (cview/append! cv :system (str "; " (ev/start-note e) "\n")))
        (doseq [l (ev/limitations e)]
          (cview/append! cv :system (str "; Limit: " l "\n"))))))

(def ^:private PANEL-WIDTH
  ;; estimate: the brief's own number, matched against jonquil.gtk.
  ;; inspector-pane/WIDTH-REQUEST (360) plus room for the paned's handle.
  420)

;; --- the right-hand slot: the inspector and the source panel -------------
;; One slot, last wins (source-panel spec, decision 3): a vertical GtkBox
;; holds both panels' roots, only one visible at a time, and the box itself
;; is hidden when neither is. Each root keeps its own visibility, so "is the
;; inspector showing?" is still (:root pane)'s own visible flag. These sit
;; above apply-selection!, which hides the slot on a runtime switch.

(def ^:private STATUS-MARGIN
  ;; px at each end of the status bar: GNOME's usual 6-12 px gutter, the
  ;; status box's own child spacing (6) plus a little.
  8)

(defn- show-right-panel!
  "Shows which (:inspector or :source) in the slot and hides the other.
  When the slot was hidden, also sizes h-paned so the panel claims about
  PANEL-WIDTH, as the inspector always did; while it's showing, the split
  stays where the person put it. right is {:win :h-paned :slot
  :inspector-root :source-root}. GTK-thread only."
  [{:keys [win h-paned slot inspector-root source-root]} which]
  (let [was-hidden? (zero? (g/gtk-widget-get-visible slot))
        [show hide] (if (= which :inspector) [inspector-root source-root] [source-root inspector-root])]
    (g/gtk-widget-set-visible hide 0)
    (g/gtk-widget-set-visible show 1)
    (g/gtk-widget-set-visible slot 1)
    (when was-hidden?
      (g/gtk-paned-set-position h-paned (max 0 (- (g/gtk-widget-get-width win) PANEL-WIDTH))))))

(defn- focus-within?
  "True when keyboard focus is widget or inside it."
  [widget]
  (let [root (g/gtk-widget-get-root widget)]
    (when-not (ffi/null? root)
      (let [f (g/gtk-root-get-focus root)]
        (and (not (ffi/null? f))
             (or (= f widget) (pos? (g/gtk-widget-is-ancestor f widget))))))))

(defn- hide-right-panel!
  "Hides which's root, and the slot once neither root is showing. Hiding
  :source also cancels any re-scroll settling still in flight for it
  (source-pane/cancel-scroll-settle!, final review finding #1), so the
  shared settle timeout stops touching the view behind a hidden panel
  instead of only finding out once the view is actually destroyed.
  close-source-panel! reaches this same cancellation by calling through
  here, not by calling cancel-scroll-settle! a second time."
  [{:keys [slot inspector-root source-root source-pane]} which]
  (let [[root other] (if (= which :inspector) [inspector-root source-root] [source-root inspector-root])]
    (g/gtk-widget-set-visible root 0)
    (when (= which :source)
      (source-pane/cancel-scroll-settle! (:scroll-cancelled? source-pane)))
    (when (zero? (g/gtk-widget-get-visible other))
      (g/gtk-widget-set-visible slot 0))))

;; apply-selection! sits here, after start-and-report! (above), which it
;; calls: Jolt resolves an unqualified symbol at compile time, not lazily,
;; so it can't sit any earlier in the file, before start-and-report! exists.
(defn- apply-selection!
  "Makes sel the window's selection. When it needs a different evaluator
  (jonquil.dialect/selection-key), the old one is interrupted and stopped
  off the GTK thread and a new one starts, and the inspector panel (if
  showing) hides and drops its own state (minor: the panel's old view
  would otherwise call ev/inspect! on an evaluator that may not even
  implement it). sel-ctx is {:cv :status-label :dialect-label :sel* :ev*
  :mode-fn :reset-chk :reset-state :right :inspector-state}.

  The new evaluator joins jonquil.runtime-registry as soon as it's made
  (so jonquil.main's stop-all! can never miss it, even if the window
  closes before it ever starts); the old one leaves once it's stopped.

  interrupt! and stop! on the old evaluator are guarded separately, so a
  throw from one still runs the other: each step's own throw is reported
  on the console rather than skipping the step after it and orphaning the
  old child.

  cljd plan P2.T4: an attach evaluator's connection drives the label
  (watch-connection!), Reset on Run follows the runtime
  (apply-reset-toggle!), and the label is set last, from the evaluator the
  window ends up with, so opening another file of the same app keeps
  reading \"attached\"."
  [{:keys [cv status-label dialect-label sel* ev* mode-fn reset-chk reset-state right inspector-state active-ed]} sel]
  (let [old-sel @sel*]
    (reset! sel* sel)
    (show-selection! cv dialect-label sel)
    (apply-reset-toggle! reset-chk reset-state sel)
    (when (not= (dialect/selection-key old-sel) (dialect/selection-key sel))
      (let [old @ev*
            new (doto (ev/make sel) registry/register!)]
        (ev/set-mode! new (mode-fn))
        (watch-connection! new ev* sel* dialect-label cv)
        (reset! ev* new)
        ;; Focus inside a panel this hides would go nowhere, so it moves to
        ;; the active tab's editor, as closing the inspector does.
        (let [focus-in-panel? (or (focus-within? (:inspector-root right))
                                  (focus-within? (:source-root right)))]
          (hide-right-panel! right :inspector)
          (hide-right-panel! right :source)
          (when (and focus-in-panel? active-ed)
            (when-let [ed (active-ed)] (editor/focus! ed))))
        (reset! inspector-state nil)
        (cview/append! cv :system (str "; Switched to " (dialect/label sel)
                                       ". The previous session is gone.\n"))
        (future
          (try (when (ev/evaluating? old) (ev/interrupt! old))
               (catch :default ex
                 (cview/append! cv :error (str "; switch: " (ex-message ex) "\n"))))
          (try (ev/stop! old)
               (catch :default ex
                 (cview/append! cv :error (str "; switch: " (ex-message ex) "\n")))
               (finally (registry/unregister! old))))
        (start-and-report! cv status-label new sel)))
    (g/gtk-label-set-text dialect-label (dialect-text sel @ev*))
    (refresh-prompt! cv ev*)))

(defn- make-eval-opts
  "The opts run-definitions!/submit-repl! share (the lock-caller rule:
  every one of these runs on the eval worker thread and must only
  enqueue).

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
  pane currently holds, and must not name it as the culprit.

  :on-complete also refreshes the console's prompt from ev*, since the
  evaluation may have moved the session's namespace; every evaluator
  updates its prompt-ns before it calls on-complete."
  [ed cv ev* status-label is-run?]
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
                  (on-gui! (fn [] (cview/note-run-end! cv)))
                  (refresh-prompt! cv ev*)
                  (let [[kind text] (console/result-line result)]
                    (cview/append! cv kind text))
                  (when (and is-run?
                             (= :error (:status result))
                             (str/includes? (console/error-text result) "EOF while reading"))
                    ;; editor/editor-text calls GTK text-buffer functions, which
                    ;; is a real GTK-thread requirement, not just this project's
                    ;; own lock -- on-complete runs on the eval worker thread, so
                    ;; the read (and the append! that depends on it) is handed to
                    ;; on-gui! rather than called here directly.
                    (on-gui! (fn []
                                                              ;; Final review finding 3b: the Run's tab may have
                               ;; closed while it ran; its buffer is gone then.
                               (when-let [msg (when-not @(:destroyed? ed)
                                                (console/unclosed-bracket-message (editor/editor-text ed)))]
                                 (cview/append! cv :system (str msg "\n")))))))})

(defn- refuse-foreign!
  "Tabs plan P2.T4: the refusal message (truthy), after printing it, when
  tab belongs to another project or dialect than the window
  (jonquil.tabs/runnable-here?), so Run, Run Selection and Run Form
  evaluate nothing there."
  [{:keys [tab cv sel*]}]
  (let [tsel @(:project tab)]
    (when-not (tabs/runnable-here? @sel* tsel)
      (let [msg (tabs/refusal-message {:file (file-name @(:path tab)) :tab-sel tsel
                                       :window-sel @sel*})]
        (cview/append! cv :system msg)
        msg))))

(defn- notifying-opts
  "base (make-eval-opts' map) with both callbacks chained so an MCP hook
  hears the evaluation: :on-output also collects each chunk into out (an
  atom of strings), and :on-complete, after the base's own handling, calls
  (notify {:done {:result r :output <all collected output>}}). The base's
  :on-output arity is kept ([kind s])."
  [base out notify]
  (assoc base
         :on-output (fn [kind s]
                      ((:on-output base) kind s)
                      (swap! out conj s))
         :on-complete (fn [result]
                        ;; notify in finally: a throw in the base handling (a
                        ;; malformed result map) must not lose the MCP call's
                        ;; only wake-up. The rethrow still reaches the
                        ;; evaluator's own guard.
                        (try ((:on-complete base) result)
                             (finally
                               (notify {:done {:result result :output (apply str @out)}}))))))

(defn- hooked-opts
  "Plugins P6: opts without :hook-source (:run :selection :form :repl) and
  :hook-tab, and, when it had a source, its :on-complete chained so the
  plugins' :after-run and :on-error hooks hear how the eval ended. Only
  opts handed to ev/eval! come here, so an eval that never started fires
  nothing. Starting the hooks only starts futures; a throw there is
  dropped so the eval's own completion is never lost to it."
  [{:keys [hook-source hook-tab on-complete] :as opts}]
  (let [opts (dissoc opts :hook-source :hook-tab)]
    (if-not hook-source
      opts
      (assoc opts :on-complete
             (fn [result]
               (when on-complete (on-complete result))
               (try (plugin-hooks/after-eval! {:source hook-source :tab hook-tab :result result})
                    (catch :default _ nil)))))))

(defn- do-run!
  "The upstream banner, printed ONLY when the Run actually starts (M1).
  ev/evaluating? is checked BEFORE ev/eval! and the banner (when printed)
  goes first, in that order, so it's never possible for the program's own
  output to land ahead of a banner that hasn't been enqueued yet. When the
  engine is already busy, ev/eval! returns its warning string instead of
  starting anything, and only that line is appended -- no banner.

  P3.T5: commands/pre-run-problem runs against the editor's own text, but
  ONLY when the engine is idle. When it names a bracket problem, Run
  never reaches ev/eval! at all -- nothing is evaluated, even a form
  before the broken one, so an earlier form's own unresolved-symbol error
  can never hide the bracket message (row pre-run-hidden-by-error). The
  banner still prints before the problem is appended as :error, and the
  status is set to Ready.

  Fix round 1 (Important #2): a Run while one is ALREADY running must
  stay exactly the P2 path -- ev/evaluating? is checked FIRST, before
  the bracket check ever runs, so a second Run never sets the status to
  Ready (the first program is still running) and never appends a bracket
  :error into the middle of its output; only ev/eval!'s own busy-refusal
  warning is appended, same as P2, with no banner (the busy path never
  printed one) and no status change (eval/eval-async's own busy branch
  returns its warning string without ever calling on-status-change).

  Cmd/Ctrl+Shift+L's own choke point for the banner (jonquil.gtk.console-
  view's own citation: the banner doesn't go through echo-entry!, since
  only Run Selection/Run Form do): cview/mark-entry-start! is queued right
  before the banner itself is queued, so it resolves (on the console's own
  next drain) right where the banner's own text lands -- never on the busy
  path above, which prints no banner at all.

  MCP2: with notify (a fn of one map), exactly one call reports how the Run
  went -- {:refused msg}, {:busy warning}, {:problem text}, or, from the
  eval worker, {:done {:result r :output s}} -- and the return value is a
  reader, (fn [] output-so-far); without it this returns nil as before."
  ([ctx] (do-run! ctx nil))
  ([{:keys [tab cv ev* status-label] :as ctx} notify]
   (let [{ed :ed path-atom :path id :id} tab
         out (atom [])
         refused (refuse-foreign! ctx)
         opts (fn [] (let [base (assoc (make-eval-opts ed cv ev* status-label true)
                                       :run? true :path @path-atom :owner id)]
                       (hooked-opts (assoc (if notify (notifying-opts base out notify) base)
                                           :hook-source :run :hook-tab id))))]
     (cond
       refused (when notify (notify {:refused refused}))
       (ev/evaluating? @ev*)
       (let [warning (ev/eval! @ev* (editor/editor-text ed) (opts))]
         (when warning
           (cview/append! cv :system warning)
           (when notify (notify {:busy warning}))))
       :else
       (let [text (editor/editor-text ed)
             problem (commands/pre-run-problem text)]
         (cview/mark-entry-start! cv)
         ;; Tabs plan ruling 5: a saved tab's banner names its file.
         (cview/append! cv :system (tabs/run-banner @path-atom))
         (if problem
           (do (cview/append! cv :error (str problem "\n"))
               (set-status! status-label "Ready")
               (when notify (notify {:problem problem})))
           (let [_ (when-not (ev/evaluating? @ev*) (cview/note-run-start! cv))
                 warning (ev/eval! @ev* text (opts))]
             (when warning
               (cview/append! cv :system warning)
               (when notify (notify {:busy warning})))))))
     (when notify (fn [] (apply str @out))))))

(defn- do-stop!
  "Fix round 2, finding B: a running Stop's detached line comes from that
  evaluation's own on-output (attach/interrupt! calls it directly). An
  idle Stop has no evaluation and so no on-output to print through, and
  on-connection! isn't wired to the console anywhere (watch-connection!
  only sets the dialect label) -- spec §3 has no \"while running\"
  qualifier, so the smallest fix is here: check, before interrupt! runs,
  whether this is an attach evaluator that's connected and idle, and
  print the same line cv would otherwise never see. Widening the shared
  Evaluator protocol's interrupt! return value for one runtime's idle
  notice would touch jonquil.session and jonquil.nrepl.evaluator too, for
  no reason either of them needs. Checked before interrupt! runs, since
  that's what closes the connection; connected? would read false after."
  [ev* cv]
  (let [e @ev*
        detached (cond (instance? jonquil.nrepl.attach.Attach e) cljd/detached-message
                       (instance? jonquil.nrepl.shadow.Shadow e) cljs/detached-message)
        idle-detach? (and detached
                          (connection/connected? e)
                          (not (ev/evaluating? e)))]
    (ev/interrupt! e)
    (when idle-detach?
      (cview/append! cv :system (str detached "\n"))))
  nil)

;; --- REPL: the console's on-submit, and the history adapters ---------------

(defn- do-repl-eval!
  "submit-repl! with the same opts run uses, minus the banner -- and the
  same warning-append bookkeeping, since submit-repl! can return the same
  busy warning run-definitions! can. extra-opts is merged in last: Run
  Selection and Run Form pass {:ns-source buffer-text}, which the attach
  evaluator uses to switch the session into the buffer's namespace first
  (cljd plan P2.T4); the in-process and nREPL evaluators ignore it."
  ([ed cv ev* status-label text] (do-repl-eval! ed cv ev* status-label text nil))
  ([ed cv ev* status-label text extra-opts]
   (let [_ (when-not (ev/evaluating? @ev*) (cview/note-run-start! cv))
         warning (ev/eval! @ev* text (hooked-opts (merge (assoc (make-eval-opts ed cv ev* status-label false) :run? false)
                                                         extra-opts)))]
     (when warning (cview/append! cv :system warning)))))

(defn- do-run-selection!
  "Cmd/Ctrl+E: commands/run-selection-text (P3.T1) on the editor's snapshot
  is nil for a blank line, else the selection or the caret's whole line.
  When non-nil, echoes \"> text\" to the console as if typed, then goes
  through do-repl-eval! -- the SAME evaluator path a typed REPL entry uses
  (history via ev/eval!, the busy-refusal warning, the same make-eval-opts).

  Fix round 1 (Important #1): echo-entry!'s mark? is (not (ev/evaluating?
  @ev*)), read BEFORE do-repl-eval! can change it -- mirroring do-run!'s
  own idle/busy split. While a program is ALREADY running, this still
  echoes (do-repl-eval! then appends the usual busy-refusal warning, same
  as before) but must NOT steal the last-entry mark from that running
  program: a later Shift+L needs to keep clearing the WHOLE run, not stop
  short at wherever this mid-run echo landed."
  [{:keys [tab cv ev* status-label] :as ctx}]
  (let [ed (:ed tab)
        snap (editor/snapshot ed)]
    (when-let [text (commands/run-selection-text snap)]
      (when-not (refuse-foreign! ctx)
        (cview/echo-entry! cv text (not (ev/evaluating? @ev*)))
        (do-repl-eval! ed cv ev* status-label text {:ns-source (:text snap) :owner (:id tab)
                                                    :hook-source :selection :hook-tab (:id tab)})))))

;; --- MCP hooks (mcp2): run/eval/run-selection that report back ---------------

(defn- mcp-eval!
  "A REPL entry at the console for the MCP server: echoes text, then
  evaluates it like do-repl-eval! (run? false, owner :repl unless
  extra-opts says otherwise), with notifying-opts chained on. notify gets
  exactly one map: {:busy warning} (the warning is also appended after the
  echo, as for a typed entry) or, later, {:done ...}. Returns the output
  reader, (fn [] output-so-far)."
  [{:keys [tab cv ev* status-label]} text extra-opts notify]
  (let [ed (:ed tab)
        out (atom [])
        opts (hooked-opts (merge (notifying-opts (assoc (make-eval-opts ed cv ev* status-label false) :run? false)
                                                 out notify)
                                 extra-opts))]
    (cview/echo-entry! cv text (not (ev/evaluating? @ev*)))
    (let [_ (when-not (ev/evaluating? @ev*) (cview/note-run-start! cv))
          warning (ev/eval! @ev* text opts)]
      (when warning
        (cview/append! cv :system warning)
        (notify {:busy warning})))
    (fn [] (apply str @out))))

(defn- mcp-run-selection!
  "Selects start..end (clamped to the buffer) in the active tab, then takes
  the do-run-selection! path: {:none s} for a blank range, {:refused msg}
  for a foreign tab, else echo + eval with :ns-source and the tab's :id as
  :owner. Returns the output reader, (fn [] \"\") on the no-eval paths."
  [{:keys [tab] :as ctx} start end notify]
  (let [ed (:ed tab)
        buf (:buf ed)
        n (g/gtk-text-buffer-get-char-count buf)
        clamp (fn [x] (max 0 (min n x)))
        a (clamp start)
        b (clamp end)
        _ (g/with-iters* buf a b (fn [i1 i2] (g/gtk-text-buffer-select-range buf i1 i2)))
        snap (editor/snapshot ed)]
    (if-let [text (commands/run-selection-text snap)]
      (if-let [msg (refuse-foreign! ctx)]
        (do (notify {:refused msg}) (fn [] ""))
        (mcp-eval! ctx text {:ns-source (:text snap) :owner (:id tab)
                             :hook-source :selection :hook-tab (:id tab)}
                   notify))
      (do (notify {:none (str "Nothing to run: the range " a ".." b " is blank.")})
          (fn [] "")))))

;; --- Run Form (cljd plan P3.T2) ---------------------------------------------

(def ^:private FLASH-MS
  ;; estimate: long enough to see which form ran, short enough not to be in
  ;; the way of the next keystroke. The spec asks for a brief flash.
  250)

(defn- make-flash-fn
  "{:flash! (fn [start end caret]) :cancel! (fn [] -> boolean)}. :flash!
  selects start..end in ed's buffer, then FLASH-MS later puts the caret
  back where it was, unless the selection changed meanwhile (the user
  moved on) -- the same restore check :cancel! below uses. :cancel!
  restores AT ONCE (I2 fix wave) instead of waiting for the timeout, for
  editor.clj's own key dispatch to call ahead of every other key clause:
  a printable key, Backspace or Tab landing while a flash is still
  showing would otherwise act on the flash's own selection, since the
  flash is a real one. Returns true when a flash was actually pending.

  A second :flash! call while the first's own selection is STILL exactly
  the live one (nothing else -- a keystroke's cancel!, a set-text!, a
  click elsewhere -- has touched it since) carries that first call's own
  saved caret over, rather than its own caret argument, which is really
  just that first selection's own start (select-range's mark convention),
  not a real caret the user left behind. Anything else touching the
  selection since (including a whole different buffer arriving) makes
  this call's own caret argument the right one again -- the same
  liveness check restore! uses to decide whether to act at all.

  One foreign-callable per window, reused by every flash: jonquil.gtk.
  callbacks retains every callable for the process's life, so a fresh one
  per flash would leak. GTK thread only."
  [ed]
  (let [pending (atom nil)
        buf (:buf ed)
        restore! (fn []
                   (boolean
                    (when-let [{:keys [caret sel]} (first (reset-vals! pending nil))]
                      (when (= sel (g/selection-offsets buf))
                        (g/with-iters* buf caret caret (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))
                      true)))
        restore-cb (callable (fn [_data]
                               (try (restore!) (catch :default _ nil))
                                ;; G_SOURCE_REMOVE: a one-shot timeout.
                               0)
                             [:pointer] :int)]
    {:flash! (fn [start end caret]
               (let [p @pending
                     saved (if (and p (= (:sel p) (g/selection-offsets buf))) (:caret p) caret)]
                 (g/with-iters* buf start end (fn [a b] (g/gtk-text-buffer-select-range buf a b)))
                 (reset! pending {:caret saved :sel [start end]})
                 (g/g-timeout-add FLASH-MS restore-cb ffi/null))
               nil)
     :cancel! restore!}))

(defn- do-run-form!
  "Cmd/Ctrl+Enter and the editor menu's Run Form: commands/run-form on the
  editor's snapshot. No form at the caret sets the status \"No form at the
  caret.\" and sends nothing. Otherwise it flashes the form's range, echoes
  the form at the prompt and sends it the way Run Selection does, so an
  attach evaluator switches to the buffer's namespace first.

  Fix round 1 (Important #1): echo-entry!'s mark? is (not (ev/evaluating?
  @ev*)), same idle/busy split as do-run-selection! above, and for the
  same reason -- a mid-run Cmd+Enter must still echo and flash, but must
  never steal the mark from a program that's still running."
  [{:keys [tab cv ev* status-label flash-fns] :as ctx}]
  (let [ed (:ed tab)
        snap (editor/snapshot ed)]
    (if-let [{:keys [text start end]} (commands/run-form snap)]
      (when-not (refuse-foreign! ctx)
        ((:flash! flash-fns) start end (:caret snap))
        (cview/echo-entry! cv text (not (ev/evaluating? @ev*)))
        (do-repl-eval! ed cv ev* status-label text {:ns-source (:text snap) :owner (:id tab)
                                                    :hook-source :form :hook-tab (:id tab)}))
      (set-status! status-label "No form at the caret."))))

;; --- inspector panel (P2.T2) -------------------------------------------

(defn- close-inspector-panel!
  "Hides pane's root and focuses the editor (Decisions: closing hides the
  pane's root and focuses the editor -- the pane keeps its state, so the
  next open replaces it). Used both as the pane's own on-close (its Close
  button, or Escape while the rows view itself has focus) and the
  window keymap's :inspector/close Escape binding (ruling 8, below)."
  [right ed]
  (hide-right-panel! right :inspector)
  (editor/focus! ed))

(defn- show-inspector-panel!
  "Makes the inspector's root visible. The FIRST time (the slot was
  hidden), also sizes h-paned so the panel claims about PANEL-WIDTH of the
  window (Decisions: \"On open, when the pane was hidden, set the
  position to the window width minus 420 (estimate).\") -- a later open,
  while it's already showing, leaves the split exactly where the person
  put it. See show-right-panel!. GTK-thread only."
  [right]
  (show-right-panel! right :inspector))

(defn- try-inspect!
  "Ruling 9: the window sends at most one inspect! at a time. Runs f
  (which must send exactly one ev/inspect! request, through an on-view
  that eventually flips inspecting? back to false) only when no request
  is already pending, atomically flipping inspecting? true first -- a key
  or button press while one is pending is dropped here, with nothing
  queued for after it. A throw from f before its request goes out (the
  panel's show or measure, say) resets the flag before rethrowing, so one
  failure can't leave the inspector dead for the rest of the session."
  [inspecting? f]
  (when (compare-and-set! inspecting? false true)
    (try (f)
         (catch :default e
           (reset! inspecting? false)
           (throw e)))))

(defn- on-inspect-view!
  "on-view's own body, already handed to on-gui! by its caller (Decisions):
  swap! inspector-state with jonquil.inspect/on-reply -- or, the first
  time (inspector-state nil, since inspect-last!/inspect-form! reset it
  right before sending :start), jonquil.inspect/open, which a :start
  reply's own on-reply application would reduce to anyway. A reply with
  :defd also prints \"; Defined <defd>\" on the console as a system line.
  inspecting? is cleared FIRST, so the pane's own next key/click (or
  another inspect-last!/inspect-form!) is never dropped on account of a
  request that has, in fact, already finished."
  [pane inspector-state inspecting? cv status-label reply]
  (try
    (reset! inspecting? false)
    (let [state (swap! inspector-state
                       (fn [s] (if s (inspect/on-reply s reply) (inspect/open reply))))]
      (inspector-pane/show-state! pane state)
      (when-let [defd (:defd reply)]
        (cview/append! cv :system (str "; Defined " defd "\n"))))
    (catch :default e
      (set-status! status-label (str "; inspect: " (ex-message e))))))

(defn- inspect-last!
  "Cmd/Ctrl+I and the menu's \"Inspect Last Result\" (Decisions): ruling 6's
  availability check puts the unavailable sentence in the status bar and
  stops; otherwise shows/sizes the panel, moves focus to its rows view
  (I3: so Return/Escape/arrows go to the panel, not the editor, without
  an extra click), drops any earlier session's own state (so on-view
  opens fresh rather than continuing an old browse), and sends :start
  with the pane's own measured size once its rows view is actually
  allocated (I4: the first open in a window would otherwise measure
  before GTK's own layout pass ever ran) -- guarded by ruling 9, so a
  second press while the first :start is still in flight does nothing."
  [ev* right pane inspector-state inspecting? inspector-last-args on-view status-label]
  (if-not (and (satisfies? ev/Inspector @ev*) (ev/inspectable? @ev*))
    (set-status! status-label inspect/unavailable-message)
    (try-inspect!
     inspecting?
     (fn []
       (show-inspector-panel! right)
       (inspector-pane/focus! pane)
       (reset! inspector-state nil)
       ;; The evaluator current at the press, not whatever @ev* holds once
       ;; the rows view is allocated: a file switch in between would hand
       ;; :start to an evaluator without Inspector. A throw on this deferred
       ;; path releases the in-flight flag, or the inspector would stay dead.
       (let [e @ev*]
         (inspector-pane/measure-when-ready!
          pane
          (fn [measured]
            (try
              (reset! inspector-last-args measured)
              (ev/inspect! e :start measured on-view)
              (catch :default ex
                (reset! inspecting? false)
                (throw ex))))))))))

(defn- inspect-form!
  "Cmd/Ctrl+Shift+I and the menu's \"Inspect Form\" (Decisions): the same
  availability check as inspect-last!, then commands/run-form on the
  editor's snapshot -- \"No form at the caret.\" for no form, the exact
  wording do-run-form! uses. Otherwise flashes, echoes and evaluates the
  form exactly as do-run-form! does, but builds its OWN opts map (never
  passing a second :on-complete through do-repl-eval!'s extra-opts, which
  would just replace the window's own): after make-eval-opts' own
  completion handling runs, an :ok result calls inspect-last! through
  on-gui! -- :on-complete runs on the eval worker thread (the lock-caller
  rule: must only enqueue), and inspect-last! reaches GTK.

  Ruling 9: a press while an inspect! request is already pending does
  nothing at all, not even the eval -- the same drop inspect-last!/
  on-request/on-def give a key or button in that window."
  [{:keys [tab cv ev* status-label inspecting? inspect-last-fn] :as ctx}]
  (let [{:keys [ed flash-fns]} tab]
    (cond
    ;; Final review finding 4: a foreign tab can't evaluate this way either.
      (refuse-foreign! ctx) nil

      (not (and (satisfies? ev/Inspector @ev*) (ev/inspectable? @ev*)))
      (set-status! status-label inspect/unavailable-message)

      @inspecting? nil

      :else
      (let [snap (editor/snapshot ed)]
        (if-let [{:keys [text start end]} (commands/run-form snap)]
          (let [base-opts (make-eval-opts ed cv ev* status-label false)
                opts (assoc base-opts
                            :on-complete
                            (fn [result]
                              ((:on-complete base-opts) result)
                              (when (= :ok (:status result))
                                (on-gui! inspect-last-fn))))]
            ((:flash! flash-fns) start end (:caret snap))
            (cview/echo-entry! cv text (not (ev/evaluating? @ev*)))
            (let [_ (when-not (ev/evaluating? @ev*) (cview/note-run-start! cv))
                  warning (ev/eval! @ev* text (assoc opts :run? false :ns-source (:text snap) :owner (:id tab)))]
              (when warning (cview/append! cv :system warning))))
          (set-status! status-label "No form at the caret."))))))

;; --- window commands (P3.T3): F6 focus toggle, clear console, zoom --------

(defn- do-toggle-focus!
  "F6 (Jonquil's own key): moves focus between the editor and
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

(defn- do-clear-last-output!
  "Cmd/Ctrl+Shift+L (CIDER's C-c C-o): clears only the most recent
  entry's own block. cview/clear-last-output! does the actual work; this
  is only the window's own dispatch fn, same shape as do-clear-console!
  just above."
  [cv]
  (cview/clear-last-output! cv))

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
  "Closes every OPEN popover in popovers (jonquil.gtk.popover/hide!) except
  skip, when given, and returns whether any OTHER one was open -- the
  window's own :close-popovers!*, which the window keymap's
  :popover/close-all Escape binding calls (with no skip) ahead of the Stop
  binding, so closing a popover during a run must happen here, before
  Escape can also Stop the program. A caller about to show or reposition skip passes it here
  instead, so hiding and reshowing the SAME popover within one frame never
  happens -- repositioning an already open popover
  (jonquil.gtk.popover/show-at-caret!) is the clean path."
  [popovers & [skip]]
  (let [open (remove #(= % skip) (filter popover/open? @popovers))]
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

(defn- ask-lookup!
  "ev/lookup for sym, for quick doc and jump. A plain evaluator is asked
  directly and (on-doc doc) runs at once. A server evaluator
  (ev/ServerHelp) is asked through ev/ask-help!: an answer within the
  grace wait runs (on-doc doc) at once too; a later one sets the status to
  \"Looking up <sym>...\" and runs (on-doc doc) on the GTK thread when it
  lands, but only while the window still has that evaluator and (still?)
  holds (the caret is on the same symbol in the active tab); else the
  status goes back to Ready, unless something newer has replaced the
  Looking up line. label names the feature in an error line, also used
  when the lookup itself threw (ev/help-error)."
  [{:keys [ev* status-label]} sym text pos still? label on-doc]
  (let [e @ev*
        looking (str "Looking up " sym "...")
        answer! (fn [doc]
                  (if-let [msg (ev/help-error doc)]
                    (set-status! status-label (str "; " label ": " msg))
                    (on-doc doc)))]
    (if-not (ev/server-help? e)
      (on-doc (ev/lookup e sym text pos))
      (let [[k doc] (ev/ask-help! ev/help-grace-ms (fn [] (ev/lookup e sym text pos))
                                  (fn [doc]
                                    (on-gui!
                                     (fn []
                                       (try
                                         (when (= looking (g/label-text status-label))
                                           (set-status! status-label "Ready"))
                                         (when (and (identical? e @ev*) (still?))
                                           (answer! doc))
                                         (catch :default ex
                                           (set-status! status-label (str "; " label ": " (ex-message ex)))))))))]
        (if (= k :now)
          (answer! doc)
          (set-status! status-label looking))))))

(defn- same-target?
  "Whether snap's help/target is still id ({:symbol :start})."
  [id snap]
  (= id (select-keys (help/target snap) [:symbol :start])))

(defn- live-editor?
  "False once ed has been destroyed (its tab closed): a late answer must
  not read its buffer then (review finding 4)."
  [ed]
  (not @(:destroyed? ed)))

(defn- do-quick-doc!
  "Ctrl+Q/Shift+F1 (P4.T4), the ui's own :quick-doc!*: help/target on ed's
  current snapshot; none sets status \"No symbol at the caret.\"
  Otherwise looks up ev/lookup (D1.T4; the window's evaluator, in the
  running program's own prompt namespace) and shows help/doc-text in the
  quick-doc popover at the caret. Wrapped in try/catch, since no lookup
  here may throw out of a key handler, even though ev/lookup already
  catches its own exceptions -- help/doc-text and syntax/format-
  autocomplete-doc run outside that catch, and this is the editor's own
  key dispatch calling straight through, with nothing else between it
  and a throw.

  P4.T5: close-popovers-fn runs right before show-doc! -- so opening quick
  doc closes completion's popover too, through the SAME registry Escape
  already uses (P4.T3), never a pairwise special case against completion
  by name.

  P3.T2 (ruling 11): a press on the SAME target (last-shown's own
  {:symbol :start}, set below whenever this shows something) while the
  quick-doc popover is STILL open, for an evaluator that satisfies
  ev/FullDoc, asks the server for full documentation instead of
  re-showing the same quick-doc text: the status says Loading, and
  ev/full-doc's on-result -- a worker thread, per its own docstring -- is
  handed to on-gui! whole, so every GTK/editor call inside it runs safely
  on the GTK thread, wrapped in its own try/catch that sets the status on
  a throw. A stale result (help/target on a FRESH snapshot no longer
  matches the target that was loading -- the caret moved on to something
  else while the request was in flight) is dropped: nothing is shown for
  it. Otherwise this closes every OTHER open popover (close-popovers-fn
  full-doc-pop, never full-doc-pop itself, so it's never hidden and
  reshown within the same frame) and shows the text at 60% of the
  window's own height (gtk-widget-get-height), then clears the status
  back to Ready -- or, a non-:ok result, sets it from help/full-doc-
  status. Every other press, and every evaluator without FullDoc (cljd,
  in-process), takes the ordinary re-show path below unchanged, which
  also records the freshly shown target in last-shown."
  [{:keys [ed ev* status-label help-pop full-doc-pop last-shown close-popovers-fn win active?]}]
  (try
    (let [snap (editor/snapshot ed)]
      (if-let [{:keys [symbol start] :as target} (help/target snap)]
        (let [id (select-keys target [:symbol :start])]
          (if (and (popover/open? help-pop) (= id @last-shown) (satisfies? ev/FullDoc @ev*))
            (do (set-status! status-label (str "Loading full docs for " symbol "..."))
                (ev/full-doc @ev* symbol (:text snap)
                             (fn [result]
                               (on-gui!
                                (fn []
                                  (try
                                    (cond
                                      ;; Tabs (spike §3): another tab became active
                                      ;; while the server answered; a popover popped
                                      ;; up on a hidden page maps anyway and is lost
                                      ;; on the next switch, so show nothing.
                                      (not (active?))
                                      (set-status! status-label "Ready")

                                      ;; The caret moved to another symbol while the
                                      ;; server answered: drop the text, but don't
                                      ;; leave "Loading full docs..." behind.
                                      (not= id (select-keys (help/target (editor/snapshot ed)) [:symbol :start]))
                                      (set-status! status-label "Ready")

                                      (= :ok (:status result))
                                      (do (close-popovers-fn full-doc-pop)
                                          (help-popover/show-full-doc!
                                           full-doc-pop (:text result)
                                           (int (* 0.6 (g/gtk-widget-get-height win))))
                                          (set-status! status-label "Ready"))

                                      :else
                                      (set-status! status-label (help/full-doc-status symbol result)))
                                    (catch :default e
                                      (set-status! status-label (str "; full doc: " (ex-message e))))))))))
            (ask-lookup! {:ev* ev* :status-label status-label} symbol (:text snap) start
                         (fn [] (and (active?) (live-editor? ed) (same-target? id (editor/snapshot ed))))
                         "quick doc"
                         (fn [doc]
                           (reset! last-shown id)
                           (close-popovers-fn help-pop)
                           (help-popover/show-doc! help-pop (help/doc-text symbol doc))))))
        (set-status! status-label "No symbol at the caret.")))
    (catch :default e
      (set-status! status-label (str "; quick doc: " (ex-message e))))))

(defn- show-source!
  "Opens loc in the source panel (painted with the window's current
  profile) and shows the panel in the right-hand slot, focusing it. A read
  failure is on the panel's footer; the panel still shows. ctx as
  do-jump!'s."
  [{:keys [sp right sel*]} loc]
  (source-pane/set-profile! sp (:profile @sel*))
  (source-pane/open-location! sp loc)
  (show-right-panel! right :source)
  (source-pane/focus! sp))

(declare jump-to! source-jump-to!)

(defn- open-in-tab!
  "Jump-opens-a-tab (user's choice, 2026-10-19): a definition in one of
  the project's files (loc's :editable?, the rule Open in window uses)
  opens in a tab, or brings forward the tab that has it, with the caret on
  its line, and the panel closes. Where the jump started, the active tab
  and its caret, goes on :jump-back* first, for Cmd/Ctrl+[. When the tab
  doesn't end up showing loc's file (the read failed), the panel shows it
  instead, footer and all. ctx is the source-ctx map."
  [{:keys [open-file! active-tab jump-back* right status-label] :as ctx} symbol loc]
  (let [{from-ed :ed from-path :path from-id :id} (active-tab)
        from {:id from-id :path @from-path :offset (:caret (editor/snapshot from-ed))}
        {err :error} (open-file! (:path loc))
        {:keys [ed path id]} (active-tab)]
    (if (and (not err) @path (source/same-path? @path (:path loc)))
      (let [text (editor/editor-text ed)]
        ;; Only a jump that left its tab can go back: open-file! fills an
        ;; untouched Untitled tab in place, and there's nothing to return
        ;; to there (review finding 1).
        (when-not (= id from-id) (swap! jump-back* source/push from))
        (place-caret-and-scroll! ed (source/line-offset text (:line (source/clamp-line text (:line loc)))))
        (hide-right-panel! right :source)
        (editor/focus! ed)
        (set-status! status-label (str "Jumped to " symbol ".")))
      (show-source! ctx loc))))

(defn- jump-back!
  "Cmd/Ctrl+[ in the editor after a jump into a tab: back to the tab and
  caret the newest jump started from, or that file reopened when its tab
  has closed since. Returns false, doing nothing, when no jump is left to
  go back from, so the key passes on as before. ctx is the source-ctx map
  plus :select-tab!."
  [{:keys [jump-back* select-tab! open-file! active-tab tab-open? status-label]}]
  (if (empty? @jump-back*)
    false
    ;; An entry whose tab has closed and whose file can't be reopened (an
    ;; Untitled tab, a deleted file) is skipped for the next one down
    ;; (review finding 2).
    (loop []
      (if-let [[{:keys [id path offset]} rest-h] (source/back @jump-back*)]
        (do (reset! jump-back* rest-h)
            (let [restored? (cond
                              (tab-open? id) (do (select-tab! id) true)
                              path (do (open-file! path)
                                       (boolean (some-> (active-tab) :path deref (source/same-path? path))))
                              :else false)]
              (if restored?
                (let [{:keys [ed]} (active-tab)]
                  (place-caret-and-scroll! ed (min offset (count (editor/editor-text ed))))
                  (editor/focus! ed)
                  (set-status! status-label "Back.")
                  true)
                (recur))))
        (do (set-status! status-label "Nothing to go back to: those tabs are closed.")
            true)))))

(defn- do-jump!
  "F12/Ctrl+B (P4.T4), the ui's own :jump!*: help/jump-target on ed's
  snapshot, using ev/lookup as the external fallback. :buffer places the
  caret, status \"Jumped to <sym>.\". An :external target whose lookup
  carries :file goes through jonquil.source/location: a location in the
  window's own file moves the caret to that line (clamped) instead; one
  in a project file opens in its tab (open-in-tab!); anything else opens
  in the source panel (source-panel spec). An :external target without
  :file (ClojureDart, ClojureScript, jank, a Jolt var with no recorded
  file) shows its doc in the quick-doc popover, closing every other P4
  popover first. No definition reads \"No definition found for <sym>.\", no symbol
  \"No symbol at the caret.\" Wrapped in try/catch like do-quick-doc!. ctx
  is {:ed :ev* :status-label :help-pop :close-popovers-fn :sp :right :path
  :sel*}."
  [{:keys [ed status-label] :as ctx}]
  (try
    (let [snap (editor/snapshot ed)]
      (if-let [{:keys [symbol start] :as t} (help/target snap)]
        (let [local (help/jump-target snap nil)]
          (if (= :buffer (:kind local))
            (jump-to! ctx symbol local)
            ;; A late answer is placed against the text as it is then, not
            ;; as it was when F12 was pressed (review finding 5).
            (ask-lookup! ctx symbol (:text snap) start
                         (fn [] (and ((:active? ctx (constantly true))) (live-editor? ed)
                                     (same-target? (select-keys t [:symbol :start]) (editor/snapshot ed))))
                         "jump"
                         (fn [doc] (jump-to! ctx symbol (help/jump-target (editor/snapshot ed) doc))))))
        (set-status! status-label "No symbol at the caret.")))
    (catch :default e
      (set-status! status-label (str "; jump: " (ex-message e))))))

(defn- jump-to!
  "do-jump!'s target in hand (help/jump-target's answer, nil for none)."
  [{:keys [ed status-label help-pop close-popovers-fn path sel*] :as ctx} symbol target]
  (let [loc (when (= :external (:kind target))
              (source/location (:doc target) {:project-dir (:project-dir @sel*)}))]
    (cond
      (= :buffer (:kind target))
      (do (place-caret-and-scroll! ed (:offset target))
          (set-status! status-label (str "Jumped to " symbol ".")))

      (and (:path loc) @path (source/same-path? (:path loc) @path))
      (let [text (editor/editor-text ed)]
        (place-caret-and-scroll! ed (source/line-offset text (:line (source/clamp-line text (:line loc)))))
        (set-status! status-label (str "Jumped to " symbol ".")))

      (and (:editable? loc) (:open-file! ctx))
      (do (close-popovers-fn help-pop)
          (open-in-tab! ctx symbol loc))

      loc
      (do (close-popovers-fn help-pop)
          (show-source! ctx loc))

      (= :external (:kind target))
      (do (close-popovers-fn help-pop)
          (help-popover/show-doc! help-pop (help/doc-text symbol (:doc target))))

      :else
      (set-status! status-label (str "No definition found for " symbol ".")))))

(defn- do-source-jump!
  "F12/Ctrl+B inside the source panel: the same lookup on the panel
  editor's own snapshot, so the namespace comes from the panel file's ns
  form. A definition in the panel's file moves the panel caret (history
  kept); one in the window's own file moves the window's caret to it and
  focuses the window's editor, as do-jump! does (it used to load the
  window's file into the panel); one elsewhere loads into the panel; none
  says so on the footer. ctx as do-jump!'s."
  [{:keys [sp] :as ctx}]
  (try
    (let [snap (editor/snapshot (:editor sp))]
      (if-let [{:keys [symbol start] :as t} (help/target snap)]
        (let [local (help/jump-target snap nil)]
          (if (= :buffer (:kind local))
            (source-jump-to! ctx symbol local)
            ;; Only while the panel still shows (review finding 4), and
            ;; placed against its text as it is then (finding 5).
            (ask-lookup! ctx symbol (:text snap) start
                         (fn [] (and (pos? (g/gtk-widget-get-visible (:source-root (:right ctx))))
                                     (same-target? (select-keys t [:symbol :start]) (editor/snapshot (:editor sp)))))
                         "jump"
                         (fn [doc] (source-jump-to! ctx symbol (help/jump-target (editor/snapshot (:editor sp)) doc))))))
        (source-pane/report! sp "No symbol at the caret.")))
    (catch :default e
      (source-pane/report! sp (str "; jump: " (ex-message e))))))

(defn- source-jump-to!
  "do-source-jump!'s target in hand (help/jump-target's answer, nil for
  none)."
  [{:keys [sp sel* ed path status-label] :as ctx} symbol target]
  (let [loc (when (= :external (:kind target))
              (source/location (:doc target) {:project-dir (:project-dir @sel*)}))]
    (cond
      (= :buffer (:kind target)) (source-pane/move-to-offset! sp (:offset target))
      (and (:path loc) path @path (source/same-path? (:path loc) @path))
      (let [text (editor/editor-text ed)]
        (place-caret-and-scroll! ed (source/line-offset text (:line (source/clamp-line text (:line loc)))))
        (editor/focus! ed)
        (set-status! status-label (str "Jumped to " symbol ".")))
      (and (:editable? loc) (:open-file! ctx)) (open-in-tab! ctx symbol loc)
      loc (show-source! ctx loc)
      :else (source-pane/report! sp (str "No source found for " symbol ".")))))

(defn- close-source-panel!
  "Hides the source panel (its state kept) and focuses the editor;
  hide-right-panel! also cancels any re-scroll settling still in flight
  for it."
  [right ed]
  (hide-right-panel! right :source)
  (editor/focus! ed))

(defn- do-source-open!
  "Open in window: an editable panel file opens in a tab through open-file!
  (a new tab, or the tab already showing it), the caret goes to the panel
  caret's line, and the panel closes -- but only once that tab really
  shows loc's own path (source/same-path?: final review finding #2). When
  it doesn't, the read failed: the panel stays open and says so on its
  footer. A read-only location only says why on the footer. Tabs (P2.T3):
  nothing is replaced any more, so there's no unsaved-changes gate. ctx
  is {:cv :sp :right :open-file! :active-tab}."
  [{:keys [sp right open-file! active-tab]}]
  (when-let [{:keys [loc caret]} (source-pane/current sp)]
    (if-not (:editable? loc)
      (source-pane/report! sp (source-pane/read-only-reason loc))
      (let [line (source/line-at (editor/editor-text (:editor sp)) caret)
            {err :error} (open-file! (:path loc))
            {:keys [ed path]} (active-tab)]
        (if (source/same-path? @path (:path loc))
          (do (place-caret-and-scroll! ed (source/line-offset (editor/editor-text ed) line))
              (close-source-panel! right ed))
          (source-pane/report! sp (or err "Couldn't open that file.")))))))

;; --- rename (P4.T7) ------------------------------------------------------

(defn- do-rename!
  "Shift+F6/F2, the ui's own :rename!*: help/target on ed's current
  snapshot; none sets status \"Put the caret on a symbol first.\" (the
  brief's own wording -- rename-problem's OWN sentence for this case
  reads the same, but this path never opens the popover at all, so there
  is no problem label to show it in). Otherwise closes every other open
  P4 popover first (close-popovers-fn, same reasoning as do-quick-doc!/
  do-jump! above) and shows the rename popover prefilled with the target
  symbol. Same try/catch reasoning as do-quick-doc!/do-jump!: help/target
  and help-popover/show-rename! run with nothing else between this and a
  throw."
  [ed rename-pop status-label close-popovers-fn]
  (try
    (if-let [{:keys [symbol]} (help/target (editor/snapshot ed))]
      (do (close-popovers-fn)
          (help-popover/show-rename! rename-pop symbol))
      (set-status! status-label "Put the caret on a symbol first."))
    (catch :default e
      (set-status! status-label (str "; rename: " (ex-message e))))))

(defn- occurrence-count-phrase
  "\"n occurrence\" or \"n occurrences\" -- the plural rule for the rename
  status line."
  [n]
  (str n " occurrence" (when (not= 1 n) "s")))

(defn- do-rename-to!
  "The rename popover's Enter, and the ui's own :rename-to!*: new-name is
  the entry's current text. help/rename-problem on ed's current snapshot
  -- non-nil shows that sentence in the popover's own problem label and
  returns it, applying nothing; nil applies edit/rename's result through
  editor/apply-op! (one user action, one undo step per apply-op!'s own
  contract), closes the popover, sets the rename status wording and
  returns nil. old is read once (help/target) and reused for both the
  status line and (inside rename-problem) the problem sentence, mirroring
  do-quick-doc!/do-jump!'s own snapshot-once shape. Wrapped in try/catch,
  same reasoning as every other P4 action here -- this is the entry's own
  \"activate\" signal handler, so nothing else stands between a throw
  here and GTK's own signal emission."
  [ed rename-pop new-name status-label]
  (try
    (let [snap (editor/snapshot ed)
          old (:symbol (help/target snap))
          problem (help/rename-problem snap new-name)]
      (if problem
        (do (help-popover/show-rename-problem! rename-pop problem) problem)
        (let [result (edit/rename snap new-name)]
          (editor/apply-op! ed result)
          (popover/hide! rename-pop)
          (set-status! status-label
                       (str "Renamed " (occurrence-count-phrase (count (:edits result)))
                            " of '" old "' to '" new-name "'."))
          nil)))
    (catch :default e
      (let [msg (str "; rename: " (ex-message e))]
        (set-status! status-label msg)
        msg))))

;; --- completion (P4.T5) -------------------------------------------------

(defn- do-complete!
  "Ctrl+Space, the ui's own :complete!*: completion/open! on ed's current
  snapshot. Wrapped in try/catch, the same reasoning as do-quick-doc!/
  do-jump! above -- completion/open! runs help/prefix-at, docs/candidates,
  help/open-state and, for a single candidate, editor/apply-op! straight
  through, with nothing else between it and a throw."
  [ed completion status-label]
  (try
    (completion/open! completion (editor/snapshot ed))
    (catch :default e
      (set-status! status-label (str "; complete: " (ex-message e))))))

;; --- REPL completion (P4.T6) --------------------------------------------

(defn- repl-history-context
  "The evaluator's REPL history entries (jonquil.eval/create-history's own
  :items), joined by newlines -- P4.T6's own extra-context for docs/
  candidates' 5-arity, read straight off (ev/history @ev*), the SAME atom
  history-prev-fn/history-next-fn (above) read and write, never a parallel
  copy of it."
  [ev*]
  (str/join "\n" (:items @(ev/history @ev*))))

(defn- do-repl-complete!
  "Ctrl+Space in the console, the ui's own console-facing :complete!*:
  cview/input-snapshot on cv, extended with repl-history-context as
  :extra-context, then completion/open! on repl-completion -- nil from
  input-snapshot (caret before input-start) does nothing, same gate the
  console's own key dispatch already applies before ever calling this.
  Same try/catch reasoning as do-complete! above."
  [cv ev* repl-completion status-label]
  (try
    (when-let [snap (cview/input-snapshot cv)]
      (completion/open! repl-completion (assoc snap :extra-context (repl-history-context ev*))))
    (catch :default e
      (set-status! status-label (str "; complete: " (ex-message e))))))

(defn- wire-completion!
  "Joins completion to the popover registry and its own view's key/caret/
  focus hooks -- shared by the editor's and the console's completion
  instance, which otherwise wire identically. set-fns! registers
  :complete! and a :handle-key! that reports a throw to the status label
  and returns 1 (completion/handle-key! already caught the key by the
  time it reached this wrapper, so a throw escaping it still means the
  key was owned, never a fall-through); on-moved! is the view's own
  caret-move hook (on-cursor-moved! for the editor, on-input-changed! for
  the console -- the callback ignores whatever args it's called with, so
  one fn works for either) and refines the popup on every call, reporting
  a throw the same way; on-focus-lost! closes the popup."
  [completion complete-fn status-label register-popover-fn set-fns! on-moved! on-focus-lost!]
  (register-popover-fn completion)
  (set-fns!
   {:complete! complete-fn
    :handle-key! (fn [keyval state]
                   (try
                     (completion/handle-key! completion keyval state)
                     (catch :default e
                       (set-status! status-label (str "; complete: " (ex-message e)))
                       1)))})
  (on-moved! (fn [& _]
               (try
                 (completion/refine! completion)
                 (catch :default e
                   (set-status! status-label (str "; complete: " (ex-message e)))))))
  (on-focus-lost! (fn [] (completion/close! completion))))

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

(defn- focus-in-popover?
  "True when win's focus is inside pp's popover (its entry's GtkText
  delegate, as find-bar-focused? explains). pp may be nil. Asked of the
  rename popover and of the command palette, both popovers with an entry
  (window-key-ctx's :entry-focused?)."
  [win pp]
  (let [f (g/gtk-root-get-focus win)]
    (boolean (and pp (not (ffi/null? f))
                  (not (zero? (g/gtk-widget-is-ancestor f (:popover pp))))))))

(defn- reload-code-css!
  "Loads theme t's CSS (jonquil.theme/code-css) at font size px into the
  one shared GtkCssProvider. The self-test's :code-css* reads the CSS back
  from the provider itself (g/css-provider-string), so nothing keeps a copy."
  [provider t px]
  (g/gtk-css-provider-load-from-string provider (theme/code-css t px)))

(defn- do-zoom!
  "Cmd/Ctrl+=/-/0: the next font size from commands/zoom (P3.T1), reloaded
  into the one shared GtkCssProvider (added to the display once, in
  activate!) so both views resize live -- probe 4's measured behaviour.
  TH.T4: reloads under the CURRENT theme (theme-name), not a fixed one, so
  a zoom never drops back to the default theme's colours."
  [provider zoom-px theme-name direction]
  (let [px (commands/zoom @zoom-px direction)]
    (reset! zoom-px px)
    (reload-code-css! provider (theme/theme @theme-name) px)))

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
  [{:keys [tabs cv provider zoom-px theme-name]} k]
  (let [t (theme/theme k)]
    ;; Tabs plan P2.T2: every tab's editor and find bar, not just one.
    (doseq [{:keys [ed fb]} @tabs]
      (editor/apply-theme! ed t)
      (find-bar/apply-theme! fb t))
    (cview/apply-theme! cv t)
    (reload-code-css! provider t @zoom-px)
    (g/set-color-scheme! (g/gtk-settings-get-default) (:dark? t))
    (reset! theme-name k))
  nil)

(defn- poll-tick!
  "One tick of the `auto` appearance poll, returned as the GLib source
  result: 0 drops the timer (the choice is no longer :auto), 1 keeps it.
  It never touches poll-armed?: only the real GLib callback may clear that
  (see poll-cb in activate!), because only its return of 0 drops the timer.
  A closing window does nothing, since the app is about to quit and the
  self-test's suppressed close mustn't stop the poll. Otherwise resolves
  the choice against the system and applies it only when it differs from
  the theme being shown. A nil from the system fn means the read failed
  (inside the poll on macOS it can mean nothing else), so the palette is
  left alone and the tick returns 1; startup and apply-choice! still count
  nil as light. Never saves, because the saved value is :auto."
  [{:keys [theme-name theme-choice system-dark-fn* closing?] :as theme-ctx}]
  (cond
    (not= :auto @theme-choice) 0
    @closing? 1
    :else (let [dark? ((deref system-dark-fn*))]
            (when-not (nil? dark?)
              (let [r (theme/resolve-choice :auto dark?)]
                (when (not= r @theme-name)
                  (apply-window-theme! theme-ctx r))))
            1)))

(defn- apply-choice!
  "Sets the theme choice to k (a jonquil.theme choice key, possibly :auto),
  applies the theme it resolves to (:auto asks the system through
  system-dark-fn*, nil counting as light) and returns that resolved key.
  Arms the 1 second appearance poll when k is :auto on macOS; poll-armed?
  keeps it to one timer however often :auto is chosen."
  [{:keys [theme-choice system-dark-fn* poll-armed? poll-cb arm-count] :as theme-ctx} k]
  (reset! theme-choice k)
  (let [r (theme/resolve-choice k (when (= :auto k) ((deref system-dark-fn*))))]
    (apply-window-theme! theme-ctx r)
    (when (and (= :auto k)
               (= "Mac OS X" (System/getProperty "os.name"))
               (compare-and-set! poll-armed? false true))
      ;; If arming throws there is no timer, so the flag must not stay
      ;; true. The throw continues to the caller.
      (try
        (g/g-timeout-add 1000 poll-cb ffi/null)
        (catch Throwable e
          (reset! poll-armed? false)
          (throw e)))
      (swap! arm-count inc))
    r))

(defn- load-and-apply-theme!
  "TH.T5: jonquil.settings/load-settings against path, applied with
  apply-window-theme! -- a missing file is silently :light (load-settings'
  own contract), a present-but-broken one still resolves to a real theme,
  with a :warning appended to the console as :system once. Used both by
  activate! at startup and as the :load-settings!* self-test hook, so a row
  can drive the exact same startup path against a synthetic path."
  [{:keys [cv] :as theme-ctx} path]
  (let [{:keys [theme warning]} (settings/load-settings path)]
    (apply-choice! theme-ctx theme)
    (when warning
      (cview/append! cv :system (str "; settings: " warning "\n")))))

(defn- do-cycle-theme!
  "TH.T5: the chord/:cycle-theme!* action. Advances to the next theme
  (theme/next-theme over the choice, wrapping) and applies it, then saves {:theme k} to
  settings-path -- read at CALL time (not captured once), so a self-test
  row can repoint it via :settings-path!* right before calling this. A
  save error is reported on the console as :system, but the theme switch
  itself already happened and is kept regardless. Locked decision (the
  brief): while a program is running, the status label is left alone --
  overwriting a live Running…/Waiting for input would make the status
  lie, so only the idle case gets 'Theme: <name>'."
  [{:keys [cv theme-choice settings-path status-label evaluating?] :as theme-ctx}]
  (let [k (theme/next-theme @theme-choice)
        r (apply-choice! theme-ctx k)]
    (when-let [err (settings/save-settings! @settings-path {:theme k})]
      (cview/append! cv :system (str "; settings: " err "\n")))
    (when-not (evaluating?)
      (set-status! status-label (if (= :auto k)
                                  (str "Theme: auto (" (name r) ")")
                                  (str "Theme: " (name k)))))))

(defn- make-on-submit
  "on-submit is built and handed to make-console-view BEFORE the console
  view it will act on exists, so it takes an atom and derefs it lazily, at
  call time -- by then activate! has reset! it to the real cv (P2.T7's only
  forward reference; every other closure below is built after its pieces
  already exist)."
  [tab-fn *cv ev* status-label]
  (fn [{:keys [action text]}]
    (case action
      :eval (let [tab (tab-fn)]
              (do-repl-eval! (:ed tab) @*cv ev* status-label text {:hook-source :repl :hook-tab (:id tab)}))
      :send-stdin (ev/send-input! @ev* text)
      :eof (ev/send-eof! @ev*))))

(defn- history-prev-fn [ev*]
  (fn [current-text]
    (let [h (ev/history @ev*)
          [new-hist text] (eval/history-prev @h current-text)]
      (reset! h new-hist)
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
  [ev*]
  (fn []
    (let [h (ev/history @ev*)
          hist @h]
      (when (< (:index hist) (count (:items hist)))
        (let [[new-hist text] (eval/history-next hist)]
          (reset! h new-hist)
          text)))))

;; --- window-level keys: jonquil/keymap.edn's :window, all of them --------

(defn- window-key-ctx
  "The window keymap's ctx for one key: on-window-key's own argument map ctx
  plus the active tab's find-bar and focus reads and actions as fns, so
  jonquil.command-registry never touches GTK itself. keyval0 and state are
  the key's, for the find bar's Escape (:find-bar-key!). The command
  palette builds the same ctx to run a window command as its key would
  (slice S8)."
  [{:keys [win active-tab pane] :as ctx} keyval0 state]
  ;; Tabs plan P2.T2: the find bar is the active tab's.
  (let [fb (:fb (active-tab))]
    (assoc ctx
           :find-bar-navigable? #(find-bar/nav-ready? fb)
           :find-next! #(find-bar/find-next! fb)
           :find-prev! #(find-bar/find-prev! fb)
           :find-bar-focused? #(find-bar-focused? win fb)
           :find-bar-key! #(find-bar/handle-key! fb keyval0 state)
           :inspector-focused? #(inspector-pane/focused? pane win)
           ;; the rename popover's entry and the command palette's (slice S8
           ;; follow-up): either one keeps every key but Escape
           :entry-focused? #(let [t (active-tab)]
                               (or (focus-in-popover? win (:rename-pop t))
                                   (focus-in-popover? win (:palette t)))))))

(defn- on-window-key
  "The window's capture-phase key handler. Every key goes through
  jonquil.command-registry/window-answer: one fixed stage (while the
  rename popover's or the command palette's entry has focus, every key but
  Escape is the entry's, so Run, close and tab switches can't fire
  mid-typing), then jonquil/keymap.edn's
  :window bindings, first match wins, run through the command registry.
  A command answers :handled (1), :to-gtk (0, the key is GTK's: stop) or
  :next (try the next matching binding). keymap_shipped_test pins what
  each window chord runs and the Escape chain's order and conditions,
  since S9 deleted the differential oracle that checked this against the
  cond it replaced (plugins PL1 S1-S4).

  M4: keyval is lowered with gdk_keyval_to_lower first. Caps Lock reports
  the UPPERCASE keyval with no Shift bit, so the chords match whatever Caps
  Lock says, while a binding's own :shift still reads the modifier mask.

  M5: Escape Stops a running program only while one is evaluating;
  otherwise it answers 0, so an IME composition or GTK's own handling gets
  it. Before Stop, Escape goes to the focused find bar (whose own 0, an IME
  preedit composing, is final and goes to GTK, never to Stop), then closes
  any open popover, the focused inspector panel, the focused source panel,
  the file tree's open menu, or leaves the focused tree, in that order,
  without Stopping.

  ctx is the window's map of fns, which the commands and conditions read;
  kctx adds the active tab's find-bar reads and actions and the focus
  checks, so the registry never touches GTK. source-keys ({:focused?
  :close! :back! :open!}, plus an optional :jump-back!, each a no-arg fn)
  is the source panel's: Escape closes it when focused, Cmd/Ctrl+Shift+O
  opens its location in the window, Cmd/Ctrl+[ steps its history back, and
  anywhere else Cmd/Ctrl+[ goes back from a jump that opened a tab
  (:jump-back! true when it went back, else the key passes on). tree-keys
  ({:focused? :leave! :menu-open? :close-menu!}) is the file tree's, and a
  window without a tree passes nil; F2, Delete, Cmd/Ctrl+BackSpace,
  Shift+F10 and Menu match no binding, so they reach the tree's own
  handler.

  The whole answer runs inside try/catch, the same containment
  editor/dispatch-key! gives the editor: a throw from any condition or
  command is reported through set-status! and the key counts as used (1),
  instead of escaping into GTK's signal emission and killing the process."
  [{:keys [status-label] :as ctx}]
  (fn [_ctl keyval0 _keycode state _data]
    (try
      (let [keyval (g/gdk-keyval-to-lower keyval0)
            kctx (window-key-ctx ctx keyval0 state)
            ;; one read per key: the bindings and commands agree
            plugins @plugin-registry/live]
        ;; plugins P3: the live plugins' :window bindings ahead of the
        ;; shipped ones (a plugin may bind a built-in window command)
        (cmd-registry/window-answer
         (plugin-registry/window-bindings plugins) keyval state
         {:holds? (cmd-registry/holds-fn cmd-registry/window-conditions kctx)
          ;; plugins P5: and their window-scope commands, so a :run one
          ;; bound to a key runs
          :run! #(cmd-registry/run-command! (merge cmd-registry/window-commands
                                                   (plugin-registry/commands plugins))
                                            % kctx)}))
      (catch :default e
        (set-status! status-label (str "; key: " (ex-message e)))
        1))))

;; --- context menus (P4.T7) -----------------------------------------------

(defn- install-context-menu!
  "Builds view's P4 context menu (probe 3's own shape): a GMenu with one
  entry per item, each a detailed action name \"p4.<action-name>\"
  (g_menu_append), set as view's extra-menu (gtk_text_view_set_extra_menu
  -- GTK's own Cut/Copy/Paste/... stay, this only APPENDS); a matching
  GSimpleActionGroup holding one no-parameter GSimpleAction per item,
  each wired to call f (its \"activate\" signal), inserted on view under
  prefix \"p4\" (gtk_widget_insert_action_group). items is [[label
  action-name f] ...], from jonquil.command-registry/menu-items (slice
  S7) -- f runs the item's command, the SAME one its key chord runs.

  Returns the GSimpleActionGroup pointer so a caller can fire an item by
  its own unprefixed action-name through g_action_group_activate_action
  directly on the group -- probe 3 measured that equivalent to
  gtk_widget_activate_action_variant(view, \"p4.<name>\", NULL) on the
  prefixed name, since \"p4\" here is only this group's own insertion
  prefix, not a second name the action itself carries.

  Every item's own \"activate\" callback runs inside try/catch, reporting
  a throw through on-error (fn [msg] -> nil, window.clj's own
  set-status!) rather than letting it escape: an uncaught throw from a
  GSimpleAction \"activate\" callback goes through Jolt's foreign-callable
  and kills the process (exit 1), measured live -- the same failure mode
  do-complete!/handle-key! guard against, here at the one call site every
  menu item (current and future) shares, rather than in each item's own
  fn."
  [view items on-error]
  (let [menu (g/g-menu-new)
        group (g/g-simple-action-group-new)]
    (doseq [[label action-name f] items]
      (g/g-menu-append menu label (str "p4." action-name))
      (let [action (g/g-simple-action-new action-name ffi/null)]
        (g/g-signal-connect-data action "activate"
                                 (callable (fn [_action _param _data]
                                             (try
                                               (f)
                                               (catch :default e
                                                 (on-error (str "; " action-name ": " (ex-message e)))))
                                             nil)
                                           [:pointer :pointer :pointer] :void)
                                 ffi/null ffi/null g/CONNECT-DEFAULT)
        (g/g-action-map-add-action group action)))
    (g/gtk-widget-insert-action-group view "p4" group)
    (g/gtk-text-view-set-extra-menu view menu)
    group))

;; --- construction ------------------------------------------------------

;; --- tab operations (tabs plan P2.T3) --------------------------------------
;; ctx below is the window's tab map (activate!'s tab-ctx): {:tabs :active-id
;; :notebook :win :cv :line-col-label :close-popovers-fn :popovers ...}.

(defn- tab-by-id [tabs id] (some (fn [t] (when (= id (:id t)) t)) @tabs))

(declare session-changed!)

(defn- refresh-labels!
  "Sets every tab's label (jonquil.tabs/labels) and shows the strip only
  while there are two or more tabs. Every open, close and path change comes
  through here, so it also saves the tab list (session-changed!)."
  [{:keys [tabs notebook] :as ctx}]
  (let [ts @tabs]
    (doseq [[t text] (map vector ts (tabs/labels (map (fn [t] {:path @(:path t) :dirty? (editor/dirty? (:ed t))}) ts)))]
      (tab/set-label! t text))
    (g/gtk-notebook-set-show-tabs notebook (if (> (count ts) 1) 1 0)))
  (session-changed! ctx))

(defn- on-switch!
  "Makes t the active tab: the window's title, Ln/Col and focus follow it,
  and every open popover closes (one on a hidden page would be lost). The
  saved tab list records the new active tab (session-changed!)."
  [{:keys [active-id win line-col-label close-popovers-fn tree-pane] :as ctx} t]
  (reset! active-id (:id t))
  (close-popovers-fn)
  ;; File-tree plan P2.T2: the tree selects the active tab's file (never
  ;; expanding folders for it, ruling 5).
  (when tree-pane (file-tree-pane/select-path! tree-pane @(:path t)))
  (update-title! (:ed t) win (:path t))
  (let [[line col] (g/cursor-line-col (:buf (:ed t)))]
    (g/gtk-label-set-text line-col-label (str "Ln " (inc line) ", Col " (inc col))))
  (editor/focus! (:ed t))
  (session-changed! ctx))

(defn- switch-page-fn
  "The notebook's switch-page handler. Spike §1: the tab is found from the
  page argument, never get_current_page (which reads -1 while a current
  page is being removed), and a page that isn't in tabs (the first
  append, or a tab already taken out) is ignored."
  [{:keys [tabs] :as ctx}]
  (fn [_nb page _n _data]
    (when-let [t (some (fn [t] (when (= page (:root t)) t)) @tabs)]
      (on-switch! ctx t))
    nil))

(defn- select-tab!
  "Shows tab id's page. set_current_page fires switch-page, except when the
  page is already current, so on-switch! runs here in that case."
  [{:keys [tabs notebook active-id] :as ctx} id]
  (when-let [t (tab-by-id tabs id)]
    (g/gtk-notebook-set-current-page notebook (g/gtk-notebook-page-num notebook (:root t)))
    (when-not (= id @active-id) (on-switch! ctx t))))

(defn- remove-tab!
  "Takes tab id out of the window, in the spike's order: out of tabs first
  (so the switch-page remove_page fires never resolves to it), its five
  popovers out of the registry and unparented (unparenting drops their
  last reference, and a page removed with a popover still parented spins
  GTK in a warning loop), then the page, then the neighbour selected."
  [{:keys [tabs active-id notebook popovers sel* sel-ctx cv ev*] :as ctx} id]
  (when-let [t (tab-by-id tabs id)]
    (let [ts @tabs
          i (.indexOf ^java.util.List (mapv :id ts) id)
          neighbour (or (get ts (inc i)) (get ts (dec i)))
          was-active? (= id @active-id)
          gone (set (tab/popovers t))]
      (swap! tabs (fn [v] (vec (remove (fn [x] (= id (:id x))) v))))
      (when was-active? (reset! active-id (:id neighbour)))
      (swap! popovers (fn [ps] (vec (remove gone ps))))
      ;; Final review finding 3a: a Run Form flash still pending would
      ;; restore the selection on this tab's buffer after it's destroyed.
      (when-let [cancel! (:cancel! (:flash-fns t))] (cancel!))
      ;; Final review finding 2: nothing will Run as this tab again, so
      ;; its namespaces go to the REPL's set for the next Run to clear.
      (let [e @ev*] (when (satisfies? ev/Owners e) (ev/release-owner! e id)))
      (tab/dispose-popovers! t)
      (g/gtk-notebook-remove-page notebook (g/gtk-notebook-page-num notebook (:root t)))
      (refresh-labels! ctx)
      (when was-active?
        (g/gtk-notebook-set-current-page notebook (g/gtk-notebook-page-num notebook (:root neighbour)))
        (on-switch! ctx neighbour))
      ;; Ruling 1: one tab left, from another project -> the window adopts
      ;; it (today's switch, with its "Switched to" line).
      (let [[only & more] @tabs
            osel (when only @(:project only))]
        (when (and only (not more) osel
                   (not (tabs/runnable-here? @sel* osel)))
          (try (apply-selection! sel-ctx osel)
               (catch :default e
                 (cview/append! cv :error (str "; project: " (ex-message e) "\n")))))))))

(defn- close-tab-choice!
  "The close-tab alert's decision: 0 Save (then close once saved), 1 Don't
  Save (close), 2 Cancel or a dismissal (nothing)."
  [{:keys [tabs cv win] :as ctx} id {:keys [index error]}]
  (when-let [{:keys [ed path]} (tab-by-id tabs id)]
    (let [close-if-saved! (fn [] (refresh-labels! ctx) (when-not (editor/dirty? ed) (remove-tab! ctx id)))]
      (cond
        error nil
        (= index 1) (remove-tab! ctx id)
        (= index 0)
        (if-let [p @path]
          (do (save-to! ed cv path p) (close-if-saved!))
          (file-dialog! :save win
                        (fn [{p :path err :error :as r}]
                          (cond
                            p (do (save-to! ed cv path p) (close-if-saved!))
                            (dialog-dismissed? r) nil
                            :else (cview/append! cv :error (str "; save: " err "\n"))))))
        :else nil))))

(defn- close-tab!
  "Cmd/Ctrl+W and a tab's ×: on the last tab, closes the window (today's
  path, with its own alert). Otherwise a dirty tab asks first, with the
  same alert the window uses for one file; a clean one closes."
  [{:keys [tabs win cv] :as ctx} id]
  (when-let [{:keys [ed path]} (tab-by-id tabs id)]
    (cond
      (= 1 (count @tabs)) (g/gtk-window-close win)
      (editor/dirty? ed)
      (when-not @alert-pending?
        (reset! alert-pending? true)
        (try
          (show-alert! win (tabs/close-alert [(if-let [p @path] (file-name p) "Untitled")]) CLOSE-ALERT
                       (fn [result] (close-tab-choice! ctx id result)))
          (catch :default e
            (reset! alert-pending? false)
            (cview/append! cv :error (str "; close: " (ex-message e) "\n")))))
      :else (remove-tab! ctx id))))

;; --- tabs (tabs plan P2.T2) -------------------------------------------------
;; The window keeps its tabs in a vector (tabs) and the active one's id
;; (active-id); everything per buffer lives in a tab map built by
;; jonquil.gtk.tab/make-tab! and wired here. Closures built in wire-tab!
;; capture their own tab, never the active one: a tab's editor keys, menu
;; and popovers act on that tab even while another is active.

(defn- wire-tab!
  "Connects one tab to the window, doing for its editor everything
  activate! used to do for the single editor, and returns the tab with
  :flash-fns, :menu-group and :actions {:quick-doc! :jump! :rename!
  :rename-to! :complete! :run-form!} (each a fn bound to this tab) added.
  ctx is the window's map; see add-tab!."
  [{:keys [win cv ev* status-label line-col-label close-popovers-fn register-popover-fn
           active? source-ctx sel-ctx sel* tabs settings-path* inspect-last-fn inspect-form-fn tree-saved!
           open-find-fn open-replace-fn window-key-args repl-complete-fn] :as ctx}
   {:keys [ed fb help-pop full-doc-pop rename-pop completion palette last-shown path discard-warned?] :as t}]
  (let [flash-fns (make-flash-fn ed)
        quick-doc-fn (fn [] (do-quick-doc! {:ed ed :ev* ev* :status-label status-label :help-pop help-pop
                                            :full-doc-pop full-doc-pop :last-shown last-shown
                                            :close-popovers-fn close-popovers-fn :win win
                                            :active? (fn [] (active? t))}))
        jump-fn (fn [] (do-jump! (source-ctx t)))
        rename-fn (fn [] (do-rename! ed rename-pop status-label close-popovers-fn))
        rename-to-fn (fn [name] (do-rename-to! ed rename-pop name status-label))
        complete-fn (fn [] (do-complete! ed completion status-label))
        run-form-fn (fn [] (do-run-form! {:tab t :cv cv :ev* ev* :sel* sel* :status-label status-label
                                          :flash-fns flash-fns}))
        ;; slice S7: the editor menu's ctx (jonquil.command-registry/
        ;; editor-menu), this tab's own fns, each the one its key calls
        menu-ctx {:run-form! run-form-fn :jump! jump-fn :rename! rename-fn :quick-doc! quick-doc-fn
                  :inspect-last-fn inspect-last-fn :inspect-form-fn inspect-form-fn :complete! complete-fn
                  :toggle-comment! (fn [] (editor/do-toggle-comment! ed))
                  :format-all! (fn [] (editor/do-format-all! ed))
                  :format-selection! (fn [] (editor/do-format-selection! ed))
                  :indent! (fn [] (editor/do-indent! ed))
                  :unindent! (fn [] (editor/do-unindent! ed))
                  :open-find-fn open-find-fn :open-replace-fn open-replace-fn}
        menu-group
        (install-context-menu! (:view ed) (cmd-registry/menu-items cmd-registry/editor-menu menu-ctx)
                               (fn [msg] (set-status! status-label msg)))]
    (editor/set-error-fn! ed (fn [msg] (set-status! status-label msg)))
    (find-bar/set-error-fn! fb (fn [msg] (set-status! status-label msg)))
    (editor/on-dirty-changed! ed (fn [dirty]
                                   (refresh-labels! ctx)
                                   (when (active? t) (update-title! ed win path))
                                   ;; File-tree plan ruling 1: a save shows its file.
                                   (when (and (not dirty) tree-saved!) (tree-saved! t))))
    (g/g-signal-connect-data (:close-btn t) "clicked"
                             (callable (contain-click status-label "close tab" (fn [_] (close-tab! ctx (:id t))))
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; I4: a dirty->clean transition (a successful Save) re-arms the
    ;; discard warning, so an unrelated LATER dirty streak earns its own.
    (editor/on-dirty-changed! ed (fn [dirty] (when-not dirty (reset! discard-warned? false))))
    ;; P3.T5: the status bar's Ln/Col label, the active tab's position only.
    (editor/on-cursor-moved!
     ed (fn [[line col]]
          (when (active? t)
            (g/gtk-label-set-text line-col-label (str "Ln " (inc line) ", Col " (inc col))))))
    ;; P4.T4/P3.T2: the quick-doc and full-doc popovers join the window's
    ;; registry (Escape and teardown close them) and close on any caret
    ;; move or focus leaving the editor.
    (register-popover-fn help-pop)
    (register-popover-fn full-doc-pop)
    ;; P4 parked item (done): a scroll moves the text, so every open
    ;; caret-anchored popover of this tab follows the caret, or hides once
    ;; it leaves the visible text (jonquil.gtk.popover/follow-caret!).
    ;; value-changed fires on the GTK thread; both axes, one handler.
    (let [follow (callable (fn [_adj _data]
                                (doseq [p (tab/popovers t)]
                                  (popover/follow-caret! p))
                                nil)
                              [:pointer :pointer] :void)]
      (doseq [adj [(g/gtk-scrollable-get-vadjustment (:view ed))
                   (g/gtk-scrollable-get-hadjustment (:view ed))]]
        (g/g-signal-connect-data adj "value-changed" follow ffi/null ffi/null g/CONNECT-DEFAULT)))
    (editor/set-quick-doc-fn! ed quick-doc-fn)
    (editor/set-jump-fn! ed jump-fn)
    (editor/on-cursor-moved! ed (fn [_lc] (when (popover/open? help-pop) (popover/hide! help-pop))))
    (editor/on-focus-lost! ed (fn [] (when (popover/open? help-pop) (popover/hide! help-pop))))
    (editor/on-cursor-moved! ed (fn [_lc] (when (popover/open? full-doc-pop) (popover/hide! full-doc-pop))))
    (editor/on-focus-lost! ed (fn [] (when (popover/open? full-doc-pop) (popover/hide! full-doc-pop))))
    ;; P4.T7: rename is autohide ON, so GTK's own grab closes it.
    (register-popover-fn rename-pop)
    ;; slice S8: the command palette runs a command as its key would, with
    ;; its scope's ctx: the window's, this tab's editor's, the console's
    (register-popover-fn palette)
    (palette/set-handlers!
     palette
     {:on-run (fn [id]
                (try
                  (cmd-registry/run-command-in (plugin-registry/all-commands @plugin-registry/live)
                                               id {:window (window-key-ctx window-key-args 0 0)
                                                   :editor (editor/key-ctx ed)
                                                   :console {:complete! repl-complete-fn}})
                  (catch :default e
                    (set-status! status-label (str "; palette: " (ex-message e))))))
      :on-error (fn [msg] (set-status! status-label msg))})
    (editor/set-rename-fn! ed rename-fn)
    (editor/set-run-form-fn! ed run-form-fn)
    (editor/set-flash-cancel-fn! ed (:cancel! flash-fns))
    (g/g-signal-connect-data (:entry rename-pop) "activate"
                             (callable (fn [_entry _data]
                                         (try
                                           (rename-to-fn (help-popover/rename-entry-text rename-pop))
                                           (catch :default e
                                             (set-status! status-label (str "; rename: " (ex-message e)))))
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (wire-completion! completion complete-fn status-label register-popover-fn
                      (fn [fns] (editor/set-completion-fns! ed fns))
                      (fn [f] (editor/on-cursor-moved! ed f))
                      (fn [f] (editor/on-focus-lost! ed f)))
    ;; D1.T4: re-selection whenever this tab's path changes (Open, Save
    ;; As). The reset! runs on the GTK thread, so the watch does too; it
    ;; never throws into GTK. set-profile! sits in a finally so the editor
    ;; follows @sel* even when apply-selection! throws after its own reset!.
    ;; Tabs plan P2.T3 (ruling 10): the tab keeps its own selection in
    ;; :project, and paints with that selection's profile.
    (add-watch path ::dialect
               (fn [_ _ old new]
                 (when (not= old new)
                   (let [tsel (select-for new @settings-path*)]
                     (reset! (:project t) tsel)
                                          ;; Ruling 1: with one tab the window follows, as it always
                     ;; did; with two or more its project is fixed.
                     ;; A loose Jolt file keeps the window's own Jolt, as
                     ;; it does when tabs close down to it (keeps-window-jolt?).
                     (try (when (and (= 1 (count @tabs)) (not (tabs/keeps-window-jolt? @sel* tsel)))
                            (apply-selection! sel-ctx tsel))
                          (catch :default e
                            (cview/append! cv :error (str "; project: " (ex-message e) "\n")))
                          (finally (editor/set-profile! ed (:profile tsel))
                                   (refresh-labels! ctx)
                                   (when tree-saved! (tree-saved! t))))))))
    (assoc t
           :flash-fns flash-fns
           :menu-group menu-group
           :actions {:quick-doc! quick-doc-fn :jump! jump-fn :rename! rename-fn
                     :rename-to! rename-to-fn :complete! complete-fn :run-form! run-form-fn})))

(defn- add-tab!
  "Builds a tab for path (nil for Untitled) holding text, with the
  window's current theme and the profile path's own selection gives it,
  appends its page to the notebook, wires it, and only then adds it to
  tabs. Returns the wired tab. Spike §1: the notebook fires switch-page
  inside the first append, before the tab is in tabs, so the caller makes
  the tab active itself. ctx is wire-tab!'s map plus {:tabs :next-id
  :notebook :theme-name :completion-opts}."
  [{:keys [tabs next-id notebook theme-name completion-opts settings-path* sel*] :as ctx} {:keys [path text]}]
  (let [tsel (select-for path @settings-path*)
        t (tab/make-tab! {:id (swap! next-id inc) :path path :text text
                          :theme (theme/theme @theme-name)
                          :profile (:profile (if path tsel @sel*))
                          :completion-opts completion-opts})]
    (when path (reset! (:project t) tsel))
    (g/gtk-notebook-append-page notebook (:root t) (:label-box t))
    (let [wired (wire-tab! ctx t)]
      (swap! tabs conj wired)
      wired)))

(defn- new-tab!
  "Cmd/Ctrl+T and Open: a tab for path (nil for Untitled) holding text,
  placed right after the active tab (ruling 8) and selected. A failure
  while building it goes on the console and leaves the tabs as they were."
  [{:keys [tabs notebook active-id cv] :as ctx} {:keys [path text]}]
  (try
    (let [i (.indexOf ^java.util.List (mapv :id @tabs) @active-id)
          t (add-tab! ctx {:path path :text (or text "")})]
      (swap! tabs (fn [v] (tabs/insert-after (vec (remove (fn [x] (= (:id t) (:id x))) v)) i t)))
      (g/gtk-notebook-reorder-child notebook (:root t) (inc i))
      (refresh-labels! ctx)
      (select-tab! ctx (:id t))
      t)
    (catch :default e
      (cview/append! cv :error (str "; new tab: " (ex-message e) "\n"))
      nil)))

(defn- read-for-tab
  "{:text t} for path, warning on the console when it isn't UTF-8 (as
  open-path! does), or {:error msg} after appending it."
  [cv path]
  (try
    (let [text (slurp path)]
      (when (str/includes? text "�")
        (cview/append! cv :system
                       "This file isn't valid UTF-8; unreadable bytes show as � and saving will replace them.\n"))
      {:text text})
    (catch :default e
      (cview/append! cv :error (str "; open: " (ex-message e) "\n"))
      {:error (ex-message e)})))

(defn- open-file!
  "Open (tabs plan P2.T3): the tab already showing path is selected;
  otherwise an untouched Untitled active tab is filled (open-path!, ruling
  4); otherwise path opens in a new tab. Returns {:error msg} when the file
  couldn't be read, else nil."
  [{:keys [tabs cv win active-tab] :as ctx} path]
  (let [ts @tabs
        t (active-tab)]
    (if-let [i (tabs/find-open (map (fn [x] {:path @(:path x)}) ts) path)]
      (do (select-tab! ctx (:id (nth ts i))) nil)
      (if (tabs/untouched-untitled? {:path @(:path t) :text (editor/editor-text (:ed t))})
        (let [r (open-path! (:ed t) cv (:path t) path)]
          (update-title! (:ed t) win (:path t))
          (refresh-labels! ctx)
          r)
        (let [{:keys [text error]} (read-for-tab cv path)]
          (if error
            {:error error}
            (do (new-tab! ctx {:path path :text text}) nil)))))))

;; --- last session's tabs (session-tabs plan Task 2) --------------------------
;; ctx is the window's tab map (tab-ctx), which carries :tree-pane,
;; :tree-saved! and :folder-result* (the atom holding Open Folder's result
;; handler, set once activate! has built it).

(defn- reopen-tabs?
  "False only when the settings file holds :reopen-tabs? false."
  [settings-path]
  (not (false? (:reopen-tabs? (settings/load-settings settings-path)))))

(defn- session-snapshot
  "{:root :tabs :active} for sessions.edn: the tree's root, every tab's
  path in tab order (Untitled ones left out), and the active tab's path."
  [{:keys [tabs tree-pane active-tab]}]
  {:root (:root @(:state tree-pane))
   :tabs (vec (keep (fn [t] @(:path t)) @tabs))
   :active (some-> (active-tab) :path deref)})

(defn- save-session!
  "Remembers the open tabs for the tree's root in sessions.edn. nil, or an
  error string when saving failed; does nothing when :reopen-tabs? is off."
  [{:keys [settings-path*] :as ctx}]
  (when (reopen-tabs? @settings-path*)
    (let [path (last-session/sessions-path @settings-path*)]
      (last-session/save-sessions!
       path (last-session/remember (:sessions (last-session/load-sessions path)) (session-snapshot ctx))))))

(defn- session-changed!
  "Saves the tab list as it changes, not only at quit, so a crash or a
  force quit keeps it. Does nothing until :session-live? is set, which
  activate! does once the launch's own tabs are open: before that the
  first Untitled tab would overwrite the list it's about to reopen. Writes
  only when the snapshot differs from the last one written (or the state
  startup ended in), so a label refresh for a dirty mark costs no I/O. With
  :reopen-tabs? off it does nothing and records nothing, so turning it back
  on saves at the next change. A failure is said on the console
  once, until a save works again. ctx without :session-live? (a partial
  map) does nothing."
  [{:keys [session-live? session-last session-error cv] :as ctx}]
  (when (and session-live? @session-live? (reopen-tabs? @(:settings-path* ctx)))
    (let [snap (session-snapshot ctx)]
      (when (not= snap @session-last)
        (let [err (try (save-session! ctx) (catch :default e (ex-message e)))]
          (if err
            (when (not= err @session-error)
              (reset! session-error err)
              (cview/append! cv :system (str "; sessions: " err "\n")))
            (do (reset! session-error nil)
                (reset! session-last snap))))))))

(defn- session-entry!
  "The sessions.edn entry for the tree's root, or nil when :reopen-tabs? is
  off or the file can't be read (said on the console). With reroot? and
  no root, takes the most recent entry and roots the tree at its folder
  when that exists."
  [{:keys [cv tree-pane settings-path* folder-result*]} {:keys [reroot?]}]
  (when (reopen-tabs? @settings-path*)
    (let [{:keys [sessions warning]} (last-session/load-sessions (last-session/sessions-path @settings-path*))
          root (:root @(:state tree-pane))]
      (cond
        warning (do (cview/append! cv :system (str "; " warning "\n")) nil)
        (and reroot? (nil? root))
        (let [e (last-session/entry-for sessions nil)]
          (when (and (:root e) (.isDirectory (java.io.File. ^String (:root e))))
            (@folder-result* {:path (:root e)}))
          e)
        :else (last-session/entry-for sessions root)))))

(defn- reopen-failed?
  "Opens path as a tab; true when it's gone or couldn't be opened."
  [ctx path]
  (try (or (not (.isFile (java.io.File. ^String path))) (boolean (:error (open-file! ctx path))))
       (catch :default _ true)))

(defn- finish-reopen!
  "Selects the tab entry had active and names the missing files (their
  base names) on the console."
  [{:keys [cv tabs tree-saved!] :as ctx} entry missing]
  (when-let [t (and (:active entry)
                    (some (fn [t] (when (= (:active entry) @(:path t)) t)) @tabs))]
    (select-tab! ctx (:id t))
    (tree-saved! t))
  (when-let [line (last-session/missing-line missing)]
    (cview/append! cv :system line)))

(defn- reopen-entry!
  "Opens entry's tabs in order, then selects its active one and names the
  files that couldn't be opened."
  [ctx entry]
  (finish-reopen! ctx entry
                  (reduce (fn [acc p] (if (reopen-failed? ctx p) (conj acc (.getName (java.io.File. ^String p))) acc))
                          [] (:tabs entry))))

(defn- restore-session!
  "Reopens the tabs sessions.edn holds for the tree's root, in order, and
  selects the one that was active (session-entry! picks the entry and does
  any re-root). Files that are gone (or can't be opened) are named on the
  console. Does nothing when :reopen-tabs? is off."
  [ctx opts]
  (when-let [entry (session-entry! ctx opts)]
    (reopen-entry! ctx entry))
  nil)

(def ^:private FIRST-FRAME-FALLBACK-MS
  "How long after-first-frame! waits for a frame before running anyway (a
  window that starts minimized may not paint)."
  1000)

(defn- after-first-frame!
  "Runs f on a later main-loop turn, once win's first frame has been
  painted, or after FIRST-FRAME-FALLBACK-MS when no frame comes. Call it
  after gtk_window_present, since the frame clock exists only once the
  window is realized."
  [win f]
  (let [done? (atom false)
        handler (atom nil)
        clock (g/gtk-widget-get-frame-clock win)
        once! (fn [] (when (compare-and-set! done? false true) (on-gui! f)))]
    (when-not (ffi/null? clock)
      ;; Disconnected only from inside its own emission, where the clock is
      ;; alive. After the timeout wins it stays connected and does nothing,
      ;; since the timeout may fire after the window, and its clock, is gone.
      (reset! handler (g/g-signal-connect-data clock "after-paint"
                                               (callable (fn [clk _data]
                                                           (when @handler (g/g-signal-handler-disconnect clk @handler))
                                                           (once!)
                                                           nil)
                                                         [:pointer :pointer] :void)
                                               ffi/null ffi/null g/CONNECT-DEFAULT)))
    (g/g-timeout-add FIRST-FRAME-FALLBACK-MS (callable (fn [_data] (once!) 0) [:pointer] :int :on-throw 0) ffi/null)))

(defn- reopen-later!
  "Startup's reopen, once the window has painted its first frame: every
  tab in one main-loop turn, so nothing the user does can land between
  two of them. Then done! runs, even when the reopen threw (said on the
  console). Does nothing if :session-restoring? went false first, which a
  quit does."
  [{:keys [cv win session-restoring?] :as ctx} entry done!]
  (reset! session-restoring? true)
  (after-first-frame!
   win
   (fn []
     (when @session-restoring?
       (reset! session-restoring? false)
       (try
         (reopen-entry! ctx entry)
         (catch :default e
           (cview/append! cv :error (str "; reopen tabs: " (ex-message e) "\n"))))
       (done!)))))

;; --- file operations from the tree (file-ops plan T4) ------------------------
;; The pane calls these inside its popover's own signal emission (the entry's
;; activate, a button's clicked). The disk operation runs now, so an error
;; string can go back to the entry; the tree rebuild and any tab opening wait
;; for the next main-loop turn (Task 3 review ruling 1), since a rebuild
;; inside the emission would tear down rows under it. ctx is the window's tab
;; map (*tab-ctx), which carries :tree-pane.

(defn- tree-later!
  "Runs f on the next main-loop turn; a throw goes to the console under what."
  [cv what f]
  (on-gui! (fn [] (try (f) (catch :default e (cview/append! cv :error (str "; " what ": " (ex-message e) "\n")))))))

(defn- tree-failed!
  "Reports msg on the console under what, refreshes the tree next turn and
  returns msg for the name entry."
  [{:keys [cv tree-pane]} what msg]
  (cview/append! cv :error (str "; " what ": " msg "\n"))
  (tree-later! cv what (fn [] (file-tree-pane/refresh! tree-pane)))
  msg)

(defn- show-in-tree!
  "Rebuilds the tree with path's folders expanded and selects its row."
  [tree-pane path]
  (file-tree-pane/show-model! tree-pane (ft/expand-to (ft/prune @(:state tree-pane)) path))
  (file-tree-pane/select-path! tree-pane path))

(defn- note-if-hidden!
  "Says so on the console when the tree's filters hide the new entry at path."
  [{:keys [cv tree-pane]} path dir?]
  (let [n (.getName (java.io.File. ^String path))]
    (when (ft/ignored? (:ignore @(:state tree-pane)) n dir?)
      (cview/append! cv :system (ops/hidden-message n)))))

(defn- stale-tab-problem
  "The entry's problem when a tab not in except still holds path, or a path
  under it (a tab left open on a trashed file), else nil. Making path again
  would hand that tab a file it never had (final review minor 1)."
  [tabs path except]
  (when (some (fn [t] (and (not-any? (fn [x] (identical? x t)) except) (ops/retarget @(:path t) path path))) tabs)
    (str (.getName (java.io.File. ^String path)) " is open in a tab; close that tab first.")))

(defn- tree-new-file!
  "New File... in dir: creates name, then shows and selects it and opens it
  in a tab. nil, or the error string for the entry (a tab still on that
  path is checked before the disk is touched)."
  [{:keys [cv tree-pane tabs] :as ctx} dir name]
  (let [target (.getAbsolutePath (java.io.File. ^String dir (str/trim (str name))))
        {:keys [path error]} (if-let [p (stale-tab-problem @tabs target [])]
                               {:error p}
                               (ops/create-file! dir name))]
    (if error
      (tree-failed! ctx "new file" error)
      (do (tree-later! cv "new file" (fn []
                                       (show-in-tree! tree-pane path)
                                       (open-file! ctx path)
                                       (note-if-hidden! ctx path false)))
          nil))))

(defn- tree-new-folder!
  "New Folder... in dir: creates name, then shows and selects it. nil, or
  the error string for the entry."
  [{:keys [cv tree-pane] :as ctx} dir name]
  (let [{:keys [path error]} (ops/create-folder! dir name)]
    (if error
      (tree-failed! ctx "new folder" error)
      (do (tree-later! cv "new folder" (fn []
                                         (show-in-tree! tree-pane path)
                                         (note-if-hidden! ctx path true)))
          nil))))

(defn- tree-rename!
  "Rename...: renames old (the entry as listed: a symlink itself, never its
  target) to new-name, then every tab on old (or under it, for a folder)
  follows to the new listed path, keeping its text and dirty state. A tab on
  a symlink's target doesn't move, since the target didn't. The rename runs
  now; the tab paths (a path change rebuilds the tree through the tab's
  watch), labels, title and tree wait for the next turn. Which tabs move is
  decided before the move: a tab spelled differently from old (A.clj for
  a.clj on a case-insensitive volume) resolves only while old exists (final
  review Important 2). Expanded folders at or under old stay expanded under
  the new name (minor 5). A tab still on the new path refuses the rename
  (minor 1). nil, or the error string for the entry."
  [{:keys [cv tree-pane tabs win active-tab] :as ctx} old new-name]
  (let [new (.getAbsolutePath (java.io.File. (.getParentFile (java.io.File. ^String old)) (str/trim (str new-name))))
        moves (vec (keep (fn [t] (when-let [p (ops/retarget @(:path t) old new)] [t p])) @tabs))
        old-canon (.getCanonicalPath (java.io.File. ^String old))
        {:keys [error path]} (if-let [p (stale-tab-problem @tabs new (map first moves))]
                               {:error p}
                               (ops/rename! old new-name))]
    (if error
      (tree-failed! ctx "rename" error)
      (do (tree-later! cv "rename"
                       (fn []
                         ;; before the tab paths change, since a path change
                         ;; rebuilds the tree (pruning the old folders)
                         (swap! (:state tree-pane) update :expanded
                                (fn [e] (set (map (fn [d] (cond
                                                            (= d old-canon) path
                                                            (str/starts-with? d (str old-canon "/")) (str path (subs d (count old-canon)))
                                                            :else d))
                                                  e))))
                         (doseq [[t p] moves]
                           (reset! (:path t) p))
                         (refresh-labels! ctx)
                         (let [t (active-tab)] (update-title! (:ed t) win (:path t)))
                         (show-in-tree! tree-pane new)))
          nil))))

(defn- listed-dir?
  "Whether path is a folder itself, not following a symlink: a link to a
  folder is trashed as a link, so it isn't called a folder."
  [path]
  (java.nio.file.Files/isDirectory (.toPath (java.io.File. ^String path))
                                   (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS])))

(defn- trash-choice!
  "The trash alert's decision for path (the entry as listed, so a symlink
  goes to the Trash, not its target): index 0 moves it to the Trash and
  says how many open tabs were on it (they stay open, text and all); any
  other index or a dismissal does nothing. The count is taken before the
  move, while the path still resolves. Rows call this with a synthetic
  {:index n}, exactly as the alert's completion does."
  [{:keys [cv tabs tree-pane]} path {:keys [index]}]
  (when (= index 0)
    (let [n-tabs (count (filter (fn [t] (ops/retarget @(:path t) path path)) @tabs))
          dir? (listed-dir? path)
          {:keys [error]} (trash/trash! path)]
      (if error
        (cview/append! cv :error (str "; trash: " error "\n"))
        (cview/append! cv :system (ops/trash-message (.getName (java.io.File. ^String path)) n-tabs dir?)))
      (tree-later! cv "trash" (fn [] (file-tree-pane/refresh! tree-pane))))))

(defn- tree-trash!
  "Move to Trash: asks first (Cancel is the default), sharing the close
  alerts' alert-pending? guard, so a second alert never opens over one."
  [{:keys [win cv] :as ctx} path]
  (when-not @alert-pending?
    (reset! alert-pending? true)
    (try
      (let [f (java.io.File. ^String path)]
        (show-alert! win (ops/trash-alert (.getName f) (listed-dir? path)) TRASH-ALERT
                     (fn [result] (trash-choice! ctx path result))))
      (catch :default e
        (reset! alert-pending? false)
        (cview/append! cv :error (str "; trash: " (ex-message e) "\n")))))
  nil)

;; --- activate ---------------------------------------------------------------

;; P5a: the live window's open-paths fn (a vector of paths), published by
;; activate! and read by the application's "open" handler. nil until a
;; window exists.
;; Never reset: the app quits with its window.
(defonce ^:private current-open-paths (atom nil))
;; The live window's status reporter (fn [text]); nil until a window exists.
(defonce ^:private current-report (atom nil))

(defn- report-open-problem!
  "Says text on the window's status label, else on stderr with a prefix."
  [text]
  (if-let [r @current-report]
    (r text)
    (binding [*out* *err*] (println (str "jonquil: " text)))))

(defn- open-paths-from-gfiles
  "The n local paths of a GFile** array, as strings. A file with no local
  path (a remote URI) or one that throws is reported and skipped."
  [files n]
  (into []
        (keep (fn [i]
                (let [gf (ffi/read files :pointer (* 8 i))]
                  (try
                    (let [p (g/g-file-get-path* gf)]
                      (if (ffi/null? p)
                        (let [u (g/g-file-get-uri* gf)
                              uri (if (ffi/null? u) "(unknown)"
                                      (try (ffi/ptr->string u) (finally (g/g-free u))))]
                          (report-open-problem! (str "Couldn't open " uri ": not a local file"))
                          nil)
                        (try (ffi/ptr->string p) (finally (g/g-free p)))))
                    (catch :default e
                      (report-open-problem! (str "Couldn't open file " i ": " (ex-message e)))
                      nil)))))
        (range n)))

(declare activate!)

(defn launch-split
  "When an \"open\" is what launched the app, its first path is the launch
  option activate! always took from the command line: a directory is :dir,
  anything else (a missing file too) is :file. The rest open as tabs.
  dir? is injected, so this stays pure. => {:launch {..} :rest [..]}"
  [paths dir?]
  (if-let [p (first paths)]
    {:launch (if (dir? p) {:dir p} {:file p}) :rest (vec (rest paths))}
    {:launch {} :rest []}))

(defn- on-app-open!
  "The application's \"open\" handler: n GFiles go to the window's
  open-paths fn. With no window yet (the open launched the app), the first
  path is activate!'s launch option and only the rest go to open-paths
  (launch-split). Nothing throws into GTK; a failure is reported."
  [app opts files n]
  (try
    (let [paths (open-paths-from-gfiles files n)]
      (if (nil? @current-open-paths)
        (let [{:keys [launch rest]}
              (launch-split paths (fn [p] (.isDirectory (java.io.File. ^String p))))]
          (activate! app (merge opts launch))
          (when (seq rest)
            (when-let [f @current-open-paths] (f rest))))
        (when-let [f @current-open-paths] (f paths))))
    (catch :default e
      (try (report-open-problem! (str "Couldn't open: " (ex-message e)))
           (catch :default _ nil)))))

(defonce ^:private plugin-console
  ;; (fn [line]) printing one line on the window's console; activate! sets it
  (atom (fn [line] (binding [*out* *err*] (println "jonquil:" line)))))

(defonce ^:private plugin-loads
  ;; plugins P4: every plugin load (startup, Reload Plugins, a saved plugin
  ;; file) runs here, one at a time, so a load starts from what the last
  ;; one left; the state is the last step's console lines. It keeps going
  ;; after a throw, which is reported, so no later load is lost.
  (plugin-loader/load-agent
   (fn [e] (@plugin-console (str "loading plugins failed: " (or (ex-message e) (str e)))))))

(defn- plugin-load!
  "Queues a plugin load on plugin-loads: jonquil.plugin-loader/load-step!
  over the live registry, with the plugins folder and disabled ids from the
  settings file, all files or just only. The step's lines go to the console
  on the GTK thread. Returns the agent, for a caller that wants to wait."
  [{:keys [settings-path only]}]
  (send-off plugin-loads
            (fn [_]
              (let [lines (plugin-loader/load-step!
                           plugin-registry/live
                           {:settings-path settings-path :os (System/getProperty "os.name") :only only})]
                (try (doseq [l lines] (@plugin-console l))
                     (catch :default e
                       (binding [*out* *err*] (println "jonquil: plugin console:" (ex-message e)))))
                lines))))

(defn- plugin-load-now!
  "plugin-load!, waited for up to 10 s: the step's lines, or a line saying
  it didn't finish. For the self-test hooks, called off the GTK thread."
  [opts]
  (let [a (plugin-load! opts)]
    (if (await-for 10000 a)
      @a
      ["the plugin load didn't finish in 10 s"])))

(defn- activate! [app {:keys [file dir cwd on-ready settings-path plugins?]}]
  (let [;; TH.T5: an atom, not a plain value -- :settings-path!* (self-test
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
        ;; D1.T4: the window's dialect and runtime (jonquil.dialect/select),
        ;; and its one evaluator. ev* is an atom because D2.T5 swaps it when
        ;; a file from another runtime opens; every reader derefs it at
        ;; call time. session stays the evaluator the window started with,
        ;; for the ui map's :session. Joins jonquil.runtime-registry right
        ;; away, same as every later evaluator apply-selection! makes.
                ;; File-tree plan P2.T2: a directory launch resolves the directory's
        ;; own project; find-project starts at a path's parent, so it's
        ;; given a child of dir (measured 2026-10-09).
        sel* (atom (if (and dir (not file))
                     (ft/project-selection dir (:dialect (settings/load-settings @settings-path*)))
                     (select-for file @settings-path*)))
        ev* (atom (doto (ev/make @sel*) registry/register!))
        session @ev*
        evaluating-fn (fn [] (ev/evaluating? @ev*))
        ;; Tabs plan P2.T2: the window's tabs, in order, and the active
        ;; one's id; every window-level action resolves the active tab when
        ;; it runs (active-tab), never one captured at build time.
        tabs (atom [])
        active-id (atom nil)
        next-id (atom 0)
        active-tab (fn [] (some (fn [t] (when (= @active-id (:id t)) t)) @tabs))
        active? (fn [t] (= @active-id (:id t)))
        notebook (g/gtk-notebook-new)
        ;; The tab operations' context (tab-ctx), set once the tabs' wiring
        ;; context exists in the body below -- the same forward-reference
        ;; idiom *cv and *right use.
        *tab-ctx (atom nil)
        win (g/gtk-application-window-new app)
        status-label (g/gtk-label-new "Ready")
        line-col-label (g/gtk-label-new "Ln 1, Col 1")
        ;; D1.T4: the window's dialect (and runtime, when it isn't the
        ;; dialect's default), between the status text and Ln/Col.
        dialect-label (g/gtk-label-new (dialect/label @sel*))
        confirmed-close? (atom false)
        ;; P3.T5 self-test hooks only -- see close-request-fn's docstring.
        suppress-close? (atom false)
        closing? (atom false)
        *cv (atom nil)
        cv (cview/make-console-view {:on-submit (make-on-submit active-tab *cv ev* status-label)
                                     :history-prev (history-prev-fn ev*)
                                     :history-next (history-next-fn ev*)
                                     ;; I2: the console reads busy/waiting state
                                     ;; straight from the engine, never a mirror.
                                     :evaluating? evaluating-fn
                                     :waiting? (fn [] (ev/waiting-for-input? @ev*))})
        _ (reset! *cv cv)
        provider (g/gtk-css-provider-new)
        zoom-px (atom commands/DEFAULT-FONT-PX)
        ;; TH.T4: the active theme's name, kept next to zoom-px -- TH.T5
        ;; loads its initial value from settings.
        theme-name (atom theme/default-theme)
        ;; Auto theme: what the user picked (:auto or a theme key), the
        ;; function that asks the system (repointed by a self-test hook),
        ;; whether the appearance poll timer is armed, and how many times
        ;; one was (self-test only).
        theme-choice (atom theme/default-theme)
        system-dark-fn* (atom appearance/system-dark?)
        poll-armed? (atom false)
        arm-count (atom 0)
        ;; P4.T3: this window's own registry of P4 popovers (completion,
        ;; quick doc, rename -- P4.T4-T7 register theirs via
        ;; register-popover-fn as they're created); close-popovers-fn is
        ;; what the window keymap's Escape binding and :close-popovers!* call.
        ;; It takes an optional popover to skip -- a caller about to show
        ;; or reposition ITS OWN popover passes it here, so that one is
        ;; never hidden and reshown within the same frame.
        popovers (atom [])
        close-popovers-fn (fn [& [skip]] (close-popovers! popovers skip))
        register-popover-fn (fn [p] (register-popover! popovers p))
        ;; Source-panel spec: the panel is built before the tabs, whose
        ;; jump opens it; its root joins the right-hand slot once the
        ;; inspector exists (*right, reset below).
        sp (source-pane/make-pane)
        *right (atom nil)
        ;; Where each jump into a tab started, newest last, for Cmd/Ctrl+[
        ;; (open-in-tab!, jump-back!).
        jump-back* (atom [])
        source-ctx (fn [t] {:ed (:ed t) :ev* ev* :status-label status-label :help-pop (:help-pop t)
                            :close-popovers-fn close-popovers-fn :sp sp :right @*right
                            :path (:path t) :sel* sel* :active? (fn [] (active? t))
                            :open-file! (fn [p] (open-file! @*tab-ctx p))
                            :active-tab active-tab :jump-back* jump-back*})
        ;; The window's evaluator when its help asks a server
        ;; (ev/ServerHelp): completion then asks off the GTK thread.
        server-ev (fn [] (let [e @ev*] (when (ev/server-help? e) e)))
        ;; Shared by every tab's completion and the console's: the running
        ;; program's namespace, read fresh at every lookup.
        completion-ns-fn (fn [] (ev/prompt-ns @ev*))
        ;; P4.T5: what each tab's completion is built with (jonquil.gtk.tab
        ;; adds its own :ed); close-others! is the registry-wide close, so
        ;; opening completion closes any other open popover.
        completion-opts {:ns-fn completion-ns-fn
                         :candidates-fn (fn [prefix text pos extra] (ev/complete @ev* prefix text pos extra))
                         :doc-fn (fn [sym text pos] (ev/lookup @ev* sym text pos))
                         :server-ev server-ev
                         :close-others! close-popovers-fn
                         :on-error (fn [msg] (set-status! status-label msg))}
        ;; P4.T6: a second jonquil.gtk.completion instance, on the
        ;; console's own input region -- :position :top (above the input
        ;; line, per probe 2, vs. the editor's own :bottom),
        ;; :snapshot-fn/:apply-op! overridden to cview/input-snapshot and
        ;; cview/apply-input-op! so refine!/commit! read and write the
        ;; input region instead of :ed's whole buffer (jonquil.gtk.
        ;; completion's own ns docstring). close-others!/on-error are the
        ;; SAME registry-wide fns the editor's instance uses, so opening
        ;; either instance still closes any other open P4 popover.
        repl-completion (completion/make-completion
                         (:view cv)
                         ;; No :ed -- :snapshot-fn/:apply-op! below replace
                         ;; every use the default closures would have made
                         ;; of it, so there's no editor for this instance
                         ;; to name.
                         {:ns-fn completion-ns-fn
                          :candidates-fn (fn [prefix text pos extra] (ev/complete @ev* prefix text pos extra))
                          :doc-fn (fn [sym text pos] (ev/lookup @ev* sym text pos))
                          :server-ev server-ev
                          :close-others! close-popovers-fn
                          :on-error (fn [msg] (set-status! status-label msg))
                          :position :top
                          :snapshot-fn (fn [] (cview/input-snapshot cv))
                          :apply-op! (fn [result] (cview/apply-input-op! cv result))})
        repl-complete-fn (fn [] (do-repl-complete! cv ev* repl-completion status-label))
        ;; the console's completion is caret-anchored too: scrolling the
        ;; Interactions pane follows or hides it, as a tab's editor does
        ;; for its popovers (wire-tab!)
        _ (let [follow (callable (fn [_adj _data]
                                   (popover/follow-caret! repl-completion)
                                   nil)
                                 [:pointer :pointer] :void)]
            (doseq [adj [(g/gtk-scrollable-get-vadjustment (:view cv))
                         (g/gtk-scrollable-get-hadjustment (:view cv))]]
              (g/g-signal-connect-data adj "value-changed" follow ffi/null ffi/null g/CONNECT-DEFAULT)))
        run-fn (fn [] (do-run! {:tab (active-tab) :cv cv :ev* ev* :sel* sel* :status-label status-label}))
        stop-fn (fn [] (do-stop! ev* cv))
        open-fn (fn [] (do-open! cv win (fn [p] (open-file! @*tab-ctx p))))
        save-fn (fn [] (let [t (active-tab)] (do-save! (:ed t) cv win (:path t))))
        save-as-fn (fn [] (let [t (active-tab)] (do-save-as! (:ed t) cv win (:path t))))
                ;; Tabs plan P2.T3: Cmd/Ctrl+W closes the active tab; on the last
        ;; one close-tab! closes the window, as Cmd/Ctrl+W always did.
        close-fn (fn [] (close-tab! @*tab-ctx (:id (active-tab))))
        new-tab-fn (fn [] (new-tab! @*tab-ctx {}))
        step-tab-fn (fn [delta]
                      (let [ts @tabs
                            i (.indexOf ^java.util.List (mapv :id ts) @active-id)]
                        (select-tab! @*tab-ctx (:id (nth ts (tabs/cycle-index (count ts) i delta))))))
        digit-tab-fn (fn [digit]
                       (let [ts @tabs]
                         (when-let [i (tabs/index-for-digit (count ts) digit)]
                           (select-tab! @*tab-ctx (:id (nth ts i))))))
        run-selection-fn (fn [] (do-run-selection! {:tab (active-tab) :cv cv :ev* ev* :sel* sel*
                                                    :status-label status-label}))
        ;; P2.T2: the inspector panel. h-paned is the new horizontal split
        ;; (editor+console keep their existing vertical paned as its start
        ;; child; the pane's own :root becomes its end child, wired below
        ;; once both exist). *pane is a forward reference the on-view/
        ;; on-request/on-def/on-close closures below close over -- pane
        ;; itself doesn't exist until make-pane returns, the same
        ;; atom-indirection *cv gives make-on-submit above.
        h-paned (g/gtk-paned-new g/ORIENTATION-HORIZONTAL)
        inspector-state (atom nil)
        inspecting? (atom false)
        ;; Self-test hook only (:inspector-last-args*): the args of the
        ;; last inspect! request this window actually sent, so a row can
        ;; compare a :start's own :cols against the pane's own measure.
        inspector-last-args (atom nil)
        *pane (atom nil)
        on-view (fn [reply]
                  (on-gui! (fn [] (on-inspect-view! @*pane inspector-state inspecting? cv status-label reply))))
        on-inspector-request
        (fn [[op args]]
          ;; Returns true only when the request was actually sent, so the
          ;; pane commits a key step's :pending/:selected only then.
          (boolean
           (try-inspect! inspecting?
                         (fn [] (reset! inspector-last-args args) (ev/inspect! @ev* op args on-view) true))))
        on-inspector-def
        (fn [name]
          (try-inspect! inspecting?
                        (fn []
                          (let [args {:name name}]
                            (reset! inspector-last-args args)
                            ;; :pending :keep, so on-reply's default (row 0)
                            ;; doesn't reset the selection: the spec's "the
                            ;; panel stays on the same view". The pane shares
                            ;; this atom, so :selected is the row on screen.
                            (swap! inspector-state assoc :pending :keep)
                            (ev/inspect! @ev* :def args on-view)))))
        on-inspector-close (fn [] (close-inspector-panel! @*right (:ed (active-tab))))
        pane (inspector-pane/make-pane {:on-request on-inspector-request
                                        :on-close on-inspector-close
                                        :on-def on-inspector-def
                                        :state inspector-state})
        _ (reset! *pane pane)
        slot (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        right {:win win :h-paned h-paned :slot slot :inspector-root (:root pane) :source-root (:root sp)
               :source-pane sp}
        _ (reset! *right right)
        inspect-last-fn (fn [] (inspect-last! ev* right pane inspector-state inspecting?
                                              inspector-last-args on-view status-label))
        inspect-form-fn (fn [] (inspect-form! {:tab (active-tab) :cv cv :ev* ev* :sel* sel* :status-label status-label
                                               :inspecting? inspecting? :inspect-last-fn inspect-last-fn}))
        source-jump-fn (fn [] (do-source-jump! (source-ctx (active-tab))))
        source-close-fn (fn [] (close-source-panel! right (:ed (active-tab))))
        source-back-fn (fn [] (source-pane/back! sp))
        source-open-fn (fn [] (do-source-open! {:sp sp :right right :active-tab active-tab
                                                :open-file! (fn [p] (open-file! @*tab-ctx p))}))
        jump-back-fn (fn [] (jump-back! (assoc (source-ctx (active-tab))
                                               :select-tab! (fn [id] (select-tab! @*tab-ctx id))
                                               :tab-open? (fn [id] (some? (tab-by-id tabs id))))))
        source-keys {:focused? (fn [] (source-pane/focused? sp win)) :close! source-close-fn
                     :back! source-back-fn :open! source-open-fn :jump-back! jump-back-fn}
        close-inspector-fn (fn [] (close-inspector-panel! right (:ed (active-tab))))
        toggle-focus-fn (fn [] (do-toggle-focus! win (:ed (active-tab)) cv))
        clear-console-fn (fn [] (do-clear-console! cv))
        clear-last-fn (fn [] (do-clear-last-output! cv))
        zoom-fn (fn [direction] (do-zoom! provider zoom-px theme-name direction))
        theme-ctx {:tabs tabs :cv cv :provider provider :zoom-px zoom-px :theme-name theme-name
                   :settings-path settings-path* :status-label status-label
                   :evaluating? evaluating-fn :theme-choice theme-choice
                   :system-dark-fn* system-dark-fn* :poll-armed? poll-armed?
                   :arm-count arm-count :closing? closing?}
        ;; Built once, as the inspector's note-clear timer is, because
        ;; callbacks keeps every callable for the life of the process.
        ;; Only this callback clears poll-armed?, when its tick returns 0
        ;; and GLib drops the timer.
        poll-cb (callable (fn [_data]
                            (let [r (poll-tick! theme-ctx)]
                              (when (zero? r) (reset! poll-armed? false))
                              r))
                          [:pointer] :int :on-throw 1)
        theme-ctx (assoc theme-ctx :poll-cb poll-cb)
        set-theme-fn (fn [k] (apply-choice! theme-ctx k))
        ;; TH.T5: the chord's/:cycle-theme!*'s action.
        cycle-theme-fn (fn [] (do-cycle-theme! theme-ctx))
        open-find-fn (fn [] (find-bar/open! (:fb (active-tab)) :find))
        open-replace-fn (fn [] (find-bar/open! (:fb (active-tab)) :replace))
        ;; P4.T7: the editor's and the console's own context menus (probe
        ;; 3) -- each item calls the EXACT SAME fn its key does, through
        ;; its command in jonquil.command-registry (slice S7). install-
        ;; context-menu! returns the GSimpleActionGroup, so :activate-
        ;; menu-action!*/:repl-activate-menu-action!* (below) can fire an
        ;; item directly, by name, for rows and the smoke.
        console-menu-group
        (install-context-menu! (:view cv)
                               (cmd-registry/menu-items cmd-registry/console-menu
                                                        {:complete! repl-complete-fn :clear-last-fn clear-last-fn})
                               (fn [msg] (set-status! status-label msg)))
        ;; Ruling 3: the ui's editor-menu hook fires the FIRST tab's menu
        ;; (tabs are built after this let), as the other per-tab ui keys do.
        activate-menu-action-fn (fn [name] (g/g-action-group-activate-action (:menu-group (first @tabs)) name ffi/null))
        repl-activate-menu-action-fn
        (fn [name] (g/g-action-group-activate-action console-menu-group name ffi/null))
        ;; File-tree plan P2.T2: the tree on the left, its toggle first in
        ;; the toolbar, and the outer paned that holds it beside h-paned.
        *open-folder (atom nil)
        ;; True while the starting visibility is applied, so the toggled
        ;; handler doesn't save it as if a person had chosen it.
        applying-start? (atom false)
        *folder-result (atom nil)
        tree-pane (file-tree-pane/make-pane {:on-open (fn [p] (open-file! @*tab-ctx p))
                                             :on-open-folder (fn [] (when-let [f @*open-folder] (f)))
                                             :on-error (fn [msg] (set-status! status-label msg))
                                             ;; File-ops plan T4.
                                             :on-new-file (fn [dir n] (tree-new-file! @*tab-ctx dir n))
                                             :on-new-folder (fn [dir n] (tree-new-folder! @*tab-ctx dir n))
                                             :on-rename (fn [p n] (tree-rename! @*tab-ctx p n))
                                             :on-trash (fn [p] (tree-trash! @*tab-ctx p))})
        files-btn (g/gtk-toggle-button-new-with-label "☰ Files")
        tree-paned (g/gtk-paned-new g/ORIENTATION-HORIZONTAL)
        ;; slice S8: the palette lists the registry's commands and opens on
        ;; the active tab, whose palette runs the picked one
        open-palette-fn (fn [] (let [plugins @plugin-registry/live]
                                 (palette/open! (:palette (active-tab))
                                                (pal-data/entries
                                                 {:commands (plugin-registry/all-commands plugins)
                                                  :keymap {:window (plugin-registry/window-bindings plugins)
                                                           :editor (plugin-registry/editor-bindings plugins)}
                                                  :os (System/getProperty "os.name")}))))
        ;; named, so each tab's palette can build the same per-key ctx
        ;; (window-key-ctx) a window chord gets
        window-key-args {:run-fn run-fn :stop-fn stop-fn :open-fn open-fn :save-fn save-fn
                                      :save-as-fn save-as-fn :close-fn close-fn
                                      :run-selection-fn run-selection-fn :toggle-focus-fn toggle-focus-fn
                                      :clear-console-fn clear-console-fn :clear-last-fn clear-last-fn
                                      :zoom-fn zoom-fn :evaluating? evaluating-fn
                                      :win win :active-tab active-tab :open-find-fn open-find-fn
                                      :open-replace-fn open-replace-fn :theme-cycle-fn cycle-theme-fn
                                      :close-popovers-fn close-popovers-fn
                                      :inspect-last-fn inspect-last-fn :inspect-form-fn inspect-form-fn
                                      :pane pane :close-inspector-fn close-inspector-fn
                                      :status-label status-label :source-keys source-keys
                                      :new-tab-fn new-tab-fn :step-tab-fn step-tab-fn
                                      :digit-tab-fn digit-tab-fn
                                      :tree-keys {:focused? (fn [] (file-tree-pane/focused? tree-pane win))
                                                  :leave! (fn [] (editor/focus! (:ed (active-tab))))
                                                  :menu-open? (fn [] (:open? (file-tree-pane/menu-state tree-pane)))
                                                  :close-menu! (fn [] (g/gtk-popover-popdown (:popover tree-pane)))}
                                      :toggle-tree-fn (fn [] (g/gtk-toggle-button-set-active
                                                              files-btn
                                                              (if (zero? (g/gtk-toggle-button-get-active files-btn)) 1 0)))
                                      :open-palette-fn open-palette-fn
                                      :reload-plugins-fn (fn [] (plugin-load! {:settings-path @settings-path*
                                                                               :cv cv}))}
        window-key-fn (on-window-key window-key-args)
        toolbar (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        run-btn (g/gtk-button-new-with-label "Run (F5)")
        stop-btn (g/gtk-button-new-with-label "Stop (Esc)")
        open-btn (g/gtk-button-new-with-label "Open…")
        save-btn (g/gtk-button-new-with-label "Save…")
        reset-chk (g/gtk-check-button-new-with-label "Reset on Run")
        ;; The tick an attach runtime took down (dialect/reset-toggle).
        reset-state (atom {})
        ;; P3.T5: the status bar becomes a horizontal box -- the P2 label
        ;; on the left (hexpand, so it claims the box's spare width) and
        ;; the new Ln/Col label on the right.
        status-box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 6)
        paned (g/gtk-paned-new g/ORIENTATION-VERTICAL)
        vbox (g/gtk-box-new g/ORIENTATION-VERTICAL 0)
        keyctl (g/gtk-event-controller-key-new)]
    (g/gtk-box-append toolbar files-btn)
    (g/gtk-box-append toolbar run-btn)
    (g/gtk-box-append toolbar stop-btn)
    (g/gtk-box-append toolbar open-btn)
    (g/gtk-box-append toolbar save-btn)
    (g/gtk-box-append toolbar reset-chk)
    (g/gtk-check-button-set-active reset-chk 1)
    (g/gtk-widget-set-vexpand paned 1)
    ;; Tabs plan P2.T2: a notebook of tabs is the paned's start child; each
    ;; page is a tab's own find bar over its editor (jonquil.gtk.tab). The
    ;; strip stays hidden while there's one tab, so a one-file window looks
    ;; as it always did.
    (g/gtk-notebook-set-show-tabs notebook 0)
    (g/gtk-notebook-set-show-border notebook 0)
    (g/gtk-notebook-set-scrollable notebook 1)
    (g/gtk-widget-set-vexpand notebook 1)
    (g/gtk-paned-set-start-child paned notebook)
    (g/gtk-paned-set-end-child paned (:scroll cv))
    (g/gtk-paned-set-position paned 430)
    ;; P2.T2: the editor/console paned becomes h-paned's start child; the
    ;; inspector panel is its end child, hidden until the first open
    ;; (show-inspector-panel!), and kept at a fixed width when the window
    ;; resizes (never grows/shrinks with it, never squeezed below its own
    ;; WIDTH-REQUEST).
    (g/gtk-widget-set-vexpand h-paned 1)
    (g/gtk-paned-set-start-child h-paned paned)
    (g/gtk-widget-set-vexpand (:root pane) 1)
    (g/gtk-widget-set-vexpand (:root sp) 1)
    (g/gtk-box-append slot (:root pane))
    (g/gtk-box-append slot (:root sp))
    (g/gtk-paned-set-end-child h-paned slot)
    (g/gtk-paned-set-resize-end-child h-paned 0)
    (g/gtk-paned-set-shrink-end-child h-paned 0)
    (g/gtk-widget-set-visible (:root pane) 0)
    (g/gtk-widget-set-visible (:root sp) 0)
    (g/gtk-widget-set-visible slot 0)
    (g/gtk-box-append vbox toolbar)
    (g/gtk-paned-set-start-child tree-paned (:root tree-pane))
    (g/gtk-paned-set-end-child tree-paned h-paned)
    (g/gtk-paned-set-resize-start-child tree-paned 0)
    (g/gtk-paned-set-shrink-start-child tree-paned 0)
    (g/gtk-widget-set-vexpand tree-paned 1)
    (g/gtk-box-append vbox tree-paned)
    (g/gtk-widget-set-hexpand status-label 1)
    (g/gtk-box-append status-box status-label)
    (g/gtk-box-append status-box dialect-label)
    (g/gtk-box-append status-box line-col-label)
    ;; Room at both ends, so "Ln N, Col N" doesn't touch the window's right
    ;; edge (P4 parked item) and the left matches it.
    (g/gtk-widget-set-margin-start status-box STATUS-MARGIN)
    (g/gtk-widget-set-margin-end status-box STATUS-MARGIN)
    (g/gtk-box-append vbox status-box)
    (g/gtk-window-set-child win vbox)
    (g/gtk-window-set-default-size win 900 700)
    ;; P3.T3: one shared GtkCssProvider for the display, added once; both
    ;; views get the jonquil-code CSS class so a single reload resizes both
    ;; live (probe 4).
    ;; Each tab's view gets the class in jonquil.gtk.tab/make-tab!.
    (g/gtk-widget-add-css-class (:view cv) "jonquil-code")
    (g/gtk-style-context-add-provider-for-display
     (g/gdk-display-get-default) provider g/STYLE-PROVIDER-PRIORITY-APPLICATION)
    ;; TH.T5: the saved theme, in place of TH.T4's fixed theme/default-theme.
    (source-pane/apply-theme! sp (theme/theme @theme-name))
    (add-watch theme-name ::source-pane (fn [_ _ _ k] (source-pane/apply-theme! sp (theme/theme k))))
    (g/g-signal-connect-data run-btn "clicked"
                             (callable (contain-click status-label "run" (fn [_] (run-fn))) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data stop-btn "clicked"
                             (callable (contain-click status-label "stop" (fn [_] (stop-fn))) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data open-btn "clicked"
                             (callable (contain-click status-label "open" (fn [_] (open-fn))) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data save-btn "clicked"
                             (callable (contain-click status-label "save" (fn [_] (save-fn))) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data reset-chk "toggled"
                             (callable (contain-click status-label "reset on run"
                                                      (fn [btn]
                                                        (ev/set-mode! @ev*
                                                                      (if (zero? (g/gtk-check-button-get-active btn))
                                                                        :accumulate :reset))))
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-event-controller-set-propagation-phase keyctl g/GTK-PHASE-CAPTURE)
    (g/g-signal-connect-data keyctl "key-pressed"
                             (callable window-key-fn [:pointer :uint :uint :uint :pointer] :int)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller win keyctl)
    (g/g-signal-connect-data win "close-request"
                             ;; on-throw 1 refuses the close: a window that
                             ;; may hold unsaved text stays open.
                             (callable (close-request-fn {:tabs tabs :select! (fn [t] (select-tab! @*tab-ctx (:id t)))
                                                          :cv cv :win win :app app
                                                          :confirmed-close? confirmed-close?
                                                          :suppress-close? suppress-close? :closing? closing?
                                                          :popovers popovers :ev* ev*
                                                          :save-tree-width!
                                                          (fn [] (when (pos? (g/gtk-widget-get-visible (:root tree-pane)))
                                                                   (settings/save-settings!
                                                                    @settings-path*
                                                                    {:file-tree {:visible? true
                                                                                 :width (g/gtk-paned-get-position tree-paned)}})))
                                                          ;; Live saving stops first: nothing after the
                                                          ;; quit's own save may write a shorter list.
                                                          ;; A quit before startup's reopen is done
                                                          ;; stops it and saves nothing, since the
                                                          ;; tabs open are only part of the list.
                                                          :save-session! (fn [] (let [c @*tab-ctx
                                                                                      restoring? (some-> c :session-restoring? deref)]
                                                                                  (some-> c :session-live? (reset! false))
                                                                                  (some-> c :session-restoring? (reset! false))
                                                                                  (when-not restoring? (save-session! c))))})
                                       [:pointer :pointer] :int :on-throw 1)
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
    (cview/set-error-fn! cv (fn [msg] (set-status! status-label msg)))
    (editor/set-jump-fn! (:editor sp) source-jump-fn)
    (editor/set-error-fn! (:editor sp) (fn [msg] (source-pane/report! sp msg)))
    (source-pane/set-handlers! sp {:on-open source-open-fn :on-close source-close-fn})
    ;; I5: joins the SAME registry, so Escape closes the def prompt
    ;; before it can fall through to Stop (ruling 8).
    (register-popover-fn (:def-popover pane))
    (wire-completion! repl-completion repl-complete-fn status-label register-popover-fn
                      (fn [fns] (cview/set-completion-fns! cv fns))
                      (fn [f] (cview/on-input-changed! cv f))
                      (fn [f] (cview/on-focus-lost! cv f)))
    (let [sel-ctx {:cv cv :status-label status-label :dialect-label dialect-label :sel* sel* :ev* ev*
                   :reset-chk reset-chk :reset-state reset-state :right right :inspector-state inspector-state
                   :active-ed (fn [] (:ed (active-tab)))
                   :mode-fn (fn [] (if (zero? (g/gtk-check-button-get-active reset-chk)) :accumulate :reset))}
          ;; A file argument that doesn't exist opens an empty editor with
          ;; the path remembered anyway, so Save creates it -- unlike the
          ;; Open button's dialog result, this is the documented normal
          ;; case, not an error to report.
          text (when file (try (slurp file) (catch :default _ nil)))
          ;; File-tree plan P2.T2: a save (or a path change) under the root
          ;; expands to the file and highlights it when its tab is active.
          tree-saved! (fn [t]
                        (let [p @(:path t)
                              m @(:state tree-pane)]
                          (when (and p (ft/under-root? m p))
                            (file-tree-pane/show-model! tree-pane (ft/expand-to m p))
                            (when (active? t) (file-tree-pane/select-path! tree-pane p)))))
          tab-ctx {:win win :cv cv :ev* ev* :sel* sel* :status-label status-label
                   :line-col-label line-col-label :close-popovers-fn close-popovers-fn
                   :register-popover-fn register-popover-fn :active? active? :source-ctx source-ctx
                   :sel-ctx sel-ctx :settings-path* settings-path* :inspect-last-fn inspect-last-fn
                   :inspect-form-fn inspect-form-fn :open-find-fn open-find-fn
                   :open-replace-fn open-replace-fn :tabs tabs :next-id next-id :notebook notebook
                   :theme-name theme-name :completion-opts completion-opts
                   :active-id active-id :active-tab active-tab :popovers popovers
                   :tree-pane tree-pane :tree-saved! tree-saved! :folder-result* *folder-result
                   :session-live? (atom false) :session-last (atom nil) :session-error (atom nil)
                   :session-restoring? (atom false)
                   :window-key-args window-key-args :repl-complete-fn repl-complete-fn}
          first-tab (add-tab! tab-ctx {:path file :text (or text "")})
          tree-settings (:file-tree (settings/load-settings @settings-path*))
          ;; Open Folder...'s result: re-roots the tree; the window's project
          ;; follows only while its one tab is an untouched Untitled one.
          folder-result! (fn [{p :path err :error :as r}]
                           (cond
                             p (do (file-tree-pane/show-model! tree-pane (ft/load-model p))
                                   (let [ts @tabs
                                         t (first ts)]
                                     (when (and (= 1 (count ts))
                                                (tabs/untouched-untitled? {:path @(:path t) :text (editor/editor-text (:ed t))}))
                                       (apply-selection! sel-ctx (ft/project-selection
                                                                  p (:dialect (settings/load-settings @settings-path*)))))))
                             (dialog-dismissed? r) nil
                             :else (cview/append! cv :error (str "; open folder: " err "\n"))))]
      ;; Spike §1: switch-page fired inside that first append, before the
      ;; tab was in tabs, so the tab is made active here.
      (reset! active-id (:id first-tab))
      (reset! *tab-ctx tab-ctx)
      (reset! *folder-result folder-result!)
      ;; P5a: Open With and Dock drops. A directory re-roots the tree like an
      ;; Open Folder result and shows it; a file opens in a tab, and one that can't be read
      ;; goes to the status line while the rest still open.
      (reset! current-report (fn [text] (set-status! status-label text)))
      (reset! current-open-paths
              (fn [paths]
                (doseq [p paths]
                  (try
                    (if (.isDirectory (java.io.File. ^String p))
                      (do (folder-result! {:path p})
                          ;; The tree now shows this folder, so show the tree
                          ;; too; the toggle handler saves it as a choice.
                          (g/gtk-toggle-button-set-active files-btn 1))
                      (when-let [err (:error (open-file! @*tab-ctx p))]
                        (set-status! status-label (str "Couldn't open " (.getName (java.io.File. ^String p)) ": " err))))
                    (catch :default e
                      (set-status! status-label (str "Couldn't open " p ": " (ex-message e))))))))
      ;; File-tree plan P2.T2: the tree's root, starting width and
      ;; visibility (saved, else shown only for a directory launch, ruling 3),
      ;; Open Folder..., and the toggle's handler -- the one place that shows,
      ;; hides and saves (spike §4).
      (file-tree-pane/show-model! tree-pane (ft/load-model (ft/root-for {:dir dir :file file :cwd cwd})))
      (reset! *open-folder (fn [] (file-dialog! :folder win
                                                (fn [r] (try (folder-result! r)
                                                             (catch :default e
                                                               (cview/append! cv :error (str "; open folder: " (ex-message e) "\n")))))
                                                (:root @(:state tree-pane)))))
      (g/gtk-paned-set-position tree-paned (or (:width tree-settings) 240))
      (g/g-signal-connect-data files-btn "toggled"
                               (callable (contain-click
                                          status-label "files"
                                          (fn [btn]
                                            (let [show? (pos? (g/gtk-toggle-button-get-active btn))
                                                  width (g/gtk-paned-get-position tree-paned)
                                                  had-focus? (file-tree-pane/focused? tree-pane win)]
                                              (g/gtk-widget-set-visible (:root tree-pane) (if show? 1 0))
                                              ;; Hiding the focused tree leaves focus nowhere;
                                              ;; give it to the editor (final review finding 4).
                                              (when (and (not show?) had-focus?) (editor/focus! (:ed (active-tab))))
                                              ;; Only a person's toggle is a choice worth saving;
                                              ;; the starting state isn't (finding 6).
                                              (when-not @applying-start?
                                                (when-let [err (settings/save-settings!
                                                                @settings-path* {:file-tree {:visible? show? :width (max 1 width)}})]
                                                  (cview/append! cv :system (str "; settings: " err "\n")))))))
                                         [:pointer :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT)
      (g/gtk-widget-set-visible (:root tree-pane) 0)
      (when (if (some? (:visible? tree-settings)) (:visible? tree-settings) (boolean dir))
        (reset! applying-start? true)
        (try (g/gtk-toggle-button-set-active files-btn 1)
             (finally (reset! applying-start? false))))
      (g/g-signal-connect-data notebook "switch-page"
                               (callable (switch-page-fn tab-ctx) [:pointer :pointer :uint :pointer] :void)
                               ffi/null ffi/null g/CONNECT-DEFAULT)
      (load-and-apply-theme! theme-ctx @settings-path*)
      ;; I6: same non-UTF-8 warning as open-path!.
      (when (and text (str/includes? text "�"))
        (cview/append! cv :system
                       "This file isn't valid UTF-8; unreadable bytes show as � and saving will replace them.\n"))
      (try
        (show-selection! cv dialect-label @sel*)
        ;; cljd plan P2.T4: the starting evaluator's connection, label and
        ;; Reset on Run, as apply-selection! does for every later one.
        (watch-connection! @ev* ev* sel* dialect-label cv)
        (apply-reset-toggle! reset-chk reset-state @sel*)
        (g/gtk-label-set-text dialect-label (dialect-text @sel* @ev*))
        (refresh-prompt! cv ev*)
        (catch :default e
          (cview/append! cv :error (str "; project: " (ex-message e) "\n"))))
      (start-and-report! cv status-label @ev* @sel*)
      ;; Session-tabs plan Task 2: a launch that named no file reopens the
      ;; tabs open at the last quit in this project. The entry, and any
      ;; re-root, come now, so the tree shows its folder from the first
      ;; frame. The files open after the window is up (reopen-later!).
      (let [entry (when-not file
                    (try (session-entry! tab-ctx {:reroot? true})
                         (catch :default e
                           (cview/append! cv :error (str "; reopen tabs: " (ex-message e) "\n"))
                           nil)))
            ;; The launch's tabs are open, so from here on every change to
            ;; them is saved as it happens (session-changed!). The state the
            ;; launch ends in isn't written: a launch on one file would
            ;; otherwise replace the folder's remembered tabs before
            ;; anything changed. A re-root alone doesn't save either, for the
            ;; same reason; the next tab change does.
            live! (fn []
                    (reset! (:session-last tab-ctx) (session-snapshot tab-ctx))
                    (reset! (:session-live? tab-ctx) true))]
        ;; P5a: say where the child tools' PATH came from. The wait for the
        ;; lookup is off the GTK thread; the append goes through on-gui!, and
        ;; a window closed meanwhile must not throw out of the future.
        (future
          (try (when-let [line (login-path/console-line)]
                 (on-gui! (fn [] (cview/append! cv :system (str line "\n")))))
               (catch :default _ nil)))
        (let [t (active-tab)]
          (update-title! (:ed t) win (:path t))
          (g/gtk-window-present win)
          (editor/focus! (:ed t)))
        (if (seq (:tabs entry))
          (reopen-later! tab-ctx entry
                         (fn []
                           (try
                             (let [t (active-tab)]
                               (update-title! (:ed t) win (:path t))
                               (editor/focus! (:ed t)))
                             (finally (live!)))))
          (live!))))
    ;; plugins P3: the user's plugins load off the GTK thread once the
    ;; window is up; keys pressed before the swap see none yet
    (reset! plugin-console (fn [line] (on-gui! #(cview/append! cv :system (str "; " line "\n")))))
    ;; plugins P5: the effect functions a plugin's :run command reaches,
    ;; each a turn on the GTK thread (it runs on a worker) acting on the
    ;; tab with that id, which may have closed since the command started
    (let [turn (fn [f] (plugin-registry/gui-turn on-gui! f 5000))
          ;; as the MCP's stop: never interrupt an idle evaluator (an attach
          ;; evaluator would detach)
          stop-eval! (fn [] (turn #(when (ev/evaluating? @ev*) (ev/interrupt! @ev*))))
          ;; Jonquil's own lines during a plugin's eval reach the console
          notice! (fn [kind text] (on-gui! #(cview/append! cv kind text)))
          tab-of (fn [id] (or (some #(when (= id (:id %)) %) @tabs)
                              (throw (ex-info "that tab was closed" {}))))]
      (reset! plugin-registry/effects
              {:active-tab (fn [] (:id (active-tab)))
               :print! (fn [s] (turn #(cview/append! cv :system (str s "\n"))))
               :status! (fn [s] (turn #(g/gtk-label-set-text status-label (str s))))
               :buffer-text (fn [id] (turn #(editor/editor-text (:ed (tab-of id)))))
               :replace-text! (fn [id text]
                                (turn #(let [ed (:ed (tab-of id))]
                                         (when-not (editor/apply-op! ed (mcp-window/replace-op (editor/editor-text ed) text))
                                           (throw (ex-info "that tab is read-only" {}))))))
               :open! (fn [path]
                        (turn #(let [r (open-file! @*tab-ctx (str path))]
                                 (when (and (map? r) (:error r)) (throw (ex-info (str (:error r)) {})))
                                 (:id (active-tab)))))
               ;; plugins P5b: both run through the tab's id, on the
               ;; plugin's worker; await-eval! stops one that times out
               :eval! (fn [id code opts]
                        (plugin-registry/await-eval!
                         {:timeout-ms (:timeout-ms opts)
                          :interrupt! stop-eval!
                          :on-notice notice!
                          :fire (fn [cbs]
                                  ;; the dialect is checked on the evaluator the
                                  ;; eval goes to, in the same turn (review I5)
                                  (turn #(let [t (tab-of id)
                                               e @ev*]
                                           (when-not (plugin-registry/quiet-capable? e)
                                             (throw (ex-info "p/eval! works with Jolt for now; p/repl! works with every dialect" {})))
                                           (ev/eval! e code (assoc cbs :quiet (str "jonquil-quiet-" (random-uuid) ":")
                                                                   :ns-source (editor/editor-text (:ed t))
                                                                   :owner id)))))}))
               :repl! (fn [id code opts]
                        (dissoc (plugin-registry/await-eval!
                                 {:timeout-ms (:timeout-ms opts)
                                  :interrupt! stop-eval!
                                  ;; the person can answer a read at the console
                                  :input-stops? false
                                  :on-notice notice!
                                  :fire (fn [{:keys [on-output on-complete]}]
                                          (turn #(let [t (tab-of id)
                                                       busy (atom nil)]
                                                   (mcp-eval! {:tab t :cv cv :ev* ev* :status-label status-label}
                                                              code {:ns-source (editor/editor-text (:ed t)) :owner id}
                                                              (fn [m]
                                                                (if-let [w (:busy m)]
                                                                  (reset! busy w)
                                                                  (let [{:keys [result output]} (:done m)]
                                                                    (on-output :out output)
                                                                    (on-complete result)))))
                                                   @busy)))})
                                :err))
               :report! (fn [line]
                          (on-gui! #(do (cview/append! cv :system (str "; " line "\n"))
                                        (g/gtk-label-set-text status-label (str "; " line)))))}))
    (when-not (false? plugins?)
      (plugin-load! {:settings-path @settings-path* :cv cv}))
    ;; saving a plugin file reloads that plugin, and any save fires the
    ;; :after-save hooks (P6) on the saved tab; save-to! runs on the GTK
    ;; thread, so the tab lookup is safe here
    (reset! on-saved (fn [path ed]
                       (when (and (not (false? plugins?))
                                  (plugin-loader/plugin-file? (plugin-loader/plugins-dir @settings-path*) path))
                         (plugin-load! {:settings-path @settings-path* :cv cv :only path}))
                       (plugin-hooks/run-hooks! {:point :after-save
                                                 :tab (some #(when (identical? ed (:ed %)) (:id %)) @tabs)
                                                 :event {:path path}})))
    (when on-ready
      ;; Ruling 3 (tabs plan): the per-tab keys below are the FIRST tab's
      ;; objects, so every existing row keeps working unchanged; rows that
      ;; need another tab use :tabs* and :active-tab*. Actions that act on
      ;; "the window's file" (:open-path!, :save-to!, the simulated dialog
      ;; results, :close-choice!) use the active tab.
      (let [{:keys [ed fb help-pop full-doc-pop rename-pop completion palette actions]} (first @tabs)]
        (on-ready {:editor ed :console cv :session session :status-label status-label :window win
                 ;; Tabs plan P2.T2: the notebook, the tab list and the
                 ;; active tab, read live.
                   :notebook notebook
                   :tabs* (fn [] @tabs)
                 ;; File-tree plan P2.T2: the tree, its toggle, Open Folder's
                 ;; result handler (for rows), and the outer paned.
                   :file-tree tree-pane
                   :files-btn files-btn
                   :tree-paned tree-paned
                   :toggle-file-tree!* (fn [] (g/gtk-toggle-button-set-active
                                               files-btn (if (zero? (g/gtk-toggle-button-get-active files-btn)) 1 0)))
                   :file-tree-visible?* (fn [] (pos? (g/gtk-widget-get-visible (:root tree-pane))))
                   :simulate-folder-result! (fn [r] (@*folder-result r))
                 ;; File-ops plan T4: the tree's menu, as a right click, a
                 ;; button and Return in the name entry drive it, and the
                 ;; trash alert's decision with a synthetic {:index n}, as
                 ;; :close-choice! below.
                   :tree-menu!* (fn [path] (file-tree-pane/open-menu! tree-pane path))
                   :tree-press!* (fn [label] (file-tree-pane/press-item! tree-pane label))
                   :tree-submit!* (fn [text] (file-tree-pane/submit-name! tree-pane text))
                   :tree-menu-state* (fn [] (file-tree-pane/menu-state tree-pane))
                   :trash-choice! (fn [path result] (trash-choice! @*tab-ctx path result))
                   :open-paths! (fn [paths] (@current-open-paths paths))
                   :app app
                   :active-tab* active-tab
                 ;; Tabs plan P2.T3: the tab operations, exactly what the
                 ;; keys, the toolbar and the tabs' close buttons call.
                   :new-tab!* (fn [] (new-tab! @*tab-ctx {}))
                   :open-file! (fn [p] (open-file! @*tab-ctx p))
                   :select-tab! (fn [id] (select-tab! @*tab-ctx id))
                   :close-tab! (fn [id] (close-tab! @*tab-ctx id))
                   :close-tab-choice! (fn [id result] (close-tab-choice! @*tab-ctx id result))
                   :line-col-label line-col-label
                 ;; D1.T4: the window's evaluator and dialect selection, read
                 ;; live at call time (D2.T5 swaps ev*), and the dialect
                 ;; label itself.
                   :evaluator* (fn [] @ev*)
                   :selection* (fn [] @sel*)
                   :dialect-label dialect-label
                 ;; cljd plan P2.T4: the Reset on Run checkbox, for rows
                 ;; that read its sensitivity and tooltip.
                   :reset-check reset-chk
                   :find-bar fb
                   :run!* run-fn :stop!* stop-fn
                 ;; mcp2: notifying hooks for the MCP server, each calls
                 ;; notify once and returns an output reader.
                   :mcp-run!* (fn [notify]
                                (do-run! {:tab (active-tab) :cv cv :ev* ev* :sel* sel*
                                          :status-label status-label}
                                         notify))
                   :mcp-eval!* (fn [text notify]
                                 (let [t (active-tab)]
                                   (mcp-eval! {:tab t :cv cv :ev* ev* :status-label status-label}
                                              text {:hook-source :repl :hook-tab (:id t)} notify)))
                   :mcp-run-selection!* (fn [start end notify]
                                          (mcp-run-selection! {:tab (active-tab) :cv cv :ev* ev* :sel* sel*
                                                               :status-label status-label}
                                                              start end notify))
                   :open-path! (fn [p]
                                 (let [{ed :ed path :path :keys [discard-warned?]} (active-tab)]
                                   (guarded-open! ed cv discard-warned?
                                                  (fn [] (open-path! ed cv path p) (update-title! ed win path)))))
                   :save-to! (fn [p] (let [{ed :ed path :path} (active-tab)]
                                       (save-to! ed cv path p) (update-title! ed win path)))
                 ;; P3.T3 self-test hooks: the exact fns the F6/Cmd+E/Cmd+L/
                 ;; zoom key chords call, so rows can drive them directly.
                   :run-selection!* run-selection-fn
                 ;; cljd plan P3.T2: Run Form's own fn, the one Cmd/Ctrl+Enter
                 ;; and the menu item call.
                   :run-form!* (:run-form! actions)
                   :toggle-focus!* toggle-focus-fn
                   :clear-console!* clear-console-fn
                 ;; Cmd/Ctrl+Shift+L's own fn, the one that clears only the
                 ;; last entry's block -- same self-test-hook shape as
                 ;; :clear-console!* just above.
                   :clear-last-output!* clear-last-fn
                   :zoom!* zoom-fn
                 ;; TH.T4 self-test hooks: applies a theme (never saves --
                 ;; TH.T5's job), reads back the active theme name, and
                 ;; reads back the shared CSS provider's OWN serialisation
                 ;; (g/css-provider-string asks the provider itself, so a
                 ;; row can't read a copy that drifted from it).
                   :set-theme!* set-theme-fn
                   :theme-name* (fn [] @theme-name)
                 ;; Auto theme: the choice (:auto or a theme key), repoints
                 ;; the system-appearance fn, runs one poll tick by hand
                 ;; (returns its int) and reads how many poll timers were armed.
                   :theme-choice* (fn [] @theme-choice)
                   :system-dark-fn!* (fn [f] (reset! system-dark-fn* f))
                   :poll-appearance!* (fn [] (poll-tick! theme-ctx))
                   :poll-arm-count* (fn [] @arm-count)
                   :poll-armed?* (fn [] @poll-armed?)
                   :code-css* (fn [] (g/css-provider-string provider))
                 ;; TH.T5: the chord's own action, the settings path this
                 ;; window reads/writes, and the exact startup-load fn
                 ;; activate! itself just ran (so a row can drive the same
                 ;; path against a synthetic settings file).
                   :cycle-theme!* cycle-theme-fn
                   :settings-path* (fn [] @settings-path*)
                   :load-settings!* (fn [p] (load-and-apply-theme! theme-ctx p))
                 ;; TH.T5 self-test hook only (row-theme-save-failure-
                 ;; reported): repoints the window's own settings path so a
                 ;; save can be forced to fail without touching the real
                 ;; settings file or racing the filesystem.
                   :settings-path!* (fn [p] (reset! settings-path* p))
                 ;; Session-tabs plan Task 2: what the real quit saves and
                 ;; what a launch with no file restores (without re-rooting).
                   :save-session!* (fn [] (save-session! @*tab-ctx))
                   :restore-session!* (fn [] (restore-session! @*tab-ctx {:reroot? false}))
                 ;; Live saving on or off, so a row can stand in for a quit
                 ;; and a new launch (off until that launch's restore).
                   :session-live!* (fn [on?] (reset! (:session-live? @*tab-ctx) on?))
                 ;; P4.T3: the window's own popover registry -- close-
                 ;; popovers-fn is exactly what the keymap's Escape binding calls;
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
                   :quick-doc!* (:quick-doc! actions)
                   :jump!* (:jump! actions)
                   :help-popover help-pop
                 ;; P3.T2: the full-doc popover a second quick-doc press
                 ;; over the same target shows in (ruling 11) -- rows read
                 ;; it back via jonquil.gtk.popover/open? and help-popover/
                 ;; full-doc-text, same as :help-popover above.
                   :full-doc-popover full-doc-pop
                 ;; P4.T7: the ui's own rename actions -- rename!* is the
                 ;; exact fn editor.clj's own Shift+F6/F2 clauses call
                 ;; (set-rename-fn!, above); rename-to!* is the exact fn
                 ;; the popover entry's own "activate" signal calls, for
                 ;; rows to drive without a synthesized keystroke; and the
                 ;; rename popover itself, for rows to read back via
                 ;; jonquil.gtk.popover/open? and help-popover/rename-
                 ;; entry-text/rename-problem-text.
                   :rename!* (:rename! actions)
                   :rename-to!* (:rename-to! actions)
                   :rename-popover rename-pop
                 ;; P4.T7: fires a context-menu item by its own unprefixed
                 ;; action name (\"jump\", \"rename\", \"toggle-comment\",
                 ;; ...) through g_action_group_activate_action on the
                 ;; editor's/the console's own GSimpleActionGroup --
                 ;; install-context-menu!'s own docstring on why that's
                 ;; equivalent to activating the prefixed \"p4.<name>\".
                   :activate-menu-action!* activate-menu-action-fn
                 ;; slice S8: the first tab's command palette, and what
                 ;; Cmd/Ctrl+Shift+P calls to open it
                   :palette palette
                   :open-palette!* open-palette-fn
                 ;; plugins P3: load the plugin folder now (call it off the
                 ;; GTK thread; it returns the console lines), and the folder
                   :load-plugins!* (fn [] (plugin-load-now! {:settings-path @settings-path* :cv cv}))
                   :reload-plugins!* (fn [] (plugin-load-now! {:settings-path @settings-path* :cv cv}))
                   :plugins-dir* (fn [] (plugin-loader/plugins-dir @settings-path*))
                   :repl-activate-menu-action!* repl-activate-menu-action-fn
                 ;; P4.T5: the ui's own completion action -- the exact fn
                 ;; editor.clj's own Ctrl+Space clause calls -- and the
                 ;; completion popover itself, for rows to read back via
                 ;; jonquil.gtk.popover/open? and jonquil.gtk.completion/
                 ;; state.
                   :complete!* (:complete! actions)
                   :completion completion
                 ;; P4.T6: the ui's own console-facing completion action --
                 ;; the exact fn console-view's own Ctrl+Space clause calls
                 ;; -- and the console's completion popover itself, same
                 ;; reasoning as :complete!*/:completion above.
                   :repl-complete!* repl-complete-fn
                   :repl-completion repl-completion
                 ;; P2.T2: the inspector panel map (P2.T1's make-pane
                 ;; return, including its own :on-def -- rows drive the
                 ;; def prompt through it directly), the window's own
                 ;; Cmd/Ctrl+I and Cmd/Ctrl+Shift+I actions (also what the
                 ;; two new editor-menu items call), a reader for the
                 ;; window's own inspector-state atom, and a self-test-only
                 ;; hook naming the args of the last inspect! request this
                 ;; window actually sent (ruling 9's own :inspector-last-
                 ;; args* -- row-inspect-cols-match compares a :start's own
                 ;; :cols against the pane's own measure).
                   :inspector pane
                   :inspect-last!* inspect-last-fn
                   :inspect-form!* inspect-form-fn
                   :inspector-state* (fn [] @inspector-state)
                   :inspector-last-args* (fn [] @inspector-last-args)
                 ;; Source-panel spec: the jonquil.gtk.source-pane map, the
                 ;; GtkBox holding it and the inspector in the right-hand
                 ;; slot, and the window's own F12-inside/Open-in-window/
                 ;; back/close actions -- exactly what the panel's own keys
                 ;; and buttons call.
                   :source-pane sp
                   :right-slot slot
                   :source-jump!* source-jump-fn
                   :source-open!* source-open-fn
                   :source-back!* source-back-fn
                   :jump-back!* jump-back-fn
                   :source-close!* source-close-fn
                 ;; M5/M4 self-test hook: the exact fn the "key-pressed"
                 ;; signal calls, so a row can invoke it directly with a
                 ;; synthetic keyval/state -- an ordinary function call, not
                 ;; a synthesized OS keystroke.
                   :handle-window-key! window-key-fn
                 ;; M2 self-test hook: the exact dialog-result decisions,
                 ;; driven with a synthetic result map shaped like
                 ;; g/finish-file's -- file-dialog! itself can't be driven
                 ;; without a live dialog.
                                    ;; Ruling 4: a simulated Open loads into the active tab, as
                 ;; :open-path! does.
                   :simulate-open-result! (fn [result]
                                            (contained-open-result!
                                             {:cv cv
                                              :open! (fn [p] (let [t (active-tab)]
                                                               (open-path! (:ed t) cv (:path t) p)
                                                               (update-title! (:ed t) win (:path t))))}
                                             result))
                   :simulate-save-result! (fn [result] (let [t (active-tab)]
                                                         (contained-save-result! {:ed (:ed t) :cv cv :win win :path-atom (:path t)} result)))
                 ;; The toolbar's own buttons, so a row can fire their real
                 ;; "clicked" handlers (g/g-signal-emit-by-name).
                   :toolbar {:run run-btn :stop stop-btn :open open-btn :save save-btn}
                 ;; P3.T5: close-choice! is the exact fn the real alert
                 ;; dialog's own completion callback calls; request-close!*
                 ;; runs gtk_window_close, the same path the window's close
                 ;; button/OS chrome uses. closing?/suppress-close? are
                 ;; self-test-only hooks (close-request-fn's docstring).
                   :close-choice! (fn [result]
                                    (close-choice! {:dirty-tabs (filterv (fn [t] (editor/dirty? (:ed t))) @tabs)
                                                    :cv cv :win win :confirmed-close? confirmed-close?
                                                    :select! (fn [t] (select-tab! @*tab-ctx (:id t)))}
                                                   result))
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
                   :complete-alert-for-test! complete-alert!})))))

(defn- run-application!
  "Runs the application's main loop. With an argv of more than the program
  name, hands GApplication a NULL-terminated char** so it emits \"open\"
  with the files; otherwise runs with no argv, so \"activate\" fires. The C
  strings and the array are freed after the loop ends."
  [app argv]
  ;; On Linux X11 GTK writes WM_CLASS from the prgname, and g_application_run
  ;; would set it from argv[0] (or the process name). The launcher's
  ;; StartupWMClass is the app id, so set it first. macOS keeps its value.
  (when (= "Linux" (System/getProperty "os.name"))
    (g/g-set-prgname "net.b12n.jonquil"))
  (if (> (count argv) 1)
    (let [n (count argv)
          arr (ffi/alloc (* 8 (inc n)))
          strs (mapv ffi/string->ptr argv)]
      (try
        (doseq [[i p] (map-indexed vector strs)]
          (ffi/write arr :pointer p (* 8 i)))
        (ffi/write arr :pointer ffi/null (* 8 n))
        (g/g-application-run app n arr)
        (finally
          (doseq [p strs] (ffi/free p))
          (ffi/free arr))))
    (g/g-application-run app 0 ffi/null)))

(defn start!
  "Builds the Jonquil window and blocks in the GTK main loop. opts is
  {:file path-or-nil :dir path-or-nil :cwd path-or-nil :on-ready (fn [ui])
  :settings-path path-or-nil :plugins? bool}, :plugins? false skipping the
  user's plugins (plugins P3), where :dir (a directory launch) and :cwd root
  the file tree (jonquil.file-tree/root-for) and
  :settings-path defaults to (jonquil.settings/settings-path) -- every
  self-test/docs-shots/smoke harness must pass its own isolated path so it
  never touches the user's real settings file (TH.T5). ui is {:file-tree
  :files-btn :tree-paned :toggle-file-tree!* :file-tree-visible?*
  :simulate-folder-result! :tree-menu!* :tree-press!* :tree-submit!*
  :tree-menu-state* :trash-choice! :notebook
  :tabs* :active-tab* :editor
  :console :session :status-label :line-col-label :evaluator* :selection*
  :dialect-label :reset-check :window :find-bar :run!*
  :stop!* :open-path! :save-to! :run-selection!* :run-form!* :toggle-focus!*
  :clear-console!* :clear-last-output!* :zoom!* :set-theme!* :theme-name* :code-css*
  :theme-choice* :system-dark-fn!* :poll-appearance!* :poll-arm-count* :poll-armed?*
  :cycle-theme!* :settings-path* :load-settings!* :settings-path!*
  :save-session!* :restore-session!* :session-live!*
  :close-popovers!* :register-popover!* :quick-doc!* :jump!* :help-popover
  :full-doc-popover
  :rename!* :rename-to!* :rename-popover
  :activate-menu-action!* :repl-activate-menu-action!*
  :complete!* :completion
  :inspector :inspect-last!* :inspect-form!* :inspector-state* :inspector-last-args*
  :source-pane :right-slot :source-jump!* :source-open!* :source-back!* :jump-back!* :source-close!*
  :handle-window-key! :simulate-open-result! :simulate-save-result! :toolbar
  :close-choice! :activate-quit-action!* :request-close!* :closing? :suppress-close?
  :alert-dialog-count :alert-pending? :alert-release!
  :register-alert-choice-for-test! :complete-alert-for-test!}; the
  self-test rows call these functions directly, exactly as the buttons and
  keys do. :evaluator* (D1.T4) reads back the window's current evaluator
  (ev*'s deref, never a second one); :selection* reads back the current
  jonquil.dialect/select result; :dialect-label is the GtkLabel those two
  keep in sync, shown in the status bar. :find-bar is the jonquil.gtk.find-bar
  map (P3.T4); rows call jonquil.gtk.find-bar's own functions on it.
  :cycle-theme!* is what Cmd/Ctrl+Shift+M runs; :settings-path* reads back
  the window's own settings path; :load-settings!* re-runs the startup load
  against any path; :settings-path!* is self-test-only (row-theme-save-
  failure-reported), repointing the window's own settings path. :close-popovers!*
  is what the window keymap's :popover/close-all Escape binding calls (P4.T3); :register-popover!*
  joins a jonquil.gtk.popover map to this window's registry so
  :close-popovers!* knows about it -- P4.T4-T7's completion/quick-doc/
  rename popovers register theirs the same way a self-test row's own test
  popover does. :quick-doc!*/:jump!* (P4.T4) are exactly what the editor's
  own Ctrl+Q/Shift+F1/F12/Ctrl+B key clauses call; :help-popover is the
  jonquil.gtk.help-popover map those two actions show doc text in --
  jonquil.gtk.popover/open? and help-popover/quick-doc-text read it back.
  :full-doc-popover (P3.T2) is the SECOND jonquil.gtk.help-popover map a
  repeated quick-doc press over the same target shows full documentation
  in (ruling 11) -- jonquil.gtk.popover/open? and help-popover/full-doc-
  text read it back the same way.
  :rename!*/:rename-to!* (P4.T7) are exactly what the editor's own
  Shift+F6/F2 clauses and the rename popover's own entry \"activate\"
  signal call; :rename-popover is the jonquil.gtk.help-popover map those
  two act on -- jonquil.gtk.popover/open? and help-popover/rename-entry-
  text/rename-problem-text read it back. :activate-menu-action!*/:repl-
  activate-menu-action!* fire one of the editor's/the console's own P4
  context-menu items by its unprefixed action name, exactly as
  install-context-menu!'s own docstring describes. :palette (slice S8) is
  the first tab's jonquil.gtk.palette map and :open-palette!* what
  Cmd/Ctrl+Shift+P calls to open the active tab's. :load-plugins!* (plugins
  P3) loads the plugin folder now, off the GTK thread, answering the
  console lines, :reload-plugins!* (P4) does what Reload Plugins does and
  waits for it, and :plugins-dir* names that folder. :complete!* (P4.T5) is
  exactly what the editor's own Ctrl+Space clause calls; :completion is
  the jonquil.gtk.completion map it acts on -- jonquil.gtk.popover/open?
  and jonquil.gtk.completion/state read it back.
  :inspector (P2.T2) is the jonquil.gtk.inspector-pane map (P2.T1); rows
  drive its own key/click handling directly, and its :on-def is exactly
  what the def prompt's entry calls. :inspect-last!*/:inspect-form!* are
  exactly what Cmd/Ctrl+I and Cmd/Ctrl+Shift+I (and the editor menu's two
  new items) call; :inspector-state* reads back the window's own
  inspector-state atom; :inspector-last-args* is self-test-only, naming
  the args of the last inspect! request this window actually sent.
  :source-pane is the jonquil.gtk.source-pane map; :right-slot the GtkBox
  holding it and the inspector; the :source-*!* fns are what the panel's
  keys and buttons call.

  :argv (a vector of strings, \"jonquil\" first; jonquil.main/gtk-argv) is
  handed to g_application_run when it has more than the program name, so
  GApplication emits \"open\" with those files instead of \"activate\"."
  [{:keys [argv] :as opts}]
  ;; M6: G_APPLICATION_NON_UNIQUE, so a second launch starts its own
  ;; process instead of forwarding "activate" into an already-running one
  ;; over D-Bus (the default GApplication behaviour for a shared app id).
  (let [app (g/gtk-application-new "net.b12n.jonquil"
                                   (bit-or g/APPLICATION-DEFAULT-FLAGS g/G-APPLICATION-NON-UNIQUE
                                           g/G-APPLICATION-HANDLES-OPEN))]
    (g/g-signal-connect-data app "activate"
                             (callable (fn [app _data] (activate! app opts)) [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    ;; P5a: "open" (GApplication::open, void (*)(GApplication*, GFile**, gint,
    ;; const gchar*, gpointer)) carries Finder's Open With and Dock drops.
    (g/g-signal-connect-data app "open"
                             (callable (fn [app files n _hint _data] (on-app-open! app opts files n))
                                       [:pointer :pointer :int :pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (run-application! app argv)
    nil))
